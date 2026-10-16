package io.github.arttvad9r.typeddroid.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * ADB-compatible SPAKE2 over Ed25519.
 *
 * Android pairing uses BoringSSL SPAKE2 with Alice as the client and Bob as the device/server.
 * This implementation mirrors that protocol so TypedDroid owns the pairing primitive instead
 * of depending on a third-party ADB client at runtime.
 */
internal class AdbSpake2(
    private val role: Role,
    password: ByteArray,
    randomBytes: (Int) -> ByteArray = defaultSecureRandomSource(),
) {
    enum class Role { CLIENT, SERVER }

    private val passwordHash = sha512(password)
    private val passwordScalar = adjustBoringSslPasswordScalar(
        littleEndianToBigInteger(passwordHash).mod(Ed25519.L)
    )
    private val privateScalar: BigInteger
    val message: ByteArray

    private var processed = false

    init {
        require(password.isNotEmpty()) { "ADB SPAKE2 password must not be empty." }
        val random = randomBytes(64)
        require(random.size == 64) { "ADB SPAKE2 random source must return exactly 64 bytes." }
        privateScalar = littleEndianToBigInteger(random).mod(Ed25519.L).shiftLeft(3)
        require(privateScalar.signum() != 0) { "ADB SPAKE2 generated an invalid zero scalar." }

        val publicPoint = Ed25519.scalarMultiply(Ed25519.BASE, privateScalar)
        val maskPoint = Ed25519.scalarMultiply(
            if (role == Role.CLIENT) Ed25519.M else Ed25519.N,
            passwordScalar,
        )
        message = Ed25519.encode(Ed25519.add(publicPoint, maskPoint))
        random.fill(0)
    }

    fun deriveKey(peerMessage: ByteArray): ByteArray {
        check(!processed) { "ADB SPAKE2 peer message can only be processed once." }
        processed = true
        require(peerMessage.size == 32) { "ADB SPAKE2 peer message must be 32 bytes." }

        val peerStar = Ed25519.decode(peerMessage)
        val peerMask = Ed25519.scalarMultiply(
            if (role == Role.CLIENT) Ed25519.N else Ed25519.M,
            passwordScalar,
        )
        val unmaskedPeer = Ed25519.add(peerStar, Ed25519.negate(peerMask))
        val shared = Ed25519.scalarMultiply(unmaskedPeer, privateScalar)
        val sharedEncoded = Ed25519.encode(shared)

        val digest = MessageDigest.getInstance("SHA-512")
        if (role == Role.CLIENT) {
            digest.updateWithLength(ADB_PAIR_CLIENT_NAME)
            digest.updateWithLength(ADB_PAIR_SERVER_NAME)
            digest.updateWithLength(message)
            digest.updateWithLength(peerMessage)
        } else {
            digest.updateWithLength(ADB_PAIR_CLIENT_NAME)
            digest.updateWithLength(ADB_PAIR_SERVER_NAME)
            digest.updateWithLength(peerMessage)
            digest.updateWithLength(message)
        }
        digest.updateWithLength(sharedEncoded)
        digest.updateWithLength(passwordHash)
        return digest.digest()
    }

    private companion object {
        val ADB_PAIR_CLIENT_NAME = "adb pair client\u0000".encodeToByteArray()
        val ADB_PAIR_SERVER_NAME = "adb pair server\u0000".encodeToByteArray()
    }
}

private fun adjustBoringSslPasswordScalar(reduced: BigInteger): BigInteger {
    // BoringSSL keeps compatibility with its historical SPAKE2 mask behavior by adding
    // successive multiples of the prime subgroup order until the low three bits are zero.
    // The additions preserve the scalar modulo L while clearing the cofactor contribution
    // of the fixed M/N points. Conditions are intentionally evaluated after each addition.
    var scalar = reduced
    var order = Ed25519.L
    if (scalar.testBit(0)) scalar = scalar.add(order)
    order = order.shiftLeft(1)
    if (scalar.testBit(1)) scalar = scalar.add(order)
    order = order.shiftLeft(1)
    if (scalar.testBit(2)) scalar = scalar.add(order)
    check(!scalar.testBit(0) && !scalar.testBit(1) && !scalar.testBit(2)) {
        "Failed to normalize ADB SPAKE2 password scalar."
    }
    return scalar
}

private object Ed25519 {
    val P: BigInteger = BIG_INTEGER_TWO.pow(255).subtract(BigInteger.valueOf(19))
    val L: BigInteger = BIG_INTEGER_TWO.pow(252)
        .add(BigInteger("27742317777372353535851937790883648493"))
    private val D: BigInteger = mod(
        BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P))
    )
    private val SQRT_M1: BigInteger = BIG_INTEGER_TWO.modPow(
        P.subtract(BigInteger.ONE).divide(BigInteger.valueOf(4)),
        P,
    )

    val BASE = Point.fromAffine(
        BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
        BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"),
    )

    // BoringSSL's fixed SPAKE2 masking points in standard compressed Ed25519 form.
    val M: Point = decode(hexToBytes("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))
    val N: Point = decode(hexToBytes("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))

    fun add(p: Point, q: Point): Point {
        val a = mod(p.y.subtract(p.x).multiply(q.y.subtract(q.x)))
        val b = mod(p.y.add(p.x).multiply(q.y.add(q.x)))
        val c = mod(BIG_INTEGER_TWO.multiply(D).multiply(p.t).multiply(q.t))
        val d = mod(BIG_INTEGER_TWO.multiply(p.z).multiply(q.z))
        val e = mod(b.subtract(a))
        val f = mod(d.subtract(c))
        val g = mod(d.add(c))
        val h = mod(b.add(a))
        return Point(
            x = mod(e.multiply(f)),
            y = mod(g.multiply(h)),
            z = mod(f.multiply(g)),
            t = mod(e.multiply(h)),
        )
    }

    private fun double(p: Point): Point {
        val a = mod(p.x.multiply(p.x))
        val b = mod(p.y.multiply(p.y))
        val c = mod(BIG_INTEGER_TWO.multiply(p.z).multiply(p.z))
        val d = mod(a.negate())
        val e = mod(p.x.add(p.y).pow(2).subtract(a).subtract(b))
        val g = mod(d.add(b))
        val f = mod(g.subtract(c))
        val h = mod(d.subtract(b))
        return Point(
            x = mod(e.multiply(f)),
            y = mod(g.multiply(h)),
            z = mod(f.multiply(g)),
            t = mod(e.multiply(h)),
        )
    }

    fun negate(p: Point): Point = Point(
        x = mod(p.x.negate()),
        y = p.y,
        z = p.z,
        t = mod(p.t.negate()),
    )

    fun scalarMultiply(point: Point, scalar: BigInteger): Point {
        require(scalar.signum() >= 0) { "Negative Ed25519 scalar." }
        var n = scalar
        var addend = point
        var result = Point.IDENTITY
        while (n.signum() != 0) {
            if (n.testBit(0)) result = add(result, addend)
            addend = double(addend)
            n = n.shiftRight(1)
        }
        return result
    }

    fun encode(point: Point): ByteArray {
        val affine = point.affine()
        val bytes = bigIntegerToLittleEndian(affine.y, 32)
        if (affine.x.testBit(0)) bytes[31] = (bytes[31].toInt() or 0x80).toByte()
        return bytes
    }

    fun decode(encoded: ByteArray): Point {
        require(encoded.size == 32) { "Ed25519 point must be 32 bytes." }
        val copy = encoded.copyOf()
        val xOdd = copy[31].toInt() and 0x80 != 0
        copy[31] = (copy[31].toInt() and 0x7f).toByte()
        val y = littleEndianToBigInteger(copy)
        require(y < P) { "Non-canonical Ed25519 point encoding." }

        val y2 = mod(y.multiply(y))
        val numerator = mod(y2.subtract(BigInteger.ONE))
        val denominator = mod(D.multiply(y2).add(BigInteger.ONE))
        require(denominator.signum() != 0) { "Invalid Ed25519 point." }
        val x2 = mod(numerator.multiply(denominator.modInverse(P)))
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).divide(BigInteger.valueOf(8)), P)
        if (mod(x.multiply(x)) != x2) x = mod(x.multiply(SQRT_M1))
        require(mod(x.multiply(x)) == x2) { "Encoded Ed25519 point is not on the curve." }
        if (x.testBit(0) != xOdd) x = P.subtract(x)
        require(!(x == BigInteger.ZERO && xOdd)) { "Non-canonical Ed25519 sign bit." }
        return Point.fromAffine(x, y)
    }

    private fun mod(value: BigInteger): BigInteger = value.mod(P)

    data class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger,
        val t: BigInteger,
    ) {
        fun affine(): AffinePoint {
            require(z.signum() != 0) { "Invalid projective Ed25519 point." }
            val inverse = z.modInverse(P)
            return AffinePoint(
                x = x.multiply(inverse).mod(P),
                y = y.multiply(inverse).mod(P),
            )
        }

        companion object {
            val IDENTITY = fromAffine(BigInteger.ZERO, BigInteger.ONE)

            fun fromAffine(x: BigInteger, y: BigInteger) = Point(
                x = x.mod(P),
                y = y.mod(P),
                z = BigInteger.ONE,
                t = x.multiply(y).mod(P),
            )
        }
    }

    data class AffinePoint(val x: BigInteger, val y: BigInteger)
}

private fun MessageDigest.updateWithLength(data: ByteArray) {
    update(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(data.size.toLong()).array())
    update(data)
}

private fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)

private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

private fun bigIntegerToLittleEndian(value: BigInteger, size: Int): ByteArray {
    require(value.signum() >= 0) { "Negative integer." }
    var encoded = value.toByteArray()
    if (encoded.size > 1 && encoded[0] == 0.toByte()) encoded = encoded.copyOfRange(1, encoded.size)
    require(encoded.size <= size) { "Integer does not fit fixed-length field." }
    val out = ByteArray(size)
    encoded.indices.forEach { index -> out[index] = encoded[encoded.lastIndex - index] }
    return out
}

private fun hexToBytes(value: String): ByteArray {
    require(value.length % 2 == 0)
    return ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

private fun defaultSecureRandomSource(): (Int) -> ByteArray {
    val random = SecureRandom()
    return { size -> ByteArray(size).also(random::nextBytes) }
}

private val BIG_INTEGER_TWO = BigInteger.valueOf(2)

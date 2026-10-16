package io.github.arttvad9r.typeddroid.adb

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date
import javax.security.auth.x500.X500Principal

/** Android's fixed 2048-bit RSA public-key encoding used by ADB authorization. */
internal object AdbRsaPublicKeyEncoder {
    const val MODULUS_BYTES = 256
    const val ENCODED_BYTES = 3 * 4 + 2 * MODULUS_BYTES

    fun encode(publicKey: RSAPublicKey): ByteArray {
        require(publicKey.modulus.bitLength() in 2041..2048) { "ADB requires a 2048-bit RSA modulus." }
        require(publicKey.publicExponent == BigInteger.valueOf(3) ||
            publicKey.publicExponent == BigInteger.valueOf(65537)) {
            "ADB RSA exponent must be 3 or 65537."
        }

        val n = publicKey.modulus
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0Inverse = n.mod(r32).modInverse(r32)
        val n0Inv = r32.subtract(n0Inverse).mod(r32)
        val rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8)
            .modPow(BIG_INTEGER_TWO, n)

        val out = java.nio.ByteBuffer
            .allocate(ENCODED_BYTES)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.putInt(MODULUS_BYTES / 4)
        out.putInt(n0Inv.toLong().toInt())
        out.put(toLittleEndianFixed(n, MODULUS_BYTES))
        out.put(toLittleEndianFixed(rr, MODULUS_BYTES))
        out.putInt(publicKey.publicExponent.toInt())
        return out.array()
    }

    fun publicKeyString(publicKey: RSAPublicKey, comment: String = DEFAULT_COMMENT): String {
        require(comment.isNotBlank() && comment.length <= 128) { "Invalid ADB public-key comment." }
        require(comment.all { it.code in 0x21..0x7e && !it.isWhitespace() }) {
            "ADB public-key comment must be printable ASCII without whitespace."
        }
        return Base64.getEncoder().encodeToString(encode(publicKey)) + " " + comment
    }

    private fun toLittleEndianFixed(value: BigInteger, size: Int): ByteArray {
        require(value.signum() >= 0) { "Negative integers cannot be encoded as ADB key fields." }
        var bigEndian = value.toByteArray()
        if (bigEndian.size > 1 && bigEndian[0] == 0.toByte()) {
            bigEndian = bigEndian.copyOfRange(1, bigEndian.size)
        }
        require(bigEndian.size <= size) { "Integer does not fit the ADB key field." }
        val result = ByteArray(size)
        bigEndian.indices.forEach { index ->
            result[index] = bigEndian[bigEndian.lastIndex - index]
        }
        return result
    }

    private const val DEFAULT_COMMENT = "typeddroid@android"
}

internal data class AndroidAdbIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
    val publicKey: RSAPublicKey,
) {
    val adbPublicKey: String
        get() = AdbRsaPublicKeyEncoder.publicKeyString(publicKey)

    /**
     * Legacy ADB AUTH signs an already-computed 20-byte SHA-1 digest. Java's SHA1withRSA
     * would hash it a second time, so we feed the ASN.1 SHA-1 DigestInfo to NONEwithRSA.
     */
    fun signAuthToken(token: ByteArray): ByteArray {
        require(token.size == ADB_AUTH_TOKEN_BYTES) { "ADB AUTH token must be 20 bytes." }
        val signature = Signature.getInstance("NONEwithRSA")
        signature.initSign(privateKey)
        signature.update(SHA1_DIGEST_INFO_PREFIX)
        signature.update(token)
        return signature.sign()
    }
}

/** Persistent ADB identity owned by TypedDroid and stored in Android Keystore. */
internal class AndroidAdbIdentityStore(
    private val alias: String = KEY_ALIAS,
) {
    fun getOrCreate(): AndroidAdbIdentity {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(alias)) generateIdentity()

        val privateKey = keyStore.getKey(alias, null) as? PrivateKey
            ?: error("TypedDroid ADB private key is unavailable.")
        val certificate = keyStore.getCertificate(alias) as? X509Certificate
            ?: error("TypedDroid ADB certificate is unavailable.")
        val publicKey = certificate.publicKey as? RSAPublicKey
            ?: error("TypedDroid ADB certificate does not contain an RSA key.")
        return AndroidAdbIdentity(privateKey, certificate, publicKey)
    }

    fun reset() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
    }

    private fun generateIdentity() {
        val now = System.currentTimeMillis()
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setKeySize(2048)
                .setDigests(
                    KeyProperties.DIGEST_NONE,
                    KeyProperties.DIGEST_SHA1,
                    KeyProperties.DIGEST_SHA256,
                )
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=TypedDroid ADB"))
                .setCertificateSerialNumber(BigInteger.ONE)
                .setCertificateNotBefore(Date(now - ONE_DAY_MILLIS))
                .setCertificateNotAfter(Date(now + TEN_YEARS_MILLIS))
                .setUserAuthenticationRequired(false)
                .build()
        )
        generator.generateKeyPair()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "typed_droid_adb_identity_v1"
        const val ONE_DAY_MILLIS = 24L * 60 * 60 * 1000
        const val TEN_YEARS_MILLIS = 10L * 365 * ONE_DAY_MILLIS
    }
}

private const val ADB_AUTH_TOKEN_BYTES = 20
private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
    0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
    0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
)

private val BIG_INTEGER_TWO = BigInteger.valueOf(2)

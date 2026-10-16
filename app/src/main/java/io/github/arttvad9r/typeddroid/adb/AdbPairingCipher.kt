package io.github.arttvad9r.typeddroid.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AOSP-compatible AES-128-GCM channel created after SPAKE2 pairing succeeds. */
internal class AdbPairingCipher(keyMaterial: ByteArray) {
    private val key = SecretKeySpec(
        hkdfSha256(
            inputKeyMaterial = keyMaterial,
            info = HKDF_INFO,
            outputLength = 16,
        ),
        "AES",
    )
    private var encryptSequence = 0L
    private var decryptSequence = 0L

    init {
        require(keyMaterial.isNotEmpty()) { "ADB pairing key material must not be empty." }
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        require(plaintext.isNotEmpty()) { "ADB pairing plaintext must not be empty." }
        val result = crypt(Cipher.ENCRYPT_MODE, plaintext, nonce(encryptSequence))
        encryptSequence = incrementSequence(encryptSequence)
        return result
    }

    fun decrypt(ciphertextAndTag: ByteArray): ByteArray {
        require(ciphertextAndTag.size > GCM_TAG_BYTES) { "ADB pairing ciphertext is too short." }
        val result = crypt(Cipher.DECRYPT_MODE, ciphertextAndTag, nonce(decryptSequence))
        decryptSequence = incrementSequence(decryptSequence)
        return result
    }

    private fun crypt(mode: Int, input: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, key, GCMParameterSpec(GCM_TAG_BYTES * 8, nonce))
        return cipher.doFinal(input)
    }

    private fun nonce(sequence: Long): ByteArray {
        val nonce = ByteArray(GCM_NONCE_BYTES)
        ByteBuffer.wrap(nonce).order(ByteOrder.LITTLE_ENDIAN).putLong(sequence)
        return nonce
    }

    private fun incrementSequence(value: Long): Long {
        require(value != -1L) { "ADB pairing AES-GCM sequence exhausted." }
        return value + 1
    }

    private companion object {
        val HKDF_INFO = "adb pairing_auth aes-128-gcm key".encodeToByteArray()
        const val GCM_NONCE_BYTES = 12
        const val GCM_TAG_BYTES = 16
    }
}

internal fun hkdfSha256(
    inputKeyMaterial: ByteArray,
    info: ByteArray,
    outputLength: Int,
    salt: ByteArray = ByteArray(0),
): ByteArray {
    require(inputKeyMaterial.isNotEmpty()) { "HKDF input key material must not be empty." }
    require(outputLength in 1..(255 * 32)) { "HKDF output length is invalid." }

    val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
    val extract = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
    }.doFinal(inputKeyMaterial)

    val output = ByteArray(outputLength)
    var previous = ByteArray(0)
    var offset = 0
    var counter = 1
    while (offset < outputLength) {
        val block = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(extract, "HmacSHA256"))
            update(previous)
            update(info)
            update(counter.toByte())
        }.doFinal()
        previous.fill(0)
        previous = block
        val copyLength = minOf(previous.size, outputLength - offset)
        previous.copyInto(output, destinationOffset = offset, endIndex = copyLength)
        offset += copyLength
        counter += 1
    }
    extract.fill(0)
    previous.fill(0)
    return output
}

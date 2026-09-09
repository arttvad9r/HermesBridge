package io.github.arttvad9r.hermesbridge

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val DEVICE_KEY_ALIAS = "hermes_bridge_device_identity_v1"

class AndroidKeystoreDeviceIdentity {
    private val keyStore: KeyStore
        get() = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    fun publicKeyBase64(): String {
        ensureKeyExists()
        val certificate = requireNotNull(keyStore.getCertificate(DEVICE_KEY_ALIAS))
        return Base64.encodeToString(certificate.publicKey.encoded, Base64.NO_WRAP)
    }

    fun sign(payload: ByteArray): String {
        ensureKeyExists()
        val entry = keyStore.getEntry(DEVICE_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: error("Hermes Bridge device key is unavailable.")
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(entry.privateKey)
            update(payload)
            sign()
        }
        return Base64.encodeToString(signature, Base64.NO_WRAP)
    }

    private fun ensureKeyExists() {
        if (keyStore.containsAlias(DEVICE_KEY_ALIAS)) return

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE_PROVIDER,
        )
        val spec = KeyGenParameterSpec.Builder(
            DEVICE_KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }
}

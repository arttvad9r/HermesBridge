package io.github.arttvad9r.hermesbridge

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val DEVICE_KEY_ALIAS = "hermes_bridge_device_identity_v1"

enum class DeviceIdentityFailureMode {
    TRANSIENT,
    REPAIR_REQUIRED,
}

interface DeviceIdentity {
    fun hasStoredKey(): Boolean
    fun publicKeyBase64(): String
    fun sign(payload: ByteArray): String
    fun reset()
}

class DeviceIdentityUnavailableException(
    message: String,
    val mode: DeviceIdentityFailureMode = DeviceIdentityFailureMode.TRANSIENT,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class AndroidKeystoreDeviceIdentity : DeviceIdentity {
    private val keyStore: KeyStore
        get() = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    override fun hasStoredKey(): Boolean = keyStore.containsAlias(DEVICE_KEY_ALIAS)

    override fun publicKeyBase64(): String {
        return try {
            ensureKeyExists()
            val certificate = keyStore.getCertificate(DEVICE_KEY_ALIAS)
                ?: throw DeviceIdentityUnavailableException(
                    "Hermes Bridge device certificate is unavailable.",
                    DeviceIdentityFailureMode.REPAIR_REQUIRED,
                )
            Base64.encodeToString(certificate.publicKey.encoded, Base64.NO_WRAP)
        } catch (error: DeviceIdentityUnavailableException) {
            throw error
        } catch (error: Throwable) {
            throw DeviceIdentityUnavailableException(
                "Hermes Bridge could not read its Android Keystore identity.",
                DeviceIdentityFailureMode.TRANSIENT,
                error,
            )
        }
    }

    override fun sign(payload: ByteArray): String {
        val store = try {
            keyStore
        } catch (error: Throwable) {
            throw DeviceIdentityUnavailableException(
                "Android Keystore is unavailable. Try reconnecting again.",
                DeviceIdentityFailureMode.TRANSIENT,
                error,
            )
        }
        if (!store.containsAlias(DEVICE_KEY_ALIAS)) {
            throw DeviceIdentityUnavailableException(
                "Hermes Bridge device identity is missing. Pair the phone again.",
                DeviceIdentityFailureMode.REPAIR_REQUIRED,
            )
        }

        return try {
            val entry = store.getEntry(DEVICE_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                ?: throw DeviceIdentityUnavailableException(
                    "Hermes Bridge device key is unavailable. Pair the phone again.",
                    DeviceIdentityFailureMode.REPAIR_REQUIRED,
                )
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(entry.privateKey)
                update(payload)
                sign()
            }
            Base64.encodeToString(signature, Base64.NO_WRAP)
        } catch (error: DeviceIdentityUnavailableException) {
            throw error
        } catch (error: KeyPermanentlyInvalidatedException) {
            throw DeviceIdentityUnavailableException(
                "Hermes Bridge device key was permanently invalidated. Pair the phone again.",
                DeviceIdentityFailureMode.REPAIR_REQUIRED,
                error,
            )
        } catch (error: Throwable) {
            throw DeviceIdentityUnavailableException(
                "Hermes Bridge could not use the Android Keystore key right now. Try reconnecting again.",
                DeviceIdentityFailureMode.TRANSIENT,
                error,
            )
        }
    }

    override fun reset() {
        try {
            val store = keyStore
            if (store.containsAlias(DEVICE_KEY_ALIAS)) {
                store.deleteEntry(DEVICE_KEY_ALIAS)
            }
        } catch (error: Throwable) {
            throw DeviceIdentityUnavailableException(
                "Hermes Bridge could not reset its Android Keystore identity.",
                DeviceIdentityFailureMode.REPAIR_REQUIRED,
                error,
            )
        }
    }

    private fun ensureKeyExists() {
        if (hasStoredKey()) return

        try {
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
        } catch (error: Throwable) {
            throw DeviceIdentityUnavailableException(
                "Hermes Bridge could not create its Android Keystore identity.",
                DeviceIdentityFailureMode.TRANSIENT,
                error,
            )
        }
    }
}

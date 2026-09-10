package io.github.arttvad9r.hermesbridge

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

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

internal class DeviceKeyInvalidatedException(cause: Throwable? = null) :
    GeneralSecurityException("Stored device key was permanently invalidated.", cause)

internal interface DeviceKeyStoreBackend {
    fun containsAlias(alias: String): Boolean
    fun publicKeyEncoded(alias: String): ByteArray?
    fun sign(alias: String, payload: ByteArray): ByteArray?
    fun generateEcSigningKey(alias: String)
    fun deleteEntry(alias: String)
}

internal class AndroidDeviceKeyStoreBackend : DeviceKeyStoreBackend {
    private val keyStore: KeyStore
        get() = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    override fun containsAlias(alias: String): Boolean = keyStore.containsAlias(alias)

    override fun publicKeyEncoded(alias: String): ByteArray? =
        keyStore.getCertificate(alias)?.publicKey?.encoded

    override fun sign(alias: String, payload: ByteArray): ByteArray? {
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry ?: return null
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initSign(entry.privateKey)
                update(payload)
                sign()
            }
        } catch (error: KeyPermanentlyInvalidatedException) {
            throw DeviceKeyInvalidatedException(error)
        }
    }

    override fun generateEcSigningKey(alias: String) {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE_PROVIDER,
        )
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    override fun deleteEntry(alias: String) {
        keyStore.deleteEntry(alias)
    }
}

class AndroidKeystoreDeviceIdentity internal constructor(
    private val keyStoreBackend: DeviceKeyStoreBackend,
    private val keyAlias: String,
) : DeviceIdentity {
    constructor() : this(AndroidDeviceKeyStoreBackend(), DEVICE_KEY_ALIAS)

    override fun hasStoredKey(): Boolean = keyStoreBackend.containsAlias(keyAlias)

    override fun publicKeyBase64(): String {
        return try {
            ensureKeyExists()
            val encodedPublicKey = keyStoreBackend.publicKeyEncoded(keyAlias)
                ?: throw DeviceIdentityUnavailableException(
                    "Hermes Bridge device certificate is unavailable.",
                    DeviceIdentityFailureMode.REPAIR_REQUIRED,
                )
            Base64.getEncoder().encodeToString(encodedPublicKey)
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
        val hasKey = try {
            keyStoreBackend.containsAlias(keyAlias)
        } catch (error: Throwable) {
            throw DeviceIdentityUnavailableException(
                "Android Keystore is unavailable. Try reconnecting again.",
                DeviceIdentityFailureMode.TRANSIENT,
                error,
            )
        }
        if (!hasKey) {
            throw DeviceIdentityUnavailableException(
                "Hermes Bridge device identity is missing. Pair the phone again.",
                DeviceIdentityFailureMode.REPAIR_REQUIRED,
            )
        }

        return try {
            val signature = keyStoreBackend.sign(keyAlias, payload)
                ?: throw DeviceIdentityUnavailableException(
                    "Hermes Bridge device key is unavailable. Pair the phone again.",
                    DeviceIdentityFailureMode.REPAIR_REQUIRED,
                )
            Base64.getEncoder().encodeToString(signature)
        } catch (error: DeviceIdentityUnavailableException) {
            throw error
        } catch (error: DeviceKeyInvalidatedException) {
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
            if (keyStoreBackend.containsAlias(keyAlias)) {
                keyStoreBackend.deleteEntry(keyAlias)
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
            keyStoreBackend.generateEcSigningKey(keyAlias)
        } catch (error: Throwable) {
            throw DeviceIdentityUnavailableException(
                "Hermes Bridge could not create its Android Keystore identity.",
                DeviceIdentityFailureMode.TRANSIENT,
                error,
            )
        }
    }
}

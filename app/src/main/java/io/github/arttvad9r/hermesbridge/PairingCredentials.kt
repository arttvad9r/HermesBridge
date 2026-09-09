package io.github.arttvad9r.hermesbridge

class PairingCredentialManager(
    private val pairingStore: PairingRecordStore,
    private val identity: DeviceIdentity,
) {
    fun validateResume(): Result<Unit> {
        if (pairingStore.deviceId() == null) {
            return Result.failure(IllegalStateException("Device is not paired yet."))
        }

        val hasIdentity = try {
            identity.hasStoredKey()
        } catch (error: Throwable) {
            return Result.failure(
                DeviceIdentityUnavailableException(
                    "Android Keystore is temporarily unavailable. Try reconnecting again.",
                    error,
                )
            )
        }

        if (!hasIdentity) {
            pairingStore.clear()
            return Result.failure(
                DeviceIdentityUnavailableException(
                    "The stored device identity is missing. Pair the phone with Hermes again."
                )
            )
        }

        return Result.success(Unit)
    }

    fun invalidateAfterSigningFailure(): Result<Unit> {
        pairingStore.clear()
        return runCatching { identity.reset() }
    }
}

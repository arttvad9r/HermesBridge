package io.github.arttvad9r.hermesbridge

internal fun requiresDeviceIdentityRepair(error: Throwable): Boolean =
    error is DeviceIdentityUnavailableException &&
        error.mode == DeviceIdentityFailureMode.REPAIR_REQUIRED

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
                    message = "Android Keystore is temporarily unavailable. Try reconnecting again.",
                    mode = DeviceIdentityFailureMode.TRANSIENT,
                    cause = error,
                )
            )
        }

        if (!hasIdentity) {
            pairingStore.clear()
            return Result.failure(
                DeviceIdentityUnavailableException(
                    message = "The stored device identity is missing. Pair the phone with Hermes again.",
                    mode = DeviceIdentityFailureMode.REPAIR_REQUIRED,
                )
            )
        }

        return Result.success(Unit)
    }

    fun invalidateForRepair(): Result<Unit> {
        pairingStore.clear()
        return runCatching { identity.reset() }
    }
}

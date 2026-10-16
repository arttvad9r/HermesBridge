package io.github.arttvad9r.typeddroid.adb

private const val HOST_BANNER_PREFIX = "host::"
private const val DEVICE_BANNER_PREFIX = "device::"
private const val FEATURES_KEY = "features"

internal data class AdbDeviceBanner(
    val properties: Map<String, String>,
    val features: Set<String>,
) {
    val productName: String?
        get() = properties["ro.product.name"]

    val productModel: String?
        get() = properties["ro.product.model"]

    val productDevice: String?
        get() = properties["ro.product.device"]
}

internal fun buildHostConnectBanner(features: Set<String>): ByteArray {
    val safeFeatures = features
        .map(::validateBannerToken)
        .distinct()
        .sorted()
    val properties = if (safeFeatures.isEmpty()) {
        ""
    } else {
        "$FEATURES_KEY=${safeFeatures.joinToString(",")};"
    }
    return "$HOST_BANNER_PREFIX$properties".encodeToByteArray()
}

internal fun parseDeviceConnectBanner(payload: ByteArray): AdbDeviceBanner {
    require(payload.isNotEmpty()) { "ADB CNXN banner is empty." }
    val content = if (payload.last() == 0.toByte()) payload.copyOf(payload.size - 1) else payload
    require(content.none { it == 0.toByte() }) { "ADB CNXN banner contains an embedded NUL." }

    val raw = content.decodeToString(throwOnInvalidSequence = true)
    require(raw.startsWith(DEVICE_BANNER_PREFIX)) { "Unsupported ADB CNXN banner prefix." }
    val body = raw.removePrefix(DEVICE_BANNER_PREFIX)

    val properties = linkedMapOf<String, String>()
    if (body.isNotEmpty()) {
        body.split(';').forEach { entry ->
            if (entry.isEmpty()) return@forEach
            val separator = entry.indexOf('=')
            require(separator > 0 && separator < entry.lastIndex) {
                "Malformed ADB CNXN banner property."
            }
            val key = validateBannerToken(entry.substring(0, separator))
            val value = entry.substring(separator + 1)
            require(value.length <= MAX_BANNER_VALUE_LENGTH && value.all(::isSafeBannerChar)) {
                "Invalid ADB CNXN banner property value."
            }
            require(properties.put(key, value) == null) {
                "Duplicate ADB CNXN banner property."
            }
        }
    }

    val features = properties[FEATURES_KEY]
        ?.split(',')
        ?.filter { it.isNotEmpty() }
        ?.map(::validateBannerToken)
        ?.toSet()
        ?: emptySet()

    return AdbDeviceBanner(
        properties = properties.toMap(),
        features = features,
    )
}

private fun validateBannerToken(value: String): String {
    require(value.isNotEmpty() && value.length <= MAX_BANNER_TOKEN_LENGTH) {
        "Invalid ADB banner token length."
    }
    require(value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }) {
        "Invalid ADB banner token."
    }
    return value
}

private fun isSafeBannerChar(char: Char): Boolean =
    char.code in 0x20..0x7e && char != ';' && char != '\u0000'

private const val MAX_BANNER_TOKEN_LENGTH = 128
private const val MAX_BANNER_VALUE_LENGTH = 1024

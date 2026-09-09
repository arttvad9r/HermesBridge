package io.github.arttvad9r.hermesbridge

object PairingCodeValidator {
    private val format = Regex("^[A-Z0-9]{4}-[A-Z0-9]{4}$")

    fun normalize(value: String): String =
        value.trim().uppercase()

    fun isValid(value: String): Boolean =
        format.matches(normalize(value))
}

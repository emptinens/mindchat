package com.mindchat.app

/**
 * Shared decision logic behind the [MindChatGateway] contract.
 *
 * Both implementations of the public interface (`NativeMindChatGateway` and
 * `PreviewMindChatGateway`) run exactly the same normalization and validation
 * rules here, so the preview cannot drift from the native behavior: the same
 * validation that guards the FFI registration call also guards the
 * JVM-runnable preview used in debug builds and tests. State-transition and
 * fallback rules (account selection after deletion, stall thresholds) live in
 * [GatewayPolicy] and snapshot-to-UI mapping in [GatewayMapping].
 */

/** UI-safe refusal detail for an invalid XEP-0077 registration request. */
internal sealed interface RegistrationRefusal {
    data object PasswordRequired : RegistrationRefusal

    data object InvalidJid : RegistrationRefusal

    /** The exact UI-safe detail string both gateway implementations return. */
    fun toUiDetail(): String = when (this) {
        PasswordRequired -> "password required"
        InvalidJid -> "invalid JID"
    }
}

/** Outcome of validating raw registration fields. */
internal sealed interface RegistrationValidation {
    data class Valid(val input: RegistrationInput) : RegistrationValidation

    data class Refused(val refusal: RegistrationRefusal) : RegistrationValidation
}

/** Normalized registration inputs, shared by both gateway implementations. */
internal data class RegistrationInput(
    val username: String,
    val server: String,
    val displayName: String,
    val password: String,
    val fullJid: String,
)

/**
 * Validates and normalizes raw registration fields. A [RegistrationValidation.Refused]
 * carries the exact UI-safe detail the gateway must surface; a
 * [RegistrationValidation.Valid] carries inputs that are trimmed and have the
 * display-name fallback applied (username when blank).
 */
internal fun validateRegistration(
    jid: String,
    server: String,
    displayName: String,
    password: String,
): RegistrationValidation {
    if (password.isEmpty()) return RegistrationValidation.Refused(RegistrationRefusal.PasswordRequired)
    val normalizedJid = jid.trim()
    val username = normalizedJid.substringBefore('@')
    if (username.isBlank()) return RegistrationValidation.Refused(RegistrationRefusal.InvalidJid)
    return RegistrationValidation.Valid(
        RegistrationInput(
            username = username,
            server = server.trim(),
            displayName = displayName.trim().ifBlank { username },
            password = password,
            fullJid = normalizedJid,
        ),
    )
}

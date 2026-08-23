package com.mindchat.app

import com.mindchat.core.FfiDiagnosticsReport
import com.mindchat.core.FfiDisconnectKind

/**
 * Shared diagnostics decision logic (ROADMAP 6.5), the diagnostics twin of the
 * [GatewayInput] pattern.
 *
 * Both [MindChatGateway] implementations and every UI surface derive
 * UI-visible values from the raw FFI contract with exactly the same pure
 * functions here, so the preview cannot drift from the native behavior:
 *
 * - [disconnectBucket] / [disconnectLabelRes] classify a typed disconnect
 *   reason into one of the four ROADMAP buckets and produce the distinct
 *   bucket label shown above the detail prose. Prose stays display-only;
 *   nothing ever parses `connectionError` for control flow.
 * - [shouldShowQuarantineNotice] decides when the one-time quarantine notice
 *   is visible from gateway state.
 * - [serializeDiagnosticsReport] renders the opt-in export JSON. It writes
 *   exactly the report's own fields, structurally redacted on the Rust side;
 *   the Kotlin serializer adds nothing of its own (enforced by
 *   `DiagnosticsExportTest`).
 *
 * The file stays Android-free (no `Context`, no framework imports; `R.string`
 * is the app's own generated constant table, already used by
 * [GatewayInput.catalogRows]).
 */

/**
 * Typed ROADMAP 6.5 disconnect buckets rendered as distinct labels.
 *
 * - [TERMINAL]: credentials were rejected; retrying with the same credentials
 *   cannot succeed (SASL failure).
 * - [CONFIGURATION]: the server refused the stream/session; the account or
 *   server configuration is wrong.
 * - [RETRYABLE]: the link was lost; a user-triggered retry is meaningful.
 * - [INTERNAL_PERSISTENCE]: no dedicated bucket; treated as an internal
 *   problem so the user can report it via diagnostics export.
 * - [NEUTRAL]: the user explicitly disconnected; never rendered as an error.
 */
internal enum class DisconnectBucket {
    TERMINAL,
    RETRYABLE,
    CONFIGURATION,
    INTERNAL_PERSISTENCE,
    NEUTRAL,
}

/**
 * Classifies a typed disconnect reason into its ROADMAP 6.5 bucket.
 *
 * Decision recorded here: `Cancelled` maps to [DisconnectBucket.NEUTRAL] and
 * `Unknown` maps to [DisconnectBucket.INTERNAL_PERSISTENCE] (terminal +
 * internal: the account will not auto-recover, and the honest label is an
 * internal problem, not a user-actionable one). `AuthenticationFailed` is
 * [DisconnectBucket.TERMINAL] (same credentials cannot succeed),
 * `ServerRefused` is [DisconnectBucket.CONFIGURATION], and `NetworkLost` is
 * [DisconnectBucket.RETRYABLE].
 */
internal fun disconnectBucket(kind: FfiDisconnectKind): DisconnectBucket = when (kind) {
    FfiDisconnectKind.AUTHENTICATION_FAILED -> DisconnectBucket.TERMINAL
    FfiDisconnectKind.TLS_VERIFICATION_FAILED -> DisconnectBucket.TERMINAL
    FfiDisconnectKind.SERVER_REFUSED -> DisconnectBucket.CONFIGURATION
    FfiDisconnectKind.NETWORK_LOST -> DisconnectBucket.RETRYABLE
    FfiDisconnectKind.CANCELLED -> DisconnectBucket.NEUTRAL
    FfiDisconnectKind.UNKNOWN -> DisconnectBucket.INTERNAL_PERSISTENCE
}

/**
 * The distinct label resource for a typed disconnect reason, rendered above
 * the detail prose in the top bar, the account drawer rows and error dialogs.
 * Returns `null` for [DisconnectBucket.NEUTRAL] so a user-initiated
 * disconnect never surfaces as an error.
 */
internal fun disconnectLabelRes(kind: FfiDisconnectKind): Int? = when (kind) {
    FfiDisconnectKind.AUTHENTICATION_FAILED -> R.string.disconnect_kind_authentication_failed
    FfiDisconnectKind.TLS_VERIFICATION_FAILED -> R.string.disconnect_kind_tls_verification_failed
    FfiDisconnectKind.SERVER_REFUSED -> R.string.disconnect_kind_server_refused
    FfiDisconnectKind.NETWORK_LOST -> R.string.disconnect_kind_network_lost
    FfiDisconnectKind.UNKNOWN -> R.string.disconnect_kind_unknown
    FfiDisconnectKind.CANCELLED -> null
}

/**
 * Whether the one-time quarantine notice is visible. The notice shows exactly
 * when the core reported a quarantined local state and the user has not
 * dismissed it; dismissal is device-local state owned by the gateway.
 */
internal fun shouldShowQuarantineNotice(quarantined: Boolean, dismissed: Boolean): Boolean =
    quarantined && !dismissed

/**
 * Serializes the opt-in diagnostics report to compact JSON.
 *
 * The key set is exactly the [FfiDiagnosticsReport] field set, nothing more:
 * the Rust side structurally excludes passwords, message bodies, avatars and
 * JIDs, and this serializer adds no derived fields of its own (the field-name
 * audit in `DiagnosticsExportTest` pins the exact key set). Values are the
 * raw report fields; `null` optionals render as JSON `null`.
 *
 * Hand-rolled instead of `org.json` (not available to the JVM unit tests and
 * a dependency the project does not need for eleven fixed fields).
 */
internal fun serializeDiagnosticsReport(report: FfiDiagnosticsReport): String = buildString {
    append('{')
    append("\"accountCount\":").append(report.accountCount)
    append(",\"contactCount\":").append(report.contactCount)
    append(",\"conversationCount\":").append(report.conversationCount)
    append(",\"messageCount\":").append(report.messageCount)
    append(",\"reactionCount\":").append(report.reactionCount)
    append(",\"statePath\":").append(jsonString(report.statePath))
    append(",\"stateSizeBytes\":").append(report.stateSizeBytes?.toString() ?: "null")
    append(",\"stateSchemaVersion\":").append(report.stateSchemaVersion?.toString() ?: "null")
    append(",\"stateQuarantined\":").append(report.stateQuarantined)
    append(",\"stateLastSavedAtEpochMs\":").append(report.stateLastSavedAtEpochMs?.toString() ?: "null")
    append(",\"stateLastLoadedAtEpochMs\":").append(report.stateLastLoadedAtEpochMs?.toString() ?: "null")
    append('}')
}

/** Renders one JSON string value (or `null`), escaping quotes, backslashes and control characters. */
private fun jsonString(value: String?): String {
    if (value == null) return "null"
    val builder = StringBuilder(value.length + 2)
    builder.append('"')
    value.forEach { char ->
        when (char) {
            '"' -> builder.append("\\\"")
            '\\' -> builder.append("\\\\")
            '\b' -> builder.append("\\b")
            '\u000C' -> builder.append("\\f")
            '\n' -> builder.append("\\n")
            '\r' -> builder.append("\\r")
            '\t' -> builder.append("\\t")
            else -> if (char.code < 0x20) {
                builder.append("\\u").append(String.format("%04x", char.code))
            } else {
                builder.append(char)
            }
        }
    }
    builder.append('"')
    return builder.toString()
}

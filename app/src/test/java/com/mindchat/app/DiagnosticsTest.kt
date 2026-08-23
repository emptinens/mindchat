package com.mindchat.app

import com.mindchat.core.FfiDiagnosticsReport
import com.mindchat.core.FfiDisconnectKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure diagnostics decision logic (ROADMAP 6.5): the disconnect kind -> bucket
 * -> label mapping (100% variant coverage), the quarantine-notice decision,
 * and the opt-in export serializer with its redaction guarantees.
 *
 * These are the rules both [MindChatGateway] implementations share; the UI
 * renders labels and notices only through them, and the export path writes
 * exactly what [serializeDiagnosticsReport] produces, nothing more.
 */
class DiagnosticsTest {

    // --- disconnect kind -> bucket -> label (100% variant coverage) ----------

    @Test
    fun disconnectBucket_coversEveryVariant() {
        val expected = mapOf(
            FfiDisconnectKind.AUTHENTICATION_FAILED to DisconnectBucket.TERMINAL,
            FfiDisconnectKind.TLS_VERIFICATION_FAILED to DisconnectBucket.TERMINAL,
            FfiDisconnectKind.SERVER_REFUSED to DisconnectBucket.CONFIGURATION,
            FfiDisconnectKind.NETWORK_LOST to DisconnectBucket.RETRYABLE,
            FfiDisconnectKind.CANCELLED to DisconnectBucket.NEUTRAL,
            FfiDisconnectKind.UNKNOWN to DisconnectBucket.INTERNAL_PERSISTENCE,
        )
        // One assertion per variant; a future FFI variant fails this loop.
        assertEquals("every FFI variant must be classified", expected.keys.toSet(), FfiDisconnectKind.entries.toSet())
        FfiDisconnectKind.entries.forEach { kind ->
            assertEquals("bucket for $kind", expected[kind], disconnectBucket(kind))
        }
    }

    @Test
    fun disconnectLabelRes_coversEveryVariant() {
        val expected = mapOf(
            FfiDisconnectKind.AUTHENTICATION_FAILED to R.string.disconnect_kind_authentication_failed,
            FfiDisconnectKind.TLS_VERIFICATION_FAILED to R.string.disconnect_kind_tls_verification_failed,
            FfiDisconnectKind.SERVER_REFUSED to R.string.disconnect_kind_server_refused,
            FfiDisconnectKind.NETWORK_LOST to R.string.disconnect_kind_network_lost,
            FfiDisconnectKind.UNKNOWN to R.string.disconnect_kind_unknown,
            // A user-initiated disconnect is neutral: never rendered as an error.
            FfiDisconnectKind.CANCELLED to null,
        )
        assertEquals(
            "every FFI variant must have a label decision",
            expected.keys.toSet(),
            FfiDisconnectKind.entries.toSet(),
        )
        FfiDisconnectKind.entries.forEach { kind ->
            assertEquals("label for $kind", expected[kind], disconnectLabelRes(kind))
        }
    }

    @Test
    fun disconnectLabelsAreDistinctPerBucket() {
        val labels = FfiDisconnectKind.entries.mapNotNull { disconnectLabelRes(it) }
        assertEquals("each rendered kind has its own distinct label", labels.size, labels.toSet().size)
    }

    // --- quarantine notice ----------------------------------------------------

    @Test
    fun quarantineNotice_showsOnlyWhileQuarantinedAndNotDismissed() {
        assertTrue(shouldShowQuarantineNotice(quarantined = true, dismissed = false))
        assertFalse(shouldShowQuarantineNotice(quarantined = true, dismissed = true))
        assertFalse(shouldShowQuarantineNotice(quarantined = false, dismissed = false))
        assertFalse(shouldShowQuarantineNotice(quarantined = false, dismissed = true))
    }

    // --- export serializer ----------------------------------------------------

    @Test
    fun serializeDiagnosticsReport_writesCompactGoldenJson() {
        val report = FfiDiagnosticsReport(
            accountCount = 3uL,
            contactCount = 7uL,
            conversationCount = 2uL,
            messageCount = 41uL,
            reactionCount = 9uL,
            statePath = "/data/user/0/com.mindchat.app/files/mindchat_state.json",
            stateSizeBytes = 12_345uL,
            stateSchemaVersion = 1u,
            stateQuarantined = false,
            stateLastSavedAtEpochMs = 1_720_000_000_000uL,
            stateLastLoadedAtEpochMs = 1_720_000_000_100uL,
        )

        val json = serializeDiagnosticsReport(report)

        assertEquals(
            "compact, field-ordered, no whitespace",
            "{\"accountCount\":3,\"contactCount\":7,\"conversationCount\":2,\"messageCount\":41," +
                "\"reactionCount\":9,\"statePath\":\"/data/user/0/com.mindchat.app/files/mindchat_state.json\"," +
                "\"stateSizeBytes\":12345,\"stateSchemaVersion\":1,\"stateQuarantined\":false," +
                "\"stateLastSavedAtEpochMs\":1720000000000,\"stateLastLoadedAtEpochMs\":1720000000100}",
            json,
        )
    }

    @Test
    fun serializeDiagnosticsReport_rendersNullsAndQuarantineFlag() {
        val report = FfiDiagnosticsReport(
            accountCount = 0uL,
            contactCount = 0uL,
            conversationCount = 0uL,
            messageCount = 0uL,
            reactionCount = 0uL,
            statePath = null,
            stateSizeBytes = null,
            stateSchemaVersion = null,
            stateQuarantined = true,
            stateLastSavedAtEpochMs = null,
            stateLastLoadedAtEpochMs = null,
        )

        val json = serializeDiagnosticsReport(report)

        assertEquals(
            "{\"accountCount\":0,\"contactCount\":0,\"conversationCount\":0,\"messageCount\":0," +
                "\"reactionCount\":0,\"statePath\":null,\"stateSizeBytes\":null,\"stateSchemaVersion\":null," +
                "\"stateQuarantined\":true,\"stateLastSavedAtEpochMs\":null,\"stateLastLoadedAtEpochMs\":null}",
            json,
        )
    }

    @Test
    fun serializeDiagnosticsReport_fieldNameAuditAddsNothingBeyondTheReport() {
        val report = FfiDiagnosticsReport(
            accountCount = 5uL,
            contactCount = 1uL,
            conversationCount = 2uL,
            messageCount = 99uL,
            reactionCount = 0uL,
            statePath = "a,\"quoted\":path",
            stateSizeBytes = 8_192uL,
            stateSchemaVersion = 1u,
            stateQuarantined = true,
            stateLastSavedAtEpochMs = 1uL,
            stateLastLoadedAtEpochMs = 2uL,
        )

        val json = serializeDiagnosticsReport(report)
        val keys = jsonObjectKeys(json)

        assertEquals(
            "the serializer emits exactly the report's own fields and nothing more",
            setOf(
                "accountCount",
                "contactCount",
                "conversationCount",
                "messageCount",
                "reactionCount",
                "statePath",
                "stateSizeBytes",
                "stateSchemaVersion",
                "stateQuarantined",
                "stateLastSavedAtEpochMs",
                "stateLastLoadedAtEpochMs",
            ),
            keys,
        )
        val bannedFieldNames = listOf(
            "password", "secret", "token", "credential", "body", "avatar", "jid", "key",
        )
        bannedFieldNames.forEach { banned ->
            assertFalse("no $banned field may exist in the export", banned in keys)
        }
    }

    @Test
    fun serializeDiagnosticsReport_escapesStringValues() {
        val report = FfiDiagnosticsReport(
            accountCount = 0uL,
            contactCount = 0uL,
            conversationCount = 0uL,
            messageCount = 0uL,
            reactionCount = 0uL,
            statePath = "quote\"back\\slash\nnewline\ttab",
            stateSizeBytes = null,
            stateSchemaVersion = null,
            stateQuarantined = false,
            stateLastSavedAtEpochMs = null,
            stateLastLoadedAtEpochMs = null,
        )

        val json = serializeDiagnosticsReport(report)

        assertTrue("quotes must be escaped", json.contains("\\\""))
        assertTrue("backslashes must be escaped", json.contains("\\\\"))
        assertTrue("newlines must be escaped", json.contains("\\n"))
        assertTrue("tabs must be escaped", json.contains("\\t"))
        assertFalse("no raw control characters may be written", json.any { it.code < 0x20 && it != '\n' })
    }

    /** Extracts the top-level object key set from [serializeDiagnosticsReport] output. */
    private fun jsonObjectKeys(json: String): Set<String> {
        val keys = mutableSetOf<String>()
        var i = 0
        require(json.getOrNull(i) == '{') { "expected an object" }
        i++
        while (i < json.length) {
            while (i < json.length && (json[i] == ',' || json[i].isWhitespace())) i++
            if (json.getOrNull(i) == '}') break
            require(json.getOrNull(i) == '"') { "expected a key at $i" }
            i++
            val key = StringBuilder()
            while (i < json.length && json[i] != '"') {
                if (json[i] == '\\') i++
                key.append(json[i])
                i++
            }
            i++ // closing quote
            while (i < json.length && json[i].isWhitespace()) i++
            require(json.getOrNull(i) == ':') { "expected ':' at $i" }
            keys += key.toString()
            i = skipJsonValue(json, i + 1)
        }
        return keys
    }

    private fun skipJsonValue(json: String, start: Int): Int {
        var i = start
        while (i < json.length && json[i].isWhitespace()) i++
        return when (json.getOrNull(i)) {
            '"' -> {
                i++
                while (i < json.length) {
                    if (json[i] == '\\') {
                        i += 2
                    } else if (json[i] == '"') {
                        return i + 1
                    } else {
                        i++
                    }
                }
                i
            }

            '{' -> {
                var depth = 1
                i++
                while (i < json.length && depth > 0) {
                    if (json[i] == '"') {
                        i = skipJsonValue(json, i)
                    } else {
                        if (json[i] == '{') depth++
                        if (json[i] == '}') depth--
                        i++
                    }
                }
                i
            }

            '[' -> {
                var depth = 1
                i++
                while (i < json.length && depth > 0) {
                    if (json[i] == '"') {
                        i = skipJsonValue(json, i)
                    } else {
                        if (json[i] == '[') depth++
                        if (json[i] == ']') depth--
                        i++
                    }
                }
                i
            }

            else -> {
                while (i < json.length && json[i] !in charArrayOf(',', '}')) i++
                i
            }
        }
    }
}

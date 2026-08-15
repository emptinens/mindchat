package com.mindchat.app

import com.mindchat.core.FfiDiagnosticsReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance tests for the 0.1.8 diagnostics contract (ROADMAP 6.5) driven
 * through the public [MindChatGateway]. `PreviewMindChatGateway` is the
 * JVM-runnable contract implementation, so every assertion here pins
 * observable behavior the UI and the native gateway are expected to match:
 * an honest, secret-free diagnostics report; quarantine notice state that
 * comes from the report and gateway-owned dismissal; and dismissal that
 * survives a restart over the same stores.
 *
 * The native-only parts (a real `FfiDiagnosticsReport` from the core) require
 * the packaged ABI library and a device; the preview seeds its report at
 * construction and never fabricates values.
 */
class GatewayDiagnosticsContractTest {

    // --- default preview report ----------------------------------------------

    @Test
    fun defaultReportIsHonestAndSecretFree() {
        val g = PreviewMindChatGateway()

        val report = g.diagnosticsReport()

        assertEquals("a preview without a core reports zero records", 0uL, report.accountCount)
        assertEquals(0uL, report.contactCount)
        assertEquals(0uL, report.conversationCount)
        assertEquals(0uL, report.messageCount)
        assertEquals(0uL, report.reactionCount)
        assertNull(report.statePath)
        assertNull(report.stateSizeBytes)
        assertNull(report.stateSchemaVersion)
        assertFalse("a fresh preview is never quarantined", report.stateQuarantined)
        assertNull(report.stateLastSavedAtEpochMs)
        assertNull(report.stateLastLoadedAtEpochMs)
        assertFalse("the report type and state must carry no secrets", report.toString().contains("s3cret"))
        assertFalse(g.state.toString().contains("s3cret"))
    }

    @Test
    fun defaultStateShowsNoQuarantineNotice() {
        val g = PreviewMindChatGateway()
        assertFalse(g.state.diagnosticsQuarantined)
        assertFalse(g.state.diagnosticsNoticeDismissed)
        assertFalse(
            shouldShowQuarantineNotice(g.state.diagnosticsQuarantined, g.state.diagnosticsNoticeDismissed),
        )
    }

    // --- quarantine notice flow ----------------------------------------------

    @Test
    fun quarantinedSeedShowsNoticeUntilDismissed() {
        val g = PreviewMindChatGateway(seedDiagnosticsReport = quarantinedReport())

        assertTrue("the notice state comes from the report", g.state.diagnosticsQuarantined)
        assertFalse(g.state.diagnosticsNoticeDismissed)
        assertTrue(
            shouldShowQuarantineNotice(g.state.diagnosticsQuarantined, g.state.diagnosticsNoticeDismissed),
        )

        g.dismissDiagnosticsNotice()

        assertTrue(g.state.diagnosticsNoticeDismissed)
        assertFalse(
            "dismissal hides the notice",
            shouldShowQuarantineNotice(g.state.diagnosticsQuarantined, g.state.diagnosticsNoticeDismissed),
        )
    }

    @Test
    fun cleanSeedNeverShowsTheNoticeEvenAfterDismissal() {
        val g = PreviewMindChatGateway()
        g.dismissDiagnosticsNotice()
        assertTrue(g.state.diagnosticsNoticeDismissed)
        assertFalse(
            shouldShowQuarantineNotice(g.state.diagnosticsQuarantined, g.state.diagnosticsNoticeDismissed),
        )
    }

    @Test
    fun dismissalSurvivesAGatewayRestartOverTheSamePreferences() {
        val preferences = InMemoryMindChatPreferences()
        val first = PreviewMindChatGateway(
            preferences,
            seedDiagnosticsReport = quarantinedReport(),
        )
        assertTrue(
            shouldShowQuarantineNotice(first.state.diagnosticsQuarantined, first.state.diagnosticsNoticeDismissed),
        )
        first.dismissDiagnosticsNotice()
        assertTrue(preferences.readQuarantineNoticeDismissed())

        val second = PreviewMindChatGateway(
            preferences,
            seedDiagnosticsReport = quarantinedReport(),
        )

        assertTrue("the dismissal is remembered by the store", second.state.diagnosticsNoticeDismissed)
        assertFalse(
            "a dismissed notice stays hidden across restarts",
            shouldShowQuarantineNotice(second.state.diagnosticsQuarantined, second.state.diagnosticsNoticeDismissed),
        )
    }

    // --- export path contract -------------------------------------------------

    @Test
    fun serializeReportFromTheGatewayAddsNothingBeyondTheReport() {
        val g = PreviewMindChatGateway(seedDiagnosticsReport = quarantinedReport())
        val json = serializeDiagnosticsReport(g.diagnosticsReport())
        assertTrue(json.contains("\"stateQuarantined\":true"))
        // Field-name audit at the contract boundary: only report fields, no
        // password-like names anywhere in the exported text.
        assertFalse(json.contains("password"))
        assertFalse(json.contains("secret"))
        assertFalse(json.contains("body"))
        assertFalse(json.contains("avatar"))
        assertFalse(json.contains("jid"))
    }

    private fun quarantinedReport() = FfiDiagnosticsReport(
        accountCount = 1uL,
        contactCount = 2uL,
        conversationCount = 3uL,
        messageCount = 4uL,
        reactionCount = 5uL,
        statePath = "/data/user/0/com.mindchat.app/files/mindchat_state.json",
        stateSizeBytes = 1_024uL,
        stateSchemaVersion = 1u,
        stateQuarantined = true,
        stateLastSavedAtEpochMs = 1_700_000_000_000uL,
        stateLastLoadedAtEpochMs = null,
    )
}

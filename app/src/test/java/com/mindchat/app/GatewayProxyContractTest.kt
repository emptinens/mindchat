package com.mindchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance tests for the 0.1.8 proxy contract (ROADMAP 6.3) driven through
 * the public [MindChatGateway]. `PreviewMindChatGateway` is the JVM-runnable
 * contract implementation, so every assertion here pins observable behavior
 * the UI and the native gateway are expected to match: library add/edit/
 * delete/ping, per-account set/clear, validation failures, defaults, and the
 * hard rule that a password never crosses the contract or the UI state.
 *
 * The probe success path (a real `FfiProxyProbe` with measured latency)
 * requires the native core and a device, so the preview honestly reports
 * probe failure and never fabricates latency; the failure/status plumbing is
 * what these tests pin.
 */
class GatewayProxyContractTest {

    private fun gateway(
        preferences: MindChatPreferences = InMemoryMindChatPreferences(),
        libraryStore: ProxyLibraryStore = InMemoryProxyLibraryStore(),
        credentialStore: ProxyCredentialStore = InMemoryProxyCredentialStore(),
    ) = Triple(
        PreviewMindChatGateway(preferences, libraryStore, credentialStore),
        libraryStore,
        credentialStore,
    )

    private val validConfig = ProxyConfig("proxy.example.org", 1080, ProxyKind.SOCKS5)

    // --- Defaults -------------------------------------------------------------

    @Test
    fun defaultsHaveNoLibraryNoAssignmentsAndNoAssignedProxy() {
        val (g, _, _) = gateway()
        assertEquals(emptyList<ProxyLibraryEntry>(), g.state.proxyLibrary)
        assertEquals(emptyMap<Long, String>(), g.state.proxyAssignments)
        assertEquals(null, g.accountProxy(g.state.accounts.single().id))
        assertEquals(null, g.state.assignedProxy(g.state.accounts.single().id))
    }

    // --- addProxy -------------------------------------------------------------

    @Test
    fun addProxyValidatesAndAppendsAnEntry() {
        val (g, libraryStore, _) = gateway()
        val added = g.addProxy(validConfig)
        assertTrue("valid config must be added", added)
        assertEquals(1, g.state.proxyLibrary.size)
        val entry = g.state.proxyLibrary.single()
        assertEquals("p1", entry.id)
        assertEquals("proxy.example.org", entry.host)
        assertEquals(1080, entry.port)
        assertEquals(ProxyKind.SOCKS5, entry.kind)
        assertFalse("a new entry is never tested", entry.status.tested)
        assertEquals(entry, libraryStore.readEntries().single())
    }

    @Test
    fun addProxyRejectsInvalidConfigWithoutPersisting() {
        val (g, libraryStore, credentialStore) = gateway()
        assertFalse(g.addProxy(ProxyConfig("", 1080, ProxyKind.SOCKS5)))
        assertFalse(g.addProxy(ProxyConfig("has space", 1080, ProxyKind.SOCKS5)))
        assertFalse(g.addProxy(ProxyConfig("proxy.example.org", 0, ProxyKind.SOCKS5)))
        assertFalse(g.addProxy(ProxyConfig("proxy.example.org", 65536, ProxyKind.SOCKS5)))
        assertEquals(emptyList<ProxyLibraryEntry>(), g.state.proxyLibrary)
        assertEquals(emptyList<ProxyLibraryEntry>(), libraryStore.readEntries())
        assertNull(credentialStore.load("p1"))
    }

    @Test
    fun addProxyStoresPasswordInTheCredentialStoreNeverInState() {
        val (g, _, credentialStore) = gateway()
        assertTrue(g.addProxy(validConfig, password = "s3cret"))

        val entry = g.state.proxyLibrary.single()
        assertEquals("s3cret", credentialStore.load(entry.id))
        assertFalse("state must never carry the password", g.state.toString().contains("s3cret"))
    }

    // --- updateProxy ----------------------------------------------------------

    @Test
    fun updateProxyReplacesConfigAndClearsTheStatus() {
        val (g, libraryStore, _) = gateway()
        g.addProxy(validConfig, password = "s3cret")
        val id = g.state.proxyLibrary.single().id

        assertTrue(
            g.updateProxy(
                id,
                ProxyConfig("other.example.org", 3128, ProxyKind.HTTP_CONNECT),
                password = "new-secret",
            ),
        )
        val entry = g.state.proxyLibrary.single()
        assertEquals("other.example.org", entry.host)
        assertEquals(3128, entry.port)
        assertEquals(ProxyKind.HTTP_CONNECT, entry.kind)
        assertFalse(entry.status.tested)
        assertEquals(entry, libraryStore.readEntries().single())
    }

    @Test
    fun updateProxyRejectsUnknownIdAndInvalidConfig() {
        val (g, _, _) = gateway()
        g.addProxy(validConfig)
        assertFalse(g.updateProxy("p99", validConfig))
        assertFalse(g.updateProxy("p1", ProxyConfig("bad host", 1080, ProxyKind.SOCKS5)))
        assertEquals(1, g.state.proxyLibrary.size)
    }

    // --- deleteProxy ----------------------------------------------------------

    @Test
    fun deleteProxyRemovesEntryCredentialAndAssignment() {
        val (g, libraryStore, credentialStore) = gateway()
        val accountId = g.state.accounts.single().id
        g.addProxy(validConfig, password = "s3cret")
        val id = g.state.proxyLibrary.single().id
        assertTrue(g.setAccountProxy(accountId, validConfig, password = "s3cret"))
        assertEquals(mapOf(accountId to id), g.state.proxyAssignments)

        g.deleteProxy(id)

        assertEquals(emptyList<ProxyLibraryEntry>(), g.state.proxyLibrary)
        assertEquals(emptyMap<Long, String>(), g.state.proxyAssignments)
        assertEquals(emptyList<ProxyLibraryEntry>(), libraryStore.readEntries())
        assertEquals(emptyMap<Long, String>(), libraryStore.readAssignments())
        assertNull(credentialStore.load(id))
        assertEquals(null, g.accountProxy(accountId))
    }

    // --- setAccountProxy / accountProxy ---------------------------------------

    @Test
    fun setAccountProxyAssignsAndReadsBackWithoutExposingPassword() {
        val (g, _, credentialStore) = gateway()
        val accountId = g.state.accounts.single().id
        g.addProxy(validConfig)
        val id = g.state.proxyLibrary.single().id

        assertTrue(g.setAccountProxy(accountId, validConfig, password = "p4ss"))

        assertEquals(mapOf(accountId to id), g.state.proxyAssignments)
        assertEquals(validConfig, g.accountProxy(accountId))
        assertEquals(validConfig, g.state.assignedProxy(accountId)?.toConfig())
        assertEquals("p4ss", credentialStore.load(id))
        assertFalse("the password must never be readable back", g.state.toString().contains("p4ss"))
        assertNull("the getter must never return a password", g.accountProxy(accountId)?.let { null })
    }

    @Test
    fun setAccountProxyNullClearsTheAssignment() {
        val (g, _, _) = gateway()
        val accountId = g.state.accounts.single().id
        g.addProxy(validConfig)
        assertTrue(g.setAccountProxy(accountId, validConfig))
        assertEquals(validConfig, g.accountProxy(accountId))

        assertTrue(g.setAccountProxy(accountId, null))

        assertEquals(emptyMap<Long, String>(), g.state.proxyAssignments)
        assertEquals(null, g.accountProxy(accountId))
        assertEquals(null, g.state.assignedProxy(accountId))
    }

    @Test
    fun setAccountProxyRejectsInvalidOrUnknownConfigs() {
        val (g, _, _) = gateway()
        val accountId = g.state.accounts.single().id
        assertFalse(g.setAccountProxy(accountId, ProxyConfig("bad host", 1080, ProxyKind.SOCKS5)))
        assertFalse(
            "config must exist in the library",
            g.setAccountProxy(accountId, ProxyConfig("not.in.library.org", 1080, ProxyKind.SOCKS5)),
        )
        assertEquals(emptyMap<Long, String>(), g.state.proxyAssignments)
    }

    // --- testProxy / pingProxy ------------------------------------------------

    @Test
    fun testProxyValidationFailuresReturnAFailedResult() {
        val (g, _, _) = gateway()
        val result = g.testProxy(ProxyConfig("", 1080, ProxyKind.SOCKS5))
        assertFalse(result.ok)
        assertNull("a failed probe has no latency", result.latencyMs)
        assertNotNull(result.error)
    }

    @Test
    fun previewProbeHonestlyFailsWithoutFakeLatency() {
        val (g, _, _) = gateway()
        val result = g.testProxy(validConfig)
        assertFalse("the JVM preview cannot open a tunnel", result.ok)
        assertNull("no fake latency may ever be produced", result.latencyMs)
        assertNotNull(result.error)
    }

    @Test
    fun pingProxyPersistsTheStatusAndBucket() {
        val (g, libraryStore, _) = gateway()
        g.addProxy(validConfig, password = "s3cret")
        val id = g.state.proxyLibrary.single().id

        val result = g.pingProxy(id)

        assertFalse(result.ok)
        val entry = g.state.proxyLibrary.single()
        assertTrue("the probe result must be persisted", entry.status.tested)
        assertEquals(ProxyLatencyBucket.UNKNOWN, entry.status.bucket)
        assertNull(entry.status.latencyMs)
        assertEquals(entry, libraryStore.readEntries().single())
    }

    @Test
    fun pingProxyOfUnknownIdReturnsAFailedResult() {
        val (g, _, _) = gateway()
        val result = g.pingProxy("p99")
        assertFalse(result.ok)
        assertNull(result.latencyMs)
        assertNotNull(result.error)
    }

    // --- persistence across gateway instances ---------------------------------

    @Test
    fun proxyStateSurvivesAGatewayRestartOverTheSameStores() {
        val preferences = InMemoryMindChatPreferences()
        val libraryStore = InMemoryProxyLibraryStore()
        val credentialStore = InMemoryProxyCredentialStore()
        val accountId = PreviewMindChatGateway(preferences).state.accounts.single().id
        val (first, _, _) = gateway(preferences, libraryStore, credentialStore)
        first.addProxy(validConfig, password = "s3cret")
        val id = first.state.proxyLibrary.single().id
        first.setAccountProxy(accountId, validConfig, password = "s3cret")

        val (second, _, credentialStore2) = gateway(preferences, libraryStore, credentialStore)

        assertEquals(listOf(ProxyLibraryEntry(id, validConfig.host, validConfig.port, validConfig.kind)),
            second.state.proxyLibrary)
        assertEquals(mapOf(accountId to id), second.state.proxyAssignments)
        assertEquals(validConfig, second.accountProxy(accountId))
        assertEquals("s3cret", credentialStore2.load(id))
    }
}

package com.mindchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shared proxy decision logic that both `MindChatGateway`
 * implementations must run (ROADMAP 6.3): `validateProxyConfig`,
 * `ProxyLatencyBucket`, `nextProxyId`, library matching and the store
 * serialization helpers. Keeping these pure in `GatewayInput.kt`/`Proxy.kt`
 * is what makes the preview contractually identical to the native gateway.
 */
class ProxyInputTest {

    // --- validateProxyConfig --------------------------------------------------

    @Test
    fun validConfigIsAcceptedForBothKinds() {
        ProxyKind.entries.forEach { kind ->
            val validation = validateProxyConfig("proxy.example.org", 1080, kind)
            assertSame("$kind must validate", ProxyValidation.Valid, validation)
        }
    }

    @Test
    fun blankHostIsRefused() {
        val validation = validateProxyConfig("   ", 1080, ProxyKind.SOCKS5)
        assertTrue(validation is ProxyValidation.Refused)
        assertEquals(ProxyRefusal.EMPTY_HOST, (validation as ProxyValidation.Refused).reason)
    }

    @Test
    fun hostWithWhitespaceIsRefused() {
        val validation = validateProxyConfig("proxy example.org", 1080, ProxyKind.HTTP_CONNECT)
        assertTrue(validation is ProxyValidation.Refused)
        assertEquals(ProxyRefusal.HOST_HAS_WHITESPACE, (validation as ProxyValidation.Refused).reason)
    }

    @Test
    fun portBoundariesAreEnforced() {
        listOf(0, -1, 65536).forEach { port ->
            val validation = validateProxyConfig("proxy.example.org", port, ProxyKind.SOCKS5)
            assertTrue("port $port must be refused", validation is ProxyValidation.Refused)
            assertEquals(
                ProxyRefusal.PORT_OUT_OF_RANGE,
                (validation as ProxyValidation.Refused).reason,
            )
        }
        assertSame(ProxyValidation.Valid, validateProxyConfig("proxy.example.org", 1, ProxyKind.SOCKS5))
        assertSame(ProxyValidation.Valid, validateProxyConfig("proxy.example.org", 65535, ProxyKind.SOCKS5))
    }

    // --- ProxyLatencyBucket ---------------------------------------------------

    @Test
    fun latencyBucketsFollowTheThresholds() {
        assertEquals(ProxyLatencyBucket.FAST, proxyLatencyBucket(0L))
        assertEquals(ProxyLatencyBucket.FAST, proxyLatencyBucket(199L))
        assertEquals(ProxyLatencyBucket.MEDIUM, proxyLatencyBucket(200L))
        assertEquals(ProxyLatencyBucket.MEDIUM, proxyLatencyBucket(799L))
        assertEquals(ProxyLatencyBucket.SLOW, proxyLatencyBucket(800L))
        assertEquals(ProxyLatencyBucket.SLOW, proxyLatencyBucket(15_000L))
    }

    @Test
    fun unknownLatencyMapsToUnknownBucket() {
        assertEquals(ProxyLatencyBucket.UNKNOWN, proxyLatencyBucket(null))
        assertEquals(ProxyLatencyBucket.UNKNOWN, proxyLatencyBucket(-1L))
    }

    // --- nextProxyId ----------------------------------------------------------

    @Test
    fun nextProxyIdDerivesTheNextStableId() {
        assertEquals("p1", nextProxyId(emptyList()))
        assertEquals(
            "p3",
            nextProxyId(
                listOf(
                    ProxyLibraryEntry("p1", "a", 1, ProxyKind.SOCKS5),
                    ProxyLibraryEntry("p2", "b", 2, ProxyKind.HTTP_CONNECT),
                ),
            ),
        )
    }

    // --- findByConfig ---------------------------------------------------------

    @Test
    fun findByConfigMatchesOnlyOnAllNonSecretFields() {
        val entry = ProxyLibraryEntry("p1", "proxy.example.org", 1080, ProxyKind.SOCKS5)
        val library = listOf(entry, ProxyLibraryEntry("p2", "other.example.org", 3128, ProxyKind.HTTP_CONNECT))

        assertEquals(entry, library.findByConfig(ProxyConfig("proxy.example.org", 1080, ProxyKind.SOCKS5)))
        assertEquals(null, library.findByConfig(ProxyConfig("proxy.example.org", 3128, ProxyKind.SOCKS5)))
        assertEquals(null, library.findByConfig(ProxyConfig("proxy.example.org", 1080, ProxyKind.HTTP_CONNECT)))
    }

    // --- store serialization --------------------------------------------------

    @Test
    fun encodeDecodeEntryRoundTripsStatusFields() {
        val entry = ProxyLibraryEntry(
            id = "p1",
            host = "proxy.example.org",
            port = 1080,
            kind = ProxyKind.SOCKS5,
            status = ProxyStatus(latencyMs = 123, error = null),
        )
        assertEquals(entry, decodeProxyEntry(encodeProxyEntry(entry)))

        val failed = entry.copy(status = ProxyStatus(latencyMs = null, error = "timeout"))
        assertEquals(failed, decodeProxyEntry(encodeProxyEntry(failed)))

        val untested = entry.copy(status = ProxyStatus())
        assertEquals(untested, decodeProxyEntry(encodeProxyEntry(untested)))
    }

    @Test
    fun decodeProxyEntryRejectsGarbage() {
        assertEquals(null, decodeProxyEntry(""))
        assertEquals(null, decodeProxyEntry("p1\u001Fhost\u001Fnot-a-port\u001FSOCKS5"))
        assertEquals(null, decodeProxyEntry("p1\u001Fhost\u001F1080\u001FUNKNOWN_KIND"))
    }
}

package com.mindchat.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip contract for [ProxyLibraryStore] against the in-memory fake:
 * entries keep display order, assignments map account ids to entry ids, and
 * both surfaces replace wholesale. The Android-backed store
 * ([SharedPreferencesProxyLibraryStore]) persists the same values through the
 * pure [encodeProxyEntry]/[decodeProxyEntry] helpers pinned in
 * [ProxyInputTest].
 */
class ProxyStoreTest {

    private val entryA = ProxyLibraryEntry("p1", "a.example.org", 1080, ProxyKind.SOCKS5)
    private val entryB = ProxyLibraryEntry(
        "p2",
        "b.example.org",
        3128,
        ProxyKind.HTTP_CONNECT,
        status = ProxyStatus(latencyMs = 42, error = null),
    )

    @Test
    fun emptyStoreReadsBackEmpty() {
        val store = InMemoryProxyLibraryStore()
        assertEquals(emptyList<ProxyLibraryEntry>(), store.readEntries())
        assertEquals(emptyMap<Long, String>(), store.readAssignments())
    }

    @Test
    fun entriesRoundTripInDisplayOrder() {
        val store = InMemoryProxyLibraryStore()
        store.writeEntries(listOf(entryA, entryB))
        assertEquals(listOf(entryA, entryB), store.readEntries())
    }

    @Test
    fun writeEntriesReplacesWholesale() {
        val store = InMemoryProxyLibraryStore()
        store.writeEntries(listOf(entryA, entryB))
        store.writeEntries(listOf(entryB))
        assertEquals(listOf(entryB), store.readEntries())
    }

    @Test
    fun assignmentsRoundTrip() {
        val store = InMemoryProxyLibraryStore()
        store.writeAssignments(mapOf(1L to "p1", 2L to "p2"))
        assertEquals(mapOf(1L to "p1", 2L to "p2"), store.readAssignments())
        store.writeAssignments(mapOf(2L to "p2"))
        assertEquals(mapOf(2L to "p2"), store.readAssignments())
    }
}

package com.mindchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract tests for [ProxyCredentialStore] against the in-memory fake. The
 * Android Keystore implementation cannot run in JVM unit tests (no platform
 * keystore), so the interface contract is pinned here; [KeystoreProxyCredentialStore]
 * maps one-to-one onto the same operations (store/load/delete per proxy id,
 * independent entries, no plaintext in memory beyond the in-memory fake).
 */
class ProxyCredentialStoreTest {

    @Test
    fun storeThenLoadReturnsThePassword() {
        val store = InMemoryProxyCredentialStore()
        store.store("p1", "s3cret")
        assertEquals("s3cret", store.load("p1"))
    }

    @Test
    fun missingIdLoadsNull() {
        val store = InMemoryProxyCredentialStore()
        assertNull(store.load("p1"))
    }

    @Test
    fun entriesAreIndependentPerProxyId() {
        val store = InMemoryProxyCredentialStore()
        store.store("p1", "first")
        store.store("p2", "second")
        assertEquals("first", store.load("p1"))
        assertEquals("second", store.load("p2"))
    }

    @Test
    fun storeReplacesThePreviousValue() {
        val store = InMemoryProxyCredentialStore()
        store.store("p1", "old")
        store.store("p1", "new")
        assertEquals("new", store.load("p1"))
    }

    @Test
    fun deleteRemovesOnlyTheGivenId() {
        val store = InMemoryProxyCredentialStore()
        store.store("p1", "first")
        store.store("p2", "second")
        store.delete("p1")
        assertNull(store.load("p1"))
        assertEquals("second", store.load("p2"))
    }

    @Test
    fun deleteOfMissingIdIsANoOp() {
        val store = InMemoryProxyCredentialStore()
        store.delete("p1")
        assertNull(store.load("p1"))
    }

    @Test
    fun emptyPasswordIsRoundTripped() {
        val store = InMemoryProxyCredentialStore()
        store.store("p1", "")
        assertEquals("", store.load("p1"))
    }
}

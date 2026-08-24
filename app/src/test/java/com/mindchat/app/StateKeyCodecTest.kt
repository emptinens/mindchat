package com.mindchat.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM contract tests for the wrapped-state-key codec (0.1.9 storage encryption). */
class StateKeyCodecTest {
    @Test
    fun roundTripsArbitraryBytes() {
        val bytes = ByteArray(48) { (it * 37 + 11).toByte() }
        assertArrayEquals(bytes, StateKeyCodec.decodeOrNull(StateKeyCodec.encode(bytes)))
    }

    @Test
    fun roundTripsEmptyAndKeySizedInputs() {
        assertArrayEquals(ByteArray(0), StateKeyCodec.decodeOrNull(StateKeyCodec.encode(ByteArray(0))))
        val key = ByteArray(32) { it.toByte() }
        assertArrayEquals(key, StateKeyCodec.decodeOrNull(StateKeyCodec.encode(key)))
    }

    @Test
    fun decodesGarbageAsNull() {
        assertNull(StateKeyCodec.decodeOrNull("not base64 !!!"))
        assertNull(StateKeyCodec.decodeOrNull(null))
        assertNull(StateKeyCodec.decodeOrNull("   "))
    }
}

package com.mindchat.app

import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * P0-1 acceptance: the poll loop must not run while the lifecycle is below
 * [Lifecycle.State.STARTED], and must resume with one immediate poll on
 * [Lifecycle.State.STARTED]. The gateway is a hand-written [MindChatGateway]
 * stub (no mocking library) whose `pollTransport` counts FFI calls.
 *
 * Determinism: after the loop reaches a stable 750 ms cadence, polls are gated
 * so the one already inside `pollTransport` spins (cancellably) until the test
 * releases it. The stopped-phase counter can therefore only grow if the loop
 * actually kept polling in the background, never from a mid-flight poll.
 */
class PollLifecycleGatingTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private class GatedGateway : MindChatGateway {
        val pollCalls = AtomicInteger(0)
        val persistCalls = AtomicInteger(0)

        /** When true, an in-flight poll spins until released (cancellably). */
        @Volatile
        var gatePolls = false

        override var state: MindChatUiState = MindChatUiState(
            accounts = emptyList(),
            contacts = emptyList(),
            activeAccountId = 0L,
            conversations = emptyList(),
            messagesByConversation = emptyMap(),
        )

        override suspend fun pollTransport() {
            pollCalls.incrementAndGet()
            while (gatePolls) {
                delay(10)
            }
        }

        override suspend fun persistNow() {
            persistCalls.incrementAndGet()
        }

        override fun selectAccount(accountId: Long) = Unit
        override fun addAccount(jid: String, server: String, displayName: String, password: String) = false
        override fun registerAccount(jid: String, server: String, displayName: String, password: String): String? = null
        override fun reconnectAccount(accountId: Long, password: String) = false
        override fun disconnectAccount(accountId: Long) = Unit
        override fun addContact(jid: String, displayName: String) = Unit
        override fun openConversation(address: String, title: String, group: Boolean): Long? = null
        override fun sendText(conversationId: Long, text: String) = Unit
        override fun markConversationRead(conversationId: Long) = Unit
        override fun updateProfile(accountId: Long, profile: AccountProfile) = Unit
        override fun deleteAccount(accountId: Long) = Unit
        override fun renameAccount(accountId: Long, displayName: String) = Unit
        override fun deleteConversation(conversationId: Long) = Unit
        override fun toggleDynamicColor() = Unit
        override fun setAppearance(appearance: AppearanceProfile) = Unit
        override fun toggleAppLock() = Unit
        override fun <T> setSetting(key: SettingKey<T>, value: T) = Unit
        override fun setAccountSetting(accountId: Long, key: SettingKey<*>, value: Any) = Unit
        override fun addProxy(config: ProxyConfig, password: String?): Boolean = false
        override fun updateProxy(proxyId: String, config: ProxyConfig, password: String?): Boolean = false
        override fun deleteProxy(proxyId: String) = Unit
        override fun pingProxy(proxyId: String, password: String?): ProxyProbeResult =
            ProxyProbeResult(ok = false, latencyMs = null, error = "not supported")
        override fun setAccountProxy(accountId: Long, config: ProxyConfig?, password: String?): Boolean = false
        override fun accountProxy(accountId: Long): ProxyConfig? = null
        override fun testProxy(config: ProxyConfig, password: String?): ProxyProbeResult =
            ProxyProbeResult(ok = false, latencyMs = null, error = "not supported")
    }

    @Test
    fun noPollCallsWhileStopped_andImmediatePollOnResume() {
        val gateway = GatedGateway()
        composeRule.setContent { MindChatApp(gateway, null) }
        composeRule.waitForIdle()

        // Let the loop reach its steady 750 ms cadence (polls 1 and 2 done).
        awaitTrue("initial polls", timeoutMs = 10_000) { gateway.pollCalls.get() >= 2 }

        // Gate polls so the next one spins inside pollTransport; the loop is
        // now stuck and no further poll can start until the gate opens.
        gateway.gatePolls = true
        awaitTrue("gated poll started", timeoutMs = 10_000) { gateway.pollCalls.get() >= 3 }
        val callsBeforeStop = gateway.pollCalls.get()
        assertEquals(3, callsBeforeStop)

        // ON_STOP: the loop must be cancelled, persistNow must run, and the
        // stopped phase must observe zero additional polls.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        gateway.gatePolls = false
        SystemClock.sleep(2_000)
        assertEquals("no poll while stopped", callsBeforeStop, gateway.pollCalls.get())
        awaitTrue("persist on stop", timeoutMs = 10_000) { gateway.persistCalls.get() >= 1 }

        // ON_START: the loop restarts with one immediate poll.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitTrue("immediate poll on resume", timeoutMs = 10_000) {
            gateway.pollCalls.get() > callsBeforeStop
        }
    }

    private fun awaitTrue(message: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue("$message: condition not met within ${timeoutMs}ms", condition())
    }
}

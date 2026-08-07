package com.mindchat.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MindChatAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun addAccountFlowIsAvailableBeforeAnyAccountExists() {
        composeRule.onNodeWithText("MindChat").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add account").performClick()
        composeRule.onNodeWithText("JID").assertIsDisplayed()
    }

    @Test
    fun customizationChoicesSurviveGatewayRecreation() {
        val preferences = InMemoryMindChatPreferences()
        val firstGateway = PreviewMindChatGateway(preferences)

        firstGateway.toggleDynamicColor()
        firstGateway.toggleComfortableLayout()
        firstGateway.toggleAppLock()

        val recreatedGateway = PreviewMindChatGateway(preferences)
        assertFalse(recreatedGateway.state.dynamicColor)
        assertFalse(recreatedGateway.state.comfortableLayout)
        assertTrue(recreatedGateway.state.appLockEnabled)
    }

    @Test
    fun localContactsAreScopedToTheActiveAccountAndUseTheProvidedDisplayName() {
        val gateway = PreviewMindChatGateway()

        gateway.addContact("zoe@example.org", "Zoe")

        val contact = gateway.state.contacts.single { it.jid == "zoe@example.org" }
        assertEquals(gateway.state.activeAccountId, contact.accountId)
        assertEquals("Zoe", contact.displayName)
        val firstConversation = gateway.openConversation(contact.jid, contact.displayName, group = false)
        val repeatedConversation = gateway.openConversation(contact.jid, contact.displayName, group = false)
        assertTrue(firstConversation != null)
        assertEquals(firstConversation, repeatedConversation)
    }

    @Test
    fun accountSetupRequiresAPasswordBeforeThePreviewSessionIsCreated() {
        val gateway = PreviewMindChatGateway()
        val initialCount = gateway.state.accounts.size

        assertFalse(gateway.addAccount("mila@example.org", "example.org", "Mila", ""))
        assertEquals(initialCount, gateway.state.accounts.size)

        assertTrue(gateway.addAccount("mila@example.org", "example.org", "Mila", "secret"))
        assertEquals(initialCount + 1, gateway.state.accounts.size)
        assertEquals("mila@example.org", gateway.state.accounts.last().jid)
    }
}

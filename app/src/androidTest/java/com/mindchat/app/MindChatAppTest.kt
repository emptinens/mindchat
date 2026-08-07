package com.mindchat.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
}

package com.mindchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance tests for the 0.1.5 management surface driven through the public
 * [MindChatGateway] contract. `PreviewMindChatGateway` is the JVM-runnable
 * implementation of that contract (the native one needs the packaged ABI
 * library), so every assertion here pins observable behavior the UI and the
 * native gateway are expected to match: registration validation and account
 * creation, account deletion cascading to conversations/contacts/messages and
 * the stored profile, rename, and conversation deletion.
 */
class GatewayManagementContractTest {

    private fun gateway(preferences: MindChatPreferences = InMemoryMindChatPreferences()) =
        PreviewMindChatGateway(preferences)

    // --- XEP-0077 registration ------------------------------------------------

    @Test
    fun registerAccount_addsConnectingAccountAndSwitchesToIt() {
        val g = gateway()
        val error = g.registerAccount("new.user@example.org", "example.org", "New User", "s3cret")

        assertNull("registration must succeed", error)
        val added = g.state.accounts.last()
        assertEquals("new.user@example.org", added.jid)
        assertEquals("New User", added.displayName)
        assertEquals(AccountConnectionState.CONNECTING, added.connectionState)
        assertEquals(added.id, g.state.activeAccountId)
    }

    @Test
    fun registerAccount_rejectsEmptyPasswordWithoutCreatingAccount() {
        val g = gateway()
        val before = g.state.accounts

        val error = g.registerAccount("new.user@example.org", "example.org", "New User", "")

        assertEquals("password required", error)
        assertEquals(before, g.state.accounts)
    }

    @Test
    fun registerAccount_rejectsBlankLocalPart() {
        val g = gateway()
        val error = g.registerAccount("@example.org", "example.org", "New User", "s3cret")
        assertEquals("invalid JID", error)
    }

    @Test
    fun registerAccount_trimsJidAndFallsBackDisplayNameToUsername() {
        val g = gateway()
        val error = g.registerAccount("  carol@example.org  ", "example.org", "   ", "s3cret")

        assertNull(error)
        val added = g.state.accounts.last()
        assertEquals("carol@example.org", added.jid)
        assertEquals("carol", added.displayName)
    }

    // --- Account deletion -----------------------------------------------------

    @Test
    fun deleteAccount_cascadesToConversationsContactsMessagesAndStoredProfile() {
        val preferences = InMemoryMindChatPreferences()
        val g = gateway(preferences)
        val alice = g.state.accounts.single()
        g.updateProfile(
            alice.id,
            AccountProfile(avatarUri = "file:///avatars/alice.png", statusMessage = "busy", accentKey = "indigo"),
        )
        assertEquals(1, g.state.profiles.size)
        assertEquals(1, preferences.readProfiles().size)

        g.deleteAccount(alice.id)

        assertTrue("account must be removed", g.state.accounts.none { it.id == alice.id })
        assertTrue("conversations must be removed", g.state.conversations.none { it.accountId == alice.id })
        assertTrue("contacts must be removed", g.state.contacts.none { it.accountId == alice.id })
        assertTrue("messages must be removed", g.state.messagesByConversation.isEmpty())
        assertTrue("profile must leave the UI state", g.state.profiles.isEmpty())
        assertTrue("profile must leave preferences", preferences.readProfiles().isEmpty())
        assertEquals(0L, g.state.activeAccountId)
    }

    @Test
    fun deleteAccount_fallsBackActiveAccountToFirstRemaining() {
        val g = gateway()
        g.registerAccount("second@example.org", "example.org", "Second", "pw")
        val secondId = g.state.activeAccountId
        assertEquals(2, g.state.accounts.size)

        g.deleteAccount(secondId)

        assertEquals(1, g.state.accounts.size)
        assertEquals(g.state.accounts.first().id, g.state.activeAccountId)
    }

    @Test
    fun deleteAccount_unknownIdIsANoOp() {
        val g = gateway()
        val before = g.state

        g.deleteAccount(9_999L)

        assertEquals(before, g.state)
    }

    // --- Rename ---------------------------------------------------------------

    @Test
    fun renameAccount_trimsAndUpdatesDisplayName() {
        val g = gateway()
        val alice = g.state.accounts.single()

        g.renameAccount(alice.id, "  Alice K.  ")

        assertEquals("Alice K.", g.state.accounts.single().displayName)
    }

    @Test
    fun renameAccount_blankNameAndUnknownIdAreNoOps() {
        val g = gateway()
        val alice = g.state.accounts.single()

        g.renameAccount(alice.id, "   ")
        assertEquals("Alice", g.state.accounts.single().displayName)

        g.renameAccount(9_999L, "Nobody")
        assertEquals("Alice", g.state.accounts.single().displayName)
    }

    // --- Conversation deletion ------------------------------------------------

    @Test
    fun deleteConversation_removesOnlyThatConversationAndItsMessages() {
        val g = gateway()
        g.updateProfile(
            g.state.activeAccountId,
            AccountProfile(avatarUri = "file:///avatars/alice.png"),
        )

        g.deleteConversation(1L)

        assertEquals(listOf(2L), g.state.conversations.map(ConversationUi::id))
        assertTrue("conversation 1 messages must be removed", g.state.messagesByConversation[1L].isNullOrEmpty())
        assertEquals(1, g.state.messagesByConversation[2L]!!.size)
        assertFalse("account must survive", g.state.accounts.isEmpty())
        assertTrue("profile must survive", g.state.profiles.isNotEmpty())
    }

    // --- Profile persistence --------------------------------------------------

    @Test
    fun updateProfile_persistsToPreferencesAndState() {
        val preferences = InMemoryMindChatPreferences()
        val g = gateway(preferences)
        val alice = g.state.accounts.single()
        val profile = AccountProfile(avatarUri = "file:///avatars/alice.png", statusMessage = "away", accentKey = "amber")

        g.updateProfile(alice.id, profile)

        assertEquals(profile, g.state.profiles[alice.id])
        assertEquals(profile, preferences.readProfiles()[alice.id])
    }
}

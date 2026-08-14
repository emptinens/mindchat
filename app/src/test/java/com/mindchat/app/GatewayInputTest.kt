package com.mindchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shared decision logic that both `MindChatGateway` implementations
 * must run (`validateRegistration` and `nextActiveAccountId`). Keeping the
 * rules in one place is what makes the preview contractually identical to the
 * native gateway rather than merely similar.
 */
class GatewayInputTest {

    // --- validateRegistration -------------------------------------------------

    private fun valid(
        jid: String = "carol@example.org",
        server: String = "example.org",
        displayName: String = "Carol",
        password: String = "s3cret",
    ) = validateRegistration(jid, server, displayName, password) as RegistrationValidation.Valid

    @Test
    fun validInputIsNormalizedAndTrimmed() {
        val input = valid(
            jid = "  carol@example.org  ",
            server = "  example.org  ",
            displayName = "  Carol D.  ",
        ).input

        assertEquals("carol@example.org", input.fullJid)
        assertEquals("carol", input.username)
        assertEquals("example.org", input.server)
        assertEquals("Carol D.", input.displayName)
        assertEquals("s3cret", input.password)
    }

    @Test
    fun blankDisplayNameFallsBackToUsername() {
        val input = valid(jid = "carol@example.org", displayName = "   ").input
        assertEquals("carol", input.displayName)
    }

    @Test
    fun emptyPasswordIsRefusedWithUiDetail() {
        val validation = validateRegistration("carol@example.org", "example.org", "Carol", "")
        assertTrue(validation is RegistrationValidation.Refused)
        val refusal = (validation as RegistrationValidation.Refused).refusal
        assertEquals(RegistrationRefusal.PasswordRequired, refusal)
        assertEquals("password required", refusal.toUiDetail())
    }

    @Test
    fun blankLocalPartIsRefusedWithUiDetail() {
        val validation = validateRegistration("@example.org", "example.org", "Carol", "s3cret")
        assertTrue(validation is RegistrationValidation.Refused)
        val refusal = (validation as RegistrationValidation.Refused).refusal
        assertEquals(RegistrationRefusal.InvalidJid, refusal)
        assertEquals("invalid JID", refusal.toUiDetail())
    }

    @Test
    fun blankJidIsRefusedAsInvalidJid() {
        val validation = validateRegistration("   ", "example.org", "Carol", "s3cret")
        assertEquals(RegistrationRefusal.InvalidJid, (validation as RegistrationValidation.Refused).refusal)
    }

    // --- nextActiveAccountId --------------------------------------------------

    private fun account(id: Long) = AccountUi(
        id = id,
        jid = "user$id@example.org",
        displayName = "User $id",
        presence = Presence.OFFLINE,
        connectionState = AccountConnectionState.OFFLINE,
        supportsGroupChats = true,
    )

    @Test
    fun deletedActiveAccountFallsBackToFirstRemaining() {
        val accounts = listOf(account(1), account(2), account(3))
        assertEquals(2L, nextActiveAccountId(accounts, deletedId = 1, activeAccountId = 1))
    }

    @Test
    fun deletingTheOnlyActiveAccountYieldsZero() {
        val accounts = listOf(account(1))
        assertEquals(0L, nextActiveAccountId(accounts, deletedId = 1, activeAccountId = 1))
    }

    @Test
    fun deletingAnInactiveAccountKeepsTheActiveId() {
        val accounts = listOf(account(1), account(2), account(3))
        assertEquals(2L, nextActiveAccountId(accounts, deletedId = 3, activeAccountId = 2))
    }

    @Test
    fun deletingAnUnknownIdKeepsTheActiveId() {
        val accounts = listOf(account(1), account(2))
        assertEquals(2L, nextActiveAccountId(accounts, deletedId = 9_999, activeAccountId = 2))
    }
}

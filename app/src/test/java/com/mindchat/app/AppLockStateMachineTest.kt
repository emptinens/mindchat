package com.mindchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockStateMachineTest {
    @Test
    fun enablingLockBlocksContentAndRequestsOneAutomaticPrompt() {
        val machine = AppLockStateMachine()

        machine.setEnabled(true)

        assertTrue(machine.state.isEnabled)
        assertTrue(machine.state.blocksContent)
        assertTrue(machine.state.canRequestAuthentication)
        assertEquals(1, machine.state.automaticPromptNonce)
    }

    @Test
    fun cancellationKeepsTheGateLockedWithoutReopeningTheSystemPrompt() {
        val machine = AppLockStateMachine()
        machine.setEnabled(true)
        assertTrue(machine.beginAuthentication())

        machine.onAuthenticationCancelled()

        assertEquals(AppLockStatus.LOCKED, machine.state.status)
        assertTrue(machine.state.canRequestAuthentication)
        assertEquals(1, machine.state.automaticPromptNonce)
    }

    @Test
    fun backgroundingAfterSuccessLocksAgainAndSchedulesAnotherPrompt() {
        val machine = AppLockStateMachine()
        machine.setEnabled(true)
        machine.beginAuthentication()
        machine.onAuthenticationSucceeded()

        machine.onAppBackgrounded()

        assertEquals(AppLockStatus.LOCKED, machine.state.status)
        assertEquals(2, machine.state.automaticPromptNonce)
    }

    @Test
    fun backgroundingWhileAlreadyLockedSchedulesAuthenticationOnReturn() {
        val machine = AppLockStateMachine()
        machine.setEnabled(true)
        machine.beginAuthentication()
        machine.onAuthenticationCancelled()

        machine.onAppBackgrounded()

        assertEquals(AppLockStatus.LOCKED, machine.state.status)
        assertEquals(2, machine.state.automaticPromptNonce)
    }

    @Test
    fun disablingLockMakesLateAuthenticationCallbacksHarmless() {
        val machine = AppLockStateMachine()
        machine.setEnabled(true)
        machine.beginAuthentication()

        machine.setEnabled(false)
        machine.onAuthenticationSucceeded()

        assertFalse(machine.state.isEnabled)
        assertFalse(machine.state.blocksContent)
        assertEquals(AppLockStatus.UNLOCKED, machine.state.status)
    }

    @Test
    fun successWithoutAnActivePromptCannotUnlockTheGate() {
        val machine = AppLockStateMachine()
        machine.setEnabled(true)

        machine.onAuthenticationSucceeded()

        assertEquals(AppLockStatus.LOCKED, machine.state.status)
    }
}

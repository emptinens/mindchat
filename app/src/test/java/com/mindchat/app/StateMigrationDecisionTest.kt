package com.mindchat.app

import com.mindchat.core.FfiPersistenceOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Migration decision for secured loads (0.1.9 storage encryption): only a
 * legacy plaintext file triggers the immediate encrypted re-save.
 */
class StateMigrationDecisionTest {
    @Test
    fun onlyLegacyPlaintextRequiresResave() {
        assertTrue(shouldResaveAfterLoad(FfiPersistenceOutcome.PLAINTEXT_LEGACY))
        assertFalse(shouldResaveAfterLoad(FfiPersistenceOutcome.ENCRYPTED))
        assertFalse(shouldResaveAfterLoad(FfiPersistenceOutcome.MISSING))
    }
}

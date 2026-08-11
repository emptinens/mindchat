package com.mindchat.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistenceStateTrackerTest {
    @Test
    fun successfulSnapshotAcknowledgesTheCapturedMutation() {
        val tracker = PersistenceStateTracker()

        tracker.markMutation()
        val savedEpoch = tracker.captureSaveEpoch()
        tracker.markPersisted(savedEpoch)

        assertFalse(tracker.requiresSave())
    }

    @Test
    fun laterMutationRemainsDirtyAfterAnOlderSnapshotCompletes() {
        val tracker = PersistenceStateTracker()

        tracker.markMutation()
        val savedEpoch = tracker.captureSaveEpoch()
        tracker.markMutation()
        tracker.markPersisted(savedEpoch)

        assertTrue(tracker.requiresSave())
    }

    @Test
    fun olderCompletionCannotRegressANewerPersistedEpoch() {
        val tracker = PersistenceStateTracker()

        tracker.markMutation()
        val firstEpoch = tracker.captureSaveEpoch()
        tracker.markMutation()
        val secondEpoch = tracker.captureSaveEpoch()
        tracker.markPersisted(secondEpoch)
        tracker.markPersisted(firstEpoch)

        assertFalse(tracker.requiresSave())
    }
}

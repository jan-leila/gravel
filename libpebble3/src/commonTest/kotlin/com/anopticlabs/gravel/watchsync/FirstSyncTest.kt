package com.anopticlabs.gravel.watchsync

import coredev.BlobDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FirstSyncTest {
    @Test
    fun replacingClearsEveryDatabaseUpstreamClears() {
        assertEquals(BlobDatabase.entries.filter { it.sendClear }, databasesToClear(FirstSyncChoice.ReplaceWatch))
    }

    @Test
    fun keepingLeavesTheUsersDataOnTheWatch() {
        val cleared = databasesToClear(FirstSyncChoice.KeepWatch).toSet()
        for (kept in listOf(
            BlobDatabase.App,
            BlobDatabase.AppConfigs,
            BlobDatabase.AppGlance,
            BlobDatabase.CannedResponses,
            BlobDatabase.Contacts,
            BlobDatabase.HealthParams,
            BlobDatabase.HealthStats,
            BlobDatabase.WatchPrefs,
        )) {
            assertTrue(kept !in cleared, "$kept is cleared")
        }
    }

    @Test
    fun keepingStillClearsTheDatabaseThatMakesTheWatchFaithful() {
        assertTrue(BlobDatabase.Notification in databasesToClear(FirstSyncChoice.KeepWatch))
    }
}

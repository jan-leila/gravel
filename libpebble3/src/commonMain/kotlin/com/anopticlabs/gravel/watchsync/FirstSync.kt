package com.anopticlabs.gravel.watchsync

import coredev.BlobDatabase
import io.rebble.libpebblecommon.connection.PebbleIdentifier

/** What the first sync with a watch this phone has not set up does to what the watch already holds. */
enum class FirstSyncChoice {
    /** Wipe the watch and fill it from this phone: upstream's behaviour, and the default. */
    ReplaceWatch,

    /**
     * Keep the watch's apps, their settings, contacts, quick replies, health and system settings,
     * take in what the watch reports back (its system and health settings, notification mutes),
     * and rebuild only what this phone generates (timeline, notifications, weather).
     */
    KeepWatch,
}

/**
 * A first sync waiting on [FirstSyncDecider]. [usedWithAnotherPhone] is the watch's own
 * "unfaithful" report for a watch this phone has connected before; otherwise the phone simply has
 * no record of having set it up (a new pairing, or the app reinstalled).
 */
data class FirstSyncRequest(
    val identifier: PebbleIdentifier,
    val serial: String,
    val usedWithAnotherPhone: Boolean,
)

/**
 * Chooses for a [FirstSyncRequest]. Called on the connection's scope before anything is sent to
 * the watch's databases, and the whole BlobDB sync waits for it; a disconnect cancels the call.
 */
interface FirstSyncDecider {
    suspend fun decide(request: FirstSyncRequest): FirstSyncChoice
}

/** The embedder's [FirstSyncDecider], or none, which keeps upstream's wipe. */
class InjectedFirstSyncDecider(val decider: FirstSyncDecider?)

/**
 * The databases cleared on the watch for [choice]. [FirstSyncChoice.KeepWatch] clears only
 * databases this phone regenerates in full, and still clears one, because the firmware marks itself
 * faithful again only after a successful clear (pebble-firmware blob_db/endpoint.c,
 * prv_handle_database_clear); Notification is the one with a flush on every platform. Pins and
 * reminders are among them because their ids are this phone's own, so the old ones would show
 * twice beside the ones this phone pushes.
 */
fun databasesToClear(choice: FirstSyncChoice): List<BlobDatabase> = when (choice) {
    FirstSyncChoice.ReplaceWatch -> BlobDatabase.entries.filter { it.sendClear }
    FirstSyncChoice.KeepWatch -> listOf(
        BlobDatabase.Notification,
        BlobDatabase.Pin,
        BlobDatabase.Reminder,
        BlobDatabase.Weather,
    )
}

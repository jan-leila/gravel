package com.anopticlabs.gravel.notebook.capture

import com.anopticlabs.gravel.notebook.routers.RoutedLocation

/** Where the phone is as a recording starts, or null when it does not know or may not say. Never throws. */
fun interface RecordingLocation {
    suspend fun current(): RoutedLocation?

    companion object {
        val NONE = RecordingLocation { null }
    }
}

/**
 * A ring recording is only located when the phone takes it in, so it is placed where the phone is then
 * only if it ended at most this many seconds before; one synced later has no location.
 */
const val DEFAULT_RING_LOCATION_SECONDS = 600
const val MIN_RING_LOCATION_SECONDS = 1
const val MAX_RING_LOCATION_SECONDS = 7_200

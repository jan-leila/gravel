package com.anopticlabs.gravel.notebook.capture

import com.anopticlabs.gravel.notebook.routers.RoutedLocation

/** Where the phone is as a recording starts, or null when it does not know or may not say. Never throws. */
fun interface RecordingLocation {
    suspend fun current(): RoutedLocation?

    companion object {
        val NONE = RecordingLocation { null }
    }
}

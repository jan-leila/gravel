package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.capture.RecordedAt
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable

/** Where a recording was made, as the phone knew it when the recording started. */
@Serializable
data class RoutedLocation(val latitude: Double, val longitude: Double, val accuracyMeters: Float? = null)

/**
 * What a voice recording's transcript is routed as: the whole text, the time the recording
 * started, and where it was made when the phone knew. Every router receives it as one value and
 * forwards it whole; [recordedAt] is a [RecordedAt] value; [captureId] identifies the recording,
 * so a receiver can tell a repeated delivery of the same recording from a new one. These are the
 * fields of the router API (`ROUTER_API.md`): a field added here can reach every app and server a
 * user routes to, so what a delivery carries beyond the text is the user's to grant per router.
 */
@Serializable
data class RoutedText(
    val captureId: String,
    val recordedAt: String,
    val text: String,
    val location: RoutedLocation? = null,
) {
    companion object {
        fun of(captureId: String, recordedAt: Instant, zone: TimeZone, text: String, location: RoutedLocation? = null): RoutedText =
            RoutedText(captureId, RecordedAt.render(recordedAt, zone), text, location)
    }
}

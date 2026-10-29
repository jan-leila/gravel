package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.capture.RecordedAt
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable

/**
 * What a voice recording's transcript is routed as: the whole text and the time the recording
 * started, which every router receives as one value and forwards whole. [recordedAt] is a
 * [RecordedAt] value; [captureId] identifies the recording, so a receiver can tell a repeated
 * delivery of the same recording from a new one. These three fields are the payload of the
 * router API (`ROUTER_API.md`): a field added here reaches every app and server a user routes to.
 */
@Serializable
data class RoutedText(
    val captureId: String,
    val recordedAt: String,
    val text: String,
) {
    companion object {
        fun of(captureId: String, recordedAt: Instant, zone: TimeZone, text: String): RoutedText =
            RoutedText(captureId, RecordedAt.render(recordedAt, zone), text)
    }
}

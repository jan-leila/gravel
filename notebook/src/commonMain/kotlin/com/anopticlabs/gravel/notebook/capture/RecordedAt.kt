package com.anopticlabs.gravel.notebook.capture

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.offsetAt

/**
 * When a recording started, as the Notebook writes it past the queue: one ISO-8601 instant with
 * the offset of the zone it was recorded in, to the second, such as `2026-10-29T14:03:11-04:00`.
 * The one form the note front matter, a reminder, and everything text is forwarded to carry.
 */
object RecordedAt {
    /** The longest value [render] writes: a four-digit year, no fraction, a `+hh:mm` offset. */
    const val MAX_CHARS = 25

    fun render(recordedAt: Instant, zone: TimeZone): String {
        // Whole seconds: the format prints a fraction when there is one, and it is noise on a start time.
        val seconds = Instant.fromEpochSeconds(recordedAt.epochSeconds)
        return seconds.format(DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET, zone.offsetAt(seconds))
    }

    /** The instant a value [render] could have written names, or null for any other value. */
    fun parse(value: String): Instant? {
        if (value.length > MAX_CHARS) return null
        return try {
            DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET.parse(value).toInstantUsingOffset()
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    fun isWellFormed(value: String): Boolean = parse(value) != null
}

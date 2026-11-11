package com.anopticlabs.gravel.notebook.capture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

class RecordedAtTest {

    private val newYork = TimeZone.of("America/New_York")

    @Test
    fun rendersTheInstantInItsZoneOffsetToTheSecond() {
        val instant = Instant.parse("2026-10-29T18:03:11.750Z") // EDT in New York
        assertEquals("2026-10-29T14:03:11-04:00", RecordedAt.render(instant, newYork))
        assertEquals("2026-10-29T18:03:11Z", RecordedAt.render(instant, TimeZone.UTC))
        assertEquals("2026-10-30T05:03:11+11:00", RecordedAt.render(instant, TimeZone.of("Australia/Sydney")))
    }

    @Test
    fun theOffsetFollowsDaylightSavingAtTheRecordingsTime() {
        // 2026-11-01T05:30:00Z is 01:30 EDT; an hour later, after the fall-back, it is 01:30 EST.
        val beforeChange = Instant.parse("2026-11-01T05:30:00Z")
        assertEquals("2026-11-01T01:30:00-04:00", RecordedAt.render(beforeChange, newYork))
        assertEquals("2026-11-01T01:30:00-05:00", RecordedAt.render(beforeChange + kotlin.time.Duration.parse("1h"), newYork))
    }

    @Test
    fun aRenderedValueParsesBackToItsInstantAndIsNeverLongerThanTheCap() {
        val instant = Instant.fromEpochSeconds(1_793_297_191L)
        for (zone in listOf(newYork, TimeZone.UTC, TimeZone.of("Asia/Kolkata"), TimeZone.of("Pacific/Chatham"))) {
            val rendered = RecordedAt.render(instant, zone)
            assertTrue(rendered.length <= RecordedAt.MAX_CHARS, rendered)
            assertEquals(instant, RecordedAt.parse(rendered), rendered)
            assertTrue(RecordedAt.isWellFormed(rendered))
        }
    }

    @Test
    fun anythingElseIsRefused() {
        assertNull(RecordedAt.parse(""))
        assertNull(RecordedAt.parse("2026-10-29"))
        assertNull(RecordedAt.parse("2026-10-29T14:03:11"))
        assertNull(RecordedAt.parse("1793297191"))
        assertNull(RecordedAt.parse("2026-13-29T14:03:11-04:00"))
        assertNull(RecordedAt.parse("2026-10-29T14:03:11.123456789-04:00"), "longer than the cap")
        assertFalse(RecordedAt.isWellFormed("not a time"))
    }
}

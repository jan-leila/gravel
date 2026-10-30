package com.anopticlabs.gravel.notebook.routers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

class TimeMatcherTest {

    private fun fields(recordedAt: String) = TimeMatcher.fields(recordedAt)!!

    @Test
    fun theFieldsAreReadInTheRecordingsOwnOffset() {
        // 22:30 in Berlin on Tuesday 22 September 2026, which is 20:30 UTC: the local evening counts.
        val fields = fields("2026-09-22T22:30:00+02:00")
        assertEquals(TimeFields(timeOfDay = 22 * 60 + 30, dayOfWeek = 2, dayOfMonth = 22, dayOfYear = 265, weekOfMonth = 4, weekOfYear = 39, month = 9, year = 2026), fields)
        assertEquals(fields.copy(timeOfDay = 20 * 60 + 30), fields("2026-09-22T20:30:00Z"))
        // A late evening in New York is the next day in UTC; the recording's own day is what counts.
        assertEquals(31, fields("2026-12-31T23:10:00-05:00").dayOfMonth)
        assertEquals(2026, fields("2026-12-31T23:10:00-05:00").year)
        assertNull(TimeMatcher.fields("yesterday"))
        assertNull(TimeMatcher.fields("2026-09-22T22:30:00"), "an offset is part of the value")
    }

    @Test
    fun isoWeeksStartOnMondayAndBelongToTheYearWithTheThursday() {
        assertEquals(53, TimeMatcher.isoWeekOfYear(LocalDate(2027, 1, 1)), "Friday 1 January 2027 is still in 2026's week 53")
        assertEquals(53, TimeMatcher.isoWeekOfYear(LocalDate(2026, 12, 31)))
        assertEquals(1, TimeMatcher.isoWeekOfYear(LocalDate(2024, 12, 30)), "Monday 30 December 2024 starts 2025's week 1")
        assertEquals(1, TimeMatcher.isoWeekOfYear(LocalDate(2025, 1, 1)))
        assertEquals(52, TimeMatcher.isoWeekOfYear(LocalDate(2024, 12, 29)))
        assertEquals(53, TimeMatcher.isoWeekOfYear(LocalDate(2020, 12, 31)), "2020 is a leap year starting on a Wednesday")
        assertEquals(1, TimeMatcher.isoWeekOfYear(LocalDate(2021, 1, 4)))
        assertEquals(39, TimeMatcher.isoWeekOfYear(LocalDate(2026, 9, 22)))
    }

    @Test
    fun theWeekOfTheMonthCountsSevenDaysAtATime() {
        assertEquals(1, fields("2026-09-01T10:00:00Z").weekOfMonth)
        assertEquals(1, fields("2026-09-07T10:00:00Z").weekOfMonth)
        assertEquals(2, fields("2026-09-08T10:00:00Z").weekOfMonth)
        assertEquals(5, fields("2026-09-29T10:00:00Z").weekOfMonth)
    }

    @Test
    fun aRangeHoldsInclusivelyAndWrapsRoundTheCycleButNeverRoundTheYear() {
        val evening = fields("2026-09-22T22:30:00+02:00")
        val morning = fields("2026-09-23T06:00:00+02:00")
        val noon = fields("2026-09-23T12:00:00+02:00")
        val night = TimeCondition(TimeField.TIME_OF_DAY, 22 * 60, 6 * 60)
        assertTrue(TimeMatcher.holds(night, evening))
        assertTrue(TimeMatcher.holds(night, morning), "the end is included")
        assertFalse(TimeMatcher.holds(night, noon))
        val office = TimeCondition(TimeField.TIME_OF_DAY, 9 * 60, 17 * 60)
        assertTrue(TimeMatcher.holds(office, noon))
        assertFalse(TimeMatcher.holds(office, evening))
        val weekend = TimeCondition(TimeField.DAY_OF_WEEK, 6, 7)
        val friToMon = TimeCondition(TimeField.DAY_OF_WEEK, 5, 1)
        assertFalse(TimeMatcher.holds(weekend, evening), "Tuesday")
        assertTrue(TimeMatcher.holds(friToMon, fields("2026-09-27T10:00:00Z")), "Sunday")
        assertTrue(TimeMatcher.holds(friToMon, fields("2026-09-28T10:00:00Z")), "Monday")
        assertFalse(TimeMatcher.holds(friToMon, evening))
        val winter = TimeCondition(TimeField.MONTH, 11, 2)
        assertTrue(TimeMatcher.holds(winter, fields("2026-01-15T10:00:00Z")))
        assertFalse(TimeMatcher.holds(winter, evening))
        assertTrue(TimeMatcher.holds(TimeCondition(TimeField.YEAR, 2026, 2026), evening))
        assertFalse(TimeMatcher.isWellFormed(TimeCondition(TimeField.YEAR, 2027, 2026)), "the year has no cycle to wrap round")
        assertTrue(TimeMatcher.isWellFormed(night))
        assertFalse(TimeMatcher.isWellFormed(TimeCondition(TimeField.TIME_OF_DAY, 0, 1440)))
        assertFalse(TimeMatcher.isWellFormed(TimeCondition(TimeField.DAY_OF_WEEK, 0, 5)))
        assertFalse(TimeMatcher.isWellFormed(TimeCondition(TimeField.WEEK_OF_YEAR, 1, 54)))
        assertTrue(TimeMatcher.isWellFormed(TimeCondition(TimeField.DAY_OF_YEAR, 366, 1)))
    }

    @Test
    fun aRuleHoldsWhenEveryConditionDoes() {
        val officeHours = TimeRule(listOf(TimeCondition(TimeField.DAY_OF_WEEK, 1, 5), TimeCondition(TimeField.TIME_OF_DAY, 9 * 60, 17 * 60)), RouterRef.gravel("work"))
        assertTrue(TimeMatcher.holds(officeHours, fields("2026-09-23T12:00:00+02:00")))
        assertFalse(TimeMatcher.holds(officeHours, fields("2026-09-23T19:00:00+02:00")))
        assertFalse(TimeMatcher.holds(officeHours, fields("2026-09-26T12:00:00+02:00")), "Saturday")
    }

    @Test
    fun aClockIsShownAndReadAsHoursAndMinutes() {
        assertEquals("00:00", TimeMatcher.clock(0))
        assertEquals("09:05", TimeMatcher.clock(9 * 60 + 5))
        assertEquals("23:59", TimeMatcher.clock(1439))
        assertEquals(9 * 60 + 5, TimeMatcher.parseClock("9:05"))
        assertEquals(22 * 60, TimeMatcher.parseClock(" 22:00 "))
        assertNull(TimeMatcher.parseClock("24:00"))
        assertNull(TimeMatcher.parseClock("9:60"))
        assertNull(TimeMatcher.parseClock("nine"))
        assertNull(TimeMatcher.parseClock("9"))
    }
}

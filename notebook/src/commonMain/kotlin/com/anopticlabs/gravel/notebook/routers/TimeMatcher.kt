package com.anopticlabs.gravel.notebook.routers

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number

/** The values a [TimeCondition] is read against, taken from the recording's own local time. */
data class TimeFields(
    val timeOfDay: Int,
    val dayOfWeek: Int,
    val dayOfMonth: Int,
    val dayOfYear: Int,
    val weekOfMonth: Int,
    val weekOfYear: Int,
    val month: Int,
    val year: Int,
) {
    operator fun get(field: TimeField): Int = when (field) {
        TimeField.TIME_OF_DAY -> timeOfDay
        TimeField.DAY_OF_WEEK -> dayOfWeek
        TimeField.DAY_OF_MONTH -> dayOfMonth
        TimeField.DAY_OF_YEAR -> dayOfYear
        TimeField.WEEK_OF_MONTH -> weekOfMonth
        TimeField.WEEK_OF_YEAR -> weekOfYear
        TimeField.MONTH -> month
        TimeField.YEAR -> year
    }
}

/** Reads a recording's time into [TimeFields] and decides [TimeCondition]s against it. */
object TimeMatcher {
    /** The values a field can take; a condition outside them is refused by [RoutingConfig.requireWellFormed]. */
    fun range(field: TimeField): IntRange = when (field) {
        TimeField.TIME_OF_DAY -> 0..1439
        TimeField.DAY_OF_WEEK -> 1..7
        TimeField.DAY_OF_MONTH -> 1..31
        TimeField.DAY_OF_YEAR -> 1..366
        TimeField.WEEK_OF_MONTH -> 1..5
        TimeField.WEEK_OF_YEAR -> 1..53
        TimeField.MONTH -> 1..12
        TimeField.YEAR -> 1970..9999
    }

    /** Whether a range with [TimeCondition.from] above [TimeCondition.to] wraps round; the year is the one field without a cycle. */
    fun wraps(field: TimeField): Boolean = field != TimeField.YEAR

    fun isWellFormed(condition: TimeCondition): Boolean {
        val range = range(condition.field)
        if (condition.from !in range || condition.to !in range) return false
        return wraps(condition.field) || condition.from <= condition.to
    }

    /** The field as a validation message names it. */
    fun fieldName(field: TimeField): String = when (field) {
        TimeField.TIME_OF_DAY -> "time of day"
        TimeField.DAY_OF_WEEK -> "day of the week"
        TimeField.DAY_OF_MONTH -> "day of the month"
        TimeField.DAY_OF_YEAR -> "day of the year"
        TimeField.WEEK_OF_MONTH -> "week of the month"
        TimeField.WEEK_OF_YEAR -> "week of the year"
        TimeField.MONTH -> "month"
        TimeField.YEAR -> "year"
    }

    /**
     * The fields of a [RoutedText.recordedAt] value, read in the offset it carries, so a recording
     * is placed in the day it was made in; null for a value that is not such a time.
     */
    fun fields(recordedAt: String): TimeFields? {
        val local = try {
            val parsed = DateTimeComponents.Formats.ISO_DATE_TIME_OFFSET.parse(recordedAt)
            parsed.toLocalDateTime()
        } catch (e: IllegalArgumentException) {
            return null
        }
        return fields(local)
    }

    fun fields(local: LocalDateTime): TimeFields = TimeFields(
        timeOfDay = local.hour * 60 + local.minute,
        dayOfWeek = local.dayOfWeek.isoDayNumber,
        dayOfMonth = local.day,
        dayOfYear = local.date.dayOfYear,
        weekOfMonth = (local.day - 1) / 7 + 1,
        weekOfYear = isoWeekOfYear(local.date),
        month = local.month.number,
        year = local.year,
    )

    fun holds(condition: TimeCondition, fields: TimeFields): Boolean {
        val value = fields[condition.field]
        return if (condition.from <= condition.to) {
            value in condition.from..condition.to
        } else {
            value >= condition.from || value <= condition.to
        }
    }

    fun holds(rule: TimeRule, fields: TimeFields): Boolean = rule.conditions.all { holds(it, fields) }

    /** ISO-8601: weeks start on Monday, and week 1 is the one with the year's first Thursday. */
    fun isoWeekOfYear(date: LocalDate): Int {
        val week = (date.dayOfYear - date.dayOfWeek.isoDayNumber + 10) / 7
        return when {
            week < 1 -> weeksInYear(date.year - 1)
            week > weeksInYear(date.year) -> 1
            else -> week
        }
    }

    // A year has 53 ISO weeks when it starts on a Thursday, or on a Wednesday when it is a leap year.
    private fun weeksInYear(year: Int): Int {
        val first = LocalDate(year, 1, 1).dayOfWeek.isoDayNumber
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        return if (first == 4 || (leap && first == 3)) 53 else 52
    }

    /** "HH:MM" for a time-of-day value, as the editor shows it. */
    fun clock(minutes: Int): String = "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"

    /** The minutes a "HH:MM" or "H:MM" the user typed means, or null. */
    fun parseClock(text: String): Int? {
        val match = CLOCK.matchEntire(text.trim()) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return null
        return hour * 60 + minute
    }

    private val CLOCK = Regex("(\\d{1,2}):(\\d{2})")
}

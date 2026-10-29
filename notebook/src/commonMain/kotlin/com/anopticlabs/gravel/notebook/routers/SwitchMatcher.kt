package com.anopticlabs.gravel.notebook.routers

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A rule's match ran past its deadline. */
class RuleTimeoutException : RuntimeException("a rule took too long to match")

/** A deadline: true once it has passed. */
typealias Expired = () -> Boolean

fun deadlineAfter(duration: Duration): Expired {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow() > duration }
}

/** Matches a [SwitchRule]'s pattern, a regular expression the user wrote, against a transcript under a deadline. */
object SwitchMatcher {
    /** What one rule may spend on one transcript; a pattern that backtracks past it flags the capture. */
    val RULE_DEADLINE: Duration = 1.seconds

    /** Throws [IllegalArgumentException] for a pattern that is not a regular expression. */
    fun compile(rule: SwitchRule): Regex =
        if (rule.ignoreCase) Regex(rule.pattern, RegexOption.IGNORE_CASE) else Regex(rule.pattern)

    /** True when [regex] matches anywhere in [text]; [RuleTimeoutException] once [expired] says so. */
    fun matches(regex: Regex, text: String, expired: Expired): Boolean = regex.containsMatchIn(DeadlineCharSequence(text, expired))
}

/**
 * The text as the regex engine reads it: every character read checks the deadline, so a pattern
 * whose backtracking blows up on this text is stopped at the next read instead of running on.
 */
internal class DeadlineCharSequence(private val text: String, private val expired: Expired) : CharSequence {
    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        if (expired()) throw RuleTimeoutException()
        return text[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = DeadlineCharSequence(text.substring(startIndex, endIndex), expired)

    override fun toString(): String = text
}

package com.anopticlabs.gravel.notebook.routers

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

/** A [SwitchRule]'s pattern, compiled. */
class SwitchPattern internal constructor(private val pattern: Pattern) {
    /** True when the pattern matches anywhere in [text]. */
    fun matchesIn(text: String): Boolean = pattern.matcher(text).find()
}

/**
 * Matches a [SwitchRule]'s pattern, a regular expression the user wrote, against a transcript.
 * The engine is RE2/J, which takes time linear in the text whatever the pattern, so a rule can
 * never hold the drain, where the platform's backtracking engine could run for hours on a pattern
 * like `(a+)+$`, and on Android would not be stopped by anything short of killing the process.
 * The price is RE2 syntax: no backreferences and no lookaround, which the compile refuses.
 */
object SwitchMatcher {
    /** Throws [IllegalArgumentException] for a pattern that is not a regular expression RE2 reads. */
    fun compile(rule: SwitchRule): SwitchPattern {
        val flags = if (rule.ignoreCase) Pattern.CASE_INSENSITIVE else 0
        return try {
            SwitchPattern(Pattern.compile(rule.pattern, flags))
        } catch (e: PatternSyntaxException) {
            throw IllegalArgumentException(e.message, e)
        }
    }

    fun matches(pattern: SwitchPattern, text: String): Boolean = pattern.matchesIn(text)
}

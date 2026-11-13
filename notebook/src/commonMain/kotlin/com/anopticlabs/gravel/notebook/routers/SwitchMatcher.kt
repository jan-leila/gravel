package com.anopticlabs.gravel.notebook.routers

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

/** Where a pattern matched: the span, and each named group that took part, in UTF-16 units, ends exclusive. */
data class SwitchFind(val start: Int, val end: Int, val groups: List<MatchGroup>)

/** A [SwitchRule]'s pattern, compiled. */
class SwitchPattern internal constructor(private val pattern: Pattern) {
    /** The named groups the pattern declares, `(?<name>...)` or `(?P<name>...)`. */
    val groupNames: Set<String> get() = pattern.namedGroups().keys

    /** The first match in [text], or null; a group left out of the match (an optional one) is not listed. */
    fun findIn(text: String): SwitchFind? {
        val matcher = pattern.matcher(text)
        if (!matcher.find()) return null
        val groups = pattern.namedGroups().entries.sortedBy { it.value }.mapNotNull { (name, index) ->
            matcher.start(index).takeIf { it >= 0 }?.let { MatchGroup(name, it, matcher.end(index)) }
        }
        return SwitchFind(matcher.start(), matcher.end(), groups)
    }
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

    fun find(pattern: SwitchPattern, text: String): SwitchFind? = pattern.findIn(text)
}

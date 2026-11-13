package com.anopticlabs.gravel.notebook.routers

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class SwitchMatcherTest {

    private fun matches(pattern: String, text: String, ignoreCase: Boolean = true): Boolean =
        SwitchMatcher.find(SwitchMatcher.compile(SwitchRule(pattern, RouterRef.gravel("r"), ignoreCase)), text) != null

    @Test
    fun aRuleMatchesAnywhereInTheTextCaseInsensitivelyUnlessToldOtherwise() {
        assertTrue(matches("remind me", "Please REMIND me to call"))
        assertFalse(matches("remind me", "Please REMIND me to call", ignoreCase = false))
        assertTrue(matches("^remind me\\b", "remind me to call"))
        assertFalse(matches("^remind me\\b", "please remind me"))
        assertTrue(matches("café", "Un CAFÉ, please"), "case folding is not ASCII-only")
    }

    @Test
    fun aPatternThatIsNotARegularExpressionIsRefusedAtCompileTime() {
        assertFailsWith<IllegalArgumentException> { SwitchMatcher.compile(SwitchRule("(unclosed", RouterRef.gravel("r"))) }
        assertFailsWith<IllegalArgumentException> { SwitchMatcher.compile(SwitchRule("(a)\\1", RouterRef.gravel("r"))) }
        assertFailsWith<IllegalArgumentException> { SwitchMatcher.compile(SwitchRule("(?=remind)", RouterRef.gravel("r"))) }
    }

    @Test
    fun aPatternThatBlowsUpABacktrackingEngineIsMatchedInLinearTime() {
        // 32 copies of .*a over 31 a's, and nested quantifiers over 40 a's and a b, backtrack for minutes to hours on the JDK and on ICU alike.
        val start = TimeSource.Monotonic.markNow()
        assertFalse(matches("(.*a){32}", "a".repeat(31)))
        assertFalse(matches("(a+)+$", "a".repeat(40) + "b"))
        assertTrue(matches("(a+)+$", "a".repeat(40)))
        assertTrue(start.elapsedNow().inWholeSeconds < 5, "took ${start.elapsedNow()}")
    }
}

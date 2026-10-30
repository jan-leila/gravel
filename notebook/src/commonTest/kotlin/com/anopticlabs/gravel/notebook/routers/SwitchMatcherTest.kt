package com.anopticlabs.gravel.notebook.routers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SwitchMatcherTest {

    private val never: Expired = { false }

    @Test
    fun aRuleMatchesAnywhereInTheTextCaseInsensitivelyUnlessToldOtherwise() {
        assertTrue(SwitchMatcher.matches(SwitchMatcher.compile(SwitchRule("remind me", RouterRef.gravel("r"))), "Please REMIND me to call", never))
        assertFalse(SwitchMatcher.matches(SwitchMatcher.compile(SwitchRule("remind me", RouterRef.gravel("r"), ignoreCase = false)), "Please REMIND me to call", never))
        assertTrue(SwitchMatcher.matches(SwitchMatcher.compile(SwitchRule("^remind me\\b", RouterRef.gravel("r"))), "remind me to call", never))
        assertFalse(SwitchMatcher.matches(SwitchMatcher.compile(SwitchRule("^remind me\\b", RouterRef.gravel("r"))), "please remind me", never))
    }

    @Test
    fun aPatternThatIsNotARegularExpressionIsRefusedAtCompileTime() {
        assertFailsWith<IllegalArgumentException> { SwitchMatcher.compile(SwitchRule("(unclosed", RouterRef.gravel("r"))) }
    }

    @Test
    fun aMatchThatRunsPastItsDeadlineIsStopped() {
        // Polynomial blow-up: 32 copies of .*a over 31 a's backtrack for minutes on the JDK and on ICU alike.
        val regex = SwitchMatcher.compile(SwitchRule("(.*a){32}", RouterRef.gravel("r")))
        val text = "a".repeat(31)
        assertFailsWith<RuleTimeoutException> { SwitchMatcher.matches(regex, text, deadlineAfter(30.milliseconds)) }
        assertFailsWith<RuleTimeoutException> { SwitchMatcher.matches(regex, "aab", { true }) }
    }

    @Test
    fun aMatchWithinItsDeadlineIsUnaffected() {
        assertTrue(SwitchMatcher.matches(SwitchMatcher.compile(SwitchRule("call (sam|pat)", RouterRef.gravel("r"))), "remind me to call Pat", deadlineAfter(1.seconds)))
    }

    @Test
    fun theSequenceReadsAsTheText() {
        val sequence = DeadlineCharSequence("hello", never)
        assertEquals(5, sequence.length)
        assertEquals('e', sequence[1])
        assertEquals("ell", sequence.subSequence(1, 4).toString())
        assertEquals("hello", sequence.toString())
    }
}

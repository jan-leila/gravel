package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class RouteWalkTest {

    private val note = RouterConfig.Note("notes", "Notes")
    private val reminder = RouterConfig.Reminder("reminders", "Reminders")
    private val app = RouterConfig.App("tasks", "Tasks", "com.example.tasks", "com.example.tasks.Router")
    private val split = RouterConfig.Switch("split", "Split", listOf(SwitchRule("^remind me", "reminders"), SwitchRule("todo", "tasks")), otherwise = "notes")
    private val config = RoutingConfig(entry = "split", routers = listOf(split, note, reminder, app))

    private fun text(said: String) = RoutedText("c1", "2026-09-22T17:00:00+02:00", said)

    private fun flagged(block: () -> Unit): CaptureProcessingException {
        val e = assertFailsWith<CaptureProcessingException> { block() }
        assertEquals(CaptureFailureKind.FLAG, e.kind)
        assertEquals(CaptureFailureReason.NOT_ROUTED, e.reason)
        return e
    }

    @Test
    fun theDefaultGraphEndsAtItsNoteRouter() {
        assertEquals(RoutingConfig.DEFAULT.routers.single(), RouteWalk.terminal(RoutingConfig.DEFAULT, text("anything at all")))
    }

    @Test
    fun theFirstMatchingRuleWinsAndTheFallbackTakesTheRest() {
        assertEquals(reminder, RouteWalk.terminal(config, text("Remind me to call Sam, todo list later")))
        assertEquals(app, RouteWalk.terminal(config, text("add to my todo list: remind me later")))
        assertEquals(note, RouteWalk.terminal(config, text("thoughts on the garden")))
    }

    @Test
    fun switchesChainAndADiamondIsFine() {
        val second = RouterConfig.Switch("second", "Second", listOf(SwitchRule("urgent", "tasks")), otherwise = "notes")
        val first = RouterConfig.Switch("first", "First", listOf(SwitchRule("work", "second")), otherwise = "second")
        val chained = RoutingConfig(entry = "first", routers = listOf(first, second, note, app))
        assertEquals(app, RouteWalk.terminal(chained, text("work: urgent fix")))
        assertEquals(note, RouteWalk.terminal(chained, text("work: later")))
        assertEquals(app, RouteWalk.terminal(chained, text("home: urgent fix")))
    }

    @Test
    fun noMatchWithoutAFallbackIsFlaggedAsNotRouted() {
        val strict = config.copy(routers = config.routers.map { if (it is RouterConfig.Switch) it.copy(otherwise = null) else it })
        assertTrue(flagged { RouteWalk.terminal(strict, text("thoughts on the garden")) }.message!!.contains("no rule of Split matched"))
    }

    @Test
    fun aGraphTheValidationWouldRefuseStillEndsInAFlagNotALoop() {
        val missingEntry = RoutingConfig(entry = "gone", routers = listOf(note))
        assertTrue(flagged { RouteWalk.terminal(missingEntry, text("x")) }.message!!.contains("entry router"))
        val missingTarget = RoutingConfig(entry = "s", routers = listOf(RouterConfig.Switch("s", "S", emptyList(), otherwise = "gone")))
        assertTrue(flagged { RouteWalk.terminal(missingTarget, text("x")) }.message!!.contains("does not exist"))
        val circle = RoutingConfig(
            entry = "a",
            routers = listOf(RouterConfig.Switch("a", "A", emptyList(), otherwise = "b"), RouterConfig.Switch("b", "B", emptyList(), otherwise = "a")),
        )
        assertTrue(flagged { RouteWalk.terminal(circle, text("x")) }.message!!.contains("circle at A"))
        val badRule = RoutingConfig(entry = "s", routers = listOf(RouterConfig.Switch("s", "S", listOf(SwitchRule("(open", "notes"))), note))
        assertTrue(flagged { RouteWalk.terminal(badRule, text("x")) }.message!!.contains("rule 1 of S is not a valid"))
    }

    @Test
    fun aChainLongerThanTheHopCapIsFlagged() {
        val chain = (0..RouteWalk.MAX_HOPS).map { RouterConfig.Switch("s$it", "S$it", emptyList(), otherwise = "s${it + 1}") }
        val config = RoutingConfig(entry = "s0", routers = chain + RouterConfig.Note("s${RouteWalk.MAX_HOPS + 1}", "End"))
        assertTrue(flagged { RouteWalk.terminal(config, text("x")) }.message!!.contains("in a row"))
        val shorter = RoutingConfig(entry = "s1", routers = config.routers)
        assertEquals(config.routers.last(), RouteWalk.terminal(shorter, text("x")))
    }

    @Test
    fun aRuleThatRunsPastItsDeadlineIsFlagged() {
        val slow = RoutingConfig(entry = "s", routers = listOf(RouterConfig.Switch("s", "S", listOf(SwitchRule("(.*a){32}", "notes")), otherwise = "notes"), note))
        assertTrue(flagged { RouteWalk.terminal(slow, text("a".repeat(31)), ruleDeadline = 30.milliseconds) }.message!!.contains("rule 1 of S took too long"))
    }
}

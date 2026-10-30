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

    private val signer = "a".repeat(64)
    private fun g(id: String) = RouterRef.gravel(id)

    private val note = RouterConfig.Note("notes", "Notes")
    private val reminder = RouterConfig.Reminder("reminders", "Reminders")
    private val app = RouterConfig.App("tasks", "Tasks", "com.example.tasks", "com.example.tasks.Router", signer)
    private val split = RouterConfig.Switch("split", "Split", listOf(SwitchRule("^remind me", g("reminders")), SwitchRule("todo", app.ref)), otherwise = g("notes"))
    private val config = RoutingConfig(entry = g("split"), routers = listOf(split, note, reminder, app))

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

    /** A ref names a router by namespace and id: the same id in Gravel's namespace is a different router. */
    @Test
    fun aRefIsMatchedByNamespaceAndId() {
        val mine = RouterConfig.Note("tasks", "My tasks")
        val both = config.copy(routers = config.routers + mine)
        assertEquals(app, RouteWalk.terminal(both, text("todo: buy milk")))
        val toMine = both.copy(routers = both.routers.map { if (it is RouterConfig.Switch) it.copy(rules = listOf(SwitchRule("todo", g("tasks")))) else it })
        assertEquals(mine, RouteWalk.terminal(toMine, text("todo: buy milk")))
    }

    @Test
    fun switchesChainAndADiamondIsFine() {
        val second = RouterConfig.Switch("second", "Second", listOf(SwitchRule("urgent", app.ref)), otherwise = g("notes"))
        val first = RouterConfig.Switch("first", "First", listOf(SwitchRule("work", g("second"))), otherwise = g("second"))
        val chained = RoutingConfig(entry = g("first"), routers = listOf(first, second, note, app))
        assertEquals(app, RouteWalk.terminal(chained, text("work: urgent fix")))
        assertEquals(note, RouteWalk.terminal(chained, text("work: later")))
        assertEquals(app, RouteWalk.terminal(chained, text("home: urgent fix")))
    }

    private val hours = RouterConfig.Time(
        "hours", "Hours",
        listOf(
            TimeRule(listOf(TimeCondition(TimeField.DAY_OF_WEEK, 1, 5), TimeCondition(TimeField.TIME_OF_DAY, 9 * 60, 17 * 60)), app.ref),
            TimeRule(listOf(TimeCondition(TimeField.TIME_OF_DAY, 22 * 60, 6 * 60)), g("reminders")),
        ),
        otherwise = g("notes"),
    )

    /** A Time router reads the recording's own local time: the first rule whose conditions all hold wins, else the fallback. */
    @Test
    fun aTimeRouterForwardsByTheRecordingsLocalTime() {
        val timed = RoutingConfig(entry = g("hours"), routers = listOf(hours, note, reminder, app))
        assertEquals(app, RouteWalk.terminal(timed, RoutedText("c1", "2026-09-23T12:00:00+02:00", "x")), "Wednesday noon")
        assertEquals(reminder, RouteWalk.terminal(timed, RoutedText("c1", "2026-09-23T23:30:00+02:00", "x")), "late evening")
        assertEquals(reminder, RouteWalk.terminal(timed, RoutedText("c1", "2026-09-24T05:59:00+02:00", "x")), "the wrap holds to the end")
        assertEquals(note, RouteWalk.terminal(timed, RoutedText("c1", "2026-09-26T12:00:00+02:00", "x")), "Saturday noon")
        assertEquals(app, RouteWalk.terminal(timed, RoutedText("c1", "2026-09-23T12:00:00Z", "x")), "the offset the payload carries is the one that counts")
        val strict = timed.copy(routers = timed.routers.map { if (it is RouterConfig.Time) it.copy(otherwise = null) else it })
        assertTrue(flagged { RouteWalk.terminal(strict, RoutedText("c1", "2026-09-26T12:00:00+02:00", "x")) }.message!!.contains("no rule of Hours matched"))
        assertTrue(flagged { RouteWalk.terminal(timed, RoutedText("c1", "noon", "x")) }.message!!.contains("could not be read"))
        // Under a switch: a reminder cue goes to the reminders, the rest by the hour.
        val under = RoutingConfig(entry = g("split"), routers = listOf(split.copy(otherwise = g("hours")), hours, note, reminder, app))
        assertEquals(reminder, RouteWalk.terminal(under, RoutedText("c1", "2026-09-23T12:00:00+02:00", "remind me later")))
        assertEquals(app, RouteWalk.terminal(under, RoutedText("c1", "2026-09-23T12:00:00+02:00", "thoughts")))
    }

    @Test
    fun noMatchWithoutAFallbackIsFlaggedAsNotRouted() {
        val strict = config.copy(routers = config.routers.map { if (it is RouterConfig.Switch) it.copy(otherwise = null) else it })
        assertTrue(flagged { RouteWalk.terminal(strict, text("thoughts on the garden")) }.message!!.contains("no rule of Split matched"))
    }

    @Test
    fun aGraphTheValidationWouldRefuseStillEndsInAFlagNotALoop() {
        val missingEntry = RoutingConfig(entry = g("gone"), routers = listOf(note))
        assertTrue(flagged { RouteWalk.terminal(missingEntry, text("x")) }.message!!.contains("entry router"))
        val missingTarget = RoutingConfig(entry = g("s"), routers = listOf(RouterConfig.Switch("s", "S", emptyList(), otherwise = g("gone"))))
        assertTrue(flagged { RouteWalk.terminal(missingTarget, text("x")) }.message!!.contains("does not exist"))
        val circle = RoutingConfig(
            entry = g("a"),
            routers = listOf(RouterConfig.Switch("a", "A", emptyList(), otherwise = g("b")), RouterConfig.Switch("b", "B", emptyList(), otherwise = g("a"))),
        )
        assertTrue(flagged { RouteWalk.terminal(circle, text("x")) }.message!!.contains("circle at A"))
        val badRule = RoutingConfig(entry = g("s"), routers = listOf(RouterConfig.Switch("s", "S", listOf(SwitchRule("(open", g("notes")))), note))
        assertTrue(flagged { RouteWalk.terminal(badRule, text("x")) }.message!!.contains("rule 1 of S is not a valid"))
    }

    @Test
    fun aChainLongerThanTheHopCapIsFlagged() {
        val chain = (0..RouteWalk.MAX_HOPS).map { RouterConfig.Switch("s$it", "S$it", emptyList(), otherwise = g("s${it + 1}")) }
        val config = RoutingConfig(entry = g("s0"), routers = chain + RouterConfig.Note("s${RouteWalk.MAX_HOPS + 1}", "End"))
        assertTrue(flagged { RouteWalk.terminal(config, text("x")) }.message!!.contains("in a row"))
        val shorter = RoutingConfig(entry = g("s1"), routers = config.routers)
        assertEquals(config.routers.last(), RouteWalk.terminal(shorter, text("x")))
    }

    @Test
    fun aRuleThatRunsPastItsDeadlineIsFlagged() {
        val slow = RoutingConfig(entry = g("s"), routers = listOf(RouterConfig.Switch("s", "S", listOf(SwitchRule("(.*a){32}", g("notes"))), otherwise = g("notes")), note))
        assertTrue(flagged { RouteWalk.terminal(slow, text("a".repeat(31)), ruleDeadline = 30.milliseconds) }.message!!.contains("rule 1 of S took too long"))
    }
}

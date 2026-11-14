package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    fun theDefaultGraphSetsAReminderOnACueAndWritesANoteOtherwise() {
        val reminder = RoutingConfig.DEFAULT.router(g(RoutingConfig.REMINDER_ROUTER_ID))!!
        val notes = RoutingConfig.DEFAULT.router(g(RoutingConfig.NOTEBOOK_ROUTER_ID))!!
        for (cue in listOf(
            "remind me to call mom at five", "Remind me in ten minutes to check the oven", "please remind myself to stretch",
            "remember to buy milk", "don't let me forget the keys", "don’t let me forget the keys", "set a reminder for the dentist at 3",
            "I need an alarm at six tomorrow", "ping me at noon", "reminder: water the plants", "um, so, like, could you remind me to call",
        )) {
            assertEquals(reminder, RouteWalk.route(RoutingConfig.DEFAULT, text(cue)).router, cue)
        }
        for (plain in listOf(
            "anything at all", "the meeting went well, that reminds me of last year", "a reminder is something I rarely use",
            "one two three four five six seven eight remind me later",
        )) {
            assertEquals(notes, RouteWalk.route(RoutingConfig.DEFAULT, text(plain)).router, plain)
        }
    }

    @Test
    fun theFirstMatchingRuleWinsAndTheFallbackTakesTheRest() {
        assertEquals(reminder, RouteWalk.route(config, text("Remind me to call Sam, todo list later")).router)
        assertEquals(app, RouteWalk.route(config, text("add to my todo list: remind me later")).router)
        assertEquals(note, RouteWalk.route(config, text("thoughts on the garden")).router)
    }

    /** A ref names a router by namespace and id: the same id in Gravel's namespace is a different router. */
    @Test
    fun aRefIsMatchedByNamespaceAndId() {
        val mine = RouterConfig.Note("tasks", "My tasks")
        val both = config.copy(routers = config.routers + mine)
        assertEquals(app, RouteWalk.route(both, text("todo: buy milk")).router)
        val toMine = both.copy(routers = both.routers.map { if (it is RouterConfig.Switch) it.copy(rules = listOf(SwitchRule("todo", g("tasks")))) else it })
        assertEquals(mine, RouteWalk.route(toMine, text("todo: buy milk")).router)
    }

    @Test
    fun switchesChainAndADiamondIsFine() {
        val second = RouterConfig.Switch("second", "Second", listOf(SwitchRule("urgent", app.ref)), otherwise = g("notes"))
        val first = RouterConfig.Switch("first", "First", listOf(SwitchRule("work", g("second"))), otherwise = g("second"))
        val chained = RoutingConfig(entry = g("first"), routers = listOf(first, second, note, app))
        assertEquals(app, RouteWalk.route(chained, text("work: urgent fix")).router)
        assertEquals(note, RouteWalk.route(chained, text("work: later")).router)
        assertEquals(app, RouteWalk.route(chained, text("home: urgent fix")).router)
    }

    @Test
    fun theRouteCarriesAMatchForEverySwitchRuleThatMatchedOnTheWay() {
        val second = RouterConfig.Switch("second", "Second", listOf(SwitchRule("urgent", app.ref)), otherwise = g("notes"))
        val first = RouterConfig.Switch("first", "First", listOf(SwitchRule("^work:?", g("second"), cut = true)), otherwise = g("second"))
        val chained = RoutingConfig(entry = g("first"), routers = listOf(first, second, note, app))
        val route = RouteWalk.route(chained, text("work: urgent fix the build"))
        assertEquals(app, route.router)
        assertEquals(listOf(TextMatch("first", 0, 5, cut = true), TextMatch("second", 6, 12)), route.text.matches)
        assertEquals("urgent fix the build", route.text.unmatchedText, "only the rule that cuts takes its words out")
        assertEquals("work: urgent fix the build", route.text.text, "the text itself goes on whole")
        val fallback = RouteWalk.route(chained, text("home: later"))
        assertEquals(note, fallback.router)
        assertEquals(emptyList(), fallback.text.matches, "a fallback is no match")
    }

    /** A named group that takes no part in the match, an optional one left out, is not listed. */
    @Test
    fun aMatchCarriesTheNamedGroupsThatTookPart() {
        val spend = RouterConfig.Switch(
            "spend", "Spend",
            listOf(SwitchRule("^spent (?<amount>\\d+([.,]\\d+)?)( at (?P<payee>.+))?", app.ref)), otherwise = g("notes"),
        )
        val spending = RoutingConfig(entry = g("spend"), routers = listOf(spend, note, app))
        val placed = RouteWalk.route(spending, text("spent 3.50 at the bakery")).text
        assertEquals(listOf(MatchGroup("amount", 6, 10), MatchGroup("payee", 14, 24)), placed.matches.single().groups)
        assertEquals(mapOf("amount" to "3.50", "payee" to "the bakery"), placed.groupValues())
        assertEquals(listOf(MatchGroup("amount", 6, 8)), RouteWalk.route(spending, text("spent 12")).text.matches.single().groups)
    }

    /** A Time or Place rule reads the recording's time or location, which reach a router by grant alone, so it adds no match. */
    @Test
    fun onlyASwitchAddsAMatch() {
        val under = RoutingConfig(entry = g("split"), routers = listOf(split.copy(otherwise = g("hours")), hours, note, reminder, app))
        val route = RouteWalk.route(under, RoutedText("c1", "2026-09-23T12:00:00+02:00", "thoughts"))
        assertEquals(app, route.router)
        assertEquals(emptyList(), route.text.matches)
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
        assertEquals(app, RouteWalk.route(timed, RoutedText("c1", "2026-09-23T12:00:00+02:00", "x")).router, "Wednesday noon")
        assertEquals(reminder, RouteWalk.route(timed, RoutedText("c1", "2026-09-23T23:30:00+02:00", "x")).router, "late evening")
        assertEquals(reminder, RouteWalk.route(timed, RoutedText("c1", "2026-09-24T05:59:00+02:00", "x")).router, "the wrap holds to the end")
        assertEquals(note, RouteWalk.route(timed, RoutedText("c1", "2026-09-26T12:00:00+02:00", "x")).router, "Saturday noon")
        assertEquals(app, RouteWalk.route(timed, RoutedText("c1", "2026-09-23T12:00:00Z", "x")).router, "the offset the payload carries is the one that counts")
        val strict = timed.copy(routers = timed.routers.map { if (it is RouterConfig.Time) it.copy(otherwise = null) else it })
        assertTrue(flagged { RouteWalk.route(strict, RoutedText("c1", "2026-09-26T12:00:00+02:00", "x")).router }.message!!.contains("no rule of Hours matched"))
        assertTrue(flagged { RouteWalk.route(timed, RoutedText("c1", "noon", "x")).router }.message!!.contains("could not be read"))
        // Under a switch: a reminder cue goes to the reminders, the rest by the hour.
        val under = RoutingConfig(entry = g("split"), routers = listOf(split.copy(otherwise = g("hours")), hours, note, reminder, app))
        assertEquals(reminder, RouteWalk.route(under, RoutedText("c1", "2026-09-23T12:00:00+02:00", "remind me later")).router)
        assertEquals(app, RouteWalk.route(under, RoutedText("c1", "2026-09-23T12:00:00+02:00", "thoughts")).router)
    }

    @Test
    fun aPlaceRouterForwardsByWhereTheRecordingWasMadeAndHasAPlaceForNoLocation() {
        val home = Spot("Home", 52.5200, 13.4050, 800)
        val places = RouterConfig.Place(
            "places", "Places",
            listOf(PlaceRule(home, within = true, app.ref), PlaceRule(Spot("Town", 52.5200, 13.4050, 20_000), within = false, g("reminders"))),
            otherwise = g("notes"),
            unknown = g("reminders"),
        )
        val placed = RoutingConfig(entry = g("places"), routers = listOf(places, note, reminder, app))
        fun at(latitude: Double, longitude: Double) = RoutedText("c1", "2026-09-22T17:00:00+02:00", "x", RoutedLocation(latitude, longitude))
        assertEquals(app, RouteWalk.route(placed, at(52.5210, 13.4060)).router, "at home")
        assertEquals(note, RouteWalk.route(placed, at(52.5500, 13.4050)).router, "in town but not at home")
        assertEquals(reminder, RouteWalk.route(placed, at(48.8566, 2.3522)).router, "far away")
        assertEquals(reminder, RouteWalk.route(placed, text("x")).router, "no location goes where the router says")
        val noUnknown = placed.copy(routers = placed.routers.map { if (it is RouterConfig.Place) it.copy(unknown = null) else it })
        assertEquals(note, RouteWalk.route(noUnknown, text("x")).router, "else to the fallback")
        val strict = noUnknown.copy(routers = noUnknown.routers.map { if (it is RouterConfig.Place) it.copy(otherwise = null) else it })
        assertTrue(flagged { RouteWalk.route(strict, text("x")).router }.message!!.contains("no rule of Places matched"))
    }

    @Test
    fun noMatchWithoutAFallbackIsFlaggedAsNotRouted() {
        val strict = config.copy(routers = config.routers.map { if (it is RouterConfig.Switch) it.copy(otherwise = null) else it })
        assertTrue(flagged { RouteWalk.route(strict, text("thoughts on the garden")).router }.message!!.contains("no rule of Split matched"))
    }

    @Test
    fun aGraphTheValidationWouldRefuseStillEndsInAFlagNotALoop() {
        val missingEntry = RoutingConfig(entry = g("gone"), routers = listOf(note))
        assertTrue(flagged { RouteWalk.route(missingEntry, text("x")).router }.message!!.contains("entry router"))
        val missingTarget = RoutingConfig(entry = g("s"), routers = listOf(RouterConfig.Switch("s", "S", emptyList(), otherwise = g("gone"))))
        assertTrue(flagged { RouteWalk.route(missingTarget, text("x")).router }.message!!.contains("does not exist"))
        val circle = RoutingConfig(
            entry = g("a"),
            routers = listOf(RouterConfig.Switch("a", "A", emptyList(), otherwise = g("b")), RouterConfig.Switch("b", "B", emptyList(), otherwise = g("a"))),
        )
        assertTrue(flagged { RouteWalk.route(circle, text("x")).router }.message!!.contains("circle at A"))
        val badRule = RoutingConfig(entry = g("s"), routers = listOf(RouterConfig.Switch("s", "S", listOf(SwitchRule("(open", g("notes")))), note))
        assertTrue(flagged { RouteWalk.route(badRule, text("x")).router }.message!!.contains("rule 1 of S is not a valid"))
    }

    @Test
    fun aChainLongerThanTheHopCapIsFlagged() {
        val chain = (0..RouteWalk.MAX_HOPS).map { RouterConfig.Switch("s$it", "S$it", emptyList(), otherwise = g("s${it + 1}")) }
        val config = RoutingConfig(entry = g("s0"), routers = chain + RouterConfig.Note("s${RouteWalk.MAX_HOPS + 1}", "End"))
        assertTrue(flagged { RouteWalk.route(config, text("x")).router }.message!!.contains("in a row"))
        val shorter = RoutingConfig(entry = g("s1"), routers = config.routers)
        assertEquals(config.routers.last(), RouteWalk.route(shorter, text("x")).router)
    }

    @Test
    fun aRuleThatWouldBlowUpABacktrackingEngineIsDecidedAtOnce() {
        val slow = RoutingConfig(entry = g("s"), routers = listOf(RouterConfig.Switch("s", "S", listOf(SwitchRule("(.*a){32}", g("s"))), otherwise = g("notes")), note))
        assertEquals(note, RouteWalk.route(slow, text("a".repeat(31))).router)
    }
}

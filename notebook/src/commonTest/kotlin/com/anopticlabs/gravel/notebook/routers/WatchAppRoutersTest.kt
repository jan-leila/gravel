package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.pipeline.CaptureFailureKind
import com.anopticlabs.gravel.notebook.pipeline.CaptureProcessingException
import com.anopticlabs.gravel.notebook.storage.NotebookJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WatchAppRoutersTest {

    private val transit = "11111111-2222-3333-4444-555555555555"
    private val timer = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private fun g(id: String) = RouterRef.gravel(id)

    private val note = RouterConfig.Note("notes", "Notes")
    private val departures = RouterConfig.WatchApp("departures", "Departures", transit, "station", params = listOf("station"))
    private val byApp = RouterConfig.OpenApp("by-app", "By app", listOf(OpenAppRule(transit, "Transit", g("departures"))), otherwise = g("notes"))
    private val config = RoutingConfig(entry = g("by-app"), routers = listOf(byApp, note, departures))

    private fun text(said: String, openApp: String? = null) = RoutedText("c1", "2026-09-22T17:00:00+02:00", said, openApp = openApp)

    private fun refused(config: RoutingConfig): String = assertFailsWith<IllegalArgumentException> { config.requireWellFormed() }.message!!

    @Test
    fun theOpenAppDecidesAndAnyOtherAppOrNoneTakesTheFallback() {
        assertEquals(departures, RouteWalk.route(config, text("Alexanderplatz", openApp = transit)).router)
        assertEquals(note, RouteWalk.route(config, text("Alexanderplatz", openApp = timer)).router)
        assertEquals(note, RouteWalk.route(config, text("Alexanderplatz")).router)
    }

    @Test
    fun aTrialDecidesByTheAppOpenNow() {
        val open = RoutingTrial.run(config, "Alexanderplatz", "2026-09-22T17:00:00+02:00", null, openApp = transit)
        val closed = RoutingTrial.run(config, "Alexanderplatz", "2026-09-22T17:00:00+02:00", null)
        assertEquals(departures, (open as Trial.Ended).router)
        assertEquals(note, (closed as Trial.Ended).router)
    }

    @Test
    fun bothKindsRoundTripUnderTheirTagsWithoutANamespace() {
        config.requireWellFormed()
        val json = NotebookJson.encodeToString(config)
        assertEquals(config, NotebookJson.decodeFromString<RoutingConfig>(json))
        val routers = NotebookJson.parseToJsonElement(json).jsonObject.getValue("routers").jsonArray
        assertEquals(listOf("open_app", "note", "watch_app"), routers.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertTrue(routers.none { it.jsonObject.containsKey("namespace") })
    }

    @Test
    fun anOpenAppRouterForwardsAndItsRulesNeedAnAppALabelAndAnExistingTarget() {
        assertTrue(byApp.forwardsText)
        assertEquals(listOf(g("departures"), g("notes")), byApp.forwards)
        fun with(rule: OpenAppRule, otherwise: RouterRef? = g("notes")) = config.copy(routers = listOf(byApp.copy(rules = listOf(rule), otherwise = otherwise), note, departures))
        assertTrue("pick one" in refused(with(OpenAppRule("", "Transit", g("notes")))))
        assertTrue("pick one" in refused(with(OpenAppRule(timer.uppercase(), "Transit", g("notes")))))
        assertTrue("app name" in refused(with(OpenAppRule(transit, " ", g("notes")))))
        assertTrue("does not exist" in refused(with(OpenAppRule(transit, "Transit", g("nowhere")))))
        assertTrue("does not exist" in refused(with(OpenAppRule(transit, "Transit", g("notes")), otherwise = g("nowhere"))))
        assertTrue("lead back" in refused(with(OpenAppRule(transit, "Transit", g("by-app")))))
    }

    @Test
    fun aRuleIsLabelledWithAsMuchOfTheAppsNameAsFits() {
        assertEquals(OpenAppRule(transit, "Transit", g("notes")), OpenAppRule.of(WatchAppInfo(transit, " Transit "), g("notes")))
        assertEquals(RoutingConfig.MAX_NAME_CHARS, OpenAppRule.of(WatchAppInfo(transit, "x".repeat(100)), g("notes")).label.length)
        assertEquals(transit, OpenAppRule.of(WatchAppInfo(transit, "  "), g("notes")).label)
    }

    @Test
    fun aWatchAppRouterNeedsAnAppARouterAndDeclaredParameters() {
        fun with(router: RouterConfig.WatchApp) = RoutingConfig(entry = router.ref, routers = listOf(router))
        with(departures).requireWellFormed()
        assertTrue("pick one" in refused(with(departures.copy(app = ""))))
        assertTrue("pick one" in refused(with(departures.copy(router = "Not An Id"))))
        assertTrue("cannot read" in refused(with(departures.copy(params = listOf("Station")))))
        assertTrue("does not declare" in refused(with(departures.copy(fields = listOf(FieldMapping("platform", "platform"))))))
        assertTrue("namespace" in refused(with(departures.copy(namespace = "a".repeat(64)))))
    }

    @Test
    fun aWatchAppRouterSharesByGrantAloneAndFillsItsDeclaredParameters() {
        assertEquals(Need.entries.toSet(), departures.needed())
        assertEquals(Need.entries.toSet(), departures.asked())
        val never = departures.copy(shares = Shares(location = Grant.NEVER, recordedAt = Grant.ALWAYS))
        assertEquals(setOf(Need.RECORDED_AT), never.needed())
        assertEquals(setOf(Need.RECORDED_AT), never.shared(emptySet()))
        val matched = text("departures from Alexanderplatz").copy(matches = listOf(TextMatch("split", 0, 30, groups = listOf(MatchGroup("station", 16, 30)))))
        assertEquals(mapOf("station" to "Alexanderplatz"), departures.fieldsFor(matched))
        assertEquals(emptyMap(), departures.copy(fields = listOf(FieldMapping("station", null))).fieldsFor(matched))
    }

    @Test
    fun theKindsAreNamedAndTheirIdsSpelledWithADash() {
        assertEquals("Open app", RoutingEdits.kindName(byApp))
        assertEquals("open-app", RoutingEdits.idPrefix(byApp))
        assertEquals("watch-app", RoutingEdits.idPrefix(departures))
        assertTrue(RoutingConfig.isRouterId(RoutingEdits.newId(config, RoutingEdits.idPrefix("Watch app"))))
    }

    @Test
    fun movingARouterMovesTheRulesOfAnOpenAppRouterToo() {
        val renamed = departures.copy(id = "trains")
        val moved = RoutingEdits.withRouter(config, renamed, replacing = departures.ref)
        assertEquals(g("trains"), (moved.router(g("by-app")) as RouterConfig.OpenApp).rules.single().router)
        moved.requireWellFormed()
    }

    private class FakeWatchApps(var result: WatchAppSend = WatchAppSend.DELIVERED) : WatchApps {
        val sent = mutableListOf<Triple<String, String, String>>()
        override suspend fun open(): String? = null
        override suspend fun installed(): List<WatchAppInfo> = emptyList()
        override suspend fun targets(): List<WatchAppTarget> = emptyList()
        override suspend fun send(app: String, router: String, payload: String): WatchAppSend {
            sent += Triple(app, router, payload)
            return result
        }
    }

    @Test
    fun aDeliveryHandsTheAppsRouterTheNetworkPayloadWithOnlyWhatIsShared() = runBlocking<Unit> {
        val watch = FakeWatchApps()
        val here = RoutedLocation(52.52, 13.405, 30f)
        WatchAppRouterDelivery(watch).deliver(departures, text("Alexanderplatz", openApp = transit).copy(location = here), setOf(Need.RECORDED_AT))
        val (app, router, payload) = watch.sent.single()
        assertEquals(transit to "station", app to router)
        val body = NotebookJson.parseToJsonElement(payload).jsonObject
        assertEquals(setOf("api_version", "capture_id", "recorded_at", "text", "unmatched_text"), body.keys)
        assertEquals("Alexanderplatz", body.getValue("text").jsonPrimitive.content)
        assertNull(body["location"])
    }

    @Test
    fun aGoneAppFlagsNoWatchWaitsAndAnAppThatDidNotTakeItIsTriedAgain() {
        fun failed(result: WatchAppSend): CaptureProcessingException =
            assertFailsWith { runBlocking { WatchAppRouterDelivery(FakeWatchApps(result)).deliver(departures, text("x"), emptySet()) } }
        val gone = failed(WatchAppSend.GONE)
        assertEquals(CaptureFailureKind.FLAG to CaptureFailureReason.ROUTER_UNAVAILABLE, gone.kind to gone.reason)
        assertEquals(CaptureFailureKind.WAIT, failed(WatchAppSend.NO_WATCH).kind)
        assertEquals(CaptureFailureKind.RETRY, failed(WatchAppSend.NOT_TAKEN).kind)
    }

    @Test
    fun theDeliveriesDispatchAWatchAppRouterToItsDeliverer() = runBlocking<Unit> {
        val watch = FakeWatchApps()
        RouterDeliveries(watchApp = WatchAppRouterDelivery(watch)).deliver(departures, text("x"), emptySet())
        assertEquals(1, watch.sent.size)
        val e = assertFailsWith<CaptureProcessingException> { RouterDeliveries().deliver(departures, text("x"), emptySet()) }
        assertEquals(CaptureFailureReason.ROUTER_UNAVAILABLE, e.reason)
    }
}

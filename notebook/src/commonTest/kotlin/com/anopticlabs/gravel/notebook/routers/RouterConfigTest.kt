package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.storage.NotebookJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RouterConfigTest {

    private val signer = "a".repeat(64)
    private fun g(id: String) = RouterRef.gravel(id)

    private val note = RouterConfig.Note("notes", "Notes")
    private val reminder = RouterConfig.Reminder("reminders", "Reminders")
    private val app = RouterConfig.App("tasks", "Tasks app", "com.example.tasks", "com.example.tasks.NotebookRouter", signer)
    private val network = RouterConfig.Network("hook", "Webhook", "https://example.test/notes", bearerToken = "s3cret")
    private val switch = RouterConfig.Switch("split", "Split", listOf(SwitchRule("^remind me", g("reminders")), SwitchRule("todo", app.ref)), otherwise = g("notes"))
    private val full = RoutingConfig(entry = g("split"), routers = listOf(switch, note, reminder, app, network))

    private fun refused(config: RoutingConfig): String = assertFailsWith<IllegalArgumentException> { config.requireWellFormed() }.message!!

    private fun one(router: RouterConfig) = RoutingConfig(entry = router.ref, routers = listOf(router))

    @Test
    fun theDefaultIsTheReminderCuesBeforeAReminderAndANoteRouterAndIsWellFormed() {
        RoutingConfig.DEFAULT.requireWellFormed()
        assertEquals(g(RoutingConfig.CUES_ROUTER_ID), RoutingConfig.DEFAULT.entry)
        val cues = RoutingConfig.DEFAULT.router(g(RoutingConfig.CUES_ROUTER_ID)) as RouterConfig.Switch
        assertEquals(RoutingConfig.REMINDER_CUES, cues.rules.map { it.pattern })
        assertTrue(cues.rules.all { it.router == g(RoutingConfig.REMINDER_ROUTER_ID) && it.ignoreCase })
        assertEquals(g(RoutingConfig.NOTEBOOK_ROUTER_ID), cues.otherwise)
        assertEquals(RouterConfig.Reminder("reminder", "Reminder"), RoutingConfig.DEFAULT.router(g(RoutingConfig.REMINDER_ROUTER_ID)))
        assertEquals(RouterConfig.Note("notebook", "Notebook"), RoutingConfig.DEFAULT.router(g(RoutingConfig.NOTEBOOK_ROUTER_ID)))
    }

    /** The description is the user's line for a router: optional, absent from the stored JSON when unset, one bounded line when set. */
    @Test
    fun aDescriptionIsOptionalAndOneBoundedLine() {
        val described = switch.copy(description = "Sorts what I say into tasks and notes")
        val config = full.copy(routers = full.routers.map { if (it.ref == switch.ref) described else it })
        config.requireWellFormed()
        assertEquals(config, NotebookJson.decodeFromString(RoutingConfig.serializer(), NotebookJson.encodeToString(config)))
        assertFalse(NotebookJson.encodeToString(RouterConfig.serializer(), note).contains("description"))
        assertEquals(RouterConfig.Note("n", "N", description = "Mine"), NotebookJson.decodeFromString(RouterConfig.serializer(), """{"type":"note","id":"n","name":"N","description":"Mine"}"""))
        for (bad in listOf(" ", " padded", "two\nlines", "x".repeat(RoutingConfig.MAX_DESCRIPTION_CHARS + 1))) {
            assertTrue(refused(one(note.copy(description = bad))).contains("description"), bad)
        }
        one(note.copy(description = "x".repeat(RoutingConfig.MAX_DESCRIPTION_CHARS))).requireWellFormed()
        assertEquals("Mine", RoutingEdits.blurb(note.copy(description = "Mine"), full))
        assertEquals(RoutingEdits.summary(note, full), RoutingEdits.blurb(note, full))
    }

    @Test
    fun everyKindRoundTripsThroughJsonUnderItsTypeTagWithOnlyAnAppsNamespaceWritten() {
        full.requireWellFormed()
        val json = NotebookJson.encodeToString(full)
        assertEquals(full, NotebookJson.decodeFromString<RoutingConfig>(json))
        val top = NotebookJson.parseToJsonElement(json).jsonObject
        val routers = top.getValue("routers").jsonArray
        assertEquals(listOf("switch", "note", "reminder", "app", "network"), routers.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals(listOf(null, null, null, signer, null), routers.map { it.jsonObject["namespace"]?.jsonPrimitive?.content })
        assertEquals(1, top.getValue("v").jsonPrimitive.content.toInt())
        assertEquals(mapOf("id" to "split"), top.getValue("entry").jsonObject.mapValues { it.value.jsonPrimitive.content })
        val rule = routers[0].jsonObject.getValue("rules").jsonArray[1].jsonObject
        assertEquals(mapOf("namespace" to signer, "id" to "tasks"), rule.getValue("router").jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertFalse(rule.containsKey("target"))
        assertEquals(mapOf("id" to "reminders"), routers[0].jsonObject.getValue("rules").jsonArray[0].jsonObject.getValue("router").jsonObject.mapValues { it.value.jsonPrimitive.content })
    }

    /** Grants are stored under the words of the API and left out while untouched, so an older file reads as "ask about everything". */
    @Test
    fun needsAndGrantsRoundTripAndAreLeftOutWhileUntouched() {
        val untouched = NotebookJson.parseToJsonElement(NotebookJson.encodeToString(full)).jsonObject.getValue("routers").jsonArray
        assertFalse(untouched[3].jsonObject.containsKey("shares") || untouched[3].jsonObject.containsKey("needs"))
        assertFalse(untouched[4].jsonObject.containsKey("shares"))
        val wanting = app.copy(needs = setOf(Need.LOCATION, Need.RECORDED_AT), shares = Shares(location = Grant.ALWAYS, recordedAt = Grant.NEVER))
        val json = NotebookJson.encodeToString(RouterConfig.serializer(), wanting)
        val fields = NotebookJson.parseToJsonElement(json).jsonObject
        assertEquals(listOf("location", "recorded_at"), fields.getValue("needs").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(mapOf("location" to "always", "recordedAt" to "never"), fields.getValue("shares").jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals(wanting, NotebookJson.decodeFromString(RouterConfig.serializer(), json))
        val asking = NotebookJson.decodeFromString(RouterConfig.serializer(), """{"type":"app","id":"t","name":"T","packageName":"a.b","receiverClass":"a.b.R","namespace":"$signer"}""")
        assertEquals(Shares(Grant.ASK, Grant.ASK), (asking as RouterConfig.App).shares)
        assertEquals(emptySet(), asking.needs)
    }

    /** What leaves the phone: an app's declared needs, a server's every part not refused, nothing from Gravel's own kinds. */
    @Test
    fun whatARouterNeedsAsksAboutAndSharesFollowsItsKindAndGrants() {
        val always = Shares(Grant.ALWAYS, Grant.ALWAYS)
        assertEquals(emptySet(), app.needed())
        assertEquals(emptySet(), app.copy(shares = always).shared(emptySet()), "an app gets only what it declared")
        val wanting = app.copy(needs = setOf(Need.LOCATION))
        assertEquals(setOf(Need.LOCATION), wanting.needed())
        assertEquals(setOf(Need.LOCATION), wanting.asked())
        assertEquals(emptySet(), wanting.shared(emptySet()))
        assertEquals(setOf(Need.LOCATION), wanting.shared(setOf(Need.LOCATION, Need.RECORDED_AT)), "a one-time grant covers only what is needed")
        assertEquals(setOf(Need.LOCATION), wanting.copy(shares = always).shared(emptySet()))
        assertEquals(emptySet(), wanting.copy(shares = always).asked())
        assertEquals(emptySet(), wanting.copy(shares = Shares(Grant.NEVER, Grant.ASK)).shared(setOf(Need.LOCATION)), "never is never, even for this one time")
        assertEquals(setOf(Need.LOCATION, Need.RECORDED_AT), network.needed())
        assertEquals(setOf(Need.LOCATION, Need.RECORDED_AT), network.asked())
        assertEquals(setOf(Need.RECORDED_AT), network.copy(shares = Shares(Grant.NEVER, Grant.ASK)).needed())
        assertEquals(setOf(Need.RECORDED_AT), network.copy(shares = Shares(Grant.NEVER, Grant.ALWAYS)).shared(emptySet()))
        for (own in listOf(note, reminder, switch)) {
            assertEquals(null, own.shares)
            assertEquals(emptySet(), own.needed())
            assertEquals(emptySet(), own.asked())
            assertEquals(emptySet(), own.shared(setOf(Need.LOCATION)))
        }
    }

    /** The flag is written only when set, so an untouched routing file does not change; a switch cannot ask. */
    @Test
    fun aConfirmationFlagRoundTripsOnTheKindsThatConsumeTheTextAndIsLeftOutWhenOff() {
        val untouched = NotebookJson.parseToJsonElement(NotebookJson.encodeToString(full)).jsonObject.getValue("routers").jsonArray
        assertTrue(untouched.none { it.jsonObject.containsKey("confirm") })
        for (router in listOf(note, reminder, app, network)) {
            assertFalse(router.confirm)
            val careful = when (router) {
                is RouterConfig.Note -> router.copy(confirm = true)
                is RouterConfig.Reminder -> router.copy(confirm = true)
                is RouterConfig.App -> router.copy(confirm = true)
                is RouterConfig.Network -> router.copy(confirm = true)
                is RouterConfig.Switch, is RouterConfig.Time, is RouterConfig.Place -> error("not here")
            }
            val json = NotebookJson.encodeToString(RouterConfig.serializer(), careful)
            assertEquals("true", NotebookJson.parseToJsonElement(json).jsonObject.getValue("confirm").jsonPrimitive.content)
            assertEquals(careful, NotebookJson.decodeFromString(RouterConfig.serializer(), json))
            assertTrue(NotebookJson.decodeFromString(RouterConfig.serializer(), json).confirm)
        }
        assertFalse(switch.confirm)
        assertEquals(full.copy(routers = full.routers.map { if (it is RouterConfig.Note) it.copy(confirm = true) else it }).let { RoutingExport.decode(RoutingExport.encode(it, TrustEntries.EMPTY)).routing }.router(g("notes"))!!.confirm, true)
    }

    private val home = Spot("Home", 52.52, 13.405, 800)
    private val places = RouterConfig.Place("places", "Places", listOf(PlaceRule(home, within = true, g("reminders"))), otherwise = g("notes"), unknown = g("notes"))

    @Test
    fun aPlaceRouterRoundTripsAndItsSpotsAreBounded() {
        val placed = RoutingConfig(entry = g("places"), routers = listOf(places, note, reminder))
        placed.requireWellFormed()
        val json = NotebookJson.encodeToString(placed)
        assertEquals(placed, NotebookJson.decodeFromString<RoutingConfig>(json))
        val router = NotebookJson.parseToJsonElement(json).jsonObject.getValue("routers").jsonArray[0].jsonObject
        assertEquals("place", router.getValue("type").jsonPrimitive.content)
        assertEquals(setOf("label", "latitude", "longitude", "radiusMeters"), router.getValue("rules").jsonArray[0].jsonObject.getValue("spot").jsonObject.keys)
        fun with(vararg rules: PlaceRule, otherwise: RouterRef? = g("notes"), unknown: RouterRef? = null) =
            RoutingConfig(entry = g("places"), routers = listOf(places.copy(rules = rules.toList(), otherwise = otherwise, unknown = unknown), note, reminder))
        with().requireWellFormed()
        assertTrue(refused(with(PlaceRule(home.copy(label = " "), true, g("notes")))).contains("needs a place name"))
        assertTrue(refused(with(PlaceRule(home.copy(latitude = 91.0), true, g("notes")))).contains("needs a latitude, a longitude and a radius"))
        assertTrue(refused(with(PlaceRule(home.copy(radiusMeters = 5), true, g("notes")))).contains("needs a latitude, a longitude and a radius"))
        assertTrue(refused(with(PlaceRule(home, true, g("nowhere")))).contains("forwards to a router that does not exist"))
        assertTrue(refused(with(otherwise = g("nowhere"))).contains("falls back to a router that does not exist"))
        assertTrue(refused(with(unknown = g("nowhere"))).contains("without a location to a router that does not exist"))
        assertTrue(refused(RoutingConfig(entry = g("places"), routers = listOf(places.copy(namespace = signer), note, reminder))).contains("Gravel's own"))
        // A circle through a Place router's no-location target is a circle.
        val back = RouterConfig.Switch("back", "Back", emptyList(), otherwise = g("places"))
        assertTrue(refused(RoutingConfig(entry = g("notes"), routers = listOf(note, reminder, places.copy(unknown = g("back")), back))).contains("lead back to"))
    }

    @Test
    fun theNeedsMetaDataIsACommaListWhoseUnknownWordsAreIgnored() {
        assertEquals(emptySet(), RouterApi.parseNeeds(null))
        assertEquals(emptySet(), RouterApi.parseNeeds(""))
        assertEquals(setOf(Need.LOCATION), RouterApi.parseNeeds("location"))
        assertEquals(setOf(Need.LOCATION, Need.RECORDED_AT), RouterApi.parseNeeds(" recorded_at , location "))
        assertEquals(setOf(Need.RECORDED_AT), RouterApi.parseNeeds("recorded_at,mood,"))
        assertEquals(emptySet(), RouterApi.parseNeeds("Location"))
    }

    @Test
    fun aConfigFromANewerVersionOrWithoutRoutersIsRefused() {
        assertTrue(refused(full.copy(version = 2)).contains("newer version"))
        assertTrue(refused(RoutingConfig(entry = g("x"), routers = emptyList())).contains("at least one"))
        assertTrue(refused(RoutingConfig(entry = g("r0"), routers = (0..RoutingConfig.MAX_ROUTERS).map { RouterConfig.Note("r$it", "R$it") })).contains("at most"))
    }

    @Test
    fun idsAndNamesAreShapedAndUniqueWithinANamespace() {
        assertTrue(refused(one(RouterConfig.Note("Bad Id", "x"))).contains("router id"))
        assertTrue(refused(one(RouterConfig.Note("a", " "))).contains("needs a name"))
        assertTrue(refused(one(RouterConfig.Note("a", " padded"))).contains("needs a name"))
        assertTrue(refused(one(RouterConfig.Note("a", "x".repeat(RoutingConfig.MAX_NAME_CHARS + 1)))).contains("needs a name"))
        assertTrue(refused(RoutingConfig(entry = g("a"), routers = listOf(RouterConfig.Note("a", "One"), RouterConfig.Reminder("a", "Two")))).contains("share the id a"))
        // The same id in another namespace is another router.
        RoutingConfig(entry = g("tasks"), routers = listOf(RouterConfig.Note("tasks", "Mine"), app)).requireWellFormed()
        assertTrue(RoutingConfig.isRouterId("a-1"))
        assertFalse(RoutingConfig.isRouterId("a_1"))
        assertFalse(RoutingConfig.isRouterId("a".repeat(RoutingConfig.MAX_ID_CHARS + 1)))
    }

    /** Nothing an app declares can pass as one of Gravel's own routers, and nothing of Gravel's own sits in a signer's namespace. */
    @Test
    fun gravelsKindsHaveNoNamespaceAndAnAppRouterHasItsSigners() {
        assertTrue(refused(one(RouterConfig.Note("a", "Notes", namespace = signer))).contains("cannot be in a namespace"))
        assertTrue(refused(one(RouterConfig.Reminder("a", "R", namespace = "other"))).contains("cannot be in a namespace"))
        assertTrue(refused(one(RouterConfig.Switch("a", "S", namespace = signer))).contains("cannot be in a namespace"))
        assertTrue(refused(one(network.copy(namespace = signer))).contains("cannot be in a namespace"))
        assertTrue(refused(one(app.copy(namespace = ""))).contains("signing certificate"))
        assertTrue(refused(one(app.copy(namespace = "gravel"))).contains("signing certificate"))
        assertTrue(refused(one(app.copy(namespace = "A".repeat(64)))).contains("signing certificate"))
        assertTrue(refused(one(app.copy(namespace = "a".repeat(63)))).contains("signing certificate"))
        one(app).requireWellFormed()
    }

    @Test
    fun theEntryAndEveryTargetMustExistByNamespaceAndId() {
        assertTrue(refused(full.copy(entry = g("nowhere"))).contains("entry router"))
        assertTrue(refused(full.copy(entry = RouterRef(signer, "split"))).contains("entry router"))
        assertTrue(refused(full.copy(routers = full.routers - reminder)).contains("Rule 1 of Split forwards"))
        assertTrue(refused(full.copy(routers = full.routers - note)).contains("Split falls back"))
        val wrongNamespace = full.copy(routers = full.routers.map { if (it is RouterConfig.Switch) it.copy(rules = listOf(SwitchRule("todo", g("tasks")))) else it })
        assertTrue(refused(wrongNamespace).contains("Rule 1 of Split forwards"))
    }

    @Test
    fun switchRulesAreBoundedPatterns() {
        fun with(vararg rules: SwitchRule) = full.copy(routers = full.routers.map { if (it is RouterConfig.Switch) it.copy(rules = rules.toList()) else it })
        assertTrue(refused(with(SwitchRule("(open", g("notes")))).contains("Rule 1 of Split is not a valid"))
        assertTrue(refused(with(SwitchRule("", g("notes")))).contains("Rule 1 of Split needs a pattern"))
        assertTrue(refused(with(SwitchRule("a".repeat(RoutingConfig.MAX_PATTERN_CHARS + 1), g("notes")))).contains("Rule 1 of Split needs a pattern"))
        assertTrue(refused(with(*Array(RoutingConfig.MAX_RULES + 1) { SwitchRule("a", g("notes")) })).contains("at most ${RoutingConfig.MAX_RULES} rules"))
        with(*Array(RoutingConfig.MAX_RULES) { SwitchRule("a", g("notes")) }).requireWellFormed()
        with().requireWellFormed()
    }

    @Test
    fun aCircleOfSwitchesIsRefusedWhereverItStarts() {
        val a = RouterConfig.Switch("a", "A", listOf(SwitchRule("x", g("b"))), otherwise = g("notes"))
        val b = RouterConfig.Switch("b", "B", emptyList(), otherwise = g("c"))
        val c = RouterConfig.Switch("c", "C", listOf(SwitchRule("y", g("a"))), otherwise = g("notes"))
        assertTrue(refused(RoutingConfig(entry = g("notes"), routers = listOf(note, a, b, c))).contains("lead back to"))
        assertTrue(refused(RoutingConfig(entry = g("notes"), routers = listOf(note, RouterConfig.Switch("s", "S", emptyList(), otherwise = g("s"))))).contains("lead back to S"))
        // A diamond shares a router without circling.
        val left = RouterConfig.Switch("l", "L", emptyList(), otherwise = g("notes"))
        val right = RouterConfig.Switch("r", "R", emptyList(), otherwise = g("notes"))
        RoutingConfig(entry = g("top"), routers = listOf(RouterConfig.Switch("top", "Top", listOf(SwitchRule("x", g("l"))), otherwise = g("r")), left, right, note)).requireWellFormed()
        // A Time router forwards too, so a circle through one is a circle.
        val hours = RouterConfig.Time("hours", "Hours", listOf(TimeRule(listOf(TimeCondition(TimeField.MONTH, 1, 12)), g("a"))), otherwise = g("notes"))
        val toHours = RouterConfig.Switch("a", "A", emptyList(), otherwise = g("hours"))
        assertTrue(refused(RoutingConfig(entry = g("notes"), routers = listOf(note, hours, toHours))).contains("lead back to"))
    }

    private val hours = RouterConfig.Time(
        "hours", "Hours",
        listOf(TimeRule(listOf(TimeCondition(TimeField.DAY_OF_WEEK, 1, 5), TimeCondition(TimeField.TIME_OF_DAY, 9 * 60, 17 * 60)), g("reminders"))),
        otherwise = g("notes"),
    )

    @Test
    fun aTimeRouterRoundTripsUnderItsTagWithTheFieldsAsTheirWords() {
        val timed = RoutingConfig(entry = g("hours"), routers = listOf(hours, note, reminder))
        timed.requireWellFormed()
        val json = NotebookJson.encodeToString(timed)
        assertEquals(timed, NotebookJson.decodeFromString<RoutingConfig>(json))
        val router = NotebookJson.parseToJsonElement(json).jsonObject.getValue("routers").jsonArray[0].jsonObject
        assertEquals("time", router.getValue("type").jsonPrimitive.content)
        val condition = router.getValue("rules").jsonArray[0].jsonObject.getValue("conditions").jsonArray[0].jsonObject
        assertEquals(mapOf("field" to "day_of_week", "from" to "1", "to" to "5"), condition.mapValues { it.value.jsonPrimitive.content })
        assertFalse(router.containsKey("namespace"))
    }

    @Test
    fun aTimeRoutersRulesAreBoundedConditionsOnExistingTargets() {
        fun with(vararg rules: TimeRule, otherwise: RouterRef? = g("notes")) = RoutingConfig(entry = g("hours"), routers = listOf(hours.copy(rules = rules.toList(), otherwise = otherwise), note, reminder))
        with().requireWellFormed()
        assertTrue(refused(with(TimeRule(emptyList(), g("notes")))).contains("needs 1 to 4 conditions"))
        assertTrue(refused(with(TimeRule(List(5) { TimeCondition(TimeField.MONTH, 1, 1) }, g("notes")))).contains("needs 1 to 4 conditions"))
        assertTrue(refused(with(TimeRule(listOf(TimeCondition(TimeField.TIME_OF_DAY, 0, 1440)), g("notes")))).contains("has a time of day outside its range"))
        assertTrue(refused(with(TimeRule(listOf(TimeCondition(TimeField.YEAR, 2027, 2026)), g("notes")))).contains("has a year outside its range"))
        assertTrue(refused(with(TimeRule(listOf(TimeCondition(TimeField.MONTH, 1, 1)), g("nowhere")))).contains("forwards to a router that does not exist"))
        assertTrue(refused(with(otherwise = g("nowhere"))).contains("falls back to a router that does not exist"))
        assertTrue(refused(with(*Array(RoutingConfig.MAX_RULES + 1) { TimeRule(listOf(TimeCondition(TimeField.MONTH, 1, 1)), g("notes")) })).contains("at most"))
        assertTrue(refused(RoutingConfig(entry = g("hours"), routers = listOf(hours.copy(namespace = signer), note, reminder))).contains("Gravel's own"))
    }

    @Test
    fun aNetworkRouterNeedsAnHttpsAddressAndABoundedToken() {
        fun with(url: String = network.url, token: String? = network.bearerToken) = full.copy(routers = full.routers.map { if (it is RouterConfig.Network) it.copy(url = url, bearerToken = token) else it })
        assertTrue(refused(with(url = "http://example.test/notes")).contains("https://"))
        assertTrue(refused(with(url = "https://")).contains("https://"))
        assertTrue(refused(with(url = "https:///path")).contains("https://"))
        assertTrue(refused(with(url = "https://example.test/a b")).contains("https://"))
        assertTrue(refused(with(url = "https://example.test/" + "a".repeat(RoutingConfig.MAX_URL_CHARS))).contains("https://"))
        assertTrue(refused(with(token = "")).contains("token"))
        assertTrue(refused(with(token = "a b")).contains("token"))
        assertTrue(refused(with(token = "t".repeat(RoutingConfig.MAX_TOKEN_CHARS + 1))).contains("token"))
        with(token = null).requireWellFormed()
        with(url = "https://192.168.1.10:8443/hook?x=1").requireWellFormed()
    }

    @Test
    fun anAppRouterNeedsAPackageAndAClassName() {
        fun with(pkg: String = app.packageName, cls: String = app.receiverClass) = full.copy(routers = full.routers.map { if (it is RouterConfig.App) it.copy(packageName = pkg, receiverClass = cls) else it })
        assertTrue(refused(with(pkg = "nodots")).contains("package name"))
        assertTrue(refused(with(pkg = "com.1bad.pkg")).contains("package name"))
        assertTrue(refused(with(pkg = "com.example.")).contains("package name"))
        assertTrue(refused(with(cls = "")).contains("class name"))
        assertTrue(refused(with(cls = "has space")).contains("class name"))
        assertTrue(refused(with(cls = "a".repeat(RoutingConfig.MAX_CLASS_CHARS + 1))).contains("class name"))
        with(cls = "com.example.tasks.Outer\$Inner").requireWellFormed()
    }
}

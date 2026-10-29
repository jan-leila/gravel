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

    private val note = RouterConfig.Note("notes", "Notes")
    private val reminder = RouterConfig.Reminder("reminders", "Reminders")
    private val app = RouterConfig.App("tasks", "Tasks app", "com.example.tasks", "com.example.tasks.NotebookRouter")
    private val network = RouterConfig.Network("hook", "Webhook", "https://example.test/notes", bearerToken = "s3cret")
    private val switch = RouterConfig.Switch("split", "Split", listOf(SwitchRule("^remind me", "reminders"), SwitchRule("todo", "tasks")), otherwise = "notes")
    private val full = RoutingConfig(entry = "split", routers = listOf(switch, note, reminder, app, network))

    private fun refused(config: RoutingConfig): String = assertFailsWith<IllegalArgumentException> { config.requireWellFormed() }.message!!

    @Test
    fun theDefaultIsOneNoteRouterAndIsWellFormed() {
        RoutingConfig.DEFAULT.requireWellFormed()
        assertEquals(RoutingConfig.NOTEBOOK_ROUTER_ID, RoutingConfig.DEFAULT.entry)
        assertEquals(listOf(RouterConfig.Note("notebook", "Notebook")), RoutingConfig.DEFAULT.routers)
    }

    @Test
    fun everyKindRoundTripsThroughJsonUnderItsTypeTag() {
        full.requireWellFormed()
        val json = NotebookJson.encodeToString(full)
        assertEquals(full, NotebookJson.decodeFromString<RoutingConfig>(json))
        val routers = NotebookJson.parseToJsonElement(json).jsonObject.getValue("routers").jsonArray
        assertEquals(listOf("switch", "note", "reminder", "app", "network"), routers.map { it.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals(1, NotebookJson.parseToJsonElement(json).jsonObject.getValue("v").jsonPrimitive.content.toInt())
    }

    @Test
    fun aConfigFromANewerVersionOrWithoutRoutersIsRefused() {
        assertTrue(refused(full.copy(version = 2)).contains("newer version"))
        assertTrue(refused(RoutingConfig(entry = "x", routers = emptyList())).contains("at least one"))
        assertTrue(refused(RoutingConfig(entry = "r0", routers = (0..RoutingConfig.MAX_ROUTERS).map { RouterConfig.Note("r$it", "R$it") })).contains("at most"))
    }

    @Test
    fun idsAndNamesAreShapedAndUnique() {
        assertTrue(refused(RoutingConfig(entry = "Bad Id", routers = listOf(RouterConfig.Note("Bad Id", "x")))).contains("router id"))
        assertTrue(refused(RoutingConfig(entry = "a", routers = listOf(RouterConfig.Note("a", " ")))).contains("needs a name"))
        assertTrue(refused(RoutingConfig(entry = "a", routers = listOf(RouterConfig.Note("a", " padded")))).contains("needs a name"))
        assertTrue(refused(RoutingConfig(entry = "a", routers = listOf(RouterConfig.Note("a", "x".repeat(RoutingConfig.MAX_NAME_CHARS + 1))))).contains("needs a name"))
        assertTrue(refused(RoutingConfig(entry = "a", routers = listOf(RouterConfig.Note("a", "One"), RouterConfig.Reminder("a", "Two")))).contains("share the id a"))
        assertTrue(RoutingConfig.isRouterId("a-1"))
        assertFalse(RoutingConfig.isRouterId("a_1"))
        assertFalse(RoutingConfig.isRouterId("a".repeat(RoutingConfig.MAX_ID_CHARS + 1)))
    }

    @Test
    fun theEntryAndEveryTargetMustExist() {
        assertTrue(refused(full.copy(entry = "nowhere")).contains("entry router"))
        assertTrue(refused(full.copy(routers = full.routers - reminder)).contains("Rule 1 of Split forwards"))
        assertTrue(refused(full.copy(routers = full.routers - note)).contains("Split falls back"))
    }

    @Test
    fun switchRulesAreBoundedPatterns() {
        fun with(vararg rules: SwitchRule) = full.copy(routers = full.routers.map { if (it is RouterConfig.Switch) it.copy(rules = rules.toList()) else it })
        assertTrue(refused(with(SwitchRule("(open", "notes"))).contains("Rule 1 of Split is not a valid"))
        assertTrue(refused(with(SwitchRule("", "notes"))).contains("Rule 1 of Split needs a pattern"))
        assertTrue(refused(with(SwitchRule("a".repeat(RoutingConfig.MAX_PATTERN_CHARS + 1), "notes"))).contains("Rule 1 of Split needs a pattern"))
        assertTrue(refused(with(*Array(RoutingConfig.MAX_RULES + 1) { SwitchRule("a", "notes") })).contains("at most ${RoutingConfig.MAX_RULES} rules"))
        with(*Array(RoutingConfig.MAX_RULES) { SwitchRule("a", "notes") }).requireWellFormed()
        with().requireWellFormed()
    }

    @Test
    fun aCircleOfSwitchesIsRefusedWhereverItStarts() {
        val a = RouterConfig.Switch("a", "A", listOf(SwitchRule("x", "b")), otherwise = "notes")
        val b = RouterConfig.Switch("b", "B", emptyList(), otherwise = "c")
        val c = RouterConfig.Switch("c", "C", listOf(SwitchRule("y", "a")), otherwise = "notes")
        assertTrue(refused(RoutingConfig(entry = "notes", routers = listOf(note, a, b, c))).contains("lead back to"))
        assertTrue(refused(RoutingConfig(entry = "notes", routers = listOf(note, RouterConfig.Switch("s", "S", emptyList(), otherwise = "s")))).contains("lead back to S"))
        // A diamond shares a router without circling.
        val left = RouterConfig.Switch("l", "L", emptyList(), otherwise = "notes")
        val right = RouterConfig.Switch("r", "R", emptyList(), otherwise = "notes")
        RoutingConfig(entry = "top", routers = listOf(RouterConfig.Switch("top", "Top", listOf(SwitchRule("x", "l")), otherwise = "r"), left, right, note)).requireWellFormed()
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

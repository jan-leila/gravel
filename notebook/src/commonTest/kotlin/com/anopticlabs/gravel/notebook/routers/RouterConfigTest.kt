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
    fun theDefaultIsOneNoteRouterAndIsWellFormed() {
        RoutingConfig.DEFAULT.requireWellFormed()
        assertEquals(g(RoutingConfig.NOTEBOOK_ROUTER_ID), RoutingConfig.DEFAULT.entry)
        assertEquals(listOf(RouterConfig.Note("notebook", "Notebook")), RoutingConfig.DEFAULT.routers)
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

package com.anopticlabs.gravel.notebook.routers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoutingExportTest {

    private val a = "a".repeat(64)
    private val routing = RoutingConfig(
        entry = RouterRef.gravel("split"),
        routers = listOf(
            RouterConfig.Switch("split", "Split", listOf(SwitchRule("^remind me", RouterRef.gravel("reminders"))), otherwise = RouterRef.gravel("notes")),
            RouterConfig.Note("notes", "Notes"),
            RouterConfig.Reminder("reminders", "Reminders"),
            RouterConfig.Network("hook", "Webhook", "https://example.test/notes", bearerToken = "s3cret"),
        ),
    )
    private val trust = TrustEntries(added = listOf(TrustedSigner(a, "Tasks", 5L)), removedDefaults = listOf("b".repeat(64)))

    private fun refused(json: String): String = assertFailsWith<IllegalArgumentException> { RoutingExport.decode(json) }.message!!

    @Test
    fun anExportRoundTripsAsReadableJsonWithItsVersion() {
        val json = RoutingExport.encode(routing, trust)
        assertTrue(json.startsWith("{\n"), "pretty printed")
        assertTrue(json.contains("\"v\": 1"))
        assertTrue(json.contains("s3cret"), "the token is in the file, as the screen warns")
        assertEquals(RoutingExport(routing = routing, trust = trust), RoutingExport.decode(json))
    }

    @Test
    fun whatIsNotAnExportIsRefusedWithAReason() {
        assertTrue(refused("not json").contains("not a routing export"))
        assertTrue(refused("""{"v":1}""").contains("not a routing export"))
        assertTrue(refused("""{"v":2,"routing":{"entry":{"id":"notebook"},"routers":[{"type":"note","id":"notebook","name":"Notebook"}]}}""").contains("newer version"))
        assertTrue(refused("""{"v":1,"routing":{"entry":{"id":"gone"},"routers":[{"type":"note","id":"notebook","name":"Notebook"}]}}""").contains("entry router"))
        assertTrue(refused(" ".repeat(RoutingExport.MAX_CHARS + 1)).contains("too large"))
    }

    @Test
    fun theTrustHalfIsOptionalAndCleanedOnTheWayIn() {
        val bare = RoutingExport.decode("""{"v":1,"routing":{"entry":{"id":"notebook"},"routers":[{"type":"note","id":"notebook","name":"Notebook"}]}}""")
        assertEquals(TrustEntries.EMPTY, bare.trust)
        val dirty = RoutingExport.decode(
            """{"v":1,"routing":{"entry":{"id":"notebook"},"routers":[{"type":"note","id":"notebook","name":"Notebook"}]},""" +
                """"trust":{"added":[{"sha256":"${a.uppercase()}","label":" T "},{"sha256":"bad"}],"removedDefaults":["x"]}}""",
        )
        assertEquals(TrustEntries(added = listOf(TrustedSigner(a, "T", 0L))), dirty.trust)
    }
}

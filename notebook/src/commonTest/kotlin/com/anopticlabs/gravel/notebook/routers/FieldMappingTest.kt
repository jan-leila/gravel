package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.storage.NotebookJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FieldMappingTest {

    private val signer = "a".repeat(64)
    private fun g(id: String) = RouterRef.gravel(id)

    private val actual = RouterConfig.App("actual", "Actual", "com.example.actual", "com.example.actual.Router", signer, params = listOf("amount", "payee", "notes"))
    private val spend = RouterConfig.Switch(
        "spend", "Spending",
        listOf(SwitchRule("^(add an? )?expense:? (?<amount>\\d+([.,]\\d+)?)( at (?<payee>.+))?", actual.ref, cut = true)),
        otherwise = g("notes"),
    )
    private val note = RouterConfig.Note("notes", "Notes")
    private val config = RoutingConfig(entry = g("spend"), routers = listOf(spend, actual, note))

    private fun routed(said: String) = RouteWalk.route(config, RoutedText("c1", "2026-09-22T17:00:00+02:00", said)).text

    @Test
    fun anAppsParametersTakeTheSameNamedGroupByDefault() {
        assertEquals(mapOf("amount" to "3.50", "payee" to "the bakery"), actual.fieldsFor(routed("expense 3.50 at the bakery")))
        assertEquals(mapOf("amount" to "12"), actual.fieldsFor(routed("expense 12")), "a group left out of the match fills nothing")
    }

    @Test
    fun aMappingPicksAnotherSourceOrNothing() {
        val mapped = actual.copy(
            fields = listOf(FieldMapping("payee", null), FieldMapping("notes", FieldMapping.UNMATCHED_TEXT), FieldMapping("amount", "payee")),
        )
        assertEquals(mapOf("notes" to "", "amount" to "the bakery"), mapped.fieldsFor(routed("expense 3.50 at the bakery")))
        val whole = actual.copy(fields = listOf(FieldMapping("notes", FieldMapping.WHOLE_TEXT)))
        assertEquals("expense 3.50 at the bakery", whole.fieldsFor(routed("expense 3.50 at the bakery"))["notes"])
    }

    @Test
    fun aNetworkRouterSendsTheParametersTheUserNamedAndTheNotebookKindsNone() {
        val hook = RouterConfig.Network("hook", "Hook", "https://example.test/", fields = listOf(FieldMapping("value", "amount"), FieldMapping("who", "payee")))
        assertEquals(mapOf("value" to "3.50", "who" to "the bakery"), hook.fieldsFor(routed("expense 3.50 at the bakery")))
        assertEquals(emptyMap(), note.fieldsFor(routed("expense 3.50 at the bakery")))
    }

    @Test
    fun aMappingThatCannotBeSentIsRefusedOnSave() {
        fun refused(app: RouterConfig.App): String =
            assertFailsWith<IllegalArgumentException> { config.copy(routers = listOf(spend, app, note)).requireWellFormed() }.message!!
        assertTrue(refused(actual.copy(fields = listOf(FieldMapping("total", "amount")))).contains("does not declare"))
        assertTrue(refused(actual.copy(fields = listOf(FieldMapping("amount", "not a group")))).contains("not a group name"))
        assertTrue(refused(actual.copy(fields = listOf(FieldMapping("amount", "payee"), FieldMapping("amount", null)))).contains("twice"))
        assertTrue(refused(actual.copy(params = listOf("Amount"))).contains("pick the router again"))
        val hook = RouterConfig.Network("hook", "Hook", "https://example.test/", fields = listOf(FieldMapping("", "amount")))
        assertFailsWith<IllegalArgumentException> { config.copy(routers = listOf(spend, actual, note, hook)).requireWellFormed() }
        config.copy(routers = listOf(spend, actual.copy(fields = listOf(FieldMapping("notes", FieldMapping.UNMATCHED_TEXT))), note)).requireWellFormed()
    }

    @Test
    fun aMappingRoundTripsAndAnOldConfigReadsWithoutOne() {
        val mapped = config.copy(routers = listOf(spend, actual.copy(fields = listOf(FieldMapping("payee", null), FieldMapping("notes", "payee"))), note))
        assertEquals(mapped, NotebookJson.decodeFromString(RoutingConfig.serializer(), NotebookJson.encodeToString(RoutingConfig.serializer(), mapped)))
        val old = """{"v":1,"entry":{"id":"s"},"routers":[{"type":"switch","id":"s","name":"S","rules":[{"pattern":"x","router":{"id":"n"}}],"otherwise":{"id":"n"}},{"type":"note","id":"n","name":"N"}]}"""
        val read = NotebookJson.decodeFromString(RoutingConfig.serializer(), old)
        assertEquals(false, (read.routers.first() as RouterConfig.Switch).rules.single().cut, "a rule saved before cutting existed cuts nothing")
    }

    @Test
    fun theParametersAnAppDeclaresAreReadLeniently() {
        assertEquals(listOf("amount", "payee"), RouterApi.parseParams(" amount, payee ,amount,Notes,,x-y"))
        assertEquals(emptyList(), RouterApi.parseParams(null))
        assertEquals(RoutingConfig.MAX_PARAMS, RouterApi.parseParams((1..40).joinToString(",") { "p$it" }).size)
    }

    @Test
    fun theGroupNamesAreThoseOfEverySwitchRule() {
        assertEquals(setOf("amount", "payee"), config.groupNames())
    }

    /** The trial runs the walk the drain runs, names each switch, and shows what an app would be sent. */
    @Test
    fun aTrialShowsWhereASentenceEndsAndWhatWouldBeSent() {
        val trial = RoutingTrial.run(config, "Add an expense: 3.50 at the bakery", "2026-09-22T17:00:00+02:00", null) as Trial.Ended
        assertEquals(actual, trial.router)
        val match = trial.matches.single()
        assertEquals("Spending", match.switchName)
        assertEquals("Add an expense: 3.50 at the bakery", match.matched)
        assertTrue(match.cut)
        assertEquals(listOf("amount" to "3.50", "payee" to "the bakery"), match.groups)
        assertEquals("", trial.unmatchedText)
        assertEquals(mapOf("amount" to "3.50", "payee" to "the bakery"), trial.fields)
        val plain = RoutingTrial.run(config, "thoughts", "2026-09-22T17:00:00+02:00", null) as Trial.Ended
        assertEquals(note, plain.router)
        assertEquals(emptyList(), plain.matches)
        val strict = config.copy(routers = listOf(spend.copy(otherwise = null), actual, note))
        assertTrue((RoutingTrial.run(strict, "thoughts", "2026-09-22T17:00:00+02:00", null) as Trial.NotRouted).reason.contains("no rule of Spending matched"))
    }
}

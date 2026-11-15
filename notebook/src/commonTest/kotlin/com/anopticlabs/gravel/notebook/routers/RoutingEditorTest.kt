package com.anopticlabs.gravel.notebook.routers

import com.anopticlabs.gravel.notebook.llm.Prompts
import com.anopticlabs.gravel.notebook.pipeline.CaptureText
import com.anopticlabs.gravel.notebook.storage.NotebookJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class RoutingEditorTest {

    private class FakeText(vararg replies: String) : CaptureText {
        private val replies = replies.toMutableList()
        val prompts = mutableListOf<Pair<String, String>>()
        override suspend fun checkReady() = Unit
        override suspend fun prepare() = Unit
        override suspend fun release() = Unit
        override suspend fun generate(system: String, user: String, maxTokens: Int): String {
            prompts += system to user
            assertEquals(RoutingEditor.REPLY_TOKENS, maxTokens)
            return replies.removeAt(0)
        }
    }

    private fun g(id: String) = RouterRef.gravel(id)
    private val home = Spot("Home", 52.52, 13.405, 100)
    private val hook = RouterConfig.Network("hook", "Hook", "https://example.test/a", bearerToken = "s3cret")
    private val places = RouterConfig.Place("places", "Places", listOf(PlaceRule(home, within = true, router = g("hook"))), otherwise = g("notebook"))
    private val config = RoutingConfig(entry = g("places"), routers = listOf(places, hook, RouterConfig.Note("notebook", "Notebook")))

    private fun json(config: RoutingConfig) = NotebookJson.encodeToString(RoutingConfig.serializer(), config)

    /** What the model would answer: the routing as it saw it, with [edit] applied. */
    private fun answer(edit: (RoutingConfig) -> RoutingConfig) = json(edit(RoutingSecrets.redact(config).config))

    private fun withReminder(seen: RoutingConfig) = seen.copy(routers = seen.routers + RouterConfig.Reminder("reminder", "Reminder"))

    private fun refused(block: suspend () -> Unit): RoutingEditRefused.Why = assertFailsWith<RoutingEditRefused> { runBlocking { block() } }.why

    @Test
    fun aProposalIsValidatedWithItsSecretsBackAndKeptWithoutThem() = runBlocking<Unit> {
        val text = FakeText("<think>adding one</think>```json\n${answer(::withReminder)}\n```")
        val proposed = RoutingEditor.propose(text, config, "  add a reminder router ")
        assertEquals(withReminder(config), proposed.config)
        assertEquals(listOf("Added Reminder 'Reminder': sets a reminder from the text."), proposed.change.lines)
        val (system, user) = text.prompts.single()
        assertEquals(Prompts.ROUTING_EDIT_SYSTEM, system)
        assertEquals(Prompts.routingEditPrompt(json(RoutingSecrets.redact(config).config), "add a reminder router"), user)
        val kept = json(proposed.change.base) + json(proposed.change.proposed) + user
        assertFalse(kept.contains("s3cret") || kept.contains("52.52") || kept.contains("13.405"), kept)
        assertEquals(RoutingSecrets.redact(config).config, proposed.change.base)
    }

    @Test
    fun aRefusedAnswerIsAskedForOnceMoreWithTheReasonAndThenGivenUpOn() = runBlocking<Unit> {
        val dangling = answer { it.copy(entry = g("nowhere")) }
        val text = FakeText(dangling, answer(::withReminder))
        assertEquals(withReminder(config), RoutingEditor.propose(text, config, "add a reminder router").config)
        assertTrue(text.prompts[1].second.contains("Your previous answer was refused: The entry router does not exist."), text.prompts[1].second)
        val stubborn = FakeText("no JSON here", dangling)
        assertEquals(RoutingEditRefused.Why.NO_ANSWER, refused { RoutingEditor.propose(stubborn, config, "add a reminder router") })
        assertEquals(RoutingEditor.ATTEMPTS, stubborn.prompts.size)
    }

    @Test
    fun anInstructionOrARoutingPastItsCapIsRefusedBeforeTheModelRuns() {
        val text = FakeText()
        assertEquals(RoutingEditRefused.Why.INSTRUCTION, refused { RoutingEditor.propose(text, config, "   ") })
        assertEquals(RoutingEditRefused.Why.INSTRUCTION, refused { RoutingEditor.propose(text, config, "x".repeat(RoutingEditor.MAX_INSTRUCTION_CHARS + 1)) })
        val rules = List(RoutingConfig.MAX_RULES) { SwitchRule("x".repeat(RoutingConfig.MAX_PATTERN_CHARS), g("notebook")) }
        val large = RoutingConfig(entry = g("notebook"), routers = List(4) { RouterConfig.Switch("s$it", "S$it", rules) } + RouterConfig.Note("notebook", "Notebook"))
        large.requireWellFormed()
        assertEquals(RoutingEditRefused.Why.TOO_LARGE, refused { RoutingEditor.propose(text, large, "add a reminder router") })
        assertTrue(text.prompts.isEmpty())
    }

    @Test
    fun aChangeAppliesToTheRoutingItWasWorkedOutFromWithTheSecretsThatRoutingHoldsNow() = runBlocking<Unit> {
        val change = RoutingEditor.propose(FakeText(answer(::withReminder)), config, "add a reminder router").change
        assertEquals(withReminder(config), RoutingEditor.resolve(change, config))
        val rotated = config.copy(routers = config.routers.map { if (it is RouterConfig.Network) it.copy(bearerToken = "n3w") else it })
        assertEquals(withReminder(rotated), RoutingEditor.resolve(change, rotated), "a token changed meanwhile is the one kept")
    }

    @Test
    fun aChangeIsRefusedOnceTheRoutingIsNoLongerTheOneItWasReadAgainst() = runBlocking<Unit> {
        val change = RoutingEditor.propose(FakeText(answer(::withReminder)), config, "add a reminder router").change
        val renamed = config.copy(routers = config.routers.map { if (it is RouterConfig.Note) it.copy(name = "Notes") else it })
        assertEquals(RoutingEditor.CHANGED_SINCE, assertFailsWith<IllegalArgumentException> { RoutingEditor.resolve(change, renamed) }.message)
        assertFailsWith<IllegalArgumentException> { RoutingEditor.resolve(change, withReminder(config)) }
    }

    @Test
    fun aChangeRoundTripsThroughTheQueuesJson() = runBlocking<Unit> {
        val change = RoutingEditor.propose(FakeText(answer(::withReminder)), config, "add a reminder router").change
        assertEquals(change, NotebookJson.decodeFromString(RoutingChange.serializer(), NotebookJson.encodeToString(RoutingChange.serializer(), change)))
    }
}

package com.anopticlabs.gravel.notebook.routers

import kotlin.test.Test
import kotlin.test.assertEquals

class RoutingDiffTest {

    private fun g(id: String) = RouterRef.gravel(id)
    private val note = RouterConfig.Note("notebook", "Notebook")
    private val reminder = RouterConfig.Reminder("reminder", "Reminders")
    private val hook = RouterConfig.Network("hook", "Hook", "https://example.test/a", bearerToken = "tok")
    private val split = RouterConfig.Switch("split", "Split", listOf(SwitchRule("^remind me", g("reminder"))), otherwise = g("notebook"))
    private val config = RoutingConfig(entry = g("split"), routers = listOf(split, note, reminder, hook))

    @Test
    fun nothingChangedSaysSo() {
        assertEquals(listOf("Nothing changed."), RoutingDiff.describe(config, config))
    }

    @Test
    fun addedRemovedRenamedAndRetypedRoutersAreNamed() {
        val after = config.copy(
            entry = g("notebook"),
            routers = listOf(
                RouterConfig.Time("split", "Split", emptyList(), otherwise = g("notebook")),
                note.copy(name = "Notes"),
                RouterConfig.Switch("sorter", "Sorter"),
                hook,
            ),
        )
        assertEquals(
            listOf(
                "The entry router is now 'Notes'.",
                "Added Switch 'Sorter'.",
                "Removed Reminder 'Reminders'.",
                "'Split' is now a Time.",
                "Renamed 'Notebook' to 'Notes'.",
            ),
            RoutingDiff.describe(config, after),
        )
    }

    @Test
    fun ruleTokenGrantAndConfirmationChangesAreDescribedWithoutTheSecret() {
        val after = config.copy(
            routers = listOf(
                split.copy(rules = split.rules + SwitchRule("todo", g("hook")), otherwise = null),
                note.copy(confirm = true),
                reminder,
                hook.copy(url = "https://hooks.example.net/x?k=secret", bearerToken = "other", shares = Shares(Grant.ALWAYS, Grant.NEVER)),
            ),
        )
        val lines = RoutingDiff.describe(config, after)
        assertEquals(
            listOf(
                "'Split' has 2 rules instead of 1.",
                "'Split' has no fallback now.",
                "'Notebook' now asks you first.",
                "'Hook' shares differently: location always, recording time never.",
                "'Hook' now posts to hooks.example.net.",
                "'Hook' has a different token.",
            ),
            lines,
        )
        assertEquals(listOf("'Hook' lost its token."), RoutingDiff.describe(config, config.copy(routers = config.routers.map { if (it is RouterConfig.Network) it.copy(bearerToken = null) else it })))
        assertEquals(listOf("The rules of 'Split' changed."), RoutingDiff.describe(config, config.copy(routers = config.routers.map { if (it is RouterConfig.Switch) it.copy(rules = listOf(SwitchRule("^remind", g("reminder")))) else it })))
    }

    @Test
    fun aPlaceThatMovedIsNamed() {
        val home = Spot("Home", 52.52, 13.405, 800)
        val places = RouterConfig.Place("places", "Places", listOf(PlaceRule(home, true, g("notebook"))), otherwise = g("notebook"))
        val before = RoutingConfig(entry = g("places"), routers = listOf(places, note))
        val moved = before.copy(routers = listOf(places.copy(rules = listOf(PlaceRule(home.copy(latitude = 0.0, longitude = 0.0), true, g("notebook"))), unknown = g("notebook")), note))
        assertEquals(listOf("The rules of 'Places' changed.", "The place 'Home' of 'Places' moved.", "'Places' sends recordings without a location to 'Notebook'."), RoutingDiff.describe(before, moved))
    }
}

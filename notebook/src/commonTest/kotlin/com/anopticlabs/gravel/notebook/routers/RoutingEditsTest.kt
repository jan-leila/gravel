package com.anopticlabs.gravel.notebook.routers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutingEditsTest {

    private val note = RouterConfig.Note("notebook", "Notebook")
    private val reminder = RouterConfig.Reminder("reminder", "Reminders")
    private val switch = RouterConfig.Switch("switch", "Split", listOf(SwitchRule("^remind me", "reminder")), otherwise = "notebook")
    private val config = RoutingConfig(entry = "switch", routers = listOf(switch, note, reminder))

    @Test
    fun aNewIdIsTheKindsTagWithTheLowestFreeNumber() {
        assertEquals("app", RoutingEdits.newId(config, "app"))
        assertEquals("switch-2", RoutingEdits.newId(config, "switch"))
        val more = RoutingEdits.withRouter(config, RouterConfig.Switch("switch-2", "Second"))
        assertEquals("switch-3", RoutingEdits.newId(more, "switch"))
        assertEquals("switch", RoutingEdits.idPrefix(switch))
    }

    @Test
    fun aRouterIsReplacedByIdOrAppended() {
        val renamed = RoutingEdits.withRouter(config, note.copy(name = "Notes"))
        assertEquals(listOf(switch, note.copy(name = "Notes"), reminder), renamed.routers)
        val app = RouterConfig.App("app", "Tasks", "com.example.tasks", "com.example.tasks.Router")
        assertEquals(config.routers + app, RoutingEdits.withRouter(config, app).routers)
    }

    @Test
    fun theEntryAndATargetCannotBeRemovedAndAnythingElseCan() {
        assertNull(RoutingEdits.withoutRouter(config, "switch"), "the entry")
        assertNull(RoutingEdits.withoutRouter(config, "reminder"), "a rule's target")
        assertNull(RoutingEdits.withoutRouter(config, "notebook"), "the fallback")
        assertEquals(listOf(switch), RoutingEdits.referencedBy(config, "notebook"))
        val app = RouterConfig.App("app", "Tasks", "com.example.tasks", "com.example.tasks.Router")
        assertEquals(config, RoutingEdits.withoutRouter(RoutingEdits.withRouter(config, app), "app"))
        assertEquals("notebook", RoutingEdits.withEntry(config, "notebook").entry)
    }

    @Test
    fun theSummaryNamesWhatARouterDoesWithoutATokenOrAPath() {
        assertEquals("Writes the text as a note", RoutingEdits.summary(note, config))
        assertEquals("Sets a reminder from the text", RoutingEdits.summary(reminder, config))
        assertEquals("1 rule, else to Notebook", RoutingEdits.summary(switch, config))
        assertEquals("No rules", RoutingEdits.summary(RouterConfig.Switch("s", "S"), config))
        assertEquals("2 rules", RoutingEdits.summary(switch.copy(rules = switch.rules + SwitchRule("x", "notebook"), otherwise = null), config))
        assertEquals("Sends to com.example.tasks", RoutingEdits.summary(RouterConfig.App("a", "A", "com.example.tasks", "x.Y"), config))
        assertEquals("Posts to hooks.example.test:8443", RoutingEdits.summary(RouterConfig.Network("n", "N", "https://hooks.example.test:8443/notes?k=secret", "tok"), config))
        assertEquals("Note", RoutingEdits.kindName(note))
    }
}

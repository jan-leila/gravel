package com.anopticlabs.gravel.notebook.routers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutingEditsTest {

    private val signer = "a".repeat(64)
    private val other = "b".repeat(64)
    private fun g(id: String) = RouterRef.gravel(id)

    private val note = RouterConfig.Note("notebook", "Notebook")
    private val reminder = RouterConfig.Reminder("reminder", "Reminders")
    private val switch = RouterConfig.Switch("switch", "Split", listOf(SwitchRule("^remind me", g("reminder"))), otherwise = g("notebook"))
    private val config = RoutingConfig(entry = g("switch"), routers = listOf(switch, note, reminder))
    private val app = RouterConfig.App("tasks", "Tasks", "com.example.tasks", "com.example.tasks.Router", signer)

    @Test
    fun aNewIdIsTheKindsTagWithTheLowestFreeNumberAmongGravelsOwn() {
        assertEquals("app", RoutingEdits.newId(config, "app"))
        assertEquals("switch-2", RoutingEdits.newId(config, "switch"))
        val more = RoutingEdits.withRouter(config, RouterConfig.Switch("switch-2", "Second"))
        assertEquals("switch-3", RoutingEdits.newId(more, "switch"))
        assertEquals("switch", RoutingEdits.idPrefix(switch))
        // An app's router with the tag as its id is in another namespace, so it takes nothing.
        assertEquals("app", RoutingEdits.newId(RoutingEdits.withRouter(config, app.copy(id = "app")), "app"))
    }

    @Test
    fun aRouterIsReplacedByRefOrAppended() {
        val renamed = RoutingEdits.withRouter(config, note.copy(name = "Notes"))
        assertEquals(listOf(switch, note.copy(name = "Notes"), reminder), renamed.routers)
        assertEquals(config.routers + app, RoutingEdits.withRouter(config, app).routers)
        val sameIdOtherNamespace = RouterConfig.Note("tasks", "My tasks")
        assertEquals(config.routers + app + sameIdOtherNamespace, RoutingEdits.withRouter(RoutingEdits.withRouter(config, app), sameIdOtherNamespace).routers)
    }

    /** Picking another app for an app router moves its ref; the rules that named it follow. */
    @Test
    fun anEditThatChangesTheRefRetargetsTheEntryAndTheRules() {
        val wired = RoutingConfig(
            entry = app.ref,
            routers = listOf(switch.copy(rules = listOf(SwitchRule("todo", app.ref)), otherwise = app.ref), note, app),
        )
        val replacement = RouterConfig.App("inbox", "Inbox", "com.example.inbox", "com.example.inbox.Router", other)
        val moved = RoutingEdits.withRouter(wired, replacement, replacing = app.ref)
        assertEquals(replacement.ref, moved.entry)
        assertEquals(listOf(switch.copy(rules = listOf(SwitchRule("todo", replacement.ref)), otherwise = replacement.ref), note, replacement), moved.routers)
        moved.requireWellFormed()
    }

    @Test
    fun theEntryAndATargetCannotBeRemovedAndAnythingElseCan() {
        assertNull(RoutingEdits.withoutRouter(config, g("switch")), "the entry")
        assertNull(RoutingEdits.withoutRouter(config, g("reminder")), "a rule's target")
        assertNull(RoutingEdits.withoutRouter(config, g("notebook")), "the fallback")
        assertEquals(listOf(switch), RoutingEdits.referencedBy(config, g("notebook")))
        assertEquals(config, RoutingEdits.withoutRouter(RoutingEdits.withRouter(config, app), app.ref))
        assertEquals(g("notebook"), RoutingEdits.withEntry(config, g("notebook")).entry)
    }

    @Test
    fun theSummaryNamesWhatARouterDoesWithoutATokenOrAPath() {
        assertEquals("Writes the text as a note", RoutingEdits.summary(note, config))
        assertEquals("Sets a reminder from the text", RoutingEdits.summary(reminder, config))
        assertEquals("1 rule, else to Notebook", RoutingEdits.summary(switch, config))
        assertEquals("No rules", RoutingEdits.summary(RouterConfig.Switch("s", "S"), config))
        assertEquals("2 rules", RoutingEdits.summary(switch.copy(rules = switch.rules + SwitchRule("x", g("notebook")), otherwise = null), config))
        assertEquals("Sends to com.example.tasks", RoutingEdits.summary(app, config), "nothing needed, nothing said")
        val hook = RouterConfig.Network("n", "N", "https://hooks.example.test:8443/notes?k=secret", "tok")
        assertEquals("Posts to hooks.example.test:8443, asks about your location, asks about the recording time", RoutingEdits.summary(hook, config))
        assertEquals("Posts to hooks.example.test:8443", RoutingEdits.summary(hook.copy(shares = Shares(Grant.NEVER, Grant.NEVER)), config))
        assertEquals("Posts to hooks.example.test:8443, shares the recording time", RoutingEdits.summary(hook.copy(shares = Shares(Grant.NEVER, Grant.ALWAYS)), config))
        val wanting = app.copy(needs = setOf(Need.LOCATION), shares = Shares(location = Grant.ALWAYS, recordedAt = Grant.ALWAYS))
        assertEquals("Sends to com.example.tasks, shares your location", RoutingEdits.summary(wanting, config), "a grant for a part the app never asked for is not mentioned")
        assertEquals("Note", RoutingEdits.kindName(note))
        assertEquals("Writes the text as a note, asks you first", RoutingEdits.summary(note.copy(confirm = true), config))
        assertEquals("Posts to hooks.example.test:8443, shares the recording time, asks you first", RoutingEdits.summary(hook.copy(shares = Shares(Grant.NEVER, Grant.ALWAYS), confirm = true), config))
    }

    @Test
    fun aGrantIsSetOnTheOneRouterAndIgnoredForAKindWithoutGrants() {
        val withApp = RoutingEdits.withRouter(config, app)
        val granted = RoutingEdits.withGrant(withApp, app.ref, Need.LOCATION, Grant.ALWAYS)
        assertEquals(Shares(location = Grant.ALWAYS, recordedAt = Grant.ASK), (granted.router(app.ref) as RouterConfig.App).shares)
        assertEquals(withApp.routers.filter { it.ref != app.ref }, granted.routers.filter { it.ref != app.ref })
        assertEquals(Shares(location = Grant.ALWAYS, recordedAt = Grant.NEVER), (RoutingEdits.withGrant(granted, app.ref, Need.RECORDED_AT, Grant.NEVER).router(app.ref) as RouterConfig.App).shares)
        assertEquals(withApp, RoutingEdits.withGrant(withApp, g("notebook"), Need.LOCATION, Grant.ALWAYS))
        assertEquals(withApp, RoutingEdits.withGrant(withApp, g("nowhere"), Need.LOCATION, Grant.ALWAYS))
    }
}

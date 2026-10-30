package com.anopticlabs.gravel.notebook.ui

import com.anopticlabs.gravel.notebook.routers.InstalledRouter
import com.anopticlabs.gravel.notebook.routers.InstalledRouters
import com.anopticlabs.gravel.notebook.routers.RouterConfig
import com.anopticlabs.gravel.notebook.routers.RouterTrustStore
import com.anopticlabs.gravel.notebook.routers.RoutingConfig
import com.anopticlabs.gravel.notebook.routers.RoutingConfigStore
import com.anopticlabs.gravel.notebook.routers.RoutingStoreState
import com.anopticlabs.gravel.notebook.routers.SwitchRule
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class RoutingViewModelTest {

    private val root = Files.createTempDirectory("notebook").toFile()
    private val store = RoutingConfigStore(root, FakeCryptographer(softwareCrypto()))
    private val trust = RouterTrustStore(File(root, "nobackup")) { 1L }
    private val a = "a".repeat(64)

    private class FakeInstalled : InstalledRouters {
        val routers = mutableListOf<InstalledRouter>()
        override fun discover() = routers.toList()
        override fun resolve(packageName: String, receiverClass: String) = routers.firstOrNull { it.packageName == packageName && it.receiverClass == receiverClass }
        override fun signedBy(packageName: String, sha256: String) = false
    }

    private val installed = FakeInstalled()

    // Unconfined, so an action runs to its first hop onto the I/O dispatcher before its call returns; [settle] waits for the rest.
    private val viewModel = RoutingViewModel(store, trust, installed, io = Dispatchers.Unconfined)
    private val split = RoutingConfig(
        entry = "split",
        routers = listOf(
            RouterConfig.Switch("split", "Split", listOf(SwitchRule("^remind me", "reminder")), otherwise = "notebook"),
            RouterConfig.Note("notebook", "Notebook"),
            RouterConfig.Reminder("reminder", "Reminders"),
        ),
    )

    /** Waits for the action under way: busy is set before the first suspension, so this never returns early. */
    private fun settle() = runBlocking { viewModel.ui.first { !it.busy } }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun loadsTheRoutingAndTheAppsWithTheirTrust() {
        installed.routers += InstalledRouter("com.example.tasks", "com.example.tasks.Router", "Tasks", "Makes a task", listOf(a))
        installed.routers += InstalledRouter("com.example.tasks", "com.example.tasks.Other", "Archive", null, listOf(a))
        assertNull(viewModel.ui.value.store)
        viewModel.load()
        settle()
        val ui = viewModel.ui.value
        assertEquals(RoutingStoreState.Loaded(RoutingConfig.DEFAULT), ui.store)
        val app = ui.apps.single()
        assertEquals("com.example.tasks", app.packageName)
        assertEquals(listOf("Archive", "Tasks"), app.routers.map { it.label })
        assertFalse(app.trusted)
        assertFalse(app.byUser)
        assertFalse(ui.busy)
    }

    @Test
    fun aSaveStoresAValidRoutingAndARefusedOneLeavesTheStoredOneWithTheReason() {
        viewModel.load()
        settle()
        viewModel.save(split)
        settle()
        assertEquals(split, store.read())
        assertEquals(RoutingStoreState.Loaded(split), viewModel.ui.value.store)
        viewModel.save(split.copy(entry = "gone"))
        settle()
        assertEquals("The entry router does not exist.", viewModel.ui.value.error)
        assertEquals(split, store.read())
        assertEquals(RoutingStoreState.Loaded(split), viewModel.ui.value.store)
        viewModel.clearError()
        assertNull(viewModel.ui.value.error)
    }

    @Test
    fun aResetGoesBackToTheDefault() {
        viewModel.save(split)
        settle()
        viewModel.reset()
        settle()
        assertEquals(RoutingConfig.DEFAULT, store.read())
        assertEquals(RoutingStoreState.Loaded(RoutingConfig.DEFAULT), viewModel.ui.value.store)
    }

    @Test
    fun trustingAnAppMarksItAndForgettingItUnmarksIt() {
        installed.routers += InstalledRouter("com.example.tasks", "com.example.tasks.Router", "Tasks", null, listOf(a, "b".repeat(64)))
        viewModel.load()
        settle()
        viewModel.trustApp(viewModel.ui.value.apps.single())
        settle()
        val trusted = viewModel.ui.value.apps.single()
        assertTrue(trusted.trusted)
        assertTrue(trusted.byUser)
        assertEquals(setOf(a, "b".repeat(64)), trust.read().map { it.sha256 }.toSet())
        viewModel.forgetApp(trusted)
        settle()
        assertFalse(viewModel.ui.value.apps.single().trusted)
        assertTrue(trust.read().isEmpty())
    }
}

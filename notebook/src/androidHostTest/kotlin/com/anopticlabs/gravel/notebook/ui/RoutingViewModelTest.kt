package com.anopticlabs.gravel.notebook.ui

import com.anopticlabs.gravel.notebook.backup.BackupDocumentInfo
import com.anopticlabs.gravel.notebook.backup.BackupDocumentOps
import com.anopticlabs.gravel.notebook.routers.InstalledRouter
import com.anopticlabs.gravel.notebook.routers.InstalledRouters
import com.anopticlabs.gravel.notebook.routers.RouterConfig
import com.anopticlabs.gravel.notebook.routers.RouterRef
import com.anopticlabs.gravel.notebook.routers.RouterTrustStore
import com.anopticlabs.gravel.notebook.routers.RoutingConfig
import com.anopticlabs.gravel.notebook.routers.RoutingConfigStore
import com.anopticlabs.gravel.notebook.routers.RoutingExport
import com.anopticlabs.gravel.notebook.routers.RoutingStoreState
import com.anopticlabs.gravel.notebook.routers.SwitchRule
import com.anopticlabs.gravel.notebook.routers.TrustEntries
import com.anopticlabs.gravel.notebook.routers.TrustedCertificate
import com.anopticlabs.gravel.notebook.routers.TrustedSigner
import com.anopticlabs.gravel.notebook.storage.FakeCryptographer
import com.anopticlabs.gravel.notebook.storage.softwareCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private val builtIn = "c".repeat(64)
    private val trust = RouterTrustStore(File(root, "nobackup"), { 1L }, defaults = setOf(builtIn))

    private class FakeInstalled : InstalledRouters {
        val routers = mutableListOf<InstalledRouter>()
        override fun discover() = routers.toList()
        override fun resolve(packageName: String, receiverClass: String) = routers.firstOrNull { it.packageName == packageName && it.receiverClass == receiverClass }
        override fun signedBy(packageName: String, sha256: String) = false
    }

    /** Documents by name; a name that was never written cannot be read. */
    private class FakeDocuments : BackupDocumentOps<String> {
        val files = mutableMapOf<String, ByteArray>()
        override fun describe(handle: String) = files[handle]?.let { BackupDocumentInfo(handle, it.size.toLong()) }
        override fun openWrite(handle: String): OutputStream = object : ByteArrayOutputStream() {
            override fun close() {
                files[handle] = toByteArray()
            }
        }
        override fun openRead(handle: String): InputStream = ByteArrayInputStream(files[handle] ?: throw IOException("no such document"))
        override fun delete(handle: String) = files.remove(handle) != null
    }

    private val installed = FakeInstalled()
    private val documents = FakeDocuments()

    // Unconfined, so an action runs to its first hop onto the I/O dispatcher before its call returns; [settle] waits for the rest.
    private val viewModel = RoutingViewModel(store, trust, installed, documents, io = Dispatchers.Unconfined)
    private val split = RoutingConfig(
        entry = RouterRef.gravel("split"),
        routers = listOf(
            RouterConfig.Switch("split", "Split", listOf(SwitchRule("^remind me", RouterRef.gravel("reminder"))), otherwise = RouterRef.gravel("notebook")),
            RouterConfig.Note("notebook", "Notebook"),
            RouterConfig.Reminder("reminder", "Reminders"),
        ),
    )

    /** Waits for the action under way: busy is set before the first suspension, so this never returns early. */
    private fun settle() = runBlocking { viewModel.ui.first { !it.busy } }

    private fun tasksApp() {
        installed.routers += InstalledRouter("com.example.tasks", "com.example.tasks.Router", "tasks", a, "Tasks", "Makes a task", "Tasks app")
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun loadsTheRoutingTheCertificatesAndTheAppsWithTheirTrust() {
        tasksApp()
        installed.routers += InstalledRouter("com.example.tasks", "com.example.tasks.Other", "archive", a, "Archive", null, "Tasks app")
        assertNull(viewModel.ui.value.store)
        viewModel.load()
        settle()
        val ui = viewModel.ui.value
        assertEquals(RoutingStoreState.Loaded(RoutingConfig.DEFAULT), ui.store)
        assertEquals(listOf(TrustedCertificate(builtIn, null, builtIn = true, null)), ui.certificates)
        val app = ui.apps.single()
        assertEquals("com.example.tasks", app.packageName)
        assertEquals("Tasks app", app.appName)
        assertEquals(a, app.namespace)
        assertEquals(listOf("Archive", "Tasks"), app.routers.map { it.label })
        assertFalse(app.trusted)
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
        viewModel.save(split.copy(entry = RouterRef.gravel("gone")))
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
    fun trustingAnAppAddsItsCertificateUnderItsNameAndForgettingItRemovesIt() {
        tasksApp()
        viewModel.load()
        settle()
        viewModel.trustApp(viewModel.ui.value.apps.single())
        settle()
        assertTrue(viewModel.ui.value.apps.single().trusted)
        assertEquals(listOf(TrustedCertificate(builtIn, null, true, null), TrustedCertificate(a, "Tasks app", false, 1L)), viewModel.ui.value.certificates)
        viewModel.forgetApp(viewModel.ui.value.apps.single())
        settle()
        assertFalse(viewModel.ui.value.apps.single().trusted)
        assertEquals(listOf(TrustedCertificate(builtIn, null, true, null)), viewModel.ui.value.certificates)
    }

    @Test
    fun aCertificateIsAddedByHandAndABuiltInOneCanBeRemoved() {
        viewModel.load()
        settle()
        viewModel.addCertificate(b.uppercase(), " Dev key ")
        settle()
        assertEquals(listOf(TrustedCertificate(builtIn, null, true, null), TrustedCertificate(b, "Dev key", false, 1L)), viewModel.ui.value.certificates)
        viewModel.removeCertificate(builtIn)
        settle()
        assertEquals(listOf(TrustedCertificate(b, "Dev key", false, 1L)), viewModel.ui.value.certificates)
        viewModel.addCertificate("nope", null)
        settle()
        assertEquals(RoutingViewModel.BAD_DIGEST, viewModel.ui.value.error)
    }

    @Test
    fun anExportWritesTheRoutingAndTheTrustAndAnImportReplacesBoth() {
        viewModel.save(split)
        settle()
        viewModel.addCertificate(b, "Dev key")
        settle()
        viewModel.export("out.json")
        settle()
        assertEquals(RoutingViewModel.EXPORTED, viewModel.ui.value.notice)
        val exported = RoutingExport.decode(documents.files.getValue("out.json").decodeToString())
        assertEquals(split, exported.routing)
        assertEquals(TrustEntries(added = listOf(TrustedSigner(b, "Dev key", 1L))), exported.trust)
        viewModel.clearNotice()
        viewModel.reset()
        settle()
        viewModel.removeCertificate(b)
        settle()
        documents.files["in.json"] = RoutingExport.encode(split, TrustEntries(added = listOf(TrustedSigner(a, "Imported", 7L)), removedDefaults = listOf(builtIn))).encodeToByteArray()
        viewModel.import("in.json")
        settle()
        assertEquals(RoutingViewModel.IMPORTED, viewModel.ui.value.notice)
        assertEquals(split, store.read())
        assertEquals(RoutingStoreState.Loaded(split), viewModel.ui.value.store)
        assertEquals(listOf(TrustedCertificate(a, "Imported", false, 7L)), viewModel.ui.value.certificates)
    }

    @Test
    fun aFileThatIsNotAnExportChangesNothingAndSaysWhy() {
        viewModel.save(split)
        settle()
        documents.files["bad.json"] = "{}".encodeToByteArray()
        viewModel.import("bad.json")
        settle()
        assertEquals("The file is not a routing export.", viewModel.ui.value.error)
        assertEquals(split, store.read())
        viewModel.clearError()
        viewModel.import("missing.json")
        settle()
        assertEquals(RoutingViewModel.IMPORT_FAILED, viewModel.ui.value.error)
        assertEquals(split, store.read())
    }
}

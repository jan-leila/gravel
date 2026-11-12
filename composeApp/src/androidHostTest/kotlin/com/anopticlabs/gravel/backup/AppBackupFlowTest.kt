package com.anopticlabs.gravel.backup

import com.anopticlabs.gravel.notebook.backup.BackupProblem
import com.anopticlabs.gravel.notebook.backup.InvalidBackupException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppBackupFlowTest {
    private val manifest = AppBackupManifest(AppBackupFormat.VERSION, 1L, "", emptyMap(), notebook = true)
    private val imported = ImportReport(manifest, 3, 0, 0, 0, true, emptyList())

    private class FakeOps(val manifest: AppBackupManifest, val imported: ImportReport) : AppBackupOperations {
        var exportedWith: String? = null
        var checkedWith: String? = null
        var importFailure: Exception? = null
        override suspend fun export(out: OutputStream, password: CharArray): ExportReport {
            exportedWith = String(password)
            out.write(1)
            return ExportReport(manifest, null)
        }
        override suspend fun check(input: InputStream, password: CharArray): AppBackupManifest {
            checkedWith = String(password)
            return manifest
        }
        override suspend fun import(input: InputStream, password: CharArray): ImportReport {
            importFailure?.let { throw it }
            return imported
        }
    }

    private class Docs : AppBackupDocuments<String> {
        val written = mutableMapOf<String, ByteArrayOutputStream>()
        override fun openWrite(handle: String): OutputStream = ByteArrayOutputStream().also { written[handle] = it }
        override fun openRead(handle: String): InputStream = ByteArrayInputStream(written[handle]?.toByteArray() ?: ByteArray(0))
    }

    private fun TestScope.flow(ops: FakeOps, docs: Docs = Docs(), restarts: MutableList<Unit> = mutableListOf()) =
        AppBackupFlow(ops, docs, this, fileName = { "gravel-backup.gbak" }, restart = { restarts += Unit })

    @Test
    fun anExportWritesTheFileAndReadsItBackWithTheSamePassword() = runTest(StandardTestDispatcher()) {
        val ops = FakeOps(manifest, imported)
        val docs = Docs()
        val flow = flow(ops, docs)
        flow.startExport()
        val password = "a long enough backup password".toCharArray()
        flow.exportPasswordChosen(password)
        assertEquals(AppBackupState.SaveTo("gravel-backup.gbak", opened = false), flow.state.value)
        assertTrue(flow.claimPicker())
        assertFalse(flow.claimPicker(), "the picker is claimed once")
        flow.documentChosen("doc")
        advanceUntilIdle()
        assertIs<AppBackupState.Exported>(flow.state.value)
        assertEquals("a long enough backup password", ops.exportedWith)
        assertEquals("a long enough backup password", ops.checkedWith)
        assertContentEquals(byteArrayOf(1), docs.written.getValue("doc").toByteArray())
        assertTrue(password.all { it == '\u0000' }, "the password is wiped once the run ends")
    }

    @Test
    fun cancellingTheSavePickerEndsTheRunAndWipesThePassword() = runTest(StandardTestDispatcher()) {
        val flow = flow(FakeOps(manifest, imported))
        flow.startExport()
        val password = "a long enough backup password".toCharArray()
        flow.exportPasswordChosen(password)
        flow.documentChosen(null)
        assertEquals(AppBackupState.Idle, flow.state.value)
        assertTrue(password.all { it == '\u0000' })
    }

    @Test
    fun anImportAsksFirstAndRestartsOnlyOnceTheReportIsClosed() = runTest(StandardTestDispatcher()) {
        val restarts = mutableListOf<Unit>()
        val flow = flow(FakeOps(manifest, imported), restarts = restarts)
        flow.startImport()
        assertEquals(AppBackupState.ConfirmImport, flow.state.value)
        flow.importConfirmed()
        assertTrue(flow.claimPicker())
        flow.fileChosen("doc")
        flow.importPasswordEntered("pw".toCharArray())
        advanceUntilIdle()
        assertEquals(AppBackupState.Imported(imported), flow.state.value)
        assertTrue(restarts.isEmpty())
        flow.dismiss()
        assertEquals(1, restarts.size)
    }

    @Test
    fun aWrongPasswordAsksAgainForTheSameFile() = runTest(StandardTestDispatcher()) {
        val ops = FakeOps(manifest, imported)
        val flow = flow(ops)
        flow.startImport()
        flow.importConfirmed()
        flow.claimPicker()
        flow.fileChosen("doc")
        ops.importFailure = InvalidBackupException(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, "wrong")
        flow.importPasswordEntered("wrong".toCharArray())
        advanceUntilIdle()
        assertEquals(AppBackupState.ImportPassword(wrongPassword = true), flow.state.value)
        ops.importFailure = null
        flow.importPasswordEntered("right".toCharArray())
        advanceUntilIdle()
        assertIs<AppBackupState.Imported>(flow.state.value)
    }

    @Test
    fun anotherKindOfFileFailsWithAMessage() = runTest(StandardTestDispatcher()) {
        val ops = FakeOps(manifest, imported)
        ops.importFailure = InvalidBackupException(BackupProblem.NOT_A_BACKUP, "no")
        val flow = flow(ops)
        flow.startImport()
        flow.importConfirmed()
        flow.claimPicker()
        flow.fileChosen("doc")
        flow.importPasswordEntered("pw".toCharArray())
        advanceUntilIdle()
        assertIs<AppBackupState.Failed>(flow.state.value)
    }

    @Test
    fun aSecondRunWaitsForTheFirst() = runTest(StandardTestDispatcher()) {
        val flow = flow(FakeOps(manifest, imported))
        flow.startExport()
        flow.startImport()
        assertEquals(AppBackupState.ExportPassword, flow.state.value)
    }
}

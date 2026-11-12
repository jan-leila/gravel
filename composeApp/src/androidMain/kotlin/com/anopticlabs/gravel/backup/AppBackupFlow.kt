package com.anopticlabs.gravel.backup

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.backup.BackupProblem
import com.anopticlabs.gravel.notebook.backup.InvalidBackupException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream

/** What [AppBackupFlow] runs; [AppBackup] is the implementation. */
interface AppBackupOperations {
    suspend fun export(out: OutputStream, password: CharArray): ExportReport
    suspend fun check(input: InputStream, password: CharArray): AppBackupManifest
    suspend fun import(input: InputStream, password: CharArray): ImportReport
}

/** The documents a run reads and writes, by the handle the system picker returned. */
interface AppBackupDocuments<H> {
    fun openWrite(handle: H): OutputStream
    fun openRead(handle: H): InputStream
}

sealed interface AppBackupState {
    data object Idle : AppBackupState
    data object ExportPassword : AppBackupState

    /** The save picker is [opened] once the screen has launched it, so a recomposition does not launch it twice. */
    data class SaveTo(val suggestedName: String, val opened: Boolean) : AppBackupState
    data object ConfirmImport : AppBackupState
    data class PickFile(val opened: Boolean) : AppBackupState
    data class ImportPassword(val wrongPassword: Boolean) : AppBackupState
    data class Working(val importing: Boolean) : AppBackupState
    data class Exported(val report: ExportReport) : AppBackupState

    /** Restored; the app restarts when the user closes this, so the restored preferences take effect. */
    data class Imported(val report: ImportReport) : AppBackupState
    data class Failed(val message: String) : AppBackupState
}

/**
 * One export or import at a time. An export takes a new password, writes to the document the
 * user picks, then reads it back through with the same password before calling it done. An
 * import asks first, since it changes the app and then restarts it; a wrong password asks again
 * without picking the file again. The password is zeroed when the run ends.
 */
class AppBackupFlow<H>(
    private val backup: AppBackupOperations,
    private val documents: AppBackupDocuments<H>,
    private val scope: CoroutineScope,
    private val fileName: () -> String,
    private val restart: () -> Unit,
) {
    private val logger = Logger.withTag("AppBackupFlow")
    private val _state = MutableStateFlow<AppBackupState>(AppBackupState.Idle)
    val state: StateFlow<AppBackupState> = _state.asStateFlow()
    private var password: CharArray? = null
    private var picked: H? = null

    fun startExport() = start(AppBackupState.ExportPassword)

    fun startImport() = start(AppBackupState.ConfirmImport)

    private fun start(first: AppBackupState) {
        if (_state.value != AppBackupState.Idle) return
        _state.value = first
    }

    fun exportPasswordChosen(chars: CharArray) {
        if (_state.value != AppBackupState.ExportPassword) return chars.fill('\u0000')
        password = chars
        _state.value = AppBackupState.SaveTo(fileName(), opened = false)
    }

    /** True for the one caller that should launch the picker the state asks for. */
    fun claimPicker(): Boolean {
        when (val s = _state.value) {
            is AppBackupState.SaveTo -> if (!s.opened) _state.value = s.copy(opened = true) else return false
            is AppBackupState.PickFile -> if (!s.opened) _state.value = s.copy(opened = true) else return false
            else -> return false
        }
        return true
    }

    /** The save picker's answer; null is a cancel. */
    fun documentChosen(handle: H?) {
        if (_state.value !is AppBackupState.SaveTo) return
        val chars = password ?: return end()
        if (handle == null) return end()
        _state.value = AppBackupState.Working(importing = false)
        scope.launch {
            _state.value = try {
                val report = documents.openWrite(handle).use { backup.export(it, chars) }
                documents.openRead(handle).use { backup.check(it, chars) }
                AppBackupState.Exported(report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(e) { "export failed" }
                AppBackupState.Failed("The backup could not be written or did not read back: ${e.message ?: e::class.simpleName}")
            }
            wipe()
        }
    }

    fun importConfirmed() {
        if (_state.value != AppBackupState.ConfirmImport) return
        _state.value = AppBackupState.PickFile(opened = false)
    }

    /** The open picker's answer; null is a cancel. */
    fun fileChosen(handle: H?) {
        if (_state.value !is AppBackupState.PickFile) return
        if (handle == null) return end()
        picked = handle
        _state.value = AppBackupState.ImportPassword(wrongPassword = false)
    }

    fun importPasswordEntered(chars: CharArray) {
        val handle = picked
        if (_state.value !is AppBackupState.ImportPassword || handle == null) return chars.fill('\u0000')
        _state.value = AppBackupState.Working(importing = true)
        scope.launch {
            _state.value = try {
                AppBackupState.Imported(documents.openRead(handle).use { backup.import(it, chars) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: InvalidBackupException) {
                if (e.problem == BackupProblem.WRONG_PASSWORD_OR_DAMAGED) {
                    AppBackupState.ImportPassword(wrongPassword = true)
                } else {
                    AppBackupState.Failed(messageOf(e.problem))
                }
            } catch (e: Exception) {
                logger.w(e) { "import failed" }
                AppBackupState.Failed("The backup could not be restored: ${e.message ?: e::class.simpleName}")
            } finally {
                chars.fill('\u0000')
            }
            if (_state.value !is AppBackupState.ImportPassword) picked = null
        }
    }

    /** Closes whatever the run shows; closing a finished import restarts the app. */
    fun dismiss() {
        val s = _state.value
        if (s is AppBackupState.Working) return
        end()
        if (s is AppBackupState.Imported) restart()
    }

    private fun end() {
        wipe()
        picked = null
        _state.value = AppBackupState.Idle
    }

    private fun wipe() {
        password?.fill('\u0000')
        password = null
    }

    private fun messageOf(problem: BackupProblem) = when (problem) {
        BackupProblem.NOT_A_BACKUP -> "That file is not a Gravel backup."
        BackupProblem.NEWER_FORMAT -> "That backup was made by a newer version of Gravel. Update Gravel and try again."
        else -> "That backup file is damaged."
    }
}

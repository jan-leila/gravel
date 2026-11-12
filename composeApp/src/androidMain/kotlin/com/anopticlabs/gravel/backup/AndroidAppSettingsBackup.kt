package com.anopticlabs.gravel.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.anopticlabs.gravel.notebook.backup.ArchiveFormat
import com.anopticlabs.gravel.notebook.backup.PasswordProblem
import com.anopticlabs.gravel.notebook.backup.backupPasswordProblem
import com.anopticlabs.gravel.ui.AppSettingsBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate

class AndroidAppSettingsBackup(private val context: Context, backup: AppBackup) : AppSettingsBackup {
    private val flow = AppBackupFlow(
        backup = backup,
        documents = ContentDocuments(context),
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        fileName = { "${AppBackupFormat.FILE_PREFIX}${LocalDate.now()}${AppBackupFormat.FILE_SUFFIX}" },
        restart = { restartApp(context) },
    )

    override fun startExport() = flow.startExport()

    override fun startImport() = flow.startImport()

    @Composable
    override fun Dialogs() = AppBackupDialogs(flow)
}

private class ContentDocuments(private val context: Context) : AppBackupDocuments<Uri> {
    // "wt": a picked file that already exists is truncated, not overwritten in place.
    override fun openWrite(handle: Uri): OutputStream =
        context.contentResolver.openOutputStream(handle, "wt") ?: error("the file could not be opened for writing")

    override fun openRead(handle: Uri): InputStream =
        context.contentResolver.openInputStream(handle) ?: error("the file could not be opened")
}

/** Relaunches into a new process, which re-reads every restored preference. */
private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    Runtime.getRuntime().exit(0)
}

/** Secure windows: the dialogs hold passwords and describe the backup's contents. */
private val dialogProperties = DialogProperties(dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn)

@Composable
private fun AppBackupDialogs(flow: AppBackupFlow<Uri>) {
    val state by flow.state.collectAsState()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(AppBackupFormat.MIME_TYPE)) { flow.documentChosen(it) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { flow.fileChosen(it) }
    LaunchedEffect(state) {
        val s = state
        try {
            if (s is AppBackupState.SaveTo && flow.claimPicker()) save.launch(s.suggestedName)
            if (s is AppBackupState.PickFile && flow.claimPicker()) open.launch(arrayOf("*/*"))
        } catch (e: Exception) {
            // No app on the phone handles the picker; the run cannot go on.
            flow.dismiss()
        }
    }
    when (val s = state) {
        AppBackupState.Idle, is AppBackupState.SaveTo, is AppBackupState.PickFile -> Unit
        AppBackupState.ExportPassword -> NewPasswordDialog(onConfirm = flow::exportPasswordChosen, onCancel = flow::dismiss)
        AppBackupState.ConfirmImport -> AlertDialog(
            onDismissRequest = flow::dismiss,
            properties = dialogProperties,
            title = { Text("Restore a backup?") },
            text = {
                Text(
                    "The backup's settings replace this phone's. Its apps, notification rules, contact settings " +
                        "and health history are added, and its Notebook is merged in. Nothing here is deleted. Gravel " +
                        "restarts when it is done. Sign-ins and the speech server's token are not in a backup; " +
                        "enter them again afterwards.",
                )
            },
            confirmButton = { TextButton(onClick = flow::importConfirmed) { Text("Choose file") } },
            dismissButton = { TextButton(onClick = flow::dismiss) { Text("Cancel") } },
        )
        is AppBackupState.ImportPassword -> EnterPasswordDialog(s.wrongPassword, onOpen = flow::importPasswordEntered, onCancel = flow::dismiss)
        is AppBackupState.Working -> AlertDialog(
            onDismissRequest = {},
            properties = dialogProperties,
            title = { Text(if (s.importing) "Restoring" else "Backing up") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This can take a minute. Keep Gravel open.")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
        is AppBackupState.Exported -> AlertDialog(
            onDismissRequest = flow::dismiss,
            properties = dialogProperties,
            title = { Text("Backup saved") },
            text = { Text(exportedText(s.report)) },
            confirmButton = { TextButton(onClick = flow::dismiss) { Text("Done") } },
        )
        is AppBackupState.Imported -> AlertDialog(
            onDismissRequest = {},
            properties = dialogProperties,
            title = { Text("Backup restored") },
            text = { Text(importedText(s.report), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = flow::dismiss) { Text("Restart Gravel") } },
        )
        is AppBackupState.Failed -> AlertDialog(
            onDismissRequest = flow::dismiss,
            properties = dialogProperties,
            title = { Text("Backup not finished") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = flow::dismiss) { Text("OK") } },
        )
    }
}

private fun exportedText(report: ExportReport): String = buildString {
    append("The file opened again with your password and holds everything it should. Keep the password: without it the backup cannot be opened.")
    report.notebookSkipped?.let { append("\n\nThe Notebook is not in it: $it") }
    if (report.manifest.missingPbws.isNotEmpty()) {
        append("\n\n${report.manifest.missingPbws.size} sideloaded app(s) had no file on this phone and are not in it.")
    }
}

private fun importedText(report: ImportReport): String = buildString {
    append("${report.rowsWritten} settings and records restored")
    if (report.appsRestored > 0) append(", ${report.appsRestored} sideloaded app(s) reinstalled")
    append(".")
    if (report.notebookRestored) append(" The Notebook was merged in.")
    if (report.rowsRefused > 0) append("\n\n${report.rowsRefused} record(s) did not fit this version of Gravel and were skipped.")
    if (report.appsNotRestored > 0) append("\n\n${report.appsNotRestored} sideloaded app(s) could not be reinstalled; sideload them again.")
    report.problems.forEach { append("\n\n$it") }
    append("\n\nGravel restarts now so every setting takes effect.")
}

@Composable
private fun NewPasswordDialog(onConfirm: (CharArray) -> Unit, onCancel: () -> Unit) {
    val first = remember { TextFieldState() }
    val again = remember { TextFieldState() }
    DisposableEffect(Unit) {
        onDispose {
            first.clearText()
            again.clearText()
        }
    }
    var shown by remember { mutableStateOf(false) }
    val problem = backupPasswordProblem(first.text, again.text)
    AlertDialog(
        onDismissRequest = onCancel,
        properties = dialogProperties,
        title = { Text("Back up Gravel") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "The backup holds your settings, watchapp settings (which can include their passwords " +
                        "and keys), the app list with sideloaded apps, health history and the Notebook. " +
                        "Choose a password to encrypt it.",
                )
                val firstError = first.text.isNotEmpty() && (problem == PasswordProblem.TOO_SHORT || problem == PasswordProblem.NOT_TEXT)
                PasswordField(first, "Password", shown, { shown = !shown }, firstError) {
                    Text("At least ${ArchiveFormat.MIN_PASSWORD_LENGTH} characters")
                }
                val againError = again.text.isNotEmpty() && problem == PasswordProblem.DIFFERENT
                PasswordField(again, "Password again", shown, { shown = !shown }, againError) {
                    if (againError) Text("The passwords are different")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { again.clearText(); onConfirm(first.take()) }, enabled = problem == null) { Text("Back up") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun EnterPasswordDialog(wrong: Boolean, onOpen: (CharArray) -> Unit, onCancel: () -> Unit) {
    val field = remember(wrong) { TextFieldState() }
    DisposableEffect(field) { onDispose { field.clearText() } }
    var shown by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        properties = dialogProperties,
        title = { Text("Restore a backup") },
        text = {
            PasswordField(field, "Backup password", shown, { shown = !shown }, wrong) {
                if (wrong) Text("Wrong password, or the file is damaged")
            }
        },
        confirmButton = { TextButton(onClick = { onOpen(field.take()) }, enabled = field.text.isNotEmpty()) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordField(
    state: TextFieldState,
    label: String,
    shown: Boolean,
    toggle: () -> Unit,
    isError: Boolean,
    supporting: @Composable () -> Unit,
) {
    OutlinedSecureTextField(
        state = state,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        textObfuscationMode = if (shown) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
        isError = isError,
        supportingText = supporting,
        trailingIcon = {
            IconButton(onClick = toggle) {
                Icon(
                    if (shown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (shown) "Hide password" else "Show password",
                )
            }
        },
    )
}

/** The field's text as a new array, which the receiver owns, with the field cleared. */
private fun TextFieldState.take(): CharArray {
    val chars = CharArray(text.length) { text[it] }
    clearText()
    return chars
}

package com.anopticlabs.gravel.notebook.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.routers.Grant
import com.anopticlabs.gravel.notebook.routers.InstalledRouter
import com.anopticlabs.gravel.notebook.routers.Need
import com.anopticlabs.gravel.notebook.routers.RouterConfig
import com.anopticlabs.gravel.notebook.routers.RouterRef
import com.anopticlabs.gravel.notebook.routers.RouterTrust
import com.anopticlabs.gravel.notebook.routers.RoutingConfig
import com.anopticlabs.gravel.notebook.routers.RoutingEdits
import com.anopticlabs.gravel.notebook.routers.RoutingExport
import com.anopticlabs.gravel.notebook.routers.RoutingStoreState
import com.anopticlabs.gravel.notebook.routers.Shares
import com.anopticlabs.gravel.notebook.routers.SwitchRule
import com.anopticlabs.gravel.notebook.routers.TrustedCertificate
import com.anopticlabs.gravel.notebook.routers.shares
import coredevices.pebble.ui.TopBarParams
import org.koin.compose.viewmodel.koinViewModel

/** Settings > Notebook > Text Routing: the routing graph, its routers' editors, the trusted certificates, the apps that declare routers, and export and import. */
@Composable
fun RoutingSettingsScreen(topBarParams: TopBarParams) {
    val title = stringResource(R.string.notebook_routing_title)
    LaunchedEffect(title) {
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
        topBarParams.title(title)
    }
    val viewModel = koinViewModel<RoutingViewModel<Uri>>()
    LaunchedEffect(viewModel) { viewModel.load() }
    val ui by viewModel.ui.collectAsState()
    NotebookSecureContent { RoutingContent(ui, viewModel) }
}

private sealed interface RoutingDialog {
    data object PickEntry : RoutingDialog

    data object AddRouter : RoutingDialog

    data class Edit(val router: RouterConfig) : RoutingDialog

    data class Delete(val router: RouterConfig) : RoutingDialog

    data object ConfirmReset : RoutingDialog

    data class Trust(val app: RouterApp) : RoutingDialog

    data class Forget(val app: RouterApp) : RoutingDialog

    data object AddCertificate : RoutingDialog

    data class RemoveCertificate(val certificate: TrustedCertificate) : RoutingDialog

    data object ConfirmExport : RoutingDialog

    data object ConfirmImport : RoutingDialog
}

@Composable
private fun RoutingContent(ui: RoutingUi, viewModel: RoutingViewModel<Uri>) {
    var dialog by remember { mutableStateOf<RoutingDialog?>(null) }
    val config = (ui.store as? RoutingStoreState.Loaded)?.config
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(RoutingExport.MIME_TYPE)) { it?.let(viewModel::export) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(viewModel::import) }
    // A phone without a document picker throws on launch; the error says so instead of ending the screen.
    fun launch(block: () -> Unit) = try {
        block()
    } catch (e: Exception) {
        viewModel.pickerUnavailable()
    }
    ui.notice?.let { notice ->
        AlertDialog(
            onDismissRequest = viewModel::clearNotice,
            title = { Text(stringResource(R.string.notebook_routing_title)) },
            text = { Text(notice) },
            confirmButton = { TextButton(onClick = viewModel::clearNotice) { Text(stringResource(R.string.notebook_routing_ok)) } },
        )
    }
    ui.error?.let { error ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            title = { Text(stringResource(R.string.notebook_routing_problem)) },
            text = { Text(error) },
            confirmButton = { TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.notebook_routing_ok)) } },
        )
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.notebook_routing_intro), style = MaterialTheme.typography.bodyMedium)
        when (val state = ui.store) {
            null -> Text(stringResource(R.string.notebook_routing_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            RoutingStoreState.Unavailable -> {
                Text(stringResource(R.string.notebook_routing_unavailable), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::load) { Text(stringResource(R.string.notebook_routing_try_again)) }
            }
            RoutingStoreState.Unreadable -> {
                Text(stringResource(R.string.notebook_routing_unreadable), color = MaterialTheme.colorScheme.error)
                Button(onClick = { dialog = RoutingDialog.ConfirmReset }) { Text(stringResource(R.string.notebook_routing_reset)) }
            }
            is RoutingStoreState.Loaded -> LoadedRouting(state.config, ui.busy) { dialog = it }
        }
        HorizontalDivider()
        CertificatesSection(ui) { dialog = it }
        HorizontalDivider()
        AppsSection(ui, viewModel) { dialog = it }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { dialog = RoutingDialog.ConfirmExport }, enabled = !ui.busy && config != null) { Text(stringResource(R.string.notebook_routing_export)) }
            OutlinedButton(onClick = { dialog = RoutingDialog.ConfirmImport }, enabled = !ui.busy) { Text(stringResource(R.string.notebook_routing_import)) }
        }
    }
    val close = { dialog = null }
    when (val open = dialog) {
        null -> Unit
        RoutingDialog.PickEntry -> config?.let { c ->
            RouterPicker(stringResource(R.string.notebook_routing_entry), c.routers, c.entry, onPick = { viewModel.save(RoutingEdits.withEntry(c, it)) }, onDismiss = close)
        }
        RoutingDialog.AddRouter -> config?.let { c -> KindPicker(c, onPick = { dialog = RoutingDialog.Edit(it) }, onDismiss = close) }
        is RoutingDialog.Edit -> config?.let { c ->
            RouterEditor(open.router, c, ui.apps, onSave = { router ->
                viewModel.save(RoutingEdits.withRouter(c, router, replacing = open.router.ref))
                close()
            }, onTrust = viewModel::trustApp, onDismiss = close)
        }
        is RoutingDialog.Delete -> config?.let { c -> DeleteQuestion(open.router, c, onConfirm = { viewModel.save(it) }, onDismiss = close) }
        RoutingDialog.ConfirmReset -> Question(
            stringResource(R.string.notebook_routing_reset_title), stringResource(R.string.notebook_routing_reset_body), stringResource(R.string.notebook_routing_reset),
            onConfirm = { viewModel.reset() }, onDismiss = close,
        )
        is RoutingDialog.Trust -> TrustQuestion(open.app, onConfirm = { viewModel.trustApp(open.app) }, onDismiss = close)
        is RoutingDialog.Forget -> Question(
            stringResource(R.string.notebook_routing_forget_title, open.app.appName), stringResource(R.string.notebook_routing_forget_body),
            stringResource(R.string.notebook_routing_stop_trusting), onConfirm = { viewModel.forgetApp(open.app) }, onDismiss = close,
        )
        RoutingDialog.AddCertificate -> AddCertificateDialog(onAdd = { digest, label -> viewModel.addCertificate(digest, label) }, onDismiss = close)
        is RoutingDialog.RemoveCertificate -> Question(
            stringResource(R.string.notebook_routing_remove_certificate_title, open.certificate.label ?: stringResource(R.string.notebook_routing_unnamed)),
            stringResource(R.string.notebook_routing_remove_certificate_body), stringResource(R.string.notebook_routing_remove),
            onConfirm = { viewModel.removeCertificate(open.certificate.sha256) }, onDismiss = close,
        )
        RoutingDialog.ConfirmExport -> Question(
            stringResource(R.string.notebook_routing_export_title), stringResource(R.string.notebook_routing_export_body), stringResource(R.string.notebook_routing_export),
            onConfirm = { launch { export.launch(RoutingExport.FILE_NAME) } }, onDismiss = close,
        )
        RoutingDialog.ConfirmImport -> Question(
            stringResource(R.string.notebook_routing_import_title), stringResource(R.string.notebook_routing_import_body), stringResource(R.string.notebook_routing_import),
            onConfirm = { launch { import.launch(arrayOf("*/*")) } }, onDismiss = close,
        )
    }
}

@Composable
private fun LoadedRouting(config: RoutingConfig, busy: Boolean, open: (RoutingDialog) -> Unit) {
    val entry = config.router(config.entry)
    Text(stringResource(R.string.notebook_routing_entry), style = MaterialTheme.typography.titleMedium)
    ListItem(
        headlineContent = { Text(entry?.name ?: config.entry.id) },
        supportingContent = { entry?.let { Text(RoutingEdits.summary(it, config)) } },
        trailingContent = { TextButton(onClick = { open(RoutingDialog.PickEntry) }, enabled = !busy) { Text(stringResource(R.string.notebook_routing_change)) } },
    )
    Text(stringResource(R.string.notebook_routing_routers), style = MaterialTheme.typography.titleMedium)
    config.routers.forEach { router ->
        ListItem(
            modifier = Modifier.clickable(enabled = !busy) { open(RoutingDialog.Edit(router)) },
            overlineContent = { Text(kindName(router)) },
            headlineContent = { Text(router.name) },
            supportingContent = { Text(RoutingEdits.summary(router, config)) },
            trailingContent = {
                IconButton(onClick = { open(RoutingDialog.Delete(router)) }, enabled = !busy) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.notebook_routing_delete))
                }
            },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { open(RoutingDialog.AddRouter) }, enabled = !busy) { Text(stringResource(R.string.notebook_routing_add)) }
        TextButton(onClick = { open(RoutingDialog.ConfirmReset) }, enabled = !busy) { Text(stringResource(R.string.notebook_routing_reset)) }
    }
}

@Composable
private fun AppsSection(ui: RoutingUi, viewModel: RoutingViewModel<Uri>, open: (RoutingDialog) -> Unit) {
    Text(stringResource(R.string.notebook_routing_apps), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.notebook_routing_apps_intro), style = MaterialTheme.typography.bodyMedium)
    if (ui.apps.isEmpty()) Text(stringResource(R.string.notebook_routing_no_apps), color = MaterialTheme.colorScheme.onSurfaceVariant)
    ui.apps.forEach { app ->
        ListItem(
            overlineContent = { Text(app.routers.joinToString { it.label }) },
            headlineContent = { Text(app.appName) },
            supportingContent = {
                val standing = if (app.trusted) R.string.notebook_routing_trusted else R.string.notebook_routing_not_trusted
                Column {
                    Text(app.packageName)
                    Text(app.namespace, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(standing))
                }
            },
            trailingContent = {
                if (app.trusted) {
                    TextButton(onClick = { open(RoutingDialog.Forget(app)) }, enabled = !ui.busy) { Text(stringResource(R.string.notebook_routing_stop_trusting)) }
                } else {
                    TextButton(onClick = { open(RoutingDialog.Trust(app)) }, enabled = !ui.busy) { Text(stringResource(R.string.notebook_routing_trust)) }
                }
            },
        )
    }
    TextButton(onClick = viewModel::load, enabled = !ui.busy) { Text(stringResource(R.string.notebook_routing_refresh)) }
}

@Composable
private fun kindName(router: RouterConfig): String = stringResource(
    when (router) {
        is RouterConfig.Note -> R.string.notebook_routing_kind_note
        is RouterConfig.Reminder -> R.string.notebook_routing_kind_reminder
        is RouterConfig.Switch -> R.string.notebook_routing_kind_switch
        is RouterConfig.App -> R.string.notebook_routing_kind_app
        is RouterConfig.Network -> R.string.notebook_routing_kind_network
    }
)

@Composable
private fun Question(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notebook_cancel)) } },
    )
}

@Composable
private fun DeleteQuestion(router: RouterConfig, config: RoutingConfig, onConfirm: (RoutingConfig) -> Unit, onDismiss: () -> Unit) {
    val without = RoutingEdits.withoutRouter(config, router.ref)
    if (without == null) {
        val reason = if (config.entry == router.ref) {
            stringResource(R.string.notebook_routing_cannot_delete_entry, router.name)
        } else {
            stringResource(R.string.notebook_routing_cannot_delete_target, RoutingEdits.referencedBy(config, router.ref).joinToString { it.name }, router.name)
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.notebook_routing_delete_title, router.name)) },
            text = { Text(reason) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notebook_routing_ok)) } },
        )
    } else {
        Question(
            stringResource(R.string.notebook_routing_delete_title, router.name), stringResource(R.string.notebook_routing_delete_body),
            stringResource(R.string.notebook_routing_delete), onConfirm = { onConfirm(without) }, onDismiss = onDismiss,
        )
    }
}

/** The signer is shown whole, so the user can check it against what the app's developer publishes. */
@Composable
private fun TrustQuestion(app: RouterApp, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebook_routing_trust_title, app.appName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.notebook_routing_trust_body))
                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                Text(app.namespace, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(stringResource(R.string.notebook_routing_trust_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notebook_cancel)) } },
    )
}

@Composable
private fun RouterPicker(title: String, routers: List<RouterConfig>, current: RouterRef?, onPick: (RouterRef) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                routers.forEach { router ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onPick(router.ref)
                            onDismiss()
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = router.ref == current, onClick = null)
                        Text(router.name, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notebook_cancel)) } },
    )
}

@Composable
private fun KindPicker(config: RoutingConfig, onPick: (RouterConfig) -> Unit, onDismiss: () -> Unit) {
    data class Kind(val name: Int, val hint: Int, val make: (String, String) -> RouterConfig)
    val kinds = listOf(
        Kind(R.string.notebook_routing_kind_note, R.string.notebook_routing_kind_note_hint) { id, name -> RouterConfig.Note(id, name) },
        Kind(R.string.notebook_routing_kind_reminder, R.string.notebook_routing_kind_reminder_hint) { id, name -> RouterConfig.Reminder(id, name) },
        Kind(R.string.notebook_routing_kind_switch, R.string.notebook_routing_kind_switch_hint) { id, name -> RouterConfig.Switch(id, name) },
        // An app router's ref is the picked router's, so it starts with none.
        Kind(R.string.notebook_routing_kind_app, R.string.notebook_routing_kind_app_hint) { _, name -> RouterConfig.App("", name, "", "", "") },
        Kind(R.string.notebook_routing_kind_network, R.string.notebook_routing_kind_network_hint) { id, name -> RouterConfig.Network(id, name, "") },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebook_routing_add)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                kinds.forEach { kind ->
                    val name = stringResource(kind.name)
                    ListItem(
                        modifier = Modifier.clickable {
                            // The name is the kind's, and the id its tag with the lowest free number; both can be changed.
                            onPick(kind.make(RoutingEdits.newId(config, name.lowercase()), name))
                        },
                        headlineContent = { Text(name) },
                        supportingContent = { Text(stringResource(kind.hint)) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notebook_cancel)) } },
    )
}

/**
 * Edits one router. Save validates the whole routing with this router in it, so a target that does not exist
 * or a pattern that does not compile is refused here with its reason, and nothing is stored. Picking an
 * app's router that is not trusted yet asks for the trust first, with the signer shown.
 */
@Composable
private fun RouterEditor(
    router: RouterConfig,
    config: RoutingConfig,
    apps: List<RouterApp>,
    onSave: (RouterConfig) -> Unit,
    onTrust: (RouterApp) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(router.ref) { mutableStateOf(router.name) }
    var rules by remember(router.ref) { mutableStateOf((router as? RouterConfig.Switch)?.rules.orEmpty()) }
    var otherwise by remember(router.ref) { mutableStateOf((router as? RouterConfig.Switch)?.otherwise) }
    var picked by remember(router.ref) {
        mutableStateOf((router as? RouterConfig.App)?.let { app -> apps.flatMap { it.routers }.firstOrNull { it.packageName == app.packageName && it.receiverClass == app.receiverClass } })
    }
    var askTrust by remember(router.ref) { mutableStateOf<Pair<RouterApp, InstalledRouter>?>(null) }
    var url by remember(router.ref) { mutableStateOf((router as? RouterConfig.Network)?.url ?: "https://") }
    var token by remember(router.ref) { mutableStateOf((router as? RouterConfig.Network)?.bearerToken.orEmpty()) }
    var shares by remember(router.ref) { mutableStateOf(router.shares ?: Shares()) }
    var problem by remember(router.ref) { mutableStateOf<String?>(null) }
    val others = config.routers.filter { it.ref != router.ref }
    val appKindName = stringResource(R.string.notebook_routing_kind_app)
    val pickOne = stringResource(R.string.notebook_routing_pick_one)
    fun built(): RouterConfig {
        val trimmed = name.trim()
        return when (router) {
            is RouterConfig.Note -> RouterConfig.Note(router.id, trimmed, confirm = router.confirm)
            is RouterConfig.Reminder -> RouterConfig.Reminder(router.id, trimmed, confirm = router.confirm)
            is RouterConfig.Switch -> RouterConfig.Switch(router.id, trimmed, rules, otherwise)
            is RouterConfig.App -> picked?.let { RouterConfig.App(it.id, trimmed, it.packageName, it.receiverClass, it.namespace, it.needs, shares, router.confirm) } ?: router.copy(name = trimmed, shares = shares)
            is RouterConfig.Network -> RouterConfig.Network(router.id, trimmed, url.trim(), token.trim().ifEmpty { null }, shares = shares, confirm = router.confirm)
        }
    }
    fun take(choice: InstalledRouter) {
        picked = choice
        // A router still called by its kind takes the picked router's label as its name.
        if (name.isBlank() || name == router.name && router.name == appKindName) name = choice.label
    }
    askTrust?.let { (app, choice) ->
        TrustQuestion(app, onConfirm = {
            onTrust(app)
            take(choice)
        }, onDismiss = { askTrust = null })
    }
    Dialog(onDismissRequest = onDismiss, properties = notebookDialogProperties()) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            NoKeyboardLearning {
                Column(Modifier.imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(kindName(router), style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.notebook_routing_field_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    when (router) {
                        is RouterConfig.Note, is RouterConfig.Reminder -> Unit
                        is RouterConfig.Switch -> SwitchFields(rules, otherwise, others, onRules = { rules = it }, onOtherwise = { otherwise = it })
                        is RouterConfig.App -> {
                            if (picked == null && router.packageName.isNotEmpty()) Text(stringResource(R.string.notebook_routing_app_gone), color = MaterialTheme.colorScheme.error)
                            AppFields(apps, picked, onPick = { app, choice -> if (app.trusted) take(choice) else askTrust = app to choice })
                            SharesFields(needed = picked?.needs ?: router.needs, shares, onShares = { shares = it })
                        }
                        is RouterConfig.Network -> {
                            NetworkFields(url, token, onUrl = { url = it }, onToken = { token = it })
                            SharesFields(needed = Need.entries.toSet(), shares, onShares = { shares = it })
                        }
                    }
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.notebook_cancel)) }
                        TextButton(onClick = {
                            val candidate = built()
                            if (candidate is RouterConfig.App && candidate.packageName.isEmpty()) {
                                problem = pickOne
                                return@TextButton
                            }
                            try {
                                RoutingEdits.withRouter(config, candidate, replacing = router.ref).requireWellFormed()
                                onSave(candidate)
                            } catch (e: IllegalArgumentException) {
                                problem = e.message
                            }
                        }) { Text(stringResource(R.string.notebook_save)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchFields(
    rules: List<SwitchRule>,
    otherwise: RouterRef?,
    targets: List<RouterConfig>,
    onRules: (List<SwitchRule>) -> Unit,
    onOtherwise: (RouterRef?) -> Unit,
) {
    Text(stringResource(R.string.notebook_routing_rules), style = MaterialTheme.typography.titleSmall)
    rules.forEachIndexed { index, rule ->
        fun replace(changed: SwitchRule) = onRules(rules.mapIndexed { i, r -> if (i == index) changed else r })
        OutlinedTextField(
            value = rule.pattern,
            onValueChange = { replace(rule.copy(pattern = it)) },
            label = { Text(stringResource(R.string.notebook_routing_field_pattern)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TargetPicker(stringResource(R.string.notebook_routing_forward_to), rule.router, targets, allowNone = false) { ref -> ref?.let { replace(rule.copy(router = it)) } }
            Checkbox(checked = rule.ignoreCase, onCheckedChange = { replace(rule.copy(ignoreCase = it)) })
            Text(stringResource(R.string.notebook_routing_ignore_case), style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = { onRules(rules.filterIndexed { i, _ -> i != index }) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.notebook_routing_remove_rule))
            }
        }
    }
    if (rules.size < RoutingConfig.MAX_RULES) {
        val first = targets.firstOrNull()
        OutlinedButton(onClick = { first?.let { onRules(rules + SwitchRule("", it.ref)) } }, enabled = first != null) { Text(stringResource(R.string.notebook_routing_add_rule)) }
    }
    TargetPicker(stringResource(R.string.notebook_routing_otherwise), otherwise, targets, allowNone = true, onPick = onOtherwise)
}

/** A button showing the current target's name, with a menu of the routers; [allowNone] adds a choice that flags the recording. */
@Composable
private fun TargetPicker(label: String, current: RouterRef?, targets: List<RouterConfig>, allowNone: Boolean, onPick: (RouterRef?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = targets.firstOrNull { it.ref == current }?.name ?: current?.id?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.notebook_routing_none)
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text("$label: $shown")
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (allowNone) {
                DropdownMenuItem(text = { Text(stringResource(R.string.notebook_routing_none)) }, onClick = {
                    onPick(null)
                    open = false
                })
            }
            targets.forEach { target ->
                DropdownMenuItem(text = { Text(target.name) }, onClick = {
                    onPick(target.ref)
                    open = false
                })
            }
        }
    }
}

/** Every router the installed apps declare, trusted or not; the app's name and namespace tell two routers of one label apart. */
@Composable
private fun AppFields(apps: List<RouterApp>, picked: InstalledRouter?, onPick: (RouterApp, InstalledRouter) -> Unit) {
    Text(stringResource(R.string.notebook_routing_pick_app), style = MaterialTheme.typography.titleSmall)
    if (apps.isEmpty()) Text(stringResource(R.string.notebook_routing_no_apps), color = MaterialTheme.colorScheme.onSurfaceVariant)
    apps.forEach { app ->
        app.routers.forEach { choice ->
            Row(Modifier.fillMaxWidth().clickable { onPick(app, choice) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = picked?.ref == choice.ref && picked?.packageName == choice.packageName, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                    Text(choice.label)
                    choice.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (choice.needs.isNotEmpty()) {
                        val wanted = choice.needs.sortedBy { it.ordinal }.map { needName(it) }
                        Text(stringResource(R.string.notebook_routing_wants, wanted.joinToString()), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("${app.appName} - ${app.namespace}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!app.trusted) Text(stringResource(R.string.notebook_routing_not_trusted), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun NetworkFields(url: String, token: String, onUrl: (String) -> Unit, onToken: (String) -> Unit) {
    OutlinedTextField(
        value = url,
        onValueChange = onUrl,
        label = { Text(stringResource(R.string.notebook_routing_field_url)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = token,
        onValueChange = onToken,
        label = { Text(stringResource(R.string.notebook_routing_field_token)) },
        supportingText = { Text(stringResource(R.string.notebook_routing_token_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * One grant per need. A need the router never declared gets no picker: the grant would change
 * nothing, and the user should not think the router is after it.
 */
@Composable
private fun SharesFields(needed: Set<Need>, shares: Shares, onShares: (Shares) -> Unit) {
    Text(stringResource(R.string.notebook_routing_shares), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(R.string.notebook_routing_shares_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Need.entries.forEach { need ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(needName(need), Modifier.weight(1f))
            if (need in needed) {
                GrantPicker(shares.of(need), onPick = { onShares(shares.with(need, it)) })
            } else {
                Text(stringResource(R.string.notebook_routing_not_requested), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun GrantPicker(current: Grant, onPick: (Grant) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) {
            Text(grantName(current))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Grant.entries.forEach { grant ->
                DropdownMenuItem(text = { Text(grantName(grant)) }, onClick = {
                    onPick(grant)
                    open = false
                })
            }
        }
    }
}

@Composable
private fun needName(need: Need): String = stringResource(
    when (need) {
        Need.LOCATION -> R.string.notebook_routing_need_location
        Need.RECORDED_AT -> R.string.notebook_routing_need_recorded_at
    },
)

@Composable
private fun grantName(grant: Grant): String = stringResource(
    when (grant) {
        Grant.ASK -> R.string.notebook_routing_grant_ask
        Grant.ALWAYS -> R.string.notebook_routing_grant_always
        Grant.NEVER -> R.string.notebook_routing_grant_never
    },
)

/** The certificates in force, built-in ones marked; any can be removed, and one added by its digest. */
@Composable
private fun CertificatesSection(ui: RoutingUi, open: (RoutingDialog) -> Unit) {
    Text(stringResource(R.string.notebook_routing_certificates), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.notebook_routing_certificates_intro), style = MaterialTheme.typography.bodyMedium)
    if (ui.certificates.isEmpty()) Text(stringResource(R.string.notebook_routing_no_certificates), color = MaterialTheme.colorScheme.onSurfaceVariant)
    ui.certificates.forEach { certificate ->
        ListItem(
            overlineContent = { if (certificate.builtIn) Text(stringResource(R.string.notebook_routing_built_in)) },
            headlineContent = { Text(certificate.label ?: stringResource(R.string.notebook_routing_unnamed)) },
            supportingContent = { Text(certificate.sha256, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) },
            trailingContent = {
                TextButton(onClick = { open(RoutingDialog.RemoveCertificate(certificate)) }, enabled = !ui.busy) { Text(stringResource(R.string.notebook_routing_remove)) }
            },
        )
    }
    OutlinedButton(onClick = { open(RoutingDialog.AddCertificate) }, enabled = !ui.busy) { Text(stringResource(R.string.notebook_routing_add_certificate)) }
}

/** A name and a digest; the digest's shape is checked here, so a typo is refused before anything is written. */
@Composable
private fun AddCertificateDialog(onAdd: (String, String?) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf("") }
    var digest by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.notebook_routing_add_certificate)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text(stringResource(R.string.notebook_routing_field_certificate_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = digest,
                    onValueChange = { digest = it },
                    label = { Text(stringResource(R.string.notebook_routing_field_certificate_digest)) },
                    supportingText = { Text(stringResource(R.string.notebook_routing_certificate_digest_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val normalized = RouterTrust.normalizeDigest(digest)
                if (normalized == null) {
                    problem = RoutingViewModel.BAD_DIGEST
                } else {
                    onAdd(normalized, label.trim().ifEmpty { null })
                    onDismiss()
                }
            }) { Text(stringResource(R.string.notebook_routing_add_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notebook_cancel)) } },
    )
}

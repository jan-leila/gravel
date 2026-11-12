package com.anopticlabs.gravel.gitsource

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Source
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coredevices.pebble.ui.TopBarIconButtonWithToolTip
import coredevices.pebble.ui.TopBarParams
import coredevices.ui.M3Dialog
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun GitSourcesScreen(topBarParams: TopBarParams) {
    val viewModel = koinViewModel<GitSourcesViewModel>()
    val sources by viewModel.sources.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val addStep by viewModel.addStep.collectAsState()
    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(null)
        topBarParams.title("Git Sources")
        topBarParams.actions {
            TopBarIconButtonWithToolTip(onClick = viewModel::checkAll, icon = Icons.Filled.Refresh, description = "Check All")
        }
        viewModel.refresh()
        viewModel.messages.collect { topBarParams.showSnackbar(it) }
    }

    var approving by remember { mutableStateOf<GitSourceView?>(null) }
    var editing by remember { mutableStateOf<GitSourceView?>(null) }
    var notesOf by remember { mutableStateOf<GitSourceView?>(null) }
    var forgetting by remember { mutableStateOf<GitSourceView?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = viewModel::startAdd) { Icon(Icons.Filled.Add, contentDescription = "Add Repository") }
        },
    ) { insets ->
        val list = sources
        when {
            list == null -> Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Text(
                "Follow a GitHub, Codeberg, Forgejo or Gitea repository that publishes .pbw files as releases. " +
                    "Gravel installs its newest release and keeps it updated.",
                modifier = Modifier.padding(insets).padding(16.dp),
            )
            else -> LazyColumn(contentPadding = insets) {
                items(list, key = { it.source.uuid }) { view ->
                    GitSourceRow(
                        view = view,
                        busy = view.source.uuid in busy,
                        onInstall = { if (view.status == GitSourceStatus.NEEDS_APPROVAL) approving = view else viewModel.install(view) },
                        onCheck = { viewModel.check(view) },
                        onEdit = { editing = view },
                        onNotes = { notesOf = view },
                        onForget = { forgetting = view },
                    )
                }
            }
        }
    }

    addStep?.let { step ->
        AddGitSourceDialog(
            step = step,
            onFormChange = viewModel::editForm,
            onSubmit = viewModel::submitUrl,
            onChooseAsset = viewModel::chooseAsset,
            onConfirm = viewModel::confirmAdd,
            onDismiss = viewModel::cancelAdd,
        )
    }
    approving?.let { view ->
        ApproveCapabilitiesDialog(
            view = view,
            onApprove = {
                viewModel.install(view, view.pendingCapabilities)
                approving = null
            },
            onDismiss = { approving = null },
        )
    }
    editing?.let { view ->
        EditGitSourceDialog(
            view = view,
            onSave = { prereleases, auto, pattern ->
                viewModel.setOptions(view, prereleases, auto, pattern)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    notesOf?.let { view ->
        M3Dialog(
            onDismissRequest = { notesOf = null },
            title = { Text("${view.source.availableTag ?: view.source.installedTag}") },
            buttons = { TextButton(onClick = { notesOf = null }) { Text("Close") } },
            scrollableContent = true,
        ) {
            Text(view.source.availableNotes?.takeIf { it.isNotBlank() } ?: "This release has no notes.")
        }
    }
    forgetting?.let { view ->
        M3Dialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Stop following ${view.repo.displayName}?") },
            buttons = {
                TextButton(onClick = { forgetting = null }) { Text("Cancel") }
                TextButton(onClick = {
                    viewModel.forget(view)
                    forgetting = null
                }) { Text("Stop Following") }
            },
        ) {
            Text("${view.source.appTitle} stays in your locker and on your watch, but is no longer updated from this repository.")
        }
    }
}

@Composable
private fun GitSourceRow(
    view: GitSourceView,
    busy: Boolean,
    onInstall: () -> Unit,
    onCheck: () -> Unit,
    onEdit: () -> Unit,
    onNotes: () -> Unit,
    onForget: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var menuOpen by remember { mutableStateOf(false) }
    val source = view.source
    val action = when (view.status) {
        GitSourceStatus.UPDATE_AVAILABLE -> "Update"
        GitSourceStatus.NEEDS_APPROVAL -> "Review"
        GitSourceStatus.REPLACED -> "Reinstall ${source.availableTag ?: source.installedTag}"
        GitSourceStatus.NOT_INSTALLED -> "Install"
        GitSourceStatus.UP_TO_DATE -> null
    }
    ListItem(
        headlineContent = { Text(source.appTitle) },
        supportingContent = {
            Column {
                Text(view.repo.displayName, style = MaterialTheme.typography.bodySmall)
                val versions = if (view.status == GitSourceStatus.UPDATE_AVAILABLE || view.status == GitSourceStatus.NEEDS_APPROVAL) {
                    "${source.installedTag} → ${source.availableTag}"
                } else {
                    source.installedTag
                }
                Text("$versions · ${view.status.label()}" + if (source.autoUpdate) " · Auto-update" else "")
                view.error?.let { Text(it.message(), color = MaterialTheme.colorScheme.error) }
                if (action != null) {
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = onInstall, enabled = !busy) { Text(action) }
                }
            }
        },
        trailingContent = {
            if (busy) {
                CircularProgressIndicator()
            } else {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Check Now") }, onClick = { menuOpen = false; onCheck() })
                    DropdownMenuItem(text = { Text("Settings") }, onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(text = { Text("Release Notes") }, onClick = { menuOpen = false; onNotes() })
                    DropdownMenuItem(text = { Text("Open Repository") }, onClick = { menuOpen = false; uriHandler.openUri(view.repo.webUrl) })
                    DropdownMenuItem(text = { Text("Stop Following") }, onClick = { menuOpen = false; onForget() })
                }
            }
        },
    )
}

@Composable
private fun AddGitSourceDialog(
    step: AddStep,
    onFormChange: (AddForm) -> Unit,
    onSubmit: () -> Unit,
    onChooseAsset: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var chosen by remember(step is AddStep.ChooseAsset) { mutableStateOf<String?>(null) }
    val loading = when (step) {
        is AddStep.Enter -> step.loading
        is AddStep.ChooseAsset -> step.loading
        is AddStep.Confirm -> step.installing
    }
    M3Dialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Source, contentDescription = null) },
        title = { Text(if (step is AddStep.Confirm) "Install ${step.prepared.facts.title}?" else "Follow a Repository") },
        scrollableContent = true,
        buttons = {
            if (loading) CircularProgressIndicator(Modifier.padding(end = 8.dp))
            TextButton(onClick = onDismiss) { Text("Cancel") }
            when (step) {
                is AddStep.Enter -> TextButton(onClick = onSubmit, enabled = !loading && step.form.url.isNotBlank()) { Text("Next") }
                is AddStep.ChooseAsset -> TextButton(onClick = { chosen?.let(onChooseAsset) }, enabled = !loading && chosen != null) { Text("Next") }
                is AddStep.Confirm -> TextButton(onClick = onConfirm, enabled = !loading) { Text("Install") }
            }
        },
    ) {
        Column {
            when (step) {
                is AddStep.Enter -> {
                    TextField(
                        value = step.form.url,
                        onValueChange = { onFormChange(step.form.copy(url = it)) },
                        label = { Text("Repository address") },
                        placeholder = { Text("https://github.com/owner/repo") },
                        singleLine = true,
                        enabled = !step.loading,
                        isError = step.error != null,
                    )
                    step.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
                    ToggleRow("Include pre-releases", step.form.includePrereleases, enabled = !step.loading) {
                        onFormChange(step.form.copy(includePrereleases = it))
                    }
                    ToggleRow("Update automatically", step.form.autoUpdate, enabled = !step.loading) {
                        onFormChange(step.form.copy(autoUpdate = it))
                    }
                    Text(
                        "Updates that ask for more access always wait for you.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                is AddStep.ChooseAsset -> {
                    Text("${step.preview.release.name} has several .pbw files. Which one should Gravel install, now and in later releases?")
                    step.preview.names.forEach { name ->
                        Row(Modifier.fillMaxWidth().clickable { chosen = name }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = chosen == name, onClick = { chosen = name })
                            Text(name)
                        }
                    }
                }
                is AddStep.Confirm -> ConfirmDetails(step.prepared)
            }
        }
    }
}

@Composable
private fun ConfirmDetails(prepared: PreparedInstall) {
    val facts = prepared.facts
    Text("From ${prepared.repo.displayName}, release ${prepared.release.name} (${prepared.asset.name}), version ${facts.version}.")
    Spacer(Modifier.height(8.dp))
    Text("App ID", style = MaterialTheme.typography.labelMedium)
    Text(facts.uuid.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    Text("Later releases install only if they have this App ID.", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    Text("Declares", style = MaterialTheme.typography.labelMedium)
    Text(facts.capabilities.takeIf { it.isNotEmpty() }?.sorted()?.joinToString("\n") { "• ${capabilityLabel(it)}" } ?: "Nothing beyond running on the watch")
    Spacer(Modifier.height(8.dp))
    Text(
        if (prepared.asset.sha256 != null) {
            "The download matched the checksum the release lists."
        } else {
            "This host lists no checksum for release files; the download was checked against the listed size only."
        },
        style = MaterialTheme.typography.bodySmall,
    )
    prepared.replacesSource?.let { old ->
        Spacer(Modifier.height(8.dp))
        Text(
            "This app is already followed from ${old.toGitRepo()?.displayName ?: old.repo}; it will follow this repository instead.",
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (prepared.alreadyInLocker && prepared.replacesSource == null) {
        Spacer(Modifier.height(8.dp))
        Text("This replaces the copy of the app already in your locker.", color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ApproveCapabilitiesDialog(view: GitSourceView, onApprove: () -> Unit, onDismiss: () -> Unit) {
    M3Dialog(
        onDismissRequest = onDismiss,
        title = { Text("${view.source.appTitle} ${view.source.availableTag} asks for more") },
        buttons = {
            TextButton(onClick = onDismiss) { Text("Not Now") }
            TextButton(onClick = onApprove) { Text("Install") }
        },
    ) {
        Column {
            Text("This release declares access the installed one did not:")
            Spacer(Modifier.height(8.dp))
            Text(view.pendingCapabilities.sorted().joinToString("\n") { "• ${capabilityLabel(it)}" })
            Spacer(Modifier.height(8.dp))
            Text(
                "Location and internet access still follow Settings > Watch App Permissions.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun EditGitSourceDialog(view: GitSourceView, onSave: (Boolean, Boolean, String?) -> Unit, onDismiss: () -> Unit) {
    var prereleases by remember { mutableStateOf(view.source.includePrereleases) }
    var auto by remember { mutableStateOf(view.source.autoUpdate) }
    var pattern by remember { mutableStateOf(view.source.assetPattern.orEmpty()) }
    val patternValid = pattern.isEmpty() || runCatching { Regex(pattern) }.isSuccess
    M3Dialog(
        onDismissRequest = onDismiss,
        title = { Text(view.repo.displayName) },
        scrollableContent = true,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            TextButton(onClick = { onSave(prereleases, auto, pattern.ifEmpty { null }) }, enabled = patternValid) { Text("Save") }
        },
    ) {
        Column {
            ToggleRow("Include pre-releases", prereleases) { prereleases = it }
            ToggleRow("Update automatically", auto) { auto = it }
            TextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = { Text("File pattern (regular expression)") },
                placeholder = { Text("Any single .pbw") },
                isError = !patternValid,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            Text("Only .pbw files whose whole name matches are considered.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

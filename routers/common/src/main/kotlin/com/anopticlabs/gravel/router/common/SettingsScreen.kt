package com.anopticlabs.gravel.router.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one screen a router app has: what it is, its settings, a test send, its listed routers
 * when it has any ([routers], kept in [routerList]), and the deliveries
 * set aside as not sent, each with its reason, to try again or discard. Values are saved field by
 * field as the user leaves them; the test runs the app's sender with a fixed text. On its first
 * showing the screen asks for the notification permission, which the worker needs to report a
 * delivery it set aside while the app is not on screen.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    title: String,
    intro: String,
    fields: List<Field>,
    settings: Settings,
    sender: Sender,
    routers: RouterListSource? = null,
    routerList: RouterList? = null,
) {
    val context = LocalContext.current
    val outbox = remember { Outbox(context) }
    val values = remember { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.key, settings[it.key].orEmpty()) } } }
    var result by remember { mutableStateOf<String?>(null) }
    var notSent by remember { mutableStateOf(emptyList<NotSent>()) }
    val scope = rememberCoroutineScope()
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        notSent = withContext(Dispatchers.IO) { outbox.listNotSent() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text(title) }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(intro, style = MaterialTheme.typography.bodyMedium)
            fields.forEach { field ->
                val choices = field.choices
                if (choices != null) {
                    ChoiceField(field, values[field.key].orEmpty(), choices) {
                        values[field.key] = it
                        settings[field.key] = it
                    }
                } else {
                    OutlinedTextField(
                        value = values[field.key].orEmpty(),
                        onValueChange = {
                            values[field.key] = it
                            settings[field.key] = it
                        },
                        label = { Text(field.label + if (field.required) "" else " (optional)") },
                        supportingText = field.hint?.let { { Text(it) } },
                        singleLine = true,
                        visualTransformation = if (field.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = if (field.secret) KeyboardType.Password else KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Button(onClick = {
                result = "Sending a test…"
                scope.launch {
                    result = withContext(Dispatchers.IO) {
                        settings.missing()?.let { return@withContext "${it.label} is not set." }
                        testSend(sender, null)
                    }
                }
            }) { Text("Send a test") }
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (routers != null && routerList != null) RoutersSection(routers, routerList, settings, sender)
            if (notSent.isNotEmpty()) {
                Text("Not sent", style = MaterialTheme.typography.titleMedium)
                Text("These recordings were not sent. Fix the settings, then try them again, or discard them.", style = MaterialTheme.typography.bodyMedium)
                notSent.forEach { item ->
                    NotSentRow(
                        item,
                        onTryAgain = {
                            scope.launch {
                                withContext(Dispatchers.IO) { outbox.tryAgain(item) }
                                OutboxWorker.enqueue(context)
                                notSent = withContext(Dispatchers.IO) { outbox.listNotSent() }
                            }
                        },
                        onDiscard = {
                            scope.launch {
                                notSent = withContext(Dispatchers.IO) {
                                    outbox.discard(item)
                                    outbox.listNotSent()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NotSentRow(item: NotSent, onTryAgain: () -> Unit, onDiscard: () -> Unit) {
    val at = remember(item.atMillis) { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.atMillis)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.delivery.text, style = MaterialTheme.typography.bodyMedium)
        Text("$at · ${item.reason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onTryAgain) { Text("Try again") }
            TextButton(onClick = onDiscard) { Text("Discard") }
        }
    }
}

/**
 * The app's listed routers: fetched from the service with a refresh, each shared with Gravel or
 * not by its checkbox, or declared here with an add form and removed one by one. Each has a test
 * send of its own. The list is read and written off the main thread.
 */
@Composable
private fun RoutersSection(source: RouterListSource, list: RouterList, settings: Settings, sender: Sender) {
    var routers by remember { mutableStateOf(emptyList<ListedRouter>()) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { routers = withContext(Dispatchers.IO) { list.all() } }
    fun change(block: () -> Unit) {
        scope.launch {
            status = withContext(Dispatchers.IO) {
                try {
                    block()
                    null
                } catch (e: IOException) {
                    "Not saved: ${e.message ?: e::class.simpleName}"
                }
            }
            routers = withContext(Dispatchers.IO) { list.all() }
        }
    }
    Text(source.title, style = MaterialTheme.typography.titleMedium)
    Text(source.intro, style = MaterialTheme.typography.bodyMedium)
    when (source) {
        is RouterListSource.Fetched -> Button(onClick = {
            status = "Refreshing…"
            scope.launch {
                status = withContext(Dispatchers.IO) {
                    settings.missing()?.let { return@withContext "${it.label} is not set." }
                    try {
                        val fetched = source.fetch()
                        list.merge(fetched, source.sharedByDefault)
                        "${fetched.size} found."
                    } catch (e: IOException) {
                        "Not refreshed: ${e.message ?: e::class.simpleName}"
                    } catch (e: RuntimeException) {
                        "Not refreshed: ${e.message ?: e::class.simpleName}"
                    }
                }
                routers = withContext(Dispatchers.IO) { list.all() }
            }
        }) { Text("Refresh") }
        is RouterListSource.Declared -> DeclareForm(source) { made -> change { list.add(made) } }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    routers.forEach { router ->
        ListedRow(
            router,
            fetched = source is RouterListSource.Fetched,
            sender = sender,
            onShared = { shared -> change { list.setShared(router.id, shared) } },
            onRemove = { change { list.remove(router.id) } },
        )
    }
}

@Composable
private fun DeclareForm(source: RouterListSource.Declared, onMade: (ListedRouter) -> Unit) {
    val values = remember { mutableStateMapOf<String, String>() }
    var problem by remember { mutableStateOf<String?>(null) }
    source.fields.forEach { field ->
        OutlinedTextField(
            value = values[field.key].orEmpty(),
            onValueChange = { values[field.key] = it },
            label = { Text(field.label + if (field.required) "" else " (optional)") },
            supportingText = field.hint?.let { { Text(it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    OutlinedButton(onClick = {
        val typed = values.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        val missing = source.fields.firstOrNull { it.required && typed[it.key] == null }
        if (missing != null) {
            problem = "${missing.label} is not set."
            return@OutlinedButton
        }
        try {
            onMade(source.make(typed))
            values.clear()
            problem = null
        } catch (e: IllegalArgumentException) {
            problem = e.message
        }
    }) { Text("Add") }
}

@Composable
private fun ListedRow(router: ListedRouter, fetched: Boolean, sender: Sender, onShared: (Boolean) -> Unit, onRemove: () -> Unit) {
    var result by remember(router.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (fetched) Checkbox(checked = router.shared, onCheckedChange = onShared)
        Column(Modifier.weight(1f)) {
            Text(router.label, style = MaterialTheme.typography.bodyMedium)
            router.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (fetched && !router.shared) Text("Not shown to Gravel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = {
            result = "Sending a test…"
            scope.launch { result = withContext(Dispatchers.IO) { testSend(sender, router.id) } }
        }) { Text("Test") }
        if (!fetched) TextButton(onClick = onRemove) { Text("Remove") }
    }
}

private fun testSend(sender: Sender, routerId: String?): String = try {
    when (val sent = sender.send(Delivery(TEST_CAPTURE_ID, TEST_TEXT, null, null, routerId))) {
        SendResult.Sent -> "Sent."
        is SendResult.Refused -> "Refused: ${sent.reason}"
        is SendResult.Retry -> "Not sent: ${sent.reason}"
    }
} catch (e: RuntimeException) {
    "Not sent: ${e::class.simpleName}"
}

@Composable
private fun ChoiceField(field: Field, value: String, choices: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("${field.label}: ${value.ifEmpty { choices.first() }}") }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        choices.forEach { choice ->
            DropdownMenuItem(text = { Text(choice) }, onClick = {
                onPick(choice)
                open = false
            })
        }
    }
}

const val TEST_CAPTURE_ID = "test-send"
const val TEST_TEXT = "A test from a Gravel router app."

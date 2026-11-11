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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one screen a router app has: what it is, its settings, a test send, and the deliveries
 * set aside as not sent, each with its reason, to try again or discard. Values are saved field by
 * field as the user leaves them; the test runs the app's sender with a fixed text. On its first
 * showing the screen asks for the notification permission, which the worker needs to report a
 * delivery it set aside while the app is not on screen.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(title: String, intro: String, fields: List<Field>, settings: Settings, sender: Sender) {
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
                        try {
                            when (val sent = sender.send(Delivery(TEST_CAPTURE_ID, TEST_TEXT, null, null))) {
                                SendResult.Sent -> "Sent."
                                is SendResult.Refused -> "Refused: ${sent.reason}"
                                is SendResult.Retry -> "Not sent: ${sent.reason}"
                            }
                        } catch (e: RuntimeException) {
                            "Not sent: ${e::class.simpleName}"
                        }
                    }
                }
            }) { Text("Send a test") }
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
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

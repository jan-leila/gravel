package com.anopticlabs.gravel.router.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The one screen a router app has: what it is, its settings, and a test send. Values are
 * saved field by field as the user leaves them; the test runs the app's sender with a fixed text.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(title: String, intro: String, fields: List<Field>, settings: Settings, sender: Sender) {
    val values = remember { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.key, settings[it.key].orEmpty()) } } }
    var result by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
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

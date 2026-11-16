package com.anopticlabs.gravel.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.anopticlabs.gravel.textserver.SelfHostedTextService
import com.anopticlabs.gravel.textserver.TextServerException
import com.anopticlabs.gravel.textserver.TextServerMode
import com.anopticlabs.gravel.textserver.TextServerStore
import com.anopticlabs.gravel.textserver.TextServerTestResult
import coredevices.pebble.ui.ServerDialogSpec
import coredevices.pebble.ui.ServerSettingsDialog
import coredevices.pebble.ui.saveServerTrust
import coredevices.util.CoreConfigHolder
import coredevices.util.transcription.serverHostPort
import org.koin.compose.koinInject

/** What the text server's connection test says for what the server answered. */
internal fun textServerTestText(result: TextServerTestResult): String = when (result.status) {
    in 200..299 -> if (result.chatReply) {
        "Server answered. The Notebook can use it."
    } else {
        "Server answered, but not with a chat completion. Ollama's endpoint is /v1/chat/completions."
    }
    401, 403 -> "Server rejected the token (HTTP ${result.status})."
    404 -> "Nothing at that path, or no such model on the server (HTTP 404). Ollama's endpoint is " +
        "/v1/chat/completions, and `ollama list` names its models."
    400 -> "Server refused the request (HTTP 400). Check the model name."
    else -> "Server returned HTTP ${result.status}."
}

/** The setting's choices, worded as the speech recognition ones are. */
internal fun textServerModeText(mode: TextServerMode): String = when (mode) {
    TextServerMode.LocalOnly -> "Local Only"
    TextServerMode.ServerOnly -> "Server Only"
    TextServerMode.ServerFirst -> "Server (with Local Fallback)"
    TextServerMode.LocalFirst -> "Local (with Server Fallback)"
}

/** The modes offered: the server ones only once a server is saved, as for speech recognition. */
internal fun textServerModes(serverConfigured: Boolean): List<TextServerMode> =
    if (serverConfigured) TextServerMode.entries else listOf(TextServerMode.LocalOnly)

/**
 * The text server dialog's Save: the store's writes before the config's, as the transcription server's Save does,
 * and the server modes back to Local Only when the server is removed, since they would have nothing to run.
 */
internal fun saveTextServerSettings(
    holder: CoreConfigHolder,
    store: TextServerStore,
    url: String,
    model: String,
    token: String,
    clearToken: Boolean,
    savedHostPort: String?,
) {
    val cleaned = url.trim().ifBlank { null }
    saveServerTrust(store, cleaned, cleaned?.let(::serverHostPort), token, clearToken, savedHostPort)
    val config = holder.config.value
    val text = config.textServerConfig
    holder.update(
        config.copy(
            textServerConfig = text.copy(
                serverUrl = cleaned,
                serverModel = model.trim().ifBlank { null },
                mode = if (cleaned == null) TextServerMode.LocalOnly else text.mode,
            ),
        ),
    )
}

/** Configures the Notebook's self-hosted text model server ([ServerSettingsDialog]); a model name is required. */
@Composable
fun TextServerDialog(onDismissRequest: () -> Unit) {
    val holder: CoreConfigHolder = koinInject()
    val store: TextServerStore = koinInject()
    val service: SelfHostedTextService = koinInject()
    val spec = remember {
        val initial = holder.config.value.textServerConfig
        ServerDialogSpec(
            title = "Self-hosted text model server",
            description = "Sends the text of Notebook recordings, in the server modes, to your own text model server " +
                "over https to title and tag notes and word reminders, only while this phone is using Wi-Fi that " +
                "isn't marked metered unless Use the Server on Mobile Data is on. Works with any OpenAI-compatible " +
                "chat completions server, such as Ollama's.",
            urlPlaceholder = "https://ollama.home.lan/v1/chat/completions",
            modelLabel = "Model name",
            modelHint = "The model's name on the server, such as gemma4:e4b.",
            modelRequired = true,
            initialUrl = initial.serverUrl,
            initialModel = initial.serverModel,
            store = store,
            test = { url, model, token ->
                try {
                    textServerTestText(service.testConnection(url, model.orEmpty(), token))
                } catch (e: TextServerException.Unreachable) {
                    "Could not reach the server: ${e.message}"
                } catch (e: TextServerException) {
                    e.message ?: "The request failed."
                }
            },
            save = { url, model, token, clearToken, savedHostPort ->
                saveTextServerSettings(holder, store, url, model, token, clearToken, savedHostPort)
            },
        )
    }
    ServerSettingsDialog(spec, onDismissRequest)
}

/**
 * Settings > AI > LLM's server rows: the server itself, where the text model runs, and, with a server mode chosen,
 * whether the server may be used on mobile data.
 */
@Composable
fun TextServerSection() {
    val holder: CoreConfigHolder = koinInject()
    val service: SelfHostedTextService = koinInject()
    val config by holder.config.collectAsState()
    val text = config.textServerConfig
    val url = text.serverUrl?.trim()
    // Read after the state above, so each change of the config recomposes with a fresh answer.
    val configured = service.configured()
    var showDialog by remember { mutableStateOf(false) }
    var choosingMode by remember { mutableStateOf(false) }

    Column {
        ListItem(
            modifier = Modifier.fillMaxWidth().clickable { showDialog = true },
            headlineContent = { Text("Self-hosted Server") },
            supportingContent = { Text(url?.let { serverHostPort(it) ?: "Invalid URL" } ?: "Not set") },
        )
        if (configured) {
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { choosingMode = true },
                headlineContent = { Text("Where the Text Model Runs") },
                supportingContent = { Text(textServerModeText(text.mode)) },
            )
        }
        if (configured && text.mode != TextServerMode.LocalOnly) {
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable {
                    holder.update(config.copy(textServerConfig = text.copy(serverOnMobileData = !text.serverOnMobileData)))
                },
                headlineContent = { Text("Use the Server on Mobile Data") },
                supportingContent = {
                    Text(
                        if (text.mode == TextServerMode.ServerOnly) {
                            "Otherwise, Notebook recordings wait for Wi-Fi that isn't marked metered."
                        } else {
                            "Otherwise, away from Wi-Fi that isn't marked metered, this phone's text model answers."
                        },
                    )
                },
                trailingContent = {
                    Switch(
                        checked = text.serverOnMobileData,
                        onCheckedChange = { holder.update(config.copy(textServerConfig = text.copy(serverOnMobileData = it))) },
                    )
                },
            )
        }
    }
    if (showDialog) TextServerDialog(onDismissRequest = { showDialog = false })
    if (choosingMode) {
        AlertDialog(
            onDismissRequest = { choosingMode = false },
            title = { Text("Where the Text Model Runs") },
            text = {
                Column {
                    textServerModes(configured).forEach { mode ->
                        Row(
                            modifier = Modifier.fillMaxWidth().selectable(selected = mode == text.mode) {
                                holder.update(holder.config.value.let { it.copy(textServerConfig = it.textServerConfig.copy(mode = mode)) })
                                choosingMode = false
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == text.mode, onClick = null)
                            Text(textServerModeText(mode), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingMode = false }) { Text("Cancel") } },
        )
    }
}

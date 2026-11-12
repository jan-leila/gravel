package com.anopticlabs.gravel.watchsync

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coredevices.pebble.rememberLibPebble
import org.koin.compose.koinInject

/**
 * Asks, for the first watch waiting in [FirstSyncPrompt.pending], whether to keep what it holds or
 * replace it with this phone's. It cannot be dismissed without an answer: the watch's sync waits on
 * it, and it goes away by itself if the watch disconnects.
 */
@Composable
fun FirstSyncDialog() {
    val prompt: FirstSyncPrompt = koinInject()
    val pending by prompt.pending.collectAsState()
    val request = pending.firstOrNull()?.request ?: return
    val watches by rememberLibPebble().watches.collectAsState()
    val name = watches.firstOrNull { it.identifier == request.identifier }?.displayName() ?: "Your watch"
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("$name is already set up") },
        text = {
            Column {
                Text(
                    if (request.usedWithAnotherPhone) {
                        "It was last used with another phone."
                    } else {
                        "This phone hasn't set it up before, for example because the app was reinstalled."
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Keep: the watch keeps its apps, watchfaces, their settings and its own settings, " +
                        "and the app takes in the settings the watch reports. Its timeline, notifications " +
                        "and weather are rebuilt from this phone. Store apps join this phone's list as " +
                        "you use them on the watch.",
                )
                Spacer(Modifier.height(8.dp))
                Text("Replace: the watch is wiped and set up again from this phone.")
            }
        },
        confirmButton = {
            TextButton(onClick = { prompt.answer(request.identifier, FirstSyncChoice.KeepWatch) }) {
                Text("Keep watch")
            }
        },
        dismissButton = {
            TextButton(onClick = { prompt.answer(request.identifier, FirstSyncChoice.ReplaceWatch) }) {
                Text("Replace")
            }
        },
    )
}

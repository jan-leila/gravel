package com.anopticlabs.gravel.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.russhwolf.settings.Settings
import coreapp.composeapp.generated.resources.Res
import coreapp.composeapp.generated.resources.fdroid_logo
import coreapp.composeapp.generated.resources.unsigned_listing_close
import coreapp.composeapp.generated.resources.unsigned_listing_dont_show_again
import coreapp.composeapp.generated.resources.unsigned_listing_how
import coreapp.composeapp.generated.resources.unsigned_listing_install_signed
import coreapp.composeapp.generated.resources.unsigned_listing_backup_restore_menu_back_up
import coreapp.composeapp.generated.resources.unsigned_listing_backup_restore_menu_notebook
import coreapp.composeapp.generated.resources.unsigned_listing_backup_restore_menu_restore
import coreapp.composeapp.generated.resources.unsigned_listing_backup_restore_menu_settings
import coreapp.composeapp.generated.resources.unsigned_listing_step_re_pair
import coreapp.composeapp.generated.resources.unsigned_listing_step_backup
import coreapp.composeapp.generated.resources.unsigned_listing_step_install
import coreapp.composeapp.generated.resources.unsigned_listing_step_restore
import coreapp.composeapp.generated.resources.unsigned_listing_step_uninstall
import coreapp.composeapp.generated.resources.unsigned_listing_title
import coreapp.composeapp.generated.resources.unsigned_listing_why
import coredevices.util.CommonBuildKonfig
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.mp.KoinPlatform

const val SIGNED_APPLICATION_ID = "com.anopticlabs.gravel.signed"

const val SIGNED_FDROID_URL = "https://f-droid.org/packages/$SIGNED_APPLICATION_ID/"

const val UNSIGNED_LISTING_HIDDEN = "gravel_unsigned_listing_hidden"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UnsignedListingDialog() {
    val settings: Settings = koinInject()
    var open by rememberSaveable {
        mutableStateOf(CommonBuildKonfig.UNSIGNED_LISTING && !settings.getBoolean(UNSIGNED_LISTING_HIDDEN, false))
    }
    if (!open) return
    // No binding (iOS) or no answer yet: unknown counts as data, but nothing is shown until it is known.
    val checkNotebookData = remember { KoinPlatform.getKoin().getOrNull<NotebookDataCheck>() }
    val hasData by produceState<Boolean?>(null, checkNotebookData) { value = checkNotebookData?.hasData() ?: true }
    val needsBackup = hasData ?: return
    var dontShowAgain by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val close = {
        if (dontShowAgain) settings.putBoolean(UNSIGNED_LISTING_HIDDEN, true)
        open = false
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(Res.string.unsigned_listing_title)) },
        text = {
            Column {
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(Res.string.unsigned_listing_why))
                    Text(stringResource(Res.string.unsigned_listing_how))
                    if (needsBackup) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(Res.string.unsigned_listing_step_backup, 1))
                            Text(stringResource(Res.string.unsigned_listing_backup_restore_menu_settings), fontFamily = FontFamily.Monospace)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = ">")
                            Text(stringResource(Res.string.unsigned_listing_backup_restore_menu_notebook), fontFamily = FontFamily.Monospace)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = ">")
                            Text(stringResource(Res.string.unsigned_listing_backup_restore_menu_back_up), fontFamily = FontFamily.Monospace)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(Res.string.unsigned_listing_step_uninstall, if (needsBackup) 2 else 1))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(Res.string.unsigned_listing_step_install, if (needsBackup) 3 else 2))
                    }
                    if (needsBackup) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(Res.string.unsigned_listing_step_restore, 4))
                            Text(stringResource(Res.string.unsigned_listing_backup_restore_menu_settings), fontFamily = FontFamily.Monospace)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = ">")
                            Text(stringResource(Res.string.unsigned_listing_backup_restore_menu_notebook), fontFamily = FontFamily.Monospace)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = ">")
                            Text(stringResource(Res.string.unsigned_listing_backup_restore_menu_restore), fontFamily = FontFamily.Monospace)
                        }
                    } else {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(Res.string.unsigned_listing_step_re_pair, 3))
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = dontShowAgain, onCheckedChange = { dontShowAgain = it })
                    Text(stringResource(Res.string.unsigned_listing_dont_show_again))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { uriHandler.openUri(SIGNED_FDROID_URL) }) {
                Icon(painterResource(Res.drawable.fdroid_logo), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.unsigned_listing_install_signed))
            }
        },
        dismissButton = {
            TextButton(onClick = close) { Text(stringResource(Res.string.unsigned_listing_close)) }
        },
    )
}


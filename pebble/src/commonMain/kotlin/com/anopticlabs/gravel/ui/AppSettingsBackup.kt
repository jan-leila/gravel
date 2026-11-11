package com.anopticlabs.gravel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import org.koin.compose.getKoin

/**
 * The whole-app backup, as Settings starts it; the platform's app module binds it. [Dialogs] shows
 * the run in progress and is kept composed above every screen, so a system file picker's answer
 * lands whatever screen the user returns to.
 */
interface AppSettingsBackup {
    /** Ignored while another run is in progress. */
    fun startExport()

    fun startImport()

    @Composable
    fun Dialogs()
}

@Composable
fun rememberAppSettingsBackup(): AppSettingsBackup? {
    val koin = getKoin()
    return remember(koin) { koin.getOrNull<AppSettingsBackup>() }
}

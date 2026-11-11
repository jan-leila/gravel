package com.anopticlabs.gravel.backup

import com.anopticlabs.gravel.notebook.pipeline.CaptureCoordinator
import com.anopticlabs.gravel.ui.AppSettingsBackup
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val appBackupModule = module {
    single {
        AppBackup(
            context = androidContext(),
            libPebble = get(),
            coreDatabase = get(),
            notebookBackup = get(),
            notebookGate = get(),
            routingStore = get(),
            trustStore = get(),
            notebookDrain = { get<CaptureCoordinator>().captureQueued() },
        )
    }
    single<AppSettingsBackup> { AndroidAppSettingsBackup(androidContext(), get()) }
}

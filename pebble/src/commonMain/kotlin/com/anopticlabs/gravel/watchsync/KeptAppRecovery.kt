package com.anopticlabs.gravel.watchsync

import co.touchlab.kermit.Logger
import coredevices.pebble.services.AppstoreService
import coredevices.pebble.services.RealPebbleWebServices
import coredevices.pebble.services.toLockerEntry
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.metadata.WatchType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * Adds to the phone's locker the store apps a kept watch turns out to hold. The phone cannot list
 * a watch's apps, so this learns them one at a time from what the watch reports running (its
 * watchface on connect, then each app the user opens) and looks each unknown one up by UUID in the
 * enabled appstore sources. Sideloaded apps are not in any store and stay unknown to the phone.
 */
class KeptAppRecovery(
    private val libPebble: LibPebble,
    private val keptWatches: KeptWatches,
    private val webServices: RealPebbleWebServices,
) : KoinComponent {
    private val logger = Logger.withTag("KeptAppRecovery")
    private val tried = mutableSetOf<Uuid>()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun init(scope: CoroutineScope) {
        scope.launch {
            combine(libPebble.watches, keptWatches.serials) { watches, kept ->
                watches.filterIsInstance<ConnectedPebbleDevice>().filter { it.serial in kept }
            }.flatMapLatest { kept ->
                if (kept.isEmpty()) {
                    emptyFlow()
                } else {
                    merge(*kept.map { watch -> watch.runningApp.filterNotNull().map { it to watch.watchInfo.platform.watchType } }.toTypedArray())
                }
            }.collect { (uuid, watchType) -> recover(uuid, watchType) }
        }
    }

    /** Runs on the one collector above, so [tried] needs no lock. */
    private suspend fun recover(uuid: Uuid, watchType: WatchType) {
        if (!tried.add(uuid)) return
        if (libPebble.getLockerApp(uuid).first() != null) return
        val (storeId, source) = webServices.searchUuidInSources(uuid).firstOrNull() ?: run {
            logger.d { "$uuid is in no enabled appstore source" }
            return
        }
        val service = get<AppstoreService> { parametersOf(source) }
        val app = service.fetchAppStoreApp(storeId, watchType)?.data?.firstOrNull() ?: return
        val entry = app.toLockerEntry(source.url, timelineToken = null) ?: return
        logger.i { "adding ${app.title} ($uuid), found on a kept watch, to the locker" }
        libPebble.addAppToLocker(entry)
    }
}

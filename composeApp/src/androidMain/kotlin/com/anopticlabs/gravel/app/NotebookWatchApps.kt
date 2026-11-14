package com.anopticlabs.gravel.app

import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.notebook.routers.WatchAppInfo
import com.anopticlabs.gravel.notebook.routers.WatchAppSend
import com.anopticlabs.gravel.notebook.routers.WatchAppTarget
import com.anopticlabs.gravel.notebook.routers.WatchApps
import com.anopticlabs.gravel.routers.WatchappDelivery
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.locker.AppType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlin.uuid.Uuid

/**
 * Gravel: the Notebook's text routing reads the watch's apps through this. [pebble] is resolved per call, as the
 * Notebook's graph is built before the watch library starts. A failure of the library reads as nothing known.
 */
class LibPebbleWatchApps(private val pebble: () -> LibPebble) : WatchApps {
    override suspend fun open(): String? = guarded(null) {
        pebble().watches.value.filterIsInstance<ConnectedPebbleDevice>().firstNotNullOfOrNull { it.runningApp.value }?.toString()
    }

    override suspend fun installed(): List<WatchAppInfo> = guarded(emptyList()) {
        pebble().getAllLockerBasicInfo().first()
            .filter { it.type == AppType.Watchapp }
            .map { WatchAppInfo(it.id.toString(), it.title) }
            .sortedBy { it.name.lowercase() }
    }

    // A declaration outlives its app's removal from the locker, so only installed apps are listed.
    override suspend fun targets(): List<WatchAppTarget> = guarded(emptyList()) {
        val declared = pebble().watchappRouters.value
        installed().flatMap { app ->
            declared[Uuid.parse(app.uuid)].orEmpty().map { WatchAppTarget(app, it.id, it.name, it.description, it.params) }
        }
    }

    override suspend fun send(app: String, router: String, payload: String): WatchAppSend = guarded(WatchAppSend.NOT_TAKEN) {
        when (pebble().routeToWatchapp(Uuid.parse(app), router, payload)) {
            WatchappDelivery.Delivered -> WatchAppSend.DELIVERED
            WatchappDelivery.NotInstalled -> WatchAppSend.GONE
            WatchappDelivery.NoWatch -> WatchAppSend.NO_WATCH
            WatchappDelivery.NotTaken -> WatchAppSend.NOT_TAKEN
        }
    }

    private suspend fun <T> guarded(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w { "the watch's apps could not be read: ${e::class.simpleName}" }
        fallback
    }

    private companion object {
        val logger = Logger.withTag("NotebookWatchApps")
    }
}

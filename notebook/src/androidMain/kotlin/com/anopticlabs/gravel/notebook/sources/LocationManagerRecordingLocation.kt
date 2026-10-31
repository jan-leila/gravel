package com.anopticlabs.gravel.notebook.sources

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.anopticlabs.gravel.notebook.capture.RecordingLocation
import com.anopticlabs.gravel.notebook.routers.RoutedLocation
import java.util.concurrent.Executor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The phone's location from the system's location manager: a fresh fix from the best enabled
 * provider within [timeoutMillis], else the newest last-known fix from any provider that is under
 * [MAX_LAST_KNOWN_AGE_MILLIS] old, so a stale fix never places a recording somewhere the phone
 * left long ago. Null without either location permission, or when nothing is known. A watch
 * recording is looked up while Gravel has no screen showing, which from Android 10 counts as
 * background use: with the foreground-only grant the system answers null to both lookups, so the
 * Recording Location setting asks for the background grant as well.
 */
class LocationManagerRecordingLocation(
    private val context: Context,
    private val timeoutMillis: Long = 5_000,
) : RecordingLocation {

    override suspend fun current(): RoutedLocation? {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val fresh = try {
            providers(manager).firstOrNull()?.let { provider -> withTimeoutOrNull(timeoutMillis) { currentFrom(manager, provider) } }
        } catch (e: SecurityException) {
            null
        }
        val location = fresh ?: lastKnown(manager) ?: return null
        return RoutedLocation(location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy else null)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    // The GPS provider needs the fine permission; the others answer to the coarse one too.
    private fun providers(manager: LocationManager): List<String> {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) add(LocationManager.GPS_PROVIDER)
        }
        return candidates.filter { manager.isProviderEnabled(it) }
    }

    private suspend fun currentFrom(manager: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        LocationManagerCompat.getCurrentLocation(manager, provider, signal, DIRECT) { location ->
            if (cont.isActive) cont.resume(location)
        }
    }

    private fun lastKnown(manager: LocationManager): Location? {
        val now = SystemClock.elapsedRealtimeNanos()
        return manager.allProviders
            .mapNotNull { provider ->
                try {
                    manager.getLastKnownLocation(provider)
                } catch (e: SecurityException) {
                    null
                } catch (e: IllegalArgumentException) {
                    null
                }
            }
            .filter { now - it.elapsedRealtimeNanos <= MAX_LAST_KNOWN_AGE_MILLIS * 1_000_000 }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    private companion object {
        const val MAX_LAST_KNOWN_AGE_MILLIS = 15 * 60 * 1000L
        val DIRECT = Executor { it.run() }
    }
}

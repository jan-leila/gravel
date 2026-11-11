package com.anopticlabs.gravel.watchsync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.eygraber.uri.toAndroidUri
import coredevices.pebble.RealPebbleDeepLinkHandler.Companion.NOTIFICATION_INTENT_URI_SHOW_WATCHES
import coredevices.util.R
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleIdentifier

/**
 * Posts one notification per watch waiting on the keep-or-replace question; tapping it opens the
 * app, whose dialog asks. The answer is never taken from the notification itself, so it carries
 * no action a broadcast could fake.
 */
class AndroidFirstSyncNotifier(private val appContext: AppContext) : FirstSyncNotifier {
    override fun show(request: FirstSyncRequest) {
        val context = appContext.context
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Watch setup", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Asks what to do with what a watch already holds when it connects"
            },
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.setData(NOTIFICATION_INTENT_URI_SHOW_WATCHES.toAndroidUri())
        val key = key(request.identifier)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Your watch is waiting")
            .setContentText("Keep what's on the watch, or replace it with this phone's? The watch won't sync until you choose.")
            .setStyle(NotificationCompat.BigTextStyle())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(context, key, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .build()
        context.getSystemService(NotificationManager::class.java).notify(key, notification)
    }

    override fun dismiss(identifier: PebbleIdentifier) {
        appContext.context.getSystemService(NotificationManager::class.java).cancel(key(identifier))
    }

    private fun key(identifier: PebbleIdentifier) = NOTIFICATION_KEY_BASE + (identifier.asString.hashCode() and 0x3ff)

    private companion object {
        const val CHANNEL_ID = "gravel_first_sync"
        const val NOTIFICATION_KEY_BASE = 74_000
    }
}

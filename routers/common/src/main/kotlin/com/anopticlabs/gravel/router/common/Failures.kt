package com.anopticlabs.gravel.router.common

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** Tells the user about a delivery the integration refused; the text names the reason, never the recording. */
object Failures {
    private const val CHANNEL = "router_failures"
    private const val ID = 1

    fun notifyRefused(context: Context, reason: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Deliveries not sent", NotificationManager.IMPORTANCE_DEFAULT))
        val appName = context.applicationInfo.loadLabel(context.packageManager)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$appName could not send a recording")
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(ID, notification)
        } catch (_: SecurityException) {
        }
    }
}

package com.anopticlabs.gravel.gitsource

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.eygraber.uri.toAndroidUri
import coredevices.pebble.RealPebbleDeepLinkHandler.Companion.NOTIFICATION_INTENT_URI_GIT_SOURCES
import coredevices.util.R

/** One notification per source, keyed by its UUID, opening the Git Sources screen. */
class AndroidGitSourceNotifier(private val context: Context) : GitSourceNotifier {
    override fun notifyUpdate(source: GitSourceEntity, needsApproval: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Watch App Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Updates of watchfaces and apps followed from git repositories"
            },
        )
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.setData(NOTIFICATION_INTENT_URI_GIT_SOURCES.toAndroidUri())
        val pending = PendingIntent.getActivity(context, source.notificationId(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val body = if (needsApproval) {
            "${source.availableTag} asks for more access. Review it before it installs"
        } else {
            "${source.availableTag} is available from ${source.owner}/${source.repo}"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Update for ${source.appTitle}")
            .setContentText(body)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_TAG, source.notificationId(), notification)
    }

    override fun cancel(source: GitSourceEntity) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_TAG, source.notificationId())
    }

    // The tag keeps these ids apart from the app's other notifications, which use untagged ids.
    private fun GitSourceEntity.notificationId() = uuid.hashCode()

    private companion object {
        const val CHANNEL_ID = "git_source_updates"
        const val NOTIFICATION_TAG = "git_source"
    }
}

package app.cloudsaver.util

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.cloudsaver.R

/**
 * Two channels only: "Working" (low importance, silent, visible only while
 * compressing) and "Warnings" (rare, real problems). Never spam.
 */
object Notifications {

    const val CH_WORKING = "working"
    const val CH_WARNINGS = "warnings"

    const val ID_WORKING = 10
    const val ID_WARN_AGED = 20
    const val ID_WARN_SAFETY = 21
    const val ID_WARN_SPACE = 22

    fun createChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val working = NotificationChannel(
            CH_WORKING,
            context.getString(R.string.channel_working),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        val warnings = NotificationChannel(
            CH_WARNINGS,
            context.getString(R.string.channel_warnings),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        nm.createNotificationChannel(working)
        nm.createNotificationChannel(warnings)
    }

    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun working(context: Context, text: String): Notification {
        val pi = contentIntent(context)
        return NotificationCompat.Builder(context, CH_WORKING)
            .setSmallIcon(R.drawable.ic_stat_cloud)
            .setContentTitle(context.getString(R.string.notif_working_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    /**
     * Posts a warning. [enabled] is the user's "Warnings" setting, passed in by
     * the caller because reading it here would mean blocking on DataStore.
     */
    fun warn(context: Context, id: Int, title: String, text: String, enabled: Boolean = true) {
        if (!enabled) return
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_WARNINGS)
            .setSmallIcon(R.drawable.ic_stat_cloud)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(context))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // Permission revoked between check and notify - fine, work continues.
        }
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

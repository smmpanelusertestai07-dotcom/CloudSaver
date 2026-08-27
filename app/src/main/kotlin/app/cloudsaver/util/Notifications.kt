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
import app.cloudsaver.data.prefs.Options
import app.cloudsaver.data.prefs.OptionsRepo

/**
 * Two channels. "Working" is silent, ongoing-only, and never survives the run
 * that posted it. "Alerts" is for the rare case where something the app cannot
 * fix needs a person - and even then, the same alert is posted at most once a
 * day, and one tap silences the lot for a week.
 *
 * Everything posted here is also written to the Activity log, so a swipe never
 * loses information.
 */
object Notifications {

    const val CH_WORKING = "working"
    const val CH_ALERTS = "alerts"

    /** Retired: the pre-3.0 warnings channel, removed so it stops appearing. */
    private const val CH_LEGACY_WARNINGS = "warnings"

    const val ID_WORKING = 10
    const val ID_WARN_AGED = 20
    const val ID_WARN_SAFETY = 21
    const val ID_WARN_SPACE = 22

    /** The same alert is worth saying once a day at most. */
    const val DEDUP_MS = 86_400_000L

    /** How long "Mute for 7 days" lasts. */
    const val MUTE_MS = 7 * 86_400_000L

    /** Extra carrying the screen an alert should open. */
    const val EXTRA_ROUTE = "app.cloudsaver.route"
    const val ACTION_MUTE = "app.cloudsaver.MUTE_ALERTS"

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
            description = context.getString(R.string.channel_working_desc)
        }
        val alerts = NotificationChannel(
            CH_ALERTS,
            context.getString(R.string.channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.channel_alerts_desc)
        }
        nm.createNotificationChannel(working)
        nm.createNotificationChannel(alerts)
        // An upgrade would otherwise leave the old channel in the system
        // settings list, where turning it off does nothing.
        runCatching { nm.deleteNotificationChannel(CH_LEGACY_WARNINGS) }
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
        val pi = contentIntent(context, null)
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
     * Takes down the ongoing "working" notification.
     *
     * WorkManager usually clears a foreground notification when the worker
     * finishes, but not on every path - a cancelled or crashed run can leave
     * the status-bar icon behind, and an icon that says the app is busy while
     * it is idle is a lie the user cannot dismiss. The worker calls this in a
     * finally block so the icon always goes.
     */
    fun clearWorking(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_WORKING) }
    }

    /**
     * When each kind of alert was last posted, read back from the stored
     * record. A line that has been damaged, or written by a build that stored
     * something else, is dropped rather than trusted.
     */
    fun lastAlertTimes(encoded: String): Map<String, Long> {
        if (encoded.isEmpty()) return emptyMap()
        val out = HashMap<String, Long>()
        for (line in encoded.split('\n')) {
            val at = line.substringBefore(' ', "").toLongOrNull() ?: continue
            val key = line.substringAfter(' ', "")
            if (key.isNotEmpty()) out[key] = at
        }
        return out
    }

    /**
     * The record on its way back to storage, with everything older than a day
     * left out: a day-old entry can no longer hold an alert back, and keeping
     * it would let the record grow for as long as the app is installed.
     */
    fun encodeAlertTimes(times: Map<String, Long>, now: Long): String = times.entries
        .filter { now - it.value < DEDUP_MS }
        .joinToString("\n") { "${it.value} ${it.key}" }

    /** A key as it is stored: one line, so the record stays readable back. */
    private fun alertKey(key: String): String = key.replace('\n', ' ')

    /**
     * Posts an alert, unless the user has muted alerts or this same alert was
     * already posted today.
     *
     * [dedupKey] identifies the alert rather than the notification slot, so a
     * cloud that is still not uploading tomorrow gets one reminder rather than
     * one an hour. [options] is passed in because reading DataStore here would
     * mean blocking a worker thread on it.
     *
     * The record is kept per kind of alert. One key and one time could only
     * ever remember the last alert posted, so on a day with two different
     * problems each one wiped the other's record and both were free to post
     * again immediately - the user got the same two warnings over and over
     * while the code plainly said once a day. Each kind now carries its own
     * time, and a quiet day removes itself from the record.
     */
    suspend fun alert(
        context: Context,
        id: Int,
        title: String,
        text: String,
        options: Options,
        dedupKey: String = title,
        route: String? = null,
        now: Long = System.currentTimeMillis()
    ) {
        if (!options.warningsNotif) return
        if (now < options.alertsMutedUntil) return
        val key = alertKey(dedupKey)
        val posted = lastAlertTimes(options.lastAlerts)
        val lastAt = posted[key]
        if (lastAt != null && now - lastAt < DEDUP_MS) return
        // Recording the attempt before knowing it can be shown would spend the
        // day's one slot on a notification nobody saw, so the permission check
        // comes first.
        if (!canPost(context)) return
        val repo = OptionsRepo.get(context)
        repo.setString(
            OptionsRepo.K.LAST_ALERTS,
            encodeAlertTimes(posted + (key to now), now)
        )
        post(context, id, title, text, route)
    }

    private fun post(context: Context, id: Int, title: String, text: String, route: String?) {
        if (!canPost(context)) return
        val mute = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, AlertActions::class.java).setAction(ACTION_MUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_cloud)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(context, route))
            .addAction(0, context.getString(R.string.notif_mute_7_days), mute)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            // Permission revoked between check and notify - fine, work continues.
        }
    }

    private fun contentIntent(context: Context, route: String?): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        route?.let { intent.putExtra(EXTRA_ROUTE, it) }
        return PendingIntent.getActivity(
            // A distinct request code per route, or the system would hand back
            // the first intent it cached and every alert would open Home.
            context, route?.hashCode() ?: 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

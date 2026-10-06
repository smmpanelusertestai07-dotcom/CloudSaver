package app.entesaver.util

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
import app.entesaver.R
import app.entesaver.data.prefs.Options
import app.entesaver.data.prefs.OptionsRepo

/**
 * Two channels. "Working" is silent, ongoing-only, and never survives the run
 * that posted it. "Alerts" is for the rare case where something the app cannot
 * fix needs a person - and even then the same alert is posted at most once a
 * day, backs off when it is ignored, and one tap silences the lot for a week.
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
    const val ID_WARN_STALLED = 23
    /** Its own slot: sharing one with the safety pause, each replaced the other. */
    const val ID_WARN_CLOUD = 24
    /** An old folder ran empty: Ente can stop backing it up. */
    const val ID_NOTE_FOLDER = 25

    /** Every slot an alert can use - what "Mute" takes down, and nothing else. */
    val ALERT_IDS = listOf(
        ID_WARN_AGED, ID_WARN_SAFETY, ID_WARN_SPACE, ID_WARN_STALLED, ID_WARN_CLOUD, ID_NOTE_FOLDER
    )

    /** The same alert is worth saying once a day at most. */
    const val DEDUP_MS = 86_400_000L

    /** The longest an ignored alert waits between reminders. */
    const val MONTH_MS = 30 * 86_400_000L

    /**
     * Quiet for this long - longer than the longest wait, so a problem that
     * is still there keeps its monthly pace rather than starting over -
     * and an alert begins again from its first reminder.
     */
    const val RESET_MS = 2 * MONTH_MS

    /** How long "Mute for 7 days" lasts. */
    const val MUTE_MS = 7 * 86_400_000L

    /** Extra carrying the screen an alert should open. */
    const val EXTRA_ROUTE = "app.entesaver.route"
    const val ACTION_MUTE = "app.entesaver.MUTE_ALERTS"

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
            .setSmallIcon(R.drawable.ic_stat_saver)
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

    /** When one kind of alert was last posted, and how many times in a row. */
    data class Posted(val at: Long, val times: Int)

    /**
     * How long an ignored alert waits before it is posted again.
     *
     * The same problem used to come back every day for as long as it lasted -
     * a cloud app that stopped uploading in March was a notification a day
     * in June - which is how an app gets its notifications switched off, the
     * one alert that matters going with them. Ignored, it waits longer each
     * time: a day, three days, a week, and from then on once a month. Fixed
     * and quiet for two months, it starts again from a day.
     */
    fun waitAfter(times: Int): Long = when {
        times <= 1 -> DEDUP_MS
        times == 2 -> 3 * DEDUP_MS
        times == 3 -> 7 * DEDUP_MS
        else -> MONTH_MS
    }

    /**
     * Each kind of alert's record, read back from storage. A line that has
     * been damaged, or written by a build that stored something else, is
     * dropped rather than trusted; a line from before the count was kept
     * reads as posted once.
     */
    fun lastAlertTimes(encoded: String): Map<String, Posted> {
        if (encoded.isEmpty()) return emptyMap()
        val out = HashMap<String, Posted>()
        for (line in encoded.split('\n')) {
            val stamp = line.substringBefore(' ', "")
            val at = stamp.substringBefore('x').toLongOrNull() ?: continue
            val times = stamp.substringAfter('x', "1").toIntOrNull()?.coerceAtLeast(1) ?: 1
            val key = line.substringAfter(' ', "")
            if (key.isNotEmpty()) out[key] = Posted(at, times)
        }
        return out
    }

    /**
     * The record on its way back to storage, with every entry quiet for two
     * months left out: it can no longer hold an alert back, and keeping it
     * would let the record grow for as long as the app is installed.
     */
    fun encodeAlertTimes(times: Map<String, Posted>, now: Long): String = times.entries
        .filter { now - it.value.at < RESET_MS }
        .joinToString("\n") { "${it.value.at}x${it.value.times} ${it.key}" }

    /** Whether an alert with this record may be posted [now]. */
    fun due(posted: Posted?, now: Long): Boolean =
        posted == null || now - posted.at >= waitAfter(posted.times)

    /** A key as it is stored: one line, so the record stays readable back. */
    private fun alertKey(key: String): String = key.replace('\n', ' ')

    /**
     * Posts an alert, unless the user has muted alerts or this same alert is
     * still inside its wait ([waitAfter]). True when it was actually shown,
     * so a caller counting reminders never counts one nobody saw.
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
     * time and count, and two quiet months remove it from the record.
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
    ): Boolean {
        if (!options.warningsNotif) return false
        if (now < options.alertsMutedUntil) return false
        val key = alertKey(dedupKey)
        val posted = lastAlertTimes(options.lastAlerts)
        val last = posted[key]
        if (!due(last, now)) return false
        // Recorded only once it has been shown: a reminder nobody saw must
        // not push the next one further out.
        if (!canPost(context)) return false
        if (!post(context, id, title, text, route)) return false
        val times = if (last == null || now - last.at >= RESET_MS) 1 else last.times + 1
        OptionsRepo.get(context).setString(
            OptionsRepo.K.LAST_ALERTS,
            encodeAlertTimes(posted + (key to Posted(now, times)), now)
        )
        return true
    }

    /** Takes down whatever alerts are showing, and only those. */
    fun clearAlerts(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        for (id in ALERT_IDS) runCatching { manager.cancel(id) }
    }

    private fun post(context: Context, id: Int, title: String, text: String, route: String?): Boolean {
        if (!canPost(context)) return false
        val mute = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, AlertActions::class.java).setAction(ACTION_MUTE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_saver)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent(context, route))
            .addAction(0, context.getString(R.string.notif_mute_7_days), mute)
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(id, n)
            true
        } catch (e: SecurityException) {
            // Permission revoked between check and notify - fine, work continues.
            false
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

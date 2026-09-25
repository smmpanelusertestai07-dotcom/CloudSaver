package com.pocketide.limiter

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pocketide.R
import com.pocketide.core.Channels

/** The engine's notifications: the ongoing "Computer running" one and the safe-stop notice. */
internal object EngineNotices {
    const val RUNNING_ID = 4101
    private const val STOPPED_ID = 4102

    data class Line(val name: String, val working: Boolean, val starting: Boolean)

    fun running(context: Context, lines: List<Line>): Notification {
        val text = if (lines.isEmpty()) {
            "Starting the computer…"
        } else {
            lines.joinToString(" · ") { line ->
                "${line.name}: ${when {
                    line.starting -> "starting"
                    line.working -> "working"
                    else -> "ready"
                }}"
            }
        }
        val stopAll = PendingIntent.getService(
            context, 0,
            Intent(context, EngineService::class.java).setAction(EngineService.ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, Channels.ENGINE)
            .setSmallIcon(R.drawable.ic_stat_pocketide)
            .setContentTitle("Computer running")
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop everything", stopAll)
            .build()
    }

    /** Says what stopped and why. Without the notification permission it stays on the Home banner only. */
    fun stopped(context: Context, stop: RoomStop) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val notification = NotificationCompat.Builder(context, Channels.ENGINE)
            .setSmallIcon(R.drawable.ic_stat_pocketide)
            .setContentTitle("Agents stopped safely")
            .setContentText(stop.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(stop.message))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(STOPPED_ID, notification)
        } catch (_: SecurityException) {
            // The permission was taken away between the check and the post.
        }
    }

    /** The name an agent is known by when the catalog does not have it yet. */
    fun defaultName(agentId: String): String = when (agentId) {
        "claude" -> "Claude"
        "codex" -> "Codex"
        "antigravity" -> "Antigravity"
        else -> agentId.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private fun openApp(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}

package com.pocketide.ui.web

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.pocketide.R
import com.pocketide.core.Channels
import com.pocketide.core.NotificationIds

/**
 * Web addresses a program inside Linux asked the phone to open (a sign-in page, most often).
 * With PocketIDE in front the page opens at once in Chrome; otherwise Android does not let an app
 * open a page by itself, so a notification offers it, one tap away.
 */
object Links {
    fun open(context: Context, url: String) {
        if (!WebPolicy.isWebLink(url)) return
        ContextCompat.getMainExecutor(context).execute {
            val inFront = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (inFront) Browser.open(context, url) else offer(context, url)
        }
    }

    private fun offer(context: Context, url: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context,
            url.hashCode(),
            Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notice = NotificationCompat.Builder(context, Channels.LINKS)
            .setSmallIcon(R.drawable.ic_stat_pocketide)
            .setContentTitle(context.getString(R.string.link_title))
            .setContentText(WebPolicy.hostOf(url).orEmpty())
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        NotificationManagerCompat.from(context).notify(NotificationIds.LINK, notice)
    }
}

package com.pocketide

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.ProcessLifecycleOwner
import com.pocketide.core.Channels
import com.pocketide.ui.lock.AppLock

/** Holds the app's single [AppGraph]. */
class PocketApp : Application() {
    lateinit var graph: AppGraph
        private set

    lateinit var appLock: AppLock
        private set

    override fun onCreate() {
        super.onCreate()
        // The screen that says an error stopped the app runs alone in its own process.
        if (StopNote.isNoteProcess()) return
        // Debug builds (the tests) keep Android's own handling, which reports the error to the test.
        if (!BuildConfig.DEBUG) StopNote.install(this)
        graph = AppGraph(this)
        appLock = AppLock(graph.clock)
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLock)
        registerActivityLifecycleCallbacks(graph.foreground)
        createChannels()
        AppStartup.onCreate(graph)
    }

    /** The one notification channel: the ongoing "Connected to Cloud Shell" notice. */
    private fun createChannels() {
        val channel = NotificationChannel(Channels.CONNECTION, getString(R.string.connection_channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.connection_channel_description)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}

/** Reaches the graph from anything with a context. */
val android.content.Context.graph: AppGraph
    get() = (applicationContext as PocketApp).graph

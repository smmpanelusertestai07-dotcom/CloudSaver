package com.pocketide

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.pocketide.ui.lock.AppLock

/** Holds the app's single [AppGraph]. */
class PocketApp : Application() {
    lateinit var graph: AppGraph
        private set

    lateinit var appLock: AppLock
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        appLock = AppLock(graph.clock)
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLock)
        AppStartup.onCreate(graph)
    }
}

/** Reaches the graph from anything with a context. */
val android.content.Context.graph: AppGraph
    get() = (applicationContext as PocketApp).graph

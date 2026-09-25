package com.pocketide

import android.app.Application

/** Holds the app's single [AppGraph]. */
class PocketApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        AppStartup.onCreate(graph)
    }
}

/** Reaches the graph from anything with a context. */
val android.content.Context.graph: AppGraph
    get() = (applicationContext as PocketApp).graph

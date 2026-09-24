package com.pocketide.limiter

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** The special-use foreground service that keeps the computer alive while agents run. */
class EngineService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}

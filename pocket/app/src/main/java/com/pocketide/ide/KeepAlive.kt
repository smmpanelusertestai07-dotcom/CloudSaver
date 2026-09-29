package com.pocketide.ide

import android.content.Context

/**
 * Who needs the app kept running right now: set-up, and code-server while it is on. The foreground
 * service ([ComputerService]) runs while anyone does, and ends with the last one.
 */
class KeepAlive(private val context: Context) {
    private val holders = mutableSetOf<String>()

    @Synchronized
    fun hold(who: String) {
        if (holders.add(who) && holders.size == 1) ComputerService.keep(context)
    }

    @Synchronized
    fun release(who: String) {
        if (holders.remove(who) && holders.isEmpty()) ComputerService.release(context)
    }

    companion object {
        const val SET_UP = "set-up"
        const val IDE = "code-server"
    }
}

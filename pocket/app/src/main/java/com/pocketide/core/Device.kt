package com.pocketide.core

import android.content.Context
import android.os.Build
import java.util.UUID

/** This install's identity for the vault lease and conflict copies. Not a hardware id. */
object Device {
    private const val PREFS = "pocketide.device"
    private const val KEY_ID = "id"

    fun id(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_ID, id).apply()
        return id
    }

    fun name(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        return if (Build.MODEL.startsWith(maker, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
    }
}

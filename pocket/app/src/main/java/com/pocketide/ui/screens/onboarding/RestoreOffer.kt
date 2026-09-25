package com.pocketide.ui.screens.onboarding

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a returning owner's chats in Drive still wait for "Bring my chats back" on this phone.
 * Set when the Drive step rebuilds an existing key; cleared once a restore starts, or when the
 * key is new or Drive holds nothing. Until then the Computer step, and after set-up Home, offer
 * the restore plan. This phone only.
 */
class RestoreOffer internal constructor(private val flag: Flag) {
    internal interface Flag {
        fun read(): Boolean
        fun write(value: Boolean)
    }

    private val state = MutableStateFlow(flag.read())
    val pending: StateFlow<Boolean> = state.asStateFlow()

    /** The Drive step has the key: [restored] from Drive and GitHub (chats may wait there), or made new. */
    fun keyReady(restored: Boolean) = set(restored)

    /** "Bring my chats back" was tapped: the sync job carries the restore on from here. */
    fun started() = set(false)

    /** The restore could not start: it stays on offer. */
    fun failed() = set(true)

    /** Drive holds nothing to bring back. */
    fun nothingInDrive() = set(false)

    private fun set(value: Boolean) {
        if (state.value == value) return
        state.value = value
        flag.write(value)
    }

    private class PrefsFlag(private val prefs: SharedPreferences) : Flag {
        override fun read() = prefs.getBoolean(KEY, false)
        override fun write(value: Boolean) = prefs.edit { putBoolean(KEY, value) }
    }

    companion object {
        private const val PREFS = "ui-restore"
        private const val KEY = "pending"

        @Volatile
        private var shared: RestoreOffer? = null

        /** The one offer of this process, so every screen sees the same answer. */
        fun of(context: Context): RestoreOffer = shared ?: synchronized(this) {
            shared ?: RestoreOffer(PrefsFlag(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)))
                .also { shared = it }
        }
    }
}

@Composable
fun rememberRestoreOffer(): RestoreOffer {
    val context = LocalContext.current
    return remember(context) { RestoreOffer.of(context) }
}

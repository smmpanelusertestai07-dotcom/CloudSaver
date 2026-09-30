package com.pocketide

import android.content.Context
import com.pocketide.core.Clock
import com.pocketide.core.LogBackgroundFailure
import com.pocketide.core.SettingsStore
import com.pocketide.core.createSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's parts, made on first use. PocketIDE keeps only its settings on this phone (with the
 * Google account Cloud Shell opens with); the computer is Google Cloud Shell, where VS Code and the
 * agents run. PocketIDE has no server of its own.
 */
class AppGraph(val context: Context) {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + LogBackgroundFailure)
    val clock: Clock = Clock.SYSTEM

    val settings: SettingsStore by lazy { createSettingsStore(this) }
}

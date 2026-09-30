package com.pocketide.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The owner's choices, kept on this phone only. A newer version's extra fields are ignored by an
 * older one, and fields an older version wrote that this one no longer knows are dropped.
 */
@Serializable
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Colours from the wallpaper (Android 12 and newer) instead of PocketIDE's violet. */
    val dynamicColor: Boolean = false,
    /** The version of the terms the owner accepted; 0 = not yet. */
    val termsAccepted: Int = 0,
    val onboardingDone: Boolean = false,
    /** Ask for the phone's screen lock when the app opens, and cover the app in Recents. */
    val appLock: Boolean = false,
    /** The Google account Cloud Shell opens with, picked in Android's own account chooser; empty = not yet. */
    val cloudAccount: String = "",
    /** When the owner said Cloud Shell's set-up had finished (UTC epoch ms); 0 = not yet. */
    val cloudSetUpAt: Long = 0,
    /** When PocketIDE last opened Cloud Shell (UTC epoch ms): Google deletes its home after 120 days unused. */
    val cloudOpenedAt: Long = 0,
    /** The commit of the set-up script the owner last ran; a newer app version may bring a newer one. */
    val cloudScript: String = "",
    /** The Google account gcloud signed in with on this phone, for PocketIDE's connection; empty = not yet. */
    val gcloudAccount: String = "",
    /**
     * The secret in every address of PocketIDE's private door to Cloud Shell's ports (32 hex
     * characters), and the phone port that door listens on. Kept, so the pages' cache stays valid.
     */
    val proxyKey: String = "",
    val proxyPort: Int = 0,
    /** The owner chose to open the agents in Chrome only, without PocketIDE's connection on this phone. */
    val chromeOnly: Boolean = false,
)

/** The settings after "Delete everything": every choice back to its default. */
fun Settings.afterDeleteEverything(): Settings = Settings()

/** Settings on this phone, observed by every screen and module. */
interface SettingsStore {
    val settings: StateFlow<Settings>
    fun update(change: (Settings) -> Settings)
}

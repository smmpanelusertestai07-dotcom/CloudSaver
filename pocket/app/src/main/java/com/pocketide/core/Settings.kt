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
    /** Show Esc, Tab, Ctrl, arrows and Enter above the keyboard on the agent screen. */
    val keyBar: Boolean = true,
    /** The project the agents work in: a folder in ~/projects; empty = the projects folder itself. */
    val project: String = "",
    /** Automatic updates may use mobile data, not only Wi-Fi. */
    val updatesOnMobileData: Boolean = false,
    /** When the automatic updates last finished (UTC epoch ms); 0 = never. */
    val lastUpdate: Long = 0,
)

/** The settings after "Delete everything": every choice back to its default. */
fun Settings.afterDeleteEverything(): Settings = Settings()

/** Settings on this phone, observed by every screen and module. */
interface SettingsStore {
    val settings: StateFlow<Settings>
    fun update(change: (Settings) -> Settings)
}

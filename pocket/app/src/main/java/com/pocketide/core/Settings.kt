package com.pocketide.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The owner's choices, kept on this phone only. Nothing here is the owner's data: code and chats
 * live in their GitHub account, so none of it needs a backup.
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
    /** The cloud computer the Computer tab opens: its codespace name. Empty = none chosen yet. */
    val lastComputer: String = "",
    val newComputer: NewComputerChoices = NewComputerChoices(),
    /** Keep the connection open while PocketIDE is in the background, with a notification and a Stop button. */
    val stayConnected: Boolean = true,
    /** Show Esc, Tab, arrows and Send above the keyboard on the computer screen. */
    val keyBar: Boolean = true,
    /**
     * The owner's own GitHub App, entered in the app when the build carries none (or a different
     * one). Both values are public. Empty = use the build's.
     */
    val gitHubAppClientId: String = "",
    val gitHubAppSlug: String = "",
)

/** What a new cloud computer gets. GitHub fixes the idle time and auto-delete time when it creates one. */
@Serializable
data class NewComputerChoices(
    /** GitHub's machine type name; empty = the smallest (2 cores), which uses the fewest free hours. */
    val machine: String = "",
    /** Stops by itself after this many minutes without activity (GitHub allows 5 to 240). */
    val idleMinutes: Int = 30,
    /** Deleted by GitHub after this many days stopped and unused (GitHub allows 0 to 30). */
    val keepDays: Int = 30,
)

/**
 * The settings after "Delete PocketIDE's data from this phone": every choice back to its default.
 * This copy's GitHub App stays, because without it an owner whose build carries none could not
 * sign in again.
 */
fun Settings.afterDeleteEverything(): Settings = Settings(gitHubAppClientId = gitHubAppClientId, gitHubAppSlug = gitHubAppSlug)

/** Settings on this phone, observed by every screen and module. */
interface SettingsStore {
    val settings: StateFlow<Settings>
    fun update(change: (Settings) -> Settings)
}

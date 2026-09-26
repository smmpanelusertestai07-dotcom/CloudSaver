package com.pocketide.core

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * The owner's choices. Defaults are the plan's defaults. Values marked "synced" travel in the
 * encrypted vault index so a new phone gets them back.
 */
@Serializable
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** Metered data per day in MB; 0 = never use mobile data for PocketIDE's own transfers. */
    val mobileDailyLimitMb: Int = 200,
    /** Big downloads (set-up, engine and agent updates, Chromium, large clones) wait for Wi-Fi. */
    val wifiOnlyBigDownloads: Boolean = true,
    /** Chat videos upload on mobile data too (off: they wait for Wi-Fi). */
    val videosOnMobileData: Boolean = false,
    /** PocketIDE's phone storage limit in GB (always leaves ≥ 2 GB free on the phone). Synced. */
    val phoneLimitGb: Int = 8,
    /** PocketIDE's share of Drive in GB. Synced. */
    val driveLimitGb: Int = 2,
    /** 0 = Auto (from RAM). */
    val maxAgents: Int = 0,
    /** Idle agents' rooms close after this many minutes without work (sessions kept); 0 = never. Choices: Off, 15, 30, 60. */
    val idleSleepMinutes: Int = 30,
    val appLock: Boolean = true,
    /** Hide every agent except the built-in three. Synced. */
    val onlyOfficialAgents: Boolean = false,
    /** Keep chats in Drive: 0 = until I delete, else months after the last message. Synced. */
    val keepChatsMonths: Int = 0,
    /** Phone copies of chats: days after Drive has them; -1 = keep all. Synced. */
    val phoneChatDays: Int = 30,
    /** Media copies on the phone: days; -1 = keep all. Synced. */
    val phoneMediaDays: Int = 30,
    /** Unused project caches: 30 or 14 days. Synced. */
    val cacheDays: Int = 30,
    /** Unused computer removed after this many days without agent activity; -1 = never. Synced. */
    val computerUnusedDays: Int = 90,
    /** Automatic rule when Drive share is full: chats older than 12 months → Recently deleted. */
    val autoTrimOldChats: Boolean = true,
    /** The optional extra password wrapping Half G is set. */
    val extraPassword: Boolean = false,
    /** The owner did the one-time OEM battery step (Realme and others). */
    val oemStepDone: Boolean = false,
    val onboardingDone: Boolean = false,
    /**
     * The owner's own GitHub App, entered in the app when the build carries none (or a different
     * one). Both values are public; this phone only, never synced. Empty = use the build's.
     */
    val gitHubAppClientId: String = "",
    val gitHubAppSlug: String = "",
    val privacyChecklistDone: Boolean = false,
    /**
     * Claude Code connects each session to Remote Control, so Anthropic also keeps its chat in the
     * owner's Claude account (the Claude app, claude.ai/code). This phone only; applied at the room's next start.
     */
    val claudeChatsInAccount: Boolean = true,
)

/**
 * The settings after "Delete everything": every choice back to its default, so none reaches the
 * next vault and set-up starts over. What configures the app on this phone rather than holding the
 * owner's data stays: this copy's GitHub App (without it, an owner whose build carries none could
 * not sign in again) and the battery step done in Android's own settings, which Android keeps.
 */
fun Settings.afterDeleteEverything(): Settings =
    Settings(gitHubAppClientId = gitHubAppClientId, gitHubAppSlug = gitHubAppSlug, oemStepDone = oemStepDone)

/** Settings on this phone, observed by every screen and module. */
interface SettingsStore {
    val settings: StateFlow<Settings>
    fun update(change: (Settings) -> Settings)
}

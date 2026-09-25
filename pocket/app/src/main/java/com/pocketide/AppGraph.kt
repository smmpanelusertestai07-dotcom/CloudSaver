package com.pocketide

import android.content.Context
import com.pocketide.agents.AgentCatalog
import com.pocketide.agents.createAgentCatalog
import com.pocketide.bridge.PhoneBridge
import com.pocketide.bridge.PortBridge
import com.pocketide.bridge.createPhoneBridge
import com.pocketide.bridge.createPortBridge
import com.pocketide.builds.Builds
import com.pocketide.builds.createBuilds
import com.pocketide.core.AppDirs
import com.pocketide.core.Clock
import com.pocketide.core.KeystoreBox
import com.pocketide.core.SecureStore
import com.pocketide.core.SettingsStore
import com.pocketide.core.createSettingsStore
import com.pocketide.git.GitGate
import com.pocketide.git.createGitGate
import com.pocketide.github.GitHubApi
import com.pocketide.github.GitHubAuth
import com.pocketide.github.createGitHubApi
import com.pocketide.github.createGitHubAuth
import com.pocketide.google.DriveAuth
import com.pocketide.google.DriveStore
import com.pocketide.google.createDriveAuth
import com.pocketide.google.createDriveStore
import com.pocketide.limiter.Limiter
import com.pocketide.limiter.PhoneMonitor
import com.pocketide.limiter.createLimiter
import com.pocketide.limiter.createPhoneMonitor
import com.pocketide.linux.Computer
import com.pocketide.linux.createComputer
import com.pocketide.lock.AccessGuard
import com.pocketide.lock.AppLock
import com.pocketide.lock.createAccessGuard
import com.pocketide.lock.createAppLock
import com.pocketide.media.MediaLibrary
import com.pocketide.media.createMediaLibrary
import com.pocketide.projects.Projects
import com.pocketide.projects.createProjects
import com.pocketide.rooms.Rooms
import com.pocketide.rooms.createRooms
import com.pocketide.schedule.Schedules
import com.pocketide.schedule.createSchedules
import com.pocketide.secrets.ProjectSecrets
import com.pocketide.secrets.createProjectSecrets
import com.pocketide.sessions.Sessions
import com.pocketide.sessions.createSessions
import com.pocketide.sync.DataBudget
import com.pocketide.sync.SyncEngine
import com.pocketide.sync.createDataBudget
import com.pocketide.sync.createSyncEngine
import com.pocketide.update.AppUpdater
import com.pocketide.update.createAppUpdater
import com.pocketide.usage.UsageReporter
import com.pocketide.usage.createUsageReporter
import com.pocketide.vault.VaultKeys
import com.pocketide.vault.createVaultKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's object graph, built once in [PocketApp]. Plain constructor wiring: every module is
 * created lazily by its own `create…` function in its own package, so each module owns its
 * wiring and nothing is created before it is needed.
 */
class AppGraph(val context: Context) {
    val dirs: AppDirs = AppDirs.from(context)
    val clock: Clock = Clock.SYSTEM
    /** Lives as long as the process; work that must survive a kill goes through WorkManager. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Sealed secrets (tokens, the vault key, Secrets), opened only by this install. */
    val secureStore: SecureStore by lazy { SecureStore(java.io.File(context.filesDir, "secure"), KeystoreBox()) }
    val settings: SettingsStore by lazy { createSettingsStore(this) }
    val phone: PhoneMonitor by lazy { createPhoneMonitor(this) }
    val limiter: Limiter by lazy { createLimiter(this) }
    val computer: Computer by lazy { createComputer(this) }
    val portBridge: PortBridge by lazy { createPortBridge(this) }
    val phoneBridge: PhoneBridge by lazy { createPhoneBridge(this) }
    val gitHubAuth: GitHubAuth by lazy { createGitHubAuth(this) }
    val gitHub: GitHubApi by lazy { createGitHubApi(this) }
    val driveAuth: DriveAuth by lazy { createDriveAuth(this) }
    val drive: DriveStore by lazy { createDriveStore(this) }
    val vault: VaultKeys by lazy { createVaultKeys(this) }
    val git: GitGate by lazy { createGitGate(this) }
    val projects: Projects by lazy { createProjects(this) }
    val sessions: Sessions by lazy { createSessions(this) }
    val agents: AgentCatalog by lazy { createAgentCatalog(this) }
    val rooms: Rooms by lazy { createRooms(this) }
    val dataBudget: DataBudget by lazy { createDataBudget(this) }
    val sync: SyncEngine by lazy { createSyncEngine(this) }
    val media: MediaLibrary by lazy { createMediaLibrary(this) }
    val builds: Builds by lazy { createBuilds(this) }
    val usage: UsageReporter by lazy { createUsageReporter(this) }
    val secrets: ProjectSecrets by lazy { createProjectSecrets(this) }
    val schedules: Schedules by lazy { createSchedules(this) }
    val access: AccessGuard by lazy { createAccessGuard(this) }
    val appLock: AppLock by lazy { createAppLock(this) }
    val updater: AppUpdater by lazy { createAppUpdater(this) }
}

package com.pocketide

import android.content.Context
import com.pocketide.agents.AgentStore
import com.pocketide.agents.OpenVsx
import com.pocketide.agents.VerifiedDownload
import com.pocketide.core.AppFolders
import com.pocketide.core.Clock
import com.pocketide.core.Http
import com.pocketide.core.KeyStore
import com.pocketide.core.KeystoreBox
import com.pocketide.core.SecureStore
import com.pocketide.core.SettingsStore
import com.pocketide.core.createSettingsStore
import com.pocketide.ide.Ide
import com.pocketide.ide.KeepAlive
import com.pocketide.ide.LinkOpener
import com.pocketide.ide.Projects
import com.pocketide.ide.Updater
import com.pocketide.linux.Computer
import com.pocketide.linux.ComputerState
import com.pocketide.linux.LinuxDirs
import com.pocketide.linux.ProotComputer
import com.pocketide.ui.web.AgentPage
import com.pocketide.ui.web.Links
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's parts, made on first use. Everything is on this phone, in the app's private storage:
 * the computer (Ubuntu), the owner's projects, the agents and their sign-ins and chats, the keys
 * and the settings. PocketIDE has no server of its own.
 */
class AppGraph(val context: Context) {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock: Clock = Clock.SYSTEM

    /** Completed once the start-up housekeeping (deleting what older versions left) has finished. */
    val startupDone = CompletableDeferred<Unit>()

    val secureStore: SecureStore by lazy { SecureStore(AppFolders.of(context).secure, KeystoreBox()) }
    val settings: SettingsStore by lazy { createSettingsStore(this) }
    val keys: KeyStore by lazy { KeyStore(secureStore) }

    val dirs: LinuxDirs by lazy { LinuxDirs.of(context) }
    val computer: Computer by lazy { ProotComputer(context, dirs, clock, beforeFirstWrite = { startupDone.await() }) }

    val agents: AgentStore by lazy {
        AgentStore(computer, dirs, OpenVsx(Http.client), VerifiedDownload(Http.downloads))
    }

    val ide: Ide by lazy {
        Ide(
            context = context,
            computer = computer,
            dirs = dirs,
            keys = { keys.environment() },
            agents = { agents.screens() },
            keepAlive = { on ->
                if (on) {
                    keepAlive.hold(KeepAlive.IDE)
                    links.start()
                } else {
                    links.stop()
                    keepAlive.release(KeepAlive.IDE)
                }
            },
        )
    }

    /** Opens the web addresses programs inside Linux ask for (sign-in pages). */
    val links: LinkOpener by lazy { LinkOpener(dirs.openRequests) { url -> Links.open(context, url) } }

    val updater: Updater by lazy { Updater(this) }

    val projects: Projects by lazy { Projects(dirs) }

    /** Keeps the app running (the foreground service) while set-up or code-server needs it. */
    val keepAlive: KeepAlive by lazy { KeepAlive(context) }

    private val settingUp = AtomicBoolean(false)
    private var setUpJob: Job? = null

    /**
     * The one tap of set-up: the computer (Ubuntu, its tools, code-server), then the three official
     * agents. It runs in the app's own scope with the app kept running, so leaving the screen or
     * the app does not stop it half way; tapping again while it runs does nothing, and after a
     * stop it continues where it stopped.
     */
    fun setUp() {
        if (!settingUp.compareAndSet(false, true)) return
        keepAlive.hold(KeepAlive.SET_UP)
        setUpJob = scope.launch {
            try {
                computer.install()
                if (computer.state.value == ComputerState.Ready) runCatching { agents.installOfficial() }
            } finally {
                settingUp.set(false)
                keepAlive.release(KeepAlive.SET_UP)
            }
        }
    }

    /** The notification's Stop: set-up (it continues next time from where it stopped) and code-server. */
    suspend fun stopEverything() {
        setUpJob?.cancel()
        ide.stop()
    }

    /** The agent screen's page, kept while the process lives. Main thread only. */
    val page: AgentPage by lazy { AgentPage(context) }
}

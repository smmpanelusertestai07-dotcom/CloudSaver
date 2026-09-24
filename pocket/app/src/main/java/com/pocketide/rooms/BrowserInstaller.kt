package com.pocketide.rooms

import com.pocketide.linux.LinuxCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The `install_browser` tool: installs the agents' test browser once, for every room, in the
 * background. A call starts it when the phone's rules allow a big download (Wi-Fi by default) and
 * heavy work, and later calls report progress.
 */
internal class BrowserInstaller(
    private val env: RoomsEnv,
    private val configurator: RoomConfigurator,
    private val afterInstall: suspend () -> Unit,
) {
    private sealed interface Progress {
        data object Idle : Progress
        data class Running(val step: String, val since: Long) : Progress
        data class Failed(val why: String) : Progress
    }

    @Volatile
    private var progress: Progress = Progress.Idle
    private val lock = Mutex()

    suspend fun request(agentId: String): String = lock.withLock {
        if (withContext(Dispatchers.IO) { configurator.browserInstalled() }) return INSTALLED
        (progress as? Progress.Running)?.let { running ->
            val minutes = (env.now() - running.since) / 60_000
            return "Installing the test browser: ${running.step} (started $minutes min ago). Call install_browser again in a few minutes."
        }
        val heavy = env.canStartHeavyWork("installing the test browser")
        if (!heavy.allowed) return "Not now: ${heavy.reason ?: "the phone needs a rest"}. Try again later."
        val data = env.allowDownload(BrowserTools.DOWNLOAD_BYTES, DATA_KIND)
        if (!data.allowed) {
            return "Waiting: ${data.reason ?: "big downloads wait for Wi-Fi"}. The test browser is a download of about " +
                "${BrowserTools.DOWNLOAD_BYTES / 1_000_000} MB. Ask the owner to connect to Wi-Fi, then call install_browser again."
        }
        val lastFailure = (progress as? Progress.Failed)?.why
        progress = Progress.Running("starting", env.now())
        env.scope.launch { install(agentId) }
        "Installing the test browser now (about ${BrowserTools.DOWNLOAD_BYTES / 1_000_000} MB). Call install_browser again in a few minutes to see progress." +
            (lastFailure?.let { " The last try failed: $it" } ?: "")
    }

    private suspend fun install(agentId: String) {
        var failure: String? = null
        val code = try {
            withContext(Dispatchers.IO) {
                RoomLayout.hostFolders(env.dirs, agentId).forEach { it.mkdirs() }
                configurator.installTools()
            }
            env.computer.run(command(agentId)) { line ->
                when {
                    line.startsWith("STEP ") -> progress = Progress.Running(line.removePrefix("STEP ").trim(), env.now())
                    line.startsWith("FAILED ") -> failure = line.removePrefix("FAILED ").trim()
                }
            }
        } catch (cancelled: CancellationException) {
            progress = Progress.Idle
            throw cancelled
        } catch (failed: Exception) {
            progress = Progress.Failed(failed.message ?: "the installer could not start")
            return
        }
        if (code != 0) {
            progress = Progress.Failed(failure ?: "the installer stopped (code $code)")
            return
        }
        env.recordDownload(BrowserTools.DOWNLOAD_BYTES, DATA_KIND)
        progress = Progress.Idle
        afterInstall()
    }

    private fun command(agentId: String) = LinuxCommand(
        argv = listOf(
            RoomLayout.PYTHON, BrowserTools.INSTALLER,
            "--prefix", BrowserTools.PREFIX,
            "--browsers", BrowserTools.BROWSERS,
            "--node", BrowserTools.NODE,
        ),
        binds = RoomLayout.binds(env.dirs, agentId),
        workDir = RoomLayout.GUEST_TMP,
    )

    private companion object {
        const val DATA_KIND = "browser"
        const val INSTALLED = "The test browser is installed. Its tools (Playwright and Chrome DevTools) appear the next time " +
            "this agent's room starts; ask the owner to restart the agent if you need them in this chat."
    }
}

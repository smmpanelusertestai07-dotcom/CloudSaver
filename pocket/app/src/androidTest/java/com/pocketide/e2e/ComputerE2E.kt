package com.pocketide.e2e

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketide.agents.Agent
import com.pocketide.core.ThemeMode
import com.pocketide.graph
import com.pocketide.ide.IdeState
import com.pocketide.linux.ComputerState
import com.pocketide.linux.LinuxCommand
import com.pocketide.ui.nav.WorkspaceRoute
import com.pocketide.ui.screens.workspace.WorkspaceScreen
import com.pocketide.ui.theme.PocketTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The whole app on this device, end to end, as a new owner uses it: Set up (Ubuntu from the
 * internet, its tools, code-server and the three official agents), code-server started, each
 * agent's own screen in the app's page, a sign-in terminal with a line pasted into it, and a link
 * a program inside Linux opens. Each screen is saved as a picture under files/e2e, with a log of every step, which CI
 * reads back with run-as.
 *
 * Runs only when asked (`am instrument -e e2e true`): it downloads about 1 GB and takes a while.
 */
@RunWith(AndroidJUnit4::class)
class ComputerE2E {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val graph get() = compose.activity.graph
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val out by lazy { File(instrumentation.targetContext.filesDir, "e2e").apply { mkdirs() } }
    private val companionLog by lazy { File(graph.dirs.home, ".pocketide/companion.log") }

    @Before
    fun onlyWhenAsked() {
        assumeTrue("Runs only with -e e2e true", InstrumentationRegistry.getArguments().getString("e2e") == "true")
    }

    @Test
    fun setsUpAndShowsEveryAgent() {
        // code-server's VS Code draws in the WebView, so the steps say which one this device has.
        note("webview: " + (WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "none"))
        runBlocking {
            graph.setUp()
            val end = withTimeout(SET_UP_MS) {
                var last = ""
                graph.computer.state
                    .onEach { state ->
                        val said = state.toString().take(MAX_NOTE)
                        if (said != last) note("computer: $said")
                        last = said
                    }
                    .first { it == ComputerState.Ready || it is ComputerState.Broken }
            }
            assertEquals("The computer is set up", ComputerState.Ready, end)
            withTimeout(AGENTS_MS) {
                graph.agents.installed.first { installed ->
                    Agent.entries.all { agent -> installed.any { it.id.equals(agent.extensionId, ignoreCase = true) } }
                }
            }
            note("agents: " + graph.agents.installed.value.joinToString { "${it.id} ${it.version}" })
            val on = withTimeout(IDE_MS) { graph.ide.start() }
            note("code-server on 127.0.0.1:${on.port}")
            assertEquals("code-server asks for its password without the app's cookie", HTTP_FOUND, status("http://127.0.0.1:${on.port}/"))
        }

        var route by mutableStateOf(WorkspaceRoute(agentId = Agent.CLAUDE.extensionId))
        compose.setContent {
            PocketTheme(ThemeMode.LIGHT) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    key(route) { WorkspaceScreen(route, onBack = {}, onHelpPage = {}) }
                }
            }
        }
        Agent.entries.forEach { agent ->
            compose.runOnIdle { route = WorkspaceRoute(agentId = agent.extensionId) }
            awaitCompanion("show ${agent.extensionId} with ")
            // An agent's first start on a phone takes a while; Antigravity also fetches Google's agy.
            SystemClock.sleep(AGENT_SETTLE_MS)
            shoot(agent.name.lowercase())
        }
        val signIn = Agent.CLAUDE
        compose.runOnIdle {
            route = WorkspaceRoute(agentId = signIn.extensionId, terminal = true, command = signIn.signInCommand, title = "Sign in: ${signIn.displayName}")
        }
        awaitCompanion("request terminal")
        SystemClock.sleep(TERMINAL_SETTLE_MS)
        shoot("sign-in-terminal")
        // The key bar's Paste: the line reaches that terminal, typed after the command, not run.
        graph.ide.type(PASTED)
        awaitCompanion("typed ${PASTED.length} characters")
        SystemClock.sleep(POLL_MS)
        shoot("pasted")

        linkFromLinux()
        assertTrue("code-server kept running", graph.ide.state.value is IdeState.On)
        note("done")
    }

    /** A program inside Linux opening a web address (as the agents' sign-in commands do) reaches the app. */
    private fun linkFromLinux() {
        val folder = graph.dirs.openRequests
        val code = runBlocking { graph.computer.run(LinuxCommand(listOf("/opt/pocketide/bin/xdg-open", "https://example.com/pocketide-e2e"))) { } }
        assertEquals("xdg-open inside Linux", 0, code)
        val deadline = SystemClock.uptimeMillis() + LINK_MS
        while (folder.listFiles().orEmpty().any { it.name.endsWith(".url") } && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(POLL_MS)
        assertTrue("The app took the link Linux asked it to open", folder.listFiles().orEmpty().none { it.name.endsWith(".url") })
        note("link from Linux: taken by the app")
        SystemClock.sleep(POLL_MS)
        shoot("link-opened")
    }

    /** Waits until the companion inside code-server logged [line], and fails with its log if it never does. */
    private fun awaitCompanion(line: String) {
        val deadline = SystemClock.uptimeMillis() + COMPANION_MS
        while (SystemClock.uptimeMillis() < deadline) {
            // In a Compose test the screen recomposes, and its effects run, only when the test lets
            // Compose's clock go on: without this, a new route set a moment ago is never composed,
            // and the screen never sends its request.
            compose.waitForIdle()
            if (companionLog.isFile && companionLog.readText().contains(line)) {
                note("companion: $line")
                return
            }
            SystemClock.sleep(POLL_MS)
        }
        // What the page, Linux and the screen were doing when the wait ran out.
        note("page answers: ${pageAnswers()}")
        note("busiest programs:\n${busiestPrograms()}")
        runCatching { shoot("waiting") }.onFailure { note("no picture: ${it.message}") }
        val said = if (companionLog.isFile) companionLog.readText().takeLast(MAX_LOG) else "(no companion log)"
        throw AssertionError("The companion never logged \"$line\":\n$said")
    }

    /** Whether the page's JavaScript still runs: it answers a one-line script within a few seconds. */
    private fun pageAnswers(): String {
        val asked = CountDownLatch(1)
        val answered = CountDownLatch(1)
        var found = false
        // Posted, not run with runOnMainSync, so a busy main thread is reported instead of waited on.
        Handler(Looper.getMainLooper()).post {
            val page = webViewIn(compose.activity.window.decorView)
            found = page != null
            page?.evaluateJavascript("document.readyState") { answered.countDown() }
            asked.countDown()
        }
        return when {
            !asked.await(PROBE_MS, TimeUnit.MILLISECONDS) -> "the app's main thread is busy"
            !found -> "no page on the screen"
            answered.await(PROBE_MS, TimeUnit.MILLISECONDS) -> "yes"
            else -> "no answer in ${PROBE_MS / 1000} s"
        }
    }

    private fun webViewIn(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { webViewIn(view.getChildAt(it)) }
        else -> null
    }

    /** The app's programs (Linux's among them), by the processor time each has used. */
    private fun busiestPrograms(): String = File("/proc").listFiles().orEmpty()
        .filter { dir -> dir.name.all(Char::isDigit) }
        .mapNotNull { dir ->
            val command = runCatching { File(dir, "cmdline").readText().replace('\u0000', ' ').trim() }.getOrNull()
            val fields = runCatching { File(dir, "stat").readText().substringAfterLast(')').trim().split(' ') }.getOrNull()
            if (command.isNullOrEmpty() || fields == null) return@mapNotNull null
            val ticks = (fields.getOrNull(UTIME)?.toLongOrNull() ?: 0) + (fields.getOrNull(STIME)?.toLongOrNull() ?: 0)
            ticks to command.take(MAX_COMMAND)
        }
        .sortedByDescending { it.first }
        .take(MAX_PROGRAMS)
        .joinToString("\n") { (ticks, command) -> "  ${ticks / TICKS_PER_SECOND} s  $command" }

    private fun status(url: String): Int = (URL(url).openConnection() as HttpURLConnection).run {
        instanceFollowRedirects = false
        connectTimeout = HTTP_TIMEOUT_MS
        readTimeout = HTTP_TIMEOUT_MS
        try {
            responseCode
        } finally {
            disconnect()
        }
    }

    /** The whole screen as the owner sees it, the agent's page included. */
    private fun shoot(name: String) {
        compose.waitForIdle()
        val picture = instrumentation.uiAutomation.takeScreenshot() ?: return note("no picture of $name")
        File(out, "$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        note("picture: $name.png")
    }

    private fun note(text: String) {
        Log.i(TAG, text)
        File(out, "steps.txt").appendText("${System.currentTimeMillis()} $text\n")
    }

    private companion object {
        const val TAG = "PocketIDE-E2E"
        const val SET_UP_MS = 75 * 60 * 1000L
        const val AGENTS_MS = 30 * 60 * 1000L
        const val IDE_MS = 10 * 60 * 1000L
        const val COMPANION_MS = 8 * 60 * 1000L
        const val AGENT_SETTLE_MS = 90 * 1000L
        const val TERMINAL_SETTLE_MS = 20 * 1000L
        const val LINK_MS = 30 * 1000L
        const val POLL_MS = 2000L
        const val HTTP_FOUND = 302
        const val HTTP_TIMEOUT_MS = 10_000
        const val PNG_QUALITY = 100
        const val MAX_NOTE = 200
        const val MAX_LOG = 4000
        const val PASTED = " --version"
        const val PROBE_MS = 5000L
        const val MAX_PROGRAMS = 12
        const val MAX_COMMAND = 160
        const val TICKS_PER_SECOND = 100

        /** utime and stime in /proc/<pid>/stat, counted from the field after the command's name. */
        const val UTIME = 11
        const val STIME = 12
    }
}

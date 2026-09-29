package com.pocketide.ui.screens.workspace

import android.app.Activity
import android.net.Uri
import android.view.KeyEvent
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.agents.Agent
import com.pocketide.docs.DocsContent
import com.pocketide.graph
import com.pocketide.ide.IdeFiles
import com.pocketide.ide.IdeState
import com.pocketide.ui.components.Tone
import com.pocketide.ui.nav.WorkspaceRoute
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.web.Browser
import com.pocketide.ui.web.PageHost
import com.pocketide.ui.web.PageState
import com.pocketide.ui.web.WebPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * An agent's own screen, full screen: code-server on this phone with only that agent in view, or
 * a terminal (with a sign-in command typed, for [WorkspaceRoute.command]). The page keeps running
 * when the owner leaves; Back closes the page's menus first, then leaves the terminal for the agent.
 *
 * After a sign-in in the terminal, the agent's screen comes back by itself, signed in: when the
 * file where the agent keeps its account changes, the page reloads (so the agent reads it) and
 * shows the agent again.
 */
@Composable
fun WorkspaceScreen(route: WorkspaceRoute, onBack: () -> Unit, onHelpPage: (String) -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val graph = context.graph
    val ide by graph.ide.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val installed by graph.agents.installed.collectAsStateWithLifecycle()
    val folder = graph.projects.guestFolder(settings.project)
    val agent = Agent.of(route.agentId)
    val title = route.title.ifEmpty { agent?.displayName ?: installed.firstOrNull { it.id == route.agentId }?.displayName ?: "Agent" }
    val scope = rememberCoroutineScope()

    var page by remember { mutableStateOf<PageState>(PageState.Loading(0)) }
    var askOpen by remember { mutableStateOf<String?>(null) }
    var pendingFiles by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var asked by remember { mutableStateOf(false) }
    // A new WebView after Android stopped the old one's renderer.
    var generation by remember { mutableIntStateOf(0) }
    // The terminal's title while a terminal is in front, instead of the agent.
    var terminalTitle by remember { mutableStateOf(if (route.terminal) title else null) }
    var signIn by remember { mutableStateOf<SignInWatch?>(null) }
    var showWhenReady by remember { mutableStateOf(false) }
    val reloadAndShow = {
        page = PageState.Loading(0)
        showWhenReady = true
        graph.page.reload()
    }
    val openTerminal = { name: String, text: String ->
        terminalTitle = name
        graph.ide.terminal(name, text, folder)
    }
    val signInWith = { signingIn: Agent ->
        val file = signingIn.sharedSignInFile?.let { File(graph.dirs.home, it) }
        scope.launch { signIn = file?.let { SignInWatch(it, stampOf(it)) } }
        openTerminal("Sign in: ${signingIn.displayName}", signingIn.signInCommand)
    }
    val back = {
        graph.page.back {
            if (terminalTitle != null && route.agentId.isNotEmpty()) {
                terminalTitle = null
                graph.ide.show(route.agentId)
            } else {
                onBack()
            }
        }
    }
    val start = { graph.scope.launch { runCatching { graph.ide.start() } } }
    val restart = {
        graph.page.release()
        graph.scope.launch {
            graph.ide.stop()
            runCatching { graph.ide.start() }
        }
    }
    val answer = { uris: List<Uri> ->
        pendingFiles?.onReceiveValue(uris.toTypedArray())
        pendingFiles = null
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents(), answer)
    // Pictures come from Android's photo picker, which needs no storage permission and shows only what the owner picks.
    val pickPictures = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(), answer)

    LaunchedEffect(Unit) { runCatching { graph.ide.start() } }
    DisposableEffect(Unit) {
        onDispose {
            graph.page.detach()
            pendingFiles?.onReceiveValue(null)
        }
    }
    BackHandler { back() }

    val host = remember {
        object : PageHost {
            override fun openInChrome(url: String, fromTap: Boolean) {
                if (fromTap || WebPolicy.isSignInSite(url, IdeFiles.SIGN_IN_SITES)) Browser.open(activity, url) else askOpen = url
            }

            override fun pickFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams) {
                pendingFiles?.onReceiveValue(null)
                pendingFiles = callback
                val accepts = params.acceptTypes.orEmpty().toList()
                runCatching {
                    if (acceptsOnlyImages(accepts)) {
                        pickPictures.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } else {
                        pickFiles.launch("*/*")
                    }
                }.onFailure {
                    callback.onReceiveValue(null)
                    pendingFiles = null
                }
            }

            override fun downloadRefused() {
                Toast.makeText(activity, "Files stay inside PocketIDE. Use git to share them.", Toast.LENGTH_LONG).show()
            }

            override fun onPageState(state: PageState) {
                page = state
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopBar(
            title = terminalTitle ?: title,
            onBack = back,
            onSignIn = agent?.let { { signInWith(it) } },
            onRefresh = { if (terminalTitle == null && route.agentId.isNotEmpty()) reloadAndShow() else graph.page.reload() },
            onTerminal = { openTerminal("Terminal", "") },
            onRestart = { restart() },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val state = ide) {
                is IdeState.On -> {
                    key(state, folder, generation) {
                        AndroidView(factory = { graph.page.attach(activity, state, folder, host) }, modifier = Modifier.fillMaxSize())
                    }
                    LaunchedEffect(state) {
                        if (!asked) {
                            asked = true
                            when {
                                route.terminal && agent != null && route.command == agent.signInCommand -> signInWith(agent)
                                route.terminal -> graph.ide.terminal(title, route.command, folder)
                                route.agentId.isNotEmpty() -> graph.ide.show(route.agentId)
                            }
                        }
                    }
                    // A reloaded page asks for the agent again once it is up; the companion in the
                    // new window takes the request when it starts.
                    LaunchedEffect(page, showWhenReady) {
                        if (showWhenReady && page == PageState.Ready) {
                            showWhenReady = false
                            if (route.agentId.isNotEmpty()) graph.ide.show(route.agentId)
                        }
                    }
                    PageOverlay(page, onReload = {
                        if (page == PageState.Stopped) {
                            page = PageState.Loading(0)
                            generation++
                        } else {
                            graph.page.reload()
                        }
                    })
                }
                is IdeState.Failed -> Failed(state, onRetry = { start() }, onHelp = { onHelpPage(DocsContent.TROUBLE_ID) })
                is IdeState.Starting -> Waiting(state.step)
                IdeState.Off -> Waiting("Starting code-server…")
            }
        }
        if (settings.keyBar && imeVisible()) KeyBar(onKey = graph.page::sendKey)
    }

    signIn?.let { watch ->
        LaunchedEffect(watch) {
            val signedIn = withTimeoutOrNull(SIGN_IN_WAIT_MS) {
                while (stampOf(watch.file) == watch.before) delay(SIGN_IN_POLL_MS)
            } != null
            if (signedIn) {
                // The command prints its last lines, then the agent's screen comes back signed in.
                delay(SIGN_IN_SETTLE_MS)
                terminalTitle = null
                reloadAndShow()
            }
            signIn = null
        }
    }

    askOpen?.let { url ->
        AlertDialog(
            onDismissRequest = { askOpen = null },
            title = { Text("Open in Chrome?") },
            text = { Text("The page wants to open ${WebPolicy.hostOf(url) ?: "a web page"}.") },
            confirmButton = {
                TextButton(onClick = {
                    Browser.open(activity, url)
                    askOpen = null
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { askOpen = null }) { Text("Cancel") } },
        )
    }
}

@Composable
@Suppress("LongParameterList") // One callback per action in the bar.
private fun TopBar(
    title: String,
    onBack: () -> Unit,
    onSignIn: (() -> Unit)?,
    onRefresh: () -> Unit,
    onTerminal: () -> Unit,
    onRestart: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onSignIn != null) IconButton(onClick = onSignIn) { Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = "Sign in with the terminal") }
            IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Reload the screen") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Terminal") }, onClick = {
                        menu = false
                        onTerminal()
                    })
                    DropdownMenuItem(text = { Text("Restart code-server") }, onClick = {
                        menu = false
                        onRestart()
                    })
                }
            }
        }
    }
}

/** Esc, Tab, Ctrl+C, the arrows and Enter: keys a phone's keyboard lacks, for the terminal and the editor. */
@Composable
private fun KeyBar(onKey: (Int, Int) -> Unit) {
    val keys = listOf(
        "Esc" to (KeyEvent.KEYCODE_ESCAPE to 0),
        "Tab" to (KeyEvent.KEYCODE_TAB to 0),
        "Ctrl+C" to (KeyEvent.KEYCODE_C to (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)),
        "↑" to (KeyEvent.KEYCODE_DPAD_UP to 0),
        "↓" to (KeyEvent.KEYCODE_DPAD_DOWN to 0),
        "←" to (KeyEvent.KEYCODE_DPAD_LEFT to 0),
        "→" to (KeyEvent.KEYCODE_DPAD_RIGHT to 0),
        "Enter" to (KeyEvent.KEYCODE_ENTER to 0),
    )
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            keys.forEach { (label, key) ->
                TextButton(onClick = { onKey(key.first, key.second) }, modifier = Modifier.heightIn(min = 44.dp)) { Text(label) }
            }
        }
    }
}

@Composable
private fun imeVisible(): Boolean = WindowInsets.ime.getBottom(LocalDensity.current) > 0

@Composable
private fun Waiting(step: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(Modifier.size(40.dp))
        Gap(16.dp)
        Text(step, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Gap(8.dp)
        Text(
            "The first start takes a minute or two on a phone. The agents open by themselves.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Failed(state: IdeState.Failed, onRetry: () -> Unit, onHelp: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        NoticeCard(state.fix, tone = Tone.ERROR, title = state.why)
        Gap(16.dp)
        PrimaryAction("Try again", onClick = onRetry)
        TextButton(onClick = onHelp, modifier = Modifier.fillMaxWidth()) { Text("What to do when it does not start") }
    }
}

@Composable
private fun PageOverlay(page: PageState, onReload: () -> Unit) {
    when (page) {
        is PageState.Loading -> if (page.progress in
            1 until FULL
        ) {
            LinearProgressIndicator(progress = { page.progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        is PageState.Failed, PageState.Stopped -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                val message = if (page is PageState.Failed) page.message else "Android stopped the page to free memory. The agents kept working."
                NoticeCard(message, tone = Tone.WARN)
                Gap(16.dp)
                PrimaryAction("Reload", onClick = onReload)
            }
        }
        PageState.Ready -> Unit
    }
}

private const val FULL = 100
private const val SIGN_IN_WAIT_MS = 15 * 60 * 1000L
private const val SIGN_IN_POLL_MS = 1500L
private const val SIGN_IN_SETTLE_MS = 1500L

/** A sign-in running in the terminal: the agent's account file, and when it last changed before. */
private data class SignInWatch(val file: File, val before: Long)

/** When [file] last changed (0 while it does not exist). */
private suspend fun stampOf(file: File): Long = withContext(Dispatchers.IO) { file.lastModified() }

/** True when a file input takes only pictures (image types only), which the photo picker serves. */
internal fun acceptsOnlyImages(accepts: List<String>): Boolean {
    val types = accepts.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
    return types.isNotEmpty() && types.all { it.startsWith("image/") }
}

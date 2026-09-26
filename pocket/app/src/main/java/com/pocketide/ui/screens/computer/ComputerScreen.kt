package com.pocketide.ui.screens.computer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.KeyEvent
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.AppGraph
import com.pocketide.agents.Agent
import com.pocketide.cloud.Computer
import com.pocketide.cloud.ComputerService
import com.pocketide.cloud.OpenStep
import com.pocketide.graph
import com.pocketide.ui.components.AgentLogo
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.VsCodeLogo
import com.pocketide.ui.shell.CenteredTitle
import com.pocketide.ui.shell.FinePrint
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.PrimaryAction
import com.pocketide.ui.shell.SecondaryAction
import com.pocketide.ui.shell.ShellPage
import com.pocketide.ui.web.Browser
import com.pocketide.ui.web.ComputerWebView
import com.pocketide.ui.web.PageHost
import com.pocketide.ui.web.PageState
import com.pocketide.ui.web.WebPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private sealed interface Phase {
    data class Opening(val step: OpenStep) : Phase
    data class Ready(val computer: Computer) : Phase
    data class Failed(val message: String) : Phase
}

/**
 * The cloud computer, full screen: VS Code for the web on the owner's codespace with the agents
 * in front, PocketIDE's key bar under it, and the ⋯ menu for agents, terminal and the computer.
 */
@Composable
fun ComputerScreen(agentToShow: Agent?, onAgentShown: () -> Unit, onHome: () -> Unit, onHelp: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val activity = context as? FragmentActivity ?: return
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val name = settings.lastComputer
    if (name.isBlank()) {
        NoComputer(onHome)
        return
    }
    var attempt by remember(name) { mutableIntStateOf(0) }
    var phase by remember(name) { mutableStateOf<Phase>(Phase.Opening(OpenStep.CHECKING)) }
    var askedForNotices by rememberSaveable { mutableStateOf(false) }
    val notices = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(name, attempt) {
        phase = open(graph, name) { phase = Phase.Opening(it) }
    }

    when (val current = phase) {
        is Phase.Opening -> ShellPage {
            CenteredTitle(Icons.Outlined.Terminal, "Opening your computer", null)
            Gap(24.dp)
            OpeningSteps(current.step, addsSetUp = false)
            Gap(16.dp)
            FinePrint("A stopped computer takes about a minute to start. It uses your free hours only while it runs.")
            SecondaryAction("Back to Home", onClick = onHome)
        }
        is Phase.Failed -> ShellPage(centered = true) {
            CenteredTitle(Icons.Outlined.Terminal, "The computer did not open", null, Tone.ERROR)
            Gap(16.dp)
            NoticeCard(current.message, Tone.ERROR)
            Gap(20.dp)
            PrimaryAction("Try again", onClick = { attempt++ })
            SecondaryAction("Back to Home", onClick = onHome)
        }
        is Phase.Ready -> {
            LaunchedEffect(current.computer.name, settings.stayConnected) {
                if (!settings.stayConnected) return@LaunchedEffect
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedForNotices &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    askedForNotices = true
                    notices.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                ComputerService.stayConnected(context, current.computer)
            }
            ComputerPage(
                activity = activity,
                computer = current.computer,
                agentToShow = agentToShow,
                onAgentShown = onAgentShown,
                keyBar = settings.keyBar,
                onHome = onHome,
                onHelp = onHelp,
            )
        }
    }
}

/** Starts the computer when needed and waits for it; the phase to show once that is done. */
private suspend fun open(graph: AppGraph, name: String, onStep: (OpenStep) -> Unit): Phase = try {
    var started = false
    val computer = graph.computers.startAndWait(name) { step ->
        if (step == OpenStep.STARTING || step == OpenStep.CREATING) started = true
        onStep(step)
    }
    // A page kept from before the computer stopped shows GitHub's "stopped" screen.
    if (started) graph.computerPage.reopenIfFor(computer.name, computer.webUrl)
    Phase.Ready(computer)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (e: Exception) {
    Phase.Failed(e.message ?: "The computer did not open. Try again.")
}

@Composable
private fun ComputerPage(
    activity: FragmentActivity,
    computer: Computer,
    agentToShow: Agent?,
    onAgentShown: () -> Unit,
    keyBar: Boolean,
    onHome: () -> Unit,
    onHelp: () -> Unit,
) {
    val graph = activity.graph
    val page = graph.computerPage
    var pageState by remember { mutableStateOf<PageState>(PageState.Loading(0)) }
    var generation by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var waiting by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    var askOpen by remember { mutableStateOf<String?>(null) }
    val answer = { uris: List<Uri> ->
        waiting?.onReceiveValue(uris.toTypedArray().takeIf { it.isNotEmpty() })
        waiting = null
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(), answer)
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), answer)
    val host = remember(computer.name) {
        object : PageHost {
            override fun openInChrome(url: String, fromTap: Boolean) {
                if (fromTap) Browser.open(activity, url) else askOpen = url
            }

            override fun pickFiles(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams) {
                // The page waits for one answer per request: an older one is answered empty first.
                waiting?.onReceiveValue(null)
                waiting = callback
                if (acceptsOnlyImages(params.acceptTypes.orEmpty().toList())) {
                    pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } else {
                    pickFiles.launch(arrayOf("*/*"))
                }
            }

            override fun onPageState(state: PageState) {
                pageState = state
            }
        }
    }
    BackHandler { page.back(onHome) }
    LaunchedEffect(pageState, agentToShow) {
        val agent = agentToShow ?: return@LaunchedEffect
        if (pageState != PageState.Ready) return@LaunchedEffect
        page.showAgent(agent) { shown ->
            if (!shown) Toast.makeText(activity, agentMissing(agent), Toast.LENGTH_LONG).show()
            onAgentShown()
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(generation) {
                AndroidView(
                    factory = { page.attach(activity, computer.name, computer.webUrl, host) },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { page.detach() },
                )
            }
            PageOverlay(pageState, onReload = {
                if (pageState == PageState.Stopped) generation++ else page.reopen(computer.webUrl)
            })
        }
        if (keyBar) KeyBar(page, onMenu = { menu = true }) else FloatingMenuButton(onMenu = { menu = true })
    }
    askOpen?.let { url ->
        AlertDialog(
            onDismissRequest = { askOpen = null },
            title = { Text("Open in Chrome?") },
            text = { Text("The page wants to open ${WebPolicy.hostOf(url).orEmpty()}.") },
            confirmButton = {
                TextButton(onClick = {
                    askOpen = null
                    Browser.open(activity, url)
                }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { askOpen = null }) { Text("Not now") } },
        )
    }
    if (menu) {
        ComputerMenu(
            computer = computer,
            page = page,
            keyBar = keyBar,
            onDismiss = { menu = false },
            onHome = onHome,
            onHelp = onHelp,
        )
    }
}

/** A file input that takes only pictures gets the photo picker; anything else, the files picker. */
internal fun acceptsOnlyImages(acceptTypes: List<String>): Boolean {
    val types = acceptTypes.flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    return types.isNotEmpty() && types.all { it.startsWith("image/") }
}

private fun agentMissing(agent: Agent): String =
    "Tap ${agent.displayName} at the top of the page. If it is not there yet, it is still installing: give it a minute."

@Composable
private fun PageOverlay(state: PageState, onReload: () -> Unit) {
    when (state) {
        is PageState.Loading -> LinearProgressIndicator(
            progress = { state.progress / 100f },
            modifier = Modifier.fillMaxWidth(),
        )
        is PageState.Failed -> OverlayCard(state.message, onReload)
        PageState.Stopped -> OverlayCard("Android closed the page to free memory. Your agents kept working in the cloud.", onReload)
        PageState.Ready -> Unit
    }
}

@Composable
private fun OverlayCard(text: String, onReload: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NoticeCard(text, Tone.WARN)
            PrimaryAction("Reload", onClick = onReload)
        }
    }
}

private const val CTRL = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON

/**
 * The keys a phone keyboard lacks, as real key presses to the page. Ctrl stays down for the next
 * key. Send is Ctrl+Enter: the agents are set so Enter makes a new line and Ctrl+Enter sends.
 */
@Composable
private fun KeyBar(page: ComputerWebView, onMenu: () -> Unit) {
    var ctrl by remember { mutableStateOf(false) }
    val send = { code: Int, meta: Int ->
        page.sendKey(code, meta or if (ctrl) CTRL else 0)
        ctrl = false
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onMenu) { Icon(Icons.Outlined.MoreHoriz, contentDescription = "PocketIDE menu") }
            Key("Esc") { send(KeyEvent.KEYCODE_ESCAPE, 0) }
            Key("Tab") { send(KeyEvent.KEYCODE_TAB, 0) }
            Key("Ctrl", on = ctrl) { ctrl = !ctrl }
            Key("←") { send(KeyEvent.KEYCODE_DPAD_LEFT, 0) }
            Key("↑") { send(KeyEvent.KEYCODE_DPAD_UP, 0) }
            Key("↓") { send(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
            Key("→") { send(KeyEvent.KEYCODE_DPAD_RIGHT, 0) }
            Key("Ctrl C") { send(KeyEvent.KEYCODE_C, CTRL) }
            Key("Send", on = true) { send(KeyEvent.KEYCODE_ENTER, CTRL) }
        }
    }
}

@Composable
private fun Key(label: String, on: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.heightIn(min = 36.dp).widthIn(min = 44.dp),
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun FloatingMenuButton(onMenu: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onMenu) { Icon(Icons.Outlined.MoreHoriz, contentDescription = "PocketIDE menu") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComputerMenu(computer: Computer, page: ComputerWebView, keyBar: Boolean, onDismiss: () -> Unit, onHome: () -> Unit, onHelp: () -> Unit) {
    val context = LocalContext.current
    val graph = context.graph
    val scope = rememberCoroutineScope()
    val close = { action: () -> Unit ->
        onDismiss()
        action()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                VsCodeLogo(size = 22.dp)
                Spacer(Modifier.padding(start = 10.dp))
                Text(computer.repo.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                StatusChip(computer.state.label(), computer.state.tone())
            }
            Agent.entries.forEach { agent ->
                MenuRow(
                    title = agent.displayName,
                    detail = "by ${agent.maker}",
                    leading = { AgentLogo(agent, size = 36.dp) },
                    onClick = {
                        close {
                            page.showAgent(agent) { shown ->
                                if (!shown) Toast.makeText(context, agentMissing(agent), Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            MenuRow("Terminal", "Opens or closes VS Code's terminal", icon = Icons.Outlined.Terminal) {
                close { page.sendKey(KeyEvent.KEYCODE_GRAVE, CTRL) }
            }
            MenuRow("Command palette", "Every VS Code command, by name", icon = Icons.Outlined.Search) {
                close { page.sendKey(KeyEvent.KEYCODE_F1) }
            }
            ListItem(
                headlineContent = { Text("Keyboard keys") },
                supportingContent = { Text("Esc, Tab, Ctrl, arrows and Send above the keyboard") },
                leadingContent = { Icon(Icons.Outlined.Keyboard, contentDescription = null) },
                trailingContent = { Switch(checked = keyBar, onCheckedChange = { on -> graph.settings.update { it.copy(keyBar = on) } }) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            )
            MenuRow("Reload page", "Your agents keep working while it reloads", icon = Icons.Outlined.Refresh) { close { page.reload() } }
            MenuRow("Open in Chrome", "The same computer in the browser", icon = Icons.AutoMirrored.Outlined.OpenInNew) {
                close { Browser.open(context, computer.webUrl) }
            }
            MenuRow("Stop computer", "Saves your free hours; your files stay", icon = Icons.Outlined.PowerSettingsNew) {
                close {
                    scope.launch {
                        try {
                            graph.computers.stop(computer.name)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        ComputerService.disconnect(context)
                        page.release()
                        onHome()
                    }
                }
            }
            MenuRow("Home", null, icon = Icons.Outlined.Home) { close(onHome) }
            MenuRow("Help", null, icon = Icons.AutoMirrored.Outlined.HelpOutline) { close(onHelp) }
        }
    }
}

@Composable
private fun MenuRow(
    title: String,
    detail: String?,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        leadingContent = leading ?: icon?.let { { Icon(it, contentDescription = null) } },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun NoComputer(onHome: () -> Unit) {
    ShellPage(centered = true) {
        CenteredTitle(Icons.Outlined.Terminal, "No computer open", "Open a project's cloud computer from Home, or make a new project.")
        Gap(24.dp)
        PrimaryAction("Go to Home", onClick = onHome)
    }
}

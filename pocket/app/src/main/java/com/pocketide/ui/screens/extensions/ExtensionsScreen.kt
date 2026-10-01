package com.pocketide.ui.screens.extensions

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.agents.Agent
import com.pocketide.cloudshell.Answer
import com.pocketide.cloudshell.CloudShellInfo
import com.pocketide.cloudshell.InstalledExtension
import com.pocketide.cloudshell.OpenVsx
import com.pocketide.cloudshell.OpenVsxExtension
import com.pocketide.graph
import com.pocketide.link.LinkState
import com.pocketide.ui.components.AgentLogo
import com.pocketide.ui.components.DialogBody
import com.pocketide.ui.components.ExtensionLogo
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.Tone
import com.pocketide.ui.screens.live.Asking
import com.pocketide.ui.screens.live.ConnectFirst
import com.pocketide.ui.screens.live.KeepConnectionWhileShown
import com.pocketide.ui.shell.NoticeCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Each agent's extensions, by info.py's name for the agent; null until Cloud Shell answers. */
private typealias Installed = Map<String, List<InstalledExtension>>

/**
 * Extensions: Open VSX searched from the phone, with each extension's icon, maker, downloads and
 * rating. A tap shows it whole and installs it in an agent's VS Code in Cloud Shell, or removes it;
 * what each agent's VS Code has is read live from Cloud Shell.
 */
@Composable
fun ExtensionsScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val link by graph.link.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var found by remember { mutableStateOf<OpenVsx.Found?>(null) }
    var installed by remember { mutableStateOf<Installed?>(null) }
    var picked by remember { mutableStateOf<OpenVsxExtension?>(null) }
    var asked by remember { mutableIntStateOf(0) }
    KeepConnectionWhileShown()
    LaunchedEffect(query) {
        if (query.isNotBlank()) delay(TYPING_MS)
        found = null
        found = OpenVsx.search(query)
    }
    LaunchedEffect(link == LinkState.On, asked) {
        if (link == LinkState.On) installed = (graph.cloudInfo.extensions() as? Answer.Got)?.value?.agents
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                Text("Extensions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(QUERY_LIMIT) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Search Open VSX") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (query.isBlank()) item { InstalledCard(link, installed, onChanged = { asked++ }) }
                item {
                    Text(
                        if (query.isBlank()) "Most installed on Open VSX" else "On Open VSX",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                when (val now = found) {
                    null -> item { Asking("Searching Open VSX…") }
                    is OpenVsx.Found.Failed -> item { NoticeCard(now.why, tone = Tone.WARN) }
                    is OpenVsx.Found.Some -> {
                        if (now.extensions.isEmpty()) item { Text("Open VSX has nothing by that name.") }
                        items(now.extensions, key = { it.id }) { extension -> ExtensionRow(extension, onClick = { picked = extension }) }
                    }
                }
            }
        }
    }
    picked?.let { extension ->
        ExtensionDialog(extension, link, installed, onChanged = { asked++ }, onClose = { picked = null })
    }
}

/** What each agent's VS Code has now, read from Cloud Shell; anything but the agent's own can go. */
@Composable
private fun InstalledCard(link: LinkState, installed: Installed?, onChanged: () -> Unit) {
    if (link != LinkState.On) {
        ConnectFirst(link, "Each agent's extensions are")
        return
    }
    SectionCard("In each agent's VS Code") {
        if (installed == null) {
            Asking("Asking Cloud Shell…")
            return@SectionCard
        }
        Agent.entries.forEach { agent ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentLogo(agent, size = 24.dp)
                Spacer(Modifier.width(8.dp))
                Text(agent.displayName, style = MaterialTheme.typography.titleSmall)
            }
            installed[CloudShellInfo.key(agent)].orEmpty().forEach { extension ->
                InstalledRow(agent, extension, onChanged)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Text(
            "Each agent's VS Code keeps its own extensions, and they update by themselves. The agent's own and " +
                "PocketIDE's layout stay.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InstalledRow(agent: Agent, extension: InstalledExtension, onChanged: () -> Unit) {
    val graph = LocalContext.current.graph
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ExtensionLogo(extension.id.substringAfter('.'), size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(extension.id, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                said ?: listOf(extension.version, if (extension.own) "kept by PocketIDE" else "").filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else if (!extension.own) {
            TextButton(onClick = {
                busy = true
                scope.launch {
                    val done = graph.link.extension(install = false, agent, extension.id, anyPublisher = false) { said = it }
                    busy = false
                    if (done) onChanged() else said = said ?: "It was not removed. Try again."
                }
            }) { Text("Remove") }
        }
    }
}

/** One search result: icon, name, maker, downloads, rating and what it does. */
@Composable
private fun ExtensionRow(extension: OpenVsxExtension, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClickLabel = "Show ${extension.title}", onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ExtensionIcon(extension, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    extension.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (extension.verified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Outlined.Verified, contentDescription = "Verified publisher", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                facts(extension),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            extension.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * One extension, whole, and where it goes: a button for each agent's VS Code. A publisher Open VSX
 * has not verified needs a second tap, after the warning.
 */
@Composable
private fun ExtensionDialog(extension: OpenVsxExtension, link: LinkState, installed: Installed?, onChanged: () -> Unit, onClose: () -> Unit) {
    val graph = LocalContext.current.graph
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf<Agent?>(null) }
    var said by remember { mutableStateOf<String?>(null) }
    var unverifiedOk by remember { mutableStateOf(extension.verified) }
    val has = { agent: Agent -> installed?.get(CloudShellInfo.key(agent)).orEmpty().any { it.id.equals(extension.id, ignoreCase = true) } }
    AlertDialog(
        onDismissRequest = { if (working == null) onClose() },
        icon = { ExtensionIcon(extension, 48.dp) },
        title = { Text(extension.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            DialogBody {
                Text("${extension.id} ${extension.version}", style = MaterialTheme.typography.bodySmall)
                Text(facts(extension), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                extension.description?.takeIf { it.isNotBlank() }?.let { Text(it) }
                if (extension.deprecated) NoticeCard("Its publisher says it is no longer kept up to date.", tone = Tone.WARN)
                if (!extension.verified) {
                    NoticeCard(
                        "Open VSX has not verified this publisher: anyone could have published it under that name. An extension can " +
                            "use everything in your Cloud Shell. Install it only if you trust it.",
                        tone = Tone.WARN,
                    )
                }
                if (link != LinkState.On) {
                    Text("Connect to install it: the agents' VS Code is in Cloud Shell. Home > Open the computer, or Computer > Connect.")
                } else {
                    Agent.entries.forEach { agent ->
                        AgentTarget(agent, installed = has(agent), working = working, enabled = working == null && installed != null) {
                            if (!unverifiedOk) {
                                unverifiedOk = true
                                said = "Tap again to install it anyway."
                                return@AgentTarget
                            }
                            working = agent
                            said = null
                            scope.launch {
                                val done = graph.link.extension(install = true, agent, extension.id, anyPublisher = !extension.verified) { said = it }
                                working = null
                                said = if (done) {
                                    "Installed in ${agent.displayName}'s VS Code. If it is open, reload it to use the " +
                                        "extension now: Tools > Reload this VS Code."
                                } else {
                                    said ?: "It was not installed. Try again."
                                }
                                if (done) onChanged()
                            }
                        }
                    }
                }
                said?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onClose, enabled = working == null) { Text("Close") } },
        dismissButton = { TextButton(onClick = { uri.openUri(extension.page) }) { Text("Open VSX page") } },
    )
}

@Composable
private fun AgentTarget(agent: Agent, installed: Boolean, working: Agent?, enabled: Boolean, onInstall: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        AgentLogo(agent, size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Text(agent.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        when {
            working == agent -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            installed -> Text("Installed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            else -> OutlinedButton(onClick = onInstall, enabled = enabled) { Text("Install") }
        }
    }
}

/** An extension's own icon from Open VSX, or its initial on a tile while there is none. */
@Composable
private fun ExtensionIcon(extension: OpenVsxExtension, size: Dp) {
    val icon by produceState(ExtensionIcons.cached(extension.id), extension.id) {
        if (value == null) value = ExtensionIcons.load(extension)
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.2f)))
    } else {
        ExtensionLogo(extension.title, size = size)
    }
}

/** Icons of the extensions shown, in memory while the app runs. */
private object ExtensionIcons {
    private val memory = LruCache<String, ImageBitmap>(ICONS_KEPT)

    fun cached(id: String): ImageBitmap? = memory.get(id)

    suspend fun load(extension: OpenVsxExtension): ImageBitmap? {
        val bytes = OpenVsx.icon(extension) ?: return null
        return withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }?.also { memory.put(extension.id, it) }
    }
}

/** Publisher, downloads and rating, in a line. */
private fun facts(extension: OpenVsxExtension): String = buildList {
    add(extension.namespace)
    add("${compact(extension.downloadCount)} downloads")
    extension.averageRating?.let { add(String.format(Locale.US, "%.1f ★", it)) }
}.joinToString(" · ")

/** 9,301,251 as 9.3M; 12,400 as 12K. */
internal fun compact(count: Long): String = when {
    count >= MILLION -> String.format(Locale.US, "%.1fM", count / MILLION.toDouble()).replace(".0M", "M")
    count >= THOUSAND -> "${count / THOUSAND}K"
    else -> count.toString()
}

private const val TYPING_MS = 400L
private const val QUERY_LIMIT = 100
private const val ICONS_KEPT = 120
private const val THOUSAND = 1_000L
private const val MILLION = 1_000_000L

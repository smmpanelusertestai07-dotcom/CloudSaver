package com.pocketide.ui.screens.project

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.bridge.BridgedPort
import com.pocketide.bridge.PortBridge
import com.pocketide.rooms.RoomTraffic
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.StatusChip
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.web.AgentWebView
import com.pocketide.ui.web.WebViewHolder
import com.pocketide.ui.web.rememberWebViewHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What Preview shows for one session; hoisted so tab switches keep the page. */
@Stable
class PreviewState internal constructor(internal val web: WebViewHolder) {
    /** Dev servers found on the phone with their reach; null before the first check. */
    var found by mutableStateOf<Map<Int, Reach>?>(null)
        internal set
    var scanning by mutableStateOf(false)
        internal set
    var showing by mutableStateOf<BridgedPort?>(null)
        internal set
    /** The first dev server the agent announces opens by itself once; later ones wait for a tap. */
    internal var autoOpened = false
}

/** Preview state for [sessionId]; its page is closed and its port un-exposed when the caller leaves. */
@Composable
fun rememberPreviewState(sessionId: String): PreviewState {
    val graph = rememberGraph()
    val web = rememberWebViewHolder("preview:$sessionId")
    val state = remember(web) { PreviewState(web) }
    DisposableEffect(state) {
        onDispose { state.showing?.let { graph.portBridge.revoke(it.targetPort) } }
    }
    return state
}

private const val PREVIEW_EXPLAINED =
    "Preview shows a dev server running on this phone (Vite, Next, Flask…) in the phone's own browser engine. " +
        "It is local only: nothing is published. GitHub builds send their results to Media instead."

/** The Preview tab: the dev servers on the phone, and the chosen one's site to tap around in. */
@Composable
fun PreviewPanel(sessionId: String, state: PreviewState, nav: PocketNav, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val announcedBySession by graph.rooms.previewPorts.collectAsStateWithLifecycle()
    val announced = announcedBySession[sessionId].orEmpty()
    val ports = previewPorts(announced, state.found.orEmpty(), appPorts(graph.portBridge))

    val open = { port: Int -> openPort(scope, graph.portBridge, sessionId, state, port, snackbar) }
    LaunchedEffect(state, announced) { scanInto(graph.portBridge, state, announced) }
    LaunchedEffect(state, ports) {
        if (state.autoOpened || state.showing != null) return@LaunchedEffect
        autoOpenPort(ports)?.let { port ->
            state.autoOpened = true
            open(port)
        }
    }

    val showing = state.showing
    if (showing != null) {
        val reach = ports.firstOrNull { it.port == showing.targetPort }?.reach
        Column(modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Port ${showing.targetPort}", style = MaterialTheme.typography.titleSmall)
                reach?.let { ReachChip(it, Modifier.padding(start = 8.dp)) }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { state.web.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
                    IconButton(onClick = { closePreview(graph.portBridge, state) }) { Icon(Icons.Filled.Close, contentDescription = "Close preview") }
                }
            }
            if (reach == Reach.WIFI) WifiWarning(showing.targetPort, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            AgentWebView(
                url = showing.entryUrl,
                holder = state.web,
                isInternal = graph.portBridge::isInternal,
                onOpenExternal = nav::openExternal,
                onNotice = { message -> scope.launch { snackbar.showSnackbar(message) } },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        return
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = "Preview") {
            Text(PREVIEW_EXPLAINED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Dev servers", Modifier.weight(1f))
            if (state.scanning) {
                CircularProgressIndicator(Modifier.size(20.dp))
            } else {
                OutlinedButton(onClick = { scope.launch { scanInto(graph.portBridge, state, announced) } }) { Text("Check again") }
            }
        }
        if (ports.isEmpty()) {
            EmptyState(
                Icons.Filled.Web,
                "No dev server running",
                "Ask the agent to start one on 127.0.0.1 (for example \"npm run dev\"), then tap Check again.",
            )
        }
        ports.forEach { port -> PortRow(port) { open(port.port) } }
    }
}

@Composable
private fun PortRow(port: PreviewPort, onOpen: () -> Unit) {
    SectionCard(title = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Port ${port.port}", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (port.fromAgent) "Started by the agent in this session" else "Found on this phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                port.reach?.let { ReachChip(it) }
            }
            FilledTonalButton(onClick = onOpen) { Text("Open") }
        }
        if (port.reach == Reach.WIFI) WifiWarning(port.port)
    }
}

@Composable
private fun ReachChip(reach: Reach, modifier: Modifier = Modifier) {
    StatusChip(reach.label, if (reach == Reach.WIFI) Tone.WARN else Tone.OK, modifier)
}

/** The warning, and the request to hand the agent so it restarts the server on 127.0.0.1. */
@Composable
private fun WifiWarning(port: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier) {
        Text(WIFI_WARNING, style = MaterialTheme.typography.bodySmall, color = toneColor(Tone.WARN))
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("Request for the agent", loopbackRequest(port)))
        }) { Text("Copy the request for the agent") }
    }
}

private fun openPort(scope: CoroutineScope, bridge: PortBridge, sessionId: String, state: PreviewState, port: Int, snackbar: SnackbarHostState) {
    scope.launch {
        // Exposing binds a listening socket: not on the main thread.
        attempt { withContext(Dispatchers.IO) { bridge.expose(port, RoomTraffic.previewPurpose(sessionId)) } }
            .onSuccess { exposed ->
                state.web.retry()
                state.showing = exposed
            }
            .onFailure { snackbar.showSnackbar("Could not open port $port: ${plainReason(it)}") }
    }
}

private fun closePreview(bridge: PortBridge, state: PreviewState) {
    state.showing?.let { bridge.revoke(it.targetPort) }
    state.web.destroy()
    state.web.retry()
    state.showing = null
}

/** Ports the app itself serves: agent screens, terminals and the bridge's own listeners. */
private fun appPorts(bridge: PortBridge): Set<Int> =
    bridge.exposed.flatMap { port ->
        if (RoomTraffic.isPreview(port.purpose)) listOf(port.bridgePort) else listOf(port.bridgePort, port.targetPort)
    }.toSet()

private suspend fun scanInto(bridge: PortBridge, state: PreviewState, announced: List<Int>) {
    state.scanning = true
    try {
        attempt { bridge.listeners(COMMON_DEV_PORTS + announced) }.onSuccess { state.found = reachByPort(it) }
    } finally {
        state.scanning = false
    }
}

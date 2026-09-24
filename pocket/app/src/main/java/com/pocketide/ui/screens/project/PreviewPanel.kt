package com.pocketide.ui.screens.project

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.bridge.BridgedPort
import com.pocketide.bridge.PortBridge
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.nav.PocketNav
import com.pocketide.ui.web.AgentWebView
import com.pocketide.ui.web.WebViewHolder
import com.pocketide.ui.web.rememberWebViewHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/** What Preview shows for one session; hoisted so tab switches keep the page. */
@Stable
class PreviewState internal constructor(internal val web: WebViewHolder) {
    var probed by mutableStateOf<Set<Int>?>(null)
        internal set
    var probing by mutableStateOf(false)
        internal set
    var showing by mutableStateOf<BridgedPort?>(null)
        internal set
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
        "It is local only: nothing is published, and nobody else can open it. GitHub builds send their results to Media instead."

/** The Preview tab: pick a dev-server port, see the site, tap around it like a real one. */
@Composable
fun PreviewPanel(sessionId: String, state: PreviewState, nav: PocketNav, snackbar: SnackbarHostState, modifier: Modifier = Modifier) {
    val graph = rememberGraph()
    val scope = rememberCoroutineScope()
    val announcedBySession by graph.rooms.previewPorts.collectAsStateWithLifecycle()
    val announced = announcedBySession[sessionId].orEmpty()

    LaunchedEffect(state) {
        if (state.probed == null) probeInto(state)
    }

    val showing = state.showing
    if (showing != null) {
        Column(modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Port ${showing.targetPort} on this phone", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { state.web.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
                IconButton(onClick = {
                    graph.portBridge.revoke(showing.targetPort)
                    state.web.destroy()
                    state.showing = null
                }) { Icon(Icons.Filled.Close, contentDescription = "Close preview") }
            }
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

    val ports = previewPorts(announced, state.probed.orEmpty(), appPorts(graph.portBridge))
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = "Preview") {
            Text(PREVIEW_EXPLAINED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Dev servers", Modifier.weight(1f))
            if (state.probing) {
                CircularProgressIndicator(Modifier.size(20.dp))
            } else {
                OutlinedButton(onClick = { scope.launch { probeInto(state) } }) { Text("Check again") }
            }
        }
        if (ports.isEmpty()) {
            EmptyState(
                Icons.Filled.Web,
                "No dev server running",
                "Ask the agent to start one (for example \"npm run dev\"), then tap Check again.",
            )
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ports.forEach { port ->
                    FilterChip(
                        selected = false,
                        onClick = {
                            scope.launch {
                                attempt { graph.portBridge.expose(port, "preview") }
                                    .onSuccess { state.showing = it }
                                    .onFailure { snackbar.showSnackbar("Could not open port $port: ${plainReason(it)}") }
                            }
                        },
                        label = { Text(if (port in announced) "$port · from the agent" else "$port") },
                    )
                }
            }
        }
    }
}

/** Ports the app itself serves: agent screens, terminals and the bridge's own listeners. */
private fun appPorts(bridge: PortBridge): Set<Int> =
    bridge.exposed.flatMap { port ->
        if (port.purpose == "preview") listOf(port.bridgePort) else listOf(port.bridgePort, port.targetPort)
    }.toSet()

private suspend fun probeInto(state: PreviewState) {
    state.probing = true
    try {
        state.probed = listeningPorts(COMMON_DEV_PORTS)
    } finally {
        state.probing = false
    }
}

/** Which of [ports] accept a connection on 127.0.0.1 (the computer shares the phone's loopback). */
private suspend fun listeningPorts(ports: List<Int>): Set<Int> = coroutineScope {
    ports.map { port -> async(Dispatchers.IO) { port.takeIf { isListening(it) } } }.awaitAll().filterNotNull().toSet()
}

private fun isListening(port: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
    true
} catch (_: IOException) {
    false
}

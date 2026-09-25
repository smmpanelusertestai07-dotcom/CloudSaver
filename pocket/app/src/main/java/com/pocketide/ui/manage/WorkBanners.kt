package com.pocketide.ui.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.pocketide.AppGraph
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import com.pocketide.ui.nav.PocketNav
import kotlinx.coroutines.launch

/** Android holds back PocketIDE's background work (R23): worth knowing, never in the way. */
@Composable
fun BackgroundLimitNote(text: String, modifier: Modifier = Modifier) {
    NoteBanner(Icons.Outlined.HourglassEmpty, text, Tone.WARN, modifier)
}

/**
 * A room stopped without the owner asking: why ("Nothing was lost" is in the sentence), Resume,
 * and a close button when the stop can be dismissed.
 */
@Composable
fun StopBanner(text: String, onResume: (() -> Unit)?, onDismiss: (() -> Unit)?, modifier: Modifier = Modifier) {
    NoteBanner(Icons.Outlined.PauseCircle, text, Tone.NEUTRAL, modifier, onDismiss) {
        if (onResume != null) TextButton(onClick = onResume) { Text("Resume") }
    }
}

@Composable
private fun NoteBanner(
    icon: ImageVector,
    text: String,
    tone: Tone,
    modifier: Modifier,
    onDismiss: (() -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.primary else toneColor(tone)
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.large, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 4.dp))
                if (onDismiss != null) IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Dismiss") }
            }
            if (action != null) Box(Modifier.align(Alignment.End)) { action() }
        }
    }
}

/**
 * Opens the rooms of [agentIds] again on their sessions: the first on screen, the others in
 * the background. Returns false when none of them has an open session to go back to.
 */
fun resumeRooms(graph: AppGraph, agentIds: List<String>, nav: PocketNav): Boolean {
    val targets = WorkText.resumeTargets(agentIds, graph.sessions.all.value) { agentId ->
        runCatching { graph.sessions.activeSession(agentId) }.getOrNull()
    }
    val (_, firstSession) = targets.firstOrNull() ?: return false
    targets.drop(1).forEach { (agentId, sessionId) ->
        graph.scope.launch { attempt { graph.rooms.open(agentId, sessionId) } }
    }
    nav.agent(firstSession)
    return true
}

package com.pocketide.ui.screens.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketide.AppGraph
import com.pocketide.core.Redact
import com.pocketide.graph
import com.pocketide.model.AgentInfo
import com.pocketide.ui.theme.Brand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** The app graph, from the composition's context. */
@Composable
fun rememberGraph(): AppGraph {
    val context = LocalContext.current
    return remember(context) { context.graph }
}

/** Runs [block], turning any failure except cancellation into a [Result]. */
suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}

/** One short sentence from an error, with anything secret-looking removed. */
fun plainReason(e: Throwable): String {
    val message = e.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    if (message.isEmpty()) return "Something went wrong. Please try again."
    return Redact.text(message).take(240)
}

/**
 * Runs an owner's action in the background and reports it in the snackbar: [done] on success
 * (if any), "[failed]: <reason>" otherwise. Never crashes the screen.
 */
fun CoroutineScope.act(
    snackbar: SnackbarHostState,
    failed: String,
    done: String? = null,
    block: suspend () -> Unit,
) {
    launch {
        attempt { block() }
            .onSuccess { if (done != null) snackbar.showSnackbar(done) }
            .onFailure { snackbar.showSnackbar("$failed: ${plainReason(it)}") }
    }
}

/** An uppercase label above a group, as in the design. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

/** A rounded letter tile for an agent, coloured by which agent it is. */
@Composable
fun AgentMark(agent: AgentInfo?, fallbackId: String, size: Dp = 44.dp) {
    val name = agent?.displayName ?: fallbackId
    val (background, foreground) = agentColors(agent?.id ?: fallbackId)
    Surface(shape = RoundedCornerShape(size / 4), color = background, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?",
                color = foreground,
                fontWeight = FontWeight.Bold,
                style = if (size >= 40.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
            )
        }
    }
}

private fun agentColors(agentId: String): Pair<Color, Color> = when (agentId) {
    "claude" -> Color(0xFFE8A07E) to Color(0xFF3A1606)
    "codex" -> Color(0xFFDDE1E7) to Color(0xFF111418)
    "antigravity" -> Color(0xFF7C8CF8) to Color.White
    else -> Brand.TileFlat to Brand.Mark
}

/** The name the owner knows an agent by. */
fun agentName(agent: AgentInfo?, agentId: String): String = agent?.displayName ?: agentId

/** A centred explanation when a list is empty. */
@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) action()
    }
}

/** A yes/no question before anything that cannot simply be undone. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmLabel, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

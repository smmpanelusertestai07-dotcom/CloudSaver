package com.pocketide.ui.screens.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.agents.SearchEntry
import com.pocketide.graph
import com.pocketide.ui.components.ExtensionLogo
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import com.pocketide.ui.shell.Gap
import com.pocketide.ui.shell.NoticeCard
import com.pocketide.ui.shell.OutlinedCard
import com.pocketide.ui.shell.ShellPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Finds agents (and any other extension) on Open VSX and installs one with a tap. Only verified
 * publishers' packages install, each checked against Open VSX's checksum and signature; the list
 * starts with the most downloaded AI extensions.
 */
@Composable
fun AddAgentsScreen(onBack: () -> Unit) {
    val graph = LocalContext.current.graph
    val installed by graph.agents.installed.collectAsStateWithLifecycle()
    val activity by graph.agents.activity.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchEntry>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Tone>?>(null) }
    val have = installed.map { it.id }.toSet()

    fun search(text: String) {
        searching = true
        message = null
        scope.launch {
            try {
                results = graph.agents.search(text)
                if (results.isEmpty()) message = "Nothing on Open VSX matches \"$text\"." to Tone.NEUTRAL
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message = "Open VSX could not be reached. Check the connection and try again." to Tone.ERROR
            } finally {
                searching = false
            }
        }
    }
    LaunchedEffect(Unit) { search(STARTING_QUERY) }

    ShellPage {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Text("Add agents", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        }
        Gap(8.dp)
        Text(
            "From Open VSX, the open extension registry. Only publishers Open VSX has verified can be installed, " +
                "and every package is checked against its published checksum and signature.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Gap(12.dp)
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search, for example cline, kilo, continue") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search(query.ifBlank { STARTING_QUERY }) }),
        )
        Gap(12.dp)
        message?.let { (text, tone) ->
            NoticeCard(text, tone = tone)
            Gap(12.dp)
        }
        if (searching) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
        if (results.isNotEmpty()) {
            OutlinedCard {
                results.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val refusal = graph.agents.refusal(entry.namespace, entry.name, entry.verified)
                    ResultRow(
                        entry = entry,
                        installed = entry.id in have,
                        refusal = refusal,
                        busy = activity != null,
                        onInstall = {
                            message = null
                            scope.launch {
                                try {
                                    val done = graph.agents.install(entry.namespace, entry.name)
                                    message = "${done.displayName} is installed. It is on Home." to Tone.OK
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    message = (failure.message ?: "It could not be installed.") to Tone.ERROR
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ResultRow(entry: SearchEntry, installed: Boolean, refusal: String?, busy: Boolean, onInstall: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ExtensionLogo(entry.displayName ?: entry.name, size = 40.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.displayName ?: entry.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.namespace, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.verified) {
                        Icon(Icons.Outlined.Verified, contentDescription = "Verified publisher", tint = toneColor(Tone.OK), modifier = Modifier.size(16.dp))
                    }
                    Text("· ${downloads(entry.downloadCount)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                installed -> Text("Installed", style = MaterialTheme.typography.labelLarge, color = toneColor(Tone.OK))
                refusal == null -> FilledTonalButton(onClick = onInstall, enabled = !busy) { Text("Install") }
            }
        }
        entry.description?.takeIf { it.isNotBlank() }?.let {
            Gap(6.dp)
            Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (!installed && refusal != null) {
            Gap(6.dp)
            Text(refusal, style = MaterialTheme.typography.bodySmall, color = toneColor(Tone.WARN))
        }
    }
}

private fun downloads(count: Long): String = when {
    count >= MILLION -> String.format(Locale.ENGLISH, "%.1fM downloads", count / MILLION.toDouble())
    count >= THOUSAND -> String.format(Locale.ENGLISH, "%.0fK downloads", count / THOUSAND.toDouble())
    else -> "$count downloads"
}

private const val MILLION = 1_000_000L
private const val THOUSAND = 1_000L

/** What the list shows before the owner types: AI agents. */
private const val STARTING_QUERY = "ai agent"

package com.pocketide.ui.screens.data

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.AppGraph
import com.pocketide.model.AgentInfo
import com.pocketide.rooms.ConfigChange
import com.pocketide.rooms.sentence
import com.pocketide.rooms.shownValue
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.manage.ActionRunner
import com.pocketide.ui.manage.Hint

/**
 * Settings that can run code (hooks, tool servers, permission rules, environment variables)
 * that an agent added in its room. Every room start takes them out again; here the owner reads
 * each one and keeps it (written back at every start) or lets it go. Only this card keeps one.
 * Below them, the skills, subagents, commands and command rules that can run code, which wait on
 * this phone until the owner keeps them for the Drive backup, or removes them.
 */
@Composable
internal fun ConfigChangesCard(graph: AppGraph, agents: List<AgentInfo>, runner: ActionRunner) {
    val waiting by graph.rooms.configChanges.collectAsStateWithLifecycle()
    val kept by graph.rooms.keptConfig.collectAsStateWithLifecycle()
    val held by graph.sync.heldFiles.collectAsStateWithLifecycle()
    val name = { agentId: String -> agents.firstOrNull { it.id == agentId }?.displayName ?: agentId }
    SectionCard(null) {
        Hint(
            "Hooks, tool servers, permission rules and environment variables can run code. PocketIDE writes them " +
                "again at every room start, so a change an agent makes waits here until you keep it.",
        )
        if (waiting.isEmpty() && kept.isEmpty() && held.isEmpty()) Hint("No agent has changed them.")
        WaitingChanges(waiting, name, graph, runner)
        if (kept.isNotEmpty()) KeptChanges(kept, name, graph, runner)
        if (held.isNotEmpty()) HeldFilesSection(held, name, graph, runner)
    }
}

@Composable
private fun WaitingChanges(waiting: List<ConfigChange>, name: (String) -> String, graph: AppGraph, runner: ActionRunner) {
    waiting.forEachIndexed { index, change ->
        if (index > 0) HorizontalDivider()
        ChangeRow(change, name(change.agentId)) {
            val key = actionKey(change)
            if (change.keepable) {
                TextButton(onClick = {
                    runner.run(key, done = "Kept. It is written back at every start.") { graph.rooms.keepConfigChange(change) }
                }, enabled = !runner.isBusy(key)) { Text("Keep") }
            }
            TextButton(onClick = { runner.run(key, done = "Let go.") { graph.rooms.dropConfigChange(change) } }, enabled = !runner.isBusy(key)) {
                Text("Let go")
            }
        }
    }
}

@Composable
private fun KeptChanges(kept: List<ConfigChange>, name: (String) -> String, graph: AppGraph, runner: ActionRunner) {
    ConfigSubtitle("Kept by you")
    kept.forEach { change ->
        ChangeRow(change, name(change.agentId)) {
            val key = actionKey(change)
            TextButton(onClick = {
                runner.run(key, done = "No longer kept.") { graph.rooms.stopKeepingConfigChange(change) }
            }, enabled = !runner.isBusy(key)) { Text("Stop keeping") }
        }
    }
}

@Composable
private fun ChangeRow(change: ConfigChange, agentName: String, actions: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(change.sentence(agentName), style = MaterialTheme.typography.bodyLarge)
        ConfigPath(change.file)
        ConfigText(change.shownValue())
        if (!change.keepable) Hint(notKeepable(change))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
    }
}

@Composable
internal fun ConfigSubtitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
}

/** A file under a room's home, as the owner finds it there. */
@Composable
internal fun ConfigPath(path: String) {
    Text(
        "~/$path",
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A setting or a file's text as it was found: its lines as they are, scrolled sideways. */
@Composable
internal fun ConfigText(text: String) {
    SelectionContainer {
        Text(
            text.take(MAX_SHOWN),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            softWrap = false,
        )
    }
}

private fun notKeepable(change: ConfigChange) =
    if (change.file == ".codex/config.toml") {
        "PocketIDE cannot keep a server list written as one inline table; ask the agent to add the server again with codex mcp add."
    } else {
        "PocketIDE cannot write this back as it was written (the file held the wrong kind of value there); ask the agent to add it again."
    }

private fun actionKey(change: ConfigChange) = "config:${change.agentId}:${change.file}:${change.place}:${change.key}:${change.value.hashCode()}"

private const val MAX_SHOWN = 2_000

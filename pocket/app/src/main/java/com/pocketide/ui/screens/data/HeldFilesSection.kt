package com.pocketide.ui.screens.data

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketide.AppGraph
import com.pocketide.core.Redact
import com.pocketide.sync.HeldFile
import com.pocketide.ui.manage.ActionRunner
import com.pocketide.ui.manage.ConfirmDialog
import com.pocketide.ui.manage.Hint

/**
 * Skills, subagents, commands and command rules that can run code: each waits on this phone,
 * out of the Drive backup, until the owner keeps that version or removes the file from its room.
 */
@Composable
internal fun HeldFilesSection(held: List<HeldFile>, name: (String) -> String, graph: AppGraph, runner: ActionRunner) {
    var removing by remember { mutableStateOf<HeldFile?>(null) }
    ConfigSubtitle("Waiting before your Drive backup")
    Hint(
        "Skills, subagents, commands and command rules that can run code stay on this phone until you keep them. " +
            "Kept, they go to your Drive backup and come back on your other phones.",
    )
    held.forEachIndexed { index, file ->
        if (index > 0) HorizontalDivider()
        HeldRow(file, name(file.agentId)) {
            val key = heldKey(file)
            TextButton(onClick = {
                runner.run(key, done = "Kept. It goes to your Drive backup at the next sync.") { graph.sync.keepHeldFile(file) }
            }, enabled = !runner.isBusy(key)) { Text("Keep") }
            TextButton(onClick = { removing = file }, enabled = !runner.isBusy(key)) { Text("Remove") }
        }
    }
    removing?.let { file ->
        ConfirmDialog(
            title = "Remove this file?",
            text = "PocketIDE deletes ~/${file.path} from ${name(file.agentId)}'s room. This cannot be undone.",
            confirmLabel = "Remove",
            destructive = true,
            onConfirm = { runner.run(heldKey(file), done = "Removed.") { graph.sync.removeHeldFile(file) } },
            onDismiss = { removing = null },
        )
    }
}

@Composable
private fun HeldRow(file: HeldFile, agentName: String, actions: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(heldName(file, agentName), style = MaterialTheme.typography.bodyLarge)
        file.reasons.forEach { Text("It $it.", style = MaterialTheme.typography.bodyMedium) }
        ConfigPath(file.path)
        if (file.text.isEmpty()) Hint("It holds no text to show here.") else ConfigText(Redact.text(file.text))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = actions)
    }
}

/** What a held file is, as the owner knows it: "Claude Code's subagent helper". */
private fun heldName(file: HeldFile, agentName: String): String {
    val parts = file.path.split('/')
    val stem = parts.last().removeSuffix(".md")
    val skill = parts.getOrNull(2)?.takeIf { parts.size >= SKILL_FILE_DEPTH }
    return when (parts.getOrNull(1)) {
        "skills" -> when {
            skill == null -> "A file in $agentName's skills folder"
            parts.size == SKILL_FILE_DEPTH && parts.last().equals("SKILL.md", ignoreCase = true) -> "$agentName's skill $skill"
            else -> "A file in $agentName's skill $skill"
        }
        "agents" -> "$agentName's subagent $stem"
        "commands" -> "$agentName's command /$stem"
        else -> "$agentName's command rules"
    }
}

private fun heldKey(file: HeldFile) = "held:${file.agentId}:${file.path}:${file.sha256}"

/** ".claude/skills/<skill>/SKILL.md": a skill's own file sits this many names deep. */
private const val SKILL_FILE_DEPTH = 4

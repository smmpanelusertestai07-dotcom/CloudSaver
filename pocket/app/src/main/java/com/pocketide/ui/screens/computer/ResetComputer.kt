package com.pocketide.ui.screens.computer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import com.pocketide.linux.ResetPlan
import com.pocketide.model.SessionStatus
import com.pocketide.sync.SyncStatus
import com.pocketide.ui.components.Tone
import com.pocketide.ui.manage.OutlivingWork
import com.pocketide.ui.manage.PlainError
import com.pocketide.ui.manage.attempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The key of a running reset, shared with the Computer screen's runner so its buttons wait. */
internal const val RESET_KEY = "reset"

/** The reset sheet's words, built from what the computer's reset really deletes and keeps. */
internal object ResetText {
    const val SAVING = "Pushing each session's unfinished work to GitHub and backing up your data to Drive…"
    const val STARTED = "The computer is being rebuilt. You can follow it on the Computer screen."

    fun sheet(plan: ResetPlan): String = buildString {
        append("Goes:\n")
        plan.removes.forEach { append("• ").append(it).append('\n') }
        append("\nStays:\n")
        plan.keeps.forEach { append("• ").append(it).append('\n') }
        append("\nFirst, each session's unfinished work is pushed to GitHub and your data is backed up to Drive. ")
        append("Then Ubuntu is built again from the same recipe: a big download, Wi-Fi is best.")
    }

    fun unsaved(problems: List<String>): String =
        "Some work is not in GitHub or Drive yet:\n" + problems.joinToString("\n") { "• $it" } +
            "\n\nThe reset keeps it on this phone, but it has no copy anywhere else until it is saved."
}

private sealed interface ResetStep {
    data object Confirm : ResetStep
    data object Saving : ResetStep
    data class Unsaved(val problems: List<String>) : ResetStep
}

/**
 * "Reset computer", from the Computer screen and from Settings: the sheet from
 * [com.pocketide.linux.Computer.resetPlan], then unfinished session work pushed and the vault
 * upload confirmed, and only then the reset. Work that could not be saved is named, and the
 * owner decides. The reset outlives the screen, so it runs in the app's scope.
 */
@Composable
internal fun ResetComputerDialogs(graph: AppGraph, onClose: () -> Unit, onNotice: (String, Tone) -> Unit) {
    var step by remember { mutableStateOf<ResetStep>(ResetStep.Confirm) }
    val plan = remember(graph) { graph.computer.resetPlan() }

    fun start(saveFirst: Boolean) {
        if (!OutlivingWork.claim(RESET_KEY)) {
            onClose()
            return
        }
        step = ResetStep.Saving
        graph.scope.launch {
            try {
                val problems = if (saveFirst) saveBeforeReset(graph) else emptyList()
                if (problems.isNotEmpty()) {
                    step = ResetStep.Unsaved(problems)
                    return@launch
                }
                onClose()
                onNotice(ResetText.STARTED, Tone.OK)
                graph.computer.reset()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onClose()
                onNotice(PlainError.of(e), Tone.ERROR)
            } finally {
                OutlivingWork.release(RESET_KEY)
            }
        }
    }

    when (val current = step) {
        ResetStep.Confirm -> ResetQuestion("Reset the computer?", ResetText.sheet(plan), "Reset", onClose) { start(saveFirst = true) }
        ResetStep.Saving -> AlertDialog(
            // The work runs on; closing now would hide its outcome.
            onDismissRequest = {},
            title = { Text("Saving your work first") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(ResetText.SAVING, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
        is ResetStep.Unsaved ->
            ResetQuestion("Reset anyway?", ResetText.unsaved(current.problems), "Reset anyway", onClose) { start(saveFirst = false) }
    }
}

/** A confirm sheet that stays open on confirm, so the next step can show in its place. */
@Composable
private fun ResetQuestion(title: String, text: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Stops the rooms, pushes every open session's branch and waits for a sync. Returns what could
 * not be saved, in plain sentences; empty when everything is in GitHub and Drive.
 */
private suspend fun saveBeforeReset(graph: AppGraph): List<String> {
    graph.rooms.stopAll()
    val problems = mutableListOf<String>()
    graph.sessions.all.value
        .filter { it.status == SessionStatus.OPEN && it.deletedAt == null }
        .forEach { session ->
            val why = attempt { graph.sessions.autosave(session.id) }.fold({ it }, PlainError::of)
            if (why != null) problems += "\"${session.title}\": $why"
        }
    attempt { graph.sync.syncNow() }.onFailure { problems += "Your data: ${PlainError.of(it)}" }
    when (val status = graph.sync.status.value) {
        is SyncStatus.Waiting -> problems += "Your data: ${status.why}"
        is SyncStatus.Error -> problems += "Your data: ${status.why}"
        else -> Unit
    }
    return problems.distinct()
}

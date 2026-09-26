package com.pocketide.ui.screens.data

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketide.AppGraph
import com.pocketide.ui.manage.ConfirmDialog
import com.pocketide.ui.manage.ErrorNote
import com.pocketide.ui.manage.Hint
import com.pocketide.ui.manage.ManagePage
import com.pocketide.ui.manage.MemoryDocument
import com.pocketide.ui.manage.MemoryFile
import com.pocketide.ui.manage.MemoryFiles
import com.pocketide.ui.manage.PlainError
import com.pocketide.ui.manage.SavePlan
import com.pocketide.ui.manage.attempt
import com.pocketide.ui.manage.rememberActionRunner
import com.pocketide.ui.nav.PocketNav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The file being edited, kept by what identifies it; its sizes are read again when the list returns. */
internal val MemoryFileSaver: Saver<MemoryFile?, Any> = listSaver(
    save = { file -> if (file == null) emptyList() else listOf(file.agentId, file.label, file.file.path, file.exists, file.bytes, file.primary, file.synced) },
    restore = { parts ->
        parts.takeIf { it.size == MEMORY_FILE_PARTS }?.let {
            MemoryFile(
                agentId = it[0] as String,
                label = it[1] as String,
                file = File(it[2] as String),
                exists = it[3] as Boolean,
                bytes = it[4] as Long,
                primary = it[5] as Boolean,
                synced = it[6] as Boolean,
            )
        }
    },
)

private const val MEMORY_FILE_PARTS = 7

/**
 * The owner's text, and the file as it was opened, survive the app lock and the process being
 * ended. A text too large for Android's saved state is not kept; the file is read again instead.
 */
internal val DraftSaver: Saver<String?, String> = Saver(
    save = { text -> text?.takeIf { it.length <= MAX_SAVED_CHARS } },
    restore = { it },
)

private const val MAX_SAVED_CHARS = 64 * 1024

/**
 * A plain text editor for one instructions or memory file. The app's own block (between its
 * marker comments) is shown but not editable, and is written back exactly as it is on disk.
 */
@Composable
fun MemoryEditor(graph: AppGraph, home: File, file: MemoryFile, agentName: String, nav: PocketNav, onClose: () -> Unit) {
    val runner = rememberActionRunner()
    val path = file.file.path
    // The file as it was opened: a save checks it against the disk, so the agent's own writes are not lost.
    var opened by rememberSaveable(path, stateSaver = DraftSaver) { mutableStateOf<String?>(null) }
    var loadError by remember(path) { mutableStateOf<String?>(null) }
    var before by rememberSaveable(path, stateSaver = DraftSaver) { mutableStateOf<String?>(null) }
    var after by rememberSaveable(path, stateSaver = DraftSaver) { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var changedOnDisk by remember { mutableStateOf(false) }

    LaunchedEffect(path) {
        if (opened != null && before != null && after != null) return@LaunchedEffect
        attempt { withContext(Dispatchers.IO) { MemoryFiles.read(home, file.file) } }
            .onSuccess { text ->
                val document = MemoryDocument.parse(text)
                opened = text
                if (before == null) before = document.before
                if (after == null) after = document.after
            }
            .onFailure { loadError = localError(it) }
    }

    val document = remember(opened) { opened?.let(MemoryDocument::parse) }
    val changed = document != null && (before != document.before || after != document.after)
    val close = { if (changed) confirmDiscard = true else onClose() }
    BackHandler(onBack = close)

    fun save(overwrite: Boolean) {
        val current = document ?: return
        runner.run(
            key = "save",
            onFailure = { runner.say(localError(it)) },
            onSuccess = { outcome: SaveOutcome ->
                when (outcome) {
                    is SaveOutcome.Saved -> {
                        opened = outcome.document.join()
                        before = outcome.document.before
                        after = outcome.document.after
                        runner.say("Saved.")
                    }
                    SaveOutcome.ChangedOnDisk -> changedOnDisk = true
                    is SaveOutcome.Refused -> runner.say(outcome.why)
                }
            },
        ) { save(graph, home, file.file, current, before.orEmpty(), after.orEmpty(), overwrite) }
    }

    ManagePage(file.label, nav, runner, onBack = close) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(agentName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Hint(
                    if (file.synced) {
                        "Saved here and synced, encrypted, to your Drive. The agent reads it at the start of each session."
                    } else {
                        "Saved on this phone only. The agent reads it at the start of each session."
                    },
                )
            }
        }
        when {
            loadError != null -> item { ErrorNote(loadError.orEmpty()) }
            document == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            else -> {
                item { EditorField(if (document.managed == null) "Text" else "Your text above", before.orEmpty()) { before = it } }
                document.managed?.let { block ->
                    item { ManagedBlock(block) }
                    item { EditorField("Your text below", after.orEmpty()) { after = it } }
                }
                item {
                    Button(
                        onClick = { save(overwrite = false) },
                        enabled = changed && !runner.isBusy("save"),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (runner.isBusy("save")) "Saving…" else "Save") }
                }
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard your changes?",
            text = "What you typed since the last save is lost.",
            confirmLabel = "Discard",
            destructive = true,
            onConfirm = onClose,
            onDismiss = { confirmDiscard = false },
        )
    }
    if (changedOnDisk) {
        ConfirmDialog(
            title = "This file changed",
            text = "$agentName wrote to it after you opened it. Replace it with your text, or cancel, copy what you " +
                "typed, and open the file again to see the new version.",
            confirmLabel = "Replace",
            destructive = true,
            onConfirm = { save(overwrite = true) },
            onDismiss = { changedOnDisk = false },
        )
    }
}

private sealed interface SaveOutcome {
    data class Saved(val document: MemoryDocument) : SaveOutcome
    data object ChangedOnDisk : SaveOutcome
    data class Refused(val why: String) : SaveOutcome
}

/** Reads the file as it is now and writes the owner's parts around the app's current block. */
private suspend fun save(
    graph: AppGraph,
    home: File,
    file: File,
    loaded: MemoryDocument,
    before: String,
    after: String,
    overwrite: Boolean,
): SaveOutcome = withContext(Dispatchers.IO) {
    val now = MemoryDocument.parse(MemoryFiles.read(home, file))
    when (val plan = MemoryDocument.plan(loaded, now, before, after, overwrite)) {
        SavePlan.ChangedOnDisk -> SaveOutcome.ChangedOnDisk
        is SavePlan.Refused -> SaveOutcome.Refused(plan.why)
        is SavePlan.Write -> {
            MemoryFiles.save(home, file, plan.text)
            attempt { graph.sync.requestSync("instructions edited") }
            SaveOutcome.Saved(MemoryDocument.parse(plan.text))
        }
    }
}

/** Local file errors carry their own plain sentence; anything else gets the general one. */
private fun localError(error: Throwable): String = PlainError.local(error, "Could not open or save this file.")

@Composable
private fun EditorField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = 6,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ManagedBlock(block: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Written by PocketIDE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Hint("The app keeps these rules up to date (where to run builds, the phone's tools). They are kept as they are when you save.")
            Text(
                block.trimEnd(),
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = MaterialTheme.typography.bodySmall.fontSize),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

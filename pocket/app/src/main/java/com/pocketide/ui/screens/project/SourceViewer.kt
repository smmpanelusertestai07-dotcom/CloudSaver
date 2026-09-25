package com.pocketide.ui.screens.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pocketide.model.SessionRecord
import com.pocketide.ui.manage.DiskEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A session's files, read-only: a simple folder tree and a viewer with syntax colours (§8c).
 * Opens on [startFile] when given (a row of Changes), else on the worktree's top folder.
 * Back goes from a file to its folder, and from a folder to the one above.
 */
@Composable
fun SessionFilesDialog(session: SessionRecord, startFile: String?, onDismiss: () -> Unit) {
    val graph = rememberGraph()
    val tree = remember(session.id) {
        SourceTree(graph.dirs.work, graph.dirs.worktree(session.agentId, session.projectId, session.id))
    }
    var folder by rememberSaveable(session.id) { mutableStateOf(startFile?.let(SourcePaths::parent).orEmpty()) }
    var file by rememberSaveable(session.id) { mutableStateOf(startFile) }
    val back: () -> Unit = {
        if (file != null) {
            file = null
        } else if (folder.isNotEmpty()) {
            folder = SourcePaths.parent(folder)
        } else {
            onDismiss()
        }
    }

    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val atTop = file == null && folder.isEmpty()
                    IconButton(onClick = back) {
                        Icon(
                            if (atTop) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (atTop) "Close" else "Back",
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            file?.let(SourcePaths::name) ?: folder.ifEmpty { "Files" }.let(SourcePaths::name),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "${session.branch} · read-only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val open = file
                    if (open != null) {
                        FileBody(tree, open)
                    } else {
                        FolderBody(tree, folder, onFolder = { folder = SourcePaths.child(folder, it) }, onFile = { file = SourcePaths.child(folder, it) })
                    }
                }
            }
        }
    }
}

/** A folder as read; [entries] is null when the folder is not there or is reached through a link. */
private class Listing(val entries: List<DiskEntry>?)

@Composable
private fun FolderBody(tree: SourceTree, folder: String, onFolder: (String) -> Unit, onFile: (String) -> Unit) {
    val listing by produceState<Listing?>(null, folder) {
        value = Listing(withContext(Dispatchers.IO) { tree.list(folder) })
    }
    val list = (listing ?: return Spinner()).entries
    when {
        list == null -> Note(if (folder.isEmpty()) WORKTREE_MISSING else "This folder is not in the session now.")
        list.isEmpty() -> Note("This folder is empty.")
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.name }) { entry -> EntryRow(entry, onFolder, onFile) }
        }
    }
}

@Composable
private fun Spinner() {
    Box(Modifier.fillMaxSize().padding(24.dp)) { CircularProgressIndicator() }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp))
}

@Composable
private fun EntryRow(entry: DiskEntry, onFolder: (String) -> Unit, onFile: (String) -> Unit) {
    val opens = entry.kind == DiskEntry.Kind.FOLDER || entry.kind == DiskEntry.Kind.FILE
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = opens) {
                if (entry.kind == DiskEntry.Kind.FOLDER) onFolder(entry.name) else onFile(entry.name)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = when (entry.kind) {
            DiskEntry.Kind.FOLDER -> Icons.Filled.Folder
            DiskEntry.Kind.LINK -> Icons.Filled.Link
            else -> Icons.AutoMirrored.Filled.InsertDriveFile
        }
        val tint = if (opens) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            entry.name,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val side = when (entry.kind) {
            DiskEntry.Kind.FILE -> WorkFormat.bytes(entry.bytes)
            DiskEntry.Kind.LINK -> "link, not opened"
            DiskEntry.Kind.OTHER -> "not a file"
            DiskEntry.Kind.FOLDER -> null
        }
        side?.let {
            Spacer(Modifier.width(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A file as read, with its colours worked out off the main thread. */
private class LoadedFile(val view: SourceView, val tokens: List<Token>)

@Composable
private fun FileBody(tree: SourceTree, path: String) {
    val loaded by produceState<LoadedFile?>(null, path) {
        value = withContext(Dispatchers.IO) {
            val view = tree.read(path)
            val syntax = SyntaxColors.forPath(path)
            LoadedFile(view, if (view is SourceView.Text && syntax != null) SyntaxColors.tokens(view.text, syntax) else emptyList())
        }
    }
    val file = loaded ?: return Spinner()
    when (val view = file.view) {
        SourceView.Missing -> Note("This file is not in the session now: the agent deleted or moved it.")
        is SourceView.Unreadable -> Note(view.why)
        is SourceView.Text -> SourceText(view.text, file.tokens)
    }
}

@Composable
private fun SourceText(text: String, tokens: List<Token>) {
    val lines = remember(text) { SyntaxColors.lines(text) }
    val palette = SyntaxPalette(
        keyword = MaterialTheme.colorScheme.primary,
        string = MaterialTheme.colorScheme.tertiary,
        comment = MaterialTheme.colorScheme.onSurfaceVariant,
        number = MaterialTheme.colorScheme.secondary,
    )
    val code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    val numberWidth = (lines.size.toString().length * NUMBER_DIGIT_DP + NUMBER_GAP_DP).dp
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(lines.size) { index ->
            val range = lines[index]
            Row(Modifier.fillMaxWidth().padding(end = 12.dp)) {
                Text(
                    (index + 1).toString(),
                    modifier = Modifier.widthIn(min = numberWidth).padding(end = 10.dp),
                    style = code,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.End,
                )
                SelectionContainer(Modifier.weight(1f)) {
                    Text(colored(text, range, SyntaxColors.inLine(tokens, range.first, range.last + 1), palette), style = code)
                }
            }
        }
    }
}

private class SyntaxPalette(val keyword: Color, val string: Color, val comment: Color, val number: Color)

private fun colored(text: String, range: IntRange, tokens: List<Token>, palette: SyntaxPalette): AnnotatedString = buildAnnotatedString {
    append(text, range.first, range.last + 1)
    tokens.forEach { token ->
        val style = when (token.kind) {
            TokenKind.KEYWORD -> SpanStyle(color = palette.keyword)
            TokenKind.STRING -> SpanStyle(color = palette.string)
            TokenKind.COMMENT -> SpanStyle(color = palette.comment, fontStyle = FontStyle.Italic)
            TokenKind.NUMBER -> SpanStyle(color = palette.number)
        }
        addStyle(style, token.start, token.end)
    }
}

private const val NUMBER_DIGIT_DP = 8
private const val NUMBER_GAP_DP = 12
private const val WORKTREE_MISSING =
    "This session's files are not on this phone now. After Put on main its worktree is removed; the code is on main in GitHub."

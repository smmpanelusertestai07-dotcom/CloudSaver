package com.pocketide.ui.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketide.core.Ist
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import com.pocketide.ui.nav.PocketNav

/** Readable line length on wide screens and in landscape. */
private val MaxContentWidth = 640.dp

/** The smallest height a tappable row may have. */
private val MinTouch = 48.dp

/**
 * A pushed manage screen: a title bar with back and the scrolling content. The shell's own
 * scaffold already keeps the content inside the system bars, so no insets are added here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagePage(
    title: String,
    nav: PocketNav,
    runner: ActionRunner? = null,
    onBack: () -> Unit = nav::back,
    actions: @Composable RowScope.() -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    header: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = actions,
                windowInsets = WindowInsets(0),
            )
        },
        snackbarHost = { if (runner != null) SnackbarHost(runner.snackbar) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding)) {
            header?.invoke()
            ManageList(Modifier.weight(1f), state, content)
        }
    }
}

/** The centred, width-limited list every manage screen scrolls in. */
@Composable
fun ManageList(modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState(), content: LazyListScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier.widthIn(max = MaxContentWidth).fillMaxSize(),
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** An upper-case label above a group, as on the Activity concept. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 12.dp, start = 4.dp).semantics { heading() },
    )
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/** A coloured dot and a sentence. */
@Composable
fun ToneLine(told: Told, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 7.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(toneColor(told.tone)),
        )
        Spacer(Modifier.width(10.dp))
        Text(told.text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** A row that opens another screen. */
@Composable
fun NavRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Hint(subtitle)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A web page that opens in Chrome, outside the app. */
@Composable
fun LinkRow(label: String, url: String, nav: PocketNav, note: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .clip(RoundedCornerShape(12.dp))
            .clickable { nav.openExternal(url) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            Hint(note ?: url.removePrefix("https://"))
        }
        Spacer(Modifier.width(12.dp))
        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Opens in Chrome", tint = MaterialTheme.colorScheme.primary)
    }
}

/** "As of 24 Sep 2026, 4:05 PM" with a refresh button, or a spinner while loading. */
@Composable
fun AsOfLine(at: Long?, loading: Boolean, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Hint(if (at != null) "As of ${Ist.dateTime(at)}" else if (loading) "Checking…" else "Not checked yet", Modifier.weight(1f))
        if (loading) {
            CircularProgressIndicator(Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Refresh") }
        }
    }
}

@Composable
fun ErrorNote(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = toneColor(Tone.ERROR), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = toneColor(Tone.ERROR))
    }
}

/** A thin bar for "used of total"; turns amber past 80 % and red when full. */
@Composable
fun UsageMeter(fraction: Double, modifier: Modifier = Modifier) {
    val clamped = fraction.coerceIn(0.0, 1.0).toFloat()
    val tone = when {
        fraction >= 1.0 -> Tone.ERROR
        fraction >= 0.8 -> Tone.WARN
        else -> Tone.OK
    }
    LinearProgressIndicator(
        progress = { clamped },
        modifier = modifier.fillMaxWidth().height(6.dp),
        color = toneColor(tone),
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** A quiet message in place of a list that has nothing in it. */
@Composable
fun EmptyNote(icon: ImageVector, title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(14.dp).size(28.dp))
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Hint(text)
    }
}

/** A yes/no question before anything that deletes or cannot be undone. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    extra: @Composable (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                extra?.invoke()
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                },
                enabled = confirmEnabled,
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

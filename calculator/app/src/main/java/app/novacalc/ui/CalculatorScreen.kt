package app.novacalc.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.novacalc.R
import app.novacalc.engine.CalcException
import kotlinx.coroutines.launch

/** The calculator page wired to the view model, with clipboard, snackbar and history sheet. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CalculatorRoute(
    viewModel: CalculatorViewModel,
    state: CalculatorUiState,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var historyOpen by remember { mutableStateOf(false) }
    val copiedMessage = stringResource(R.string.copied)
    val pasteInvalidMessage = stringResource(R.string.pasted_invalid)

    fun copy() {
        val text = state.copyText ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("NovaCalc", text))
        scope.launch { snackbar.showSnackbar(copiedMessage) }
    }

    fun paste() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrBlank() || !viewModel.paste(text)) {
            scope.launch { snackbar.showSnackbar(pasteInvalidMessage) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier.semantics { testTagsAsResourceId = true },
    ) { innerPadding ->
        CalculatorScreen(
            state = state,
            onAction = viewModel::onAction,
            onOpenSettings = onOpenSettings,
            onOpenHistory = { historyOpen = true },
            onCopy = ::copy,
            onPaste = ::paste,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        )
    }

    if (historyOpen) {
        HistorySheet(
            entries = state.history,
            onDismiss = { historyOpen = false },
            onUseResult = { viewModel.useHistoryResult(it); historyOpen = false },
            onUseExpression = { viewModel.useHistoryExpression(it); historyOpen = false },
            onDelete = { viewModel.deleteHistoryEntry(it.id) },
            onClearAll = viewModel::clearHistory,
        )
    }
}

@Composable
fun CalculatorScreen(
    state: CalculatorUiState,
    onAction: (CalcAction) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = state.settings.haptics
    BoxWithConstraints(modifier = modifier) {
        val landscape = maxWidth > maxHeight
        val sciHeight = maxHeight * 0.27f
        Column(modifier = Modifier.fillMaxSize()) {
            TopRow(state, onAction, onOpenSettings, onOpenHistory)
            Display(
                state = state,
                onCopy = onCopy,
                onPaste = onPaste,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                compact = landscape,
            )
            ControlsRow(state, onAction, onCopy, haptics)
            if (landscape) {
                Row(
                    modifier = Modifier.weight(2.2f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ScientificPad(
                        inverse = state.inverse, angleUnit = state.angleUnit, hasMemory = state.memory != null,
                        haptics = haptics, onAction = onAction, modifier = Modifier.weight(1.25f).fillMaxHeight(),
                    )
                    BasicPad(haptics = haptics, onAction = onAction, modifier = Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier = Modifier.weight(if (state.scientific) 2.6f else 1.85f).fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                ) {
                    AnimatedVisibility(
                        visible = state.scientific,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        ScientificPad(
                            inverse = state.inverse, angleUnit = state.angleUnit, hasMemory = state.memory != null,
                            haptics = haptics, onAction = onAction,
                            modifier = Modifier.fillMaxWidth().height(sciHeight),
                        )
                    }
                    BasicPad(haptics = haptics, onAction = onAction, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun TopRow(
    state: CalculatorUiState,
    onAction: (CalcAction) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenHistory, modifier = Modifier.testTag("open_history")) {
            Icon(painterResource(R.drawable.ic_history), contentDescription = stringResource(R.string.history))
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open_settings")) {
            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
        }
        Spacer(Modifier.weight(1f))
        state.memory?.let { memory ->
            AssistChip(
                onClick = { onAction(CalcAction.MemoryRecall) },
                label = { Text("M = $memory", maxLines = 1) },
                modifier = Modifier.padding(end = 6.dp).semantics { contentDescription = "Memory holds $memory" }.testTag("memory_chip"),
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
        AssistChip(
            onClick = { onAction(CalcAction.ToggleAngleUnit) },
            label = { Text(if (state.angleUnit == app.novacalc.engine.AngleUnit.DEGREES) "DEG" else "RAD") },
            modifier = Modifier.testTag("angle_chip"),
        )
    }
}

@Composable
private fun Display(
    state: CalculatorUiState,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    val errorText = state.error?.let { errorMessage(it) }

    @OptIn(ExperimentalFoundationApi::class)
    Box(
        modifier = modifier
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onLongClick = { menu = true },
                onClick = {},
            )
            .testTag("display"),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.End,
        ) {
            when {
                state.result != null -> {
                    ScrollingText(state.expression, sizeFor(state.expression, 26, 20, 16), c.onSurfaceVariant, FontWeight.Normal, "expression")
                    Spacer(Modifier.height(4.dp))
                    ScrollingText(state.result, sizeFor(state.result, if (compact) 44 else 60, 44, 32), c.onSurface, FontWeight.Medium, "result")
                }
                errorText != null -> {
                    ScrollingText(state.expression, sizeFor(state.expression, 34, 26, 20), c.onSurfaceVariant, FontWeight.Normal, "expression")
                    Spacer(Modifier.height(4.dp))
                    ScrollingText(errorText, 26.sp, c.error, FontWeight.Medium, "error")
                }
                else -> {
                    val shown = state.expression.ifEmpty { "0" }
                    ScrollingText(shown, sizeFor(shown, if (compact) 36 else 44, 34, 26), c.onSurface, FontWeight.Normal, "expression")
                    Spacer(Modifier.height(4.dp))
                    ScrollingText(state.preview, 28.sp, c.onSurfaceVariant, FontWeight.Normal, "preview")
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy_result)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null) },
                enabled = state.copyText != null,
                onClick = { menu = false; onCopy() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.paste)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_content_paste), contentDescription = null) },
                onClick = { menu = false; onPaste() },
            )
        }
    }
}

private fun sizeFor(text: String, large: Int, medium: Int, small: Int): TextUnit = when {
    text.length <= 10 -> large.sp
    text.length <= 16 -> medium.sp
    else -> small.sp
}

@Composable
private fun ScrollingText(text: String, size: TextUnit, color: Color, weight: FontWeight, tag: String) {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text = text,
            color = color,
            fontSize = size,
            fontWeight = weight,
            lineHeight = size * 1.15f,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.End,
            modifier = Modifier.horizontalScroll(scroll).testTag(tag),
        )
    }
}

@Composable
private fun errorMessage(kind: CalcException.Kind): String = when (kind) {
    CalcException.Kind.DIVIDE_BY_ZERO -> stringResource(R.string.error_divide_by_zero)
    CalcException.Kind.DOMAIN -> stringResource(R.string.error_domain)
    CalcException.Kind.OVERFLOW -> stringResource(R.string.error_overflow)
    CalcException.Kind.SYNTAX, CalcException.Kind.EMPTY -> stringResource(R.string.error_syntax)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlsRow(state: CalculatorUiState, onAction: (CalcAction) -> Unit, onCopy: () -> Unit, haptics: Boolean) {
    val c = MaterialTheme.colorScheme
    val view = LocalView.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val sciLabel = stringResource(if (state.scientific) R.string.scientific_hide else R.string.scientific_show)
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .combinedClickable(onClick = { onAction(CalcAction.ToggleScientific) })
                .semantics { contentDescription = sciLabel }
                .testTag("toggle_scientific")
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ƒ(x)", color = c.primary, fontWeight = FontWeight.Medium, fontSize = 16.sp)
            Spacer(Modifier.width(2.dp))
            Icon(
                if (state.scientific) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null, tint = c.primary,
            )
        }
        Spacer(Modifier.weight(1f))
        if (state.copyText != null) {
            IconButton(onClick = onCopy, modifier = Modifier.testTag("copy")) {
                Icon(painterResource(R.drawable.ic_content_copy), contentDescription = stringResource(R.string.copy_result), tint = c.onSurfaceVariant)
            }
        }
        val backspaceLabel = stringResource(R.string.backspace)
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onLongClick = {
                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        onAction(CalcAction.Clear)
                    },
                    onClick = {
                        if (haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onAction(CalcAction.Backspace)
                    },
                )
                .semantics { contentDescription = backspaceLabel }
                .testTag("backspace"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_backspace), contentDescription = null, tint = c.primary)
        }
    }
}

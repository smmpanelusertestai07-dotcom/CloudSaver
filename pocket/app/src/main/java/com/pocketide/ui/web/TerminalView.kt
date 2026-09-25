package com.pocketide.ui.web

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.launch

/**
 * One terminal: its WebView, the latched modifiers of its keyboard bar and its font size.
 * Hoisted by the screen so switching tabs does not drop the shell's page.
 */
@Stable
class TerminalState internal constructor() {
    internal val web = WebViewHolder()
    var mods by mutableStateOf(Modifiers.NONE)
        internal set
    /** The terminal page's address once the room handed it out; kept so a tab switch reuses it. */
    var url by mutableStateOf<String?>(null)
    internal var fontPx by mutableIntStateOf(WebPrefs.DEFAULT_FONT)

    fun destroy() = web.destroy()
}

@Composable
fun rememberTerminalState(key: Any): TerminalState {
    val state = remember(key) { TerminalState() }
    DisposableEffect(state) { onDispose { state.destroy() } }
    return state
}

private const val PASTE_NOTE = "terminal-paste"

/**
 * The `>_` terminal: the room's shell page in the shared WebView component, with the keyboard
 * bar (Esc, ^C, Tab, Shift+Tab, Ctrl, Alt, arrows, | ~ / -, Home, End, PgUp, PgDn, Copy and the
 * font size) above the phone keyboard.
 *
 * What the page provides: `window.pocketKey(data)` writes [data] to the shell as typed
 * (required); `window.pocketText()` returns the text to copy and `window.pocketFontSize(px)`
 * sets the font size (both optional). [onNewShell] asks the room for a fresh page when this one
 * can no longer be reached.
 */
@Composable
fun TerminalView(
    url: String,
    state: TerminalState,
    isInternal: (String) -> Boolean,
    onOpenExternal: (String) -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
    onNewShell: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = rememberWebPrefs()
    var pasteNote by remember { mutableStateOf(false) }
    LaunchedEffect(prefs) {
        state.fontPx = prefs.terminalFont()
        state.web.evaluate(TerminalKeys.fontSizeCall(state.fontPx))
        pasteNote = !prefs.noteShown(PASTE_NOTE)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AgentWebView(
                url = url,
                holder = state.web,
                isInternal = isInternal,
                onOpenExternal = onOpenExternal,
                onNotice = onNotice,
                modifier = Modifier.fillMaxSize(),
                onRetry = onNewShell,
                onCreated = { view -> listenForUsedModifiers(view, url, state) },
                onPageFinished = { view, _ ->
                    view.evaluateJavascript(TerminalKeys.MODIFIER_SCRIPT, null)
                    view.evaluateJavascript(TerminalKeys.setModifiersCall(state.mods), null)
                    view.evaluateJavascript(TerminalKeys.fontSizeCall(state.fontPx), null)
                },
            )
        }
        if (pasteNote) {
            PasteNote {
                pasteNote = false
                scope.launch { prefs.markNoteShown(PASTE_NOTE) }
            }
        }
        KeyBar(
            mods = state.mods,
            onMods = { mods ->
                state.mods = mods
                state.web.evaluate(TerminalKeys.setModifiersCall(mods))
            },
            onKey = { key ->
                state.web.evaluate(TerminalKeys.pocketKeyCall(TerminalKeys.sequence(key, state.mods)))
                if (state.mods.any) {
                    state.mods = Modifiers.NONE
                    state.web.evaluate(TerminalKeys.setModifiersCall(Modifiers.NONE))
                }
            },
            onCopy = {
                state.web.evaluate(TerminalKeys.COPY_SCRIPT) { result ->
                    val text = TerminalKeys.copiedText(result)
                    if (text == null) {
                        onNotice("There is no text to copy yet.")
                    } else {
                        copyToClipboard(context, text)
                        // Android 13 and later confirm a copy themselves.
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onNotice("Copied the terminal's text.")
                    }
                }
            },
            onFont = { step ->
                state.fontPx = clampFont(state.fontPx + step)
                state.web.evaluate(TerminalKeys.fontSizeCall(state.fontPx))
                val chosen = state.fontPx
                scope.launch { prefs.setTerminalFont(chosen) }
            },
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("Terminal", text))
}

@Composable
private fun PasteNote(onDismiss: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Pasting several lines? The shell shows \">\" while a command is still open. That is normal.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Got it") }
        }
    }
}

/** The page tells the bar when a latched modifier was used by a key typed on the phone keyboard. */
private fun listenForUsedModifiers(view: WebView, url: String, state: TerminalState) {
    val origin = WebPolicy.originOf(url) ?: return
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
    // Without the channel the bar still works; its latch just clears on the next bar key instead.
    runCatching {
        WebViewCompat.addWebMessageListener(view, "pocketBar", setOf(origin)) { _, message, _, _, _ ->
            if (message.data == "used") state.mods = Modifiers.NONE
        }
    }
}

/** One scrolling row of 48 dp keys; a fade at the right edge says there are more. */
@Composable
private fun KeyBar(
    mods: Modifiers,
    onMods: (Modifiers) -> Unit,
    onKey: (TermKey) -> Unit,
    onCopy: () -> Unit,
    onFont: (Int) -> Unit,
) {
    val view = LocalView.current
    val tap = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    val scroll = rememberScrollState()
    val barColor = MaterialTheme.colorScheme.surfaceContainer
    Surface(color = barColor, tonalElevation = 2.dp) {
        Box {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModifierKey("Ctrl", "Control, applies to the next key", mods.ctrl) {
                    tap()
                    onMods(mods.copy(ctrl = !mods.ctrl))
                }
                ModifierKey("Alt", "Alt, applies to the next key", mods.alt) {
                    tap()
                    onMods(mods.copy(alt = !mods.alt))
                }
                TermKey.entries.forEach { key ->
                    BarKey(key.label, key.description) {
                        tap()
                        onKey(key)
                    }
                }
                BarKey("Copy", "Copy the terminal's text") { tap(); onCopy() }
                BarKey("A−", "Smaller text") { tap(); onFont(-FONT_STEP) }
                BarKey("A+", "Larger text") { tap(); onFont(FONT_STEP) }
            }
            if (scroll.canScrollForward) {
                Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0.88f to Color.Transparent, 1f to barColor)))
            }
        }
    }
}

private const val FONT_STEP = 2

@Composable
private fun BarKey(label: String, description: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        contentPadding = KeyPadding,
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics { contentDescription = description },
    ) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

@Composable
private fun ModifierKey(label: String, description: String, latched: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 56.dp).semantics { contentDescription = description }
    if (latched) {
        FilledTonalButton(onClick = onClick, contentPadding = KeyPadding, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = KeyPadding, modifier = modifier) { Text(label) }
    }
}

private val KeyPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)

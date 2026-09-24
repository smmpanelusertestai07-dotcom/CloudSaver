package com.pocketide.ui.web

import android.view.HapticFeedbackConstants
import android.webkit.WebView
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * One terminal: its WebView and the latched modifiers of its keyboard bar. Hoisted by the screen
 * so switching tabs does not drop the shell's page.
 */
@Stable
class TerminalState internal constructor() {
    internal val web = WebViewHolder()
    var mods by mutableStateOf(Modifiers.NONE)
        internal set
    /** The terminal page's address once the room handed it out; kept so a tab switch reuses it. */
    var url by mutableStateOf<String?>(null)

    fun destroy() = web.destroy()
}

@Composable
fun rememberTerminalState(key: Any): TerminalState {
    val state = remember(key) { TerminalState() }
    DisposableEffect(state) { onDispose { state.destroy() } }
    return state
}

/**
 * The `>_` terminal: the room's shell page in the shared WebView component, with the keyboard
 * bar (Esc, Tab, Ctrl, Alt, arrows, | ~ / -, Home, End, PgUp, PgDn) above the phone keyboard.
 * The page must define `window.pocketKey(data)`, which writes [data] to the shell as typed.
 */
@Composable
fun TerminalView(
    url: String,
    state: TerminalState,
    isInternal: (String) -> Boolean,
    onOpenExternal: (String) -> Unit,
    onNotice: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AgentWebView(
                url = url,
                holder = state.web,
                isInternal = isInternal,
                onOpenExternal = onOpenExternal,
                onNotice = onNotice,
                modifier = Modifier.fillMaxSize(),
                onCreated = { view -> listenForUsedModifiers(view, url, state) },
                onPageFinished = { view, _ ->
                    view.evaluateJavascript(TerminalKeys.MODIFIER_SCRIPT, null)
                    view.evaluateJavascript(TerminalKeys.setModifiersCall(state.mods), null)
                },
            )
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
        )
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

@Composable
private fun KeyBar(mods: Modifiers, onMods: (Modifiers) -> Unit, onKey: (TermKey) -> Unit) {
    val view = LocalView.current
    val tap = { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                OutlinedButton(
                    onClick = {
                        tap()
                        onKey(key)
                    },
                    contentPadding = KeyPadding,
                    modifier = Modifier.heightIn(min = 40.dp).widthIn(min = 44.dp).semantics { contentDescription = key.description },
                ) { Text(key.label, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

@Composable
private fun ModifierKey(label: String, description: String, latched: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.heightIn(min = 40.dp).widthIn(min = 52.dp).semantics { contentDescription = description }
    if (latched) {
        FilledTonalButton(onClick = onClick, contentPadding = KeyPadding, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = KeyPadding, modifier = modifier) { Text(label) }
    }
}

private val KeyPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)

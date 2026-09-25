package com.pocketide.ui.web

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** The keys a phone keyboard buries or lacks, in the order the bar shows them. */
enum class TermKey(val label: String, val description: String) {
    ESC("Esc", "Escape"),
    CTRL_C("^C", "Control C, stops the running command"),
    TAB("Tab", "Tab"),
    SHIFT_TAB("⇧Tab", "Shift Tab, switches Claude Code's mode"),
    UP("↑", "Up"),
    DOWN("↓", "Down"),
    LEFT("←", "Left"),
    RIGHT("→", "Right"),
    SHIFT_UP("⇧↑", "Shift Up"),
    SHIFT_DOWN("⇧↓", "Shift Down"),
    PIPE("|", "Pipe"),
    TILDE("~", "Tilde"),
    SLASH("/", "Slash"),
    DASH("-", "Dash"),
    HOME("Home", "Home"),
    END("End", "End"),
    PAGE_UP("PgUp", "Page up"),
    PAGE_DOWN("PgDn", "Page down"),
}

/** Latched modifiers. Each clears itself after the next key, so a forgotten latch cannot linger. */
data class Modifiers(val ctrl: Boolean = false, val alt: Boolean = false) {
    val any: Boolean get() = ctrl || alt

    companion object {
        val NONE = Modifiers()
    }
}

/**
 * What each key sends to the shell: the byte sequences an xterm-compatible terminal expects,
 * with xterm's modifier parameter (1 + Shift + 2·Alt + 4·Ctrl) on cursor and editing keys.
 */
object TerminalKeys {
    private const val ESC = "\u001b"

    fun sequence(key: TermKey, mods: Modifiers = Modifiers.NONE): String = when (key) {
        TermKey.ESC -> if (mods.alt) ESC + ESC else ESC
        TermKey.CTRL_C -> character('c', mods.copy(ctrl = true))
        TermKey.TAB -> if (mods.alt) ESC + "\t" else "\t"
        TermKey.SHIFT_TAB -> if (mods.alt) "$ESC$ESC[Z" else "$ESC[Z"
        TermKey.UP -> cursor('A', mods)
        TermKey.DOWN -> cursor('B', mods)
        TermKey.SHIFT_UP -> cursor('A', mods, shift = true)
        TermKey.SHIFT_DOWN -> cursor('B', mods, shift = true)
        TermKey.RIGHT -> cursor('C', mods)
        TermKey.LEFT -> cursor('D', mods)
        TermKey.HOME -> cursor('H', mods)
        TermKey.END -> cursor('F', mods)
        TermKey.PAGE_UP -> tilde(5, mods)
        TermKey.PAGE_DOWN -> tilde(6, mods)
        TermKey.PIPE -> character('|', mods)
        TermKey.TILDE -> character('~', mods)
        TermKey.SLASH -> character('/', mods)
        TermKey.DASH -> character('-', mods)
    }

    /** One typed character with the latched modifiers applied (Ctrl+C is 0x03, Alt+x is ESC x). */
    fun character(c: Char, mods: Modifiers): String {
        val base = if (mods.ctrl) ctrlOf(c) ?: c.toString() else c.toString()
        return if (mods.alt) ESC + base else base
    }

    /** The control character for [c], as terminals map it; null when there is none. */
    fun ctrlOf(c: Char): String? {
        val upper = c.uppercaseChar()
        if (upper in '@'..'_') return (upper.code - 64).toChar().toString()
        return when (c) {
            ' ', '2' -> "\u0000"
            '3' -> ESC
            '4', '|' -> "\u001c"
            '5' -> "\u001d"
            '6', '~' -> "\u001e"
            '7', '/', '-' -> "\u001f"
            '8', '?' -> "\u007f"
            else -> null
        }
    }

    /** A JavaScript call that hands [data] to the terminal page, safely quoted. */
    fun pocketKeyCall(data: String): String = "window.pocketKey&&window.pocketKey(${jsString(data)})"

    /** Tells the page which modifiers apply to the next key typed on the phone keyboard. */
    fun setModifiersCall(mods: Modifiers): String =
        "window.__pocketSetMods&&window.__pocketSetMods(${mods.ctrl},${mods.alt})"

    /**
     * A JavaScript string literal for any text: quotes, backslashes, control characters and the
     * two line separators JavaScript treats as newlines are all escaped, and "</" is split so the
     * literal can never close a script element.
     */
    fun jsString(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        var previous = ' '
        for (ch in s) {
            when {
                ch == '"' -> out.append("\\\"")
                ch == '\\' -> out.append("\\\\")
                ch == '\n' -> out.append("\\n")
                ch == '\r' -> out.append("\\r")
                ch == '\t' -> out.append("\\t")
                ch == '/' && previous == '<' -> out.append("\\/")
                ch.code < 0x20 || ch.code == 0x7f || ch == ' ' || ch == ' ' ->
                    out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                else -> out.append(ch)
            }
            previous = ch
        }
        return out.append('"').toString()
    }

    /** Tells the page the owner's font size, when it lets the bar set one. */
    fun fontSizeCall(px: Int): String = "window.pocketFontSize&&window.pocketFontSize(${clampFont(px)})"

    /**
     * Returns the text to copy: the page's own `window.pocketText()` when it has one, else the
     * selection, else the terminal rows (a canvas terminal cannot be long-pressed).
     */
    const val COPY_SCRIPT: String =
        "(function(){try{if(window.pocketText)return String(window.pocketText());" +
            "var s=String(window.getSelection?window.getSelection():'');if(s)return s;" +
            "var r=document.querySelector('.xterm-rows');return r?r.innerText:document.body.innerText;}catch(e){return '';}})()"

    /**
     * The text [COPY_SCRIPT] returned (evaluateJavascript hands back a JSON value), cut to [max]
     * characters without splitting a surrogate pair; null when there is nothing to copy.
     */
    fun copiedText(jsonResult: String?, max: Int = MAX_COPY_CHARS): String? {
        val text = try {
            (Json.parseToJsonElement(jsonResult ?: return null) as? JsonPrimitive)?.takeIf { it.isString }?.content
        } catch (_: SerializationException) {
            null
        } ?: return null
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return null
        if (trimmed.length <= max) return trimmed
        val end = if (trimmed[max - 1].isHighSurrogate()) max - 1 else max
        return trimmed.substring(0, end)
    }

    const val MAX_COPY_CHARS = 512 * 1024

    private fun modifierParam(mods: Modifiers, shift: Boolean): Int =
        1 + (if (shift) 1 else 0) + (if (mods.alt) 2 else 0) + (if (mods.ctrl) 4 else 0)

    private fun cursor(final: Char, mods: Modifiers, shift: Boolean = false): String =
        if (mods.any || shift) "$ESC[1;${modifierParam(mods, shift)}$final" else "$ESC[$final"

    private fun tilde(code: Int, mods: Modifiers): String =
        if (mods.any) "$ESC[$code;${modifierParam(mods, shift = false)}~" else "$ESC[$code~"

    /**
     * Injected into the terminal page once it loads: while Ctrl or Alt is latched on the bar,
     * the next character typed on the phone keyboard is turned into its control sequence and
     * sent through `window.pocketKey`, then the latch clears and the bar is told through the
     * `pocketBar` message channel (allowed for the terminal's own origin only).
     */
    val MODIFIER_SCRIPT: String = """
        (function () {
          if (window.__pocketSetMods) return;
          var mods = { ctrl: false, alt: false };
          function ctrlOf(c) {
            var k = c.toUpperCase().charCodeAt(0);
            if (k >= 64 && k <= 95) return String.fromCharCode(k - 64);
            switch (c) {
              case ' ': case '2': return '\u0000';
              case '3': return '\u001b';
              case '4': case '|': return '\u001c';
              case '5': return '\u001d';
              case '6': case '~': return '\u001e';
              case '7': case '/': case '-': return '\u001f';
              case '8': case '?': return '\u007f';
            }
            return c;
          }
          window.__pocketSetMods = function (ctrl, alt) { mods.ctrl = !!ctrl; mods.alt = !!alt; };
          document.addEventListener('beforeinput', function (e) {
            if (!mods.ctrl && !mods.alt) return;
            if (e.inputType !== 'insertText' || !e.data || e.data.length !== 1) return;
            var s = mods.ctrl ? ctrlOf(e.data) : e.data;
            if (mods.alt) s = '\u001b' + s;
            e.preventDefault();
            e.stopImmediatePropagation();
            mods.ctrl = false; mods.alt = false;
            if (window.pocketKey) window.pocketKey(s);
            if (window.pocketBar) window.pocketBar.postMessage('used');
          }, true);
        })();
    """.trimIndent()
}

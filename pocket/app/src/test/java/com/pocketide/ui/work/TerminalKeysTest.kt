package com.pocketide.ui.work

import com.pocketide.ui.web.Modifiers
import com.pocketide.ui.web.TermKey
import com.pocketide.ui.web.TerminalKeys
import com.pocketide.ui.web.WebPrefs
import com.pocketide.ui.web.clampFont
import com.pocketide.ui.web.clampZoom
import com.pocketide.ui.web.nextZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalKeysTest {
    private val esc = "\u001b"
    private val ctrl = Modifiers(ctrl = true)
    private val alt = Modifiers(alt = true)
    private val both = Modifiers(ctrl = true, alt = true)

    @Test
    fun plainKeysSendXtermSequences() {
        assertEquals(esc, TerminalKeys.sequence(TermKey.ESC))
        assertEquals("\t", TerminalKeys.sequence(TermKey.TAB))
        assertEquals("$esc[A", TerminalKeys.sequence(TermKey.UP))
        assertEquals("$esc[B", TerminalKeys.sequence(TermKey.DOWN))
        assertEquals("$esc[C", TerminalKeys.sequence(TermKey.RIGHT))
        assertEquals("$esc[D", TerminalKeys.sequence(TermKey.LEFT))
        assertEquals("$esc[H", TerminalKeys.sequence(TermKey.HOME))
        assertEquals("$esc[F", TerminalKeys.sequence(TermKey.END))
        assertEquals("$esc[5~", TerminalKeys.sequence(TermKey.PAGE_UP))
        assertEquals("$esc[6~", TerminalKeys.sequence(TermKey.PAGE_DOWN))
        assertEquals("|", TerminalKeys.sequence(TermKey.PIPE))
        assertEquals("~", TerminalKeys.sequence(TermKey.TILDE))
        assertEquals("/", TerminalKeys.sequence(TermKey.SLASH))
        assertEquals("-", TerminalKeys.sequence(TermKey.DASH))
    }

    @Test
    fun modifiersUseXtermParameter() {
        assertEquals("$esc[1;5A", TerminalKeys.sequence(TermKey.UP, ctrl))
        assertEquals("$esc[1;3D", TerminalKeys.sequence(TermKey.LEFT, alt))
        assertEquals("$esc[1;7C", TerminalKeys.sequence(TermKey.RIGHT, both))
        assertEquals("$esc[1;5H", TerminalKeys.sequence(TermKey.HOME, ctrl))
        assertEquals("$esc[5;5~", TerminalKeys.sequence(TermKey.PAGE_UP, ctrl))
        assertEquals("$esc[6;3~", TerminalKeys.sequence(TermKey.PAGE_DOWN, alt))
    }

    @Test
    fun altPrefixesEscapeAndCtrlMakesControlCharacters() {
        assertEquals(esc + esc, TerminalKeys.sequence(TermKey.ESC, alt))
        assertEquals("$esc\t", TerminalKeys.sequence(TermKey.TAB, alt))
        assertEquals("\t", TerminalKeys.sequence(TermKey.TAB, ctrl))
        assertEquals("\u001f", TerminalKeys.sequence(TermKey.SLASH, ctrl))
        assertEquals("\u001f", TerminalKeys.sequence(TermKey.DASH, ctrl))
        assertEquals("\u001c", TerminalKeys.sequence(TermKey.PIPE, ctrl))
        assertEquals("$esc\u001e", TerminalKeys.sequence(TermKey.TILDE, both))
        assertEquals("$esc/", TerminalKeys.sequence(TermKey.SLASH, alt))
    }

    @Test
    fun ctrlOfLettersAndSymbols() {
        assertEquals("\u0003", TerminalKeys.ctrlOf('c'))
        assertEquals("\u0003", TerminalKeys.ctrlOf('C'))
        assertEquals("\u0001", TerminalKeys.ctrlOf('a'))
        assertEquals("\u001a", TerminalKeys.ctrlOf('z'))
        assertEquals(esc, TerminalKeys.ctrlOf('['))
        assertEquals("\u0000", TerminalKeys.ctrlOf('@'))
        assertEquals("\u0000", TerminalKeys.ctrlOf(' '))
        assertEquals("\u007f", TerminalKeys.ctrlOf('?'))
        assertNull(TerminalKeys.ctrlOf('é'))
        assertEquals("é", TerminalKeys.character('é', ctrl))
        assertEquals("$esc\u0004", TerminalKeys.character('d', both))
    }

    @Test
    fun jsStringEscapesEverythingDangerous() {
        assertEquals("\"plain\"", TerminalKeys.jsString("plain"))
        assertEquals("\"a\\\"b\"", TerminalKeys.jsString("a\"b"))
        assertEquals("\"a\\\\b\"", TerminalKeys.jsString("a\\b"))
        assertEquals("\"\\n\\r\\t\"", TerminalKeys.jsString("\n\r\t"))
        assertEquals("\"\\u001b[A\"", TerminalKeys.jsString("$esc[A"))
        assertEquals("\"\\u0000\\u007f\"", TerminalKeys.jsString("\u0000\u007f"))
        assertEquals("\"\\u2028\\u2029\"", TerminalKeys.jsString("\u2028\u2029"))
        assertEquals("\"<\\/script>\"", TerminalKeys.jsString("</script>"))
        assertEquals("\"it's\"", TerminalKeys.jsString("it's"))
    }

    @Test
    fun callsAreGuardedAndQuoted() {
        assertEquals("window.pocketKey&&window.pocketKey(\"\\u0003\")", TerminalKeys.pocketKeyCall("\u0003"))
        assertEquals("window.__pocketSetMods&&window.__pocketSetMods(true,false)", TerminalKeys.setModifiersCall(ctrl))
        assertEquals("window.__pocketSetMods&&window.__pocketSetMods(false,false)", TerminalKeys.setModifiersCall(Modifiers.NONE))
    }

    @Test
    fun modifierScriptIsSelfContained() {
        val script = TerminalKeys.MODIFIER_SCRIPT
        assertTrue(script.contains("window.__pocketSetMods ="))
        assertTrue(script.contains("pocketBar.postMessage('used')"))
        assertFalse("no Kotlin template leaked into the script", script.contains("$"))
    }

    @Test
    fun agentKeysForClaudeCodeAndCodex() {
        assertEquals("\u0003", TerminalKeys.sequence(TermKey.CTRL_C))
        assertEquals("$esc\u0003", TerminalKeys.sequence(TermKey.CTRL_C, alt))
        assertEquals("$esc[Z", TerminalKeys.sequence(TermKey.SHIFT_TAB))
        assertEquals("$esc$esc[Z", TerminalKeys.sequence(TermKey.SHIFT_TAB, alt))
        assertEquals("$esc[1;2A", TerminalKeys.sequence(TermKey.SHIFT_UP))
        assertEquals("$esc[1;2B", TerminalKeys.sequence(TermKey.SHIFT_DOWN))
        // xterm: 1 + Shift 1 + Alt 2 + Ctrl 4.
        assertEquals("$esc[1;6A", TerminalKeys.sequence(TermKey.SHIFT_UP, ctrl))
        assertEquals("$esc[1;8B", TerminalKeys.sequence(TermKey.SHIFT_DOWN, both))
    }

    @Test
    fun everyKeyHasALabelAndADescriptionForTalkBack() {
        TermKey.entries.forEach { key ->
            assertTrue(key.label.isNotBlank())
            assertTrue(key.description.isNotBlank())
            assertTrue("$key sends something", TerminalKeys.sequence(key).isNotEmpty())
        }
    }

    @Test
    fun copiedTextComesBackFromJson() {
        assertEquals("ls -la\nfile", TerminalKeys.copiedText("\"ls -la\\nfile\\n\\n\""))
        assertEquals("quote \" and \\", TerminalKeys.copiedText("\"quote \\\" and \\\\\""))
        assertNull(TerminalKeys.copiedText(null))
        assertNull(TerminalKeys.copiedText("null"))
        assertNull(TerminalKeys.copiedText("\"   \""))
        assertNull("a page answering with a number", TerminalKeys.copiedText("42"))
        assertNull("a page answering with an object", TerminalKeys.copiedText("{\"a\":1}"))
        assertNull("not JSON at all", TerminalKeys.copiedText("\"unterminated"))
        assertEquals("abc", TerminalKeys.copiedText("\"abcdef\"", max = 3))
        // Never ends on half of a surrogate pair.
        assertEquals("a", TerminalKeys.copiedText("\"a\uD83D\uDE00b\"", max = 2))
        val huge = "x".repeat(TerminalKeys.MAX_COPY_CHARS + 10)
        assertEquals(TerminalKeys.MAX_COPY_CHARS, TerminalKeys.copiedText("\"$huge\"")?.length)
    }

    @Test
    fun fontSizeIsClampedBeforeItReachesThePage() {
        assertEquals("window.pocketFontSize&&window.pocketFontSize(14)", TerminalKeys.fontSizeCall(14))
        assertEquals("window.pocketFontSize&&window.pocketFontSize(10)", TerminalKeys.fontSizeCall(-400))
        assertEquals("window.pocketFontSize&&window.pocketFontSize(24)", TerminalKeys.fontSizeCall(Int.MAX_VALUE))
    }

    @Test
    fun zoomCyclesThroughTheMenuSizes() {
        assertEquals(115, nextZoom(100))
        assertEquals(125, nextZoom(115))
        assertEquals(150, nextZoom(125))
        assertEquals(100, nextZoom(150))
        assertEquals(100, nextZoom(999))
        assertEquals(125, nextZoom(120))
        assertEquals(100, clampZoom(10))
        assertEquals(150, clampZoom(400))
        assertEquals(WebPrefs.DEFAULT_FONT, clampFont(WebPrefs.DEFAULT_FONT))
    }

    @Test
    fun modifiersKnowWhenAnyIsLatched() {
        assertFalse(Modifiers.NONE.any)
        assertTrue(ctrl.any)
        assertTrue(alt.any)
    }
}

package com.pocketide.ui.work

import com.pocketide.ui.web.Modifiers
import com.pocketide.ui.web.TermKey
import com.pocketide.ui.web.TerminalKeys
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
    fun modifiersKnowWhenAnyIsLatched() {
        assertFalse(Modifiers.NONE.any)
        assertTrue(ctrl.any)
        assertTrue(alt.any)
    }
}

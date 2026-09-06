package app.novacalc.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorEditorTest {

    private fun CalculatorEditor.type(keys: String) {
        keys.forEach { k ->
            when (k) {
                in '0'..'9' -> digit(k)
                '.' -> decimalPoint()
                '+', '−', '×', '÷', '^' -> operator(k)
                '(' -> openParenthesis()
                ')' -> closeParenthesis()
                'p' -> smartParenthesis()
                '±' -> toggleSign()
                '<' -> backspace()
                '%' -> postfix("%")
                '!' -> postfix("!")
                'π' -> constant('π')
                'e' -> constant('e')
                's' -> function("sin")
                'r' -> function("sqrt")
                else -> error("unknown key $k")
            }
        }
    }

    private fun display(keys: String): String = CalculatorEditor().apply { type(keys) }.displayText(true)
    private fun engine(keys: String): String = CalculatorEditor().apply { type(keys) }.engineExpression()

    @Test fun digitsAndLeadingZeros() {
        assertEquals("7", display("7"))
        assertEquals("1,234", display("1234"))
        assertEquals("5", display("05"))
        assertEquals("0.5", display("0.5"))
        assertEquals("0.5", display(".5"))
        assertEquals("1.55", display("1.5.5"))
        assertEquals("−5", display("±5"))
        assertEquals("−0.5", display("±.5"))
    }

    @Test fun operatorsAreNormalised() {
        assertEquals("2 + 3", display("2+3"))
        assertEquals("2 × 3", display("2+×3"))
        assertEquals("", display("+"))
        assertEquals("−", display("−"))
        assertEquals("−3", display("−3"))
        assertEquals("3 × −2", display("3×−2"))
        assertEquals("2^3", display("2^3"))
        assertEquals("2 + 3", display("2++3"))
    }

    @Test fun smartParentheses() {
        assertEquals("(", display("p"))
        assertEquals("(2 + 3)", display("p2+3p"))
        assertEquals("(2 + 3) × (", display("p2+3p×p"))
        assertEquals("2(", display("2p"))
        assertEquals("(2 + (", display("p2+p"))
        assertEquals("", display(")"))
        assertEquals("(2)(", display("(2)("))
        assertEquals("2", display("2)"))
        assertEquals("(2)", display("(2))"))
    }

    @Test fun functionsAndConstants() {
        assertEquals("sin(30)", display("s30)"))
        assertEquals("√(9", display("r9"))
        assertEquals("2π", display("2π"))
        assertEquals("πe", display("πe"))
        assertEquals("sqrt(9)", engine("r9)"))
        assertEquals("sin(30)", engine("s30"))
    }

    @Test fun postfixNeedsAValue() {
        assertEquals("50%", display("50%"))
        assertEquals("5!", display("5!"))
        assertEquals("", display("%"))
        assertEquals("2 + ", display("2+%").trimEnd() + " ")
        assertEquals("(2 + 3)!", display("p2+3p!"))
    }

    @Test fun toggleSign() {
        assertEquals("−5", display("5±"))
        assertEquals("5", display("5±±"))
        assertEquals("2 + −3", display("2+3±"))
        assertEquals("2 + 3", display("2+3±±"))
        assertEquals("−(2 + 3)", display("p2+3p±"))
        assertEquals("(2 + 3)", display("p2+3p±±"))
        assertEquals("−π", display("π±"))
        assertEquals("−5!", display("5!±"))
        assertEquals("3 × −(4)", display("3p4p±"))
        assertEquals("−", display("±"))
        assertEquals("", display("±±"))
        assertEquals("2 × −", display("2×±"))
    }

    @Test fun backspace() {
        assertEquals("12", display("123<"))
        assertEquals("", display("1<"))
        assertEquals("12", display("12+<"))
        assertEquals("2", display("2s<"))
        assertEquals("−", display("−5<"))
        assertEquals("", display("−5<<"))
        assertEquals("", display("<"))
        assertEquals("5", display("5%<"))
    }

    @Test fun engineExpressionDropsDanglingTokens() {
        assertEquals("2+3", engine("2+3"))
        assertEquals("2", engine("2+"))
        assertEquals("2", engine("2×("))
        assertEquals("2×(3+4)", engine("2×(3+4"))
        assertEquals("", engine("−"))
        assertEquals("3×-5", engine("3×−5"))
        assertEquals("sin(30)", engine("s30"))
        assertEquals("", engine("s"))
    }

    @Test fun hasOperation() {
        assertFalse(CalculatorEditor.hasOperation(CalculatorEditor().apply { type("123") }.snapshot()))
        assertTrue(CalculatorEditor.hasOperation(CalculatorEditor().apply { type("1+2") }.snapshot()))
        assertTrue(CalculatorEditor.hasOperation(CalculatorEditor().apply { type("5!") }.snapshot()))
        assertTrue(CalculatorEditor.hasOperation(CalculatorEditor().apply { type("π") }.snapshot()))
    }

    @Test fun literalAndTenPowerInsertion() {
        assertEquals("12 × 5", CalculatorEditor().apply { type("12"); literal("5") }.displayText(true))
        assertEquals("12 + 5", CalculatorEditor().apply { type("12+"); literal("5") }.displayText(true))
        assertEquals("−5", CalculatorEditor().apply { type("−"); literal("5") }.displayText(true))
        assertEquals("5", CalculatorEditor().apply { type("−"); literal("-5") }.displayText(true))
        assertEquals("2 × −5", CalculatorEditor().apply { type("2×−"); literal("5") }.displayText(true))
        assertEquals("10^", CalculatorEditor().apply { tenPower() }.displayText(true))
        assertEquals("2 × 10^3", CalculatorEditor().apply { type("2"); tenPower(); type("3") }.displayText(true))
    }

    @Test fun roundTripThroughEngineString() {
        val editor = CalculatorEditor().apply { type("2×s30)+r9)−5!") }
        val restored = CalculatorEditor.fromEngineString(editor.engineExpression())
        assertEquals(editor.snapshot(), restored)
        assertEquals(emptyList<InputToken>(), CalculatorEditor.fromEngineString("hello"))
        assertEquals(listOf(InputToken.Number("1.5E+3")), CalculatorEditor.fromEngineString("1.5E+3"))
    }

    @Test fun digitLimit() {
        val editor = CalculatorEditor()
        repeat(20) { editor.digit('9') }
        assertEquals(CalculatorEditor.MAX_DIGITS, editor.displayText(false).length)
    }
}

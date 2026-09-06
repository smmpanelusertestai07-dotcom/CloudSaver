package app.novacalc.ui

import app.novacalc.engine.AngleUnit

enum class KeyStyle { DIGIT, OPERATOR, EQUALS, CLEAR, FUNCTION, SCI, SCI_ACTIVE, MEMORY }

/** A keypad key: its label, an accessibility description (also the test handle), the action it fires, and its look. */
data class CalcKey(
    val label: String,
    val description: String,
    val action: CalcAction,
    val style: KeyStyle,
    val enabled: Boolean = true,
)

private fun digit(d: Char) = CalcKey(d.toString(), d.toString(), CalcAction.Digit(d), KeyStyle.DIGIT)

val BasicKeys: List<List<CalcKey>> = listOf(
    listOf(
        CalcKey("AC", "All clear", CalcAction.Clear, KeyStyle.CLEAR),
        CalcKey("( )", "Parentheses", CalcAction.SmartParenthesis, KeyStyle.FUNCTION),
        CalcKey("%", "Percent", CalcAction.Postfix("%"), KeyStyle.FUNCTION),
        CalcKey("÷", "Divide", CalcAction.Operator('÷'), KeyStyle.OPERATOR),
    ),
    listOf(digit('7'), digit('8'), digit('9'), CalcKey("×", "Multiply", CalcAction.Operator('×'), KeyStyle.OPERATOR)),
    listOf(digit('4'), digit('5'), digit('6'), CalcKey("−", "Minus", CalcAction.Operator('−'), KeyStyle.OPERATOR)),
    listOf(digit('1'), digit('2'), digit('3'), CalcKey("+", "Plus", CalcAction.Operator('+'), KeyStyle.OPERATOR)),
    listOf(
        CalcKey("±", "Toggle sign", CalcAction.ToggleSign, KeyStyle.DIGIT),
        digit('0'),
        CalcKey(".", "Decimal point", CalcAction.DecimalPoint, KeyStyle.DIGIT),
        CalcKey("=", "Equals", CalcAction.Equals, KeyStyle.EQUALS),
    ),
)

fun scientificKeys(inverse: Boolean, angleUnit: AngleUnit, hasMemory: Boolean): List<List<CalcKey>> = listOf(
    listOf(
        CalcKey("INV", "Inverse functions", CalcAction.Inverse, if (inverse) KeyStyle.SCI_ACTIVE else KeyStyle.SCI),
        CalcKey(
            if (angleUnit == AngleUnit.DEGREES) "DEG" else "RAD",
            if (angleUnit == AngleUnit.DEGREES) "Angle unit degrees" else "Angle unit radians",
            CalcAction.ToggleAngleUnit, KeyStyle.SCI,
        ),
        if (inverse) CalcKey("sin⁻¹", "Inverse sine", CalcAction.Function("asin"), KeyStyle.SCI)
        else CalcKey("sin", "Sine", CalcAction.Function("sin"), KeyStyle.SCI),
        if (inverse) CalcKey("cos⁻¹", "Inverse cosine", CalcAction.Function("acos"), KeyStyle.SCI)
        else CalcKey("cos", "Cosine", CalcAction.Function("cos"), KeyStyle.SCI),
        if (inverse) CalcKey("tan⁻¹", "Inverse tangent", CalcAction.Function("atan"), KeyStyle.SCI)
        else CalcKey("tan", "Tangent", CalcAction.Function("tan"), KeyStyle.SCI),
    ),
    listOf(
        CalcKey("xʸ", "Power", CalcAction.Operator('^'), KeyStyle.SCI),
        if (inverse) CalcKey("10ˣ", "Ten to the power", CalcAction.TenPower, KeyStyle.SCI)
        else CalcKey("log", "Logarithm", CalcAction.Function("log"), KeyStyle.SCI),
        if (inverse) CalcKey("eˣ", "Exponential", CalcAction.Function("exp"), KeyStyle.SCI)
        else CalcKey("ln", "Natural logarithm", CalcAction.Function("ln"), KeyStyle.SCI),
        if (inverse) CalcKey("∛", "Cube root", CalcAction.Function("cbrt"), KeyStyle.SCI)
        else CalcKey("√", "Square root", CalcAction.Function("sqrt"), KeyStyle.SCI),
        CalcKey("x²", "Square", CalcAction.Postfix("²"), KeyStyle.SCI),
    ),
    listOf(
        CalcKey("x!", "Factorial", CalcAction.Postfix("!"), KeyStyle.SCI),
        CalcKey("1/x", "Reciprocal", CalcAction.Postfix("⁻¹"), KeyStyle.SCI),
        CalcKey("π", "Pi", CalcAction.Constant('π'), KeyStyle.SCI),
        CalcKey("e", "Euler's number", CalcAction.Constant('e'), KeyStyle.SCI),
        CalcKey("|x|", "Absolute value", CalcAction.Function("abs"), KeyStyle.SCI),
    ),
    listOf(
        CalcKey("MC", "Memory clear", CalcAction.MemoryClear, KeyStyle.MEMORY, enabled = hasMemory),
        CalcKey("MR", "Memory recall", CalcAction.MemoryRecall, KeyStyle.MEMORY, enabled = hasMemory),
        CalcKey("M+", "Memory add", CalcAction.MemoryAdd, KeyStyle.MEMORY),
        CalcKey("M−", "Memory subtract", CalcAction.MemorySubtract, KeyStyle.MEMORY),
        CalcKey("MS", "Memory store", CalcAction.MemoryStore, KeyStyle.MEMORY),
    ),
)

package app.novacalc.engine

/** One unit of what the user has typed. [engine] is what the evaluator reads; [display] is what the screen shows. */
sealed class InputToken {
    abstract val engine: String
    abstract fun display(grouping: Boolean): String

    /** A number literal in progress: digits, at most one '.', optional leading '-'. "-" alone is allowed while typing. */
    data class Number(val text: String) : InputToken() {
        val isComplete: Boolean get() = text.any { it.isDigit() }
        override val engine: String get() = text
        override fun display(grouping: Boolean): String = NumberFormatter.formatLiteral(text, grouping)
    }

    /** Binary operator: '+', '−', '×', '÷', '^'. */
    data class Operator(val symbol: Char) : InputToken() {
        override val engine: String get() = symbol.toString()
        override fun display(grouping: Boolean): String = if (symbol == '^') "^" else " $symbol "
    }

    /** A function call; the opening parenthesis is part of the token. */
    data class Function(val name: String) : InputToken() {
        override val engine: String get() = "$name("
        override fun display(grouping: Boolean): String = when (name) {
            "sqrt" -> "√("
            "cbrt" -> "∛("
            "asin" -> "sin⁻¹("
            "acos" -> "cos⁻¹("
            "atan" -> "tan⁻¹("
            "abs" -> "abs("
            else -> "$name("
        }
    }

    data object LParen : InputToken() {
        override val engine: String get() = "("
        override fun display(grouping: Boolean): String = "("
    }

    data object RParen : InputToken() {
        override val engine: String get() = ")"
        override fun display(grouping: Boolean): String = ")"
    }

    /** 'π' or 'e'. */
    data class Constant(val symbol: Char) : InputToken() {
        override val engine: String get() = symbol.toString()
        override fun display(grouping: Boolean): String = symbol.toString()
    }

    /** "%", "!", "²", "⁻¹". */
    data class Postfix(val symbol: String) : InputToken() {
        override val engine: String get() = symbol
        override fun display(grouping: Boolean): String = symbol
    }

    /** True for tokens after which a binary operator or postfix may follow. */
    val endsValue: Boolean
        get() = when (this) {
            is Number -> isComplete
            is RParen, is Constant, is Postfix -> true
            else -> false
        }
}

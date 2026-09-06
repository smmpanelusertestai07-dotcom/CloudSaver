package app.novacalc.engine

/**
 * The typing model: a list of [InputToken]s plus the rules that keep it well
 * formed (no "3++4", no "0005", smart parentheses, sign toggling that wraps a
 * whole group). Pure Kotlin, fully unit-tested.
 */
class CalculatorEditor(initial: List<InputToken> = emptyList()) {

    private val tokens = ArrayList<InputToken>(initial)

    val isEmpty: Boolean get() = tokens.isEmpty()
    fun snapshot(): List<InputToken> = tokens.toList()

    fun replaceWith(list: List<InputToken>) {
        tokens.clear()
        tokens.addAll(list)
    }

    fun clear() = tokens.clear()

    private val last: InputToken? get() = tokens.lastOrNull()
    private val lastIndex: Int get() = tokens.size - 1

    val openParens: Int get() = openParens(tokens)

    /** Appends a digit. Returns false when the literal is already at its maximum length. */
    fun digit(d: Char): Boolean {
        val l = last
        if (l is InputToken.Number) {
            val t = l.text
            if (t.count { it.isDigit() } >= MAX_DIGITS) return false
            val newText = when (t) {
                "0" -> d.toString()
                "-0" -> "-$d"
                else -> t + d
            }
            tokens[lastIndex] = InputToken.Number(newText)
        } else {
            if (tokens.size >= MAX_TOKENS) return false
            tokens += InputToken.Number(d.toString())
        }
        return true
    }

    fun decimalPoint(): Boolean {
        val l = last
        if (l is InputToken.Number) {
            if (l.text.contains('.') || l.text.contains('E')) return false
            tokens[lastIndex] = InputToken.Number(if (l.text == "-") "-0." else l.text + ".")
        } else {
            if (tokens.size >= MAX_TOKENS) return false
            tokens += InputToken.Number("0.")
        }
        return true
    }

    /** Binary operator: '+', '−', '×', '÷', '^'. A leading or doubled '−' becomes a negative literal. */
    fun operator(op: Char): Boolean {
        val l = last
        return when {
            l == null || l is InputToken.LParen || l is InputToken.Function ||
                (l is InputToken.Operator && op == '−' && l.symbol in "×÷^") -> {
                if (op == '−') { tokens += InputToken.Number("-"); true } else false
            }
            l is InputToken.Operator -> { tokens[lastIndex] = InputToken.Operator(op); true }
            l is InputToken.Number && !l.isComplete -> false
            else -> { if (tokens.size >= MAX_TOKENS) false else { tokens += InputToken.Operator(op); true } }
        }
    }

    fun function(name: String): Boolean {
        if (tokens.size >= MAX_TOKENS) return false
        tokens += InputToken.Function(name)
        return true
    }

    /** One key for both parentheses: closes when something is open and a value just ended, otherwise opens. */
    fun smartParenthesis(): Boolean {
        val l = last
        return if (l != null && l.endsValue && openParens > 0) closeParenthesis() else openParenthesis()
    }

    fun openParenthesis(): Boolean {
        if (tokens.size >= MAX_TOKENS) return false
        tokens += InputToken.LParen
        return true
    }

    fun closeParenthesis(): Boolean {
        val l = last ?: return false
        if (!l.endsValue || openParens == 0) return false
        tokens += InputToken.RParen
        return true
    }

    /** Inserts a ready number (memory recall, history reuse, paste), multiplying implicitly after a value. */
    fun literal(text: String): Boolean {
        if (tokens.size + 2 > MAX_TOKENS) return false
        val l = last
        if (l is InputToken.Number && !l.isComplete) {
            // "−" followed by a recalled value: merge the sign into the literal.
            tokens[lastIndex] = InputToken.Number(if (text.startsWith("-")) text.substring(1) else "-$text")
            return true
        }
        if (l != null && l.endsValue) tokens += InputToken.Operator('×')
        tokens += InputToken.Number(text)
        return true
    }

    /** The 10ˣ key: inserts "10^" (with an implicit × after a value). */
    fun tenPower(): Boolean {
        if (tokens.size + 3 > MAX_TOKENS) return false
        val l = last
        if (l != null && l.endsValue) tokens += InputToken.Operator('×')
        tokens += InputToken.Number("10")
        tokens += InputToken.Operator('^')
        return true
    }

    /** Appends already-parsed tokens (paste). */
    fun append(list: List<InputToken>): Boolean {
        if (tokens.size + list.size > MAX_TOKENS) return false
        val l = last
        val first = list.firstOrNull() ?: return false
        if (l != null && l.endsValue && (first is InputToken.Number || first is InputToken.Constant || first is InputToken.Function || first is InputToken.LParen)) {
            tokens += InputToken.Operator('×')
        }
        tokens.addAll(list)
        return true
    }

    fun constant(symbol: Char): Boolean {
        if (tokens.size >= MAX_TOKENS) return false
        tokens += InputToken.Constant(symbol)
        return true
    }

    fun postfix(symbol: String): Boolean {
        val l = last ?: return false
        if (!l.endsValue) return false
        tokens += InputToken.Postfix(symbol)
        return true
    }

    /** Negates the operand that was just typed: a literal, a constant, or a whole parenthesised group. */
    fun toggleSign(): Boolean {
        when (val l = last) {
            null, is InputToken.Operator, is InputToken.LParen, is InputToken.Function -> {
                if (tokens.size >= MAX_TOKENS) return false
                tokens += InputToken.Number("-")
            }
            is InputToken.Number -> {
                val text = l.text
                when {
                    text == "-" -> tokens.removeAt(lastIndex)
                    text.startsWith("-") -> tokens[lastIndex] = InputToken.Number(text.substring(1))
                    else -> tokens[lastIndex] = InputToken.Number("-$text")
                }
            }
            else -> {
                val start = operandStart(lastIndex)
                val before = tokens.getOrNull(start - 1)
                if (before is InputToken.Number && before.text == "-") {
                    tokens.removeAt(start - 1)
                } else {
                    if (tokens.size + 2 > MAX_TOKENS) return false
                    // "3(4)" negated must stay a product, so keep the multiplication explicit.
                    if (before != null && before.endsValue) tokens.add(start, InputToken.Operator('×'))
                    val at = if (before != null && before.endsValue) start + 1 else start
                    tokens.add(at, InputToken.Number("-"))
                }
            }
        }
        return true
    }

    fun backspace() {
        when (val l = last) {
            null -> Unit
            is InputToken.Number -> {
                if (l.text.length > 1) tokens[lastIndex] = InputToken.Number(l.text.dropLast(1))
                else tokens.removeAt(lastIndex)
            }
            else -> tokens.removeAt(lastIndex)
        }
    }

    /** Index of the first token of the operand that ends at [end]. */
    private fun operandStart(end: Int): Int {
        var i = end
        while (i >= 0) {
            when (tokens[i]) {
                is InputToken.Postfix -> i--
                is InputToken.RParen -> {
                    var depth = 0
                    var j = i
                    while (j >= 0) {
                        when (tokens[j]) {
                            is InputToken.RParen -> depth++
                            is InputToken.LParen, is InputToken.Function -> depth--
                            else -> Unit
                        }
                        if (depth == 0) break
                        j--
                    }
                    return if (j < 0) 0 else j
                }
                else -> return i
            }
        }
        return 0
    }

    fun displayText(grouping: Boolean): String = displayText(tokens, grouping)

    fun engineExpression(): String = engineExpression(tokens)

    companion object {
        const val MAX_DIGITS = 16
        const val MAX_TOKENS = 120

        fun displayText(tokens: List<InputToken>, grouping: Boolean): String =
            buildString { tokens.forEach { append(it.display(grouping)) } }.trim()

        fun openParens(tokens: List<InputToken>): Int {
            var open = 0
            for (t in tokens) when (t) {
                is InputToken.LParen, is InputToken.Function -> open++
                is InputToken.RParen -> open--
                else -> Unit
            }
            return open
        }

        /** Trailing operators and unfinished tokens are dropped and open groups are closed, so "2×(3+" evaluates as 2×3. */
        fun engineExpression(tokens: List<InputToken>): String {
            val list = tokens.toMutableList()
            while (list.isNotEmpty()) {
                val l = list[list.size - 1]
                val dangling = l is InputToken.Operator || l is InputToken.Function || l is InputToken.LParen ||
                    (l is InputToken.Number && !l.isComplete)
                if (dangling) list.removeAt(list.size - 1) else break
            }
            val sb = StringBuilder()
            list.forEach { sb.append(it.engine) }
            repeat(openParens(list)) { sb.append(')') }
            return sb.toString()
        }

        /** True when the expression is more than a bare literal, i.e. worth previewing. */
        fun hasOperation(tokens: List<InputToken>): Boolean =
            tokens.any { it !is InputToken.Number && it !is InputToken.LParen && it !is InputToken.RParen } ||
                tokens.count { it is InputToken.Number } > 1

        /** Rebuilds tokens from a saved [engineExpression]; unreadable text yields an empty list. */
        fun fromEngineString(text: String): List<InputToken> {
            if (text.isBlank()) return emptyList()
            return try {
                Tokenizer.tokenize(text).map { tok ->
                    when (tok) {
                        is Tok.Num -> InputToken.Number(tok.text)
                        is Tok.Op -> InputToken.Operator(if (tok.c == '-') '−' else tok.c)
                        is Tok.Func -> InputToken.Function(tok.name)
                        is Tok.Const -> InputToken.Constant(tok.c)
                        is Tok.Postfix -> InputToken.Postfix(tok.s)
                        Tok.LParen -> InputToken.LParen
                        Tok.RParen -> InputToken.RParen
                    }
                }.let(::mergeFunctionParens)
            } catch (e: CalcException) {
                emptyList()
            }
        }

        /** The tokenizer emits Func then LParen; the editor keeps them as one token. */
        private fun mergeFunctionParens(list: List<InputToken>): List<InputToken> {
            val out = ArrayList<InputToken>(list.size)
            var i = 0
            while (i < list.size) {
                val t = list[i]
                if (t is InputToken.Function && i + 1 < list.size && list[i + 1] is InputToken.LParen) {
                    out += t
                    i += 2
                } else {
                    out += t
                    i++
                }
            }
            return out
        }
    }
}

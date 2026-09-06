package app.novacalc.engine

import java.math.BigDecimal

/** Lexical tokens understood by [Evaluator]. */
sealed class Tok {
    data class Num(val value: BigDecimal, val text: String) : Tok()
    /** Binary operator: '+', '-', '×', '÷', '^'. */
    data class Op(val c: Char) : Tok()
    /** Postfix operator: "!", "%", "²", "⁻¹". */
    data class Postfix(val s: String) : Tok()
    data class Func(val name: String) : Tok()
    /** 'π' or 'e'. */
    data class Const(val c: Char) : Tok()
    data object LParen : Tok()
    data object RParen : Tok()
}

object Tokenizer {
    val FUNCTIONS = setOf(
        "sin", "cos", "tan", "asin", "acos", "atan",
        "sinh", "cosh", "tanh", "ln", "log", "sqrt", "cbrt", "abs", "exp"
    )

    fun tokenize(input: String): List<Tok> {
        val out = ArrayList<Tok>()
        val s = input
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == ' ' || c == ',' || c == ' ' || c == ' ' -> i++
                c.isDigit() || c == '.' -> {
                    val start = i
                    i++
                    while (i < s.length && (s[i].isDigit() || s[i] == '.' ||
                            (s[i] == ',' && i + 1 < s.length && s[i + 1].isDigit()))) i++
                    // Scientific notation as produced by BigDecimal.toString(): 1.5E+10, 2E-7
                    if (i < s.length && s[i] == 'E') {
                        val j = i + 1
                        val hasSign = j < s.length && (s[j] == '+' || s[j] == '-')
                        val digitAt = if (hasSign) j + 1 else j
                        if (digitAt < s.length && s[digitAt].isDigit()) {
                            i = digitAt
                            while (i < s.length && s[i].isDigit()) i++
                        }
                    }
                    val text = s.substring(start, i).replace(",", "")
                    if (text.count { it == '.' } > 1 || text == ".") throw CalcException(CalcException.Kind.SYNTAX)
                    val value = try {
                        BigDecimal(if (text.startsWith(".")) "0$text" else text)
                    } catch (e: NumberFormatException) {
                        throw CalcException(CalcException.Kind.SYNTAX)
                    }
                    out += Tok.Num(value, text)
                }
                c == '+' -> { out += Tok.Op('+'); i++ }
                c == '-' || c == '−' -> { out += Tok.Op('-'); i++ }
                c == '×' || c == '*' -> { out += Tok.Op('×'); i++ }
                c == '÷' || c == '/' -> { out += Tok.Op('÷'); i++ }
                c == '^' -> { out += Tok.Op('^'); i++ }
                c == '(' -> { out += Tok.LParen; i++ }
                c == ')' -> { out += Tok.RParen; i++ }
                c == '!' -> { out += Tok.Postfix("!"); i++ }
                c == '%' -> { out += Tok.Postfix("%"); i++ }
                c == '²' -> { out += Tok.Postfix("²"); i++ }
                c == '⁻' -> {
                    if (i + 1 < s.length && s[i + 1] == '¹') { out += Tok.Postfix("⁻¹"); i += 2 }
                    else throw CalcException(CalcException.Kind.SYNTAX)
                }
                c == 'π' -> { out += Tok.Const('π'); i++ }
                c == '√' -> { out += Tok.Func("sqrt"); i++ }
                c == '∛' -> { out += Tok.Func("cbrt"); i++ }
                c in 'a'..'z' || c in 'A'..'Z' -> {
                    val start = i
                    while (i < s.length && (s[i] in 'a'..'z' || s[i] in 'A'..'Z')) i++
                    when (val name = s.substring(start, i).lowercase()) {
                        "e" -> out += Tok.Const('e')
                        "pi" -> out += Tok.Const('π')
                        in FUNCTIONS -> out += Tok.Func(name)
                        else -> throw CalcException(CalcException.Kind.SYNTAX)
                    }
                }
                else -> throw CalcException(CalcException.Kind.SYNTAX)
            }
        }
        return out
    }
}

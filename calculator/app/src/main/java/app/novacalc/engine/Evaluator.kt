package app.novacalc.engine

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

enum class AngleUnit { DEGREES, RADIANS }

/**
 * Recursive-descent evaluator over [Tokenizer] output.
 *
 * Grammar (highest precedence last):
 *   additive       := multiplicative (('+' | '-') multiplicative)*
 *   multiplicative := unary (('×' | '÷' | implicit) unary)*
 *   unary          := ('-' | '+') unary | power
 *   power          := postfix ('^' unary)?            (right associative, so 2^3^2 = 512)
 *   postfix        := primary ('!' | '%' | '²' | '⁻¹')*
 *   primary        := number | constant | '(' additive ')' | function argument
 *
 * Arithmetic is exact-decimal (34 significant digits) so 0.1 + 0.2 is 0.3.
 * Transcendental functions go through IEEE doubles and are rounded to 15
 * significant digits, which hides the binary noise (sin 30° is exactly 0.5).
 *
 * Percent follows the convention every hand-held calculator uses:
 * `200 + 10%` is 220, `200 × 10%` is 20, `50%` alone is 0.5.
 * A missing closing parenthesis at the end of the input is implied.
 */
class Evaluator(private val angleUnit: AngleUnit = AngleUnit.DEGREES) {

    private var toks: List<Tok> = emptyList()
    private var pos = 0

    fun evaluate(expression: String): BigDecimal {
        toks = Tokenizer.tokenize(expression)
        if (toks.isEmpty()) throw CalcException(CalcException.Kind.EMPTY)
        pos = 0
        val value = parseAdditive().value
        if (pos != toks.size) throw CalcException(CalcException.Kind.SYNTAX)
        return checkMagnitude(value)
    }

    private class Term(val value: BigDecimal, val percent: Boolean)

    private fun peek(): Tok? = toks.getOrNull(pos)
    private fun advance(): Tok = toks[pos++]

    private fun parseAdditive(): Term {
        var left = parseMultiplicative()
        while (true) {
            val t = peek()
            if (t is Tok.Op && (t.c == '+' || t.c == '-')) {
                advance()
                val right = parseMultiplicative()
                // "a + b%" means a plus b percent of a; the % term already holds b/100.
                val rv = if (right.percent) left.value.multiply(right.value, MC) else right.value
                val v = if (t.c == '+') left.value.add(rv, MC) else left.value.subtract(rv, MC)
                left = Term(v, false)
            } else return left
        }
    }

    private fun parseMultiplicative(): Term {
        var left = parseUnary()
        while (true) {
            val t = peek() ?: return left
            left = when {
                t is Tok.Op && (t.c == '×' || t.c == '÷') -> {
                    advance()
                    val right = parseUnary()
                    val v = if (t.c == '×') left.value.multiply(right.value, MC) else divide(left.value, right.value)
                    Term(v, false)
                }
                startsPrimary(t) -> {
                    // Implicit multiplication: 2π, 3(4+5), 2sin(30), (1+2)(3+4)
                    val right = parseUnary()
                    Term(left.value.multiply(right.value, MC), false)
                }
                else -> return left
            }
        }
    }

    private fun startsPrimary(t: Tok) =
        t is Tok.Num || t is Tok.Const || t is Tok.LParen || t is Tok.Func

    private fun parseUnary(): Term {
        val t = peek()
        if (t is Tok.Op && t.c == '-') {
            advance()
            val r = parseUnary()
            return Term(r.value.negate(), r.percent)
        }
        if (t is Tok.Op && t.c == '+') {
            advance()
            return parseUnary()
        }
        return parsePower()
    }

    private fun parsePower(): Term {
        val base = parsePostfix()
        val t = peek()
        if (t is Tok.Op && t.c == '^') {
            advance()
            val exponent = parseUnary()
            return Term(power(base.value, exponent.value), false)
        }
        return base
    }

    private fun parsePostfix(): Term {
        var v = parsePrimary()
        var percent = false
        while (true) {
            val t = peek()
            if (t is Tok.Postfix) {
                advance()
                when (t.s) {
                    "!" -> { v = factorial(v); percent = false }
                    "%" -> { v = v.divide(HUNDRED, MC); percent = true }
                    "²" -> { v = checkMagnitude(v.multiply(v, MC)); percent = false }
                    "⁻¹" -> { v = divide(BigDecimal.ONE, v); percent = false }
                }
            } else return Term(v, percent)
        }
    }

    private fun parsePrimary(): BigDecimal {
        val t = peek() ?: throw CalcException(CalcException.Kind.SYNTAX)
        advance()
        return when (t) {
            is Tok.Num -> t.value
            is Tok.Const -> if (t.c == 'π') PI else E
            is Tok.LParen -> {
                val v = parseAdditive().value
                expectRParen()
                v
            }
            is Tok.Func -> {
                val arg = if (peek() is Tok.LParen) {
                    advance()
                    val v = parseAdditive().value
                    expectRParen()
                    v
                } else {
                    // Prefix form without parentheses, e.g. √9 or a pasted "sin30".
                    parseUnary().value
                }
                applyFunction(t.name, arg)
            }
            else -> throw CalcException(CalcException.Kind.SYNTAX)
        }
    }

    /** Consumes ')' if present; end of input implies the closing parenthesis. */
    private fun expectRParen() {
        when (peek()) {
            is Tok.RParen -> advance()
            null -> Unit
            else -> throw CalcException(CalcException.Kind.SYNTAX)
        }
    }

    // ---- arithmetic helpers -------------------------------------------------

    private fun divide(a: BigDecimal, b: BigDecimal): BigDecimal {
        if (b.signum() == 0) throw CalcException(CalcException.Kind.DIVIDE_BY_ZERO)
        return a.divide(b, MC)
    }

    private fun power(base: BigDecimal, exponent: BigDecimal): BigDecimal {
        val stripped = exponent.stripTrailingZeros()
        val isInteger = stripped.scale() <= 0
        if (isInteger && stripped.abs() <= MAX_INT_EXPONENT) {
            val n = stripped.intValueExact()
            if (base.signum() == 0 && n < 0) throw CalcException(CalcException.Kind.DIVIDE_BY_ZERO)
            if (base.signum() == 0 && n == 0) return BigDecimal.ONE
            return checkMagnitude(base.pow(n, MC))
        }
        if (base.signum() < 0) throw CalcException(CalcException.Kind.DOMAIN)
        if (base.signum() == 0) return if (exponent.signum() > 0) BigDecimal.ZERO else throw CalcException(CalcException.Kind.DIVIDE_BY_ZERO)
        return fromDouble(Math.pow(base.toDouble(), exponent.toDouble()))
    }

    private fun factorial(x: BigDecimal): BigDecimal {
        val stripped = x.stripTrailingZeros()
        if (stripped.scale() > 0 || stripped.signum() < 0) throw CalcException(CalcException.Kind.DOMAIN)
        if (stripped > MAX_FACTORIAL) throw CalcException(CalcException.Kind.OVERFLOW)
        val n = stripped.intValueExact()
        var acc = BigInteger.ONE
        for (k in 2..n) acc = acc.multiply(BigInteger.valueOf(k.toLong()))
        return BigDecimal(acc).round(MC)
    }

    private fun applyFunction(name: String, x: BigDecimal): BigDecimal = when (name) {
        "sqrt" -> {
            if (x.signum() < 0) throw CalcException(CalcException.Kind.DOMAIN)
            fromDouble(sqrt(x.toDouble()))
        }
        "cbrt" -> fromDouble(cbrt(x.toDouble()))
        "abs" -> x.abs()
        "exp" -> fromDouble(exp(x.toDouble()))
        "ln" -> {
            if (x.signum() <= 0) throw CalcException(CalcException.Kind.DOMAIN)
            fromDouble(ln(x.toDouble()))
        }
        "log" -> {
            if (x.signum() <= 0) throw CalcException(CalcException.Kind.DOMAIN)
            fromDouble(log10(x.toDouble()))
        }
        "sin" -> trig(x, 's')
        "cos" -> trig(x, 'c')
        "tan" -> trig(x, 't')
        "asin" -> {
            if (x.abs() > BigDecimal.ONE) throw CalcException(CalcException.Kind.DOMAIN)
            fromAngle(asin(x.toDouble()))
        }
        "acos" -> {
            if (x.abs() > BigDecimal.ONE) throw CalcException(CalcException.Kind.DOMAIN)
            fromAngle(acos(x.toDouble()))
        }
        "atan" -> fromAngle(atan(x.toDouble()))
        "sinh" -> fromDouble(sinh(x.toDouble()))
        "cosh" -> fromDouble(cosh(x.toDouble()))
        "tanh" -> fromDouble(tanh(x.toDouble()))
        else -> throw CalcException(CalcException.Kind.SYNTAX)
    }

    private fun trig(x: BigDecimal, which: Char): BigDecimal {
        if (angleUnit == AngleUnit.DEGREES) {
            val a = x.remainder(DEG_360)
            if (a.remainder(DEG_90).signum() == 0) {
                // Exact quadrant values; doubles would give 1.2e-16 for sin 180°.
                val k = ((a.toInt() / 90) % 4 + 4) % 4
                return when (which) {
                    's' -> BigDecimal(intArrayOf(0, 1, 0, -1)[k])
                    'c' -> BigDecimal(intArrayOf(1, 0, -1, 0)[k])
                    else -> if (k % 2 == 1) throw CalcException(CalcException.Kind.DOMAIN) else BigDecimal.ZERO
                }
            }
            val rad = Math.toRadians(a.toDouble())
            return fromDouble(when (which) { 's' -> sin(rad); 'c' -> cos(rad); else -> tan(rad) })
        }
        val d = x.toDouble()
        return fromDouble(when (which) { 's' -> sin(d); 'c' -> cos(d); else -> tan(d) })
    }

    /** Converts an inverse-trig result (radians) to the selected unit. */
    private fun fromAngle(radians: Double): BigDecimal =
        fromDouble(if (angleUnit == AngleUnit.DEGREES) Math.toDegrees(radians) else radians)

    private fun fromDouble(d: Double): BigDecimal {
        if (d.isNaN()) throw CalcException(CalcException.Kind.DOMAIN)
        if (d.isInfinite()) throw CalcException(CalcException.Kind.OVERFLOW)
        if (d == 0.0) return BigDecimal.ZERO
        return BigDecimal(d).round(DOUBLE_MC).stripTrailingZeros()
    }

    private fun checkMagnitude(v: BigDecimal): BigDecimal {
        if (v.signum() != 0 && v.precision() - v.scale() > MAX_INT_DIGITS) throw CalcException(CalcException.Kind.OVERFLOW)
        return v
    }

    companion object {
        val MC = MathContext(34, RoundingMode.HALF_EVEN)
        private val DOUBLE_MC = MathContext(15, RoundingMode.HALF_EVEN)
        private val HUNDRED = BigDecimal(100)
        private val DEG_360 = BigDecimal(360)
        private val DEG_90 = BigDecimal(90)
        private val MAX_INT_EXPONENT = BigDecimal(9999)
        private val MAX_FACTORIAL = BigDecimal(1000)
        private const val MAX_INT_DIGITS = 5000
        val PI: BigDecimal = BigDecimal("3.141592653589793238462643383279503")
        val E: BigDecimal = BigDecimal("2.718281828459045235360287471352662")
    }
}

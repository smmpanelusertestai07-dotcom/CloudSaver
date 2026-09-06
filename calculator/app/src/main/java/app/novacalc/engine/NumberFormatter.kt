package app.novacalc.engine

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Turns exact results into what the display shows, and back into what the engine reads. */
object NumberFormatter {

    /**
     * Human-readable result: at most [maxFractionDigits] decimals, thousands
     * grouping when [grouping] is set, and scientific notation once a number
     * has more than 15 integer digits or is too small to show at this precision.
     */
    fun format(value: BigDecimal, maxFractionDigits: Int = 10, grouping: Boolean = true): String {
        val v = value.stripTrailingZeros()
        if (v.signum() == 0) return "0"
        val intDigits = v.precision() - v.scale()
        if (intDigits > 15) return scientific(v)
        val rounded = v.setScale(maxFractionDigits.coerceIn(0, 20), RoundingMode.HALF_UP).stripTrailingZeros()
        if (rounded.signum() == 0) return scientific(v)
        return plain(rounded, grouping)
    }

    /** A literal the tokenizer reads back exactly: 15 significant digits, plain unless extreme. */
    fun toLiteral(value: BigDecimal): String {
        val r = value.round(MathContext(15, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        if (r.signum() == 0) return "0"
        val intDigits = r.precision() - r.scale()
        return if (intDigits in -6..16) r.toPlainString() else r.toString()
    }

    /** Pretty form of a number literal as typed, e.g. "-1234.50" to "−1,234.50". */
    fun formatLiteral(text: String, grouping: Boolean): String {
        val neg = text.startsWith("-")
        val body = if (neg) text.substring(1) else text
        if (body.contains('E')) return (if (neg) "−" else "") + body
        val dot = body.indexOf('.')
        val intPart = if (dot >= 0) body.substring(0, dot) else body
        val frac = if (dot >= 0) body.substring(dot) else ""
        val grouped = if (grouping) groupDigits(intPart) else intPart
        return (if (neg) "−" else "") + grouped + frac
    }

    private fun scientific(v: BigDecimal): String {
        val r = v.round(MathContext(10, RoundingMode.HALF_UP)).stripTrailingZeros()
        val exponent = r.precision() - r.scale() - 1
        val mantissa = r.movePointLeft(exponent).stripTrailingZeros()
        val sign = if (mantissa.signum() < 0) "−" else ""
        return sign + mantissa.abs().toPlainString() + "E" + exponent
    }

    private fun plain(v: BigDecimal, grouping: Boolean): String = formatLiteral(v.toPlainString(), grouping)

    fun groupDigits(digits: String): String {
        if (digits.length <= 3 || !digits.all { it.isDigit() }) return digits
        val sb = StringBuilder(digits.length + digits.length / 3)
        for ((index, ch) in digits.withIndex()) {
            if (index > 0 && (digits.length - index) % 3 == 0) sb.append(',')
            sb.append(ch)
        }
        return sb.toString()
    }
}

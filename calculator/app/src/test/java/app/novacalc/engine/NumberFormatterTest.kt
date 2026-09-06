package app.novacalc.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class NumberFormatterTest {

    private fun fmt(v: String, digits: Int = 10, grouping: Boolean = true) =
        NumberFormatter.format(BigDecimal(v), digits, grouping)

    @Test fun integersAreGrouped() {
        assertEquals("0", fmt("0"))
        assertEquals("7", fmt("7"))
        assertEquals("1,234", fmt("1234"))
        assertEquals("1,234,567", fmt("1234567"))
        assertEquals("1234567", fmt("1234567", grouping = false))
        assertEquals("−1,234,567", fmt("-1234567"))
        assertEquals("100", fmt("1E+2"))
    }

    @Test fun fractionsAreRoundedAndTrimmed() {
        assertEquals("0.3333333333", fmt("0.333333333333333333333"))
        assertEquals("0.67", fmt("0.666666", 2))
        assertEquals("1", fmt("0.99999999999"))
        assertEquals("2.5", fmt("2.5000"))
        assertEquals("1,234.5678", fmt("1234.5678"))
        assertEquals("−0.5", fmt("-0.5"))
        assertEquals("3", fmt("2.999", 0))
    }

    @Test fun extremesUseScientificNotation() {
        assertEquals("1E16", fmt("10000000000000000"))
        assertEquals("1.234567891E20", fmt("123456789123456789123"))
        assertEquals("1E-11", fmt("0.00000000001"))
        assertEquals("−2.5E-12", fmt("-0.0000000000025"))
        assertEquals("0.0000000001", fmt("0.0000000001"))
        assertEquals("123,456,789,012,345", fmt("123456789012345"))
    }

    @Test fun literalRoundTrip() {
        assertEquals("1000", NumberFormatter.toLiteral(BigDecimal("1E+3")))
        assertEquals("0.333333333333333", NumberFormatter.toLiteral(BigDecimal("0.3333333333333333333333333333333333")))
        assertEquals("-2.5", NumberFormatter.toLiteral(BigDecimal("-2.50")))
        assertEquals("1E+20", NumberFormatter.toLiteral(BigDecimal("100000000000000000000")))
        assertEquals("0", NumberFormatter.toLiteral(BigDecimal.ZERO))
        // Whatever toLiteral produces, the tokenizer must read back to the same value.
        for (v in listOf("1E+20", "-2.5", "0.000001", "1E-9", "123456789012345")) {
            val literal = NumberFormatter.toLiteral(BigDecimal(v))
            assertEquals(v, BigDecimal(v).stripTrailingZeros(), Evaluator().evaluate(literal).stripTrailingZeros())
        }
    }

    @Test fun typedLiterals() {
        assertEquals("1,234.50", NumberFormatter.formatLiteral("1234.50", true))
        assertEquals("−12", NumberFormatter.formatLiteral("-12", true))
        assertEquals("0.", NumberFormatter.formatLiteral("0.", true))
        assertEquals("−", NumberFormatter.formatLiteral("-", true))
        assertEquals("1E+20", NumberFormatter.formatLiteral("1E+20", true))
    }
}

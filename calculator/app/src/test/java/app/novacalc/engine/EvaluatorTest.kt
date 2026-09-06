package app.novacalc.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal

class EvaluatorTest {

    private fun eval(expr: String, unit: AngleUnit = AngleUnit.DEGREES): BigDecimal =
        Evaluator(unit).evaluate(expr).stripTrailingZeros()

    /** Compares at 15 significant digits, the precision transcendental results carry. */
    private fun assertValue(expected: String, expr: String, unit: AngleUnit = AngleUnit.DEGREES) {
        val mc = java.math.MathContext(15)
        assertEquals(expr, BigDecimal(expected).round(mc).stripTrailingZeros(), eval(expr, unit).round(mc).stripTrailingZeros())
    }

    /** Exact comparison, for results that must not lose a digit. */
    private fun assertExact(expected: String, expr: String) {
        assertEquals(expr, BigDecimal(expected).stripTrailingZeros(), eval(expr))
    }

    private fun assertError(kind: CalcException.Kind, expr: String) {
        val e = assertThrows(CalcException::class.java) { eval(expr) }
        assertEquals(expr, kind, e.kind)
    }

    @Test fun basicArithmetic() {
        assertValue("15", "7+8")
        assertValue("-1", "7-8")
        assertValue("56", "7×8")
        assertValue("2.5", "5÷2")
        assertValue("14", "2+3×4")
        assertValue("20", "(2+3)×4")
        assertValue("1", "10-3×3")
        assertValue("2", "8÷2÷2")
        assertValue("6", "10-2-2")
    }

    @Test fun asciiOperatorsAccepted() {
        assertValue("14", "2+3*4")
        assertValue("2.5", "5/2")
        assertValue("-1", "7-8")
    }

    @Test fun exactDecimals() {
        assertValue("0.3", "0.1+0.2")
        assertValue("1", "0.1×10")
        assertValue("3.3", "1.1×3")
        assertValue("0.1", "1÷10")
    }

    @Test fun unaryMinus() {
        assertValue("-5", "-5")
        assertValue("5", "--5")
        assertValue("-15", "3×-5")
        assertValue("-1", "3+-4")
        assertValue("-4", "-2^2")
        assertValue("0.25", "2^-2")
        assertValue("-120", "-5!")
    }

    @Test fun powersAreRightAssociative() {
        assertValue("512", "2^3^2")
        assertValue("8", "2^3")
        assertValue("2", "4^0.5")
        assertValue("1", "0^0")
        assertValue("0", "0^2")
        assertValue("1000000", "10^6")
    }

    @Test fun percentSemantics() {
        assertValue("0.5", "50%")
        assertValue("220", "200+10%")
        assertValue("180", "200-10%")
        assertValue("20", "200×10%")
        assertValue("2000", "200÷10%")
        assertValue("50", "100+-50%")
    }

    @Test fun postfixOperators() {
        assertValue("120", "5!")
        assertValue("1", "0!")
        assertValue("25", "5²")
        assertValue("0.25", "4⁻¹")
        assertValue("625", "5²²")
        assertValue("720", "3!!")
    }

    @Test fun parenthesesAndImplicitMultiplication() {
        assertValue("12", "3(4)")
        assertValue("21", "(1+2)(3+4)")
        assertValue("6.283185307179586", "2π")
        assertValue("3", "√9")
        assertValue("1", "2sin(30)")
        assertValue("14", "2(3+4)")
    }

    @Test fun missingClosingParenthesisIsImplied() {
        assertValue("20", "(2+3)×4")
        assertValue("20", "4×(2+3")
        assertValue("9", "√(81")
        assertValue("14", "2×(3+(4")
    }

    @Test fun trigonometryInDegrees() {
        assertValue("0.5", "sin(30)")
        assertValue("0.5", "cos(60)")
        assertValue("1", "tan(45)")
        assertValue("0", "sin(180)")
        assertValue("0", "cos(90)")
        assertValue("-1", "cos(180)")
        assertValue("1", "sin(90)")
        assertValue("0", "sin(360)")
        assertValue("-1", "sin(-90)")
        assertValue("90", "asin(1)")
        assertValue("60", "acos(0.5)")
        assertValue("45", "atan(1)")
        assertError(CalcException.Kind.DOMAIN, "tan(90)")
        assertError(CalcException.Kind.DOMAIN, "asin(2)")
    }

    @Test fun trigonometryInRadians() {
        assertValue("1", "sin(π÷2)", AngleUnit.RADIANS)
        assertValue("-1", "cos(π)", AngleUnit.RADIANS)
        assertValue("0", "sin(0)", AngleUnit.RADIANS)
        assertValue("1.5707963267949", "asin(1)", AngleUnit.RADIANS)
    }

    @Test fun logarithmsRootsAndExp() {
        assertValue("2", "log(100)")
        assertValue("1", "ln(e)")
        assertValue("3", "√(9)")
        assertValue("1.4142135623731", "√2")
        assertValue("3", "cbrt(27)")
        assertValue("1", "exp(0)")
        assertValue("7", "abs(-7)")
        assertValue("2.718281828459045", "e")
        assertError(CalcException.Kind.DOMAIN, "ln(0)")
        assertError(CalcException.Kind.DOMAIN, "log(-1)")
        assertError(CalcException.Kind.DOMAIN, "√(-1)")
    }

    @Test fun scientificNotationLiterals() {
        assertValue("150000000000", "1.5E+11")
        assertValue("0.0000002", "2E-7")
        assertValue("2000", "2E3")
    }

    @Test fun errors() {
        assertError(CalcException.Kind.DIVIDE_BY_ZERO, "1÷0")
        assertError(CalcException.Kind.DIVIDE_BY_ZERO, "0⁻¹")
        assertError(CalcException.Kind.DIVIDE_BY_ZERO, "0^-1")
        assertError(CalcException.Kind.DOMAIN, "2.5!")
        assertError(CalcException.Kind.DOMAIN, "(-3)!")
        assertError(CalcException.Kind.OVERFLOW, "1001!")
        assertError(CalcException.Kind.OVERFLOW, "10^9999^9")
        assertError(CalcException.Kind.SYNTAX, "2+")
        assertError(CalcException.Kind.SYNTAX, "×2")
        assertError(CalcException.Kind.SYNTAX, "2..3")
        assertError(CalcException.Kind.SYNTAX, "foo(2)")
        assertError(CalcException.Kind.SYNTAX, "(2+3))")
        assertError(CalcException.Kind.EMPTY, "")
    }

    @Test fun largeAndPreciseValues() {
        assertExact("9999999999999999999999", "9999999999999999999999")
        assertExact("99999999999999980000000000000001", "9999999999999999×9999999999999999")
        assertExact("0.3", "0.1+0.2")
        assertExact("6.283185307179586476925286766559006", "2π")
        assertEquals(34, Evaluator().evaluate("1÷3").precision())
        assertValue("3628800", "10!")
    }

    @Test fun whitespaceAndGroupingIgnored() {
        assertValue("1235", "1,234 + 1")
        assertValue("1234567.5", "1,234,567.5")
        assertValue("2468", "1,234×2")
        assertValue("5", "2 + 3")
    }
}

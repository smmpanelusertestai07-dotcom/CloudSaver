package app.cloudsaver.core.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerifyMathTest {

    @Test
    fun ninetyPercentBoundaryExact() {
        assertTrue(VerifyMath.batchVerified(txBytes = 900, batchBytes = 1000))
        assertFalse(VerifyMath.batchVerified(txBytes = 899, batchBytes = 1000))
        assertTrue(VerifyMath.batchVerified(txBytes = 1000, batchBytes = 1000))
        assertTrue(VerifyMath.batchVerified(txBytes = 5000, batchBytes = 1000))
    }

    @Test
    fun degenerateInputsNeverVerify() {
        assertFalse(VerifyMath.batchVerified(txBytes = 100, batchBytes = 0))
        assertFalse(VerifyMath.batchVerified(txBytes = -1, batchBytes = 1000))
        assertFalse(VerifyMath.batchVerified(txBytes = 0, batchBytes = 1))
    }

    @Test
    fun noOverflowOnHugeValues() {
        val fiveHundredGb = 500L * 1024 * 1024 * 1024
        assertTrue(VerifyMath.batchVerified(fiveHundredGb, fiveHundredGb))
        assertFalse(VerifyMath.batchVerified(fiveHundredGb / 2, fiveHundredGb))
    }
}

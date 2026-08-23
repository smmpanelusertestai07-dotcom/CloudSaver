package app.litesaver.core.logic

/**
 * Data-count verification: a release batch counts as VERIFIED when the selected
 * cloud app's TX bytes since the batch release reach 90% of the batch size.
 * (Integer math - no float rounding surprises.)
 */
object VerifyMath {
    fun batchVerified(txBytes: Long, batchBytes: Long): Boolean =
        batchBytes > 0 && txBytes >= 0 && txBytes * 10 >= batchBytes * 9
}

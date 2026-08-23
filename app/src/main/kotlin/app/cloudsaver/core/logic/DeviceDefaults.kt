package app.cloudsaver.core.logic

/**
 * Limits chosen from what the phone actually has, rather than one number for
 * every device.
 *
 * A 64 GB phone and a 512 GB phone should not reserve the same headroom, and
 * a daily upload cap that suits a metered connection is wrong for someone on
 * Wi-Fi all week. These are recommendations only: a value the user picked is
 * never overwritten.
 */
object DeviceDefaults {

    /** Keep the larger of 1.5 GB and a twentieth of the phone free. */
    fun reserveMb(totalBytes: Long): Int {
        val fivePercent = (totalBytes / 20 / Defaults.MB).toInt()
        return maxOf(1536, fivePercent)
    }

    /**
     * How much the app may occupy: a tenth of what is free, never more than
     * 5 GB, but always enough for two days of releases or the pipeline would
     * jam against its own limit.
     */
    fun ownLimitMb(freeBytes: Long, dailyCapMb: Int): Int {
        val tenthOfFree = (freeBytes / 10 / Defaults.MB).toInt()
        val ceiling = 5 * 1024
        val floor = if (dailyCapMb > 0) dailyCapMb * 2 else 1536
        return minOf(ceiling, tenthOfFree).coerceAtLeast(floor)
    }

    /**
     * Daily upload allowance. Small or nearly full phones get the cautious
     * figure; a phone that has been on Wi-Fi most of the week can afford more,
     * because the cap exists to protect mobile data, not the cloud.
     */
    fun dailyCapMb(totalBytes: Long, freeBytes: Long, wifiShareLast7Days: Double): Int {
        val smallPhone = totalBytes <= 64L * 1024 * Defaults.MB
        val nearlyFull = freeBytes < 8L * 1024 * Defaults.MB
        return when {
            smallPhone || nearlyFull -> 250
            wifiShareLast7Days > 0.70 -> 1024
            else -> 500
        }
    }

    /** True when a stored choice has drifted far enough to be worth a hint. */
    fun looksWrong(current: Int, recommended: Int): Boolean =
        current > 0 && recommended > 0 && (current > recommended * 3 || current * 3 < recommended)
}

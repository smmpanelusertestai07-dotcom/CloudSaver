package app.cloudsaver.core.logic

/**
 * Why Android ended a run, in words, and which reasons mean the app is not
 * actually keeping up.
 *
 * WorkManager reports `getStopReason()` as an int from API 31. Nothing read
 * it, so a run the platform cut short was indistinguishable from one that
 * finished: both stamped the last-run time, both left every health check
 * green, and the app reported itself well while completing a fraction of the
 * work. Android 16 turned that from an edge case into the normal one - a job
 * running alongside a foreground service is now subject to the JobScheduler
 * runtime quota, and the standby bucket decides how much of it there is.
 *
 * The numbers are duplicated here rather than referenced from WorkInfo so
 * this stays a pure rule with its own tests; each is checked against the name
 * the platform gives it.
 */
object Stops {

    const val UNKNOWN = "STOPPED"

    /** The platform's own constant names, so a log line says what it means. */
    fun name(reason: Int): String = when (reason) {
        -256 -> "NOT_STOPPED"
        0 -> UNKNOWN
        1 -> "CANCELLED_BY_APP"
        2 -> "PREEMPT"
        3 -> "TIMEOUT"
        4 -> "DEVICE_STATE"
        5 -> "CONSTRAINT_BATTERY_NOT_LOW"
        6 -> "CONSTRAINT_CHARGING"
        7 -> "CONSTRAINT_CONNECTIVITY"
        8 -> "CONSTRAINT_DEVICE_IDLE"
        9 -> "CONSTRAINT_STORAGE_NOT_LOW"
        10 -> "QUOTA"
        11 -> "BACKGROUND_RESTRICTION"
        12 -> "APP_STANDBY"
        13 -> "USER"
        14 -> "SYSTEM_PROCESSING"
        15 -> "ESTIMATED_APP_LAUNCH_TIME_CHANGED"
        16 -> "FOREGROUND_SERVICE_TIMEOUT"
        else -> UNKNOWN
    }

    /**
     * Reasons that mean "the phone is rationing us", as opposed to a run that
     * ended for a reason the app already explains elsewhere.
     *
     * A cancel by the app itself, or a constraint the app chose (charging,
     * battery-not-low), is already surfaced as a wait state on Home with its
     * own sentence. These are different: the work was allowed to start and
     * then taken away, and the remedy - letting the app run unrestricted - is
     * a setting only the user can reach.
     */
    private val RATIONED = setOf(
        "QUOTA", "APP_STANDBY", "BACKGROUND_RESTRICTION",
        "TIMEOUT", "FOREGROUND_SERVICE_TIMEOUT", "DEVICE_STATE", "PREEMPT"
    )

    /** True when [reason] means the app is being cut short rather than idle. */
    fun isRationed(reason: String): Boolean = reason in RATIONED
}

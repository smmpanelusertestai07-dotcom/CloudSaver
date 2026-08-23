package app.litesaver.core.logic

/**
 * Scheduling brain (13.G). One pure function decides whether photo work, video
 * work, both or nothing may run right now, and why not. It is evaluated at
 * worker start AND between every item, so a phone that is picked up mid-run
 * stops encoding within one item.
 *
 * WorkManager itself only carries a batteryNotLow constraint; every other
 * condition lives here so it can be unit tested and explained to the user.
 */
object RunDecider {

    /** Everything the decision needs from the device, read once per check. */
    data class Power(
        val plugged: Boolean,
        val batteryPct: Int,
        /** Battery temperature in tenths of a degree Celsius. */
        val batteryTempTenthsC: Int,
        val saverOn: Boolean,
        val screenInteractive: Boolean,
        /** Best estimate of how long the screen has been off (0 while on). */
        val screenOffMs: Long,
        /** PowerManager thermal status >= MODERATE. */
        val thermalThrottled: Boolean
    )

    /** Today's on-battery usage, reset at local midnight. */
    data class Budget(val videoEncodeMs: Long, val photosOnBattery: Int)

    enum class Wait {
        NONE,
        PAUSED,
        BATTERY_SAVER,
        TOO_HOT,
        NOT_CHARGING,
        BATTERY_LOW,
        SCREEN_ON,
        BUDGET_USED,
        PHOTO_CAP
    }

    /**
     * [photos] / [videos] say what may be processed right now. When both are
     * false, [wait] explains why in a form the Home screen can show verbatim.
     * [floorPct] is the battery floor that applied to this decision, so the
     * message can name the real number.
     */
    data class Plan(
        val photos: Boolean,
        val videos: Boolean,
        val wait: Wait,
        val floorPct: Int
    ) {
        val canRun: Boolean get() = photos || videos
    }

    /** Battery floor while running on battery. */
    fun batteryFloor(mode: SpeedMode): Int = when (mode) {
        SpeedMode.SMART -> Defaults.SMART_BATTERY_FLOOR
        SpeedMode.FAST -> Defaults.FAST_BATTERY_FLOOR
        SpeedMode.CHARGING_ONLY -> Defaults.CHARGING_BATTERY_FLOOR
    }

    /** Daily on-battery video-encode budget in ms (0 = no on-battery work). */
    fun videoBudgetMs(mode: SpeedMode): Long = when (mode) {
        SpeedMode.SMART -> Defaults.SMART_BUDGET_MS
        SpeedMode.FAST -> Defaults.FAST_BUDGET_MS
        SpeedMode.CHARGING_ONLY -> 0L
    }

    /** How long the screen must have been off before video encodes start. */
    fun screenOffWaitMs(mode: SpeedMode): Long = when (mode) {
        SpeedMode.SMART -> Defaults.SMART_SCREEN_OFF_WAIT_MS
        SpeedMode.FAST -> 0L
        SpeedMode.CHARGING_ONLY -> 0L
    }

    fun decide(
        mode: SpeedMode,
        power: Power,
        budget: Budget,
        paused: Boolean = false
    ): Plan {
        val floor = batteryFloor(mode)
        if (paused) return Plan(false, false, Wait.PAUSED, floor)

        // Battery Saver pauses compression in every mode, charging included:
        // the user asked the system to stop background work.
        if (power.saverOn) return Plan(false, false, Wait.BATTERY_SAVER, floor)
        if (power.thermalThrottled ||
            power.batteryTempTenthsC > Defaults.BATTERY_MAX_TEMP_TENTHS_C
        ) {
            return Plan(false, false, Wait.TOO_HOT, floor)
        }

        if (power.plugged) {
            // Charging: photos always, videos need a small floor so a nearly
            // empty phone charges up first. No budget, screen may be on.
            val videos = power.batteryPct >= Defaults.CHARGING_BATTERY_FLOOR
            return Plan(photos = true, videos = videos, wait = Wait.NONE, floorPct = floor)
        }

        if (mode == SpeedMode.CHARGING_ONLY) {
            return Plan(false, false, Wait.NOT_CHARGING, floor)
        }

        // On battery.
        if (power.batteryPct < floor) return Plan(false, false, Wait.BATTERY_LOW, floor)

        // Photos are cheap: no screen-off wait, no time budget, only a daily cap.
        val photos = budget.photosOnBattery < Defaults.PHOTO_CAP_ON_BATTERY

        // Videos are expensive: screen must be off long enough and the daily
        // encode budget must be left. A mode with a zero wait (FAST) does not
        // care about the screen at all.
        val screenWait = screenOffWaitMs(mode)
        val screenBlocked = screenWait > 0 &&
            (power.screenInteractive || power.screenOffMs < screenWait)
        val budgetBlocked = budget.videoEncodeMs >= videoBudgetMs(mode)
        val videos = !screenBlocked && !budgetBlocked

        if (photos || videos) return Plan(photos, videos, Wait.NONE, floor)

        // Nothing may run: report the most useful blocker.
        val reason = when {
            budgetBlocked -> Wait.BUDGET_USED
            screenBlocked -> Wait.SCREEN_ON
            else -> Wait.PHOTO_CAP
        }
        return Plan(false, false, reason, floor)
    }

    /**
     * "Run now" is user-initiated, so only hard safety limits apply:
     * charging or at least 15%, and not too hot.
     */
    fun decideManual(power: Power): Plan {
        val floor = Defaults.CHARGING_BATTERY_FLOOR
        if (power.thermalThrottled ||
            power.batteryTempTenthsC > Defaults.BATTERY_MAX_TEMP_TENTHS_C
        ) {
            return Plan(false, false, Wait.TOO_HOT, floor)
        }
        if (!power.plugged && power.batteryPct < floor) {
            return Plan(false, false, Wait.BATTERY_LOW, floor)
        }
        return Plan(photos = true, videos = true, wait = Wait.NONE, floorPct = floor)
    }
}

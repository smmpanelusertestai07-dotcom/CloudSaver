package app.cloudsaver.core.logic

/**
 * What Home's one button offers, and whether it should be there at all.
 *
 * The button used to be called "Back up now", which is a promise CloudSaver
 * cannot keep: it optimises, and the user's own cloud app does the uploading.
 * It was also always present, so on a finished queue it invited a tap that
 * could do nothing.
 *
 * Tapping it starts a run the user asked for. That overrides the scheduling
 * rules - charging, screen-off, today's battery budget - because those exist
 * to avoid surprising someone, and this is not a surprise. It never overrides
 * the safety guards: heat, a nearly flat battery, and free space are about the
 * phone, not about convenience.
 */
object HomeAction {

    /** The hard floor for a run the user started by hand. */
    const val BATTERY_FLOOR_PCT = Defaults.CHARGING_BATTERY_FLOOR

    enum class Visibility {
        /** Offer the button. */
        BUTTON,

        /** A run is under way; show its progress instead of an action. */
        WORKING,

        /** Nothing to do, or the user has deliberately stopped everything. */
        HIDDEN
    }

    /** A guard the user cannot override, named so the reason can be shown. */
    enum class Blocker { NONE, TOO_HOT, BATTERY_LOW, NOT_ENOUGH_SPACE }

    /** The one line under the button. */
    enum class Note {
        /** Idle and scheduled: the button only brings the next run forward. */
        JUST_STARTS_IT,

        /** Work is being held back by budget or charger; this overrides that. */
        OVERRIDES_WAITING,

        /** Blocked by a guard; the blocker says which. */
        BLOCKED
    }

    data class State(
        val visibility: Visibility,
        val enabled: Boolean,
        val blocker: Blocker,
        val note: Note
    )

    /**
     * [waitReason] is the scheduler's current answer, used only to choose the
     * wording: the button runs regardless of anything the scheduler is
     * waiting for.
     */
    fun decide(
        queued: Int,
        running: Boolean,
        paused: Boolean,
        thermalThrottled: Boolean,
        batteryPct: Int,
        plugged: Boolean,
        freeBytes: Long,
        minFreeBytes: Long,
        waitReason: RunDecider.Wait
    ): State {
        if (running) {
            return State(Visibility.WORKING, enabled = false, Blocker.NONE, Note.JUST_STARTS_IT)
        }
        // Pause is a deliberate choice with its own control; offering a button
        // that contradicts it here would just be confusing.
        if (paused || queued <= 0) {
            return State(Visibility.HIDDEN, enabled = false, Blocker.NONE, Note.JUST_STARTS_IT)
        }

        val blocker = when {
            thermalThrottled -> Blocker.TOO_HOT
            // A charger makes a low battery a non-issue.
            !plugged && batteryPct in 1 until BATTERY_FLOOR_PCT -> Blocker.BATTERY_LOW
            freeBytes in 1 until minFreeBytes -> Blocker.NOT_ENOUGH_SPACE
            else -> Blocker.NONE
        }
        if (blocker != Blocker.NONE) {
            return State(Visibility.BUTTON, enabled = false, blocker, Note.BLOCKED)
        }

        // Everything the scheduler is waiting for is something this run skips.
        val overriding = when (waitReason) {
            RunDecider.Wait.NONE, RunDecider.Wait.PAUSED -> false
            else -> true
        }
        return State(
            visibility = Visibility.BUTTON,
            enabled = true,
            blocker = Blocker.NONE,
            note = if (overriding) Note.OVERRIDES_WAITING else Note.JUST_STARTS_IT
        )
    }

    /**
     * Whether Home may offer the "check my uploads" link at all.
     *
     * It exists for one situation only: the chosen cloud app removes its own
     * uploads, and Usage Access is switched off so the app cannot see the
     * bytes go. With access granted the check happens by itself, and a button
     * that duplicates automatic work is a button that implies it did not
     * happen.
     */
    fun showVerifyLink(usageAccessGranted: Boolean, cloudRemovesItsUploads: Boolean): Boolean =
        !usageAccessGranted && cloudRemovesItsUploads
}

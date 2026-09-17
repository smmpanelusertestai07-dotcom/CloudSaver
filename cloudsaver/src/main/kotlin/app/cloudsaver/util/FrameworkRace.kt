package app.cloudsaver.util

/**
 * Tells Android's own teardown race apart from a fault in this app.
 *
 * "Activity client record must not be null to execute transaction item:
 * TopResumedActivityChangeItem" is a race inside the framework: the system
 * tells an activity it is no longer on top after that activity's own record
 * has already gone. No app can prevent it and nothing the person does causes
 * it; it is reported on plenty of phones, the owner's among them. Counted as
 * a crash, it puts "CloudSaver stopped unexpectedly" on Home for Android's
 * tidying, and two of them near a launch would open the recovery page for
 * an app with nothing wrong.
 *
 * The judgement matters in both directions, which is why it is narrow: the
 * message alone is not enough, a frame inside Android's own transaction
 * executor is required too. Call a real bug a framework race and it is
 * hidden for ever; call a race a real bug and the person is told the app
 * failed every time Android closes a screen at an awkward moment. The
 * sister project in this repository keeps the same judgement.
 */
object FrameworkRace {

    private val MESSAGES = listOf(
        "Activity client record must not be null",
        "Unable to find non-null record"
    )

    private const val FRAMEWORK_PACKAGE = "android.app.servertransaction."

    /** True only for the platform's own race, judged by message and by frame. */
    fun isRace(error: Throwable): Boolean {
        var one: Throwable? = error
        while (one != null) {
            if (one is IllegalArgumentException) {
                val message = one.message.orEmpty()
                val known = message.contains("Activity") && MESSAGES.any { message.contains(it) }
                if (known && one.stackTrace.any { it.className.startsWith(FRAMEWORK_PACKAGE) }) {
                    return true
                }
            }
            one = one.cause
        }
        return false
    }
}

package com.pocketdesk;

/**
 * Tells Android's own teardown races apart from a fault in this app.
 *
 * "Activity client record must not be null to execute transaction item:
 * TopResumedActivityChangeItem" is a race inside the framework: the system tells an activity it
 * is no longer on top after that activity's own record has already gone. No app can prevent it
 * and nothing the owner does causes it -- it is reported on plenty of phones, this one included.
 *
 * The judgement matters in both directions, which is why it lives here on its own, with no
 * Android class in sight so a test can run it: call a real bug a framework race and it is hidden
 * from the owner for ever; call a framework race a real bug and the owner is told their computer
 * failed every time Android closes a screen at an awkward moment.
 */
final class FrameworkRace {

    private FrameworkRace() {}

    static boolean is(Throwable error) {
        for (Throwable one = error; one != null; one = one.getCause()) {
            String message = one.getMessage();
            if (message != null
                    && (message.contains("Activity client record must not be null")
                        || message.contains("TopResumedActivityChangeItem")
                        || message.contains("Unable to find non-null record"))) {
                return true;
            }
            StackTraceElement[] frames = one.getStackTrace();
            if (frames == null) continue;
            for (StackTraceElement frame : frames) {
                if (frame.getClassName().startsWith("android.app.servertransaction.")) return true;
            }
        }
        return false;
    }
}

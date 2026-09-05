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

    /**
     * The sentences that only Android's own teardown writes. Kept in one place so a stack trace
     * and a report already written to disk are judged by exactly the same words.
     */
    private static final String[] MARKS = {
            "Activity client record must not be null",
            "TopResumedActivityChangeItem",
            "Unable to find non-null record",
    };

    /**
     * The same judgement applied to a report that is already on disk.
     *
     * It is needed because a race does not happen instead of a real fault -- it happens just
     * AFTER one, as the phone tidies up the screen the real fault killed. Written blindly, the
     * race's report lands on top of the report that actually says what went wrong, and the owner
     * is left looking at Android's tidying instead of the cause. This lets the recorder tell "the
     * report I already have is the real one" from "the report I already have is another race",
     * so only the second is ever replaced.
     */
    static boolean isReport(String report) {
        if (report == null) return false;
        for (String mark : MARKS) {
            if (report.contains(mark)) return true;
        }
        return report.contains("android.app.servertransaction.");
    }

    static boolean is(Throwable error) {
        for (Throwable one = error; one != null; one = one.getCause()) {
            if (!(one instanceof IllegalArgumentException)) continue;
            String message = one.getMessage();
            boolean knownMessage = message != null
                    && (message.contains("Activity client record must not be null")
                        || message.contains("Unable to find non-null record"))
                    && message.contains("Activity");
            if (!knownMessage) continue;
            StackTraceElement[] frames = one.getStackTrace();
            if (frames == null) continue;
            for (StackTraceElement frame : frames) {
                if (frame.getClassName().startsWith("android.app.servertransaction.")) return true;
            }
        }
        return false;
    }
}

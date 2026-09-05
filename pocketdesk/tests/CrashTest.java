package com.pocketdesk;

/**
 * The one judgement Crash makes on its own: is this Android's own teardown race, or is it a
 * fault in this app?
 *
 * Getting it wrong is expensive in both directions. Call a real bug a framework race and it is
 * hidden from the owner for ever; call a framework race a real bug and the owner is told their
 * computer failed every time Android closes a screen at an awkward moment.
 */
public final class CrashTest {

    public static void main(String[] args) {
        // The exact error a Realme phone on Android 13 reported, rebuilt frame for frame.
        IllegalArgumentException race = new IllegalArgumentException(
                "Activity client record must not be null to execute transaction item: "
                        + "TopResumedActivityChangeItem{onTop=false}");
        race.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("android.app.servertransaction.ActivityTransactionItem",
                        "getActivityClientRecord", "ActivityTransactionItem.java", 66),
                new StackTraceElement("android.app.ActivityThread$H", "handleMessage",
                        "ActivityThread.java", 2465),
        });
        require(FrameworkRace.is(race), "the Android teardown race must be recognised");

        // Recognised from the frames alone, for the ones whose message says nothing useful.
        NullPointerException quiet = new NullPointerException();
        quiet.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("android.app.servertransaction.TransactionExecutor",
                        "execute", "TransactionExecutor.java", 95),
        });
        require(FrameworkRace.is(quiet), "a servertransaction frame is the same race");

        // And through a wrapper, because that is how they usually arrive.
        require(FrameworkRace.is(new RuntimeException("wrapped", race)),
                "the race must still be recognised through a cause");

        // A real fault in this app must never be hidden behind that name.
        NullPointerException ours = new NullPointerException(
                "Attempt to invoke virtual method on a null object reference");
        ours.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.pocketdesk.MainActivity", "refreshLiveTiles",
                        "MainActivity.java", 1700),
                new StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 106),
        });
        require(!FrameworkRace.is(ours), "a fault in this app must not be treated as Android's");

        require(!FrameworkRace.is(new java.io.IOException("no space left on device")),
                "an ordinary failure must not be treated as Android's");
        require(!FrameworkRace.is(new RuntimeException()), "an empty error is not the race");

        // ---- the same judgement on a report already written to disk ---------------------------
        //
        // The race is thrown a few milliseconds AFTER the fault that caused it, so writing it
        // blindly overwrote the report that said what actually went wrong. Recognising the
        // report on disk is what lets the recorder keep the real one.
        require(FrameworkRace.isReport(
                        "2026-09-04 12:43\nAndroid 13 \u00b7 RMX3197\n"
                        + "java.lang.IllegalArgumentException: Activity client record must not be "
                        + "null to execute transaction item: TopResumedActivityChangeItem"),
                "a stored race report must be recognised as one");
        require(FrameworkRace.isReport("\tat android.app.servertransaction.TransactionExecutor.execute"),
                "a stored report with only a servertransaction frame is still the race");
        require(!FrameworkRace.isReport(
                        "2026-09-04 13:10\nAndroid 13 \u00b7 RMX3197\n"
                        + "java.lang.NullPointerException: Attempt to invoke virtual method "
                        + "'android.content.Context android.content.Context.getApplicationContext()' "
                        + "on a null object reference\n"
                        + "\tat com.pocketdesk.MicBridge.<init>(MicBridge.java:43)"),
                "the real cause of the black screen must NOT be mistaken for Android's race");
        require(!FrameworkRace.isReport(""), "no report is not a race report");
        require(!FrameworkRace.isReport(null), "a missing report is not a race report");

        System.out.println("PASS CrashTest (framework race told apart from a real fault)");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

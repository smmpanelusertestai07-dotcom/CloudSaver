package com.pocketlinux;

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

        // A framework-looking frame alone is not enough: an app fault delivered as part of an
        // Activity transaction has those frames too, and hiding it would lose the real cause.
        NullPointerException quiet = new NullPointerException();
        quiet.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("android.app.servertransaction.TransactionExecutor",
                        "execute", "TransactionExecutor.java", 95),
        });
        require(!FrameworkRace.is(quiet), "a frame alone must not hide a real app fault");

        // And through a wrapper, because that is how they usually arrive.
        require(FrameworkRace.is(new RuntimeException("wrapped", race)),
                "the race must still be recognised through a cause");

        // A real fault in this app must never be hidden behind that name.
        NullPointerException ours = new NullPointerException(
                "Attempt to invoke virtual method on a null object reference");
        ours.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("com.pocketlinux.MainActivity", "refreshLiveTiles",
                        "MainActivity.java", 1700),
                new StackTraceElement("android.os.Handler", "dispatchMessage", "Handler.java", 106),
        });
        require(!FrameworkRace.is(ours), "a fault in this app must not be treated as Android's");

        require(!FrameworkRace.is(new java.io.IOException("no space left on device")),
                "an ordinary failure must not be treated as Android's");
        require(!FrameworkRace.is(new RuntimeException()), "an empty error is not the race");

        System.out.println("PASS CrashTest (framework race told apart from a real fault)");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

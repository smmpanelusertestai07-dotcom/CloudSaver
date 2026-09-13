package com.pocketagent.mobile;

/** Regression cases for the native account button and the connect action's shared routing. */
public final class ConnectionFlowTest {
    private static int assertions;
    public static void main(String[] args) {
        expect(ConnectionFlow.Step.SETUP, false, false, false, false, false, false, false, false, false, "");
        expect(ConnectionFlow.Step.WAIT_SETUP, false, true, false, false, false, false, false, false, false, "");
        // The last workspace marker can appear before the service finishes. Wait until it releases the job.
        expect(ConnectionFlow.Step.WAIT_SETUP, true, true, true, false, false, false, false, false, false, "");
        expect(ConnectionFlow.Step.PROJECT, true, false, false, false, false, false, false, false, false, "");
        expect(ConnectionFlow.Step.CONNECT, true, false, true, false, false, false, false, false, false, "");
        // The Android service has not acknowledged this first tap yet: don't launch it twice.
        expect(ConnectionFlow.Step.WAIT_AGENT, true, false, true, false, false, false, false, true, false, "");
        expect(ConnectionFlow.Step.WAIT_AGENT, true, false, true, false, true, true, false, false, false, "");
        expect(ConnectionFlow.Step.WAIT_AGENT, true, false, true, false, false, true, false, false, false, "");
        expect(ConnectionFlow.Step.WAIT_AGENT, true, false, true, false, false, true, true, false, false, "");
        // An arriving sign-in URL becomes an actionable button even while auth is still in progress.
        expect(ConnectionFlow.Step.SIGN_IN, true, false, true, false, false, true, true, false, false, "https://auth.openai.com/authorize");
        expect(ConnectionFlow.Step.READY, true, false, true, true, false, false, false, false, false, "");
        // A failed login can be retried; a stale URL must not reopen a broken attempt.
        expect(ConnectionFlow.Step.CONNECT, true, false, true, false, false, true, true, false, true, "https://auth.openai.com/old");
        for (ConnectionFlow.Step step : ConnectionFlow.Step.values()) {
            check(!ConnectionFlow.button(step).isEmpty(), "Every phase needs visible feedback");
            check(ConnectionFlow.waiting(step) == (step == ConnectionFlow.Step.WAIT_SETUP || step == ConnectionFlow.Step.WAIT_AGENT), "Only active waiting phases disable the account button");
        }
        check(ConnectionFlow.button(ConnectionFlow.Step.SIGN_IN).contains("sign-in"), "Sign-in must become a visible action");
        System.out.println("ConnectionFlowTest: " + assertions + " assertions passed");
    }

    private static void expect(ConnectionFlow.Step expected, boolean workspace, boolean setup, boolean project,
                               boolean ready, boolean installing, boolean connecting, boolean auth,
                               boolean pending, boolean error, String url) {
        ConnectionFlow.Step actual = ConnectionFlow.next(workspace, setup, project, ready, installing, connecting, auth, pending, error, url);
        check(actual == expected, "Expected " + expected + ", got " + actual);
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}

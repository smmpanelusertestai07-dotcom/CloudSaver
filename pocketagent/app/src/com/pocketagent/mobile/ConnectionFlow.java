package com.pocketagent.mobile;

/** Shared by the account button and connect action so a prerequisite never starts invisibly. */
final class ConnectionFlow {
    enum Step { SETUP, WAIT_SETUP, PROJECT, CONNECT, WAIT_AGENT, SIGN_IN, READY }

    private ConnectionFlow() { }

    static Step next(boolean workspaceReady, boolean setupBusy, boolean projectReady,
                     boolean connected, boolean installing, boolean connecting,
                     boolean authenticating, boolean requestPending, boolean error,
                     String authUrl) {
        if (!workspaceReady) return setupBusy ? Step.WAIT_SETUP : Step.SETUP;
        if (setupBusy) return Step.WAIT_SETUP;
        if (!projectReady) return Step.PROJECT;
        if (connected) return Step.READY;
        if (!error && authUrl != null && !authUrl.isEmpty()) return Step.SIGN_IN;
        if (installing || requestPending || (!error && (connecting || authenticating))) return Step.WAIT_AGENT;
        return Step.CONNECT;
    }

    static boolean waiting(Step step) { return step == Step.WAIT_SETUP || step == Step.WAIT_AGENT; }

    static String button(Step step) {
        switch (step) {
            case SETUP: return "Set up workspace";
            case WAIT_SETUP: return "Setting up workspace…";
            case PROJECT: return "Create project";
            case WAIT_AGENT: return "Connecting…";
            case SIGN_IN: return "Continue sign-in";
            case READY: return "Refresh account";
            default: return "Connect account";
        }
    }
}

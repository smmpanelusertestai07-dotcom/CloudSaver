package com.pocketagent.mobile;

/** Serial ownership guard for one running agent harness over the shared project files. */
final class AgentSwitchPolicy {
    private AgentSwitchPolicy() { }
    static String blocked(boolean busy, boolean installing, boolean connecting, boolean authenticating,
            boolean sessionOpening, boolean recovering, boolean approvals, boolean voice,
            boolean modelChanging, boolean integrations, boolean sessions, boolean controls, boolean projectTools) {
        if (installing) return "Wait for agent setup to finish.";
        if (authenticating) return "Finish or cancel sign-in before switching agents.";
        if (connecting || sessionOpening || recovering) return "Wait for the current connection to finish.";
        if (voice) return "End the voice conversation before switching agents.";
        if (busy || approvals) return "Finish or stop the current task before switching agents.";
        if (projectTools) return "Wait for project tools to finish changing files.";
        if (modelChanging || integrations || sessions || controls) return "Wait for the current agent action to finish.";
        return "";
    }
    static void requireOrigin(String provider, String project, String accountToken,
            String expectedProvider, String expectedProject, String expectedAccountToken) {
        if (!provider.equals(expectedProvider) || !project.equals(expectedProject))
            throw new IllegalStateException("The active agent or project changed. Choose an agent again.");
        if (expectedAccountToken != null && !accountToken.equals(expectedAccountToken))
            throw new IllegalStateException("The connected account changed. Choose an agent again.");
    }
}

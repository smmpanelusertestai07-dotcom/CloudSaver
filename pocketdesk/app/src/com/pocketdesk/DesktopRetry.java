package com.pocketdesk;

/** Independent startup and per-outage budgets, using a caller-supplied monotonic clock. */
final class DesktopRetry {
    private long deadline;
    private boolean connectedBefore;
    DesktopRetry(long now) { deadline = now + 180_000L; }

    boolean retry(long now, boolean handshakeCompleted, long connectedMillis, boolean runtimeActive) {
        // A successful long-lived session earns a new reconnect budget. Rapidly flapping
        // connections do not, or they would retry forever. Startup never consumes this budget.
        if (handshakeCompleted && (!connectedBefore || connectedMillis >= 30_000L)) {
            deadline = now + 45_000L;
        }
        connectedBefore |= handshakeCompleted;
        return now < deadline && (!connectedBefore || runtimeActive);
    }

    boolean hasConnected() { return connectedBefore; }
}

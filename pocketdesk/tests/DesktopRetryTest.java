package com.pocketlinux;

public final class DesktopRetryTest {
    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        DesktopRetry startup = new DesktopRetry(0);
        check(startup.retry(179_000, false, 0, false), "slow initial startup ended early");
        check(!startup.retry(180_000, false, 0, false), "startup wait has no deadline");
        // Opening at the very end of startup must still allow a complete later reconnect.
        check(startup.retry(181_000, true, 1_000, true), "late startup used reconnect allowance");
        check(startup.retry(225_999, false, 0, true), "reconnect ended before its deadline");
        check(!startup.retry(226_000, true, 50, true), "flapping handshake reset the outage forever");
        check(startup.retry(500_000, true, 120_000, true), "healthy session did not reset retry allowance");
        check(!startup.retry(500_001, false, 0, false), "stopped runtime is being reconnected forever");
        System.out.println("PASS DesktopRetry (late startup, bounded flapping, independent later outages, stopped runtime)");
    }
}

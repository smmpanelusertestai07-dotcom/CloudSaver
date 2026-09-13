package com.pocketagent.mobile;

public final class MessageTimeTest {
    private static int checks;
    private static final long NOW = 1_789_214_400_000L;
    private static void equal(String expected, String actual) {
        checks++;
        if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual);
    }
    public static void main(String[] args) {
        equal("", MessageTime.relative(0, NOW));
        equal("", MessageTime.relative(-1, NOW));
        equal("", MessageTime.relative(NOW, 0));
        equal("Just now", MessageTime.relative(NOW, NOW));
        equal("Just now", MessageTime.relative(NOW + 60000, NOW));
        equal("Just now", MessageTime.relative(Long.MAX_VALUE, NOW));
        equal("Just now", MessageTime.relative(NOW - 59000, NOW));
        equal("1 min ago", MessageTime.relative(NOW - 60000, NOW));
        equal("2 mins ago", MessageTime.relative(NOW - 120000, NOW));
        equal("1 hr ago", MessageTime.relative(NOW - 3600000, NOW));
        equal("2 hrs ago", MessageTime.relative(NOW - 7200000, NOW));
        equal("2 hrs ago", MessageTime.relative((NOW - 7200000) / 1000, NOW));
        equal("1 day ago", MessageTime.relative(NOW - 86400000, NOW));
        equal("2 days ago", MessageTime.relative(NOW - 172800000, NOW));
        equal("1 wk ago", MessageTime.relative(NOW - 604800000, NOW));
        equal("2 wks ago", MessageTime.relative(NOW - 1209600000, NOW));
        equal("1 mo ago", MessageTime.relative(NOW - 2592000000L, NOW));
        equal("1 yr ago", MessageTime.relative(NOW - 31536000000L, NOW));
        equal("", MessageTime.absolute(0));
        if (MessageTime.toMillis(NOW / 1000) != NOW || MessageTime.toMillis(NOW) != NOW)
            throw new AssertionError("timestamp unit normalization");
        System.out.println("MessageTimeTest: " + (checks + 1) + " checks passed");
    }
}

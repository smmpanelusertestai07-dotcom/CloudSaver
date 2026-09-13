package com.pocketagent.mobile;

import java.text.DateFormat;
import java.util.Date;

/** Relative labels derived only from a saved or provider-supplied message timestamp. */
final class MessageTime {
    private MessageTime() { }

    static long toMillis(long timestamp) {
        if (timestamp <= 0) return 0;
        // Provider events use Unix seconds; local chat history uses Unix milliseconds.
        return timestamp < 100_000_000_000L ? timestamp * 1000L : timestamp;
    }

    static String relative(long timestamp, long nowMillis) {
        long time = toMillis(timestamp);
        if (time == 0 || nowMillis <= 0) return "";
        long seconds = time >= nowMillis ? 0 : (nowMillis - time) / 1000;
        if (seconds < 60) return "Just now";
        if (seconds < 3600) return unit(seconds / 60, "min", "mins");
        if (seconds < 86400) return unit(seconds / 3600, "hr", "hrs");
        if (seconds < 604800) return unit(seconds / 86400, "day", "days");
        if (seconds < 2592000) return unit(seconds / 604800, "wk", "wks");
        if (seconds < 31536000) return unit(seconds / 2592000, "mo", "mos");
        return unit(seconds / 31536000, "yr", "yrs");
    }

    static String absolute(long timestamp) {
        long time = toMillis(timestamp);
        return time == 0 ? "" : DateFormat.getDateTimeInstance(DateFormat.MEDIUM,
                DateFormat.SHORT).format(new Date(time));
    }

    private static String unit(long amount, String one, String many) {
        return amount + " " + (amount == 1 ? one : many) + " ago";
    }
}

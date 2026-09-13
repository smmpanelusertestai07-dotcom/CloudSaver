package com.pocketagent.doors;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * What actually happened, kept so the app stops guessing.
 *
 * Two of this app's three doors are documented by their publishers and have never been run on
 * a phone under PRoot. Rather than ship a confident claim either way, every start writes down
 * what it found, and the home screen shows that instead of the guess from Doors.java once
 * there is one. The first person to open a door is running the experiment, and the app should
 * say so honestly and then remember the answer.
 *
 * Nothing here leaves the phone.
 */
final class Probe {
    static final String UNKNOWN = "unknown";
    static final String WORKS = "works";
    static final String FAILED = "failed";

    private Probe() {}

    private static SharedPreferences prefs(Context context) {
        return Ubuntu.prefs(context);
    }

    static String state(Context context, String agentId) {
        return prefs(context).getString("probe_" + agentId, UNKNOWN);
    }

    /** The daemon's own last words, shown when a door failed so the reason is not hidden. */
    static String detail(Context context, String agentId) {
        return prefs(context).getString("probe_detail_" + agentId, "");
    }

    /** How the daemon ended up running: its own service path, or the app supervising it. */
    static String route(Context context, String agentId) {
        return prefs(context).getString("probe_route_" + agentId, "");
    }

    static void record(Context context, String agentId, String state, String detail, String route) {
        prefs(context).edit()
                .putString("probe_" + agentId, state)
                .putString("probe_detail_" + agentId, detail == null ? "" : trim(detail))
                .putString("probe_route_" + agentId, route == null ? "" : route)
                .apply();
    }

    static void clear(Context context, String agentId) {
        prefs(context).edit()
                .remove("probe_" + agentId)
                .remove("probe_detail_" + agentId)
                .remove("probe_route_" + agentId)
                .apply();
    }

    private static String trim(String text) {
        String plain = text.trim();
        return plain.length() > 600 ? plain.substring(plain.length() - 600) : plain;
    }
}

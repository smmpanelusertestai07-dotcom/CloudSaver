package com.pocketdesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.TrafficStats;

import java.util.Calendar;

/**
 * A daily cap on mobile-data use, set by the owner.
 *
 * Android counts this app's bytes for us (TrafficStats, per uid) and never resets them, so a
 * baseline is stored at the start of each day and today's use is the difference. The count
 * covers everything this app's processes send and receive -- the Linux computer included --
 * because those processes run under this app's uid. The cap resets at local midnight, the same
 * moment most daily SIM allowances do.
 */
final class DataBudget {
    static final String KEY_CAP_MB = "data_cap_mb";           // 0 = no cap
    private static final String KEY_DAY = "data_day";
    private static final String KEY_BASE = "data_base_bytes";

    private DataBudget() {}

    static int capMb(SharedPreferences prefs) {
        return prefs.getInt(KEY_CAP_MB, 0);
    }

    /** Bytes this app has moved since local midnight, or -1 when Android cannot count them. */
    static long usedToday(Context context) {
        int uid = context.getApplicationInfo().uid;
        long now = TrafficStats.getUidRxBytes(uid) + TrafficStats.getUidTxBytes(uid);
        if (now < 0 || TrafficStats.getUidRxBytes(uid) == TrafficStats.UNSUPPORTED) return -1;
        SharedPreferences prefs = context.getSharedPreferences(ContainerRuntime.PREFS, Context.MODE_PRIVATE);
        int today = dayStamp();
        long base = prefs.getLong(KEY_BASE, -1);
        // A new day, a reboot (counters restart at zero), or a first run: today starts here.
        if (prefs.getInt(KEY_DAY, -1) != today || base < 0 || now < base) {
            prefs.edit().putInt(KEY_DAY, today).putLong(KEY_BASE, now).apply();
            return 0;
        }
        return now - base;
    }

    /** True when a cap is set, the phone is on mobile data, and today's use has reached it. */
    static boolean exhausted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(ContainerRuntime.PREFS, Context.MODE_PRIVATE);
        int cap = capMb(prefs);
        if (cap <= 0) return false;
        if (!"Mobile data".equals(DeviceProbe.read(context).network)) return false;
        long used = usedToday(context);
        return used >= 0 && used >= cap * 1024L * 1024L;
    }

    private static int dayStamp() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }
}

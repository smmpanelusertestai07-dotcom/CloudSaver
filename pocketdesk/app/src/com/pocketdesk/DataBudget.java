package com.pocketdesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.TrafficStats;

import java.util.Calendar;

/**
 * A daily cap on mobile-data use, set by the owner.
 *
 * Android counts this app's bytes for us (TrafficStats, per uid) but over every network at
 * once, so the bytes are attributed here: each reading compares the counter with the last
 * one, and the difference is added to today's total only when both readings were taken on
 * mobile data. Wi-Fi never counts, exactly as the Settings row promises. The count covers
 * everything this app's processes send and receive -- the Linux computer included -- because
 * they run under this app's uid. The cap resets at local midnight, the same moment most daily
 * SIM allowances do. Readings are taken every few seconds while the home screen is open and
 * with every progress report of the background service.
 */
final class DataBudget {
    static final String KEY_CAP_MB = "data_cap_mb";           // 0 = no cap
    private static final String KEY_DAY = "data_day";
    private static final String KEY_MOBILE_USED = "data_mobile_used";
    private static final String KEY_LAST_TOTAL = "data_last_total";
    private static final String KEY_LAST_MOBILE = "data_last_mobile";

    private DataBudget() {}

    static int capMb(SharedPreferences prefs) {
        return prefs.getInt(KEY_CAP_MB, 0);
    }

    /** Mobile-data bytes this app has moved since local midnight, or -1 when Android cannot count. */
    static long usedToday(Context context) {
        int uid = context.getApplicationInfo().uid;
        long rx = TrafficStats.getUidRxBytes(uid);
        long tx = TrafficStats.getUidTxBytes(uid);
        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED || rx < 0 || tx < 0) return -1;
        long total = rx + tx;
        boolean mobile = "Mobile data".equals(DeviceProbe.read(context).network);
        SharedPreferences prefs = context.getSharedPreferences(ContainerRuntime.PREFS, Context.MODE_PRIVATE);
        int today = dayStamp();
        long used = prefs.getLong(KEY_MOBILE_USED, 0L);
        long lastTotal = prefs.getLong(KEY_LAST_TOTAL, -1L);
        boolean lastMobile = prefs.getBoolean(KEY_LAST_MOBILE, false);
        if (prefs.getInt(KEY_DAY, -1) != today) {
            // A new day: the total starts again from nothing.
            used = 0L;
        } else if (lastTotal >= 0 && total >= lastTotal && lastMobile && mobile) {
            // Both readings on mobile data: everything in between went over it.
            used += total - lastTotal;
        }
        // A reboot restarts the counter at zero (total < lastTotal): nothing is attributed.
        prefs.edit().putInt(KEY_DAY, today).putLong(KEY_MOBILE_USED, used)
                .putLong(KEY_LAST_TOTAL, total).putBoolean(KEY_LAST_MOBILE, mobile).apply();
        return used;
    }

    /** True when a cap is set, the phone is on mobile data, and today's use has reached it. */
    static boolean exhausted(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(ContainerRuntime.PREFS, Context.MODE_PRIVATE);
        int cap = capMb(prefs);
        if (cap <= 0) return false;
        if (!"Mobile data".equals(DeviceProbe.read(context).network)) return false;
        long used = usedToday(context);
        return used >= 0 && used >= cap * 1_000_000L;
    }

    private static int dayStamp() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.YEAR) * 1000 + c.get(Calendar.DAY_OF_YEAR);
    }
}

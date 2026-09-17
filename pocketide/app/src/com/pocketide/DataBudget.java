package com.pocketide;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.TrafficStats;

import java.util.Calendar;

/**
 * A daily ceiling on how much mobile data this app spends on itself.
 *
 * The owner this was written for is mostly on mobile data, and the first run of this app fetches
 * several hundred megabytes. Spending someone's month in one afternoon because they tapped
 * "Set up" on the wrong network is not an acceptable way for software to behave, so there is a
 * limit, it is theirs to set, and Wi-Fi is never counted against it.
 *
 * The figure comes from Android's own per-UID counter. It is not exact -- the platform rounds,
 * and it counts everything this process sends including the workspace's own downloads, which is
 * precisely what should be counted here.
 */
final class DataBudget {
    static final String[] LABELS = {
            "No limit", "200 MB", "500 MB", "1 GB", "2 GB", "5 GB"};
    static final int[] VALUES = {0, 200, 500, 1000, 2000, 5000};

    private DataBudget() {}

    static int capMb(Context context) {
        return Prefs.of(context).getInt(Prefs.DATA_CAP_MB, 0);
    }

    static void setCapMb(Context context, int mb) {
        Prefs.of(context).edit().putInt(Prefs.DATA_CAP_MB, mb).apply();
    }

    static String label(Context context) {
        int cap = capMb(context);
        for (int i = 0; i < VALUES.length; i++) if (VALUES[i] == cap) return LABELS[i];
        return LABELS[0];
    }

    /** Bytes this app has sent or received since midnight, on any network. */
    static long usedToday(Context context) {
        SharedPreferences prefs = Prefs.of(context);
        long now = counter();
        if (now < 0) return -1;
        int today = dayStamp();
        int storedDay = prefs.getInt(Prefs.DATA_USED_DAY, 0);
        long baseline = prefs.getLong(Prefs.DATA_USED_BYTES, -1);
        if (storedDay != today || baseline < 0 || baseline > now) {
            // A new day, a first run, or a reboot -- the kernel counter restarts at boot, so a
            // baseline larger than the counter means the phone restarted, not that data was
            // refunded. Either way today starts from here.
            prefs.edit().putInt(Prefs.DATA_USED_DAY, today).putLong(Prefs.DATA_USED_BYTES, now).apply();
            return 0;
        }
        return now - baseline;
    }

    /**
     * True when a download should wait. Wi-Fi is never limited; without a limit nothing waits.
     */
    static boolean exhausted(Context context) {
        int cap = capMb(context);
        if (cap <= 0) return false;
        if (DeviceProbe.isWifi(context)) return false;
        long used = usedToday(context);
        return used >= 0 && used >= cap * 1_000_000L;
    }

    /** True when the owner asked for Wi-Fi only and this is not Wi-Fi. */
    static boolean blockedByWifiOnly(Context context) {
        return Prefs.of(context).getBoolean(Prefs.WIFI_ONLY, false)
                && !DeviceProbe.isWifi(context);
    }

    /** The one sentence a screen should show when a download cannot start. */
    static String whyBlocked(Context context) {
        if (blockedByWifiOnly(context)) {
            return "Set-up is waiting for Wi-Fi, because “Download on Wi-Fi only” is on "
                    + "in Settings.";
        }
        if (exhausted(context)) {
            return "Today's mobile data limit is used up. Downloads continue after midnight, on "
                    + "Wi-Fi, or with a higher limit in Settings.";
        }
        return "";
    }

    private static long counter() {
        long received = TrafficStats.getUidRxBytes(android.os.Process.myUid());
        long sent = TrafficStats.getUidTxBytes(android.os.Process.myUid());
        if (received == TrafficStats.UNSUPPORTED || sent == TrafficStats.UNSUPPORTED) return -1;
        return received + sent;
    }

    private static int dayStamp() {
        Calendar now = Calendar.getInstance();
        return now.get(Calendar.YEAR) * 1000 + now.get(Calendar.DAY_OF_YEAR);
    }
}

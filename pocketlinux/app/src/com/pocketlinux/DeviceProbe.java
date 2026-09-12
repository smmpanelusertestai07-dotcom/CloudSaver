package com.pocketlinux;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.Locale;

final class DeviceProbe {
    /**
     * How long one reading is handed out again before the phone is asked afresh.
     *
     * One read registers a sticky battery broadcast, stats the filesystem, and asks the power
     * and connectivity services: several trips out to Android's own services. The home screen's
     * timer, the data budget and the background service's guards each used to ask separately
     * within the same instant, so a single five-second tick cost five full readings and a long
     * setup on a budget phone ran tens of thousands of them.
     *
     * A second and a half is chosen to be shorter than anything the guards react to. Battery
     * percent, free space, temperature and the network name do not move meaningfully in that
     * time, so a guard protecting the phone still sees a genuine change on its next look; what
     * it no longer does is pay for the same reading five times over.
     */
    private static final long CACHE_MS = 1_500L;

    /**
     * The last reading and when it was taken, shared by every caller in the process.
     *
     * Everything read here belongs to the phone and the app as a whole, not to one screen, so
     * which Context asked makes no difference. The time is the boot clock rather than the wall
     * clock, because a clock correction must not make a stale reading look fresh.
     */
    private static volatile DeviceProbe cached;
    private static volatile long cachedAt;

    final String model;
    final String androidVersion;
    final String abi;
    final long totalRam;
    final long freeStorage;
    final int batteryPercent;
    final float batteryTempC;
    final String network;
    final int thermalStatus;

    private DeviceProbe(String model, String androidVersion, String abi, long totalRam,
                        long freeStorage, int batteryPercent, float batteryTempC,
                        String network, int thermalStatus) {
        this.model = model;
        this.androidVersion = androidVersion;
        this.abi = abi;
        this.totalRam = totalRam;
        this.freeStorage = freeStorage;
        this.batteryPercent = batteryPercent;
        this.batteryTempC = batteryTempC;
        this.network = network;
        this.thermalStatus = thermalStatus;
    }

    static DeviceProbe read(Context context) {
        DeviceProbe recent = cached;
        if (recent != null && SystemClock.elapsedRealtime() - cachedAt < CACHE_MS) return recent;

        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(memory);
        else memory.totalMem = Runtime.getRuntime().maxMemory();

        StatFs storage = new StatFs(context.getFilesDir().getAbsolutePath());
        long free = storage.getAvailableBytes();

        Intent battery = null;
        try {
            battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        } catch (RuntimeException ignored) {}
        int level = -1;
        float temp = -1;
        if (battery != null) {
            int rawLevel = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            level = scale > 0 ? Math.round(rawLevel * 100f / scale) : -1;
            temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10) / 10f;
        }

        int thermal = PowerManager.THERMAL_STATUS_NONE;
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                try { thermal = pm.getCurrentThermalStatus(); }
                catch (RuntimeException ignored) {}
            }
        }

        String manufacturer = Build.MANUFACTURER == null ? "Android" : Build.MANUFACTURER;
        String deviceModel = Build.MODEL == null ? "device" : Build.MODEL;
        if (manufacturer.trim().isEmpty()) manufacturer = "Android";
        if (deviceModel.trim().isEmpty()) deviceModel = "device";
        String niceModel = manufacturer.substring(0, 1).toUpperCase(Locale.ROOT)
                + manufacturer.substring(1) + " " + deviceModel;
        String abi = Build.SUPPORTED_ABIS.length == 0 ? "unknown" : Build.SUPPORTED_ABIS[0];
        DeviceProbe fresh = new DeviceProbe(niceModel, "Android " + Build.VERSION.RELEASE, abi,
                memory.totalMem, free, level, temp, networkName(context), thermal);
        // The reading is published before its timestamp on purpose: two threads reading at once
        // may then both do the work, which is only wasteful, whereas the other order could hand
        // out an older reading under a fresh time.
        cached = fresh;
        cachedAt = SystemClock.elapsedRealtime();
        return fresh;
    }

    /**
     * Framebuffer size for the Linux desktop, in landscape orientation.
     *
     * Matching the phone's own pixel count keeps the picture sharp at 1:1 instead of scaling a
     * smaller desktop up, which is what made text look soft. Size comes from the screen; how big
     * things *look* is set by the desktop's DPI instead.
     */
    static int[] desktopGeometry(Context context, int longSideCap) {
        int width = 1280;
        int height = 720;
        try {
            WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            int rawWidth = 0;
            int rawHeight = 0;
            if (manager != null && Build.VERSION.SDK_INT >= 30) {
                try {
                    android.graphics.Rect bounds = manager.getMaximumWindowMetrics().getBounds();
                    rawWidth = bounds.width();
                    rawHeight = bounds.height();
                } catch (Throwable ignored) {
                    rawWidth = 0;
                }
            }
            if (rawWidth <= 0 && manager != null) {
                DisplayMetrics metrics = new DisplayMetrics();
                manager.getDefaultDisplay().getRealMetrics(metrics);
                rawWidth = metrics.widthPixels;
                rawHeight = metrics.heightPixels;
            }
            if (rawWidth > 0 && rawHeight > 0) {
                width = Math.max(rawWidth, rawHeight);
                height = Math.min(rawWidth, rawHeight);
            }
        } catch (Throwable ignored) {
            // Keep the 1280x720 default when the display cannot be measured.
        }
        if (longSideCap > 0 && width > longSideCap) {
            height = Math.round(height * (longSideCap / (float) width));
            width = longSideCap;
        }
        return new int[]{Math.max(800, width - (width % 2)), Math.max(480, height - (height % 2))};
    }

    static boolean isWifi(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    static boolean hasInternet(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    static boolean isArm64() {
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) return true;
        }
        return false;
    }

    /**
     * Decimal units, like Android's own Settings screen: a phone that says 12.4 GB free there
     * says 12.4 GB free here, and "4 GB needed" means the same 4 GB in both places.
     */
    static String formatBytes(long bytes) {
        if (bytes < 0) return "Unknown";
        if (bytes >= 1_000_000_000L) return String.format(Locale.ROOT, "%.1f GB", bytes / 1e9);
        if (bytes >= 1_000_000L) return String.format(Locale.ROOT, "%.0f MB", bytes / 1e6);
        if (bytes >= 1_000L) return String.format(Locale.ROOT, "%.0f KB", bytes / 1e3);
        return bytes + " B";
    }

    /** Human transfer rate, e.g. "1.4 MB/s". */
    static String formatRate(long bytesPerSecond) {
        if (bytesPerSecond <= 0) return "";
        if (bytesPerSecond >= 1_000_000L) return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1e6);
        return String.format(Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1e3);
    }

    /** Remaining time in plain words, e.g. "about 4 min left". */
    static String formatEta(long seconds) {
        if (seconds <= 0 || seconds > 86400) return "";
        if (seconds < 60) return "about " + seconds + " sec left";
        long minutes = (seconds + 59) / 60;
        if (minutes < 60) return "about " + minutes + " min left";
        return "about " + ((minutes + 59) / 60) + " hr left";
    }

    /**
     * True when the phone is plugged in, which relaxes the battery guards.
     *
     * Read fresh every time, unlike the full probe above. This is asked only at the moments a
     * decision is made -- before a start, before a download -- and never on a timer, and an
     * owner who plugs the phone in expects the very next tap to work.
     */
    static boolean isCharging(Context context) {
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery == null) return false;
            int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            return plugged != 0;
        } catch (RuntimeException error) {
            return false;
        }
    }

    static String thermalName(int value) {
        switch (value) {
            case PowerManager.THERMAL_STATUS_LIGHT: return "Light";
            case PowerManager.THERMAL_STATUS_MODERATE: return "Moderate";
            case PowerManager.THERMAL_STATUS_SEVERE: return "Severe";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "Critical";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "Emergency";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "Shutdown";
            default: return "Normal";
        }
    }

    private static String networkName(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "Unknown network";
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "Offline";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Mobile data";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
        return "Connected";
    }
}

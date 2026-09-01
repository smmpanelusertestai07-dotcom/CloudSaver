package com.pocketdesk;

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
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.Locale;

final class DeviceProbe {
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
        return new DeviceProbe(niceModel, "Android " + Build.VERSION.RELEASE, abi,
                memory.totalMem, free, level, temp, networkName(context), thermal);
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

    static String formatBytes(long bytes) {
        if (bytes < 0) return "Unknown";
        if (bytes >= 1073741824L) return String.format(Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
        if (bytes >= 1048576L) return String.format(Locale.ROOT, "%.0f MB", bytes / 1048576.0);
        if (bytes >= 1024L) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    /** Human transfer rate, e.g. "1.4 MB/s". */
    static String formatRate(long bytesPerSecond) {
        if (bytesPerSecond <= 0) return "";
        if (bytesPerSecond >= 1048576L) return String.format(Locale.ROOT, "%.1f MB/s", bytesPerSecond / 1048576.0);
        return String.format(Locale.ROOT, "%.0f KB/s", bytesPerSecond / 1024.0);
    }

    /** Remaining time in plain words, e.g. "about 4 min left". */
    static String formatEta(long seconds) {
        if (seconds <= 0 || seconds > 86400) return "";
        if (seconds < 60) return "about " + seconds + " sec left";
        long minutes = (seconds + 59) / 60;
        if (minutes < 60) return "about " + minutes + " min left";
        return "about " + ((minutes + 59) / 60) + " hr left";
    }

    /** True when the phone is plugged in, which relaxes the battery guards. */
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

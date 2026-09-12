package com.pocketlinux;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;

/**
 * The phone's fitness to run the Linux computer right now, in one place.
 *
 * A dot on the Settings tab is a claim on attention, so the rule for it is written once and
 * kept narrow: only things the owner can fix from Settings. Free space, heat and the data limit
 * are said on the home tab where the numbers are, and are not counted twice. Nothing the owner
 * chose on purpose (a data limit, Wi-Fi only) is ever treated as a problem.
 */
final class Health {
    final boolean notificationsOff;
    final boolean batteryRestricted;
    final boolean spaceLow;
    final boolean hot;
    final boolean dataCapReached;
    final boolean notCompatible;
    /** The app lock switched itself off because the phone's own lock was removed. */
    final boolean lockDisabledItself;

    private Health(boolean notificationsOff, boolean batteryRestricted, boolean spaceLow, boolean hot,
                   boolean dataCapReached, boolean notCompatible, boolean lockDisabledItself) {
        this.notificationsOff = notificationsOff;
        this.batteryRestricted = batteryRestricted;
        this.spaceLow = spaceLow;
        this.hot = hot;
        this.dataCapReached = dataCapReached;
        this.notCompatible = notCompatible;
        this.lockDisabledItself = lockDisabledItself;
    }

    /** Cheap system calls only: this runs every few seconds while the home screen is open. */
    static Health read(Context context, DeviceProbe probe, boolean compatible) {
        boolean notificationsOff = Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean batteryRestricted = power == null
                || !power.isIgnoringBatteryOptimizations(context.getPackageName());
        boolean spaceLow = probe.freeStorage < (ContainerRuntime.isInstalled(context)
                ? DeviceCheck.LOW_FREE_BYTES : DeviceCheck.MIN_FREE_BYTES);
        boolean hot = probe.thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
                || probe.batteryTempC >= 44f;
        SharedPreferences prefs = context.getSharedPreferences(ContainerRuntime.PREFS, Context.MODE_PRIVATE);
        boolean lockNotice = prefs.getBoolean(ContainerRuntime.KEY_LOCK_NOTICE, false);
        return new Health(notificationsOff, batteryRestricted, spaceLow, hot,
                DataBudget.exhausted(context), !compatible, lockNotice);
    }

    /** Settings gets a dot only for what Settings can fix. */
    boolean settingsDot() {
        return notificationsOff || batteryRestricted || lockDisabledItself;
    }
}

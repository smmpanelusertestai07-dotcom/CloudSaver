package com.pocketagent.doors;

import android.app.ActivityManager;
import android.content.Context;

/**
 * What this phone can be asked to hold.
 *
 * The viewer keeps a second copy of the screen so a half-applied frame is never drawn, and on a
 * small phone that copy is what pushes it over. The one question asked here decides how much
 * memory the viewer spends on that, which is the difference between a working editor and one
 * the system kills in the middle of a change.
 */
final class DeviceCheck {

    /**
     * True on a phone with under three gigabytes, or one Android itself calls low-memory.
     *
     * The reference phone for this app has 3.9 GB and is not one, which is deliberate: it should
     * not quietly take the cheaper path on the device everything is tested on.
     */
    static boolean isSmallPhone(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null && manager.isLowRamDevice()) return true;
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(memory);
        return memory.totalMem > 0 && memory.totalMem < 3_000_000_000L;
    }

    private DeviceCheck() {}
}

package com.pocketlinux;

import android.content.pm.ActivityInfo;

/**
 * Which way up the screen is allowed to be -- one rule, used by both screens.
 *
 * Two things were wrong before this existed, and both came from Android's confusingly named
 * constants rather than from anything visible in the code:
 *
 *   * "Portrait" was SCREEN_ORIENTATION_USER_PORTRAIT, which is not portrait: it is "portrait,
 *     either way up". Put the phone down face-up and pick it up the other way and the screen
 *     turned over, so the camera ended up at the bottom and every gesture came from the wrong
 *     edge. A phone has one right way up in portrait, and that is SCREEN_ORIENTATION_PORTRAIT.
 *   * "Auto-rotate" on the desktop was SCREEN_ORIENTATION_FULL_SENSOR, whose whole purpose is to
 *     add the upside-down rotation that the phone would not normally use. Same result.
 *
 * SENSOR is what auto-rotate should have been all along: it follows the phone through the three
 * rotations a phone actually has, and -- unlike UNSPECIFIED or the USER_* family -- it keeps
 * following them when the phone's own rotation lock is on. That matters here, because the app
 * carries its own rotation setting: an owner who picked "Auto-rotate" in PocketLinux has said
 * what they want, and having a system toggle silently overrule it is the bug they reported.
 *
 * Landscape is the one place both directions are right, so it stays sensor-driven: a phone held
 * in landscape has no natural top, and every video player on Android does the same.
 */
final class ScreenRotation {

    static final String AUTO = "auto";
    static final String PORTRAIT = "portrait";
    static final String LANDSCAPE = "landscape";

    private ScreenRotation() { }

    /**
     * Which way round the Linux desktop is born.
     *
     * The computer used to take its shape from the phone alone, so "Portrait" reached the phone
     * window and stopped there: the desktop inside was still a landscape one, shown sideways
     * until something happened to resize it. Only Auto-rotate asks the phone now.
     */
    static boolean portraitDesktop(String setting, boolean phoneIsPortrait) {
        if (PORTRAIT.equals(setting)) return true;
        if (LANDSCAPE.equals(setting)) return false;
        return phoneIsPortrait;
    }

    /** The Android orientation for one of PocketLinux's three settings. */
    static int of(String setting) {
        if (PORTRAIT.equals(setting)) return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (LANDSCAPE.equals(setting)) return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        return ActivityInfo.SCREEN_ORIENTATION_SENSOR;
    }

    /**
     * The orientation that pins the screen exactly as it is now, for the viewer's rotation lock.
     * Reverse landscape is kept apart from landscape here: locking is a promise that nothing
     * moves, and a phone held the other way round in landscape must not flip while it is on.
     */
    static int pin(int currentRotation, boolean landscape) {
        if (landscape) {
            return currentRotation == android.view.Surface.ROTATION_180
                    || currentRotation == android.view.Surface.ROTATION_270
                    ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        }
        return currentRotation == android.view.Surface.ROTATION_180
                ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    }
}

package com.pocketide;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;

/**
 * Which way up the app is allowed to be.
 *
 * It needs a setting rather than a default because of one detail of how Android works: a phone
 * with auto-rotate switched off never offers an app landscape at all, however wide the app would
 * like to be. An owner who turns that off for their home screen and their messages has, without
 * meaning to, also decided that the editor can never have the extra width -- and landscape is
 * worth roughly twice the columns here, which is the difference between a file tree beside the
 * code and no file tree.
 *
 * So there are three choices and the middle one is not "on":
 *
 *   Follow the phone   the phone decides, including its own rotation lock. The default, because
 *                      overruling a lock the owner set, on the strength of a setting they never
 *                      touched, is its own bug.
 *   Portrait           held upright whatever the phone is doing.
 *   Landscape          held sideways whatever the phone is doing, either way round, so the
 *                      cable and the camera can be on whichever side suits.
 *
 * SENSOR_LANDSCAPE rather than LANDSCAPE for the third: plain LANDSCAPE picks one of the two
 * sideways orientations and stays there, so half the time the phone has to be turned the way the
 * app chose rather than the way it is being held.
 */
final class Rotation {

    static final String AUTO = "auto";
    static final String PORTRAIT = "portrait";
    static final String LANDSCAPE = "landscape";

    static final String[] LABELS = {"Follow the phone", "Portrait", "Landscape"};
    static final String[] VALUES = {AUTO, PORTRAIT, LANDSCAPE};
    static final int[] ICONS =
            {R.drawable.ic_rotate, R.drawable.ic_phone, R.drawable.ic_desktop};

    private Rotation() {}

    static String choice(Context context) {
        return Prefs.of(context).getString(Prefs.ROTATION, AUTO);
    }

    static void set(Context context, String value) {
        Prefs.of(context).edit().putString(Prefs.ROTATION, value).apply();
    }

    static String label(Context context) {
        String current = choice(context);
        for (int i = 0; i < VALUES.length; i++) if (VALUES[i].equals(current)) return LABELS[i];
        return LABELS[0];
    }

    /**
     * Applied from onStart rather than only onCreate, so changing it in Settings reaches a
     * screen that is already open instead of waiting for it to be opened again.
     */
    static void apply(Activity activity) {
        try {
            activity.setRequestedOrientation(of(choice(activity)));
        } catch (Throwable refused) {
            // Some manufacturer builds refuse a request from a screen that is going away.
            // Which way up the window is, is not worth a crash.
        }
    }

    static int of(String setting) {
        if (PORTRAIT.equals(setting)) return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        if (LANDSCAPE.equals(setting)) return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    }
}

package com.pocketide;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/**
 * Light, dark, or whatever the phone is doing.
 *
 * Android's own words are used for the choices -- "System default", not a phrase invented here
 * -- so a setting in this app reads exactly like the same setting in the phone's Settings.
 *
 * The system bars are painted to match the page rather than left at the platform default,
 * because a cream page under a black status bar is the single thing that makes an Android app
 * look unfinished, and it costs four lines.
 */
final class Theme {
    static final String[] LABELS = {"System default", "Light", "Dark"};
    static final String[] VALUES = {"system", "light", "dark"};
    static final int[] ICONS =
            {R.drawable.ic_auto_mode, R.drawable.ic_light_mode, R.drawable.ic_dark_mode};

    private Theme() {}

    static String choice(Context context) {
        return Prefs.of(context).getString(Prefs.THEME, "system");
    }

    static void set(Context context, String value) {
        Prefs.of(context).edit().putString(Prefs.THEME, value).apply();
    }

    static String label(Context context) {
        String current = choice(context);
        for (int i = 0; i < VALUES.length; i++) if (VALUES[i].equals(current)) return LABELS[i];
        return LABELS[0];
    }

    /** Call from onCreate, before any view is added. Paints the window and the system bars. */
    static void apply(Activity activity) {
        boolean dark = Ui.dark(activity);
        Window window = activity.getWindow();
        window.setStatusBarColor(Ui.bg(dark));
        window.setNavigationBarColor(Ui.bg(dark));
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        // Dark icons on a light bar, light icons on a dark one. Without this the clock is
        // white on cream on every light phone.
        if (dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }
}

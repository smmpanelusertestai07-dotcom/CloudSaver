package com.pocketide;

import android.app.Activity;
import android.content.Context;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * Light, dark, or whatever the phone is doing -- and where the system bars are allowed to draw.
 *
 * Android's own words are used for the choices ("System default", not a phrase invented here)
 * so a setting in this app reads exactly like the same setting in the phone's Settings.
 *
 * The system bars are the part that was wrong, and it was wrong in a way that only shows on a
 * real phone. This app targets SDK 35, and an app targeting 35 on Android 15 is drawn edge to
 * edge whether it asks to be or not: setStatusBarColor and setNavigationBarColor became no-ops,
 * and the window simply starts at the top of the screen and ends at the bottom of it.
 *
 * With no inset handling -- and there was none anywhere in this app -- that means the clock and
 * the battery are painted over the app's own title, and the gesture bar is painted over the
 * bottom row of destinations, so the lower part of every tab's 48 dp target cannot be tapped.
 * Two owners reported the symptom as "the status bar colour merges" and "the navigation bar at
 * the bottom is wrong". Both are this.
 *
 * fitBars() below is the answer: the app asks the window for the insets, gives its top bar that
 * much extra padding on top and its bottom bar that much on the bottom, and paints both. The
 * system bars then sit on the app's own colour instead of over its content, which is also what
 * makes the seam between them visible again.
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

    /** Call from onCreate, before any view is added. */
    static void apply(Activity activity) {
        boolean dark = Ui.dark(activity);
        Window window = activity.getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        // Honoured up to Android 14 and ignored from 15, where the app paints the strip behind
        // each bar itself. Kept for the versions that still listen: the nav bar takes the CARD
        // colour rather than the page, because the app's own bottom bar is card, and a system
        // bar a shade off the bar above it is a seam an owner notices without knowing why.
        if (Build.VERSION.SDK_INT < 35) {
            window.setStatusBarColor(Ui.bg(dark));
            window.setNavigationBarColor(Ui.card(dark));
        }
        setBarIcons(window, dark);
    }

    /**
     * Dark icons on a light bar, light icons on a dark one.
     *
     * Without it the clock is white on cream on every light phone. The Android 11+ call is used
     * where it exists because the flags it replaces are deprecated and behave inconsistently on
     * 15; the old path stays for 10.
     */
    private static void setBarIcons(Window window, boolean dark) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int appearance = dark ? 0
                        : WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(appearance,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
            return;
        }
        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decor.setSystemUiVisibility(flags);
    }

    /**
     * Gives the app's own bars the room the system bars take, so nothing is drawn underneath
     * one and nothing is left unreachable beneath the gesture bar.
     *
     * Either bar may be null -- the editor has no top bar of its own, and the set-up screen has
     * no bottom one. The sides are padded on the root because a phone held sideways puts the
     * gesture bar and often a camera cutout on one edge, and a row of buttons that runs under
     * either is a row with a dead end.
     */
    static void fitBars(final View root, final View topBar, final View bottomBar) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
                left = bars.left;
                right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
            }
            view.setPadding(left, 0, right, 0);
            if (topBar != null) {
                topBar.setPadding(topBar.getPaddingLeft(), top,
                        topBar.getPaddingRight(), topBar.getPaddingBottom());
            }
            if (bottomBar != null) {
                bottomBar.setPadding(bottomBar.getPaddingLeft(), bottomBar.getPaddingTop(),
                        bottomBar.getPaddingRight(), bottom);
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    /**
     * The same, for a screen whose content runs to both edges and has no bar of its own.
     *
     * Used by the editor: the WebView is given the top inset as padding so the page does not
     * start under the clock, and the toolbar along the bottom takes the gesture bar's height.
     */
    static void fitContent(final View root, final View content, final View bottomBar) {
        fitBars(root, content, bottomBar);
    }
}

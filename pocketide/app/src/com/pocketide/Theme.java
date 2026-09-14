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

        // The window background comes from the phone's night setting; everything the app draws
        // comes from the app's OWN theme setting. Set this app to Dark on a phone in Light mode
        // and the window was white for one frame before a dark interface was painted over it.
        // That flash is what an owner described as garbled text on opening: it is the previous
        // frame and the next one visible at once.
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.bg(dark)));

        // Honoured up to Android 14 and ignored from 15, where the app paints the strip behind
        // each bar itself. Kept for the versions that still listen. Both take the PAGE colour:
        // the app's own bottom bar floats over the page now, so what meets the system bar is
        // the page, and a system bar a shade off it is a seam an owner notices without knowing
        // why.
        if (Build.VERSION.SDK_INT < 35) {
            window.setStatusBarColor(Ui.bg(dark));
            window.setNavigationBarColor(Ui.bg(dark));
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
            int top, bottom, left, right, keyboard;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
                left = bars.left;
                right = bars.right;
                keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
                keyboard = 0;
            }
            // The keyboard is an inset too, and on Android 15 an app drawn edge to edge is no
            // longer resized for it by adjustResize alone: the search box on Agents and the
            // editor's own toolbar sat under the keyboard. The root gives up the keyboard's
            // height at the bottom, and the bottom bar keeps only whatever the gesture bar
            // still needs above that -- which, while the keyboard is up, is nothing. On the
            // versions that still resize the window themselves the keyboard inset arrives
            // already consumed, as zero, so nothing is padded twice.
            view.setPadding(left, 0, right, keyboard);
            bottom = Math.max(0, bottom - keyboard);
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
     * For a screen with no bar of its own at the top: the root itself takes the inset.
     *
     * This existed as fitContent() and had ZERO call sites, which is the whole of the bug it
     * was written to prevent. Only the main screen ever asked for insets at all -- so the
     * editor, Set up and About all still drew at y=0 on Android 15, with the clock over their
     * titles and the gesture bar over the row of buttons along the bottom. Exactly the fault
     * an owner reported for the main screen, still present on the other three, because the
     * helper for them was never wired up.
     *
     * Passing the root as its own top bar is deliberate and works because of the order inside
     * fitBars: the sides are written first, then the top is written on top of them.
     */
    static void fitScreen(final View root, final View bottomBar) {
        fitBars(root, root, bottomBar);
    }
}

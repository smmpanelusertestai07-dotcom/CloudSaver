package com.pocketide;

import android.app.Activity;
import android.os.Build;

/**
 * Back on Android 13 and later.
 *
 * The manifest opts this app into predictive back (enableOnBackInvokedCallback), and an app
 * that has opted in never has onBackPressed() called on Android 13 or later -- the gesture
 * goes to whatever was registered here, or straight out of the app when nothing was. Both
 * screens with their own idea of Back used to override onBackPressed() alone, so on a new
 * phone Back closed the whole app from Settings and left the editor without giving its own
 * history a chance, while an old phone did what the code said.
 *
 * The Android 13 types live in a nested class of their own, so a phone without them never has
 * to load them and the outer class verifies cleanly everywhere.
 */
final class Back {
    private Back() {}

    static boolean predictive() { return Build.VERSION.SDK_INT >= 33; }

    /**
     * Sends Back to {@code onBack} while registered. Returns the handle for unregister(), or
     * null below Android 13, where onBackPressed() still runs and nothing is needed.
     */
    static Object register(Activity activity, Runnable onBack) {
        if (!predictive()) return null;
        return Api33.register(activity, onBack);
    }

    static void unregister(Activity activity, Object handle) {
        if (handle == null || !predictive()) return;
        Api33.unregister(activity, handle);
    }

    private static final class Api33 {
        static Object register(Activity activity, Runnable onBack) {
            android.window.OnBackInvokedCallback callback = onBack::run;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }

        static void unregister(Activity activity, Object handle) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    (android.window.OnBackInvokedCallback) handle);
        }
    }
}

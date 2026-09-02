package com.pocketdesk;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The app lock: the phone's own fingerprint or PIN, asked whenever PocketDesk comes back to
 * the front.
 *
 * It covers the whole app. A lock on the home screen alone was a lock someone walked around:
 * the desktop, with every AI app signed in, came straight back from the recent-apps list. Now
 * both screens show a locked screen with an Unlock button until the phone says it is you, and
 * the lock re-arms every time the app leaves the foreground (App counts its screens). A
 * cancelled prompt leaves the locked screen in place rather than closing the app, so the way
 * back in is always one tap away.
 */
final class AppLock {
    /** Locked from the moment the process starts, so the first opening asks. */
    private static volatile boolean locked = true;
    private static final int REQUEST_CREDENTIAL = 7701;
    /** The caller waiting on the PIN screen, which answers through onActivityResult. */
    private static Callback pending;

    private AppLock() {}

    static boolean enabled(Context context) {
        return context.getSharedPreferences(ContainerRuntime.PREFS, Context.MODE_PRIVATE)
                .getBoolean(ContainerRuntime.KEY_APP_LOCK, false);
    }

    static boolean hasScreenLock(Context context) {
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isDeviceSecure();
    }

    /** Called by App when no screen of this app has been in front for a moment. */
    static void relock() { locked = true; }

    static boolean isLocked(Context context) {
        if (!enabled(context)) return false;
        if (!hasScreenLock(context)) {
            // The phone's own lock was removed, and removing it required knowing it. The app
            // lock turns itself off visibly (Settings shows a note) rather than becoming a
            // door with no key.
            SharedPreferences prefs = context.getSharedPreferences(ContainerRuntime.PREFS, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(ContainerRuntime.KEY_APP_LOCK, false)
                    .putBoolean(ContainerRuntime.KEY_LOCK_NOTICE, true).apply();
            return false;
        }
        return locked;
    }

    interface Callback { void done(boolean unlocked); }

    /**
     * Shows the locked screen over {@code root} and asks the phone at once. The overlay is
     * removed only when the phone says yes; Cancel leaves it, with its Unlock button.
     */
    static View show(Activity activity, FrameLayout root, Runnable onUnlocked) {
        View existing = root.findViewWithTag("pocketdesk-lock");
        if (existing != null) return existing;
        Context context = activity;
        boolean dark = true;
        LinearLayout screen = new LinearLayout(context);
        screen.setTag("pocketdesk-lock");
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER);
        screen.setBackgroundColor(Ui.DARK_BG);
        screen.setClickable(true);   // swallows touches meant for what is underneath
        // Above everything else in the same frame, the desktop's floating chip included.
        screen.setElevation(Ui.dp(context, 24));
        screen.setPadding(Ui.dp(context, 32), Ui.dp(context, 32), Ui.dp(context, 32), Ui.dp(context, 32));
        // Nothing underneath is shown in the recent-apps thumbnail while locked.
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.icon_in_app);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        screen.addView(logo, new LinearLayout.LayoutParams(Ui.dp(context, 72), Ui.dp(context, 72)));
        TextView title = Ui.bold(context, "PocketDesk is locked", 22, Ui.DARK_TEXT);
        title.setGravity(Gravity.CENTER);
        screen.addView(title, Ui.matchWrap(context, 18));
        TextView note = Ui.text(context, "Unlock with your fingerprint or the phone's PIN. "
                + "Your Linux computer and everything signed in on it stay as they were.", 14, Ui.DARK_MUTED);
        note.setGravity(Gravity.CENTER);
        screen.addView(note, Ui.matchWrap(context, 8));
        TextView outcome = Ui.text(context, "", 13, Ui.WARNING);
        outcome.setGravity(Gravity.CENTER);
        screen.addView(outcome, Ui.matchWrap(context, 12));
        Button unlock = Ui.primaryButton(context, "Unlock", R.drawable.ic_lock);
        LinearLayout.LayoutParams unlockLp = new LinearLayout.LayoutParams(
                Ui.dp(context, 220), ViewGroup.LayoutParams.WRAP_CONTENT);
        unlockLp.topMargin = Ui.dp(context, 22);
        screen.addView(unlock, unlockLp);

        Runnable ask = () -> prompt(activity, unlocked -> {
            if (unlocked) {
                locked = false;
                if (screen.getParent() == root) {
                    root.removeView(screen);
                    activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                    if (onUnlocked != null) onUnlocked.run();
                }
            } else {
                outcome.setText("Not unlocked. Tap Unlock to try again.");
            }
        });
        unlock.setOnClickListener(v -> ask.run());
        root.addView(screen, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        screen.post(ask);
        return screen;
    }

    /** True when a locked screen is currently covering {@code root}. */
    static boolean showing(FrameLayout root) {
        return root != null && root.findViewWithTag("pocketdesk-lock") != null;
    }

    /**
     * The phone's own prompt: fingerprint where enrolled, PIN otherwise. When the biometric
     * prompt itself cannot run on this phone, the plain PIN screen is used instead, so the
     * lock never depends on a sensor being available.
     */
    static void prompt(Activity activity, Callback callback) {
        if (!hasScreenLock(activity)) { locked = false; callback.done(true); return; }
        // Some Android builds throw SecurityException from authenticate() when the app does not
        // hold USE_BIOMETRIC, even to reach the PIN fallback. If the permission is not granted,
        // skip the biometric prompt entirely and use the phone's own PIN screen, which needs no
        // permission. This is what turned an old build's app-lock into a crash on resume.
        boolean canBiometric = Build.VERSION.SDK_INT < 29
                || activity.checkSelfPermission(android.Manifest.permission.USE_BIOMETRIC)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!canBiometric) {
            if (!credentialScreen(activity, callback)) callback.done(false);
            return;
        }
        try {
            android.hardware.biometrics.BiometricPrompt.Builder builder =
                    new android.hardware.biometrics.BiometricPrompt.Builder(activity)
                            .setTitle("PocketDesk is locked")
                            .setSubtitle("Unlock with your fingerprint or PIN");
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setAllowedAuthenticators(
                        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
                                | android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                builder.setDeviceCredentialAllowed(true);
            }
            builder.build().authenticate(new CancellationSignal(), activity.getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override public void onAuthenticationSucceeded(
                                android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                            locked = false;
                            callback.done(true);
                        }
                        @Override public void onAuthenticationError(int code, CharSequence message) {
                            boolean cancelled = code == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                                    || code == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                                    || code == 13 /* negative button */;
                            if (cancelled) { callback.done(false); return; }
                            // Anything else (no sensor, sensor busy, lockout): the PIN screen.
                            if (!credentialScreen(activity, callback)) callback.done(false);
                        }
                    });
        } catch (Throwable error) {
            Crash.save(activity, error);
            if (!credentialScreen(activity, callback)) callback.done(false);
        }
    }

    /** Android's own PIN / pattern / password screen; the result comes back through onActivityResult. */
    private static boolean credentialScreen(Activity activity, Callback callback) {
        KeyguardManager keyguard = (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard == null) return false;
        Intent intent = keyguard.createConfirmDeviceCredentialIntent("PocketDesk is locked",
                "Enter the phone's PIN, pattern or password");
        if (intent == null) return false;
        try {
            pending = callback;
            activity.startActivityForResult(intent, REQUEST_CREDENTIAL);
            return true;
        } catch (Throwable error) {
            pending = null;
            return false;
        }
    }

    /**
     * Activities hand their onActivityResult here; true when it was the PIN screen answering.
     * The caller that asked (the locked screen, or the Settings switch being turned on) gets
     * the answer it was waiting for.
     */
    static boolean handleResult(Activity activity, FrameLayout root, int request, int result, Runnable onUnlocked) {
        if (request != REQUEST_CREDENTIAL) return false;
        boolean ok = result == Activity.RESULT_OK;
        Callback waiting = pending;
        pending = null;
        if (ok) {
            locked = false;
            View screen = root == null ? null : root.findViewWithTag("pocketdesk-lock");
            if (screen != null) root.removeView(screen);
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            if (onUnlocked != null) onUnlocked.run();
        }
        if (waiting != null) waiting.done(ok);
        return true;
    }
}

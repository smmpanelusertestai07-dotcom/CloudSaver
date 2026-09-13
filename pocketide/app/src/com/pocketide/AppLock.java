package com.pocketide;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The app lock: the phone's own fingerprint or PIN, asked whenever PocketIDE comes to the front.
 *
 * It matters more here than in most apps. What is behind this screen is not a list of files --
 * it is an editor with three coding agents signed into their publishers' accounts, a terminal
 * with the owner's git credentials in it, and whatever source they have been paid to write.
 * Anyone holding the unlocked phone has all of it.
 *
 * Five things in here are not obvious, and each one is a bug this code already survived in the
 * app it was carried from:
 *
 *   1. The lock covers every screen, not the first one. A lock on the home screen alone was a
 *      lock that the recent-apps list walked straight around into the editor.
 *
 *   2. FLAG_SECURE is set whenever the lock is ON, not only while the locked screen is up.
 *      Android takes the recents thumbnail as a screen goes to the background -- which is
 *      before the lock is raised on the way back. The whole editor, with whatever was open in
 *      it, was sitting in the thumbnail of a locked app.
 *
 *   3. BiometricPrompt is only attempted when USE_BIOMETRIC is actually granted. Some Android
 *      builds throw SecurityException out of authenticate() without it -- even to reach the PIN
 *      fallback -- and that turned an app lock into a crash on resume.
 *
 *   4. Trips the app itself starts -- the phone's own Settings, a file picker, the credential
 *      screen -- say so first through expectReturn(), so the owner is not asked for a
 *      fingerprint in the middle of their own action. Everything else re-locks immediately,
 *      because a lock with a grace period is a lock anyone can walk past.
 *
 *   5. If the phone's screen lock is removed, this turns itself off visibly instead of becoming
 *      a door with no key. Removing it required knowing it, so nothing is lost by trusting that.
 */
final class AppLock {

    /** Locked from the moment the process starts, so the first opening asks. */
    private static volatile boolean locked = true;
    private static final int REQUEST_CREDENTIAL = 7701;
    private static final String TAG = "pocketide-lock";

    /** The caller waiting on the PIN screen, which answers through onActivityResult. */
    private static Callback pending;
    private static volatile long expectingReturnUntil;

    private AppLock() {}

    interface Callback { void done(boolean unlocked); }

    static boolean enabled(Context context) {
        return Prefs.of(context).getBoolean(Prefs.APP_LOCK, false);
    }

    static boolean hasScreenLock(Context context) {
        KeyguardManager keyguard =
                (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isDeviceSecure();
    }

    /** Called when no screen of this app is in front any more. */
    static void relock() { locked = true; }

    /** The app is sending the owner out on purpose and expects them straight back. */
    static void expectReturn() {
        expectingReturnUntil = android.os.SystemClock.elapsedRealtime() + 120_000L;
    }

    static boolean expectingReturn() {
        return android.os.SystemClock.elapsedRealtime() < expectingReturnUntil;
    }

    static void returned() { expectingReturnUntil = 0L; }

    static boolean isLocked(Context context) {
        if (!enabled(context)) return false;
        if (!hasScreenLock(context)) {
            SharedPreferences prefs = Prefs.of(context);
            prefs.edit()
                    .putBoolean(Prefs.APP_LOCK, false)
                    .putBoolean(Prefs.LOCK_NOTICE, true)
                    .apply();
            return false;
        }
        return locked;
    }

    /**
     * Hides the window from the recents list whenever the lock is on.
     *
     * See note 2 in the class comment: this is deliberately not tied to the locked screen being
     * visible, because the snapshot Android keeps is taken before the lock can be raised.
     */
    static void applyWindowSecurity(Activity activity) {
        if (activity == null) return;
        if (enabled(activity)) {
            activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        } else {
            activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    /**
     * Puts the locked screen over {@code root} and asks the phone at once.
     *
     * The overlay comes off only when the phone says yes. Cancel leaves it there with its
     * Unlock button, rather than closing the app -- the way back in is always one tap away.
     */
    static View show(Activity activity, FrameLayout root, Runnable onUnlocked) {
        View existing = root.findViewWithTag(TAG);
        if (existing != null) return existing;

        boolean dark = Ui.dark(activity);
        LinearLayout screen = new LinearLayout(activity);
        screen.setTag(TAG);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER);
        screen.setBackgroundColor(Ui.bg(dark));
        screen.setClickable(true);            // swallows touches meant for what is underneath
        screen.setElevation(Ui.dp(activity, 24));
        int pad = Ui.dp(activity, 32);
        screen.setPadding(pad, pad, pad, pad);
        activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);

        screen.addView(Shell.mark(activity, 72));

        TextView title = Ui.bold(activity, "PocketIDE is locked", 22f, Ui.text(dark));
        title.setGravity(Gravity.CENTER);
        screen.addView(title, Ui.wide(activity, 18));

        TextView note = Ui.text(activity,
                "Unlock with your fingerprint or the phone's PIN. Linux and everything "
                        + "signed in on it stay exactly as they were.", 14f, Ui.muted(dark));
        note.setGravity(Gravity.CENTER);
        screen.addView(note, Ui.wide(activity, 8));

        TextView outcome = Ui.text(activity, "", 13f, Ui.needsYou(dark));
        outcome.setGravity(Gravity.CENTER);
        screen.addView(outcome, Ui.wide(activity, 12));

        TextView unlock = Ui.primaryButton(activity, "Unlock", dark);
        LinearLayout.LayoutParams unlockParams = new LinearLayout.LayoutParams(
                Ui.dp(activity, 220), ViewGroup.LayoutParams.WRAP_CONTENT);
        unlockParams.topMargin = Ui.dp(activity, 22);
        screen.addView(unlock, unlockParams);

        Runnable ask = () -> prompt(activity, unlocked -> {
            if (unlocked) {
                locked = false;
                if (screen.getParent() == root) {
                    root.removeView(screen);
                    applyWindowSecurity(activity);
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

    static boolean showing(FrameLayout root) {
        return root != null && root.findViewWithTag(TAG) != null;
    }

    /**
     * The phone's own prompt: fingerprint where enrolled, PIN otherwise.
     *
     * When the biometric prompt cannot run on this phone, the plain PIN screen is used instead,
     * so the lock never depends on a sensor being present or working.
     */
    static void prompt(Activity activity, Callback callback) {
        if (!hasScreenLock(activity)) {
            locked = false;
            callback.done(true);
            return;
        }
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
                            .setTitle("PocketIDE is locked")
                            .setSubtitle("Unlock with your fingerprint or PIN");
            if (Build.VERSION.SDK_INT >= 30) {
                builder.setAllowedAuthenticators(
                        android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK
                                | android.hardware.biometrics.BiometricManager.Authenticators
                                        .DEVICE_CREDENTIAL);
            } else {
                builder.setDeviceCredentialAllowed(true);
            }
            builder.build().authenticate(new CancellationSignal(), activity.getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override public void onAuthenticationSucceeded(
                                android.hardware.biometrics.BiometricPrompt.AuthenticationResult r) {
                            locked = false;
                            callback.done(true);
                        }

                        @Override public void onAuthenticationError(int code, CharSequence message) {
                            boolean cancelled = code == android.hardware.biometrics.BiometricPrompt
                                            .BIOMETRIC_ERROR_USER_CANCELED
                                    || code == android.hardware.biometrics.BiometricPrompt
                                            .BIOMETRIC_ERROR_CANCELED
                                    || code == 13 /* the negative button */;
                            if (cancelled) {
                                callback.done(false);
                                return;
                            }
                            // No sensor, sensor busy, locked out: fall back to the PIN screen.
                            if (!credentialScreen(activity, callback)) callback.done(false);
                        }
                    });
        } catch (Throwable error) {
            Crash.save(activity, error);
            if (!credentialScreen(activity, callback)) callback.done(false);
        }
    }

    /** Android's own PIN / pattern / password screen; answers through onActivityResult. */
    private static boolean credentialScreen(Activity activity, Callback callback) {
        KeyguardManager keyguard =
                (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
        if (keyguard == null) return false;
        Intent intent = keyguard.createConfirmDeviceCredentialIntent("PocketIDE is locked",
                "Enter the phone's PIN, pattern or password");
        if (intent == null) return false;
        try {
            pending = callback;
            expectReturn();            // the phone's own credential screen, not a way out
            activity.startActivityForResult(intent, REQUEST_CREDENTIAL);
            return true;
        } catch (Throwable cannotStart) {
            pending = null;
            return false;
        }
    }

    /**
     * Activities hand their onActivityResult here; true when it was the PIN screen answering.
     * Whoever asked -- the locked screen, or the Settings switch being turned on -- gets its
     * answer.
     */
    static boolean handleResult(Activity activity, FrameLayout root, int request, int result,
                                Runnable onUnlocked) {
        if (request != REQUEST_CREDENTIAL) return false;
        boolean ok = result == Activity.RESULT_OK;
        Callback waiting = pending;
        pending = null;
        if (ok) {
            locked = false;
            View screen = root == null ? null : root.findViewWithTag(TAG);
            if (screen != null) root.removeView(screen);
            applyWindowSecurity(activity);
            if (onUnlocked != null) onUnlocked.run();
        }
        if (waiting != null) waiting.done(ok);
        return true;
    }
}

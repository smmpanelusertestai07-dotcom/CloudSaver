package com.pocketide;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.os.Bundle;

/**
 * Two things happen before any screen exists: the crash handler is armed, and the notification
 * channel is created.
 *
 * The channel is created here rather than when the service starts because Android will not let
 * a foreground service post into a channel that does not exist yet, and a service that cannot
 * post its notification is a service Android kills within seconds. Creating it at app start
 * costs nothing and removes the race entirely.
 */
public final class App extends Application {
    static final String CHANNEL_WORKSPACE = "Linux";

    /**
     * How many of this app's screens are in front.
     *
     * The app lock re-arms the moment this reaches zero, which is what makes it a lock rather
     * than a formality. Counting is necessary because moving between screens takes the old one
     * down after the new one comes up, and a naive "onPause means gone" would re-lock on every
     * single navigation.
     */
    private static volatile int screensInFront;

    /** True while a screen of this app is on the phone's screen. See Installer.launch. */
    static boolean inFront() { return screensInFront > 0; }

    /**
     * Each step on its own, and the count first.
     *
     * The process is a few milliseconds old here and nothing has been drawn: a failure in
     * this method is the one kind the recovery screen could not report, because the count it
     * works from had not been taken yet. So the count comes before anything that could fail,
     * and every step after it is caught, recorded and skipped rather than allowed to end the
     * process -- an app with no notification channel is worse than one with a channel, but it
     * is an app that opens and can say what went wrong.
     */
    @Override public void onCreate() {
        super.onCreate();
        try {
            Boot.starting(this);
        } catch (Throwable evenThat) {
            // Preferences unreadable. The screens below still try, and say so if they cannot.
        }
        try {
            Crash.arm(this);
        } catch (Throwable notArmed) {
            // The platform's own handler stays in place.
        }
        try {
            Exits.noteStart(this);
        } catch (Throwable unreadable) {
            Crash.save(this, unreadable);
        }
        try {
            watchForegroundState();
        } catch (Throwable notWatched) {
            Crash.save(this, notWatched);
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    NotificationChannel channel = new NotificationChannel(
                            CHANNEL_WORKSPACE, "Linux", NotificationManager.IMPORTANCE_LOW);
                    channel.setDescription(
                            "Shows while the development environment is running, with a Stop "
                                    + "button. Low importance: it never makes a sound.");
                    channel.setShowBadge(false);
                    manager.createNotificationChannel(channel);
                }
            }
        } catch (Throwable noChannel) {
            Crash.save(this, noChannel);
        }
    }

    /**
     * Re-locks the app as soon as none of its screens is in front.
     *
     * The exception is a trip the app itself started -- the phone's own Settings, a file
     * picker, the credential screen. Those call AppLock.expectReturn() first, and asking for a
     * fingerprint in the middle of the owner's own action would be the app fighting them.
     */
    private void watchForegroundState() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) {
                screensInFront++;
                AppLock.returned();
            }

            @Override public void onActivityStopped(Activity activity) {
                screensInFront = Math.max(0, screensInFront - 1);
                if (screensInFront > 0) return;
                // An errand is not a way out: the phone's own Settings, the PIN screen. But it
                // is timed, and AppLock.returned() re-locks if it took too long.
                if (AppLock.expectingReturn()) AppLock.leftForErrand();
                else AppLock.relock();
            }

            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle out) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }
}

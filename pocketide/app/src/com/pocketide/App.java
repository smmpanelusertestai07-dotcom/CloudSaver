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
    private int screensInFront;

    @Override public void onCreate() {
        super.onCreate();
        Crash.arm(this);
        watchForegroundState();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_WORKSPACE, "Linux", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(
                        "Shows while the development environment is running, with a Stop button. "
                                + "Low importance: it never makes a sound.");
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
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
                if (screensInFront == 0 && !AppLock.expectingReturn()) AppLock.relock();
            }

            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle out) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }
}

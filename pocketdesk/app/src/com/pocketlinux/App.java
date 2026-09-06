package com.pocketlinux;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Installs the crash recorder and re-arms the app lock whenever the app leaves the foreground.
 *
 * Android owns the main Looper. Starting a second Looper.loop() inside it leaves a failed
 * ActivityTransaction half executed: the framework can then receive a top-resumed callback for
 * an Activity record that no longer exists. A fatal UI exception is therefore recorded and handed
 * back to Android's normal uncaught-exception path, which gives the next launch a clean process.
 */
public final class App extends Application {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int screensInFront;
    private final Runnable relock = () -> { if (screensInFront == 0) AppLock.relock(); };

    @Override public void onCreate() {
        super.onCreate();
        Crash.install(this);
        // The notification category exists from the first launch, so Settings -> Notifications
        // lists it before the first set-up rather than only after the service has run once.
        LinuxService.ensureNotificationChannel(this);
        watchForeground();
    }

    /**
     * A lock that only ever asks once is not a lock. When the last screen of the app leaves
     * the front the lock re-arms, after a short pause that lets a rotation or a system dialog
     * (permissions, the fingerprint prompt itself) pass without counting as leaving.
     */
    private void watchForeground() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity activity) {
                screensInFront++;
                handler.removeCallbacks(relock);
            }
            @Override public void onActivityStopped(Activity activity) {
                screensInFront = Math.max(0, screensInFront - 1);
                if (screensInFront == 0) handler.postDelayed(relock, 2_000L);
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityResumed(Activity activity) {}
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }

}

package com.pocketdesk;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Installs the crash recorder, keeps a stray UI-thread exception from killing the process, and
 * re-arms the app lock whenever the app leaves the foreground.
 *
 * A single unhandled exception on the main thread used to end the whole app: the desktop screen
 * vanished back to the home screen, or Android showed "PocketDesk keeps stopping". Re-entering the
 * main Looper after an exception keeps the app alive and leaves the stack in the error report,
 * which is far more useful than a dead process.
 */
public final class App extends Application {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int screensInFront;
    private final Runnable relock = () -> { if (screensInFront == 0) AppLock.relock(); };

    @Override public void onCreate() {
        super.onCreate();
        Crash.install(this);
        keepMainThreadAlive();
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

    private void keepMainThreadAlive() {
        new Handler(Looper.getMainLooper()).post(() -> {
            while (true) {
                try {
                    Looper.loop();
                    return;                      // the looper quit on purpose
                } catch (Throwable error) {
                    Crash.save(this, error);
                    try {
                        Toast.makeText(this,
                                "PocketDesk hit an error and kept running. See Last error report.",
                                Toast.LENGTH_LONG).show();
                    } catch (Throwable ignored) {
                        // Never let the report about a failure cause another one.
                    }
                }
            }
        });
    }
}

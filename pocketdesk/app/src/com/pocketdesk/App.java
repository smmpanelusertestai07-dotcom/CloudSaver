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

    /**
     * How many errors in a row are caught before the app is allowed to die properly.
     *
     * Catching for ever sounds safer than it is: an error that repeats every time the looper
     * turns would spin the processor and empty the battery while the screen looked normal. After
     * this many in one minute, the next one is left alone -- Android ends the app, the report is
     * on disk, and the owner opens it instead of watching the phone get hot.
     */
    private static final int CAUGHT_BEFORE_GIVING_UP = 12;
    private static final long CAUGHT_WINDOW_MS = 60_000L;

    private void keepMainThreadAlive() {
        new Handler(Looper.getMainLooper()).post(() -> {
            int caught = 0;
            long since = android.os.SystemClock.elapsedRealtime();
            while (true) {
                try {
                    Looper.loop();
                    return;                      // the looper quit on purpose
                } catch (Throwable error) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - since > CAUGHT_WINDOW_MS) {
                        caught = 0;
                        since = now;
                    }
                    if (++caught > CAUGHT_BEFORE_GIVING_UP) throw error;
                    Crash.save(this, error);
                    // Android's own teardown races are recorded but never announced: telling the
                    // owner their computer hit an error, for something no app can prevent and
                    // nothing they did caused, is a false alarm about their own phone.
                    if (Crash.isFrameworkRace(error)) continue;
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

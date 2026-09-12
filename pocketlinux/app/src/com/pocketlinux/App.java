package com.pocketlinux;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Installs the crash recorder, re-arms the app lock whenever the app leaves the foreground, and
 * writes down Android's warnings that the phone is running out of memory.
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

    /** When the last memory warning was written down, so a bad minute cannot fill the log. */
    private long lastPressureNoteAt;
    /**
     * Android repeats the warning for as long as the phone is short of memory, and on a 2 GB
     * phone that can be minutes at a time. Recording every one of them would rotate the 96 KB
     * runtime log and push out the evidence of whatever actually went wrong.
     */
    private static final long PRESSURE_NOTE_GAP_MS = 60_000L;

    /**
     * Android's warning that it is about to start ending processes, and what this app gives back.
     *
     * The phones this app is built for are the ones that send it, so it is worth being exact
     * about which of the big things in this process can go and which cannot. The screens can:
     * Android rebuilds them from their saved state and reclaims them at these levels on its own,
     * without being asked. The pair of framebuffers the viewer paints the desktop from cannot,
     * because a desktop that goes blank to survive a warning has taken from the owner the very
     * thing the warning was trying to save. The Ubuntu computer cannot, for the same reason, and
     * neither can the last few lines it printed -- a few kilobytes that are the only explanation
     * anyone gets when a job has failed.
     *
     * What is left, and what was missing, is the record. The owner is told after the event that
     * "Android ended PocketLinux while the desktop was open", and nothing beside that sentence
     * showed that Android had given warning first, or how little memory the phone had when it did.
     */
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // These levels are not numbered in order of seriousness -- RUNNING_CRITICAL is 15 and
        // BACKGROUND is 40 -- so "level >= something" picks out the calm ones and misses the
        // urgent one. The two named here are the two that mean Android is about to start ending
        // processes, this one included.
        if (level != TRIM_MEMORY_RUNNING_CRITICAL && level != TRIM_MEMORY_COMPLETE) return;
        long now = SystemClock.elapsedRealtime();
        if (lastPressureNoteAt != 0L && now - lastPressureNoteAt < PRESSURE_NOTE_GAP_MS) return;
        lastPressureNoteAt = now;
        // Every runtime sample carries the phone's free and total memory, which is the whole
        // point of writing one here: this is the only moment the app is told that Android is
        // out of room, and the figures beside it are what make the next report answerable.
        RuntimeDiagnostics.sample(this, level == TRIM_MEMORY_COMPLETE
                        ? "Android memory warning: this app is next in line to be ended"
                        : "Android memory warning: the phone is running out of memory",
                ProotProcess.trackingSummary());
    }
}

package com.pocketdesk;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * Installs the crash recorder, and keeps a stray UI-thread exception from killing the process.
 *
 * A single unhandled exception on the main thread used to end the whole app: the desktop screen
 * vanished back to the home screen, or Android showed "PocketDesk keeps stopping". Re-entering the
 * main Looper after an exception keeps the app alive and leaves the stack in the error report,
 * which is far more useful than a dead process.
 */
public final class App extends Application {

    @Override public void onCreate() {
        super.onCreate();
        Crash.install(this);
        keepMainThreadAlive();
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

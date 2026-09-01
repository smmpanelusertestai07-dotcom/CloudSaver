package com.pocketdesk;

import android.app.Application;

/** Installs the crash recorder before any screen or service runs. */
public final class App extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Crash.install(this);
    }
}

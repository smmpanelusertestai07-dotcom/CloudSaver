package com.pocketagent.doors;

import android.app.Application;

/** Creates the notification channel before anything can need it. */
public final class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DoorService.ensureChannel(this);
    }
}

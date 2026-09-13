package com.pocketagent.doors;

import android.app.Application;

/**
 * Creates the notification channel, and keeps the workspace's resolver on the phone's network.
 *
 * A set-up runs for long enough to cross between mobile data and Wi-Fi, and the container's
 * resolver has to move with it -- so this watches for the change rather than reading the
 * network once at start.
 */
public final class App extends Application {
    private Dns dns;

    @Override
    public void onCreate() {
        super.onCreate();
        DoorService.ensureChannel(this);
        dns = new Dns(this);
        dns.start();
    }
}

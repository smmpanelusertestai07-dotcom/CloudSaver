package com.pocketide;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

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
    static final String CHANNEL_WORKSPACE = "workspace";

    @Override public void onCreate() {
        super.onCreate();
        Crash.arm(this);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_WORKSPACE, "Workspace", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription(
                        "Shows while the development environment is running, with a Stop button. "
                                + "Low importance: it never makes a sound.");
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
        }
    }
}

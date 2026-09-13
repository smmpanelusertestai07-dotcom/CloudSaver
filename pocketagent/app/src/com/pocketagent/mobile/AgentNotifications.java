package com.pocketagent.mobile;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

/** Generic event alerts only. Notifications cannot approve tools, reveal prompts or change projects. */
final class AgentNotifications {
    private static final String PREFS = "agent_notification_preferences";
    private static final String SEEN = "seen_event_hashes";
    private static final int NOTICE = 4860;
    private AgentNotifications() {}

    static boolean enabled(Context context, String kind) {
        return AgentNotificationPolicy.category(kind) && prefs(context).getBoolean(kind, true);
    }
    static void setEnabled(Context context, String kind, boolean value) {
        if (!AgentNotificationPolicy.category(kind)) return;
        prefs(context).edit().putBoolean(kind, value).apply();
        if (!value) {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            try {
                if (manager != null) for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications())
                    if (channel(kind).equals(notification.getNotification().getChannelId())) manager.cancel(notification.getTag(), notification.getId());
            } catch (RuntimeException unavailable) { /* The saved choice still applies to future events. */ }
        }
    }
    static void ensureChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        for (String kind : new String[]{"complete", "approval", "error"}) {
            NotificationChannel channel = new NotificationChannel(channel(kind), title(kind), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Generic PocketAgent agent alerts. Message and file contents are hidden.");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            manager.createNotificationChannel(channel);
        }
    }
    static boolean systemAllowed(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return manager != null && manager.areNotificationsEnabled()
                && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED);
    }
    static void completed(Context context, String provider, String project, String session, String event) { safeDeliver(context, "complete", provider, project, session, event); }
    static void approval(Context context, String provider, String project, String session, String event) { safeDeliver(context, "approval", provider, project, session, event); }
    static void error(Context context, String provider, String project, String session, String event) { safeDeliver(context, "error", provider, project, session, event); }
    static void clearApproval(Context context, String provider, String project, String session, String event) {
        try {
            String key = AgentNotificationPolicy.eventKey("approval", provider, project, session, event);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (!key.isEmpty() && manager != null) manager.cancel(key, NOTICE);
        } catch (RuntimeException unavailable) { /* Notification policy must not interrupt approval resolution. */ }
    }

    private static void safeDeliver(Context context, String kind, String provider, String project, String session, String event) {
        try { deliver(context, kind, provider, project, session, event); }
        catch (RuntimeException unavailable) { /* An Android alert failure must never fail an agent turn. */ }
    }

    private static synchronized void deliver(Context context, String kind, String provider, String project, String session, String event) {
        String key = AgentNotificationPolicy.eventKey(kind, provider, project, session, event);
        String next = AgentNotificationPolicy.remember(prefs(context).getString(SEEN, ""), key);
        if (next == null) return;
        // Record even disabled alerts: enabling notifications later must not replay old events.
        if (!prefs(context).edit().putString(SEEN, next).commit()) return;
        if (!enabled(context, kind) || !systemAllowed(context)) return;
        try {
            ensureChannels(context);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return;
            // Keep a bounded set of event cards; leave all foreground-service notices intact.
            java.util.ArrayList<android.service.notification.StatusBarNotification> cards = new java.util.ArrayList<>();
            for (android.service.notification.StatusBarNotification active : manager.getActiveNotifications())
                if (active.getId() == NOTICE) cards.add(active);
            java.util.Collections.sort(cards, (a,b) -> Long.compare(a.getPostTime(), b.getPostTime()));
            while (cards.size() >= 12) { android.service.notification.StatusBarNotification old = cards.remove(0); manager.cancel(old.getTag(), old.getId()); }
            Intent open = new Intent(context, DeskActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent tap = PendingIntent.getActivity(context, NOTICE, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification publicVersion = new Notification.Builder(context, channel(kind)).setSmallIcon(R.drawable.ic_stat_pocketagent)
                    .setContentTitle("PocketAgent").setContentText("Agent update").build();
            Notification notification = new Notification.Builder(context, channel(kind))
                    .setSmallIcon(R.drawable.ic_stat_pocketagent).setContentTitle(title(kind))
                    .setContentText("Open PocketAgent to review.").setContentIntent(tap).setAutoCancel(true)
                    .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
                    .setCategory("error".equals(kind) ? Notification.CATEGORY_ERROR : Notification.CATEGORY_STATUS)
                    .setTimeoutAfter("approval".equals(kind) ? 2 * 60 * 60 * 1000L : 24 * 60 * 60 * 1000L).build();
            manager.notify(key, NOTICE, notification);
        } catch (RuntimeException unavailable) { /* Android permission/channel policy can change at any time. */ }
    }
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    private static String channel(String kind) { return "agent_events_" + kind; }
    private static String title(String kind) {
        if ("complete".equals(kind)) return "Task completed";
        if ("approval".equals(kind)) return "Approval needed";
        return "Agent error";
    }
}

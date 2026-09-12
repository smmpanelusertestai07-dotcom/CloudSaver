package com.pocketlinux;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Carries an install made under the old "pocketdesk" spelling over to "pocketlinux".
 *
 * The name was only ever a name, but it was also a path: the settings file, the state directory
 * inside Ubuntu, the sockets the viewer connects to, the markers that record which set-up steps
 * already finished. Renaming the product without moving those would read, to the app, as a phone
 * it had never run on -- a second 500 MB download over mobile data, a second hour of set-up, and
 * every signed-in app inside the container gone. So the old names are moved to the new ones once,
 * the leftovers of the old spelling are deleted, and after that this class does nothing at all.
 *
 * It runs on the main thread at process start on purpose. Everything it touches is a rename or an
 * unlink inside the app's own storage, and the set-up code it protects can begin one tick later.
 */
final class Rename {
    private static final String OLD_PREFS = "pocketdesk_preferences";
    private static final String KEY_DONE = "renamed_to_pocketlinux_v1";

    private Rename() {}

    static void migrate(Context context) {
        SharedPreferences now = context.getSharedPreferences(ContainerRuntime.PREFS, Context.MODE_PRIVATE);
        if (now.getBoolean(KEY_DONE, false)) return;
        try {
            carrySettings(context, now);
            carryContainer(ContainerRuntime.rootfs(context));
        } catch (Throwable ignored) {
            // A half-carried install is still better than a refused start, and the pieces that
            // did move stay moved. The flag is written either way so this runs exactly once.
        }
        now.edit().putBoolean(KEY_DONE, true).apply();
    }

    /** Every stored setting, copied key by key; the old file is then dropped. */
    private static void carrySettings(Context context, SharedPreferences now) {
        SharedPreferences old = context.getSharedPreferences(OLD_PREFS, Context.MODE_PRIVATE);
        Map<String, ?> stored = old.getAll();
        if (stored.isEmpty()) return;
        SharedPreferences.Editor edit = now.edit();
        for (Map.Entry<String, ?> entry : stored.entrySet()) {
            String key = entry.getKey();
            if (now.contains(key)) continue;   // anything already written here wins
            Object value = entry.getValue();
            if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof String) edit.putString(key, (String) value);
        }
        edit.apply();
        old.edit().clear().apply();
    }

    /**
     * The state Ubuntu keeps under the old name, and the copies of the old programs. The
     * directories are moved so nothing inside them is lost; the programs and pictures are
     * deleted, because this build writes its own fresh copies under the new name at every start.
     */
    private static void carryContainer(File root) {
        if (!new File(root, "etc/os-release").isFile()) return;   // nothing installed yet
        moveAside(root, "var/lib/pocketdesk", "var/lib/pocketlinux");
        moveAside(root, "home/coder/.pocketdesk", "home/coder/.pocketlinux");
        moveAside(root, "home/coder/.config/pocketdesk", "home/coder/.config/pocketlinux");
        moveAside(root, "usr/local/share/pocketdesk", "usr/local/share/pocketlinux");
        dropOldCopies(new File(root, "usr/local/bin"), "pocketdesk-");
        dropOldCopies(new File(root, "usr/share/pixmaps"), "pocketdesk-");
        dropOldCopies(new File(root, "usr/share/backgrounds"), "pocketdesk.");
        // Launchers written by the old build point at /usr/local/bin/pocketdesk-*, which no
        // longer exists. They all carry the marker line, and the menu is rebuilt at every start.
        dropOldLaunchers(new File(root, "home/coder/.local/share/applications"));
        dropOldLaunchers(new File(root, "home/coder/Desktop"));
    }

    private static void moveAside(File root, String oldPath, String newPath) {
        File from = new File(root, oldPath);
        File to = new File(root, newPath);
        if (!from.exists()) return;
        if (to.exists()) { drop(from); return; }   // the new build already got there
        File parent = to.getParentFile();
        if (parent != null) parent.mkdirs();
        if (!from.renameTo(to)) drop(from);
    }

    private static void dropOldCopies(File folder, String prefix) {
        File[] children = folder.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.getName().startsWith(prefix)) drop(child);
        }
    }

    private static void dropOldLaunchers(File folder) {
        File[] children = folder.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.getName().endsWith(".desktop")) continue;
            String body = read(child);
            if (body.contains("X-PocketDesk=1") || body.contains("/usr/local/bin/pocketdesk-")) {
                child.delete();
            }
        }
    }

    private static void drop(File path) {
        try {
            Trees.delete(path);
        } catch (IOException ignored) {
            // Leftovers of the old name are harmless; nothing reads them any more.
        }
    }

    private static String read(File file) {
        try {
            byte[] bytes = new byte[(int) Math.min(file.length(), 8192L)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int got = 0, step;
                while (got < bytes.length && (step = in.read(bytes, got, bytes.length - got)) > 0) got += step;
                return new String(bytes, 0, got, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            return "";
        }
    }
}

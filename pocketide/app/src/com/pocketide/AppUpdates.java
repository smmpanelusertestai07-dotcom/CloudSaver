package com.pocketide;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Whether a newer PocketIDE exists, asked of the place releases are published.
 *
 * Everything else in this app keeps itself current -- Ubuntu's fixes, the editor, the
 * extensions -- and the app itself was the one thing that did not even know it could be out of
 * date. A sideloaded app has no store to tell it. So once a day it reads the repository's list
 * of releases, looks for tags with this app's own prefix, and remembers the newest one. That is
 * the whole of what leaves the phone: one request for a public list, carrying no account, no
 * identifier and nothing about the owner.
 *
 * The tag prefix is shared with the workflow that publishes releases -- pocketide.yml creates
 * "pocketide-v<version>" -- and tests/updates.py holds the two to the same string, because a
 * checker looking for one spelling while the publisher writes another is a checker that says
 * "up to date" for ever.
 *
 * Installing is the owner's tap, never automatic: Android does not let an app replace itself
 * silently, and would not be right to. The row opens the APK's own download link in the phone's
 * browser, and Android's installer takes it from there. Because every release is signed with
 * the same key, it installs over the copy already on the phone and nothing in Linux is touched.
 */
final class AppUpdates {

    static final String RELEASES =
            "https://api.github.com/repos/smmpanelusertestai07-dotcom/CloudSaver/releases?per_page=30";
    static final String TAG_PREFIX = "pocketide-v";
    static final long CHECK_EVERY_MS = 24L * 60 * 60 * 1000;
    static final long RETRY_AFTER_MS = 60L * 60 * 1000;

    /** The newest published release, as last read. */
    static final class Status {
        final String latest;
        final String pageUrl;
        final String apkUrl;
        final long apkBytes;
        final long checkedAt;

        Status(String latest, String pageUrl, String apkUrl, long apkBytes, long checkedAt) {
            this.latest = latest;
            this.pageUrl = pageUrl;
            this.apkUrl = apkUrl;
            this.apkBytes = apkBytes;
            this.checkedAt = checkedAt;
        }

        boolean everChecked() { return checkedAt > 0; }

        /** True only when a newer version has actually been seen. Unknown is not news. */
        boolean newer() {
            return !latest.isEmpty() && compare(latest, BuildFacts.VERSION_NAME) > 0;
        }
    }

    private static volatile boolean running;

    private AppUpdates() {}

    static boolean enabled(Context context) {
        return Prefs.of(context).getBoolean(Prefs.APP_UPDATE_CHECK, true);
    }

    static void setEnabled(Context context, boolean on) {
        Prefs.of(context).edit().putBoolean(Prefs.APP_UPDATE_CHECK, on).apply();
    }

    static Status last(Context context) {
        SharedPreferences prefs = Prefs.of(context);
        return new Status(
                prefs.getString(Prefs.APP_UPDATE_LATEST, ""),
                prefs.getString(Prefs.APP_UPDATE_PAGE, ""),
                prefs.getString(Prefs.APP_UPDATE_APK, ""),
                prefs.getLong(Prefs.APP_UPDATE_APK_BYTES, 0),
                prefs.getLong(Prefs.APP_UPDATE_CHECKED_AT, 0));
    }

    /**
     * Starts a check if one is due, and returns at once. Safe from onResume.
     *
     * Any network, not only Wi-Fi: the answer is a few kilobytes of text, and the download it
     * may lead to is the owner's own tap on a row that says how big it is.
     */
    static void maybeCheckInBackground(Context context) {
        if (!enabled(context) || running || !DeviceProbe.hasInternet(context)) return;
        long now = System.currentTimeMillis();
        SharedPreferences prefs = Prefs.of(context);
        if (now - prefs.getLong(Prefs.APP_UPDATE_CHECKED_AT, 0) < CHECK_EVERY_MS) return;
        if (now - prefs.getLong(Prefs.APP_UPDATE_TRIED_AT, 0) < RETRY_AFTER_MS) return;
        final Context app = context.getApplicationContext();
        running = true;
        try {
            new Thread(() -> {
                try {
                    check(app);
                } finally {
                    running = false;
                }
            }, "app-update-check").start();
        } catch (Throwable couldNotStart) {
            running = false;
        }
    }

    /** Asks now. Background thread only; returns null when there was no answer. */
    static Status check(Context context) {
        SharedPreferences prefs = Prefs.of(context);
        long now = System.currentTimeMillis();
        prefs.edit().putLong(Prefs.APP_UPDATE_TRIED_AT, now).apply();
        String latest = "";
        String page = "";
        String apk = "";
        long bytes = 0;
        try {
            JSONArray releases = new JSONArray(get(RELEASES));
            for (int i = 0; i < releases.length(); i++) {
                JSONObject release = releases.getJSONObject(i);
                if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
                    continue;
                }
                String tag = release.optString("tag_name", "");
                if (!tag.startsWith(TAG_PREFIX)) continue;
                String version = tag.substring(TAG_PREFIX.length());
                if (!latest.isEmpty() && compare(version, latest) <= 0) continue;
                String foundApk = "";
                long foundBytes = 0;
                JSONArray assets = release.optJSONArray("assets");
                for (int j = 0; assets != null && j < assets.length(); j++) {
                    JSONObject asset = assets.getJSONObject(j);
                    String name = asset.optString("name", "");
                    if (name.endsWith(".apk")) {
                        foundApk = asset.optString("browser_download_url", "");
                        foundBytes = asset.optLong("size", 0);
                        break;
                    }
                }
                latest = version;
                page = release.optString("html_url", "");
                apk = foundApk;
                bytes = foundBytes;
            }
        } catch (IOException | JSONException | RuntimeException noAnswer) {
            return null;
        }
        prefs.edit()
                .putString(Prefs.APP_UPDATE_LATEST, latest)
                .putString(Prefs.APP_UPDATE_PAGE, page)
                .putString(Prefs.APP_UPDATE_APK, apk)
                .putLong(Prefs.APP_UPDATE_APK_BYTES, bytes)
                .putLong(Prefs.APP_UPDATE_CHECKED_AT, now)
                .apply();
        return last(context);
    }

    /**
     * Compares two dotted versions numerically: 1.10.0 is newer than 1.9.0, which a string
     * comparison gets wrong. Anything that is not a number counts as zero.
     */
    static int compare(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int length = Math.max(left.length, right.length);
        for (int i = 0; i < length; i++) {
            int x = i < left.length ? number(left[i]) : 0;
            int y = i < right.length ? number(right[i]) : 0;
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    private static int number(String part) {
        StringBuilder digits = new StringBuilder();
        for (char c : part.toCharArray()) {
            if (c < '0' || c > '9') break;
            digits.append(c);
        }
        if (digits.length() == 0) return 0;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException tooLong) {
            return 0;
        }
    }

    private static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "PocketIDE/" + BuildFacts.VERSION_NAME);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub answered " + status + ".");
            }
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[16384];
                int read;
                while ((read = input.read(chunk)) != -1 && buffer.size() < 2 * 1024 * 1024) {
                    buffer.write(chunk, 0, read);
                }
                return buffer.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }
}

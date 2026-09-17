package com.pocketide;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Each extension's own icon, as the registry serves it.
 *
 * The Agents screen used to show a generic glyph beside every extension, because the app has
 * no right to draw a publisher's mark of its own -- and the drafts that did were removed. What
 * it can show is what every registry client shows: the icon the publisher put in the
 * extension and Open VSX serves beside its listing, fetched from the registry at run time,
 * exactly as the registry's own website and the editor's Extensions view show it. Nothing is
 * shipped in the APK, nothing is redrawn, and nothing is shown for an extension the registry
 * does not list.
 *
 * Icons come from the registry's host and nowhere else, are capped in size, decoded small,
 * and kept in the app's cache directory under the extension's id so a screen built twice
 * fetches once. A failure of any kind leaves the row's plain glyph in place.
 */
final class Icons {

    /** The only host an icon is fetched from: the registry's own. */
    static final String HOST = "open-vsx.org";

    private static final int LONGEST_SIDE_PX = 128;
    private static final int LARGEST_BYTES = 512 * 1024;

    /** How many decoded icons are kept in memory: a screen's worth, least recently shown out. */
    private static final int KEPT = 64;

    /** Decoded icons for this process, by extension id, most recently shown last. */
    private static final Map<String, Bitmap> LOADED = new LinkedHashMap<String, Bitmap>(KEPT, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > KEPT;
        }
    };

    private Icons() {}

    interface OnIcon {
        void icon(Bitmap bitmap);
    }

    /** True for the URLs this class will fetch. Anything else is left alone. */
    static boolean allowed(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            URL parsed = new URL(url);
            return "https".equals(parsed.getProtocol()) && HOST.equalsIgnoreCase(parsed.getHost());
        } catch (Throwable malformed) {
            return false;
        }
    }

    /**
     * Hands the icon for {@code id} to {@code then} on the main thread: at once from memory,
     * quickly from the cache directory, otherwise after one fetch. Never calls back on failure.
     * Called on the main thread, which is where the row it decorates lives.
     */
    static void load(final Activity activity, final String id, final String url, final OnIcon then) {
        if (!allowed(url) || id == null || id.isEmpty()) return;
        synchronized (LOADED) {
            Bitmap known = LOADED.get(id);
            if (known != null) {
                then.icon(known);
                return;
            }
        }
        new Thread(() -> {
            Bitmap bitmap = fromCache(activity, id);
            if (bitmap == null) bitmap = fetch(activity, id, url);
            if (bitmap == null) return;
            synchronized (LOADED) {
                LOADED.put(id, bitmap);
            }
            final Bitmap ready = bitmap;
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing()) then.icon(ready);
            });
        }, "icon-" + id).start();
    }

    private static File cacheFile(Context context, String id) {
        File dir = new File(context.getCacheDir(), "icons");
        if (!dir.isDirectory()) dir.mkdirs();
        return new File(dir, id.replaceAll("[^A-Za-z0-9._-]", "_") + ".png");
    }

    private static Bitmap fromCache(Context context, String id) {
        File file = cacheFile(context, id);
        if (!file.isFile()) return null;
        Bitmap decoded = null;
        try {
            if (file.length() <= LARGEST_BYTES) decoded = decodeSmall(Files.readAllBytes(file.toPath()));
        } catch (Throwable unreadable) {
            // Treated as absent, below.
        }
        if (decoded == null) file.delete();
        return decoded;
    }

    private static Bitmap fetch(Context context, String id, String url) {
        byte[] bytes;
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false);
            try {
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
                try (InputStream in = connection.getInputStream()) {
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[16384];
                    int read;
                    while ((read = in.read(chunk)) != -1) {
                        if (buffer.size() + read > LARGEST_BYTES) return null;
                        buffer.write(chunk, 0, read);
                    }
                    bytes = buffer.toByteArray();
                }
            } finally {
                connection.disconnect();
            }
        } catch (Throwable unreachable) {
            return null;
        }
        Bitmap decoded = decodeSmall(bytes);
        if (decoded == null) return null;
        // Kept as the registry sent it, so the next decode is the same picture.
        try (FileOutputStream out = new FileOutputStream(cacheFile(context, id))) {
            out.write(bytes);
        } catch (IOException notCached) {
            // Shown this time; fetched again next time.
        }
        return decoded;
    }

    /** Decoded at a size a row needs, never at the size a publisher uploaded. */
    private static Bitmap decodeSmall(byte[] bytes) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize
                    > LONGEST_SIDE_PX * 2) {
                options.inSampleSize *= 2;
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Throwable undecodable) {
            return null;
        }
    }
}

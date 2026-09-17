package com.pocketide;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The Open VSX Registry, which is where every extension in this app comes from.
 *
 * Open VSX is the Eclipse Foundation's extension registry. Microsoft's marketplace is limited
 * by its own terms to Microsoft's own products, so every build of VS Code that is not
 * Microsoft's -- VSCodium, code-server, Cursor, Windsurf, and Google's own Antigravity IDE --
 * uses this one. It is not a downgrade: on 13 September 2026 all three agent extensions carried
 * the same version, published the same day, on both registries.
 *
 * The part that matters for safety is `verified`. A namespace is marked verified only when it
 * has a real owner, and every counterfeit extension found on this registry in 2026 -- the 73 of
 * the GlassWorm cluster in April, the 77 impersonating AMD, Azure, Salesforce and a US
 * government agency in July and August -- came from accounts unaffiliated with the publishers
 * they imitated. So verified is the default filter, and turning it off is a deliberate act with
 * a warning attached.
 */
final class Registry {
    static final String API = "https://open-vsx.org/api";

    /** One extension, as the registry describes it. */
    static final class Listing {
        final String id;
        final String name;
        final String namespace;
        final String version;
        final boolean verified;
        final String description;
        final long downloads;
        final double rating;
        final int reviews;
        final String targetPlatform;
        final String engine;
        final String downloadUrl;
        final String sha256Url;

        Listing(String namespace, String name, String version, boolean verified,
                String description, long downloads, double rating, int reviews,
                String targetPlatform, String engine, String downloadUrl, String sha256Url) {
            this.namespace = namespace;
            this.name = name;
            this.id = namespace + "." + name;
            this.version = version;
            this.verified = verified;
            this.description = description;
            this.downloads = downloads;
            this.rating = rating;
            this.reviews = reviews;
            this.targetPlatform = targetPlatform;
            this.engine = engine;
            this.downloadUrl = downloadUrl;
            this.sha256Url = sha256Url;
        }
    }

    private Registry() {}

    /**
     * Searches the registry. Unverified publishers are filtered out unless the owner has turned
     * that off in Settings, which is where the warning lives.
     *
     * Runs on a worker. Never the main thread.
     */
    static List<Listing> search(Context context, String query, int size) throws IOException {
        boolean allowUnverified = Prefs.of(context).getBoolean(Prefs.ALLOW_UNVERIFIED, false);
        String url = API + "/-/search?size=" + Math.max(1, Math.min(size, 50))
                + "&query=" + URLEncoder.encode(query == null ? "" : query, "UTF-8");
        List<Listing> found = new ArrayList<>();
        try {
            JSONObject body = new JSONObject(get(url));
            JSONArray entries = body.optJSONArray("extensions");
            if (entries == null) return found;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                boolean verified = entry.optBoolean("verified", false);
                // Search results do not always carry the verified flag; a listing that does not
                // say it is verified is treated as not verified, which is the safe direction.
                if (!verified && !allowUnverified) continue;
                found.add(new Listing(
                        entry.optString("namespace"), entry.optString("name"),
                        entry.optString("version"), verified,
                        entry.optString("description"), entry.optLong("downloadCount"),
                        entry.optDouble("averageRating", -1), entry.optInt("reviewCount"),
                        entry.optString("targetPlatform", "universal"), "", "", ""));
            }
        } catch (JSONException malformed) {
            throw new IOException("The registry sent something this app could not read.", malformed);
        }
        return found;
    }

    /** The full record for one extension, including where to download it and its checksum. */
    static Listing lookup(String namespace, String name, String targetPlatform)
            throws IOException {
        String url = API + "/" + namespace + "/" + name;
        if (targetPlatform != null && !targetPlatform.isEmpty()
                && !"universal".equals(targetPlatform)) {
            url = url + "/" + targetPlatform + "/latest";
        }
        try {
            JSONObject body = new JSONObject(get(url));
            JSONObject files = body.optJSONObject("files");
            JSONObject engines = body.optJSONObject("engines");
            return new Listing(
                    body.optString("namespace"), body.optString("name"),
                    body.optString("version"), body.optBoolean("verified", false),
                    body.optString("description"), body.optLong("downloadCount"),
                    body.optDouble("averageRating", -1), body.optInt("reviewCount"),
                    body.optString("targetPlatform", "universal"),
                    engines == null ? "" : engines.optString("vscode", ""),
                    files == null ? "" : files.optString("download", ""),
                    files == null ? "" : files.optString("sha256", ""));
        } catch (JSONException malformed) {
            throw new IOException("The registry's answer for " + namespace + "." + name
                    + " could not be read.", malformed);
        }
    }

    /** The published SHA-256 of an extension's .vsix, as a lowercase hex string. */
    static String publishedChecksum(String sha256Url) throws IOException {
        if (sha256Url == null || sha256Url.isEmpty()) return "";
        return get(sha256Url).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
    }

    private static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(40000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("The extension registry answered " + status + ".");
            }
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[16384];
                int read;
                // A registry answer is kilobytes. A cap stops a redirected or hostile response
                // from filling memory on a phone with four gigabytes of it.
                while ((read = input.read(chunk)) != -1 && buffer.size() < 4 * 1024 * 1024) {
                    buffer.write(chunk, 0, read);
                }
                return buffer.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            connection.disconnect();
        }
    }

    /** The installed-extension list the app keeps, so Home can show it without asking Linux. */
    /**
     * What the editor actually has, not what this app remembers installing.
     *
     * The difference is a bug an owner reported with a screenshot: Antigravity installed from
     * inside the editor stayed "not installed" everywhere in the app, because the app was
     * reading a list only it ever wrote. The editor's own record is the answer whenever there
     * is an editor to ask; the remembered list is the fallback for before there is one. See
     * Extensions.
     */
    static List<String> installed(Context context) {
        List<String> real = Extensions.ids(context);
        if (real != null) return real;
        return remembered(context);
    }

    static List<String> remembered(Context context) {
        String stored = Prefs.of(context).getString(Prefs.INSTALLED_EXTENSIONS, "");
        List<String> ids = new ArrayList<>();
        for (String line : stored.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) ids.add(trimmed);
        }
        return ids;
    }

    static void remember(Context context, String id, boolean present) {
        // The editor has just been changed, so anything read from it a moment ago is stale.
        Extensions.forget();
        List<String> ids = remembered(context);
        if (present && !ids.contains(id)) ids.add(id);
        if (!present) ids.remove(id);
        StringBuilder joined = new StringBuilder();
        for (String each : ids) joined.append(each).append('\n');
        Prefs.of(context).edit()
                .putString(Prefs.INSTALLED_EXTENSIONS, joined.toString()).apply();
    }
}

package com.pocketagent.mobile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Portable, bounded path and stream rules shared by native media and its fixtures. */
final class WorkspaceMediaPaths {
    static final long MAX_FILE_BYTES = 512L * 1024 * 1024;
    static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;
    // Leaves room for the rest of a 16 MiB JSON frame. The string is decoded directly into a file.
    static final int MAX_INLINE_ENCODED_CHARS = 16 * 1024 * 1024 - 16384;
    static final int MAX_ARTIFACTS = 12;
    private static final Pattern LINK = Pattern.compile("!?\\[[^]\\r\\n]{0,160}\\]\\((<[^>\\r\\n]{1,2048}>|[^\\s\\r\\n)]{1,2048})(?:\\s+\"[^\"\\r\\n]{0,200}\")?\\)");

    private WorkspaceMediaPaths() { }

    static File file(File project, String relative) throws IOException {
        if (project == null || Files.isSymbolicLink(project.toPath()) || !project.isDirectory()) {
            throw new IOException("Choose a valid project folder.");
        }
        if (relative == null || relative.isEmpty() || relative.length() > 2048
                || relative.indexOf('\\') >= 0 || relative.indexOf('\0') >= 0) {
            throw new IOException("Invalid project file path.");
        }
        File base = project.getCanonicalFile();
        File file = WorkspaceTools.safeChild(base, relative);
        if (file.equals(base) || !Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("This project file is unavailable.");
        }
        return file;
    }

    static String relativeLink(File project, String guestRoot, String destination) throws IOException {
        if (destination == null || destination.length() > 2048) throw new IOException("Invalid file link.");
        String link = destination.trim();
        if (link.startsWith("<") && link.endsWith(">")) link = link.substring(1, link.length() - 1);
        if (link.indexOf('\\') >= 0 || link.indexOf('\0') >= 0) throw new IOException("Invalid file link.");
        try {
            URI uri = new URI(link.replace(" ", "%20"));
            if (uri.getRawAuthority() != null || uri.getRawQuery() != null) throw new IOException("Not a local project file.");
            String scheme = uri.getScheme();
            if (scheme != null && !scheme.equals("file") && !scheme.equals("sandbox")) {
                throw new IOException("Only local project file links are previewed.");
            }
            String path = uri.getPath();
            if (path == null || path.isEmpty()) throw new IOException("Invalid file link.");
            if (path.startsWith("/")) {
                String hostRoot = project.getCanonicalPath();
                if (path.startsWith(guestRoot + "/")) path = path.substring(guestRoot.length() + 1);
                else if (path.startsWith(hostRoot + "/")) path = path.substring(hostRoot.length() + 1);
                else throw new IOException("This link is outside the current project.");
            }
            File checked = file(project, path);
            return project.getCanonicalFile().toPath().relativize(checked.toPath()).toString().replace(File.separatorChar, '/');
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Invalid file link.", e);
        }
    }

    static List<String> artifactPaths(File project, String guestRoot, String markdown) {
        Set<String> result = new LinkedHashSet<>();
        if (markdown == null) return new ArrayList<>(result);
        Matcher matches = LINK.matcher(markdown.substring(0, Math.min(markdown.length(), 128 * 1024)));
        int considered = 0;
        while (matches.find() && result.size() < MAX_ARTIFACTS && considered++ < 80) {
            try { result.add(relativeLink(project, guestRoot, matches.group(1))); }
            catch (IOException | RuntimeException ignored) { }
        }
        return new ArrayList<>(result);
    }

    static String safeName(String supplied) {
        String value = supplied == null ? "attachment" : supplied;
        value = value.replaceAll("[^A-Za-z0-9._ -]", "_").replaceAll("^[. ]+", "");
        if (value.isEmpty()) value = "attachment";
        if (value.length() > 100) {
            int dot = value.lastIndexOf('.');
            String suffix = dot > 0 && value.length() - dot <= 12 ? value.substring(dot) : "";
            value = value.substring(0, 100 - suffix.length()) + suffix;
        }
        return value;
    }

    static long copyBounded(InputStream source, OutputStream destination, long maximum) throws IOException {
        if (maximum < 0 || maximum > MAX_FILE_BYTES) throw new IOException("Invalid copy limit.");
        byte[] buffer = new byte[32768];
        long total = 0;
        for (;;) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("File operation cancelled.");
            int count = source.read(buffer, 0, (int) Math.min(buffer.length, maximum - total + 1));
            if (count < 0) return total;
            if (count == 0) continue;
            total += count;
            if (total > maximum) throw new IOException("The file is too large. Limit: " + (maximum / (1024 * 1024)) + " MB.");
            destination.write(buffer, 0, count);
        }
    }

    static long decodeInlineImage(String encoded, OutputStream destination) throws IOException {
        if (encoded == null || encoded.isEmpty() || encoded.length() > MAX_INLINE_ENCODED_CHARS)
            throw new IOException("This inline image is too large. Ask the agent to save it as a project file.");
        final int[] position = {0};
        InputStream ascii = new InputStream() {
            @Override public int read() throws IOException {
                if (position[0] == encoded.length()) return -1;
                char value = encoded.charAt(position[0]++);
                if (value > 127) throw new IOException("Invalid image encoding.");
                return value;
            }
        };
        try (InputStream decoded = java.util.Base64.getDecoder().wrap(ascii)) {
            long size = copyBounded(decoded, destination, MAX_IMAGE_BYTES);
            if (size == 0 || position[0] != encoded.length()) throw new IOException("Invalid image encoding.");
            return size;
        } catch (IllegalArgumentException invalid) { throw new IOException("Invalid image encoding.", invalid); }
    }

    static String token(String token) throws IOException {
        if (token == null || !token.matches("[a-f0-9]{64}")) throw new IOException("Invalid shared file.");
        return token;
    }

    static String shareToken(String expectedAuthority, String rawUri) throws IOException {
        try {
            URI uri = new URI(rawUri);
            if (!"content".equals(uri.getScheme()) || !expectedAuthority.equals(uri.getRawAuthority())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPath() == null) {
                throw new IOException("Invalid shared file URI.");
            }
            String[] parts = uri.getPath().split("/", -1);
            if (parts.length != 4 || !parts[0].isEmpty() || !"file".equals(parts[1])
                    || !safeName(parts[3]).equals(parts[3])) throw new IOException("Invalid shared file URI.");
            return token(parts[2]);
        } catch (java.net.URISyntaxException | NullPointerException e) {
            throw new IOException("Invalid shared file URI.", e);
        }
    }

    static String mime(byte[] bytes, int count, String name) {
        if (starts(bytes, count, new int[]{137,80,78,71,13,10,26,10})) return "image/png";
        if (starts(bytes, count, new int[]{255,216,255})) return "image/jpeg";
        String header = new String(bytes, 0, Math.max(0, count), java.nio.charset.StandardCharsets.ISO_8859_1);
        if (header.startsWith("GIF87a") || header.startsWith("GIF89a")) return "image/gif";
        if (header.startsWith("RIFF") && header.length() >= 12) {
            if (header.substring(8,12).equals("WEBP")) return "image/webp";
            if (header.substring(8,12).equals("WAVE")) return "audio/wav";
        }
        if (header.startsWith("%PDF-")) return "application/pdf";
        if (header.startsWith("fLaC")) return "audio/flac";
        if (header.startsWith("OggS")) return "audio/ogg";
        if (header.startsWith("ID3") || (count >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 224) == 224)) return "audio/mpeg";
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (count >= 12 && header.substring(4,8).equals("ftyp")) return lower.endsWith(".m4a") ? "audio/mp4" : "video/mp4";
        if (starts(bytes, count, new int[]{26,69,223,163})) return lower.endsWith(".weba") ? "audio/webm" : "video/webm";
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log") || lower.endsWith(".csv")) return "text/plain";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".tar")) return "application/x-tar";
        if (lower.endsWith(".gz") || lower.endsWith(".tgz")) return "application/gzip";
        if (lower.matches(".*\\.(java|kt|kts|py|js|jsx|ts|tsx|c|h|cpp|hpp|cs|go|rs|rb|sh|sql|xml|yaml|yml|toml|css|html)$")) return "text/plain";
        return "application/octet-stream";
    }

    private static boolean starts(byte[] bytes, int count, int[] prefix) {
        if (count < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((bytes[i] & 255) != prefix[i]) return false;
        return true;
    }
}

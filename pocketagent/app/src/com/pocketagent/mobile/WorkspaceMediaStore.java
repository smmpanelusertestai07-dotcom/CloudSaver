package com.pocketagent.mobile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Persistent app-private attachment paths. Saved conversation media is never a disposable cache. */
final class WorkspaceMediaStore {
    static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    static final long FREE_SPACE_RESERVE = 32L * 1024 * 1024;
    private WorkspaceMediaStore() { }

    static File directory(File filesDir) throws IOException {
        File directory = WorkspaceTools.safeChild(filesDir.getCanonicalFile(), "desk-media");
        if (!directory.exists() && !directory.mkdir()) throw new IOException("Cannot save this file in PocketAgent.");
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Saved media is unavailable.");
        return directory;
    }

    static File file(File directory, String token, String suffix) throws IOException {
        if (!(".bin".equals(suffix) || ".json".equals(suffix) || ".ref".equals(suffix) || ".tmp".equals(suffix)))
            throw new IOException("Invalid attachment record.");
        return WorkspaceTools.safeChild(directory, WorkspaceMediaPaths.token(token) + suffix);
    }

    static File saved(File directory, String token, boolean metadata) throws IOException {
        File body = file(directory, token, ".bin"), info = file(directory, token, ".json");
        if (!Files.isRegularFile(body.toPath(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(info.toPath(), LinkOption.NOFOLLOW_LINKS)
                || body.length() > WorkspaceMediaPaths.MAX_FILE_BYTES || info.length() > 8192)
            throw new IOException("This saved file is unavailable.");
        return metadata ? info : body;
    }

    static long available(File directory, long reserved) throws IOException {
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Saved media is unavailable.");
        if (files.length >= 3000) throw new IOException("Saved media is full. Export files you want to keep before clearing app storage.");
        long occupied = 0;
        for (File file : files) {
            if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) occupied += file.length();
            if (occupied > MAX_TOTAL_BYTES) break;
        }
        return Math.max(0, Math.min(MAX_TOTAL_BYTES - occupied - Math.max(0, reserved),
                directory.getUsableSpace() - FREE_SPACE_RESERVE - Math.max(0, reserved)));
    }

    /** Only a one-way URL fingerprint is stored; signed URLs and query credentials stay out of metadata. */
    static String sourceKey(String url) throws IOException {
        if (url == null || url.length() > 16384) throw new IOException("Invalid media source.");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(64);
            for (byte b : hash) value.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IOException("Cannot identify this media source.", impossible); }
    }
}

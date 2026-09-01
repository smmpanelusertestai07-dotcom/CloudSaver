package com.pocketdesk;

import android.system.ErrnoException;
import android.system.Os;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

final class TarGzExtractor {
    interface ProgressListener { void onFile(int files, String name); }

    private TarGzExtractor() {}

    static void extract(InputStream compressed, File destination, ProgressListener progress)
            throws IOException {
        if (!destination.exists() && !destination.mkdirs()) {
            throw new IOException("Could not create root filesystem");
        }
        String rootPath = destination.getCanonicalPath() + File.separator;
        byte[] header = new byte[512];
        String longName = null;
        String longLink = null;
        Map<String, String> pax = new HashMap<>();
        int files = 0;
        try (GZIPInputStream input = new GZIPInputStream(compressed, 128 * 1024)) {
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Extraction cancelled");
                int got = readFully(input, header, 0, header.length);
                if (got == 0) break;
                if (got != 512) throw new IOException("Truncated tar header");
                if (isZeroBlock(header)) break;
                verifyChecksum(header);

                String name = string(header, 0, 100);
                String prefix = string(header, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                long size = number(header, 124, 12);
                int mode = (int) number(header, 100, 8);
                char type = (char) header[156];
                String link = string(header, 157, 100);

                if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                    byte[] data = readEntry(input, size);
                    skipPadding(input, size);
                    if (type == 'L') longName = trimNull(data);
                    else if (type == 'K') longLink = trimNull(data);
                    else pax.putAll(parsePax(data));
                    continue;
                }

                if (longName != null) name = longName;
                if (longLink != null) link = longLink;
                if (pax.containsKey("path")) name = pax.get("path");
                if (pax.containsKey("linkpath")) link = pax.get("linkpath");
                longName = null;
                longLink = null;
                pax.clear();

                name = cleanName(name);
                if (name.isEmpty()) {
                    skipExact(input, size);
                    skipPadding(input, size);
                    continue;
                }
                File target = safeFile(destination, rootPath, name);
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IOException("Could not create directory for " + name);
                }

                try {
                    switch (type) {
                        case 0:
                        case '0':
                        case '7':
                            if (target.exists() && target.isDirectory()) {
                                throw new IOException("File conflicts with directory: " + name);
                            }
                            try (FileOutputStream output = new FileOutputStream(target)) {
                                copyExact(input, output, size);
                            }
                            chmod(target, mode == 0 ? 0644 : mode);
                            break;
                        case '5':
                            if (!target.exists() && !target.mkdirs()) throw new IOException("Could not create " + name);
                            chmod(target, mode == 0 ? 0755 : mode);
                            skipExact(input, size);
                            break;
                        case '2':
                            if (target.exists() && !target.delete()) throw new IOException("Could not replace " + name);
                            Os.symlink(link, target.getAbsolutePath());
                            skipExact(input, size);
                            break;
                        case '1':
                            File source = safeFile(destination, rootPath, cleanName(link));
                            if (target.exists() && !target.delete()) throw new IOException("Could not replace " + name);
                            Os.link(source.getAbsolutePath(), target.getAbsolutePath());
                            skipExact(input, size);
                            break;
                        default:
                            skipExact(input, size);
                            break;
                    }
                } catch (ErrnoException error) {
                    throw new IOException("Could not extract " + name + ": " + error.getMessage(), error);
                }
                skipPadding(input, size);
                files++;
                if (progress != null && (files % 100 == 0 || files < 10)) progress.onFile(files, name);
            }
        }
    }

    private static File safeFile(File root, String rootPath, String name) throws IOException {
        File target = new File(root, name).getCanonicalFile();
        String path = target.getPath();
        if (!path.startsWith(rootPath)) throw new IOException("Unsafe path in archive: " + name);
        return target;
    }

    private static String cleanName(String value) throws IOException {
        String result = value == null ? "" : value.replace('\\', '/');
        while (result.startsWith("./")) result = result.substring(2);
        while (result.startsWith("/")) result = result.substring(1);
        if (result.equals("..") || result.startsWith("../") || result.contains("/../")) {
            throw new IOException("Unsafe archive path");
        }
        return result;
    }

    private static void verifyChecksum(byte[] header) throws IOException {
        long stored = number(header, 148, 8);
        long unsigned = 0;
        long signed = 0;
        for (int i = 0; i < 512; i++) {
            int value = (i >= 148 && i < 156) ? 32 : (header[i] & 0xff);
            unsigned += value;
            signed += (i >= 148 && i < 156) ? 32 : header[i];
        }
        if (stored != unsigned && stored != signed) throw new IOException("Archive checksum mismatch");
    }

    private static long number(byte[] bytes, int offset, int length) throws IOException {
        if ((bytes[offset] & 0x80) != 0) {
            long result = bytes[offset] & 0x7f;
            for (int i = offset + 1; i < offset + length; i++) result = (result << 8) | (bytes[i] & 0xff);
            return result;
        }
        long result = 0;
        boolean found = false;
        for (int i = offset; i < offset + length; i++) {
            int c = bytes[i] & 0xff;
            if (c == 0 || c == ' ') {
                if (found) break;
                continue;
            }
            if (c < '0' || c > '7') throw new IOException("Invalid tar number");
            found = true;
            result = (result << 3) + (c - '0');
        }
        return result;
    }

    private static String string(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static byte[] readEntry(InputStream input, long size) throws IOException {
        if (size > Integer.MAX_VALUE) throw new IOException("Metadata entry too large");
        byte[] data = new byte[(int) size];
        if (readFully(input, data, 0, data.length) != data.length) throw new IOException("Truncated archive");
        return data;
    }

    private static Map<String, String> parsePax(byte[] data) {
        Map<String, String> values = new HashMap<>();
        String text = new String(data, StandardCharsets.UTF_8);
        int position = 0;
        while (position < text.length()) {
            int space = text.indexOf(' ', position);
            if (space < 0) break;
            int length;
            try { length = Integer.parseInt(text.substring(position, space)); }
            catch (NumberFormatException ignored) { break; }
            int end = Math.min(position + length, text.length());
            String record = text.substring(space + 1, end).trim();
            int equals = record.indexOf('=');
            if (equals > 0) values.put(record.substring(0, equals), record.substring(equals + 1));
            if (length <= 0) break;
            position += length;
        }
        return values;
    }

    private static void chmod(File file, int mode) throws ErrnoException {
        Os.chmod(file.getAbsolutePath(), mode & 07777);
    }

    private static String trimNull(byte[] data) {
        int length = data.length;
        while (length > 0 && (data[length - 1] == 0 || data[length - 1] == '\n')) length--;
        return new String(data, 0, length, StandardCharsets.UTF_8);
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte value : block) if (value != 0) return false;
        return true;
    }

    private static int readFully(InputStream input, byte[] data, int offset, int length) throws IOException {
        int total = 0;
        while (total < length) {
            int read = input.read(data, offset + total, length - total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    private static void copyExact(InputStream input, FileOutputStream output, long size) throws IOException {
        byte[] buffer = new byte[128 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Truncated archive data");
            output.write(buffer, 0, read);
            remaining -= read;
        }
        output.getFD().sync();
    }

    private static void skipExact(InputStream input, long size) throws IOException {
        long remaining = size;
        byte[] buffer = new byte[64 * 1024];
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) throw new IOException("Truncated archive data");
            remaining -= read;
        }
    }

    private static void skipPadding(InputStream input, long size) throws IOException {
        long padding = (512 - (size % 512)) % 512;
        skipExact(input, padding);
    }
}

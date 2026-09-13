package com.pocketagent.mobile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Source-only ZIP extraction into an atomically reserved new project directory. */
final class ProjectArchive {
    static final long MAX_COMPRESSED = 64L * 1024 * 1024;
    static final long MAX_EXPANDED = 128L * 1024 * 1024;
    static final long MAX_FILE = 32L * 1024 * 1024;
    private static final int MAX_ENTRIES = 10000;
    interface Progress { void update(String text); }
    private ProjectArchive() {}

    static File importZip(File workspace, String name, File archive, Progress progress) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}"))
            throw new IOException("Use 1–64 letters, numbers, dots, hyphens or underscores for the new project.");
        File root = workspace.getCanonicalFile();
        if (!root.isDirectory() || Files.isSymbolicLink(workspace.toPath())) throw new IOException("The projects folder is unavailable.");
        if (!archive.isFile() || archive.length() > MAX_COMPRESSED) throw new IOException("Source ZIP limit: 64 MB compressed.");
        validateDirectory(archive);
        File target = new File(root, name);
        // mkdir reserves this exact new name; never merge into or overwrite another project.
        if (!target.mkdir()) throw new IOException("A project with this name already exists, or the folder cannot be created.");
        boolean complete = false;
        try (ZipFile zip = new ZipFile(archive, Charset.forName("CP437"))) {
            long total = 0; int count = 0; Set<String> paths = new HashSet<>(); Map<String, String> spelling = new HashMap<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                interrupted();
                ZipEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES) throw new IOException("Source ZIP limit: 10,000 entries.");
                String relative = path(entry.getName());
                if (!paths.add(relative.toLowerCase(Locale.ROOT))) throw new IOException("The ZIP contains duplicate or case-colliding paths.");
                String prefix = "";
                for (String part : relative.split("/")) {
                    prefix = prefix.isEmpty() ? part : prefix + "/" + part;
                    String previous = spelling.put(prefix.toLowerCase(Locale.ROOT), prefix);
                    if (previous != null && !previous.equals(prefix)) throw new IOException("The ZIP contains case-colliding folders.");
                }
                File file = new File(target, relative);
                if (!file.getCanonicalPath().startsWith(target.getCanonicalPath() + File.separator)) throw new IOException("A ZIP path leaves the new project.");
                assertNoLinks(target, file);
                if (entry.isDirectory()) {
                    if (file.exists() && !file.isDirectory()) throw new IOException("A ZIP file conflicts with a folder.");
                    if (!file.isDirectory() && !file.mkdirs()) throw new IOException("Cannot create an imported folder.");
                    continue;
                }
                if (entry.getSize() < 0 || entry.getSize() > MAX_FILE) throw new IOException("Source ZIP limit: 32 MB per file.");
                if (entry.getCompressedSize() > 0 && entry.getSize() > Math.max(1024 * 1024, entry.getCompressedSize() * 200))
                    throw new IOException("The ZIP has an unsafe expansion ratio.");
                File parent = file.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("A ZIP file conflicts with a parent folder.");
                assertNoLinks(target, file);
                if (!file.createNewFile()) throw new IOException("The ZIP contains colliding file paths.");
                CRC32 crc = new CRC32(); long size = 0;
                try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(file)) {
                    byte[] buffer = new byte[16384]; int read;
                    while ((read = input.read(buffer)) != -1) {
                        interrupted(); size += read; total += read;
                        if (size > MAX_FILE || total > MAX_EXPANDED || size > entry.getSize())
                            throw new IOException("ZIP expansion limit exceeded. Nothing was imported.");
                        output.write(buffer, 0, read); crc.update(buffer, 0, read);
                    }
                    output.getFD().sync();
                }
                if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw new IOException("The ZIP contains damaged file data.");
                if (progress != null && (count % 25 == 0 || count == 1)) progress.update("Importing " + count + " entries · " + (total / 1024) + " KB");
            }
            if (count == 0) throw new IOException("This ZIP is empty.");
            complete = true; return target;
        } finally { if (!complete) deleteOwned(target); }
    }

    /** Reject links/encryption/ZIP64 in central metadata before any project directory is created. */
    private static void validateDirectory(File archive) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(archive, "r")) {
            long length = input.length(), end = -1;
            if (length < 22) throw new IOException("This file is not a ZIP archive.");
            for (long offset = length - 22; offset >= Math.max(0, length - 65557); offset--) {
                input.seek(offset);
                if (read32(input) == 0x06054b50L) {
                    input.seek(offset + 20);
                    if (offset + 22 + read16(input) == length) { end = offset; break; }
                }
            }
            if (end < 0) throw new IOException("This ZIP is incomplete or uses an unsupported format.");
            input.seek(end + 4);
            int disk = read16(input), startDisk = read16(input), diskEntries = read16(input), count = read16(input);
            long size = read32(input), start = read32(input);
            if (disk != 0 || startDisk != 0 || count != diskEntries || count == 65535 || size == 0xffffffffL
                    || start == 0xffffffffL || count > MAX_ENTRIES || start + size != end)
                throw new IOException("Use a standard single-file ZIP with at most 10,000 entries; ZIP64 is not supported.");
            input.seek(start); long declaredTotal = 0;
            for (int index = 0; index < count; index++) {
                interrupted();
                if (input.getFilePointer() + 46 > end || read32(input) != 0x02014b50L) throw new IOException("Invalid ZIP directory.");
                int madeBy = read16(input); read16(input); int flags = read16(input), method = read16(input);
                input.skipBytes(8); long compressed = read32(input), expanded = read32(input);
                int nameLength = read16(input), extraLength = read16(input), commentLength = read16(input), entryDisk = read16(input);
                read16(input); long attributes = read32(input), localOffset = read32(input);
                int mode = (int)(attributes >>> 16) & 0170000;
                if ((flags & 1) != 0 || (method != 0 && method != 8) || entryDisk != 0 || localOffset >= start
                        || compressed == 0xffffffffL || expanded == 0xffffffffL || expanded > MAX_FILE)
                    throw new IOException("The ZIP contains encrypted, oversized or unsupported entries.");
                if ((((madeBy >>> 8) == 3 || (madeBy >>> 8) == 19) && mode != 0 && mode != 0100000 && mode != 0040000))
                    throw new IOException("ZIP links and special files are not imported. Export ordinary source files instead.");
                if (nameLength == 0 || nameLength > 4096 || input.getFilePointer() + nameLength + extraLength + commentLength > end)
                    throw new IOException("Invalid ZIP entry name.");
                byte[] name = new byte[nameLength]; input.readFully(name);
                path(new String(name, (flags & 2048) != 0 ? StandardCharsets.UTF_8 : Charset.forName("CP437")));
                input.skipBytes(extraLength + commentLength);
                declaredTotal += expanded;
                if (declaredTotal > MAX_EXPANDED) throw new IOException("Source ZIP limit: 128 MB expanded.");
            }
            if (input.getFilePointer() != end) throw new IOException("The ZIP directory is inconsistent.");
        }
    }

    private static String path(String value) throws IOException {
        if (value == null || value.length() > 4096 || value.startsWith("/") || value.indexOf('\\') >= 0 || value.indexOf(':') >= 0)
            throw new IOException("The ZIP contains an unsafe path.");
        String result = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        String[] parts = result.split("/", -1);
        if (parts.length > 40) throw new IOException("ZIP folders are too deeply nested.");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.equalsIgnoreCase(".git"))
                throw new IOException("Import ordinary project source without traversal paths or .git metadata.");
            for (int i = 0; i < part.length(); i++) if (Character.isISOControl(part.charAt(i))) throw new IOException("Invalid ZIP path character.");
        }
        return result;
    }
    private static void assertNoLinks(File root, File target) throws IOException {
        for (File current = target; current != null; current = current.getParentFile()) {
            if (Files.isSymbolicLink(current.toPath())) throw new IOException("A linked path cannot be imported.");
            if (current.equals(root)) return;
        }
        throw new IOException("ZIP path leaves the reserved project.");
    }
    private static int read16(RandomAccessFile input) throws IOException { return input.readUnsignedByte() | input.readUnsignedByte() << 8; }
    private static long read32(RandomAccessFile input) throws IOException { return (long)read16(input) | (long)read16(input) << 16; }
    private static void interrupted() throws IOException { if (Thread.currentThread().isInterrupted()) throw new IOException("ZIP import cancelled."); }
    private static void deleteOwned(File file) {
        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles(); if (children != null) for (File child : children) deleteOwned(child);
        }
        file.delete();
    }
}

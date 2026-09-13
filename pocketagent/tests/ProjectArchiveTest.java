package com.pocketagent.mobile;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real ZIP inputs exercise extraction, rollback, collision and metadata validation. */
public final class ProjectArchiveTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("pocketagent-import-test-");
        try {
            File workspace = Files.createDirectory(temporary.resolve("projects")).toFile();
            File zip = temporary.resolve("source.zip").toFile();
            write(zip, archive(new String[]{"src/", "src/main.js", "README.md"}, new byte[][]{new byte[0], "hello".getBytes(StandardCharsets.UTF_8), "readme".getBytes(StandardCharsets.UTF_8)}));
            File imported = ProjectArchive.importZip(workspace, "demo", zip, null);
            equal("hello", new String(Files.readAllBytes(new File(imported, "src/main.js").toPath()), StandardCharsets.UTF_8), "source content preserved");
            rejected(workspace, "demo", zip, "existing project");
            equal("hello", new String(Files.readAllBytes(new File(imported, "src/main.js").toPath()), StandardCharsets.UTF_8), "failed second import did not replace existing source");
            for (String name : new String[]{"../escape", "/absolute", "src/../../escape", "src\\escape", "C:/escape", "a/./b", ".git/config", "src/.GIT/config"}) {
                write(zip, archive(new String[]{name}, new byte[][]{{1}})); rejected(workspace, "unsafe", zip, name);
                check(!new File(workspace, "unsafe").exists(), "unsafe archive left no project: " + name);
            }
            for (String[] names : new String[][]{{"Readme.md", "README.md"}, {"src/a", "SRC/b"}, {"folder", "folder/file"}}) {
                write(zip, archive(names, new byte[][]{{1}, {2}})); rejected(workspace, "collision", zip, "collision");
                check(!new File(workspace, "collision").exists(), "collision rolls back partial extraction");
            }
            byte[] linked = archive(new String[]{"link"}, new byte[][]{"../../outside".getBytes(StandardCharsets.UTF_8)});
            int central = signature(linked, 0x50, 0x4b, 0x01, 0x02); linked[central + 5] = 3;
            int attributes = (0120777 << 16); for (int i = 0; i < 4; i++) linked[central + 38 + i] = (byte)(attributes >>> (i * 8));
            write(zip, linked); rejected(workspace, "link", zip, "Unix symlink metadata");
            byte[] expanded = new byte[3 * 1024 * 1024];
            write(zip, archive(new String[]{"bomb"}, new byte[][]{expanded})); rejected(workspace, "bomb", zip, "expansion ratio");
            check(!new File(workspace, "bomb").exists(), "expansion failure rolls back reserved project");
            byte[] damaged = stored("plain.txt", "good-data".getBytes(StandardCharsets.UTF_8)); damaged[30 + "plain.txt".length()] ^= 1;
            write(zip, damaged); rejected(workspace, "damaged", zip, "CRC mismatch");
            check(!new File(workspace, "damaged").exists(), "CRC failure rolls back partial data");
            write(zip, archive(new String[]{"file"}, new byte[][]{{1}}));
            Thread.currentThread().interrupt();
            try { rejected(workspace, "cancelled", zip, "interruption"); } finally { Thread.interrupted(); }
            check(!new File(workspace, "cancelled").exists(), "interrupted import left no project");
            check(!temporary.resolve("escape").toFile().exists(), "traversal never wrote outside workspace");
            System.out.println("PASS ProjectArchiveTest (" + assertions + " assertions)");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }
    private static byte[] archive(String[] names, byte[][] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { for (int i = 0; i < names.length; i++) { zip.putNextEntry(new ZipEntry(names[i])); zip.write(data[i]); zip.closeEntry(); } }
        return bytes.toByteArray();
    }
    private static byte[] stored(String name, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); CRC32 crc = new CRC32(); crc.update(content);
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) { ZipEntry entry = new ZipEntry(name); entry.setMethod(ZipEntry.STORED); entry.setSize(content.length); entry.setCompressedSize(content.length); entry.setCrc(crc.getValue()); zip.putNextEntry(entry); zip.write(content); zip.closeEntry(); }
        return bytes.toByteArray();
    }
    private static int signature(byte[] input, int a, int b, int c, int d) { for (int i = 0; i < input.length - 4; i++) if ((input[i]&255)==a && (input[i+1]&255)==b && (input[i+2]&255)==c && (input[i+3]&255)==d) return i; throw new AssertionError("ZIP central directory missing"); }
    private static void write(File file, byte[] bytes) throws IOException { Files.write(file.toPath(), bytes); }
    private static void rejected(File root, String name, File zip, String cause) throws IOException { boolean refused = false; try { ProjectArchive.importZip(root, name, zip, null); } catch (IOException expected) { refused = true; } check(refused, "Expected rejection: " + cause); }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void equal(String expected, String actual, String message) { check(expected.equals(actual), message); }
}

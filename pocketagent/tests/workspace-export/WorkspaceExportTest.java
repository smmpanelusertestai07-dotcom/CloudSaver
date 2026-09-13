package com.pocketagent.mobile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Exercises actual archive output so common credentials cannot silently leave in a ZIP. */
public final class WorkspaceExportTest {
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("pocketagent-export-test-");
        try {
            Path root = Files.createDirectory(temporary.resolve("project"));
            Files.createDirectories(root.resolve("src"));
            Files.write(root.resolve("src/main.js"), "export const ready = true;".getBytes(StandardCharsets.UTF_8));
            Files.write(root.resolve("README.md"), "Project source".getBytes(StandardCharsets.UTF_8));
            Files.write(root.resolve("package.json"), "{}".getBytes(StandardCharsets.UTF_8));
            Files.write(root.resolve("id_ed25519.pub"), "public-key-fixture".getBytes(StandardCharsets.UTF_8));
            String[] secrets = {".env", ".ENV.production", ".npmrc", ".netrc", ".git-credentials",
                    "server.pem", "SERVER.KEY", "release.jks", "identity.p12", "identity.PFX", "release.keystore",
                    "credentials.json", "CREDENTIALS.JSON", "service-account-prod.json",
                    "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa", "src/credentials.json"};
            for (String name : secrets) Files.write(root.resolve(name), "PRIVATE-EXPORT-FIXTURE".getBytes(StandardCharsets.UTF_8));
            for (String name : new String[]{".git", "node_modules", ".venv", ".ssh", ".aws", ".kube"}) {
                Files.createDirectory(root.resolve(name));
                Files.write(root.resolve(name).resolve("config"), "PRIVATE-EXPORT-FIXTURE".getBytes(StandardCharsets.UTF_8));
            }
            Path outside = temporary.resolve("outside.txt");
            Files.write(outside, "PRIVATE-EXPORT-FIXTURE".getBytes(StandardCharsets.UTF_8));
            Files.createSymbolicLink(root.resolve("linked-secret.txt"), outside);

            // The Android picker supplies the output stream; the recursive writer itself
            // is portable Java and is exercised without invoking Android SDK stubs.
            Method writer = WorkspaceTools.class.getDeclaredMethod("zipDirectory", java.io.File.class,
                    java.io.File.class, ZipOutputStream.class, long[].class, int.class);
            writer.setAccessible(true);
            ByteArrayOutputStream archive = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(archive)) {
                writer.invoke(null, root.toFile(), root.toFile(), zip, new long[]{0, 0}, 0);
            }
            Set<String> entries = new HashSet<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive.toByteArray()))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entries.add(entry.getName());
                    ByteArrayOutputStream content = new ByteArrayOutputStream();
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = zip.read(buffer)) != -1) content.write(buffer, 0, count);
                    if (new String(content.toByteArray(), StandardCharsets.UTF_8).contains("PRIVATE-EXPORT-FIXTURE"))
                        throw new AssertionError("Private fixture escaped through " + entry.getName());
                }
            }
            Set<String> expected = new HashSet<>(java.util.Arrays.asList("src/main.js", "README.md", "package.json", "id_ed25519.pub"));
            if (!entries.equals(expected)) throw new AssertionError("Unexpected exported entries: " + entries);
            for (String name : secrets) {
                if (!Files.exists(root.resolve(name))) throw new AssertionError("Export removed source " + name);
            }
            System.out.println("PASS WorkspaceExportTest (source preserved; conventional credentials and links excluded)");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }
}

package com.pocketdesk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class TarGzExtractorTest {
    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("pocketdesk-tar-test");
        Path source = Files.createDirectories(work.resolve("source/usr/share/very-long-directory-name-for-pax-and-gnu-tar-testing"));
        Path text = source.resolve("hello.txt");
        Files.write(text, "verified archive data".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(work.resolve("source/links"));
        Files.createSymbolicLink(work.resolve("source/links/hello-symlink"),
                Paths.get("../usr/share/very-long-directory-name-for-pax-and-gnu-tar-testing/hello.txt"));
        Files.createLink(work.resolve("source/links/hello-hardlink"), text);

        Path archive = work.resolve("fixture.tar.gz");
        Process tar = new ProcessBuilder("tar", "--format=posix", "-czf", archive.toString(),
                "-C", work.resolve("source").toString(), ".").inheritIO().start();
        require(tar.waitFor() == 0, "could not create test archive");
        Path output = work.resolve("output");
        try (java.io.InputStream input = Files.newInputStream(archive)) {
            TarGzExtractor.extract(input, output.toFile(), null);
        }
        Path extracted = output.resolve("usr/share/very-long-directory-name-for-pax-and-gnu-tar-testing/hello.txt");
        require("verified archive data".equals(new String(Files.readAllBytes(extracted), StandardCharsets.UTF_8)),
                "file contents changed");
        require(Files.isSymbolicLink(output.resolve("links/hello-symlink")), "symlink missing");
        require(Files.isSameFile(extracted, output.resolve("links/hello-hardlink")), "hardlink missing");
        System.out.println("PASS TarGzExtractorTest");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package com.pocketdesk;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Guards the rootfs cleanup that used to fail with "Could not remove incomplete setup: bin".
 * Ubuntu ships /bin as a link into /usr, so following links both deleted the wrong directory
 * and left the link behind.
 */
public final class TreesTest {
    public static void main(String[] args) throws Exception {
        deletesLinksWithoutFollowingThem();
        removesDanglingLinks();
        emptiesUnwritableDirectories();
        sizeIgnoresLinks();
        System.out.println("PASS TreesTest");
    }

    private static void deletesLinksWithoutFollowingThem() throws Exception {
        Path work = Files.createTempDirectory("pocketdesk-trees");
        Path outside = Files.createDirectories(work.resolve("outside"));
        Files.write(outside.resolve("keep.txt"), "must survive".getBytes(StandardCharsets.UTF_8));

        Path root = Files.createDirectories(work.resolve("rootfs"));
        Files.createDirectories(root.resolve("usr/bin"));
        Files.write(root.resolve("usr/bin/perl"), "binary".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(root.resolve("bin"), Paths.get("usr/bin"));
        Files.createSymbolicLink(root.resolve("escape"), outside);

        Trees.delete(root.toFile());

        require(!Files.exists(root, LinkOption.NOFOLLOW_LINKS), "rootfs was not removed");
        require(Files.exists(outside.resolve("keep.txt")),
                "deletion followed a link out of the tree and destroyed unrelated files");
    }

    private static void removesDanglingLinks() throws Exception {
        Path work = Files.createTempDirectory("pocketdesk-trees-dangling");
        Path root = Files.createDirectories(work.resolve("rootfs"));
        Files.createSymbolicLink(root.resolve("bin"), Paths.get("usr/bin"));   // target never created

        Trees.delete(root.toFile());

        require(!Files.exists(root, LinkOption.NOFOLLOW_LINKS), "a dangling link blocked cleanup");
    }

    private static void emptiesUnwritableDirectories() throws Exception {
        Path work = Files.createTempDirectory("pocketdesk-trees-readonly");
        Path root = Files.createDirectories(work.resolve("rootfs"));
        Path locked = Files.createDirectories(root.resolve("locked"));
        Files.write(locked.resolve("file.txt"), "data".getBytes(StandardCharsets.UTF_8));
        require(locked.toFile().setWritable(false, true), "could not make the fixture read-only");

        Trees.delete(root.toFile());

        require(!Files.exists(root, LinkOption.NOFOLLOW_LINKS), "a read-only directory blocked cleanup");
    }

    private static void sizeIgnoresLinks() throws Exception {
        Path work = Files.createTempDirectory("pocketdesk-trees-size");
        Path root = Files.createDirectories(work.resolve("rootfs"));
        Files.createDirectories(root.resolve("usr/bin"));
        byte[] payload = new byte[4096];
        Files.write(root.resolve("usr/bin/perl"), payload);
        Files.createSymbolicLink(root.resolve("bin"), Paths.get("usr/bin"));

        long size = Trees.size(root.toFile());
        require(size >= payload.length && size < payload.length * 2L,
                "linked directories were counted twice: " + size);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

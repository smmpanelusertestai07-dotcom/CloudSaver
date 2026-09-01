package com.pocketdesk;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Every catalogue entry is a shell script that only ever runs on a phone, so its syntax and its
 * "always newest build" promise are checked here instead of being discovered by a user mid-install.
 */
public final class LinuxAppsTest {
    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("pocketdesk-apps");
        require(LinuxApps.CATALOG.length > 0, "the catalogue is empty");

        for (LinuxApps.App app : LinuxApps.CATALOG) {
            String command = app.installCommand();

            Path script = work.resolve(app.id + ".sh");
            Files.write(script, ("#!/bin/bash\n" + command + "\n").getBytes(StandardCharsets.UTF_8));
            Process check = new ProcessBuilder("bash", "-n", script.toString())
                    .redirectErrorStream(true).start();
            String output = new String(readAll(check.getInputStream()), StandardCharsets.UTF_8);
            require(check.waitFor() == 0, "install command for " + app.id + " is not valid shell:\n" + output);

            require(app.marker.startsWith("/"), app.id + " marker must be an absolute path");
            require(app.needsBytes > 0, app.id + " must state how much space it needs");
            require(app.name != null && !app.name.isEmpty(), app.id + " has no name");
            // A pinned version would quietly go stale; every entry must resolve the newest build.
            require(!command.matches("(?s).*/\\d+\\.\\d+\\.\\d+/.*"),
                    app.id + " pins a version in its download URL");
            require(command.contains("apt-get") || command.contains("curl"),
                    app.id + " does not fetch anything");
        }

        LinuxApps.App chatgpt = LinuxApps.byId("chatgpt");
        require(chatgpt != null, "chatgpt is missing from the catalogue");
        require(chatgpt.installCommand().contains("/latest/"), "chatgpt must track the latest build");
        require(LinuxApps.byId("nope") == null, "byId must return null for an unknown id");

        // Apps are launched from their own packaged .desktop entry now, so there is no
        // hand-written launcher left to check here.

        System.out.println("PASS LinuxAppsTest (" + LinuxApps.CATALOG.length + " apps)");
    }

    private static byte[] readAll(java.io.InputStream input) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) != -1) buffer.write(chunk, 0, read);
        return buffer.toByteArray();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

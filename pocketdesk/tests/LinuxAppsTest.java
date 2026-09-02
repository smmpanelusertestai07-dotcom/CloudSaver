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
        Path projectDir = java.nio.file.Paths.get(args.length > 0 ? args[0] : ".");
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

        // Every install and uninstall runs through the shared helpers, so a stopped install is
        // repaired rather than inherited by the next attempt.
        for (LinuxApps.App app : LinuxApps.CATALOG) {
            require(app.installCommand().contains("pd_repair"),
                    app.id + " does not repair a half-applied install before starting");
            Path script = work.resolve(app.id + "-uninstall.sh");
            Files.write(script, ("#!/bin/bash\n" + app.uninstallCommand() + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            Process check = new ProcessBuilder("bash", "-n", script.toString())
                    .redirectErrorStream(true).start();
            String output = new String(readAll(check.getInputStream()), StandardCharsets.UTF_8);
            require(check.waitFor() == 0,
                    "uninstall command for " + app.id + " is not valid shell:\n" + output);
        }

        // The computer's own basics are part of the computer: there is no way to uninstall them
        // on their own. Every AI app can be uninstalled on its own.
        LinuxApps.App basics = LinuxApps.byId("basics");
        require(basics != null, "the computer's basics are missing from the catalogue");
        require(!basics.removable(), "the computer's basics must not be uninstallable");
        require(basics.installCommand().contains("apt-get -y upgrade"),
                "the basics update must install Ubuntu's security updates");
        for (LinuxApps.App app : LinuxApps.CATALOG) {
            if (!LinuxApps.isAiApp(app)) continue;
            require(app.removable(), app.id + " must be uninstallable on its own");
            require(app.uninstallCommand().contains("apt-get remove"),
                    app.id + " does not actually remove its package");
        }

        // Google Chrome carries the protection: Safe Browsing at its strongest level, dangerous
        // downloads blocked, and no way to click through a malware warning.
        require(LinuxApps.CHROME_INSTALL.contains("\"SafeBrowsingProtectionLevel\": 2"),
                "Chrome must run Safe Browsing at its enhanced level");
        require(LinuxApps.CHROME_INSTALL.contains("SafeBrowsingProceedAnywayDisabled"),
                "a malware warning must not be clickable-through");
        require(LinuxApps.CHROME_INSTALL.contains("DownloadRestrictions"),
                "dangerous downloads must be blocked");

        checkResumeHelpers(work);
        checkBootstrapShell(projectDir, work);

        LinuxApps.App chatgpt = LinuxApps.byId("chatgpt");
        require(chatgpt != null, "chatgpt is missing from the catalogue");
        require(chatgpt.installCommand().contains("/latest/"), "chatgpt must track the latest build");
        require(LinuxApps.byId("nope") == null, "byId must return null for an unknown id");

        // Apps are launched from their own packaged .desktop entry now, so there is no
        // hand-written launcher left to check here.

        System.out.println("PASS LinuxAppsTest (" + LinuxApps.CATALOG.length + " apps)");
    }

    /**
     * The helpers themselves, run for real: a step that is already done is skipped, a step that
     * fails twice is retried until it works, and three failures give up rather than loop. This
     * is what makes a stopped set-up continue instead of starting over.
     */
    private static void checkResumeHelpers(Path work) throws Exception {
        Path root = work.resolve("root");
        Path bin = work.resolve("bin");
        Files.createDirectories(bin);
        Files.createDirectories(root);
        // A stand-in apt-get that fails as many times as the counter file says.
        Path counter = work.resolve("attempts");
        // Only the install calls are counted; "apt-get update" between retries is not one.
        Files.write(bin.resolve("apt-get"), ("#!/bin/bash\n"
                + "[ \"$1\" = install ] || exit 0\n"
                + "echo install >> '" + counter + "'\n"
                + "n=$(wc -l < '" + counter + "')\n"
                + "[ \"$n\" -gt \"${FAIL_TIMES:-0}\" ]\n").getBytes(StandardCharsets.UTF_8));
        Files.write(bin.resolve("dpkg"), "#!/bin/bash\nexit 0\n".getBytes(StandardCharsets.UTF_8));
        bin.resolve("apt-get").toFile().setExecutable(true);
        bin.resolve("dpkg").toFile().setExecutable(true);

        String prelude = "#!/bin/bash\nset -eu\nexport PATH='" + bin + "':$PATH\n"
                + "export POCKETDESK_TEST_ROOT='" + root + "'\n"
                + "export POCKETDESK_RETRY_SLEEP=0\n" + LinuxApps.APT_HELPERS + "\n";

        // Succeeds first time, and records that it finished.
        Files.write(counter, new byte[0]);
        require(run(work, prelude + "pd_step demo one two\n") == 0, "pd_step must install a step");
        require(Files.exists(root.resolve("var/lib/pocketdesk/stage/demo")),
                "pd_step must record a finished step");
        require(countLines(counter) == 1, "pd_step called apt-get more than once for one step");

        // Runs again: the finished step is skipped entirely, which is what saves the download.
        require(run(work, prelude + "pd_step demo one two\n") == 0, "a finished step must succeed");
        require(countLines(counter) == 1, "a finished step must not run apt-get again");

        // Two failures then success: it keeps going instead of failing the whole set-up.
        Files.write(counter, new byte[0]);
        require(run(work, prelude + "FAIL_TIMES=2 pd_step flaky one\n") == 0,
                "pd_step must retry a step that failed on a bad connection");
        require(countLines(counter) == 3, "pd_step must try three times, not " + countLines(counter));

        // Always failing: it gives up with an error rather than looping forever.
        Files.write(counter, new byte[0]);
        require(run(work, prelude + "FAIL_TIMES=99 pd_step hopeless one\n") != 0,
                "pd_step must fail after its retries are used up");
        require(countLines(counter) == 3, "pd_step must give up after three tries, not "
                + countLines(counter));
        require(!Files.exists(root.resolve("var/lib/pocketdesk/stage/hopeless")),
                "a step that never finished must not be recorded as done");
    }

    /**
     * The set-up script itself, checked as shell.
     *
     * It is built by string concatenation in Java and only ever runs on a phone, so a stray
     * quote would be found by the owner, mid-set-up, as "exited with code 2". The literals are
     * read back out of the source, the shared constants are substituted for real, and bash is
     * asked whether the result parses.
     */
    private static void checkBootstrapShell(Path projectDir, Path work) throws Exception {
        Path source = projectDir.resolve("app/src/com/pocketdesk/ContainerRuntime.java");
        require(Files.exists(source), "ContainerRuntime.java not found at " + source);
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        int start = text.indexOf("static String bootstrapCommand() {");
        require(start > 0, "bootstrapCommand() is missing");
        int end = text.indexOf("\n    }", start);
        require(end > start, "bootstrapCommand() is not closed");

        StringBuilder script = new StringBuilder();
        for (String line : text.substring(start, end).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//")) continue;                 // a comment, not the script
            script.append(expand(trimmed));
        }
        require(script.indexOf("pd_step devtools") >= 0, "the set-up script was not read back");

        Path file = work.resolve("bootstrap.sh");
        Files.write(file, ("#!/bin/bash\n" + script + "\n").getBytes(StandardCharsets.UTF_8));
        Process check = new ProcessBuilder("bash", "-n", file.toString())
                .redirectErrorStream(true).start();
        String output = new String(readAll(check.getInputStream()), StandardCharsets.UTF_8);
        require(check.waitFor() == 0, "the set-up script is not valid shell:\n" + output);
    }

    /**
     * One line of Java source as the shell it becomes: string literals unescaped and the shared
     * constants substituted, both in the order they are written.
     */
    private static String expand(String line) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            if (line.startsWith("LinuxApps.", i)) {
                int from = i + "LinuxApps.".length();
                int to = from;
                while (to < line.length()
                        && (Character.isLetterOrDigit(line.charAt(to)) || line.charAt(to) == '_')) {
                    to++;
                }
                out.append(constant(line.substring(from, to)));
                i = to;
                continue;
            }
            if (line.charAt(i) != '"') { i++; continue; }
            for (i++; i < line.length() && line.charAt(i) != '"'; i++) {
                char c = line.charAt(i);
                if (c != '\\') { out.append(c); continue; }
                char next = line.charAt(++i);
                switch (next) {
                    case 'n': out.append('\n'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        out.append((char) Integer.parseInt(line.substring(i + 1, i + 5), 16));
                        i += 4;
                        break;
                    default: out.append(next);
                }
            }
            i++;
        }
        return out.toString();
    }

    private static String constant(String name) {
        if ("APT_HELPERS".equals(name)) return LinuxApps.APT_HELPERS;
        if ("CHROME_INSTALL".equals(name)) return LinuxApps.CHROME_INSTALL;
        if ("DEVELOPER_PACKAGES".equals(name)) return LinuxApps.DEVELOPER_PACKAGES;
        if ("DESKTOP_PACKAGES".equals(name)) return LinuxApps.DESKTOP_PACKAGES;
        throw new AssertionError("bootstrapCommand uses an unknown constant: " + name);
    }

    private static int countLines(Path file) throws Exception {
        return (int) Files.readAllLines(file).stream().filter(line -> !line.isEmpty()).count();
    }

    private static int run(Path work, String script) throws Exception {
        Path file = work.resolve("harness.sh");
        Files.write(file, script.getBytes(StandardCharsets.UTF_8));
        Process process = new ProcessBuilder("bash", file.toString())
                .redirectErrorStream(true).start();
        readAll(process.getInputStream());
        return process.waitFor();
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

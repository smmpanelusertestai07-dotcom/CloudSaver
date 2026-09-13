package com.pocketagent.mobile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Runs the production command through a real shell and a harmless argument-recording Git stub. */
public final class ProjectGitTest {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("pocketagent-git-command-");
        try {
            Path stub = temporary.resolve("git");
            Files.write(stub, "#!/bin/sh\nprintf '%s\\n' \"$@\"\n".getBytes(StandardCharsets.UTF_8)); stub.toFile().setExecutable(true);
            String url = "https://github.com/owner/has'$()repository.git";
            List<String> push = execute(temporary, ProjectGit.syncCommand(url, "feature/mobile", true));
            check(push.contains(url), "shell preserves the exact quoted URL as one argument");
            check(push.contains("HEAD:refs/heads/feature/mobile"), "push uses explicit selected branch ref");
            check(push.contains("push") && !push.contains("--force") && !push.contains("--mirror"), "push never forces or mirrors");
            check(push.contains("core.hooksPath=/dev/null") && push.contains("protocol.allow=never")
                    && push.contains("protocol.https.allow=always") && push.contains("http.sslVerify=true"), "fixed transport and hook guards present");
            List<String> pull = execute(temporary, ProjectGit.syncCommand("https://github.com/owner/repo.git", "main", false));
            check(pull.contains("--ff-only") && pull.contains("--no-rebase") && pull.contains("--no-autostash"), "pull does not rewrite or stash local history");
            List<String> signed = execute(temporary, ProjectGit.syncCommand("https://github.com/owner/repo.git", "main", true, true));
            check(signed.contains("credential.https://github.com.helper="), "verified GitHub clears only its own host helper chain");
            check(signed.stream().anyMatch(value -> value.startsWith("credential.https://github.com.helper=!env ")
                    && value.contains("-u GH_TOKEN -u GITHUB_TOKEN") && value.contains("GH_CONFIG_DIR=/root/.config/gh")
                    && value.endsWith("/usr/bin/gh auth git-credential")), "verified GitHub uses official helper with private config and no inherited token override");
            check(!ProjectGit.commandForRemote("https://github.com/owner/repo", false).contains("git-credential"), "unverified account does not inject GitHub auth");
            for (String other : new String[]{"https://gitlab.com/owner/repo", "https://github.com.evil.example/owner/repo", "https://example.com/github.com/repo"})
                check(!ProjectGit.commandForRemote(other, true).contains("git-credential"), "GitHub helper cannot be injected into another host: " + other);
            for (String[] changed : new String[][]{{"https://github.com/other/repo.git", "main"}, {"https://github.com/owner/repo.git", "other-branch"}}) {
                boolean refused = false;
                try { ProjectGit.reviewedSyncCommand("https://github.com/owner/repo.git", "main", changed[0], changed[1], true); }
                catch (IOException expected) { refused = true; }
                check(refused, "sync cannot change the destination or branch after confirmation");
            }
            check(ProjectGit.reviewedSyncCommand("https://github.com/owner/repo.git", "main", "https://github.com/owner/repo.git", "main", true)
                    .equals(ProjectGit.syncCommand("https://github.com/owner/repo.git", "main", true)), "unchanged reviewed destination produces the exact approved command");
            for (String invalid : new String[]{"http://github.com/o/r", "ssh://git@github.com/o/r", "file:///tmp/repo", "ext::evil", "https://token@github.com/o/r", "https://github.com/o/r?token=x", "https://github.com/o/r#x", "https://github.com:8443/o/r"}) {
                boolean refused = false; try { ProjectGit.remote(invalid); } catch (IOException expected) { refused = true; }
                check(refused, "rejects unsupported or credential-bearing remote: " + invalid);
            }
            for (String invalid : new String[]{"--force", "main;touch_marker", "a/../b", "a//b", "a.lock", "main\nother", "$(touch_marker)"}) {
                boolean refused = false; try { ProjectGit.syncCommand("https://github.com/o/r", invalid, true); } catch (IOException expected) { refused = true; }
                check(refused, "invalid branch cannot become shell syntax: " + invalid);
            }
            System.out.println("PASS ProjectGitTest (" + assertions + " assertions)");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temporary)) {
                for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }
    private static List<String> execute(Path directory, String command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c", command);
        builder.environment().put("PATH", directory.toString()); builder.redirectErrorStream(true);
        Process process = builder.start(); java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1024]; int size; while ((size = process.getInputStream().read(buffer)) != -1) bytes.write(buffer, 0, size);
        check(process.waitFor() == 0, "argument recorder ran successfully");
        return Arrays.asList(new String(bytes.toByteArray(), StandardCharsets.UTF_8).split("\n"));
    }
    private static void check(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
}

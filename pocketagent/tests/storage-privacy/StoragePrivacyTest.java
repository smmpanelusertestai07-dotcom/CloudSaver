package com.pocketagent.mobile;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class StoragePrivacyTest {
    private static int checks;
    interface Operation { void run() throws Exception; }
    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static void rejects(Operation operation) throws Exception {
        try { operation.run(); } catch (IllegalArgumentException | java.io.IOException expected) { checks++; return; }
        throw new AssertionError("Unsafe input was accepted");
    }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("pocketagent-storage-");
        try {
            Path app = Files.createDirectory(temp.resolve("private"));
            Path phone = Files.createDirectory(temp.resolve("phone"));
            Path old = phone.resolve("old-file.txt");
            Files.write(old, "keep me".getBytes(StandardCharsets.UTF_8));
            File shared = RuntimeStoragePaths.directory(app.toFile(), "shared", true);
            check(shared.isDirectory() && shared.getCanonicalPath().startsWith(app.toString()), "Shared is private");
            File root = RuntimeStoragePaths.directory(app.toFile(), "ubuntu-rootfs", false);
            check(!root.exists(), "Read path does not create a partial Ubuntu root");
            RuntimeStoragePaths.directory(app.toFile(), "usr/tmp", true);
            Files.createSymbolicLink(app.resolve("ubuntu-rootfs"), phone);
            rejects(() -> RuntimeStoragePaths.directory(app.toFile(), "ubuntu-rootfs", false));
            rejects(() -> RuntimeStoragePaths.directory(app.toFile(), "ubuntu-rootfs/home/coder", true));
            Files.createSymbolicLink(app.resolve("broken"), temp.resolve("missing"));
            rejects(() -> RuntimeStoragePaths.directory(app.toFile(), "broken", true));
            for (String unsafe : new String[]{"../phone", "/phone", "usr//tmp", "usr/./tmp", "usr/../tmp"})
                rejects(() -> RuntimeStoragePaths.directory(app.toFile(), unsafe, true));
            Files.write(app.resolve("regular"), new byte[]{1});
            rejects(() -> RuntimeStoragePaths.directory(app.toFile(), "regular/child", true));
            check("keep me".equals(new String(Files.readAllBytes(old), StandardCharsets.UTF_8)), "Existing public files preserved");

            JSONObject write = CodexEnvironment.writeEntryParams("MY_SERVICE_TOKEN", "value; $(echo no)\nnext");
            check(write.length() == 3, "Config write has no caller-selected file/scope");
            check("shell_environment_policy.set.MY_SERVICE_TOKEN".equals(write.getString("keyPath")), "Fixed config key");
            check("upsert".equals(write.getString("mergeStrategy")), "Preserve other variables");
            check("value; $(echo no)\nnext".equals(write.getString("value")), "Value stays a literal JSON string");
            check(!CodexEnvironment.readParams().getBoolean("includeLayers"), "Avoid reading layer values");
            for (String unsafe : new String[]{"A.B", "A]", "A\nB", "0KEY", "", "HOME", "PATH", "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "CODEX_HOME", "LD_PRELOAD", "NODE_OPTIONS", "BASH_ENV", "GEMINI_API_KEY", "GOOGLE_API_KEY", "PROOT_LOADER", "GIT_CONFIG_KEY_0", "CURSOR_API_KEY", "CLAUDE_CONFIG_DIR"})
                rejects(() -> CodexEnvironment.writeEntryParams(unsafe, "private-value"));
            rejects(() -> CodexEnvironment.writeEntryParams("KEY", "bad\0value"));
            rejects(() -> CodexEnvironment.writeEntryParams("KEY", new String(new char[4097])));
            JSONObject entries = new JSONObject().put("Z_TOKEN", "never-show-this").put("A_TOKEN", "another-secret");
            JSONArray names = CodexEnvironment.names(new JSONObject().put("config", new JSONObject()
                    .put("shell_environment_policy", new JSONObject().put("set", entries))));
            check(names.toString().equals("[\"A_TOKEN\",\"Z_TOKEN\"]"), "Only sorted names returned");
            check(CodexEnvironment.names(new JSONObject()).length() == 0, "Absent config has no inferred variables");

            final String[] launched = {""};
            ClaudeBridge bridge = new ClaudeBridge(new ClaudeBridge.Host() {
                public void send(JSONObject wire) {}
                public void sendText(String text) {}
                public void event(String event, JSONObject data) {}
                public void restart(String command) { launched[0] = command; }
                public void onReady() {}
                public void onError(String message) { throw new AssertionError(message); }
                public void onLoginUrl(String url) {}
            }, "/home/coder/Projects/test");
            bridge.receive(new JSONObject().put("type", "pocketagent_claude_auth").put("account",
                    new JSONObject().put("loggedIn", true).put("authMethod", "claude.ai")));
            bridge.onProcessExit(0);
            check(launched[0].contains("--disallowedTools 'mcp__pocketagent__*'"), "Native Claude denies legacy bundled phone tools");
            check(!launched[0].contains("'mcp__*'") && launched[0].contains("--permission-mode default"), "Other MCP tools and ordinary approvals are preserved");

            Path repo = new File(args[0]).toPath();
            String manifest = read(repo.resolve("app/AndroidManifest.xml"));
            for (String forbidden : new String[]{"MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE", "requestLegacyExternalStorage"})
                check(!manifest.contains(forbidden), "Manifest requests no broad storage access");
            check(manifest.contains("android:allowBackup=\"false\""), "Backup remains disabled");
            String runtime = read(repo.resolve("app/src/com/pocketagent/mobile/ContainerRuntime.java"));
            for (String forbidden : new String[]{"getExternalFilesDir(", "getExternalStorageDirectory(", "bindPhoneFolders(", "PhoneFiles.allowed("})
                check(!runtime.contains(forbidden), "Runtime never selects a public mount");
            String phoneHelper = read(repo.resolve("app/src/com/pocketagent/mobile/PhoneFiles.java"));
            check(!phoneHelper.contains("requestPermissions") && !phoneHelper.contains("startActivity"), "Legacy helper cannot request broad access");
            String desktop = read(repo.resolve("app/assets/pocketagent-desktop.sh"));
            check(!desktop.contains("mcp_servers.pocketagent") && !desktop.contains("claude mcp add --scope user pocketagent"), "Desktop startup does not automatically register phone-control tools");
            System.out.println("PASS StoragePrivacyTest (" + checks + " checks)");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(temp)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) {}
                });
            }
        }
    }
    static String read(Path path) throws Exception { return new String(Files.readAllBytes(path), StandardCharsets.UTF_8); }
}

package com.pocketagent.mobile;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Installs publisher releases inside the private Ubuntu workspace, never Android's shell. */
final class AgentInstaller {
    interface Listener { void progress(String message); }

    private AgentInstaller() {}

    static String version(String provider) {
        switch (provider) {
            case "codex": return "0.154.0";
            case "cursor": return "2026.09.08";
            case "claude": return "2.1.269";
            case "antigravity": return "1.1.1";
            default: return "";
        }
    }

    static String documentationUrl(String provider) {
        switch (provider) {
            case "codex": return "https://learn.chatgpt.com/docs/app-server";
            case "cursor": return "https://cursor.com/docs/cli/acp";
            case "claude": return "https://code.claude.com/docs/en/legal-and-compliance";
            case "antigravity": return "https://antigravity.google/terms";
            default: return "";
        }
    }

    /**
     * Why an engine cannot be connected yet -- the real reason, not a guess.
     *
     * Antigravity is not blocked by permission. Google's own headless documentation shows a
     * script holding agy's stdin open and driving it turn by turn, and names no restriction on
     * who may do that. What is missing is on this side: agy speaks its own newline-delimited
     * event stream (init, step_update, result), not ACP -- a native ACP mode is still an open
     * request on Google's CLI repository -- and PocketAgent has no adapter for that stream yet.
     * Saying so plainly matters: an owner told "permission was refused" would wait for Google,
     * when the work is ours.
     */
    static String unavailableReason(String provider) {
        if ("antigravity".equals(provider)) {
            return "Antigravity is not connected yet. Its CLI streams its own event format rather "
                    + "than the protocol this build speaks, and PocketAgent's adapter for it is "
                    + "not written. This is PocketAgent's missing work, not a refusal by Google.";
        }
        if (version(provider).isEmpty()) return "Unknown agent provider.";
        return "";
    }

    static boolean isInstalled(Context context, String provider) {
        if (!unavailableReason(provider).isEmpty()) return false;
        try {
            File root = ContainerRuntime.rootfs(context);
            File marker = new File(root, "var/lib/pocketagent/agents/" + provider + ".json");
            if (!marker.isFile() || marker.length() > 65536) return false;
            JSONObject record = new JSONObject(read(marker));
            String entry = record.optString("entry");
            File executable = new File(root, entry.startsWith("/") ? entry.substring(1) : entry);
            // Canonical containment also rejects tampered receipts or stale links.
            String installationRoot = new File(root, "opt/pocketagent/agents").getCanonicalPath() + "/";
            return provider.equals(record.optString("provider"))
                    && version(provider).equals(record.optString("version"))
                    && record.optBoolean("verified", false)
                    && executable.getCanonicalPath().startsWith(installationRoot)
                    && executable.isFile()
                    && new File(root, "usr/local/bin/pocketagent-" + provider).isFile();
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Call on a foreground-service worker. Cancellation interrupts the calling worker. */
    static synchronized void install(Context context, String provider, Listener listener)
            throws IOException, InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IOException("Agent setup must run in the background.");
        }
        String unavailable = unavailableReason(provider);
        if (!unavailable.isEmpty()) throw new IOException(unavailable);
        if (!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) {
            throw new IOException("Agent setup needs an ARM64 Android phone.");
        }
        File root = ContainerRuntime.rootfs(context);
        if (!new File(root, "etc/os-release").isFile()
                || !new File(root, "usr/bin/python3").exists()) {
            throw new IOException("Finish the Ubuntu workspace setup before installing an agent.");
        }
        if (isInstalled(context, provider)) {
            if (listener != null) listener.progress("Agent is installed. Connect your account to continue.");
            return;
        }
        File scripts = new File(root, "usr/local/share/pocketagent");
        if (!scripts.isDirectory() && !scripts.mkdirs()) throw new IOException("Cannot create agent setup folder.");
        File installer = new File(scripts, "agent-installer.py");
        try (InputStream input = context.getAssets().open("pocketagent-agent-installer.py");
             FileOutputStream output = new FileOutputStream(installer)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        final String[] lastError = {"The installer did not finish."};
        int result = ContainerRuntime.runContainer(context,
                "exec /usr/bin/python3 -u /usr/local/share/pocketagent/agent-installer.py " + provider,
                line -> {
                    String clean = line.replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]", "");
                    if (clean.length() > 2048) clean = clean.substring(0, 2048);
                    if (clean.startsWith("ERROR: ")) lastError[0] = clean.substring(7);
                    if (listener != null && !clean.trim().isEmpty()) listener.progress(clean);
                });
        if (result != 0) throw new IOException("Agent setup failed: " + lastError[0]);
        if (!isInstalled(context, provider)) {
            throw new IOException("Agent verification did not complete. Tap Install to retry.");
        }
    }

    private static String read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] bytes = new byte[4096];
            int count;
            while ((count = input.read(bytes)) != -1) {
                if (output.size() + count > 65536) throw new IOException("Invalid installation receipt.");
                output.write(bytes, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

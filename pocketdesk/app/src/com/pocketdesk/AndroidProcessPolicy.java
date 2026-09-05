package com.pocketdesk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import org.json.JSONObject;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Explicit Settings actions; never changes Android policy on pairing or desktop startup. */
final class AndroidProcessPolicy {
    private static final String KEY_RESULT = "android_process_policy_last_result";
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    interface Callback { void complete(Result result); }

    static final class Result {
        final JSONObject data;
        final boolean ok, supported, paired, sameDevice, restoreAvailable;
        final Boolean enabled;
        final String message;

        Result(JSONObject object) {
            data = object;
            ok = object.optBoolean("ok", false);
            supported = object.optBoolean("supported", false);
            paired = object.optBoolean("paired", false);
            sameDevice = object.optBoolean("same_device", false);
            restoreAvailable = object.optBoolean("restore_available", false);
            Object monitor = object.opt("effective_enabled");
            enabled = monitor instanceof Boolean ? (Boolean) monitor : null;
            message = limit(object.optString("message", "Check the current status again."), 1200);
        }

        boolean canApply() { return ok && supported && paired && sameDevice && !Boolean.FALSE.equals(enabled); }
        boolean canRestore() { return supported && paired && sameDevice && restoreAvailable; }

        String summary() {
            if (!ok) return "Last check: " + limit(message, 160) + " · tap to check again";
            if (Boolean.FALSE.equals(enabled)) return "Last check: child-process limit relaxed · tap to verify";
            if (!paired || !sameDevice) return "Pair this phone to check its process limit";
            return "Last check: Android child-process monitor active · tap to review";
        }
    }

    private AndroidProcessPolicy() {}
    static boolean isBusy() { return BUSY.get(); }

    static String summary(Context context) {
        if (BUSY.get()) return "Checking Android process limit…";
        String cached = context.getSharedPreferences(ContainerRuntime.PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RESULT, null);
        if (cached != null) {
            try { return new Result(new JSONObject(cached)).summary(); }
            catch (Exception ignored) {}
        }
        return "Check the paired phone's child-process limit";
    }

    /** Returns false for a duplicate tap; callbacks run on main and retain no Activity here. */
    static boolean run(Context context, String action, Callback callback) {
        if (!"status".equals(action) && !"apply".equals(action) && !"restore".equals(action))
            throw new IllegalArgumentException("Unknown Android process action");
        if (!BUSY.compareAndSet(false, true)) return false;
        final Context application = context.getApplicationContext();
        Thread worker = new Thread(() -> {
            Process process = null;
            PowerManager.WakeLock wakeLock = null;
            Result result;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            try {
                PowerManager power = (PowerManager) application.getSystemService(Context.POWER_SERVICE);
                if (power != null) {
                    wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketLinux:ProcessPolicy");
                    wakeLock.setReferenceCounted(false);
                    wakeLock.acquire(50_000L);
                }
                if (!ContainerRuntime.isInstalled(application)) {
                    result = failure(action, "not_installed", "Set up the Linux computer first. Your existing files are kept.");
                } else {
                    ContainerRuntime.writeProcessPolicyScript(application);
                    process = ContainerRuntime.startContainer(application, ContainerRuntime.processPolicyCommand(action));
                    ProcessPolicyOutput.Output output = ProcessPolicyOutput.read(process, deadline);
                    result = parse(action, output);
                }
            } catch (TimeoutException error) {
                result = failure(action, "timeout", "The paired phone did not reply in time. Turn on Wireless debugging and check again. A timed-out change must be checked again before retrying.");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                result = failure(action, "interrupted", "The check was interrupted. Check again to verify the phone's current setting.");
            } catch (Exception error) {
                // Do not show raw command output or connection credentials in a Settings row.
                result = failure(action, "runner_error", "Could not finish the Android process check. Turn on Wireless debugging for this phone and check again.");
            } finally {
                // This command owns its own PRoot session. Never stop LinuxService's desktop.
                if (process != null) ProotProcess.stopAndWait(process);
                try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); }
                catch (RuntimeException ignored) { /* A lease may expire during teardown. */ }
            }
            application.getSharedPreferences(ContainerRuntime.PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_RESULT, result.data.toString()).apply();
            BUSY.set(false);
            final Result completed = result;
            if (callback != null) new Handler(Looper.getMainLooper()).post(() -> callback.complete(completed));
        }, "pocketdesk-process-policy");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    private static Result parse(String action, ProcessPolicyOutput.Output output) throws Exception {
        // PRoot can print diagnostics before the helper's final, single JSON response.
        String[] lines = output.text.split("\\r?\\n");
        for (int index = lines.length - 1; index >= 0; index--) {
            String line = lines[index].trim();
            if (!line.startsWith("{")) continue;
            JSONObject object = new JSONObject(line);
            if (!action.equals(object.optString("action")) || !object.has("ok") || !object.has("code"))
                throw new IllegalArgumentException("Unexpected process-policy response");
            if (output.exit != 0 && object.optBoolean("ok"))
                return failure(action, "unverified_exit", "The command ended before verification. Check again to read the current setting.");
            return new Result(object);
        }
        return failure(action, "no_response", "No verified response from the paired phone. Turn on Wireless debugging and check again.");
    }

    private static Result failure(String action, String code, String message) {
        JSONObject data = new JSONObject();
        try {
            data.put("action", action).put("code", code).put("ok", false).put("message", message);
        } catch (Exception ignored) {}
        return new Result(data);
    }

    private static String limit(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}

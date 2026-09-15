package com.pocketide;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Installing an app the owner built, with no Developer options and no pairing at all.
 *
 * "Test on this phone" needs Wireless debugging, and Wireless debugging lives behind Developer
 * options because Android offers no narrower switch. An owner asked, reasonably, whether the
 * agent could get anywhere without that. Two thirds of the loop, it turns out: an app can
 * install another app and start it, with a permission that is listed in the phone's own
 * permission manager and a confirmation the owner taps each time. Only reading a log, taking a
 * screenshot and driving taps need adb.
 *
 * So the bridge has two modes. Paired, everything is automatic. Unpaired, phone install and
 * phone launch still work: this installs the APK through Android's own PackageInstaller, the
 * owner confirms on Android's own screen, and the result comes back to the terminal rather
 * than being left to guess.
 *
 * Why PackageInstaller and not an ACTION_VIEW to the APK, which is what Settings' own row
 * does. Three reasons, and each of them matters here rather than there:
 *
 *   IT ANSWERS. A session reports SUCCESS, FAILURE_ABORTED, FAILURE_CONFLICT with a message,
 *   so an agent is told what happened instead of watching a screen it cannot see.
 *
 *   IT MAKES THIS APP THE INSTALLER OF RECORD, which is what gives it the right to see and to
 *   start the app afterwards. Android 11 hides other packages from an app unless it declares
 *   which ones it wants to see; a <queries> element broad enough for "anything I build" would
 *   be broad enough for "every app on the phone", and this app is not going to ask for that to
 *   save one line. Packages it installed itself are visible to it with nothing declared.
 *
 *   IT IS REVERSIBLE BY NAME. The owner sees PocketIDE as the source of the app in Android's
 *   own App info, rather than an anonymous file.
 *
 * The permission it needs, REQUEST_INSTALL_PACKAGES, is the one the app already has for the
 * Settings row, it appears in the phone's permission manager as "Install unknown apps", and
 * Android refuses the whole thing until the owner allows it there. Nothing installs silently:
 * every session raises Android's own confirmation, every time.
 */
final class Installer {

    /** Where a session's result comes back to. Broadcast to this app only. */
    static final String ACTION_STATUS = "com.pocketide.INSTALL_STATUS";
    static final String EXTRA_TOKEN = "token";

    /** How long an owner is given to answer Android's confirmation before it is a no. */
    private static final long ANSWER_WITHIN_MS = 180_000L;

    /** The one session in flight, and where its result is posted. */
    private static final ArrayBlockingQueue<String> RESULT = new ArrayBlockingQueue<>(1);
    private static volatile String expecting = "";

    private Installer() {}

    /** True when the phone will even consider an install from this app. */
    static boolean allowed(Context context) {
        if (Build.VERSION.SDK_INT < 26) return true;
        try {
            return context.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /** Android's own page for that permission, so a refusal has somewhere to go. */
    static void openPermission(Context context) {
        Intent page = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(android.net.Uri.parse("package:" + context.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(page);
        } catch (Throwable noSuchPage) {
            try {
                context.startActivity(new Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + context.getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable notEvenThat) {
                // The sentence the caller prints already names the setting.
            }
        }
    }

    /**
     * Installs one APK and waits for the owner's answer. Call from a worker thread.
     *
     * Returns the empty string when the app is installed, and a sentence saying why not
     * otherwise. Blocking is deliberate: the caller is a terminal command an agent is waiting
     * on, and a command that returns before the work is done is a command that lies.
     */
    static String install(Context context, File apk, String packageName,
                          Workspace.Progress progress) {
        if (!allowed(context)) {
            return "Android has not been allowed to install apps from PocketIDE. Turn on "
                    + "\"Install unknown apps\" for PocketIDE in the phone's Settings, or pair "
                    + "the phone (Settings → The computer → Test on this phone) and "
                    + "this happens without a tap.";
        }
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        try {
            params.setAppPackageName(packageName);
        } catch (Throwable refused) {
            // Advisory only: Android verifies the name from the APK itself.
        }
        int id;
        PackageInstaller.Session session = null;
        try {
            id = installer.createSession(params);
            session = installer.openSession(id);
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("apk", 0, apk.length())) {
                byte[] buffer = new byte[262144];
                long written = 0;
                long announced = 0;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    written += read;
                    if (written - announced > 4_000_000L) {
                        announced = written;
                        progress.line("Copied " + DeviceProbe.formatBytes(written) + " of "
                                + DeviceProbe.formatBytes(apk.length()) + "…");
                    }
                }
                session.fsync(out);
            }
            String token = packageName + ":" + id;
            RESULT.clear();
            expecting = token;
            Intent status = new Intent(ACTION_STATUS)
                    .setPackage(context.getPackageName())
                    .putExtra(EXTRA_TOKEN, token);
            // Mutable, because the session writes its own result extras into it.
            int flags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent waiting = PendingIntent.getBroadcast(context, id, status, flags);
            progress.line("Android is asking you to confirm the install on the phone's screen"
                    + "…");
            session.commit(waiting.getIntentSender());
        } catch (IOException | RuntimeException failed) {
            if (session != null) session.abandon();
            expecting = "";
            return failed.getMessage() == null ? failed.getClass().getSimpleName()
                    : failed.getMessage();
        } finally {
            if (session != null) session.close();
        }

        String answer;
        try {
            answer = RESULT.poll(ANSWER_WITHIN_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            answer = "Stopped.";
        }
        expecting = "";
        if (answer == null) {
            return "Nobody answered Android's confirmation. Unlock the phone, run this again, "
                    + "and tap Install when it asks.";
        }
        return answer;
    }

    /**
     * Where a session's result arrives, from PhoneReceiver.
     *
     * STATUS_PENDING_USER_ACTION is not a result: it is Android saying it has a screen for the
     * owner and needs somebody to show it. That screen is started from here, and the session
     * reports again when the owner has answered it.
     */
    static void onStatus(Context context, Intent intent) {
        if (intent == null) return;
        String token = intent.getStringExtra(EXTRA_TOKEN);
        if (token == null || !token.equals(expecting)) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm == null) {
                RESULT.offer("Android did not offer a confirmation screen.");
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // The owner is leaving for Android's own screen and coming straight back.
            AppLock.expectReturn();
            try {
                context.startActivity(confirm);
            } catch (Throwable refused) {
                RESULT.offer("Android would not show the confirmation screen: "
                        + (refused.getMessage() == null ? refused.getClass().getSimpleName()
                        : refused.getMessage()));
            }
            return;
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            RESULT.offer("");
            return;
        }
        String said = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        RESULT.offer(reason(status) + (said == null || said.isEmpty() ? "" : " (" + said + ")"));
    }

    private static String reason(int status) {
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "The install was cancelled on the phone's screen.";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "The phone blocked the install.";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "A different app is already installed under that package name. Uninstall "
                        + "it first, or build with a different applicationId.";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "This APK is not compatible with this phone: its minimum Android "
                        + "version, or its processor, does not match.";
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "The APK is not valid, or is not signed.";
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "There is not enough free space on the phone.";
            default:
                return "The install did not finish.";
        }
    }

    // ------------------------------------------------------------------ afterwards

    /** True when {@code packageName} is installed and this app is allowed to see it. */
    static boolean installed(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException notThere) {
            return false;
        } catch (Throwable hidden) {
            return false;
        }
    }

    /**
     * Opens an app this one installed, with no adb.
     *
     * Visible without a <queries> element because this app is its installer of record, which
     * is the whole reason the install above goes through PackageInstaller. Started from the
     * foreground -- the editor is on the screen when an agent runs a command -- so Android's
     * background-activity rules are satisfied.
     */
    static String launch(Context context, String packageName) {
        Intent open;
        try {
            open = context.getPackageManager().getLaunchIntentForPackage(packageName);
        } catch (Throwable hidden) {
            open = null;
        }
        if (open == null) {
            return installed(context, packageName)
                    ? packageName + " has no screen to open."
                    : packageName + " is not installed, or was not installed from here. Run "
                            + "phone install first.";
        }
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        AppLock.expectReturn();
        try {
            context.startActivity(open);
            return "";
        } catch (Throwable refused) {
            return "Android would not open it: " + (refused.getMessage() == null
                    ? refused.getClass().getSimpleName() : refused.getMessage());
        }
    }
}

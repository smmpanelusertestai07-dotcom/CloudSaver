package com.pocketide;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The phone as its own test device.
 *
 * The Android emulator cannot run on a phone, and the earlier answer to "so how does an agent
 * test what it built" was a row that hands the APK to Android's installer and leaves the
 * rest to the owner's thumb. This closes the gap. Android 11 added Wireless debugging, which is
 * adb over TCP with a pairing step, and adbd listens on every interface, loopback included.
 * So an adb client inside this app's Linux can pair with and connect to the phone it is
 * running on -- the same thing Shizuku does from an ordinary app -- and from then on every
 * command a developer runs from a laptop runs from the editor's terminal instead: adb install,
 * adb shell am start, adb logcat, adb exec-out screencap, adb shell input tap, ./gradlew
 * connectedAndroidTest. Build, install, launch, read the log, screenshot, tap: an agent can do
 * the whole loop on real hardware, for nothing.
 *
 * What this class does is the two things a terminal cannot do for itself:
 *
 *   FIND THE PORTS. The phone advertises both the pairing port and the connection port over
 *   mDNS, and both change every time. A shell inside PRoot has no way to ask; NsdManager does.
 *   Every port found is checked to be this phone's own advertisement -- resolved to one of
 *   this phone's own addresses -- so a neighbour's laptop advertising adb on the same Wi-Fi
 *   can never be what gets paired with. adb is then pointed at 127.0.0.1 and nowhere else.
 *
 *   TAKE THE CODE. The pairing code is shown in a Settings dialog that closes the moment
 *   Settings leaves the screen, so an app of its own cannot ask for it. A notification with a
 *   reply box can: the owner reads the six digits, pulls the shade down and types them there,
 *   Settings never leaves the screen, and the code arrives at PhoneReceiver.
 *
 * The adb commands themselves run inside the workspace, and the server they talk to is started
 * with the editor (pocketide-editor.sh), so a connection lasts exactly as long as the editor
 * does and the terminal's own adb sees the same device. Nothing here works without the editor
 * running, and the screen says so rather than pairing into a server that would be gone by the
 * time the terminal asked.
 *
 * Said plainly wherever it is offered: pairing gives the terminal, and any agent in it, what a
 * computer with USB debugging has -- installing and removing apps, reading and writing shared
 * storage, screenshots and taps. It is a step the owner takes, and Android turns Wireless
 * debugging off at every restart on its own.
 */
final class Phone {

    /** What the phone advertises while its pairing dialog is open, and while debugging is on. */
    static final String PAIRING_SERVICE = "_adb-tls-pairing._tcp";
    static final String CONNECT_SERVICE = "_adb-tls-connect._tcp";

    /** The only host adb is ever pointed at. */
    static final String LOOPBACK = "127.0.0.1";

    /** The reply box's key, and the action the reply arrives under. */
    static final String KEY_CODE = "code";
    static final String ACTION_CODE = "com.pocketide.PHONE_CODE";

    private static final int NOTIFICATION = 4202;
    private static final long DISCOVERY_MS = 9000;

    private Phone() {}

    // ------------------------------------------------------------------ what is true

    /** Wireless debugging with pairing is Android 11 and later. */
    static boolean supported() { return Build.VERSION.SDK_INT >= 30; }

    /** Cheap enough for a screen: a file test, not a run of the tools script. */
    static boolean adbInstalled(Context context) {
        return new File(Workspace.root(context), "usr/bin/adb").exists();
    }

    static boolean paired(Context context) {
        return Prefs.of(context).getBoolean(Prefs.PHONE_PAIRED, false);
    }

    static boolean developerOptionsOn(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /** The switch itself. Readable by any app; it is a global setting, not a permission. */
    static boolean wirelessDebuggingOn(Context context) {
        try {
            return Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", 0)
                    == 1;
        } catch (Throwable unreadable) {
            return false;
        }
    }

    // ------------------------------------------------------------------ the two steps

    /**
     * Pairs, then connects. Called on a worker thread inside the running service.
     *
     * The code is checked to be six digits before it goes anywhere near a command line, and
     * the port is a number this class discovered itself. Nothing typed by anyone reaches the
     * shell unvalidated.
     */
    static void pair(Context context, String code) {
        if (code == null || !code.matches("\\d{6}")) {
            tell(context, "Not paired", "The pairing code is six digits. In Wireless "
                    + "debugging tap Pair device with pairing code again, and type the six "
                    + "digits it shows.");
            return;
        }
        int port = discover(context, PAIRING_SERVICE, DISCOVERY_MS);
        if (port <= 0) {
            tell(context, "Not paired", "The phone is not offering to pair. Keep the Pair "
                    + "device with pairing code box open in Settings while you type the code "
                    + "here, with Wi-Fi on.");
            return;
        }
        String out = run(context, "adb pair " + LOOPBACK + ":" + port + " " + code);
        if (!out.contains("Successfully paired")) {
            tell(context, "Not paired", trimmed(out, "adb pair did not succeed."));
            return;
        }
        Prefs.of(context).edit().putBoolean(Prefs.PHONE_PAIRED, true).apply();
        WorkspaceService.record("Phone: paired with itself over Wireless debugging.");
        if (connect(context, false)) {
            tell(context, "Paired and connected", "adb devices in the terminal lists this "
                    + "phone. Turn Wireless debugging off when you are done testing.");
        } else {
            tell(context, "Paired", "Paired. It did not connect just now: with Wireless "
                    + "debugging on, tap Test on this phone → Connect now.");
        }
    }

    /**
     * Connects to the port the phone is advertising. Quiet when the editor starts, loud when
     * the owner tapped Connect. True when adb reports the device connected.
     */
    static boolean connect(Context context, boolean loud) {
        int port = discover(context, CONNECT_SERVICE, DISCOVERY_MS);
        if (port <= 0) {
            if (loud) {
                tell(context, "Not connected", "Wireless debugging is not advertising a "
                        + "port. Turn it on in Developer options — it turns itself off at "
                        + "every restart — with Wi-Fi on, then try again.");
            }
            return false;
        }
        String out = run(context, "adb connect " + LOOPBACK + ":" + port);
        boolean ok = out.contains("connected to " + LOOPBACK);
        if (ok) {
            WorkspaceService.record("Phone: connected to " + LOOPBACK + ":" + port
                    + " — adb devices lists this phone.");
            if (loud) {
                tell(context, "Connected", "adb devices in the terminal lists this phone. "
                        + "Turn Wireless debugging off when you are done testing.");
            }
        } else if (loud) {
            boolean notPaired = out.contains("failed to authenticate")
                    || out.contains("unauthorized");
            tell(context, "Not connected", notPaired
                    ? "This phone has not been paired yet, or the pairing was removed. "
                            + "Choose Pair for the first time."
                    : trimmed(out, "adb connect did not succeed."));
        }
        return ok;
    }

    // ------------------------------------------------------------------ the notification

    /**
     * Posts the notification whose reply box takes the code, and opens Developer options.
     *
     * The order matters: the notification is up before Settings comes to the front, so that
     * the owner's next action after reading the code is to pull the shade down, not to come
     * back here and find the dialog gone.
     */
    static void beginPairing(Activity activity) {
        askForCode(activity);
        openDeveloperOptions(activity);
    }

    static void askForCode(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        RemoteInput input = new RemoteInput.Builder(KEY_CODE)
                .setLabel("Six-digit pairing code")
                .build();
        Intent reply = new Intent(context, PhoneReceiver.class).setAction(ACTION_CODE);
        // Mutable, because a reply box writes the typed text into this intent. Android 12
        // refuses a RemoteInput on an immutable one.
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pending = PendingIntent.getBroadcast(context, 2, reply, flags);
        Notification.Action action = new Notification.Action.Builder(
                Icon.createWithResource(context, R.drawable.ic_touch), "Enter the code", pending)
                .addRemoteInput(input)
                .build();
        String text = "In Wireless debugging, tap Pair device with pairing code. Type the "
                + "six digits it shows here, without leaving Settings.";
        Notification.Builder builder = new Notification.Builder(context, App.CHANNEL_WORKSPACE);
        try {
            manager.notify(NOTIFICATION, builder
                    .setContentTitle("Pair this phone with itself")
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(R.drawable.ic_stat_pocketide)
                    .setOnlyAlertOnce(true)
                    .addAction(action)
                    .build());
        } catch (Throwable notAllowed) {
            // Notifications denied. The screen that led here checked first and offered the
            // by-hand commands instead; this is the backstop.
        }
    }

    /** A result, where the owner is looking: the shade, since Settings is in front. */
    static void tell(Context context, String title, String text) {
        WorkspaceService.record("Phone: " + title + ". " + text);
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        Intent open = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openIntent = PendingIntent.getActivity(context, 3, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(context, App.CHANNEL_WORKSPACE);
        try {
            manager.notify(NOTIFICATION, builder
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(new Notification.BigTextStyle().bigText(text))
                    .setSmallIcon(R.drawable.ic_stat_pocketide)
                    .setContentIntent(openIntent)
                    .setAutoCancel(true)
                    .build());
        } catch (Throwable notAllowed) {
            // The Activity screen has the same line from record() above.
        }
    }

    static void openDeveloperOptions(Activity activity) {
        // The phone's own Settings, reached on purpose: not a way out for the lock.
        AppLock.expectReturn();
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable noSuchScreen) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable notEvenThat) {
                // The notification still says what to do.
            }
        }
    }

    // ------------------------------------------------------------------ finding the port

    /** One discovery at a time: NsdManager refuses a second resolve while one is running. */
    private static final Object DISCOVERY = new Object();

    /**
     * The port this phone advertises under {@code type}, or -1 within {@code timeoutMs}.
     *
     * Only an advertisement that resolves to one of this phone's own addresses counts.
     * Another device on the same network advertising the same service is ignored, which is
     * the difference between "pair the phone with itself" and "pair with whatever answers".
     */
    static int discover(Context context, final String type, long timeoutMs) {
        synchronized (DISCOVERY) {
            final NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
            if (nsd == null) return -1;
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicInteger port = new AtomicInteger(-1);
            final AtomicBoolean resolving = new AtomicBoolean(false);
            NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
                @Override public void onStartDiscoveryFailed(String t, int code) {
                    done.countDown();
                }
                @Override public void onStopDiscoveryFailed(String t, int code) {}
                @Override public void onDiscoveryStarted(String t) {}
                @Override public void onDiscoveryStopped(String t) {}
                @Override public void onServiceLost(NsdServiceInfo info) {}
                @Override public void onServiceFound(NsdServiceInfo info) {
                    if (done.getCount() == 0) return;
                    if (!resolving.compareAndSet(false, true)) return;
                    try {
                        nsd.resolveService(info, new NsdManager.ResolveListener() {
                            @Override public void onResolveFailed(NsdServiceInfo i, int code) {
                                resolving.set(false);
                            }
                            @Override public void onServiceResolved(NsdServiceInfo r) {
                                if (r.getPort() > 0 && isThisPhone(r.getHost())) {
                                    port.set(r.getPort());
                                    done.countDown();
                                }
                                resolving.set(false);
                            }
                        });
                    } catch (Throwable refused) {
                        resolving.set(false);
                    }
                }
            };
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
            } catch (Throwable refused) {
                return -1;
            }
            try {
                done.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Throwable alreadyStopped) {
                // It never started, or stopped itself. Either way nothing is left running.
            }
            return port.get();
        }
    }

    /** True when {@code host} is one of this phone's own addresses, or loopback. */
    static boolean isThisPhone(InetAddress host) {
        if (host == null) return false;
        if (host.isLoopbackAddress()) return true;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return false;
            for (NetworkInterface each : Collections.list(interfaces)) {
                for (InetAddress address : Collections.list(each.getInetAddresses())) {
                    if (Arrays.equals(address.getAddress(), host.getAddress())) return true;
                }
            }
        } catch (Throwable unreadable) {
            // No interface list means no way to prove it is this phone: refused.
        }
        return false;
    }

    // ------------------------------------------------------------------ running adb

    private static String run(Context context, String command) {
        final StringBuilder out = new StringBuilder();
        try {
            Workspace.run(context, command, line -> {
                if (out.length() < 4000) out.append(line).append('\n');
            });
        } catch (Throwable failed) {
            out.append(failed.getMessage() == null
                    ? failed.getClass().getSimpleName() : failed.getMessage());
        }
        return out.toString();
    }

    private static String trimmed(String out, String fallback) {
        String text = out == null ? "" : out.trim();
        if (text.isEmpty()) return fallback;
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    // ------------------------------------------------------------------ the words

    static final String EXPLANATION =
            "Makes this phone the test device an agent can drive. It installs adb — Ubuntu's "
                    + "own arm64 build, about 2 MB — and pairs the phone with itself over "
                    + "Wireless debugging, which Android added in Android 11. After that, adb "
                    + "devices in the editor's terminal lists this phone, and an agent can "
                    + "install what it built, launch it, read its log, screenshot it, tap it "
                    + "and run ./gradlew connectedAndroidTest — on real hardware, for nothing. "
                    + "The emulator cannot run on a phone; this is what replaces it.\n\n"
                    + "Know what pairing gives: the same access a computer with USB debugging "
                    + "has — installing and removing apps, reading and writing the phone's "
                    + "shared storage, screenshots and taps — to the terminal and any agent in "
                    + "it. Turn Wireless debugging off when you are done. Android turns it off "
                    + "at every restart anyway.";

    static final String DEVELOPER_STEPS =
            "Developer options are off on this phone. To turn them on: Settings → About "
                    + "phone (on some phones About device → Version) → tap Build number seven "
                    + "times. Then Settings → System → Developer options → Wireless debugging.";

    static final String STEPS =
            "Pairing, once:\n"
                    + "1. Open the editor, so Linux is running.\n"
                    + "2. Here, choose Pair for the first time. A notification appears and "
                    + "Developer options open.\n"
                    + "3. Turn on Wireless debugging (Wi-Fi must be on), then tap Pair device "
                    + "with pairing code.\n"
                    + "4. Pull the notification shade down and type the six digits into "
                    + "Enter the code. Do not leave Settings — the code disappears with it.\n"
                    + "5. The result arrives as a notification, and adb devices in the "
                    + "terminal lists this phone.\n\n"
                    + "Every time after that: turn Wireless debugging on, open the editor, "
                    + "and it connects by itself. Connect now does the same by hand.\n\n"
                    + "By hand in the terminal, with the ports read off the Wireless "
                    + "debugging screen:\n"
                    + "adb pair 127.0.0.1:PAIRING_PORT CODE\n"
                    + "adb connect 127.0.0.1:PORT";
}

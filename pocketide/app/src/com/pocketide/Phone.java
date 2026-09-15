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
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * adb shell am start, adb logcat, adb exec-out screencap, adb shell input tap, adb shell am
 * instrument. Build, install, launch, read the log, screenshot, tap, test: an agent can do the
 * whole loop on real hardware, for nothing.
 *
 * ON NO NETWORK PORT. adb's server normally listens on TCP 5037 on loopback, and on a phone
 * loopback is shared by every app: the wire protocol has no authentication, so a TCP server
 * would have let any app with INTERNET install, read and tap through this connection. The
 * server answers on a socket inside the app's own storage instead (ADB_SERVER_SOCKET, set by
 * Workspace.start for every PRoot), which nothing outside this app's sandbox can open. The one
 * cost is Gradle's own installDebug and connectedAndroidTest tasks, which speak only to the
 * port and therefore fail closed; assembleDebugAndroidTest plus adb shell am instrument does
 * the same job, and the Help says so.
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
     *
     * A failure the owner can put right -- the wrong number of digits, a box that had closed,
     * a mistyped code -- puts the reply box BACK, with the reason on it. A plain notification
     * in its place would tell them to type "here" with nothing left to type into.
     */
    static void pair(WorkspaceService service, String code) {
        if (code == null || !code.matches("\\d{6}")) {
            askForCode(service, "That was not six digits. ");
            return;
        }
        if (!service.ensureAdbServer()) {
            tell(service, "adb is not answering", NO_SERVER);
            return;
        }
        int port = discover(service, PAIRING_SERVICE, DISCOVERY_MS);
        if (port <= 0) {
            askForCode(service, "The phone was not offering to pair. Keep the Pair device "
                    + "with pairing code box open, with Wi-Fi on, and type its code again. ");
            return;
        }
        String out = run(service, "adb pair " + LOOPBACK + ":" + port + " " + code);
        if (!out.contains("Successfully paired")) {
            askForCode(service, "Pairing failed: " + trimmed(out, "adb pair did not succeed.")
                    + " Tap Pair device with pairing code again and type the new code. ");
            return;
        }
        Prefs.of(service).edit().putBoolean(Prefs.PHONE_PAIRED, true).apply();
        WorkspaceService.record("Phone: paired with itself over Wireless debugging.");
        if (connect(service, false)) {
            tell(service, "Paired and connected", "adb devices in the terminal lists this "
                    + "phone. Turn Wireless debugging off when you are done testing.");
        } else {
            tell(service, "Paired", "Paired. It did not connect just now: with Wireless "
                    + "debugging on, tap Test on this phone → Connect now.");
        }
    }

    /**
     * Connects to the port the phone is advertising. Quiet when the editor starts, loud when
     * the owner tapped Connect. True when adb reports the device connected.
     */
    static boolean connect(WorkspaceService service, boolean loud) {
        if (!service.ensureAdbServer()) {
            if (loud) tell(service, "adb is not answering", NO_SERVER);
            return false;
        }
        int port = discover(service, CONNECT_SERVICE, DISCOVERY_MS);
        if (port <= 0) {
            if (loud) {
                tell(service, "Not connected", "Wireless debugging is not advertising a "
                        + "port. Turn it on in Developer options — it turns itself off at "
                        + "every restart — with Wi-Fi on, then try again.");
            }
            return false;
        }
        String out = run(service, "adb connect " + LOOPBACK + ":" + port);
        boolean ok = out.contains("connected to " + LOOPBACK);
        if (ok) {
            // A connection only succeeds with a paired key, so this is the proof of pairing
            // too -- for a phone paired by hand, or before the app was reinstalled.
            Prefs.of(service).edit().putBoolean(Prefs.PHONE_PAIRED, true).apply();
            WorkspaceService.record("Phone: connected to " + LOOPBACK + ":" + port
                    + " — adb devices lists this phone.");
            if (loud) {
                tell(service, "Connected", "adb devices in the terminal lists this phone. "
                        + "Turn Wireless debugging off when you are done testing.");
            }
        } else if (loud) {
            boolean notPaired = out.contains("failed to authenticate")
                    || out.contains("unauthorized");
            tell(service, "Not connected", notPaired
                    ? "This phone has not been paired yet, or the pairing was removed. "
                            + "Choose Pair for the first time."
                    : trimmed(out, "adb connect did not succeed."));
        }
        return ok;
    }

    private static final String NO_SERVER =
            "adb is installed, but no adb server is answering inside Linux. Stop the editor "
                    + "and open it again, then try once more.";

    /** adb's server socket, inside the app's own storage. Its guest path is /root/.android/adb.sock. */
    static File serverSocket(Context context) {
        return new File(Workspace.root(context), "root/.android/adb.sock");
    }

    /**
     * True when an adb server answers on its socket: the editor's, or the one the service holds.
     *
     * A socket in the app's own storage rather than a TCP port, and that is the security
     * boundary of the whole feature: a port on loopback is reachable by every app on the
     * phone, the adb wire protocol has no authentication, and it is the server that holds the
     * paired key. Nothing outside this app's sandbox can open this file. Workspace.start sets
     * ADB_SERVER_SOCKET for every PRoot, which is what puts the server here.
     */
    static boolean serverListening(Context context) {
        File socket = serverSocket(context);
        if (!socket.exists()) return false;
        android.net.LocalSocket client = new android.net.LocalSocket();
        try {
            client.connect(new android.net.LocalSocketAddress(socket.getAbsolutePath(),
                    android.net.LocalSocketAddress.Namespace.FILESYSTEM));
            return true;
        } catch (Throwable nobody) {
            return false;
        } finally {
            try {
                client.close();
            } catch (Throwable alreadyClosed) {
                // Nothing to undo.
            }
        }
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
        askForCode(activity, "");
        openDeveloperOptions(activity);
    }

    /**
     * The notification with the reply box. {@code status} is empty the first time and the
     * reason the last attempt did not work after that, so the box comes back with its
     * explanation rather than being replaced by one.
     */
    static void askForCode(Context context, String status) {
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
        String text = status + "In Wireless debugging, tap Pair device with pairing code. "
                + "Type the six digits it shows here, without leaving Settings.";
        if (!status.isEmpty()) WorkspaceService.record("Phone: not paired yet. " + status);
        Notification.Builder builder = new Notification.Builder(context, App.CHANNEL_WORKSPACE);
        try {
            manager.notify(NOTIFICATION, builder
                    .setContentTitle(status.isEmpty()
                            ? "Pair this phone with itself" : "Not paired yet")
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

    /**
     * Whether a notification from this app can appear at all.
     *
     * The permission is one of three switches. An owner can turn the app's notifications off
     * as a whole, or this one channel off, and either makes notify() a silent no-op --
     * NotificationManager drops it without an exception. The pairing code is typed INTO a
     * notification, so this is checked before the owner is sent to Settings to read one.
     */
    static boolean canNotify(Context context) {
        if (!Permissions.notificationsAllowed(context)) return false;
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return false;
        try {
            if (!manager.areNotificationsEnabled()) return false;
            android.app.NotificationChannel channel =
                    manager.getNotificationChannel(App.CHANNEL_WORKSPACE);
            return channel == null
                    || channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        } catch (Throwable unreadable) {
            return true;
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

    /** About phone, where Build number lives: seven taps there turn Developer options on. */
    static void openAboutPhone(Activity activity) {
        AppLock.expectReturn();
        try {
            activity.startActivity(new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable noSuchScreen) {
            openDeveloperOptions(activity);
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
            // Everything heard, resolved one at a time. NsdManager refuses a second resolve
            // while one runs, and it does not repeat onServiceFound for a service it has
            // already announced -- so a service dropped because another was being resolved
            // was gone for good. On a Wi-Fi where a laptop advertises adb too, that other
            // one is exactly what resolves first and is (rightly) rejected.
            final ConcurrentLinkedQueue<NsdServiceInfo> waiting = new ConcurrentLinkedQueue<>();

            final class Discovery implements NsdManager.DiscoveryListener {
                @Override public void onStartDiscoveryFailed(String t, int code) {
                    done.countDown();
                }
                @Override public void onStopDiscoveryFailed(String t, int code) {}
                @Override public void onDiscoveryStarted(String t) {}
                @Override public void onDiscoveryStopped(String t) {}
                @Override public void onServiceLost(NsdServiceInfo info) {}
                @Override public void onServiceFound(NsdServiceInfo info) {
                    if (done.getCount() == 0) return;
                    waiting.add(info);
                    resolveNext();
                }

                /**
                 * Starts the next resolve when none is running. Also kicked every 300 ms by
                 * the thread waiting below, so a resolve refused as busy is tried again --
                 * on Android 11 and 12 an earlier resolve can hang for good, and nothing else
                 * would ever try.
                 */
                void resolveNext() {
                    if (done.getCount() == 0) return;
                    if (!resolving.compareAndSet(false, true)) return;
                    final NsdServiceInfo next = waiting.poll();
                    if (next == null) {
                        resolving.set(false);
                        return;
                    }
                    try {
                        nsd.resolveService(next, new NsdManager.ResolveListener() {
                            @Override public void onResolveFailed(NsdServiceInfo i, int code) {
                                boolean busy = code == NsdManager.FAILURE_ALREADY_ACTIVE;
                                if (busy) waiting.add(next);
                                resolving.set(false);
                                if (!busy) resolveNext();
                            }
                            @Override public void onServiceResolved(NsdServiceInfo r) {
                                if (r.getPort() > 0 && isThisPhone(r.getHost())) {
                                    port.set(r.getPort());
                                    done.countDown();
                                }
                                resolving.set(false);
                                resolveNext();
                            }
                        });
                    } catch (Throwable refused) {
                        resolving.set(false);
                        resolveNext();
                    }
                }
            }
            final Discovery listener = new Discovery();
            try {
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
            } catch (Throwable refused) {
                return -1;
            }
            long deadline = System.currentTimeMillis() + timeoutMs;
            try {
                while (System.currentTimeMillis() < deadline
                        && !done.await(300, TimeUnit.MILLISECONDS)) {
                    listener.resolveNext();
                }
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
                    + "and run its instrumented tests with adb shell am instrument — on real "
                    + "hardware, for nothing. The emulator cannot run on a phone; this is "
                    + "what replaces it.\n\nadb answers on a socket inside the app's own "
                    + "storage, not on a network port, so no other app on the phone can use "
                    + "the connection.\n\n"
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
                    + "3. Tap Wireless debugging — its name, not only its switch — and turn "
                    + "it on. Wi-Fi must be on, and the first time Android asks whether to "
                    + "allow it on this network. Then tap Pair device with pairing code.\n"
                    + "4. Pull the notification shade down and type the six digits into "
                    + "Enter the code. Do not leave Settings — the code disappears with it.\n"
                    + "5. The result arrives as a notification, and adb devices in the "
                    + "terminal lists this phone.\n\n"
                    + "Every time after that: turn Wireless debugging on, open the editor, "
                    + "and it connects by itself. Connect now does the same by hand.\n\n"
                    + "By hand, without the notification: put Settings and the editor side "
                    + "by side in split-screen, because the pairing code disappears the "
                    + "moment Settings leaves the screen. Then, with the ports read off the "
                    + "Wireless debugging screen:\n"
                    + "adb pair 127.0.0.1:PAIRING_PORT CODE\n"
                    + "adb connect 127.0.0.1:PORT\n"
                    + "Connecting alone needs no split-screen.\n\n"
                    + "Gradle's own installDebug and connectedAndroidTest talk to adb over a "
                    + "network port, which is exactly what is closed here so that no other "
                    + "app on the phone can use the connection. Build the test APK with "
                    + "./gradlew assembleDebugAndroidTest and run it with adb shell am "
                    + "instrument — the same thing those tasks would have done.";
}

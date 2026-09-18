package com.pocketide;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * What the agents can reach beyond the editor: a browser, screenshots, video, a build toolchain.
 *
 * None of it is installed at set-up, and that is the design. Set-up is already 410 MB and
 * twenty minutes; a browser is another 120 and Playwright another 60 on top, and most people do
 * not need either on their first day. So this is a switch that says what it will cost before it
 * spends anything.
 *
 * Everything it can offer was checked against what arm64 Linux under PRoot actually does, and
 * two of the answers are no:
 *
 *   The Android emulator cannot run here and never will. Google publishes no linux-aarch64
 *   emulator at all, and even a self-built one needs /dev/kvm, which Android's own SELinux
 *   policy denies to every app on an unrooted phone. The phone itself is the test device
 *   instead -- build the APK, install it, run it.
 *
 *   Apps with C or C++ in them cannot be built here, because Google publishes no arm64 NDK.
 *   Java and Kotlin projects can be, and the Android layer now finishes the job: Google's
 *   SDK through sdkmanager, and the four tools Google ships as x86-64 only replaced by
 *   checksum-pinned aarch64 builds. See pocketide-tools.sh for the pins.
 *
 * Saying both of those on the screen is the point. An owner who discovers them half way through
 * a task has been misled by silence.
 */
final class Tools {

    /** What the script reports, as the screen needs it. */
    static final class State {
        final boolean browser;
        final boolean playwright;
        final boolean android;
        /** The SDK is there AND its aapt2 runs AND Gradle is pointed at it: the part that counts. */
        final boolean sdk;
        /** GitHub's command line: the way to a computer this phone is not. */
        final boolean gh;
        final String chromiumVersion;

        State(boolean browser, boolean playwright, boolean android, String chromiumVersion) {
            this(browser, playwright, android, false, false, chromiumVersion);
        }

        State(boolean browser, boolean playwright, boolean android, boolean sdk, boolean gh,
              String chromiumVersion) {
            this.browser = browser;
            this.playwright = playwright;
            this.android = android;
            this.sdk = sdk;
            this.gh = gh;
            this.chromiumVersion = chromiumVersion;
        }

        boolean anything() { return browser || playwright || android; }
    }

    /** What each layer costs, so the screen can say it before the download starts. */
    static final long BROWSER_BYTES = 120L * 1000 * 1000;
    static final long PLAYWRIGHT_BYTES = 60L * 1000 * 1000;
    /** JDK 200, Google's command-line tools 182, platform, build- and platform-tools about 137, the four aarch64 tools 9. */
    static final long ANDROID_BYTES = 530L * 1000 * 1000;
    /** The JDK's share of that, so a phone that already has one is asked for the rest only. */
    static final long JDK_BYTES = 200L * 1000 * 1000;
    static final long GH_BYTES = 12L * 1000 * 1000;

    private Tools() {}

    /**
     * Asks the workspace what is present.
     *
     * Runs the script rather than remembering a preference, because the workspace is a real
     * Linux that the owner can also change from the editor's own terminal -- apt remove chromium
     * in there has to be reflected here, and a preference would go on claiming a browser that
     * is gone.
     */
    static State read(Context context) {
        Map<String, String> values = run(context, "check");
        return new State(
                "yes".equals(values.get("browser")),
                "yes".equals(values.get("playwright")),
                "yes".equals(values.get("android")),
                "yes".equals(values.get("android_sdk")),
                "yes".equals(values.get("gh")),
                values.getOrDefault("chromium", ""));
    }

    /**
     * Installs one layer, reporting each line as it arrives. Call from a background thread.
     * Layers: browser, playwright, android, phone -- the script's own command names.
     */
    static boolean install(Context context, String layer, Workspace.Progress progress) {
        if (!Workspace.installed(context)) {
            progress.line("Linux is not set up yet.");
            return false;
        }
        try {
            Process process = Workspace.start(context,
                    "bash /opt/pocketide/pocketide-tools.sh " + layer);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) progress.line(line);
                }
            }
            return process.waitFor() == 0;
        } catch (Throwable failed) {
            progress.line(Network.plain(failed, "Linux could not run the installer."));
            return false;
        }
    }

    private static Map<String, String> run(Context context, String command) {
        Map<String, String> values = new HashMap<>();
        if (!Workspace.installed(context)) return values;
        try {
            Process process = Workspace.start(context,
                    "bash /opt/pocketide/pocketide-tools.sh " + command);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    int equals = line.indexOf('=');
                    if (equals > 0) {
                        values.put(line.substring(0, equals).trim(),
                                line.substring(equals + 1).trim());
                    }
                }
            }
            process.waitFor();
        } catch (Throwable unreadable) {
            // A workspace that will not answer is reported as having nothing rather than as an
            // error: the screen's next line offers to install it, which is the right next step
            // either way.
        }
        return values;
    }

    /**
     * The honest account of what this phone can and cannot build, shown in full on the screen.
     *
     * Written as findings rather than marketing because every line of it was a specific
     * question with a checkable answer, and two of the answers are permanent noes that an owner
     * is better off reading here than discovering at the end of an afternoon.
     */
    static final String WHAT_CAN_BE_BUILT =
            "Websites and web apps.\n"
                    + "Fully. Node, Deno, Python, Ruby, PHP, Go and Rust all have first-class "
                    + "arm64 builds, and an agent can run a dev server, open it in the browser "
                    + "installed here, screenshot it and read the page back.\n\n"

                    + "Programs and services.\n"
                    + "Fully. Anything that compiles for arm64 Linux compiles here, including "
                    + "C and C++ for this machine itself.\n\n"

                    + "Android apps.\n"
                    + "Java and Kotlin projects build into a real, signed, installable APK "
                    + "once the Android build tools are installed from Settings. That layer "
                    + "installs a JDK, Google's own SDK through sdkmanager, and replaces the "
                    + "four tools Google ships as x86-64 only with checksum-pinned aarch64 "
                    + "builds, then points Gradle at them. A project builds with its own "
                    + "./gradlew after that.\n"
                    + "Apps containing C or C++ cannot be built at all, and that one IS "
                    + "permanent: there is no arm64 Android NDK, which is Google's decision "
                    + "rather than anything this app can change.\n\n"

                    + "Testing an Android app.\n"
                    + "The emulator cannot run here. Google publishes no linux-aarch64 "
                    + "emulator, and even a self-built one needs /dev/kvm, which Android's "
                    + "security policy denies to every app on a phone that is not rooted. "
                    + "Nothing installable changes that.\n"
                    + "What works instead is better on a phone anyway: the phone IS the test "
                    + "device, and the terminal has one command for it, phone. phone install "
                    + "and phone launch work on any phone with no set-up: Android asks you to "
                    + "confirm each install, and the app opens. From Android 11, Settings → "
                    + "The computer → Test on this phone pairs the phone with itself over "
                    + "Wireless debugging, and then phone can also read the app's own log, "
                    + "screenshot and tap it while it is on the screen, run its instrumented "
                    + "tests and uninstall it — and nothing else: no shell, no other app, no "
                    + "files, no device details. The app's own adb, the key and the server "
                    + "live in the app's storage in a root of their own, which Linux cannot "
                    + "reach; there is no adb inside Linux at all. Gradle's own installDebug "
                    + "and connectedAndroidTest expect adb's network port, which the app "
                    + "never opens; phone install and phone instrument do the same work. "
                    + "JVM unit tests run here natively; Robolectric too, in legacy graphics "
                    + "mode and without SQLite on this processor, so pixels and databases go "
                    + "to the phone.\n\n"

                    + "iOS apps.\n"
                    + "Written here, yes; built and installed, not here. Apple's licence "
                    + "allows its SDK on Apple hardware only, so there is no legitimate "
                    + "Linux trick, and every sideloading tool needs a Mac or a Windows PC "
                    + "for the signing step. What works: push the code to a free cloud Mac "
                    + "— GitHub Actions is free and unlimited for a public repository, "
                    + "Codemagic gives 500 Mac minutes a month, Expo EAS 15 builds a month "
                    + "— and ship the result as a web app, through TestFlight with a $99 a "
                    + "year Apple developer account, or as a 7-day sideload from a computer. "
                    + "The FAQ has the detail.\n\n"

                    + "Games.\n"
                    + "Small 2D and 3D games, yes, with Godot 4: it publishes a Linux arm64 "
                    + "build that exports an Android APK from the terminal with no window "
                    + "(godot --headless --export-debug Android game.apk), once its 1.3 GB "
                    + "of export templates are downloaded. Unity and Unreal cannot run here "
                    + "at all: both publish their Linux editors for x86-64 only, and there "
                    + "is no arm64 build to download. A game the size of Free Fire is a "
                    + "studio's multi-year work on those two engines with servers and a "
                    + "team behind it, which is a hardware and headcount fact rather than "
                    + "a limit of this app; the FAQ says what is realistic.\n\n"

                    + "Machine learning.\n"
                    + "Small models and ordinary data work, yes. Training anything large needs "
                    + "a GPU this phone will not give a Linux process.";
}

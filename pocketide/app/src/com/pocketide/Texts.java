package com.pocketide;

/**
 * Everything the app says about itself: what it is for, what it is not, what it asks of the
 * phone, and what it will and will not do with what it finds there.
 *
 * It lives in one file rather than scattered through the screens for a reason that is easy to
 * forget until it bites: a claim in a FAQ and the code that makes the claim true drift apart
 * silently. Keeping the words together makes them reviewable as a set, and several of the gates
 * in tests/ read this file directly -- the permission list here is checked against the manifest,
 * and the agent facts against the registry.
 *
 * Every sentence is meant to survive being read by someone who does not trust the app yet.
 */
final class Texts {
    private Texts() {}

    // ------------------------------------------------------------------ about

    static final String MISSION_TITLE = "What this is for";

    static final String MISSION =
            "PocketIDE exists for one kind of person: someone who wants to build software and "
                    + "does not own a computer.\n\n"
                    + "Every serious way to work with a coding agent today assumes a laptop. The "
                    + "agent-first desktop apps are desktop-only. The editor integrations are "
                    + "desktop-only. The terminal agents want a machine you can leave running. "
                    + "The phone apps the same companies ship are remote controls — the work "
                    + "still happens on a computer somewhere, and if you do not have one, you "
                    + "have nothing.\n\n"
                    + "This app makes the phone that computer. A real Ubuntu system, a real "
                    + "Visual Studio Code, and whichever coding agent you want, all running on "
                    + "the handset in your pocket. No laptop, no cloud subscription for compute, "
                    + "no remote machine to keep awake.";

    static final String VISION_TITLE = "Where it is going";

    static final String VISION =
            "The app is a host, not a product that has to keep up.\n\n"
                    + "New agent features arrive through the publishers' own extensions, from "
                    + "the Open VSX registry, and update themselves. New editor features arrive "
                    + "with code-server. New languages and tools arrive through apt. None of "
                    + "that needs this app to be updated, which is the point: when a fourth "
                    + "company ships a first-party coding agent, you install it the day it "
                    + "appears rather than waiting for someone to add a button for it.\n\n"
                    + "What this app owns is the part that has to be right and then stay still: "
                    + "getting Linux onto the phone safely, keeping the editor usable at 400 "
                    + "pixels wide, and being honest about what is happening.";

    static final String HOW_TITLE = "How it fits together";

    static final String HOW =
            "Your phone\n"
                    + "  └─ PocketIDE, an Android app\n"
                    + "      └─ Ubuntu 24.04 LTS (ARM64), under PRoot — no root, no virtual machine\n"
                    + "          └─ code-server — Visual Studio Code, MIT licensed\n"
                    + "              └─ extensions from Open VSX — the agents\n\n"
                    + "The agents' models run in their own companies' clouds, as they do "
                    + "everywhere. Your files stay on the phone and edits happen on the phone.";

    // ------------------------------------------------------------------ FAQ

    /**
     * Question, answer, group. Grouped contiguously, because the Help screen prints a heading
     * whenever the group changes -- so an array that returns to a group it already used prints
     * that heading twice, which is what this one did.
     *
     * Nineteen entries, down from thirty-two. What went was everything the screen it sits on
     * already answers: the list of agents, which Home and Agents both show by name; the
     * permission list, which is rendered directly beneath it; "do I need a computer", which the
     * first line of the app answers. A FAQ that restates the app is a FAQ nobody finishes.
     */
    static final String[][] FAQ = {
            {"About",
             "What is this?",
             "A real Ubuntu Linux system and a real Visual Studio Code, running inside an "
                     + "Android app, with coding agents added as their publishers' own "
                     + "extensions. It all runs on the phone \u2014 nothing is streamed from a "
                     + "server, which is why a phone several years old can run it."},
            {"About",
             "What happens if I uninstall it?",
             "Everything goes: Linux, the editor, the extensions and your projects. They live "
                     + "in the app's own storage, so Android removes them with the app. Push "
                     + "anything you want to keep to git, or save it to the phone's own files "
                     + "first."},

            {"Editor",
             "Is this the real Visual Studio Code?",
             "It is Code-OSS, the open-source project Microsoft builds Visual Studio Code from, "
                     + "packaged by Coder as code-server. Same editor, same settings, same "
                     + "keyboard shortcuts, same extension format."},
            {"Editor",
             "Do I need a Microsoft account or licence?",
             "No. Code-OSS is MIT licensed and so is code-server. Nothing here asks Microsoft "
                     + "for anything."},
            {"Editor",
             "What is Open VSX?",
             "The extension registry this app installs from, run by the Eclipse Foundation. "
                     + "Microsoft's own marketplace is licensed for Microsoft's own products "
                     + "only, so every editor that is not Visual Studio Code itself uses Open "
                     + "VSX \u2014 including the desktop editors these agents ship in."},

            {"Agents",
             "Why these three?",
             "Each of the three companies builds its own frontier AI model, ships its own "
                     + "coding agent for editors, and publishes it under its own verified name "
                     + "on Open VSX. They are set up for you, not fenced in: any extension on "
                     + "Open VSX can be installed from the Agents screen."},
            {"Agents",
             "Which of them are free?",
             "Antigravity has a free tier. Claude Code needs a Claude subscription or "
                     + "pay-as-you-go; Codex is included with ChatGPT Plus and above. The "
                     + "Agents screen shows each one's terms in the publisher's own words."},
            {"Agents",
             "Does my code go to the cloud?",
             "Your project stays here \u2014 the files, the git history, the build output, "
                     + "whatever is in your terminal. The agent does its work on this phone.\n\n"
                     + "The AI model does not. It runs in the company's data centre, so your "
                     + "questions and the files the agent needs to read are sent there to be "
                     + "answered, exactly as they would be from a laptop. Running on your phone "
                     + "is not the same as working offline, and no app can make it so."},
            {"Agents",
             "Can it see what it built?",
             "Yes, once you add the browser in Settings. An agent can start your site, open it, "
                     + "take a screenshot, read the page back, click through it, and record a "
                     + "video of the run."},

            {"Building",
             "What can I build today, with nothing extra installed?",
             "Websites and web apps, and programs in Python, Node, Go, Rust, C and C++. Git, a "
                     + "terminal and a compiler are all here from the first start."},
            {"Building",
             "Can it build an Android app?",
             "Java and Kotlin projects, yes \u2014 into a real, installable APK, once you add "
                     + "the build tools in Settings. Apps containing C or C++ cannot be built: "
                     + "Google publishes no Android compiler for this kind of processor, which "
                     + "is Google's decision rather than a limit of your phone."},
            {"Building",
             "Can I test an Android app here?",
             "Yes, but not with the emulator \u2014 that cannot run on a phone. Google ships no "
                     + "emulator for this processor, and even one built by hand needs a "
                     + "virtualisation device Android does not give apps unless the phone is "
                     + "rooted.\n\nWhat you get instead is better: your phone is the test "
                     + "device. Build the APK, install it, and it runs on real hardware. Unit "
                     + "tests run here directly."},
            {"Building",
             "Can I build an iPhone app?",
             "No. Apple requires its own tools on a Mac to build and sign them, and no Android "
                     + "app can provide that."},
            {"Building",
             "Can I make games?",
             "2D and web games, yes \u2014 any engine that runs on Linux and does not need a "
                     + "graphics card. Large 3D engines expect a desktop GPU and will not be "
                     + "happy here."},

            {"Safety",
             "How do I know an extension is safe?",
             "Start with the publisher. Open VSX marks a publisher verified when it has proved "
                     + "it controls the name it publishes under, so a verified Google, Anthropic "
                     + "or OpenAI really is that company. This app shows only verified "
                     + "publishers by default, and every counterfeit extension found on either "
                     + "major registry through 2026 came from an unverified account imitating a "
                     + "name it did not own.\n\nBefore installing anything, check four things: "
                     + "the publisher is verified; the downloads and reviews look like real use "
                     + "rather than a week-old listing; the name is spelled exactly right, since "
                     + "imitations rely on one swapped letter; and the description says what the "
                     + "extension does, not only what it is for.\n\nWhere a company publishes "
                     + "no official extension, a well-established community one is a reasonable "
                     + "choice \u2014 prefer a long history, an open repository and recent "
                     + "updates. Turning off the verified-only filter is yours to decide, and "
                     + "the app asks you to confirm it once."},
            {"Safety",
             "What can an extension actually do?",
             "Everything you can do in the editor: read and change any file in Linux, run "
                     + "commands, and use the network. That is true of extensions in every "
                     + "editor, desktops included \u2014 it is not a weakness of this app. What "
                     + "limits it here is Android: all of it happens inside this app's own "
                     + "storage, and nothing an extension does can reach your photos, your "
                     + "messages or your other apps."},
            {"Safety",
             "Can another app on my phone reach the editor?",
             "Not from the internet \u2014 the editor cannot be reached from outside this phone "
                     + "at all. Another app on the same phone could in principle connect to it, "
                     + "which is why it sits behind a password the phone generates for itself "
                     + "and never shows you. Turn on the app lock in Settings and a fingerprint "
                     + "is needed to open the editor at all."},

            {"Phone",
             "Which phones can run it?",
             "A 64-bit ARM phone on Android 10 or newer, with about 4 GB of free space and "
                     + "ideally 6 GB of memory. It runs on 4 GB with fewer things open at once. "
                     + "Home tells you where your phone stands before you start."},
            {"Phone",
             "Will my phone get hot?",
             "During set-up, yes \u2014 it unpacks and installs for twenty minutes or so. "
                     + "Afterwards it is an editor: warm while a build runs, cool while you read "
                     + "code. Activity shows what is running and lets you stop it."},
    };

    // ------------------------------------------------------------------ terms

    static final String TERMS_TITLE = "Terms of use";

    static final String TERMS =
            "1. What this app is.\n"
                    + "PocketIDE is a host. It installs and runs software published by other "
                    + "people: Canonical's Ubuntu, Coder's code-server, Microsoft's Code-OSS, "
                    + "and extensions from the Open VSX registry. It is not affiliated with, "
                    + "endorsed by or sponsored by Microsoft, Google, Anthropic, OpenAI, "
                    + "Canonical, Coder or the Eclipse Foundation. Their names and marks belong "
                    + "to them and are used here only to say truthfully whose software this is.\n\n"

                    + "2. Third-party terms apply.\n"
                    + "Using a coding agent means agreeing to that company's own terms and "
                    + "paying for their own plan. This app does not resell, proxy, subsidise or "
                    + "provide access to any of them.\n\n"

                    + "3. Accounts and payment.\n"
                    + "You sign in to each agent with your own account, inside that publisher's "
                    + "own extension. PocketIDE never asks for, sees or stores those "
                    + "credentials, and no sign-in ever happens in a window this app controls.\n\n"

                    + "4. Extensions are third-party software.\n"
                    + "Extensions run with the same permissions as the editor. Installing an "
                    + "unverified extension is your decision and your risk. Before anything is "
                    + "installed you are shown the publisher, whether the namespace is verified, "
                    + "the version, the size and the checksum.\n\n"

                    + "5. Community components.\n"
                    + "Some optional toolchains are community-maintained rather than published "
                    + "by the vendor — the ARM64 build of Android's aapt2 is the main one. They "
                    + "are labelled as such before installation, never presented as official.\n\n"

                    + "6. No warranty.\n"
                    + "The app is provided as-is, without warranty of any kind. Building, "
                    + "publishing and distributing anything you make is your responsibility, "
                    + "including app-store rules, licences, export rules and the law where you "
                    + "live.\n\n"

                    + "7. What you are responsible for.\n"
                    + "Anything an agent does in your Linux happens because you asked for "
                    + "it. Read what it proposes before you accept it, exactly as you would a "
                    + "change from anyone else.\n\n"

                    + "8. Licence.\n"
                    + "PocketIDE's own code is Apache-2.0. Every bundled component keeps its "
                    + "own licence; see Open-source notices.\n\n"

                    + "9. Changes.\n"
                    + "The terms shown here are the current ones. Anything material is noted in "
                    + "the release notes rather than changed quietly.";

    // ------------------------------------------------------------------ privacy

    static final String PRIVACY_TITLE = "Privacy";

    static final String PRIVACY_SUMMARY =
            "Nothing leaves your phone except what you ask for.";

    static final String PRIVACY =
            "No analytics. No telemetry. No crash reporting to anyone. No account. No server.\n\n"

                    + "There is no PocketIDE account and no PocketIDE server, so there is "
                    + "nowhere for your data to be sent even by accident. Telemetry is switched "
                    + "off in the editor's own settings as well.\n\n"

                    + "Where your files live.\n"
                    + "In this app's private storage. Android keeps that apart from every other "
                    + "app on the phone. Nothing is backed up, synced or copied anywhere.\n\n"

                    + "When the app uses the network.\n"
                    + "Three times, and only these three:\n"
                    + "  • downloading Ubuntu packages from Canonical\n"
                    + "  • downloading the editor and extensions from Coder's releases and the "
                    + "Open VSX registry\n"
                    + "  • each agent talking to its own company, under that company's own "
                    + "privacy policy\n\n"

                    + "Where your sign-ins are kept.\n"
                    + "Inside Linux, in each publisher's own extension storage. "
                    + "Never in this app's code and never in the installed package.\n\n"

                    + "The editor's password.\n"
                    + "Generated on this phone, kept on this phone, and used only to stop other "
                    + "apps on the same phone from reaching the editor over loopback.\n\n"

                    + "Crash records.\n"
                    + "If the app stops unexpectedly it writes the technical details to a file "
                    + "in its own storage so you can read or share them deliberately. That file "
                    + "is never sent anywhere on its own.\n\n"

                    + "On uninstall.\n"
                    + "Everything is deleted with the app.";

    /** The permission list, checked against AndroidManifest.xml by tests/permissions.py. */
    static final String[][] PERMISSIONS = {
            {"android.permission.INTERNET",
             "Internet", "Downloads, and each agent's own traffic."},
            {"android.permission.ACCESS_NETWORK_STATE",
             "Network state", "Follow DNS when Wi-Fi and mobile data swap, and know when to wait."},
            {"android.permission.WAKE_LOCK",
             "Keep the processor awake", "So a twenty-minute download is not stopped by the screen turning off."},
            {"android.permission.POST_NOTIFICATIONS",
             "Notifications", "The running notice, its progress, and its Stop button."},
            {"android.permission.FOREGROUND_SERVICE",
             "Foreground service", "So Android does not kill Linux while you are using it."},
            {"android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
             "Foreground service type", "Declares what that service is for, as Android requires."},
            {"android.permission.VIBRATE",
             "Vibration", "Feedback on the key row."},
            {"android.permission.USE_BIOMETRIC",
                    "App lock",
                    "Only to ask the phone to confirm it is you when the app lock is on. The "
                            + "check is the phone's own; no fingerprint or PIN ever reaches "
                            + "this app, and nothing is stored. Granted at install because it "
                            + "is a normal permission, and unused while the lock is off."},
            {"android.permission.MANAGE_EXTERNAL_STORAGE",
                    "The phone's files, if you turn them on",
                    "Off unless you switch it on in Settings, and Android grants it on a page "
                            + "of its own. On, the phone's storage appears inside Linux "
                            + "as ~/phone so you can open a file from Downloads or save a build "
                            + "somewhere that outlives the app. Nothing is read or copied "
                            + "without you doing it."},
            {"android.permission.READ_EXTERNAL_STORAGE",
                    "The phone's files on Android 10",
                    "The same thing, on the one Android version that predates the page above. "
                            + "Declared with maxSdkVersion 29, so newer phones never see it."},
            {"android.permission.WRITE_EXTERNAL_STORAGE",
                    "Writing to the phone's files on Android 10",
                    "As above, and equally limited to Android 10."},
            {"android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
             "Battery", "A one-tap prompt so long work is not stopped. Always optional."},
    };

    /** Named so the Help screen can say what is deliberately absent. */
    static final String NOT_REQUESTED =
            "Camera, microphone, location, contacts, SMS, calendar, phone, storage and account "
                    + "access are not requested at all. Nothing in this app needs them, and an "
                    + "app that asks for what it does not need is not one to trust with a code "
                    + "repository.";

    // ------------------------------------------------------------------ where it fits

    static final String SURFACES_TITLE = "Where this fits";

    static final String SURFACES =
            "The industry calls these development interfaces, or product surfaces. There are "
                    + "four, and all three companies support all four:\n\n"
                    + "• Agent-first desktop Linux — hand over a project and supervise. "
                    + "Desktop only; a phone can watch it through a remote companion.\n\n"
                    + "• IDE integration — the agent inside the editor, with code, terminal and "
                    + "debugger. Normally desktop only.\n\n"
                    + "• Terminal agent (CLI) — the agent in the project folder's terminal. "
                    + "Normally desktop; a phone can reach it remotely.\n\n"
                    + "• Browser Linux — build and preview on a website. Works on a phone, "
                    + "but what it can do varies.\n\n"
                    + "Two supporting modes exist as well: a mobile remote companion, where the "
                    + "work still runs on a connected computer or in the cloud, and headless "
                    + "automation through an SDK or CI.\n\n"
                    + "PocketIDE's position: it makes the second one — IDE integration, ranked "
                    + "second overall for features and control — run on the phone itself, with "
                    + "no connected computer anywhere. That is the whole point of the app.";
}

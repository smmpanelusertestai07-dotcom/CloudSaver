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
                    + "      └─ Ubuntu 24.04 LTS (ARM64), through PRoot, a translation layer — "
                    + "no root, no virtual machine\n"
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
     * Twenty-five entries. It was cut from thirty-two to nineteen once, and the rule that did
     * the cutting still applies: nothing here may restate what the screen it sits on already
     * says. What went then was the list of agents, which Home and Agents both show by name; the
     * permission list, which is rendered directly beneath it; and "do I need a computer", which
     * the first line of the app answers.
     *
     * The ones added since are all questions the app cannot answer by showing something -- what
     * updates itself and what does not, why none of it can happen with the app closed, how long
     * this Ubuntu is supported, how big a project the phone can build, how the editor is worked
     * with a thumb, and why a set-up stops when the screen goes off. A person deciding whether
     * to start real work here asks all of them, and none is visible on any screen.
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
             "How do I zoom, reach a command, or move the cursor?",
             "Pinch to zoom the editor, as in a browser, and pinch back. The Commands button "
                     + "under the editor opens the command palette, which is where every "
                     + "command lives \u2014 the same as pressing F1 on a keyboard. Keys shows "
                     + "a row of the keys a phone keyboard lacks: Ctrl, Esc, Tab, the arrows, "
                     + "Home and End. Cursor shows a pad that moves the text cursor when you "
                     + "drag on it.\n\nThe pad exists because the code editor itself does not "
                     + "yet support selecting text with a finger \u2014 that is a limit of "
                     + "Visual Studio Code's editor component on every phone, not of this "
                     + "app. The agents' panels and the terminal are ordinary web pages, and "
                     + "a long press selects text in them as it does anywhere."},
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
             "Do the agents run here, or on a cloud computer?",
             "Here. Each agent's extension runs its loop on this phone: it reads your files, "
                     + "edits them, and runs commands in this Linux. Your project stays here "
                     + "\u2014 the files, the git history, the build output, whatever is in "
                     + "your terminal.\n\nThe AI model does not. It runs in the company's data "
                     + "centre, so your questions and the files the agent needs to read are "
                     + "sent there to be answered, exactly as they would be from a laptop. "
                     + "Running on your phone is not the same as working offline, and no app "
                     + "can make it so.\n\nAll three companies also sell a separate mode where "
                     + "the whole job runs on a computer of theirs. This app does not use "
                     + "those: the point of it is that the computer is the one in your hand."},
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
             "Java and Kotlin projects, yes \u2014 into a real, signed, installable APK, with "
                     + "the project\u2019s own ./gradlew, once Settings \u2192 The computer "
                     + "\u2192 Android build tools has been installed (about 520 MB, once). "
                     + "That installs a JDK, Google\u2019s own SDK, and aarch64 builds of the "
                     + "four build tools Google only ships for x86-64 \u2014 each checked "
                     + "against a checksum before it is used.\n\nApps containing C or C++ "
                     + "cannot be built: Google publishes no Android compiler for this kind "
                     + "of processor, which is Google\u2019s decision rather than a limit of "
                     + "your phone."},
            {"Building",
             "Can I test an Android app here?",
             "Yes \u2014 on this phone, which is the test device, and from Android 11 the "
                     + "agent can drive it. Settings \u2192 The computer \u2192 Test on this "
                     + "phone installs adb (Ubuntu\u2019s own arm64 build, about 2 MB) and "
                     + "pairs the phone with itself over Wireless debugging: the app finds "
                     + "the port the phone advertises, you type the six-digit code into a "
                     + "notification, and from then on adb devices in the terminal lists this "
                     + "phone. An agent can install what it built, launch it, read its log, "
                     + "screenshot it, tap it and run ./gradlew connectedAndroidTest \u2014 on "
                     + "real hardware, for nothing. JVM and Robolectric tests run here "
                     + "directly as well, and Install an app built here hands an APK to "
                     + "Android\u2019s installer without any pairing.\n\nThe emulator cannot "
                     + "run on a phone: Google ships none for this processor, and even one "
                     + "built by hand needs a virtualisation device Android does not give "
                     + "apps unless the phone is rooted.\n\nKnow what pairing gives: the "
                     + "same access a computer with USB debugging has \u2014 installing and "
                     + "removing apps, reading and writing the phone\u2019s shared storage, "
                     + "screenshots and taps \u2014 to the terminal and any agent in it. Turn "
                     + "Wireless debugging off when you are done; Android turns it off at "
                     + "every restart anyway."},
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

            {"Updates",
             "Does it keep itself up to date?",
             "Yes, every layer of it, and each can be switched off in Settings.\n\n"
                     + "Ubuntu\u2019s security updates are taken automatically \u2014 on Wi-Fi, "
                     + "once a day, while the app is open and the editor is not. The editor "
                     + "follows code-server\u2019s releases the same way, only while it is "
                     + "closed, and only after the new copy has unpacked and proved it runs; "
                     + "if it does not, the old one goes straight back. Extensions are kept "
                     + "current by the editor itself, from Open VSX.\n\nThe app itself asks "
                     + "GitHub once a day whether a newer PocketIDE has been published and "
                     + "tells you on the Home screen. Installing it is your tap: it downloads "
                     + "in the browser and installs over this version, and Linux, the editor "
                     + "and your projects are untouched."},
            {"Updates",
             "Why only while the app is open?",
             "Because Linux only exists while the app is open. Android does not keep another "
                     + "operating system running behind a closed app, and there is no way to "
                     + "ask it to. An app that claimed to update your Linux overnight would be "
                     + "describing something that cannot happen."},
            {"Updates",
             "How long will Ubuntu 24.04 keep getting updates?",
             "Until May 2029 for the standard security updates, which is Canonical\u2019s own "
                     + "published date for this release. Ubuntu Pro extends the same release to "
                     + "May 2034 and is free for personal use on up to five machines. There is "
                     + "a further paid Legacy add-on to May 2039, which the free tier does not "
                     + "include. When 26.04 LTS is worth moving to, that will be a set-up you "
                     + "choose rather than something that happens to you."},

            {"Phone",
             "How big a project can this build?",
             "The app\u2019s own size is not the limit \u2014 a 200 MB APK is no harder to "
                     + "produce than a 2 MB one. What costs memory is the compiler, and that is "
                     + "decided by how many modules and dependencies a project has, not how big "
                     + "it ends up. Settings \u2192 The computer shows what your phone gives "
                     + "it: cores, memory, free space, and the build heap worked out from them."},
            {"Phone",
             "Which phones can run it?",
             "A 64-bit ARM phone on Android 10 or newer \u2014 any version from 10 up to the "
                     + "current one \u2014 with about 4 GB of free space and ideally 6 GB of "
                     + "memory. It runs on 4 GB with fewer things open at once: the phone this "
                     + "app is sized for is a 4 GB realme C25s on Android 12, and every memory "
                     + "figure inside it \u2014 the editor\u2019s heap, the build workers, the "
                     + "text size \u2014 is worked out from what the phone actually has rather "
                     + "than assumed. Home tells you where your phone stands before you start, "
                     + "and refuses to begin a set-up that could not finish.\n\nIt is an "
                     + "Android app and only an Android app. There is no iPhone version, "
                     + "because iOS does not let an app run another operating system inside "
                     + "itself the way Android does."},
            {"Phone",
             "Set-up or a build stopped when the screen went off. Why?",
             "The phone\u2019s battery manager ended it. Android\u2019s own switch is "
                     + "\u201cBattery: unrestricted\u201d, and Settings \u2192 Permissions "
                     + "reads it and opens it. Realme, OPPO, Xiaomi, vivo, OnePlus, Huawei and "
                     + "Samsung add switches of their own \u2014 auto-launch and background "
                     + "activity \u2014 that no app is allowed to read, so those two rows show "
                     + "the path to them in this phone\u2019s own menu words instead. Plugging "
                     + "the phone in helps too: most phones relax the battery manager while "
                     + "charging."},
            {"About",
             "Is this a computer, or an editor?",
             "It is an editor \u2014 an IDE \u2014 that happens to carry the Linux it needs to "
                     + "build and run what you write. That is the distinction worth being "
                     + "clear about, because the two are judged on different things.\n\nA "
                     + "Linux-on-Android app is judged on how complete the Linux is. This is "
                     + "judged on whether you can actually get work done on a phone: whether "
                     + "the editor fits a thumb, whether an agent can be signed in to and "
                     + "watched, whether a build survives the screen going off. Those are the "
                     + "problems solved here, and they are the ones that do not go away as "
                     + "phones get faster.\n\nOn a desktop this would be second best; nobody "
                     + "should give up a laptop for it. On a phone, where the alternative is a "
                     + "terminal emulator and a text editor, there is nothing else that puts a "
                     + "real Visual Studio Code, a real Linux and first-party coding agents "
                     + "together in one thing you can open on a bus."},
            {"Agents",
             "Why only these three, and can I add another AI?",
             "Three are pre-set. Every other one is an install away, and nothing here is a "
                     + "fence.\n\nThe three are pre-set because they pass the same four tests: "
                     + "the company trains its own frontier model family, ships its own "
                     + "first-party agentic coding extension, owns frontier-scale compute, and "
                     + "publishes a frontier-safety policy. Anthropic, Google DeepMind and "
                     + "OpenAI are the three that pass all four, and independent 2026 trackers "
                     + "place them level with each other on agentic coding and ahead of the "
                     + "rest. Being briefly top of a leaderboard is not one of the tests; "
                     + "staying there is.\n\nAnything else on Open VSX installs from the "
                     + "Agents screen \u2014 official extensions and community ones alike. That "
                     + "is what makes a command-line agent usable here: somebody wraps it in an "
                     + "extension, and it arrives with a panel, buttons and a diff view instead "
                     + "of a terminal prompt. Community extensions are hidden until you turn "
                     + "them on, and then marked UNVERIFIED wherever they appear, because that "
                     + "is the one thing every counterfeit found on the registry in 2026 had in "
                     + "common.\n\nWhen a fourth company publishes a first-party coding agent "
                     + "under a verified publisher name, you will be able to install it the day "
                     + "it appears, without waiting for this app to be updated."},
            {"Agents",
             "I installed an agent. How do I open it?",
             "Open the editor, tap Menu on the bar along the bottom, and it is named there \u2014 "
                     + "\u201cOpen Antigravity\u201d, and the same for any other agent that "
                     + "brings a panel with it. Tapping the row on the Agents screen offers the "
                     + "same thing.\n\nInside the editor it also lives in the activity bar, "
                     + "which on a phone is the row of small icons along the bottom of the "
                     + "editor itself. The menu exists because that row is easy to miss."},
            {"Editor",
             "Can I use it sideways?",
             "Yes. Turn the phone and the editor turns with it, keeping the session, the open "
                     + "files and anything running in the terminal \u2014 it is not reloaded. "
                     + "Landscape gives the editor roughly twice the width, which is enough for "
                     + "a file tree beside the code, or a diff with both sides showing.\n\nThe "
                     + "text size is worked out from the upright width and left alone when you "
                     + "turn the phone, so the editor does not resize its own text under you. "
                     + "Menu \u2192 Larger text and Smaller text change it whenever you want."},
            {"Editor",
             "Something is running off the side of the screen.",
             "Menu \u2192 Smaller text. The editor is laid out at a width worked out from this "
                     + "screen, and a phone set to a large system text size asks for more room "
                     + "than the layout has \u2014 so the app holds the editor to a minimum "
                     + "usable width and lets you adjust from there. You can also pinch to zoom "
                     + "anywhere in the editor, as in a browser."},
            {"Phone",
             "The app closes as soon as I open it.",
             "It will not do that twice more. The app counts its own openings and clears the "
                     + "count once a screen has actually been drawn; after two openings that "
                     + "never got that far, the third opens a recovery screen instead. That "
                     + "screen says what happened, lets you copy the details, and offers to "
                     + "reset what the app remembers \u2014 which does not touch Linux, the "
                     + "editor, the extensions or your projects.\n\nIf it happens once and "
                     + "then stops, Home shows what was recorded under \u201cThe app stopped "
                     + "unexpectedly\u201d."},
            {"Phone",
             "Will my phone get hot?",
             "During set-up, yes \u2014 it unpacks and installs for twenty minutes or so. "
                     + "Afterwards it is an editor: warm while a build runs, cool while you read "
                     + "code. Activity shows what is running and lets you stop it.\n\nIf the "
                     + "phone reaches the level Android calls critical, the app pauses "
                     + "everything in Linux \u2014 every process is held where it stands, "
                     + "nothing is killed and nothing is lost \u2014 and resumes it by itself "
                     + "once the phone has cooled. Activity says PAUSED while that lasts. That "
                     + "is instead of what a phone otherwise does on its own, which is to kill "
                     + "the most expensive thing running, mid-download or mid-build."},
            {"Updates",
             "What updates itself, and what waits for me?",
             "By itself, with nothing to press: Ubuntu\u2019s security fixes, once a day on "
                     + "Wi-Fi; the editor, once a day on Wi-Fi while it is closed, through a "
                     + "staged and checked swap that can be rolled back; every extension, by "
                     + "the editor itself while it is open; and a daily look at whether a newer "
                     + "PocketIDE has been published.\n\nWaiting for you, and only these: "
                     + "installing a new PocketIDE, because Android does not let an app replace "
                     + "itself without your tap; installing the optional tool layers, because "
                     + "each is hundreds of megabytes you should decide to spend; and the "
                     + "rows in Settings \u2192 Staying current that run any of the above now "
                     + "instead of tonight. Each switch there can also turn its automatic part "
                     + "off, and says what happens if you do."},
            {"About",
             "How does this compare with the Linux terminal on Pixel phones?",
             "Android 15 gave recent Pixel phones a Linux Terminal: Debian running in a "
                     + "virtual machine, as a developer option. It is a good sign for this "
                     + "whole idea, and it is a different thing.\n\nIt needs a phone with "
                     + "hardware virtualisation, which is why it is Pixel-first; it is a "
                     + "terminal, not an editor; and it comes with no coding agents. This app "
                     + "runs Ubuntu without a virtual machine, which is why it runs on a 4 GB "
                     + "phone from 2021 on Android 10, and it puts a real Visual Studio Code and "
                     + "first-party agents on top. The honest trade: a virtual machine runs "
                     + "closer to native speed than the translation layer used here. What you "
                     + "get in return is that it works on the phone you have."},
            {"About",
             "Why Linux, and not Windows or macOS?",
             "Because Linux is what the tools are built for. The servers your code will run "
                     + "on, the systems that test it, the containers it ships in, and the "
                     + "command-line tools behind every coding agent are all built and tested "
                     + "on Linux first; the agents\u2019 own CLIs ship for Linux and macOS, and "
                     + "reach Windows through a Linux layer of Microsoft\u2019s own. A "
                     + "developer\u2019s desktop is macOS or Linux for that reason, and on a "
                     + "phone only one of the two is possible.\n\nSo the Linux here is not a "
                     + "curiosity bolted on \u2014 it is the part that makes the editor and "
                     + "the agents able to do real work: build, run, test, commit, push."},
            {"Editor",
             "Does copying in the editor reach the phone\u2019s clipboard?",
             "Yes. The editor is drawn by the phone\u2019s own browser engine, which shares "
                     + "the phone\u2019s clipboard: long-press to select, Copy, and it is in "
                     + "Gboard or any other app. Paste works the other way round the same way. "
                     + "The terminal is the one place to be careful \u2014 in a shell, Ctrl+V "
                     + "is not paste; long-press and choose Paste instead."},
            {"Safety",
             "Does the app put anything in my phone\u2019s files?",
             "No. Linux, the editor, the extensions and your projects all live in the "
                     + "app\u2019s own private storage, which no other app can read and which "
                     + "Android removes entirely when the app is uninstalled. Nothing is "
                     + "written to Downloads, to Documents or anywhere else you would see in "
                     + "a file manager \u2014 unless you turn on The phone\u2019s files in "
                     + "Settings, and even then the app only reads and writes what you or an "
                     + "agent ask it to, inside ~/phone.\n\nThe other exception is one you "
                     + "take yourself: Test on this phone. While the phone is paired and "
                     + "connected, adb in the terminal has the shared storage a USB-debugging "
                     + "computer would have, which is why pairing is a step you take and why "
                     + "the row says so."},
            {"Safety",
             "Can someone read the app\u2019s own code?",
             "Yes, and that is on purpose: the source is published, and the build that "
                     + "reaches your phone is made from it in public by the repository\u2019s "
                     + "own workflow. There is nothing in the app worth hiding \u2014 no key, "
                     + "no account, no server \u2014 because the editor\u2019s password is "
                     + "generated on your phone and never leaves it, and the app has no "
                     + "account of its own. Security here does not depend on the code being "
                     + "secret. It depends on the code being right, which is what the "
                     + "forty-six checks that run on every build are for."},
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
                    + "Four times, and only these four:\n"
                    + "  • downloading Ubuntu packages from Canonical\n"
                    + "  • downloading the editor and extensions from Coder's releases and the "
                    + "Open VSX registry\n"
                    + "  • each agent talking to its own company, under that company's own "
                    + "privacy policy\n"
                    + "  • asking GitHub once a day whether a newer PocketIDE has been "
                    + "published — a request for a public list, with no account and nothing "
                    + "about you in it; Settings can turn it off\n\n"

                    + "Where your sign-ins are kept.\n"
                    + "Inside Linux, in each publisher's own extension storage. "
                    + "Never in this app's code and never in the installed package.\n\n"

                    + "The editor's password.\n"
                    + "Generated on this phone, kept on this phone, and used only to stop other "
                    + "apps on the same phone from reaching the editor from inside the "
                    + "phone.\n\n"

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
            {"android.permission.REQUEST_INSTALL_PACKAGES",
                    "Install an app you built",
                    "Only to hand an APK built inside Linux to Android\u2019s own installer "
                            + "when you tap Install an app built here in Settings. Android "
                            + "still asks you on its own screen every time, and refuses until "
                            + "you allow installs from this app there. The app cannot install "
                            + "anything by itself, and nothing outside ~/projects can be "
                            + "offered."},
            {"android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
             "Battery", "A one-tap prompt so long work is not stopped. Always optional."},
    };

    /** Named so the Help screen can say what is deliberately absent. */
    static final String NOT_REQUESTED =
            "Camera, microphone, location, contacts, SMS, calendar, phone and account access "
                    + "are not requested at all. Nothing in this app needs them, and an app "
                    + "that asks for what it does not need is not one to trust with a code "
                    + "repository. Storage is asked for only if you turn on The phone's files "
                    + "in Settings, and never at set-up.";

    // ------------------------------------------------------------------ where it fits

    static final String SURFACES_TITLE = "Where this fits";

    static final String SURFACES =
            "The industry calls these development interfaces, or product surfaces. There are "
                    + "four, and all three companies support all four:\n\n"
                    + "• Agent-first desktop workspace — hand over a project and supervise. "
                    + "Desktop only; a phone can watch it through a remote companion.\n\n"
                    + "• IDE integration — the agent inside the editor, with code, terminal and "
                    + "debugger. Normally desktop only.\n\n"
                    + "• Terminal agent (CLI) — the agent in the project folder's terminal. "
                    + "Normally desktop; a phone can reach it remotely.\n\n"
                    + "• Browser workspace — build and preview on a website. Works on a "
                    + "phone, but what it can do varies.\n\n"
                    + "Two supporting modes exist as well: a mobile remote companion, where the "
                    + "work still runs on a connected computer or in the cloud, and headless "
                    + "automation through an SDK or CI.\n\n"
                    + "PocketIDE's position: it makes the second one — IDE integration, ranked "
                    + "second overall for features and control — run on the phone itself, with "
                    + "no connected computer anywhere. That is the whole point of the app.";
}

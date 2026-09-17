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
                    + "Visual Studio Code (its open-source build, code-server), and whichever "
                    + "coding agent you want, all running on "
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
     * Forty-odd entries in seven groups, held contiguous by a gate. It was cut from thirty-two
     * to nineteen once, and the rule that did the cutting still applies: nothing here may
     * restate what the screen it sits on already says. What went then was the list of agents, which Home and Agents both show by name; the
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
             "A real Ubuntu Linux system and a real Visual Studio Code \u2014 its open-source "
                     + "build, Code - OSS, as code-server \u2014 running inside an "
                     + "Android app, with coding agents added as their publishers' own "
                     + "extensions. It all runs on the phone \u2014 nothing is streamed from a "
                     + "server, which is why a phone several years old can run it."},
            {"About",
             "What happens if I uninstall it?",
             "Everything goes: Linux, the editor, the extensions and your projects. They live "
                     + "in the app's own storage, so Android removes them with the app. Push "
                     + "anything you want to keep to git, or save it to the phone's own files "
                     + "first."},
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
            {"About",
             "How does this compare with the Linux terminal on Pixel phones?",
             "Android 15 gave recent Pixel phones a Linux Terminal: Debian running in a "
                     + "virtual machine, as a developer option. It is a good sign for this "
                     + "whole idea, and it is a different thing.\n\nIt needs a phone with "
                     + "hardware virtualisation, which is why it is Pixel-first; it is a "
                     + "terminal, not an editor; and it comes with no coding agents. This app "
                     + "runs Ubuntu without a virtual machine, which is why it runs on a 4 GB "
                     + "phone from 2021 on Android 10, and it puts a real Visual Studio Code "
                     + "(as code-server) and "
                     + "first-party agents on top. The honest trade: a virtual machine runs "
                     + "closer to native speed than the translation layer used here. What you "
                     + "get in return is that it works on the phone you have."},
            {"About",
             "How does this compare with Termux, VSCodroid, AndroidIDE and the cloud apps?",
             "Termux with proot-distro and code-server is the same recipe done by hand, and a "
                     + "good one \u2014 from GitHub or F-Droid, since Termux\u2019s own "
                     + "maintainers call its Play Store build experimental. This app is that "
                     + "recipe as one install: every piece pinned to a checksum, a job that "
                     + "survives the screen going off, and the agents set up in a tap. "
                     + "VSCodroid, open source and new in 2026, runs the editor\u2019s server "
                     + "natively with no PRoot, so it is lighter; it carries no Ubuntu, no apt "
                     + "and no Android build tools. AndroidIDE, archived in December 2024, and "
                     + "its successor Code on the Go build Android apps on the phone with no "
                     + "VS Code and no agents. Replit, GitHub Codespaces, Cursor\u2019s phone "
                     + "app and Cosyra do the work on a rented computer: nothing runs on the "
                     + "phone, and each is metered or a subscription.\n\nNone of them that "
                     + "could be found does all of this in one app: the editor, Android builds, "
                     + "the coding agents and testing on the phone itself, with no account and "
                     + "no fee of its own. And every one of them shares this app\u2019s hard "
                     + "parts, which their reviews name: the phone\u2019s keyboard, 4 GB as "
                     + "the floor, and Android\u2019s rules for what may run in the "
                     + "background."},
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
            {"Editor",
             "Is this the same Visual Studio Code as on a computer?",
             "The same editor, the same source. What runs here is code-server, which is "
                     + "Code - OSS \u2014 the open-source code Microsoft builds Visual Studio "
                     + "Code from \u2014 served to the app\u2019s own window. The files, the "
                     + "terminal, the search, the git view, the settings, the keyboard "
                     + "shortcuts, the extension model: the same. Three things differ, and "
                     + "they are the whole list.\n\nExtensions come from Open VSX, not from "
                     + "Microsoft\u2019s marketplace, whose terms allow only Microsoft\u2019s "
                     + "own builds to use it. Almost everything is on both. What is not on Open "
                     + "VSX is Microsoft\u2019s own closed extensions: Pylance (Pyright, its "
                     + "open core, is there), the C# Dev Kit, GitHub Copilot (the agents here "
                     + "do what it does), Remote SSH and Live Share; Pyright, clangd for C and "
                     + "C++, and Open Collaboration Tools in Live Share\u2019s place are all "
                     + "there. Second, a few desktop-only "
                     + "features do not exist in any web build: window zoom, native menus, "
                     + "the desktop debuggers that need a local process the browser cannot "
                     + "spawn. Third, this is an arm64 Linux with 4 GB: the same toolchain as "
                     + "a Linux laptop, and any program published only for x86-64 will not "
                     + "run.\n\nSo what you build and test here is what you would build and "
                     + "test on a Linux laptop, minus exactly those three lists. Of the "
                     + "four places the industry puts its agents \u2014 an agent-first "
                     + "desktop workspace, the IDE, the terminal, the web \u2014 this is the "
                     + "IDE, the second in that list, on a phone, with the same agents the "
                     + "desktop one has."},
            {"Editor",
             "How do I give the agent a file or a photo from the phone?",
             "In the editor\u2019s Explorer, long-press a folder and choose Upload, or use an "
                     + "agent\u2019s own attach button: the phone\u2019s file picker opens, "
                     + "you choose the file, and a copy lands in that folder under ~/projects "
                     + "where the agent can read it. That picker is Android\u2019s own: the "
                     + "app never gets access to your storage by it, only to the one file you "
                     + "chose, and nothing on the phone is changed. The other way round, a "
                     + "file the agent made can be downloaded from the editor to the "
                     + "phone\u2019s Downloads, or the whole shared storage can be switched on "
                     + "as ~/phone in Settings."},
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
            {"Editor",
             "Does copying in the editor reach the phone\u2019s clipboard?",
             "Yes. The editor is drawn by the phone\u2019s own browser engine, which shares "
                     + "the phone\u2019s clipboard: long-press to select, Copy, and it is in "
                     + "Gboard or any other app. Paste works the other way round the same way. "
                     + "The terminal is the one place to be careful \u2014 in a shell, Ctrl+V "
                     + "is not paste; long-press and choose Paste instead."},
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
             "Can I use an open model, a new model, or one running on the phone?",
             "Yes, through the one community row on the Agents screen: Kilo Code, an "
                     + "independent MIT-licensed agent you point at whichever model you like. "
                     + "It reads its model list from a registry as it runs, so a model "
                     + "released tomorrow is there without an update to anything, and it can "
                     + "talk to OpenRouter, Google AI Studio, Groq, Mistral, the model "
                     + "companies directly, or a model running here in Linux through Ollama or "
                     + "llama.cpp.\n\nThe honest part: none of it is unlimited. Free tiers "
                     + "exist and every one is capped \u2014 OpenRouter serves open models at "
                     + "about twenty requests a minute and fifty a day, Google\u2019s AI Studio "
                     + "and Groq have their own daily limits, and Kilo\u2019s own free setting "
                     + "routes to whoever will take the request, including providers that keep "
                     + "your prompts. All of them see what you send.\n\nA model running here "
                     + "sends nothing anywhere, and on a 4 GB phone it is not a real answer: "
                     + "what fits beside Android and the editor is about a billion parameters, "
                     + "enough to finish a line and not enough to plan an edit across files. "
                     + "On an 8 GB phone a three-billion model is worth trying. The app "
                     + "installs neither \u2014 both are one command away in the terminal.\n\n"
                     + "It is not official and the row says so. Install it if you want it, not "
                     + "because it is on the screen."},
            {"Agents",
             "Is any model free and unlimited? What about Kimi K3?",
             "Unlimited exists in one form only: an open-weight model running on hardware you "
                     + "own. No provider offers unlimited free use of a capable model, and the "
                     + "free tiers are all capped \u2014 OpenRouter\u2019s free models at "
                     + "twenty requests a minute and fifty a day, Groq\u2019s free plan at "
                     + "about a thousand a day, Google AI Studio\u2019s free tier with its "
                     + "own daily limits, Kilo\u2019s free setting routing to whoever will "
                     + "take the request, prompts possibly kept. What IS free and unlimited "
                     + "is compute: GitHub Actions runs a public repository\u2019s builds "
                     + "free with no minute limit.\n\nKimi K3 is real and open-weight, and "
                     + "it is already in Kilo Code\u2019s list through OpenRouter, where new "
                     + "models appear on their own \u2014 but it is paid there, and at about "
                     + "2.8 trillion parameters it is not something any phone runs. A model "
                     + "that runs here on a 4 GB phone is about a billion parameters; that "
                     + "one is unlimited, private, and modest."},
            {"Agents",
             "Browser automation, agent browsing, a cloud computer, \u201cBettergravity\u201d?",
             "Browser automation: Microsoft\u2019s own Playwright extension is on Open VSX "
                     + "(verified), and the Browser row in Settings installs the Chromium it "
                     + "drives; that is the tested path. Cline and Kilo Code each carry a "
                     + "browser tool of their own, which needs a Chrome they can reach; "
                     + "pointing them at the Chromium installed here is possible in their "
                     + "settings and has not been tested by this app.\n\nA cloud computer: "
                     + "there is no extension by that name. The nearest real ones on Open VSX "
                     + "are Daytona\u2019s (verified) and connectors for GitHub Codespaces; "
                     + "the official Codespaces extension is Marketplace-only. This app is "
                     + "the computer, on the phone, and a cloud machine is what you would "
                     + "reach from it over SSH or a git remote.\n\n\u201cBettergravity\u201d: "
                     + "nothing by that name exists on Open VSX. Searching turns up companion "
                     + "add-ons for Google\u2019s Antigravity \u2014 quota monitors and the "
                     + "like \u2014 none of which is a coding agent, and none of which this "
                     + "app lists."},
            {"Agents",
             "How do I sign in to an agent, and how does the sign-in get back here?",
             "In the agent\u2019s own panel, tap Sign in. The publisher\u2019s sign-in page "
                     + "opens in the phone\u2019s browser, never in this app: every address "
                     + "that is not the editor\u2019s own is handed to the browser, where the "
                     + "address bar is visible and a password manager works. Once you have "
                     + "signed in, the page usually returns to the editor by itself \u2014 "
                     + "through the phone\u2019s own localhost, which the Linux here shares "
                     + "\u2014 and the agent is signed in when you switch back. If the page "
                     + "shows a code instead, paste it where the agent asks: Claude Code says "
                     + "\u201cPaste code here if prompted\u201d, and Codex offers \u201cSign "
                     + "in with Device Code\u201d (in a terminal, codex login --device-auth). "
                     + "Antigravity signs in with a Google account the same way; Kilo Code "
                     + "takes an account with the model provider, or a key. Each extension "
                     + "keeps its own sign-in inside Linux; this app never sees it."},
            {"Agents",
             "I installed an agent. How do I open it?",
             "Open the editor, tap Menu on the bar along the bottom, and it is named there \u2014 "
                     + "\u201cOpen Antigravity\u201d, and the same for any other agent that "
                     + "brings a panel with it. Tapping the row on the Agents screen opens the "
                     + "editor, where Menu names it.\n\nInside the editor it also lives in the "
                     + "activity bar, "
                     + "which on a phone is the row of small icons along the bottom of the "
                     + "editor itself. The menu exists because that row is easy to miss."},
            {"Building",
             "What exactly can be built and tested here?",
             "Built and run on the phone: websites and web apps (any framework that runs on "
                     + "Node or Python), APIs and servers, command-line tools, scripts and "
                     + "bots, data work in Python, programs in Go, Rust, C and C++, databases "
                     + "such as SQLite and PostgreSQL from apt, Android apps in Java and "
                     + "Kotlin with the Android build tools row, and small games with Godot 4 "
                     + "or any web engine.\n\nTested how: a website with the Chromium the "
                     + "Browser row installs and Microsoft\u2019s Playwright extension, which "
                     + "the agent drives and screenshots; a server by calling it from the "
                     + "terminal; a library by its own tests; an Android app on this phone "
                     + "through phone install, launch, log, screenshot, record and its "
                     + "instrumented tests; a game by exporting it and installing it the same "
                     + "way.\n\nNot here, and Help says why in each case: an iPhone app, a "
                     + "Unity or Unreal game, an Android app with C or C++ in it \u2014 which "
                     + "includes Flutter and React Native \u2014 and anything that needs an "
                     + "Android emulator or an x86-64 program."},
            {"Building",
             "How does the agent see what it built? Screenshots, recordings, the browser.",
             "Every picture lands under ~/projects, where the agent reads it like any other "
                     + "file \u2014 Claude Code, Codex and Kilo Code all read images. For an "
                     + "app on this phone: phone screenshot com.example.app shot.png while "
                     + "it is on the screen, and phone record com.example.app flow.mp4 15 for "
                     + "a recording of up to sixty seconds "
                     + "that stops by itself the moment the app leaves the screen, so nothing "
                     + "else on the phone is ever filmed. For a website: Playwright\u2019s "
                     + "screenshot and video, in the Chromium here. For anything with a log: "
                     + "phone log, or the terminal. Nothing is sent anywhere by the app; what "
                     + "the agent then sends to its company is the agent\u2019s doing, under "
                     + "its terms."},
            {"Building",
             "What can I build today, with nothing extra installed?",
             "Websites and web apps, and programs in Python, Node, Go, Rust, C and C++. Git, a "
                     + "terminal and a compiler are all here from the first start."},
            {"Building",
             "Can it build an Android app?",
             "Java and Kotlin projects, yes \u2014 into a real, signed, installable APK, with "
                     + "the project\u2019s own ./gradlew, once Settings \u2192 The computer "
                     + "\u2192 Android build tools has been installed (about 530 MB, once). "
                     + "That installs a JDK, Google\u2019s own SDK, and aarch64 builds of the "
                     + "four build tools Google only ships for x86-64 \u2014 each checked "
                     + "against a checksum before it is used.\n\nApps containing C or C++ "
                     + "cannot be built: Google publishes no Android compiler for this kind "
                     + "of processor, which is Google\u2019s decision rather than a limit of "
                     + "your phone. That includes Flutter and React Native: both compile C++ "
                     + "through Google\u2019s NDK, which Google publishes for x86-64 Linux, "
                     + "Windows and Mac only, so neither builds here today."},
            {"Building",
             "Can I test an Android app here?",
             "Yes \u2014 on this phone, which is the test device, and from Android 11 the "
                     + "agent can drive it through a door with a short list on it. Settings "
                     + "\u2192 The computer \u2192 Test on this phone pairs the phone with "
                     + "itself over Wireless debugging: the app finds the port the phone "
                     + "advertises, you type the six-digit code into a notification, and the "
                     + "app\u2019s own adb does the rest. That adb ships inside the app "
                     + "(Ubuntu\u2019s arm64 build, assembled from packages pinned by checksum) "
                     + "and lives, with the pairing key and the adb server, in the app\u2019s "
                     + "own storage in a root of its own. The Linux the agent works in has no "
                     + "adb at all and cannot reach that one: nothing it can write is ever run "
                     + "with the key in reach. What remains is the phone\u2019s own line: "
                     + "everything this app runs is one Android user, and a program written to "
                     + "read another process\u2019s memory could read the adb server\u2019s while "
                     + "it runs \u2014 which is why Wireless debugging is a switch you turn off "
                     + "when you are not testing, and Android turns it off at every restart.\n\n"
                     + "What the terminal gets is one command, phone. "
                     + "It can install an APK built under ~/projects, open it, stop it, clear "
                     + "it, uninstall it, run its instrumented tests, read its own log, and "
                     + "\u2014 only while that app is on the screen \u2014 take a screenshot, "
                     + "tap, type or press a key. It cannot open a shell on the phone, touch "
                     + "another app, read the phone\u2019s files, list what is installed or "
                     + "read the phone\u2019s details; phone help is the whole list. Install an "
                     + "app built here hands an APK to Android\u2019s installer without any "
                     + "of it.\n\nTwo of those need no pairing and no Developer options at "
                     + "all: phone install and phone launch go through Android\u2019s own "
                     + "installer, which asks you to confirm each install on its own screen, "
                     + "and the app opens from there \u2014 while PocketIDE is on the screen; "
                     + "if it is not, Android will not let it open another app, and a "
                     + "notification with Open on it appears instead. So an agent can build, "
                     + "install and run what it wrote with your phone unpaired. Pairing makes "
                     + "those two silent and adds the rest \u2014 the log, the screenshot, "
                     + "the taps, the tests and uninstall.\n\nWithout the phone at all: JVM "
                     + "unit tests run in Linux "
                     + "directly, and so does Robolectric, with two limits on this processor "
                     + "\u2014 its graphics run in legacy mode, so views lay out and Espresso "
                     + "checks pass but nothing is drawn to pixels or screenshots, and its "
                     + "SQLite does not run, so tests that use a database go to the phone "
                     + "through the bridge. Its first run downloads about 200 MB of Android "
                     + "framework.\n\nWireless debugging lives in Developer options because "
                     + "Android has no narrower switch for it; the app keeps its access to "
                     + "the list above. Turn it off when you are done; Android turns it off at "
                     + "every restart anyway.\n\nAn emulator cannot run here, and cannot be "
                     + "downloaded or built into Linux either: Google publishes its emulator "
                     + "for Linux only on x86-64, plain QEMU cannot boot Google\u2019s Android "
                     + "images, and Cuttlefish, Waydroid, Anbox and redroid all need kernel "
                     + "features or root that Android gives no app. On a 4 GB phone a "
                     + "software-emulated Android would not fit beside the phone\u2019s own "
                     + "either. The phone itself, behind the door above, is the test device."},
            {"Building",
             "Can I build an iPhone app?",
             "Write one here, yes; build and install it, not here, and not through any trick. "
                     + "Apple\u2019s licence allows its SDK on Apple hardware only, so the "
                     + "Linux cross-compilers that exist (xtool, theos) need Xcode downloaded "
                     + "with an Apple ID and break that licence on a phone; and every "
                     + "sideloading tool \u2014 AltStore, Sideloadly, SideStore \u2014 needs a "
                     + "Mac or a Windows PC for the signing step and gives an app that lasts "
                     + "seven days without a paid account. None of this is a phone limit: "
                     + "Visual Studio Code on a Windows or Linux PC cannot build or install an "
                     + "iPhone app either. Only a Mac with Xcode can, which is why everyone "
                     + "else pushes to one.\n\nWhat works, and what an agent "
                     + "here can do end to end: write the app in Swift, React Native or "
                     + "Flutter, push it to a free cloud Mac \u2014 GitHub Actions is free "
                     + "and unlimited for a public repository, Codemagic gives 500 Mac minutes "
                     + "a month, Expo EAS 15 builds a month \u2014 and ship the result as a "
                     + "web app anyone can open, or through TestFlight and the App Store with "
                     + "an Apple developer account ($99 a year). The phone writes and pushes; "
                     + "the Mac in the cloud compiles."},
            {"Building",
             "Can I make games? Unity, Unreal, something like Free Fire?",
             "Godot 4, yes: it publishes a Linux arm64 build (about 77 MB) that exports an "
                     + "Android APK from the terminal with no window at all \u2014 godot "
                     + "--headless --export-debug Android game.apk \u2014 once its export "
                     + "templates (about 1.3 GB) are downloaded and the Android build tools "
                     + "row is installed. An agent can write the game in GDScript, export it "
                     + "and install it with phone install. It is not a row in Settings yet "
                     + "because it has not been proved on a 4 GB phone; the terminal is the "
                     + "way to try it. 2D and web games in any engine that runs on Linux "
                     + "without a graphics card work the same way.\n\nUnity and Unreal cannot "
                     + "run here at all, and nothing installable changes that: Unity publishes "
                     + "its Linux editor for x86-64 only (its release list has no Linux arm64 "
                     + "build), and Unreal supports Linux on x86-64 only. A headless Unity "
                     + "build still needs that editor. Nor are they editor features anywhere: "
                     + "on a Windows PC, Unity and Unreal are their own programs beside Visual "
                     + "Studio Code, which only edits their scripts. The nearest real thing is a cloud "
                     + "x86-64 machine \u2014 GitHub Actions again \u2014 running Unity in "
                     + "batch mode on code written here.\n\nA game the size of Free Fire is "
                     + "Unity, and years of work by a studio: dozens to hundreds of engineers "
                     + "and artists, a 3D art pipeline on GPU workstations, authoritative game "
                     + "servers, anti-cheat, a device farm. That is a hardware and headcount "
                     + "fact, not a limit of this app or of you. What is realistic here is "
                     + "what most studios started with: a small game, finished."},
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
                     + "current one \u2014 with 1.4 GB free to set up (about 4 GB if you add "
                     + "the browser and the Android build tools) and ideally 6 GB of "
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
             "Which battery settings can stop a long job?",
             "A set-up, a build or an agent run keeps going with the screen off: the app holds "
                     + "the processor awake while it works, and the notification\u2019s Stop "
                     + "button is the only thing meant to end it. Three things can still end "
                     + "it.\n\nSuper power saving (realme, OPPO), Ultra battery saver (Xiaomi) "
                     + "and Samsung\u2019s \u201cLimit apps and Home screen\u201d shut down every "
                     + "app not on their short list \u2014 turn those off while a job runs. "
                     + "Ordinary Power saving mode is fine once Keep working with the screen "
                     + "off is allowed in Settings; it only makes the job slower, and Activity "
                     + "says so when a job starts with it on.\n\nThe phone\u2019s own per-app "
                     + "switch must be on: Allow background activity on realme and OPPO, No "
                     + "restrictions on Xiaomi, Unrestricted on Samsung. Settings \u2192 "
                     + "Permissions shows the path in this phone\u2019s own menu words. On "
                     + "realme and Xiaomi, locking the app in the Recents screen (its card "
                     + "\u2192 Lock) also stops \u201cclear all\u201d from ending it.\n\nAndroid "
                     + "12 and later also keep a ceiling of 32 helper processes across every "
                     + "app and end the extras; an editor, a build and an agent together can "
                     + "reach it. Android 14 has a switch for it under Developer options, "
                     + "Disable child process restrictions; Android 12 and 13 have none.\n\nAuto-"
                     + "launch is not one of them: this app never starts itself. Data Saver "
                     + "and Adaptive Battery do not affect a running job. Plugging the phone "
                     + "in helps: phones relax all of this while charging."},
            {"Phone",
             "The app closes as soon as I open it.",
             "It will not do that twice more. After two openings that never reached a "
                     + "screen, the third opens a plain recovery screen instead, with three "
                     + "buttons: try again; copy the details, for a bug report; and reset the "
                     + "app\u2019s settings, which does not touch Linux, the editor, the "
                     + "extensions or your projects. Nothing in Linux is ever lost by the app "
                     + "closing.\n\nIf it still closes with nothing shown, the phone is "
                     + "ending the process before the app runs at all: check the Battery "
                     + "settings the entry below names, and that the APK came from the "
                     + "project\u2019s own GitHub Releases page."},
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
            {"Safety",
             "Does the app put anything in my phone\u2019s files?",
             "No. Linux, the editor, the extensions and your projects all live in the "
                     + "app\u2019s own private storage, which no other app can read and which "
                     + "Android removes entirely when the app is uninstalled. Nothing is "
                     + "written to Downloads, to Documents or anywhere else you would see in "
                     + "a file manager \u2014 unless you turn on The phone\u2019s files in "
                     + "Settings, and even then the app only reads and writes what you or an "
                     + "agent ask it to, inside ~/phone.\n\nTest on this phone is not an "
                     + "exception either: the terminal gets a phone command that can touch only "
                     + "the apps you built here, never the phone\u2019s files."},
            {"Safety",
             "Where is everything stored \u2014 my files, my chats with the agents?",
             "In one place: this app\u2019s own storage, /data/data/com.pocketide/files. "
                     + "Android encrypts it, keeps it from every other app, never backs it "
                     + "up (this app opts out of backup), and deletes it with the app. Inside "
                     + "it: files/linux is Ubuntu; ~/projects in there is your projects; "
                     + "/opt/code-server and ~/.local/share/code-server are the editor, its "
                     + "settings, the extensions and every extension\u2019s own storage, "
                     + "sign-ins included; files/phone is the app\u2019s own adb, the pairing "
                     + "key and the allow-list; files/last-crash.txt is the note the recovery "
                     + "screen can copy. "
                     + "Settings \u2192 Storage \u2192 What is stored where measures each of "
                     + "these.\n\nThe agents\u2019 chats are in two places, and that is the "
                     + "honest part. On the phone: Claude Code keeps its sessions under "
                     + "~/.claude/projects (thirty days by default, its cleanupPeriodDays "
                     + "setting) and its prompt history in ~/.claude/history.jsonl; Codex "
                     + "keeps rollout files under ~/.codex/sessions and its history in "
                     + "~/.codex/history.jsonl; Kilo Code keeps one folder per task in its "
                     + "extension storage under the editor\u2019s; Antigravity keeps whatever "
                     + "it keeps in its own extension storage, at a path Google does not "
                     + "document. And with the company: every prompt and reply goes to that "
                     + "agent\u2019s company as part of using its model and is held there "
                     + "under that company\u2019s terms \u2014 Anthropic keeps Claude Code "
                     + "data thirty days for a consumer account that has not opted into "
                     + "training and five years for one that has; OpenAI, Google and the "
                     + "providers behind Kilo Code have their own pages. This app sends "
                     + "nothing itself.\n\nSettings \u2192 Storage \u2192 Clear agent chats "
                     + "deletes the transcripts on the phone and nothing else; Remove "
                     + "everything, or uninstalling, deletes all of it. Nothing is ever "
                     + "written to Downloads, Documents or an SD card unless you turn on The "
                     + "phone\u2019s files and save something into ~/phone yourself."},
            {"Safety",
             "Can someone read the app\u2019s own code?",
             "Yes, and that is on purpose: the source is published, and the build that "
                     + "reaches your phone is made from it in public by the repository\u2019s "
                     + "own workflow. There is nothing in the app worth hiding \u2014 no key, "
                     + "no account, no server \u2014 because the editor\u2019s password is "
                     + "generated on your phone and never leaves it, and the app has no "
                     + "account of its own. Security here does not depend on the code being "
                     + "secret. It depends on the code being right, which is what the "
                     + "checks that run on every build \u2014 more than fifty of them \u2014 "
                     + "are for."},
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

                    + "9. Your data, and the agents' data.\n"
                    + "Everything this app holds is in its own storage on your phone, and Help "
                    + "names every folder. What an agent sends to its company — your prompts, "
                    + "its replies, the code it reads — is held by that company under its own "
                    + "terms, not this app's; deleting it there is between you and them. "
                    + "Settings → Storage deletes the copies on the phone.\n\n"

                    + "10. Testing on this phone.\n"
                    + "Pairing the phone with itself, and allowing installs from this app, "
                    + "are switches you turn on, with the phone's own confirmations. What an "
                    + "app you built here then does on your phone is that app's doing and "
                    + "yours; this app gives the agent no way to touch any other app.\n\n"

                    + "11. Changes.\n"
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
                    + "app on the phone, encrypts it, and deletes it with the app. Nothing is "
                    + "backed up, synced or copied anywhere. Help lists every folder, and "
                    + "Settings → Storage measures each.\n\n"

                    + "The agents' chats.\n"
                    + "Kept on the phone by each agent, inside Linux, in the folder that "
                    + "agent's own documentation names; Settings → Storage → Clear agent "
                    + "chats deletes them. They are not fetched back from your account with "
                    + "the company: the copy on the phone is the one the agent resumes from, "
                    + "which is why it is there. And sent to that agent's company as part of "
                    + "using its model, where they are held under that company's terms, which "
                    + "this app cannot change and does not add to.\n\n"

                    + "The phone as a test device.\n"
                    + "Pairing gives this app, and only this app, adb access to the phone; "
                    + "the key stays in this app's storage in a root of its own, and the "
                    + "Linux the agent works in gets one command with a short list on it. "
                    + "Turn Wireless debugging off when you are not testing.\n\n"

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

                    + "If the app cannot start.\n"
                    + "A short technical note is kept in the app's own storage for the recovery "
                    + "screen's Copy button, so a bug report can say what happened. It is "
                    + "deleted the next time a screen is drawn, and never sent anywhere.\n\n"

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
             "Keep working with the screen off",
             "A one-tap prompt so a long set-up, build or agent run is not stopped when the "
                     + "screen goes off. Always optional."},
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
                    + "four, and all three companies support all four. This app is the second "
                    + "of them, the IDE, on a phone, with the same agents as the first:\n\n"
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

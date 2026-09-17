# Release notes

## 2.3.0

The repository holds two apps and nothing else, and the source reads the way it should.

### Two apps, one repository

At the owner's request the repository now contains CloudSaver, at the root, and PocketIDE, under
`pocketide/`, and nothing else: `pocketlinux/` and `pocketdoors/` (PocketAgent) are gone with
their workflows, and the root `plan/` folder, which was PocketIDE's own, moved in here — the icon
generator to `branding/make_icon.py` beside the brand tokens it draws from, and the master plan to
`MASTER-PLAN.md`. Its rendered icons are not committed any more, since the shipped copies are the
ones under `app/res` and the generator remakes the rest. The root README names both apps.
CloudSaver's own files were not touched, except that its workflow no longer ignores two folders
that do not exist.

### Source that reads as one hand wrote it

Every inline fully-qualified name (`java.nio.file.Files.isSymbolicLink`, `android.system.Os`,
and fifty-six more) became an import, `android.os.Process` excepted because `java.lang.Process`
is used beside it; twelve unused imports, eleven methods nothing called, two icons nothing drew,
two strings nothing showed and one preference key nothing read are gone. Behaviour is unchanged:
every gate, every break-check and the minSdk compile pass on the result, and the release APK is
built from it.

## 2.2.5

Where everything is, a delete that stops at a link, and a gate at the oldest Android.

### Where everything is stored, said and measured

An owner asked where the chats and the files go. Help now answers by folder: Ubuntu, the
projects, the editor with every extension's own storage and sign-ins, the phone bridge, the
crash record — all inside the app's own storage, which Android encrypts, keeps from every other
app, never backs up and deletes with the app. And the agents' chats, honestly, in two places: on
the phone, in the folder each agent's own documentation or source names (Claude Code's
`~/.claude/projects`, thirty days by default; Codex's `~/.codex/sessions`; Kilo Code's tasks under
the editor's storage; Antigravity's at a path Google does not document), and with that agent's
company under its own terms — Anthropic's retention periods are quoted from its documentation.
Settings → Storage → What is stored where measures each area, and Clear agent chats deletes the
transcripts on the phone and nothing else: not sign-ins, not settings, not projects, and only
with Linux stopped. The privacy text and the terms say the same, and the terms gained the two
clauses the phone bridge needed.

### A delete that stops at a link

Remove everything walked the rootfs with File.isDirectory(), which answers for what a link
points at, so it followed any symbolic link it met. A link inside Linux can name anything the
app can reach on the phone — with The phone's files on, that includes the shared storage — and
an agent, a script or a mistake can leave one. Deletion now treats a link as a link, and
measuring does the same. This was found by reading, not by loss, and a gate holds it.

### A gate at the oldest Android

An app that closes the moment it opens is what a call to an API newer than the phone looks
like, and nothing here checked for that: the build compiles against Android 15 and minSdk is
Android 10. The new gate compiles every source against the Android 10 SDK and allows exactly the
newer symbols it is told about, each with the guard that protects it — `Build.VERSION.SDK_INT`
checks, a constant the compiler inlines, a call inside a catch of Throwable — and fails on any
other. Run today it found none unguarded, which rules that cause out for the owner's report;
run tomorrow it catches the next one before a phone does.

## 2.2.0

The phone's key leaves Linux, and the workspace gets a door with a list on it.

### Test on this phone, scoped

Wireless debugging hands whoever holds the paired key everything a computer on a USB cable
has: a shell as the shell user, every app's name, the shared storage, screenshots of whatever is
on the screen, taps into any app, install and uninstall of anything. In 2.1.5 that key sat in
the Linux rootfs beside the adb server's socket, so the terminal — and any agent in it — had
all of it. An owner asked for the opposite: the computer, the app and the agent should not get
the phone's details, and the agent should be able to test only the app it built.

So adb, the key and the server moved out — all three, and the first of them is the one that
matters. A first draft of this release moved the key into the app's storage and bound it into a
PRoot that still ran Ubuntu's adb out of the Linux rootfs, through `bash -lc`. A review of that
draft found the hole: a rootfs the agent can write to is a rootfs where `/usr/bin/adb`,
`/etc/profile.d/*.sh`, `~/.profile` and `/bin/bash` are all the agent's to replace, and any of
them, run with the key bound in, hands the key over. So adb is now the app's own. `build.sh`
assembles it at build time from Ubuntu 24.04's arm64 packages — adb and the eighteen libraries
it loads, each pinned by name and SHA-256 from Ubuntu's release pocket, which never changes —
into a 5 MB zip that ships inside the APK. The app unpacks it once per version into its private
storage and runs it there under a PRoot of its own (`Workspace.startPrivate`): one program by
absolute path, no shell, no profile, an environment set by the app, and exactly two binds —
the key directory and a staging directory. Nothing in that PRoot comes from the rootfs. The
Linux the agent works in has no adb at all; a shim at the path Gradle looks says to use
`phone` instead, and the profile line 2.1.5 wrote is removed. A key 2.1.5 left in the rootfs is
moved, not copied, at the first start.

What the terminal gets is the `phone` command, a client for the app's bridge (PhoneBroker): a
socket the app serves that does exactly these things — install an APK from under ~/projects,
open it, stop it, clear it, uninstall it, run its instrumented tests, read its own log by process
id, and, only while that app is the one on the screen, a screenshot, a tap, typed text or a key
— and each only to a package the bridge itself installed. No shell, no other app, no package
list, no device properties, no files, no forwarding; arguments reach adb as arguments, never as
a command line, and asking for anything else returns the list. The Help, the install prompt and
the notices say the same, and the by-hand pairing that would have given the terminal the whole
key is no longer described. Wireless debugging still lives in Developer options, because Android
has no narrower switch; the app's access is what is narrowed.

The same review found the smaller gaps around the door, and each is closed: the APK an agent
asks to install is copied into the app's storage first and read and installed from the copy, so
the file under ~/projects cannot change between being inspected and being installed; a package
is added to the allow-list only once it is actually on the phone; a screenshot or a tap is
decided and done in one command on the phone, so no other app can come to the front between the
check and the action; ~/projects and /root above it must be real directories, not links, before
a path under them is trusted; the bridge serves four requests at once and says "busy" past that
rather than growing a thread per request; and closing the door wakes the thread waiting on it
instead of leaving it holding the socket. Process.destroy() sends SIGTERM, which PRoot ignores,
so every PRoot the app ends is sent SIGQUIT first — including the adb server, and including a
`phone log` an agent closed, which used to keep running.

PRoot is not a security sandbox against a program that sets out to escape it, and the notes do
not claim that. What is claimed is narrower and checkable: the workspace has no path to the
phone except the door, and nothing the workspace can write is ever run with the key in reach.
The line that remains is the phone's own, and Help says so: everything this app runs is one
Android user, and a program written to read another process's memory could read the adb
server's while it runs. That is what the Wireless debugging switch is for, and why Android
turns it off at every restart.

A second review pass, on this round, found four more gaps in the door and each is closed: a
`phone log` left with Ctrl-C used to keep its worker, its PRoot and the phone's logcat until
the editor restarted, and four of those made the bridge answer only "busy" — the client now
keeps its socket open and the bridge watches it, ending the command the moment the client
leaves; every reply and the allow-list carried a host path Linux does not have, because the
rootfs prefix was compared uncanonicalised; the allow-list was a list of names, so an app of
the same name installed later by the owner from a store would have been handed to the bridge
— the signing certificate is recorded at install and checked whenever the installed package can
be seen; and an install nobody answered within three minutes was left open, so a late tap put
the app on the phone with nothing told and nothing allowed — it is abandoned instead, and the
message no longer points at a notification that had been cancelled.

### One community row: bring your own model

An owner asked whether there is one extension that keeps up with every new model, works with
open ones, can be pointed at a model running on the Linux here, and comes with an agent. There
is, and it is not any of the three official ones: Kilo Code, MIT-licensed, a verified Open VSX
namespace, with an aarch64 build of its own, a model list read from a registry at run time (so
a model released tomorrow is there without an update to anything), and support for OpenRouter,
Google AI Studio, Groq, Mistral, the model companies directly, and Ollama or llama.cpp for a
model running in Linux. It is one row under its own heading, "Bring your own model · community,
not official", and every place it appears says community where the three above say official —
the row, the install dialog, and Help. Agents.official() reads only the official list, and a
gate holds that.

The checked alternatives, so the choice is not a matter of taste: Roo Code was shut down in May
2026 and Continue's repository has been read-only since June, so neither belongs in a list meant
to last; Cline is alive and universal but ships no arm64-specific build and has an open
code-server crash report. Continue's Linux build ships an x86-64 ripgrep, which is exactly the
kind of thing that does not run here.

And the honest sentence, in Help and behind a link on the row: nothing is unlimited. OpenRouter
serves open models at about twenty requests a minute and fifty a day, Google's AI Studio and
Groq have their own daily caps, Kilo's own free setting routes to whoever will take the request
including providers that keep prompts, and all of them see what is sent. A model running here
sends nothing anywhere and, on a 4 GB phone, is about a billion parameters — enough to finish a
line, not to plan an edit across files. The app installs neither; both are one command away.

### An emulator, answered with evidence

The same owner asked whether an emulator could be downloaded or built inside Linux instead. The
answer, researched rather than assumed, is no, and Help now says why: Google's SDK repository
serves the emulator for Linux on x86-64 only (every aarch64 entry in its manifest is macOS);
plain QEMU cannot boot Google's Android images, which expect the emulator's own ranchu board and
goldfish devices; and Cuttlefish, Waydroid, Anbox and redroid each need KVM, binder kernel
modules or root. On a 4 GB phone a software-emulated Android would not fit beside the phone's
own either. Robolectric is the sandboxed alternative and its limits are stated too: on this
processor its native graphics do not load, so views lay out and Espresso checks pass but nothing
is drawn to pixels, and its SQLite does not run.

### No Developer options needed for two thirds of it

Wireless debugging lives behind Developer options because Android offers no narrower switch, and
an owner asked, reasonably, whether the agent could get anywhere without paying that. It can:
Android lets an app install another app and open it, with the permission the phone's permission
manager calls "Install unknown apps" and a confirmation tapped every time. So the bridge has two
modes. Paired, everything is automatic. Unpaired, `phone install` and `phone launch` still work
— the APK goes through a PackageInstaller session, Android asks on its own screen, and the
result comes back to the terminal instead of being left to guess. Only the log, the screenshot
and the taps need adb, and they say so.

The session is used rather than an intent for two reasons that matter here: it answers, with
SUCCESS or a named failure an agent can act on, and Android's own App info shows PocketIDE as
where the app came from. The first draft claimed a third — that being the installer of record
lets the app see what it installed — and the review read the platform's source and found it
false: Android 11 shows an app its installer, not an installer its apps. So the manifest now
declares the one query every launcher makes, apps with a home-screen activity, and the gate
that forbade any `<queries>` element forbids anything more than that one intent instead: no
QUERY_ALL_PACKAGES, no named packages, no other intent, and no code that lists packages. The
review also found that an activity started while no screen of this app is in front is dropped
by Android without a word, so `phone launch` unpaired now checks that first and, when nothing of
this app is on the screen, puts the same launch in a notification and says so, rather than
printing "Opened" over nothing.

### Permissions and power, honestly

An audit of every permission against the code found one with no use: VIBRATE, because the key
row's haptics go through View.performHapticFeedback, which needs no permission. It is gone,
from the manifest and from Help. requestLegacyExternalStorage is set, which is what lets ~/phone
work on Android 10 once The phone's files is on; Android 11 and later ignore it.

The rows in Settings say what is true. Auto-launch is not needed: this app never starts itself
— nothing at boot, nothing on a timer, every job is one the owner started — and the row now
says so, keeping only the note that on Xiaomi and vivo the same switch feeds the phone's
cleaner. Background activity is the switch that matters on a realme, Xiaomi, vivo or Samsung
phone, and is said to be. "Battery" is "Keep working with the screen off", which is what the
exemption does, and no longer shares a name with Home's battery row. The Data Saver row is gone:
a foreground job counts as foreground for network policy, and the daily update already waits
for Wi-Fi. "All app permissions" is "App info". The Notifications row no longer says Allowed
on Android 10 to 12 when the owner has turned them off; it reads the app's own switch, and
tapping it opens the page. A refusal to start Linux from the background says to open the app,
not to flip a switch that does not govern it. A low-memory exit no longer claims the battery
exemption helps, because it does not.

A new Help entry says which battery settings can stop a long job: Super power saving, Ultra
battery saver and Samsung's app limit end every app not on their list; ordinary power saving
only slows a job and Activity says so when one starts with it on; the per-app switch and the
Recents lock; Data Saver and Adaptive Battery do not matter; charging helps.

The daily Ubuntu and editor update runs under the foreground service now — its wake lock, its
thermal pause, its notification — instead of on a bare thread from the Home screen, where an
owner who put the phone down mid-apt left dpkg to be frozen or killed with nothing to say so.
Two things the review found in that move are fixed with it: an editor asked for while the update
ran was dropped without a word, and now waits for it ("Finishing the update first; the editor
opens right after") and opens the moment it is done; and Stop during the update did nothing to
apt, because the update's PRoot was nobody's handle in the service — the sweep that ends the
workspace now runs for any job, handle or not.

### The app that closes as it opens

An owner reports that the app still closes the moment it opens, again and again, with nothing
shown — after 2.1.5 added the recovery screen that was meant to catch exactly that. The recovery
screen could not catch two things: a failure before the launch count was taken, and a failure in
the steps that run after the screen is built but before its first frame, which the recovery
screen itself goes through. Both are closed. The count is now the first thing the process does,
in App.onCreate, before the crash handler, before the notification channel, before anything that
could fail, and every step after it in App.onCreate, onStart and onResume is caught and recorded
rather than allowed to end the process. The recovery screen now also says how far the last
opening got (application, home, built, drawn) and shows Android's own record of why the last
three processes ended — the reason by name, CRASH, ANR, LOW_MEMORY, or OTHER with the system's
description, which on a phone whose maker kills apps of its own accord is the one line that says
so. That record is the thing to copy and send when it happens again, and Help says so.

### What can and cannot be built, with evidence

Asked for iPhone apps, Unity, Unreal and "a game like Free Fire", the Help now answers each
from evidence rather than hope. Apple's licence allows its SDK on Apple hardware only, and every
sideloading tool needs a Mac or a PC for its signing step; what works is a free cloud Mac —
GitHub Actions on a public repository, Codemagic's 500 minutes, Expo's 15 builds a month — with
the code written here. Unity's release list has no Linux arm64 editor and Unreal supports Linux
on x86-64 only, so neither runs here and nothing installable changes that. Godot 4 does: its
arm64 Linux build exports an Android APK headlessly from the terminal, and the Help gives the
command — not yet a row in Settings, because it has not been proved on a 4 GB phone. And the
honest sentence about scale: a game like Free Fire is a studio's years on those engines with
servers and a team, a hardware and headcount fact rather than a limit of this app.

Two more entries answer what an owner asked by name: "unlimited" exists only as an open-weight
model on hardware you own (Kimi K3 is real, open-weight, already in Kilo's list through
OpenRouter, paid there, and at 2.8 trillion parameters not something any phone runs); and there
is no "Bettergravity" extension on Open VSX, no "cloud computer" extension, and browser
automation is Microsoft's own Playwright extension with the Chromium the Browser row installs.

### Gates

Every claim above is held: no VIBRATE, the legacy-storage flag, the Notifications row reading
the app's own switch, the update started as a service job and never on a bare thread, the
power-saving note at a job's start, no Data Saver row and an Auto-launch row that says "Not
needed". And for the bridge: the editor's PRoot is started with the bridge bind and no adb; the
key directory is bound from the app's storage for the app's commands only; the bridge's list has
no shell, pull, push, forward, reverse, root or tcpip; every operation but install goes through
the package allow-list; a screenshot or an input first checks the package is the one on the
screen; install takes only an APK under ~/projects that Android can read; the phone command
talks to the bridge's socket and nowhere else; and the Help no longer hands out the by-hand
pairing. And for this round: adb never runs from the rootfs (startPrivate runs one program by
absolute path with no shell, and nothing in PhoneBroker or Phone starts a rootfs PRoot); every
package in adb's root is pinned by a 64-digit SHA-256 and the zip is assembled from that list
alone; the allow-list grows only after a successful install; the APK is staged before it is
read; the screen check and the action are one command; the projects directory is checked
without following links; the bridge's executor is bounded; the door's close wakes its acceptor;
SIGQUIT precedes destroy; the manifest's one query is exactly the launcher intent and no code
lists packages; an unpaired launch checks the app is in front; a start during the update waits
rather than being dropped, under a lock so no start is lost between; a job with no handle is
still swept; the launch count is the first thing App.onCreate does and each step after it is
caught; the recovery screen reads Android's exit record; a client that leaves is noticed; guest
paths are guest paths; the allow-list checks the certificate; and an unanswered install is
abandoned. Each broken on purpose and confirmed to fail.

## 2.1.5

What three reviews of 2.1.0 found, within the hour of its publication.

### Text size that works

The editor's zoom was written as window.zoomLevel, and three menu rows pressed the editor's
zoom commands. Neither exists in the web build of the editor: zoomLevel is an Electron setting,
and code-server's workbench answered the commands with "command not found". So the whole
text-size feature — the zoom worked out from the phone's width and text-size setting, the
Editor size picker, Smaller and Larger in the menu — changed nothing on screen. It is the
page's viewport now, which is where a browser keeps its zoom: the same arithmetic (a layout
width of widthDp / 1.2^z at a scale of 1.2^z) applied by the app to the editor's page, at
once, with no restart, re-applied when the phone is turned so the editor always fills the
screen, and with pinch still free above it. The zoom is worked out from the upright width, so
an editor opened sideways no longer overflows the moment the phone comes back up.

### The editor's own settings, kept

settings.json was written from scratch at every start, and keybindings.json with it, so a
colour theme chosen in the editor, a font, an agent's stored settings and any shortcut the
owner added were gone by the next opening. Both are merged now: the app sets its own keys and
carries everything else over.

### The menu and the keys agree

F5 to F7 were bound once, when the editor started, while the menu re-read the installed
agents whenever the editor screen came back — so an agent installed since the start could open
the wrong panel, or start the debugger. The bindings and the menu now come from one read, made
whenever the editor screen comes to the front and again after every install or removal; the
editor reloads the file when it changes.

### Marks, honestly

Three publisher logos shipped in the app were never shown anywhere, two of them were
look-alikes rather than the publishers' own icons, and the notices said they were displayed.
They are gone, with Microsoft's Visual Studio Code icon, which is the mark of a build this is
not: the set-up screen shows the app's own glyph beside "code-server · the open-source Visual
Studio Code (Code - OSS)", and the notices say exactly what is and is not shown. The editor
itself shows its own mark, Coder's, at its top-left, as it should. Search never says "official"
without the registry's own "verified". Sizes on the agent rows say "about", are current, and
the comment that promised a size gate promises only the gate that exists.

### Dialogs, splash, shortcut, cursor

Dialogs are built in the app's own light or dark, with Theme.Material rather than the maker's
DeviceDefault, so a dark app on a light phone no longer gets a light frame and invisible button
ripples, and a realme skin no longer restyles the buttons. The platform's accent — the text
cursor, the selection handles, the highlight — is the brand's, not the wallpaper's. The splash
mark's fade animated a property a vector group does not have and never played; it fades on the
root now. Android 12 and later show their own splash and are not shown a second one. The
long-press shortcut's icon was a white glyph the launcher wrapped in a white disc; it is an
adaptive icon on the brand tile. The trackpad's hint follows the phone's text-size setting.

### Smaller

Help says 1.4 GB free to set up, which is what Home checks, and about 4 GB with the browser and
the Android build tools; the Agents row is described as opening the editor, where Menu names
the agent. The tools script reads an ELF's own processor field instead of trusting an exit code
PRoot does not give. A pairing resolve that never answers is given up on after three seconds so
the next in line is tried, and two quick taps cannot start two adb servers.

### Test on this phone, corrected

Finding a port could miss this phone's own advertisement. NsdManager resolves one service at a
time and never repeats an announcement, so on a Wi-Fi where a laptop advertises adb too, the
laptop resolved first, was rejected — correctly — and this phone's own service, heard during
that resolve, was dropped for good. Everything heard is queued now and resolved in turn until
one is this phone's. A successful connect records the phone as paired, so a phone paired by hand
or before a reinstall no longer says "tap to pair" while `adb devices` lists it. The by-hand
route is described as it is: the pairing code disappears when Settings leaves the screen, so
pairing by hand needs Settings and the editor side by side in split-screen, while connecting
alone does not. The steps name the Wireless debugging page (tap its name, not only its switch)
and Android's one-time "allow on this network" question. With notifications denied, Pair for
the first time offers to allow them rather than quoting instructions that could not work as
quoted; Connect now says where its result will appear; Developer options first opens About
phone instead of ending at OK.

### The server that was not there

The editor's start runs adb's server, but only when adb existed at that moment — and the
documented first-run order is open the editor, install adb, pair. In that order there was no
server; the adb the pairing ran forked one inside its own PRoot, where --kill-on-exit took it
down the moment the command ended, so the phone said "connected" and the terminal's adb saw
nothing until the editor was reopened. The service now holds a server of its own whenever none
answers on loopback (`adb server nodaemon`, alive as long as the service is) and pair and connect
refuse, saying so, if even that cannot be started. Retryable failures — not six digits, the
pairing box had closed, a mistyped code — put the reply box back with the reason on it rather
than replacing it with a notification that said "type here". And "notifications allowed" now
means all three switches: the permission, the app's notifications as a whole, and the Linux
channel; any of them off is caught before the owner is sent to Settings to read a code.

### On no network port

adb's server listens on TCP 5037 by default, and on a phone loopback is shared by every app: the
wire protocol has no authentication, and it is the server that holds the paired key, so while
the phone was connected any app with the INTERNET permission could have installed, read and
tapped through it. The server answers on a socket inside the app's own storage now
(ADB_SERVER_SOCKET, set for every PRoot the app starts and for every login shell), which nothing
outside the app's sandbox can open. Gradle's own installDebug and connectedAndroidTest speak only
to the port and fail closed; assembleDebugAndroidTest plus adb shell am instrument does the same
job, and every text that promised connectedAndroidTest now says so.

### The toolchain, kept whole

check and install only accepted "some line" naming an aapt2; they accept the exact line naming
the one they verified now, a line this script wrote for an earlier version is replaced, and one
the owner wrote pointing elsewhere is left and said. The Android Gradle Plugin installs its own
build-tools version when a project names none, x86-64 and all: every build-tools directory is
now walked at install and at every check, and any of the four tools that will not run is
replaced by a copy of the verified aarch64 build, so a directory AGP added overnight is repaired
before the next build. The closing message says to name buildToolsVersion. The adb link is
re-made at every check for the same reason. And the safety gates read the scripts with their
comments stripped, match every spelling of apt install, check the newline guard's condition and
require the adb server to be started before the editor is launched, because a review showed
each could be satisfied by a comment or by the right words in the wrong place.

### Numbers that disagreed

Help and the capacity screen said the Android toolchain was 520 MB while the row and its prompt
said 530: it is 530 everywhere, and the row asks for about 330 when the JDK is already there.
Two sentences claimed the row says what pairing gives; the prompt that installs it does, and the
sentences say that now.

### Gates

47, with the audit's checks inside them: the zoom is applied as the page's viewport with a wide
viewport and nothing writes or binds the desktop-only zoom; the keys and the menu come from one
read, after every install and removal, and the owner's own bindings and settings survive a
start; dialogs in the app's theme, the brand accent in both themes, the splash fade on the root,
one splash on Android 12, an adaptive shortcut icon, no product mark shipped, "official" only
with "verified"; found services queued, a connect records the pairing, a server answering
before adb runs, the reply box re-posted on a retryable failure, all three notification
switches, a hung resolve given up on, one adb server at a time, and the ELF's own word on its
processor. Every one broken on purpose and confirmed to fail.

## 2.1.0

An Android build finishes on the phone, and the phone installs it.

### The four files

Google's SDK installs on arm64 without trouble: sdkmanager, d8, r8 and apksigner are Java. Four
of the build tools are not — aapt2, aidl, zipalign and split-select ship as x86-64 binaries
only — and a Gradle build stopped on the first of them with an Exec format error, which is what
the app used to say plainly and leave the owner to solve. The Android layer finishes the job
now. It installs a JDK, Google's command-line tools (checked against the digest Google publishes
beside them), platform 35 and build-tools 35.0.1 through sdkmanager, and then replaces those four
binaries with the Commit451 android-arm-build-tools project's aarch64 builds of the same AOSP
source, MIT-licensed, each checked against a SHA-256 taken by downloading that exact file and
hashing it — the same standard the editor's tarball is held to. Then the one line without which
none of it is used: the Android Gradle Plugin fetches its own x86-64 aapt2 from Maven unless
`android.aapt2FromMavenOverride` names a path, so that line is written where AGP reads it.
`aapt2 version` is run at the end, because "installed" and "runs on this phone" are different
claims.

### Install an app built here

The phone is the test device, and there was no way to hand it the APK. Settings → The computer
→ Install an app built here lists the APKs under ~/projects, newest first, and hands the chosen
one to Android's own installer through a content provider that is not exported, serves only
`.apk` files, only from under ~/projects, only read-only, and only by a URI this app hands out
with a one-time read grant. Android still asks on its own screen every time. One new permission,
listed in Help with what it is for.

### Test on this phone

The emulator cannot run on a phone, so the phone is the test device — and from Android 11 the
agent can drive it. Settings → The computer → Test on this phone installs adb (Ubuntu's own
arm64 package, about 2 MB, nothing to pin) and pairs the phone with itself over Wireless
debugging, the way Shizuku does from an ordinary app: the app finds the pairing port the phone
advertises to itself (NsdManager, and only an advertisement that resolves to one of this phone's
own addresses counts), the owner types the six-digit code into a notification's reply box so
that Settings never has to leave the screen, and `adb pair 127.0.0.1:PORT CODE` runs inside
Linux. From then on the editor's start connects by itself whenever Wireless debugging is on, the
adb server lives exactly as long as the editor does, and `adb devices` in the terminal lists the
phone. An agent can `adb install` what it built, launch it, read logcat, screenshot it, tap it
and run `./gradlew connectedAndroidTest`, on real hardware, for nothing. Google's platform-tools
carry an x86-64 adb; when the SDK is installed that copy is set aside and Ubuntu's linked at the
one path the Android Gradle Plugin looks. The FAQ, the How-this-works dialog and the install
prompt all say what pairing gives — what a computer with USB debugging has — and that Android
turns it off at every restart. adb is pointed at 127.0.0.1 and nowhere else, the code is checked to be six digits
before it reaches a command line, and the receiver the code arrives through is not exported.

### Five things a review found

Set-up and the nightly update delete apt's package lists to save 60 MB, and the Android and
Playwright layers ran `apt-get install` without refreshing them, which fails on every fresh
workspace with "Unable to locate package": every install in the tools script now refreshes the
list first, and a gate holds it there. The aapt2 line is appended to an owner's existing
gradle.properties on a line of its own even when their file does not end with a newline. `check`
reports the SDK as installed only when Gradle is also pointed at the aapt2 it checked. The
"leaving them alone" message no longer contradicts the one line that is added. And Settings
redraws when the SDK or adb state changes, not only when the JDK does.

### Help

The build and test answers say what is true now: a Java or Kotlin project builds with its own
./gradlew after one 530 MB install; the phone can be paired with itself and driven; JVM and
Robolectric tests run here directly; the emulator and C/C++ remain impossible, and why.

### Gates

47, with the new checks inside them, each broken on purpose and confirmed to fail.

## 2.0.0

The closing release of this update series, and what was still open from a long list an owner
wrote for the app before this one.

### The phone's own health

If the phone reaches the level Android calls critical, the workspace is paused rather than left
to be killed: every process in Linux is held where it stands with SIGSTOP, the notification and
the Activity screen say so, and SIGCONT picks up exactly where it left off once the phone has
cooled to moderate. Nothing is killed and nothing is lost — which is the whole difference from
what a phone does on its own, where the most expensive thing running is the first thing ended,
mid-download or mid-build. Held at critical and released only at moderate, so it does not flap
at the boundary.

### A shortcut to the editor

Long-press the app's icon and there is one shortcut: Open the editor. It goes through Home so
the app lock is raised first exactly as on any other opening, and a phone with nothing set up
lands on Set up rather than on an editor that cannot start.

### Help, for the questions that kept being asked

What updates itself and what waits for you, as one plain list. How this compares with the Linux
Terminal on Pixel phones, honestly in both directions. Why Linux and not Windows or macOS.
Whether copying in the editor reaches the phone's clipboard. Whether the app puts anything in
the phone's files (it does not). Whether someone can read the app's code (yes, and why that is
the right answer). Which phones can run it now names the version range and the 4 GB phone the
app is sized for, and says plainly that it is Android-only and why.

### Gates

46, with four new checks inside them, each broken on purpose and confirmed to fail.

## 1.9.5

Two faults found by re-reading 1.9.0's own diff before shipping it.

The recovery screen — the one that opens when the app has failed to start twice, and therefore
the one screen that has to work when nothing else does — asked for its insets with no bottom bar
to apply them to. It was the only screen in the app doing that, and the result was no
gesture-bar padding at all, so its Reset button could sit underneath the gesture handle on a
phone using gesture navigation. It takes both insets now.

And the editor menu's list of key codes was called `keys`, which is already the name of the key
row on the same class. Nothing behaved wrongly, but a later edit reading the wrong one would
have compiled.

## 1.9.0

The release that answers a screenshot, a crash report, and the question "how do I open it".

### The app closed itself the moment it opened

A WebView draws its page in a separate process, and Android's rule is blunt: if that process is
killed and the app has not said it can cope, Android kills the app too — no dialog, no report,
nothing in a log an owner could find. The most expensive thing on a phone running this app is
Visual Studio Code with an extension host and three agent panels in it, so the editor's renderer
is exactly what gets reclaimed first, and reopening the app went straight back to the editor.
That is the whole of "app open karte hi apne aap close ho raha".

It is handled now. The dead window is taken down and the screen offers to open the editor again,
which costs the session and nothing else.

Two more layers sit under that, because a sideloaded app has no crash console and the one process
that could explain a death is the one that died. The app counts its own openings, writes the
count synchronously, and clears it only once a frame has actually reached the screen; after two
openings that never got that far, the third opens a recovery screen instead of trying again. And
anything thrown while the first screen is being built is caught and shown on that same screen —
so even the first failure is something an owner can read and copy, with a Reset that puts the
app's settings back to new without touching Linux, the editor, the extensions or the projects.

### An extension installed in the editor was invisible to the app

Install Antigravity from inside the editor — the ordinary way, the way the editor's own
Extensions panel offers — and every screen in the app went on saying it was not installed, while
Claude Code and Codex, which the app itself had installed, showed correctly.

Two faults, either one enough on its own. The app kept its own list in a preference and never
asked the editor, so a list only it ever wrote was wrong the moment anyone installed anything any
other way. And identifiers were compared with equals(): the registry publishes
`Google.google-antigravity` and the editor records it lower-cased, so the same extension never
matched itself.

The app reads the editor's own record now — off the phone's disk, with no Linux running — and
compares identifiers the way both sides mean them.

### "How do I open it?"

The editor's four buttons were Commands, Keys, Cursor, Back, and Commands did nothing. It is a
Menu now, and the menu names the agents: **Open Antigravity**, and the same for anything else
that brings a panel. It also holds the command palette, Files, Terminal, Extensions, the side
panel, the text size, and full screen.

Underneath, the app writes the editor's own `keybindings.json` and presses those keys as DOM
events rather than through Android's key translation — which is the path that swallowed
Ctrl+Shift+P and produced the original "Commands does not work". Three of the bindings are worked
out from whichever agents are actually installed.

### Held sideways

Landscape needed three separate things and had none of them. The window now uses the screen
under a camera notch instead of leaving a black band down one side; there is a rotation setting
with Follow the phone, Portrait and Landscape, because a phone with auto-rotate switched off
otherwise never offers the editor the extra width at all; and the editor's own state — the key
row, a held modifier, the trackpad — survives the turn instead of being thrown away with the
chrome.

### The workbench ran off the side of the screen

The owner's text scale was added to the worked-out zoom with nothing holding the result, so a
360 dp phone set to 1.3× text reached the cap and left the editor 228 effective pixels — well
under the 300 the sizing is built around, and below that the workbench stops wrapping and starts
overflowing. There is a floor now, applied after the font scale rather than before it, and the
rounding errs towards more room instead of less. A gate computes the result across nine widths
and seven text scales and fails the build if any of them falls under it.

### A modifier is a key, not a flag

A real Ctrl+C is four events — Ctrl down, C down, C up, Ctrl up. The app sent one event carrying
a meta bit, which is a plain C. The Ctrl key on the key row lit up and did nothing, and had done
since it was written. Modifiers are pressed and released as real keys now, they latch together so
Ctrl+Shift+P is reachable, and the row gains Alt, Shift, Enter, Backspace, Delete and the six
characters a shell needs most that a phone keyboard buries.

### Smaller things

Stopping Linux sweeps the workspace's own processes rather than killing PRoot and leaving
whatever it was tracing reparented and still running. A failed start offers Open the editor again
instead of a dialog with only a Copy button. The editor keeps the screen awake while an agent
works. An agent panel's attach-a-file button opens a real file picker, and a download offered
inside the editor reaches the phone instead of being dropped. Android refusing a foreground
service is reported rather than crashing the app. Search results say *official* where the
publisher is the company whose model the extension talks to, which is a stronger claim than
verified and was not being made separately. Six new entries in Help: what this app is, why these
three agents and how to add others, how to open an agent, landscape, overflow, and what to do if
the app closes on opening.

### Gates

46, with twenty-three new checks inside them, each broken on purpose and confirmed to fail.

## 1.8.0

The interface an owner actually asked for, and the last layer that did not update itself.

### The bar floats, and the page runs under it

The bottom bar is a glass capsule laid over the page now, not a strip the page stops at. It is
inset from both edges, lifted off the gesture bar, fully rounded, faintly see-through, lit along
its top edge and shadowed underneath — the shape Telegram's 2026 redesign and Google's own apps
settled on — and every page is padded by the bar's measured height so its last row can always be
scrolled out from under it. The gate that checks the bar's numbers also checks, now, that the
page passes beneath it: a capsule the page stops at is a slab with round corners.

### "The code-looking thing in the top right corner"

That was the button that opens the editor. As a bare glyph it read as decoration; as a glyph in
a tonal circle it still did. It is a Material 3 tonal button with a word now — **Editor** — and
the word is measured against its container in both themes like everything else on the bar.

### Commands did not work

The button under the editor sent Ctrl+Shift+P, and on the owner's phone that arrived as nothing:
two modifier bits and a shifted letter through the WebView's key translation. It sends F1 now,
which Visual Studio Code binds to the same command and which is one unmodified key.

### Pinch to zoom, and text the right size once

The editor refused pinch zoom, and the workbench's own viewport forbids it even when the WebView
allows it, so the app loosens that rule after the page loads. Separately, the WebView was
applying the phone's font scale on top of the zoom the app had already worked out from it, so
text was scaled twice; it is applied once now. The Cursor button carries a pointer icon rather
than a hand, and the toolbar holds 64 dp as a minimum instead of a fixed height.

### The set-up transcript scrolls

"What it is doing" was a scrolling box inside a scrolling page, and a plain ScrollView there
never moves: the page takes every drag first. It claims the drag for itself while it has
somewhere to scroll to.

### The battery switches, in this phone's own words

No app can read a maker's auto-launch or background switch, and the rows said nothing useful
about that. They now say so plainly and print the path through this phone's menus — on a realme,
Settings › Battery › App battery management › PocketIDE — in the words that phone uses. Three OEM
pages that were missing are added, the newer oplus page is tried before the older ColorOS one,
and a tap on Battery when the app is already exempt opens the maker's page instead of a dialog
Android closes on its own.

### Everything updates itself now, including the app

The editor follows code-server's releases automatically — on Wi-Fi, once a day, only while it is
closed, and only through the staged, verified, reversible swap — with a switch to make it manual.
And the app itself asks GitHub once a day whether a newer PocketIDE has been published, says so
on Home and in Settings, and hands the download to the phone's browser; every release is signed
with the same key, so it installs over the last and touches nothing in Linux. Releases are
published by the workflow under `pocketide-v<version>`, only from a build signed with the
repository's own key, and a gate holds the app's tag prefix and the workflow's to one string.

### Smaller things

The editor's Node heap follows the phone's memory instead of a 4 GB constant. Anything shown or
copied from a "details" dialog has credentials blanked first — bearer tokens, key=value secrets,
provider-prefixed keys, URL query strings — because that text is what gets pasted into a chat
asking for help. Two lines in "Where this fits" had been damaged by an earlier rename ("desktop
Linux", "Browser Linux") and read correctly again. The FAQ gains how to work the editor with a
thumb, where the agents actually run, and why a set-up stops when the screen goes off, and the
privacy text admits the fourth network use. The lock-turned-itself-off notice has a button to
the phone's security page. The Home footer that repeated the version is gone.

### Found in review, fixed before release

A second reading of the whole app, done against the change above, turned up faults that had
been there for versions. Back never worked on Android 13 or later: the manifest opts into
predictive back, and an app that has opted in never has its onBackPressed() called, so Back
closed the app from Settings on a new phone and went Home on an old one; both screens register
through the new Back class now. The keyboard is treated as an inset, so on Android 15 the search
box on Agents and the editor's toolbar no longer sit under it. The three recommended agents
opened as an empty card until something was installed, because nothing filled the list on
build. A live dialog's Done button was added after the dialog was showing, where Android never
lays it out, so a finished install could not be closed; the button exists from the start and is
revealed at the end, and installing an extension uses that dialog instead of a bare platform
box. The Activity screen read every process's status file on the drawing thread every two
seconds, and rebuilt every row each time; it reads on its own thread and updates rows in place.
The Linux size was walked — tens of thousands of files — on every five-second refresh of Home;
it is measured once a minute at most. Remove everything deleted a whole Linux on the drawing
thread; it runs behind a live dialog. Changing the theme replayed the opening splash, because it
recreated the activity; it repaints in place. Coming back to the app rebuilt every pane,
emptying the Agents search box; panes that refresh themselves are only told they are back. The
notification question before set-up offered "Cancel", which left set-up unstarted on a screen
that said Starting; it offers "Not now", and set-up starts either way. The wake lock had a
four-hour timeout, under which the CPU slept beneath a running build. The Task-Manager and
low-memory notices claimed Linux had no chance to shut down even when nothing was running; the
service now records whether it was. An errand to the phone's Settings was trusted for ever once
it had begun; it is re-checked on return, and two minutes is the limit. The set-up screen lost
its transcript, its progress and its stage marks on rotation; it replays them. The Android 12+
splash style silently dropped the bar colours the base style sets. Dialog buttons and the "why"
link use a deeper violet on the light theme, because the accent itself did not reach 4.5:1 as
words on the card, and the contrast gate measures it. Every plain-text button tells a screen
reader it is a button. The Stop action from the notification calls startForeground before it
stops, which Android requires of a service started that way.

### Gates

46, with twenty-eight new checks inside them, each verified against the code as it shipped.

## 1.7.0

The second half of the audit, and it found the thing I had fixed once and left broken
everywhere else.

### Only one screen out of four handled the system bars

An owner reported the clock drawn over the app's title and the gesture bar over the row of
destinations. That was fixed — on the main screen. `Theme.fitContent()` was written for the
editor and had **zero call sites**, so the editor's toolbar still sat under the gesture handle,
exactly where Commands, Keys, Trackpad and Home have their labels, and Set up and About still
drew their back bars under the clock. `targetSdk 35` means Android 15 draws every window edge to
edge whether it asks to or not, so a screen that does not handle insets does not get a choice.

All four handle it now, and a gate names each screen and the call it has to reach.

### Nothing in the app responded to being pressed

`Ui.tappable()` built `new RippleDrawable(colour, base, null)`. With no explicit mask, a ripple
is masked against the composite of its content layers — and nearly every call passes a
*transparent* GradientDrawable as that content, because the row underneath wants no fill of its
own. A transparent composite multiplies the ripple away completely.

Every settings row, every permission row, every extension row, every FAQ entry and the back
button on two screens gave no touch feedback at all. Nobody reports a missing ripple; they
report that the app feels unresponsive, and tap again. A radius of zero does not escape it
either: GradientDrawable reports OPAQUE only when its solid colour is opaque, and transparent
never is.

The mask is built from the base's own corner radius now, so a rounded row gets a rounded ripple
without any call site having to say so.

### The dates were a month out, and the screen said they were Canonical's

Ubuntu 24.04 LTS standard security maintenance ends **May 2029**, not June — checked against
Canonical's own release-cycle page rather than remembered. The Ubuntu Pro entitlement runs to
**May 2034**, not 2036; 2036 is not a date Canonical publishes for this release at all, and the
separate Legacy add-on that reaches May 2039 is paid and not part of the free personal tier the
same sentence referred to.

A sentence that says "that is Canonical's published date for this release, not an estimate" is
the one sentence that cannot afford to be a month out. One gate now holds the date in the script,
the Java, the FAQ and the README to a single value.

### "Android build tools" promised a build it could not finish

The dialog said "Installs a JDK so Java and Kotlin Android projects can be built into a real,
installable APK". What the layer installs is a JDK. There is no Android SDK, and no aarch64
replacements for Google's aapt2, aidl, zipalign and split-select, which ship as x86-64 only —
so a Gradle build stops on the first of them with an Exec format error. The script even
contradicted itself about this, asserting in its header that those four were already replaced
while its own closing lines correctly said they were not.

The row is "Java toolchain (JDK)" now, and it says exactly what is missing and what a Gradle
build will do until it is supplied. Everything else on that screen — web, servers, command-line
programs, JVM tests — works with just the JDK, and it says that too.

### The memory figure the app told you to watch was the wrong one

`Exits.footprintBytes()` read `/proc/self/status` — the Android process alone — and the Activity
screen printed it as "counted against Android's limit", directly beside a workspace total in the
gigabytes. Everything that actually uses memory here is a PRoot child: the editor, its extension
host, a compiler. The row sat at around a hundred megabytes and gave no warning at all, right up
to the kill the app then explains as "Android ran Linux out of memory". It sums the whole
workspace now, from the process list the caller has already built.

### Held-back security updates were invisible

`apt-get upgrade` never installs a new package, so any security fix whose new version pulls in a
dependency or bumps a soname is "kept back" and produces no line in the simulation at all — the
screen would read "everything is up to date" while the fix sat waiting. The listing uses
`dist-upgrade` now, matches the origin inside the parentheses where the *candidate's* archive is
named rather than anywhere on the line, and installs without `--only-upgrade`, which is the flag
that was refusing to pull the new dependency.

### A theme change while the app was open did nothing

Every activity declares `uiMode` in `configChanges`, so Android does not recreate them — and
nothing overrode `onConfigurationChanged`. Flipping Dark mode from the quick-settings tile left
PocketIDE painting the old palette while every other app on the phone flipped, and
`setSystemBarsAppearance` is sticky per window, so the clock stayed the wrong colour too. All
four handle it. The editor rebuilds its chrome and carries the WebView across rather than
recreating it, because losing an editor session over a change of colour would be the worse bug.

### And one wrong explanation

`update.mode: "none"` was justified by "code-server's own updater is compiled out". It is not —
code-server ships a release check, and what suppresses it is its own `--disable-update-check`
flag, which this script already sets twice. The setting stays, for the narrower reason that is
actually true, and the comment now says which flag does what so nobody drops the wrong one.

### Gates

46, with eight more checks inside them, each verified against the broken code before the fix.

## 1.6.5

An audit of what 1.6.0 shipped, and eight things it was wrong about. All eight were confirmed by
reading the code rather than taken on trust, and every one now has a check that fails against
the code as it shipped.

### The bar I had just rebuilt could not be read

The active destination's label measured **3.97:1** on the lower half of the light capsule, and
its icon measured **3.28:1** on its own indicator. Under the floor, on the one control in the
app whose entire job is to say which screen you are looking at.

Nothing caught it, because the existing contrast gate compares a colour against a flat card or
page — and the bar is neither. It is a gradient with a translucent pill on it, and the icon sits
on the pill. Measured against the card colour, both passed.

The fix is Material 3's own model rather than a nudged hex value: the indicator is a light tone
of the hue and what sits on it is a *dark* tone of the same hue, and the active label takes the
plain text colour with weight marking it as selected. 6.8:1 to 15:1 now, both themes, both ends
of the gradient. The gate composites the pill over the gradient and measures against the result,
and it reads the alpha out of `Shell.java` so changing the indicator is caught here.

### A failed update check was recorded as "everything is up to date"

The worst shape a bug can have: wrong, and sticky. The script already printed `apt_list=0` when
it could not reach Ubuntu's servers, precisely so the app could tell a real answer from no
answer — and nothing read it. A check made offline reported zero security updates, which on the
screen is the same sentence as "everything is up to date", and then stamped the clock and did
not look again for a day.

It is read now, and a check that got no answer writes nothing. The throttle keeps two clocks: a
successful check is good for a day, a failed one is tried again in an hour.

### "Everything, now" contradicted its own dialog

Its text said "the editor has to be closed for its own update, and this will not start one while
it is open". Nothing enforced it. The guard existed only on the editor row, so the one row most
likely to be tapped could pull the tree out from under a running editor. Both ends check now —
the app before the dialog and again after it, because the two are seconds apart, and the script
with `pgrep`, because that is the end that cannot be raced.

### A kill during the swap cost a 224 MB re-download

The swap is two renames with a gap between them where `/opt/code-server` does not exist. Renames
are milliseconds, but milliseconds is not never. Landing in that gap left the working editor
beside the hole under `.previous` — and the app, seeing no editor, would offer to download the
editor already on the disk. Every entry point now looks and renames it back, including starting
the editor, which is the route an owner actually takes.

### Three more

The free-space check ran before the stale staging tree was cleared, so one interrupted unpack
refused every later attempt for want of room it was itself holding. `doRun` and `collect` never
destroyed the PRoot process, so a broken pipe left apt holding the package lock — pointing at a
process the owner can neither find nor stop. And `maybeRunInBackground` claimed its slot before
`Thread.start()`, so a phone that refused to create a thread would report "already checking" for
the life of the process.

### Gates

46, with eight new checks inside them — and one of them was itself rewritten during the work: it
matched the swap recovery by function name, and a rename passed it. It matches the condition now,
and counts the call sites.

## 1.6.0

The bars, and the thing nobody had built yet: a workspace that can keep itself current.

### The bottom bar was cutting its own labels off

A screenshot showed it plainly: "Home", "Activity", "Agents" and "Settings" all missing their
descenders. The bar was fixed at 64 dp while the column inside it came to about 68, and the
overflow was silently clipped. The number was never the problem — a bar that *cannot grow* is.
It now holds 64 dp as a minimum and lets its content decide the rest, which is also what makes
it survive a phone set to large text.

Rebuilt at the same time, because the flat card-coloured strip with square corners and a
hairline above it is the 2019 bar every app has moved off: it is now a floating glass capsule,
inset 12 dp from each side, lifted 10 dp off the gesture bar, fully rounded, lit at the top
edge and clipped to its own outline so a ripple cannot square the ends off.

It still takes its own room in the layout rather than hovering over the page. A bar that floats
over a scrolling list needs every list in the app to reserve space under it, and the one that
forgets leaves its last row unreachable. The gap around the capsule is the page's own colour —
so it looks like it floats, and the layout still has an honest bottom edge.

### The `<>` in the top right was decoration

It opened the editor. Nobody could tell, because it was a bare tinted glyph with nothing around
it saying it could be pressed. It is a Material 3 filled tonal icon button now: a 40 dp
container centred in a 48 dp touch target, with the ripple masked to the circle rather than
flashing a 48 dp square.

### The tagline existed for six-tenths of a second

It appeared on the opening frame and then nowhere at all, which is a flicker rather than a
tagline. It now sits under the app's name in the top bar, in Material's own
title-and-subtitle slot.

### Nothing kept itself up to date

Everything in the workspace was pinned — a pinned Ubuntu image, a pinned editor archive,
extensions frozen at whatever version they arrived as. A pin is right on the day it is made and
wrong a year later: Ubuntu ships a security fix within hours of a CVE, and a machine that never
runs `apt` never receives it.

So the pins are now the floor rather than the ceiling:

- **Ubuntu's security updates are taken automatically** — on Wi-Fi, once a day, while the app is
  open and the editor is not. Security only, never a full upgrade, because Ubuntu's own
  maintainers have already decided which changes are safe on a stable release.
- **Extensions keep themselves current**, by the editor, with `extensions.autoUpdate` and
  `extensions.autoCheckUpdates` both on. "Update them all now" is there for a workspace that has
  not been opened in a month.
- **The editor moves when you ask.** code-server cannot update itself — it is a tarball, its own
  updater is compiled out, and `update.mode: none` is why no notification offers an update that
  could never install. The app does it instead, from Settings.

The editor update cannot be pinned by checksum the way the first install is, because nobody can
pin a version that does not exist yet. Four checks stand in for the pin, and they are named in
the script rather than left to be assumed: the version and URL come from GitHub's own release
API over TLS; the bytes on disk must match the size GitHub published for that asset; the archive
must unpack into a tree containing a runnable editor; and that editor must report the exact
version that was asked for. Only then is anything installed touched — and if the swap leaves
anything unrunnable, the previous tree goes straight back.

Updates run only while PocketIDE is open, because Linux only runs while PocketIDE is open.
Android does not keep another operating system alive behind a closed app, and an app claiming
otherwise would be describing something that cannot happen.

### The app never said what kind of computer this is

"Ubuntu on your phone" tells nobody whether their project will build. Settings → The computer
now answers it with this phone's own numbers: cores, total and free memory, free storage, and
the build heap and worker count worked out from them — the same ladder `pocketide-tools.sh`
writes into Gradle's settings, with a gate comparing the two so they cannot drift apart.

It also says the thing that is counter-intuitive enough to send someone off to buy a phone they
did not need: **the APK's size is not the limit.** A 200 MB APK is no harder to produce than a
2 MB one. What costs memory is the compiler, and that is decided by module count, source count
and the size of the dependency graph — a small app with two hundred dependencies is a heavier
build than a large app with ten.

### Gradle, sized to the phone

Installing the Android build tools now writes `~/.gradle/gradle.properties` tuned to this
phone's actual memory: the build heap and worker count from the ladder above, the Kotlin
compiler daemon bounded separately (it is a second JVM, and two unbounded JVMs is the common way
a build gets the app killed), the daemon's idle timeout cut from three hours to ninety seconds
so it is not sitting on 1.5 GB between builds, and file-system watching off because Android's
inotify limit is low enough that a large project exhausts it — and when it does, Gradle does not
fail, it stalls.

It is written only when there is no file there already. Someone who has tuned their own build
has made a decision this app does not get to overrule.

### Gates

46, up from 43. The three new ones guard the update path (that the script is actually copied
into the workspace — without which every update fails from a row that looks like it works; that
the editor swap is staged, verified and reversible; and that what happens *without being asked*
is security updates and nothing else), the build-memory ladder in the two places it has to be
identical, and the bar's ability to grow.

## 1.5.5

Everything here came from one screenshot and one list of complaints from a phone. All of it was
real, and the audit found that several of the symptoms shared a cause.

### The editor could never load

`ERR_CLEARTEXT_NOT_PERMITTED`, on the one screen the app exists for. The app refused cleartext
everywhere and the editor is `http://127.0.0.1:8391`. The refusal is right for the internet and
stays; loopback is now exempt, and only loopback.

### The system bars were sitting on the app

Targeting SDK 35 means Android 15 draws the app edge to edge whether it asks or not, and
`setStatusBarColor` is a no-op. There was no inset handling anywhere, so the clock was painted
over the app's title and the gesture bar over the row of destinations — the bottom of every tab
could not be tapped. Both bars now take the room the system bars need, and a hairline under the
top bar puts back the boundary that was missing in dark.

### Every dialog had no surface

`Dialogs.show()` built the card and then destroyed it two lines later. The delete dialog showed
nothing readable because nothing was there.

### Colours nobody could read

One green, one amber and one red used on both a cream card and a near-black one. The failure red
measured 2.99:1 on dark — and it was the Remove everything button. Two of each now, every one at
least 4.5:1 on its own ground, recomputed on every build.

### The editor's toolbar was one run of letters

`CommandsKeysTrackpadHome`, jammed left, labels too small. The buttons had no layout parameters
at all. A quarter of the width each, 24 dp icons, 12 sp labels.

### Opening the app showed two frames at once

The window's background follows the phone's night setting; everything the app draws follows the
app's own. Set to Dark on a light phone, the window was white for a frame. The splash theme was
also defined and never applied. Both fixed, and the app's name — which the Android 12+ splash
has no slot for — is now the first thing the app draws for itself.

### Words

One name for one thing: the Linux is called Linux everywhere, not five different things. The FAQ
went from 32 entries to 19, losing everything the screen it sits on already answered, and gained
the guidance that was missing: how to tell a verified publisher from an unverified one, the four
things to check before installing anything, and that a well-established community extension is a
reasonable choice where no official one exists. "Elapsed" is "so far".

### Asking for permission

Android's rationale flag is false before the first ask and false after a permanent refusal, so
reading it alone treated a fresh install as a refusal. And nothing was asked at the moment it
mattered: set-up began a forty-minute job without offering the notification that carries its
progress and its Stop button.

## 1.5.0

The app got a shape. 1.0.0 was a stack of separate screens reached by tapping rows and backing
out again; this one has destinations along the bottom, a screen that shows what the phone is
actually doing, a lock over all of it, and an editor that sizes itself to the screen it is on.

### The interface

- **A navigation bar**, built to Material 3 Expressive's numbers rather than its older ones:
  64 dp tall, 24 dp icons, a 56×32 indicator, labels always shown. Four destinations — Home,
  Activity, Agents, Settings.
- **The editor is not one of them**, and that is the specification rather than a preference.
  Material is explicit that navigation bars belong to primary pages and toolbars to the pages
  reached from them, and that the two must never share a screen. It opens full screen with its
  own toolbar, one tap from Home's button or from the action in the top bar.
- **The mark and the name in the top bar**, so arriving from a notification or a recents card
  tells you which app you are in.
- **The editor sizes itself.** Every phone used to get the constant `window.zoomLevel` 1.5. Now
  it is worked out: `z = log(widthDp / 300) / log(1.2)`, which lands every phone within three
  pixels of the 300 effective pixels Visual Studio Code needs to stay usable. Android's font
  scale is honoured on top, and the comment says what that costs.

### What the computer can do

- **A browser, screenshots and video**, installable from Settings. An agent can open what it
  built, screenshot it, read the page back, click through it and record the run. Headless, with
  no display server anywhere.
- **Android build tools**, for Java and Kotlin projects.
- **Two permanent noes, on the screen rather than in a footnote.** The Android emulator cannot
  run on a phone — Google ships none for arm64 Linux, and even a self-built one needs `/dev/kvm`,
  which Android denies every app on an unrooted device. Apps containing C or C++ cannot be built,
  because there is no arm64 Android NDK. The phone itself is the test device instead.

### Safety

- **An app lock** over every screen, using the phone's own fingerprint or PIN, with the app kept
  out of the recent-apps preview while it is on.
- **The phone's files** can be mounted into the workspace as `~/phone` — off by default, and the
  switch is the whole safety property.
- **Why the workspace stopped** while you were away, read from Android's own exit records:
  Android 17's memory limiter, the Task Manager's Stop button, the low-memory killer, or a real
  crash — each with what to do about it, because each needs something different.

### Correctness

- **16 KB memory pages.** `zipalign -p 4` aligns native libraries to 4 KB; Android 15 introduced
  devices with 16 KB pages, where a library at the wrong offset cannot be mapped and the app dies
  at startup. Now `-P 16`, and the build re-reads the finished APK and refuses to sign one that
  does not verify. A second condition — where `GNU_RELRO` ends — is checked separately, because
  Google's own script does not.
- **The launcher mark** was outside the safe zone every OEM mask guarantees, and read as
  oversized beside Gmail and Drive. Rebuilt to a per-asset fill model.

## 1.0.0 — first release

The first version of PocketIDE, and a different architecture from everything this project tried
before it.

### What it is

A real Ubuntu 24.04 LTS ARM64 system under PRoot, running code-server (Code-OSS 1.137.0), with
coding-agent extensions installed from Open VSX. It runs entirely on the phone.

### What changed from the approaches that did not work

- **No VNC, no X server, no Electron.** An earlier design ran a full Electron IDE over a remote
  framebuffer and it crashed against Android's process ceiling on a 3.9 GB phone. The editor here
  is a Node server whose interface is HTML, drawn by the phone's own browser engine. Real text,
  platform scrolling, the phone's keyboard.
- **The three agents arrive the same way** — as their publishers' own extensions, from their own
  verified Open VSX namespaces. Google publishes one after all; an earlier search missed it under
  507 community results and wrongly reported it did not exist.
- **Not locked to three.** The whole Open VSX registry is browsable, verified publishers by
  default, with unverified behind a warning that quotes the real 2026 counterfeit numbers.
- **Material 3 Expressive, not Liquid Glass.** Liquid Glass is Apple's; Google has ruled it out
  for Android. Glass stays as an accent.

### Safety

- Ubuntu 24.04.5 LTS, the current point release, pinned to the SHA-256 in Canonical's own
  SHA256SUMS and re-checked by hashing the downloaded bytes. code-server pinned to a SHA-256
  verified first-hand, because Coder publishes none.
- Every extension download compared against the checksum Open VSX publishes, before installing.
- The editor listens on 127.0.0.1 only, behind a password this phone generates for itself —
  Android does not keep loopback private between apps.
- You never see that password. The app writes its SHA-256 into code-server's config as
  `hashed-password` and the same digest into code-server's session cookie, which is exactly what
  code-server checks under that method, so the editor opens already signed in. Read out of the
  pinned release's own source, not assumed — and a gate holds every part of it.
- Cleartext HTTP refused outright.

### Honesty

- 43 gates, every one of them bidirectional: each was verified to fail when the thing it guards
  is broken, not merely to pass when it is not.
- The Help screen carries the mission, the FAQ, the terms, the privacy position and the full
  permission list, and the gates check that list against the manifest.

### Known and stated

- Monaco has no touch text selection — Microsoft's own open issue. The cursor trackpad is the
  mitigation, not a fix.
- OpenAI's Codex Remote needs a Mac or Windows host, so there is no phone surface for it. The app
  says so rather than pretending.
- The Android and iOS build toolchains are not in this release; they need their own testing pass
  on a real device.

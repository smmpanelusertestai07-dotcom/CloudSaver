# PocketLinux

PocketLinux is a native Android app that runs a real Ubuntu 24.04 LTS ARM64 desktop on the phone
itself, with a built-in screen viewer, keyboard and mouse support. No root, no second device, no
cloud machine. It is built for people who write software from a phone because they do not have a
PC.

Everything runs locally: no WebView, no cloud PC, no subscription, no account and no telemetry.
Linux is the only system here, deliberately. See **Why Linux, and which Ubuntu** below.

## Why Linux, and which Ubuntu

Android already runs a Linux kernel. What Android will not give an installed app is root, or a
virtual machine, so a second operating system is out of reach. A Linux *userland* is not. Ubuntu's
ARM64 programs are ordinary binaries built for the same processor and the same kernel interface
the phone is already running, so they need no kernel of their own. PRoot supplies the rest: it
watches each system call the program makes and rewrites the file paths in it, so `/usr/bin` inside
the container means a folder inside this app's own private storage outside it. Nothing is granted
that Android had not already allowed, which is why this works on a stock phone with a locked
bootloader.

PocketLinux runs **Ubuntu 24.04 LTS ARM64** (the release Ubuntu calls "noble"). Set-up downloads
Canonical's own `ubuntu-base-24.04.4-base-arm64.tar.gz` and checks it against a SHA-256 compiled
into the app before anything is unpacked (`ContainerRuntime.java`). Ubuntu 24.04 LTS has security
updates from Ubuntu until **April 2029**. That is the only support date this release has. Earlier
versions of these documents also printed 2036 and 2039; those figures do not belong to Ubuntu
24.04 LTS and are not claimed here.

Canonical eventually removes older point releases from that folder. When the pinned file is no
longer there, the app reads the folder's own `SHA256SUMS`, takes the newest ARM64 base image listed
in it and checks that download against the digest printed beside it. Same host, same publisher, and
no digest compiled into an app nobody can update any more.

Everything installed afterwards comes from Ubuntu's signed ARM64 archive. apt checks the clearsigned
`InRelease` file against the Ubuntu archive keyring, then checks every `.deb` against a SHA-256 out
of that signed index. The honest words for that are **signed by Ubuntu**, not "safe" and not
"scanned": apt's own manual says apt-secure does not review signatures at a package level, so what
is guaranteed is that the archive is authentic, not that a given program is trustworthy.

## What you get

| Area | Detail |
| --- | --- |
| Linux | Ubuntu 24.04.4 LTS ARM64, SHA-256 verified, running under PRoot |
| Desktop | Openbox (windows, PocketLinux themerc), tint2 panel along the bottom (pinned apps, one button per open window, tray, this phone's battery/temperature/memory/free storage, 12-hour clock, PocketLinux mark that shows the desktop; movable to the top from the wallpaper menu), plus an event-driven work-area guard that keeps Chrome, tools, dialogs and installers inside the visible portrait/landscape boundary and above the panel. LXTerminal, PCManFM file manager, dunst notifications, PulseAudio, TigerVNC. Not GNOME, KDE, Xfce or Cinnamon: those want a gigabyte and a graphics chip before an app opens |
| Viewer | In-app RFB 3.8 client over a unix socket in app-private storage (`…/ubuntu-rootfs/home/coder/.pocketlinux/vnc.sock`, Xtigervnc started with `-rfbunixpath`, `-rfbunixmode 0600` and `-rfbport -1`). There is no TCP port behind it and no fallback to one. Android shares loopback between apps, so a VNC port with no password would be a desktop every other app on the phone could watch and type into. The desktop is born the way Screen rotation says (the phone's way up on Auto-rotate) and kept the size of the screen; at 100 % it sits inside a small gap with a rounded blue border on a deep backdrop (portrait and landscape); pinch, or More ▾ → Zoom in, to look closer, Fit to come back, Full screen to hide the controls |
| Controls | One bar, bottom by default (or top), five items wide so it fits the narrowest phone: **Home · status · Keyboard · Finger/Mouse/Screen · More ▾**. More holds everything else as one list in four groups. Now: Mute, the special-keys row (Esc, Tab, Ctrl, Alt, Super, Shift, arrows, Enter, Del, Home/End, PgUp/PgDn, F1-F12), Hold the mouse button down. Picture: Fit, Zoom in, Zoom out, Wider workspace, Bigger interface, Rotate, Rotation lock, Full screen, Auto-hide the controls, move the bar to the top or bottom. Windows: Switch, All open apps, Apps menu, Fit window, Resize, Minimise, Minimise all, Close, Force close, Reload. Phone: Volume and mute, Microphone, Take a photo, Add a file from the phone or a cloud drive, Phone files, Paste from the phone, Lock the screen. Full screen leaves one draggable chip on the glass to bring the bar back |
| Input | Finger mode (tap where you touch, swipe to scroll with a fling, hold to right-click, a hand at the pointer) and Mouse mode (drag the pointer, which is the shape the desktop reports; two fingers scroll; tap-then-drag), USB and Bluetooth mouse, hardware keyboard, composing-aware phone keyboard, Android clipboard bridge |
| Viewer internals | The picture is sent without any lossy compression: the viewer offers ZRLE (zlib over 64x64 tiles), CopyRect and Raw, and no JPEG encoding at all, so text on the desktop is never softened by a compressor. TigerVNC's Xvnc is itself the X server, so screen updates come out of the drawing pipeline instead of from polling the screen. Cursor pseudo-encoding (the desktop does not paint its pointer into the picture) and double-buffered updates (an RFB update is blitted to the screen only when complete, so frames never tear) |
| Sound and microphone | PulseAudio inside Linux plays into a virtual output whose PCM is streamed over a private unix socket; the app plays it through AudioTrack while the desktop is open. More ▾ → Microphone explicitly streams 16 kHz speech from Android into a private FIFO/PulseAudio source; it is off at every start and stops whenever the desktop leaves the foreground |
| Tools | Installed by set-up: `bash`, Git/Git LFS, curl/wget, nano/vim, sudo/apt, zip/unzip, less/file/rsync/jq/htop/tree, ripgrep, tmux, SSH, build-essential, Python 3 with pip/venv, Node.js/npm, SQLite, plus text editor, archives, picture viewer, calculator, task manager, screenshots and sound controls. **Tools → Software** is a lightweight store-like UI over Ubuntu's signed ARM64 apt repositories. Settings → Storage → Update the computer's basics refreshes all of it with Ubuntu security updates |
| Browser and downloads | **Google Chrome**, installed from Google's own apt repository, is the one browser for links and sign-ins. Google publishes an official ARM64 Linux `.deb`, so this is Chrome itself and not a substitute. Settings → Data and files → Downloads go to offers **Ask every time**, private **Computer Downloads**, or public **Phone Downloads** (`Download/PocketLinux`, with Phone files access). The Chrome policy, the Firefox profile, XDG file dialogs, Files, the package installer and the download watcher all use the same destination; switching never moves existing files |
| Installing anything else | A `.deb` downloaded in Chrome opens PocketLinux's installer: name, version, publisher, size against this phone's free space, and four checks (processor, space, dependencies, unsigned source) before an *Install anyway* or a blocked install with the reason. Registered as the handler for `.deb` files; the Apps menu also has *Install a downloaded app*. A Windows program or an AppImage is refused by name, with the reason, before anything is downloaded or changed |
| Apps | Rows install the publishers' own ChatGPT (with Codex), Claude Desktop (with Claude Code), Cursor and Antigravity ARM64 Linux builds, plus **Mobile app development** and **Design and game tools**. Install once; the same row updates in place, and install or uninstall can run beside an open desktop. The computer's own basics cannot be uninstalled: they are the computer |
| Reliability | The desktop runs **without PRoot's seccomp accelerator**, always. The accelerator breaks Chromium and Electron signal-handler resets (`socket()` and `readlink()` return ENOSYS and the app aborts), which was the "ChatGPT goes back by itself". Chromium apps also use `--no-zygote` |
| Data and privacy | Daily mobile-data limit with midnight reset; explicit download destination; app lock covering home and desktop with the phone's fingerprint or PIN; everything local, and Android's own cloud backup switched off for this app |
| Home screen | An opening (app mark and name, then Tux and "Powered by Linux · Ubuntu 24.04 LTS"), then three tabs on a bottom bar: Home (state and progress with Tux, Needs attention, mobile data meter, device compatibility and checked facts), Apps (the four AI desktop apps, Mobile app development, Design and game tools, and Install an app you downloaded), Settings (Appearance, Running, Data and files, Privacy and safety, Permissions including Background activity, Auto-launch and the Privacy monitor, and Storage) |
| Launching | Every launcher runs through `pocketlinux-open`, which adds the correct platform-specific sandbox and rendering profile, recognises an already-open app by the process that owns its window, brings it to the front, and shows the reason on screen if the app dies. Records are kept on the phone in `~/.pocketlinux/logs/` |
| Sign-in | The browser hands `chatgpt://`, `codex://` and `claude://` links back to the app that asked (`desktop-file-utils` plus a mimeapps table rebuilt on every start), through the launcher, which forwards the callback to the existing app even when its window is hidden, preserves the browser, and reports handoff failures |
| Phone files | Optional: six of the phone's folders (Download, DCIM, Documents, Pictures, Music, Movies) bound in as `/home/coder/Phone`, shown as **Phone files** on the desktop, the panel, the menu and Super+P, with Phone, Phone Downloads, Phone Photos and Phone Documents in every GTK file dialog's sidebar |
| Desktop extras | A blue Linux wallpaper with Tux, an **Apps** button wearing Tux on the panel (the full app list; also Super+A and a right-click or long press on the wallpaper), Chrome, Terminal, Files and Phone files on the panel, and the phone's own battery, temperature, free memory and network on the panel (`pocketlinux-status`, every 20 s); a memory guard that defers a new heavy Linux launch when available memory is below 700 MB without closing existing windows; an unclean stop (Android ending the app, or the display dying) is written down with the time and shown on the Home tab |

## What you can build with it

Three things decide every case, and the processor is only the third: how much memory is free, that
there is no graphics chip, and whether the tool publishes an ARM64 Linux build at all. The list
below is in three parts for that reason. A single list that mixed them would be a promise this app
cannot keep.

**Comfortable.** These are the things the hardware is genuinely good at.

- **Python, Node.js, C and C++.** Python 3 with pip and venv, Node.js with npm, and
  build-essential are installed by set-up. All have first-class ARM64 Linux builds.
- **Go, Rust and Dart.** Not installed by set-up, but all three publish official ARM64 Linux
  toolchains, so `apt install` or the vendor's own tarball works. Rust's `aarch64-unknown-linux-gnu`
  is a Tier 1 target with host tools, so the compiler itself runs here, not only the output.
- **Git, SSH and the terminal.** Git and Git LFS, OpenSSH's client, `rsync`, `jq`, `ripgrep`,
  `tmux`, `vim` and `nano` are all installed. Editing in a terminal with tmux and ripgrep is the
  most comfortable way to work on this screen, by a wide margin.
- **SQLite.** Installed, needs no server and almost no memory. It is the right default here.
- **CLI coding agents.** This is one of the strongest cases, because the heavy work happens on
  someone else's computer and the local cost is a text editor and an HTTPS connection. The Claude
  Code CLI publishes an official ARM64 Linux `.deb` whose only dependency is `libc6` 2.17 or newer,
  and Anthropic states a 4 GB minimum. It needs a paid Claude plan.
- **Driving a phone with adb.** `adb` and `scrcpy` are native ARM64 Ubuntu packages, and adb is an
  ordinary program that needs nothing but a TCP socket. With Android 11+ wireless debugging the
  computer can reach this same phone at `127.0.0.1`, or a second phone on the same Wi-Fi:
  `adb shell input`, `uiautomator dump`, `screencap`, `install`, `logcat`. **Apps → Mobile app
  development** installs the tools and a pairing helper.

**Possible, but tight.** These run. They are slow, or they want more memory than this phone has
spare, and you should expect to feel it.

- **Android APK builds.** Gradle, a headless JDK 21, `adb`, `fastboot`, `aapt` and `scrcpy` install
  from Ubuntu's own ARM64 archive. The one awkward piece is `aapt2`: Google publishes it for Intel
  Linux only, and Android's build plugin normally fetches that Intel binary from Maven, so
  **Mobile app development** also asks apt for an ARM64 `aapt2` and, when it gets one, writes
  `android.aapt2FromMavenOverride` into `~/.gradle/gradle.properties` to point Gradle at it. If it
  cannot get one it says so on the spot instead of letting a build fail later. The row also pins
  the Gradle daemon off and the heap at 1 GB, because the daemon is what runs a 4 GB phone out of
  memory. Small and medium projects build. There is no NDK, so no C or C++ modules: Google ships
  the NDK for Intel Linux only.
- **A GUI editor.** VS Code publishes ARM64 packages. Under PRoot it has to run with
  `--no-sandbox`, and it wants roughly 700 MB to 1 GB before you open a project, so it is a 4 GB
  and up proposition and a cramped one on a 720-pixel-wide screen.
- **2D game work in Godot.** Godot publishes official ARM64 Linux builds, and **Apps → Design and
  game tools** installs Ubuntu's ARM64 Godot package. Godot's stated minimum memory is 4 GB, and 2D
  work is genuinely workable. 3D is drawn on the processor and is slow. Exporting to Android needs
  the same build tools as above.
- **Images and drawing.** GIMP and Inkscape have ARM64 packages and work at this size.
  **Apps → Design and game tools** installs them.
- **Video with ffmpeg.** The ARM64 build works, but only as a software encoder. The phone's own
  hardware video encoder is an Android API and is not reachable from inside the container, so
  expect real time or slower.
- **Blender.** It installs from Ubuntu's ARM64 archive, and the row installs it. Be clear about
  what that buys: Blender's own published minimum is 8 GB of memory and a graphics chip with 2 GB
  of video memory and OpenGL 4.3. This phone has none of those. It will open, and it will not be
  usable for real 3D work.
- **PostgreSQL and MariaDB.** ARM64 packages exist. PostgreSQL still asks the kernel for a small
  System V shared-memory segment, and Android deliberately leaves System V IPC out, so it may
  refuse to start. Use SQLite, or a database on another machine.

**Out of reach.** Not slow. Not available.

- **Android Studio** publishes no ARM64 Linux build. Intel Linux only.
- **Flutter** publishes no ARM64 Linux SDK: every stable release carries an x64 Dart SDK. The
  standalone Dart SDK does have ARM64, so Dart command-line and server work is fine.
- **Unity's editor** requires an x64 processor with SSE2 and a real graphics chip.
- **The Android NDK** publishes Intel Linux builds only, so there is no C or C++ path for Android.
- **JetBrains IDEs** do publish an ARM64 Linux tarball, but JetBrains' own stated minimum is 8 GB
  of memory with 3 GB for the IDE. That is twice this hardware, so it is not a supported path here.
- **Zed** refuses to start without a real graphics chip; its override lands on the software
  renderer, whose performance upstream itself calls awful.
- **Docker and Podman** install and then do not work. See the next section but one.
- **The Android emulator** needs hardware virtualisation. See the next section but one.

## Compared with Google's Linux Terminal on Pixel phones

Google ships a Linux Terminal on some phones, and it is a fair question why you would use this
instead. Here is the honest answer.

Google's Linux Terminal runs Debian in a **real virtual machine on a real Linux kernel**, so it is
**faster than PocketLinux** for compiling and for anything that touches a lot of files. PocketLinux
traces every system call to rewrite the paths in it, which is what makes it work without root, and
that costs speed. Expect PocketLinux to feel noticeably slower than a real PC, especially while
installing packages or compiling.

What Google's Terminal needs is a phone with the Android Virtualization Framework and support for
non-protected virtual machines. The chips reported to qualify are Google Tensor G1 or newer,
MediaTek Dimensity 9400 or newer, and Samsung Exynos 2500. Qualcomm Snapdragon phones do not
support it. It is also switched on inside Developer options rather than shipped ready to use.

**No third-party app can use that virtual machine, on any phone.** The Android Virtualization
Framework's Java APIs are all `@SystemApi` and require the restricted `MANAGE_VIRTUAL_MACHINE`
permission, which is granted only to privileged or preinstalled apps. Google's own documentation
says they are not available to third-party apps. So this is not a race PocketLinux lost. There is
no version of PocketLinux, or of any other app you can install, that could have used it.

Where PocketLinux is the better tool:

- It runs on **any** ARM64 Android phone from Android 10 onwards. No particular chip, no Developer
  options, no root.
- It ships **a whole desktop**, not a bare terminal: windows, a panel, a file manager, a browser and
  an app list, drawn on the phone's screen with touch, an on-screen keyboard and a pointer.
- It **bridges the phone's own files**, so six of the phone's folders can appear inside Linux as
  `/home/coder/Phone` and a Linux program can open a photo or a document that is already on the
  phone.
- It installs the AI desktop apps and the adb workbench for you, with the flags they need to run in
  a container.

If you have a Pixel 6 or newer, or an Exynos or Dimensity flagship, and all you want is a shell,
Google's Terminal is the faster choice and you should use it. If you want a desktop, or you have any
other phone, this is the one that exists.

This comparison was checked in September 2026 against Google's own documentation. The chip list is
what has been reported rather than published by Google, so treat it as a guide. Nothing here is a
speed measurement taken on a Realme C25s, and none is claimed.

## What it cannot do

A short list, so none of it has to be discovered the hard way. Each line has the reason and the
nearest real alternative.

- **No Docker, Podman or any container runtime.** They need kernel isolation (namespaces, cgroups,
  overlayfs) that PRoot does not provide, because PRoot rewrites file paths rather than creating a
  new kernel view. *Instead:* `apt` for software, and `DOCKER_HOST=ssh://…` pointed at a real
  machine if you need containers.
- **No snaps.** snapd needs systemd to mount a snap's squashfs through a loop device, and there is
  no systemd here. Do not run `apt install firefox` or `apt install chromium-browser` on Ubuntu
  either: those are transitional packages that pull in snapd. *Instead:* the `.deb` of the same
  program. Firefox has one in Mozilla's own apt repository; Chrome has one in Google's.
- **No Flatpak apps.** Every Flatpak runs through bubblewrap, which has required unprivileged user
  namespaces unconditionally since version 0.12.0, and Android does not give an app those.
  *Instead:* the program's `.deb`.
- **AppImages do not run by double-clicking.** An AppImage mounts its own payload with FUSE, and
  Android does not grant an unprivileged app FUSE. The installer says so by name instead of failing
  silently. *Instead:* run it once with `--appimage-extract-and-run`, or extract it and keep the
  folder. Very few AppImages are built for ARM64 anyway.
- **No Android emulator and no device farm.** Every route (the Android Studio emulator, Cuttlefish,
  redroid, Waydroid, Anbox) needs hardware virtualisation or kernel modules that an unprivileged
  Android app cannot have. *Instead:* the adb workbench. Wireless debugging to this phone at
  `127.0.0.1`, or to a second phone on the same Wi-Fi, gives the same automation surface with no
  emulator.
- **No iOS anything.** There is no legal, working iOS runtime for ARM64 Linux; Apple licenses its
  system for Apple hardware. This will never be added. *Instead:* a paid cloud device farm on real
  Apple hardware, reached from Chrome inside the desktop.
- **No hardware virtualisation, so no second kernel.** `/dev/kvm` is not reachable from an ordinary
  Android app, and the Virtualization Framework APIs are reserved for preinstalled apps. That rules
  out Windows, macOS, another Linux kernel, and kernel modules. *Instead:* PRoot, which is what
  ships, and a machine over SSH when you genuinely need a kernel of your own.
- **No GPU acceleration, no compositor blur, no real glass panels, and no hardware video decode
  inside Linux.** Android does not expose the graphics device to an unprivileged app, so everything
  is drawn on the processor. Live compositor blur was measured at 225 % CPU on a sixteen-core
  desktop machine before any blur was even visible, so it is not a question of tuning.
  *Instead:* the look comes from a wallpaper blurred once when it was made, flat opaque
  panels and rounded corners drawn as artwork. That costs nothing and looks the same.
- **Claude Desktop's Cowork tab cannot work here.** Cowork boots a QEMU virtual machine and requires
  `/dev/kvm` and `/dev/vhost-vsock` with no software fallback; Anthropic's documentation names
  container-based Linux environments as exactly the failing case, and states roughly 25 GB of disk
  and at least 8 GB of memory. *Instead:* the Claude Code CLI, which needs only `libc6` 2.17 or
  newer and runs in the terminal on this hardware. Everything else in Claude Desktop works.
- **Sign-ins may not survive a restart the way they do on a PC.** Electron apps keep their token in
  a system keyring over D-Bus; with no keyring they fall back to a store that Electron's own
  documentation describes as encryption with a hardcoded password. Under PRoot the keyring daemon
  often fails to start. *Instead:* set-up installs `gnome-keyring` and `libsecret` so it usually
  works, but expect to sign in again sometimes.
- **The AI desktop apps are not promised to run.** ChatGPT, Claude Desktop, Cursor and Antigravity
  all publish real ARM64 Linux packages, and PocketLinux installs them the way their publishers say
  to. Whether each one launches under PRoot on a given phone has not been tested on every phone, and
  Antigravity in particular has open upstream bugs about ARM64 Linux. The rows install them and
  report the real failure if one fails. They are not a guarantee.
- **No x86 emulation.** Software built only for amd64 or Intel PCs will not run.

## Device requirements

- Android 10 (API 29) and above, on any brand of phone with an ARM64 processor. Checked live on the
  home screen, and the tests check the app's stated minimum against the build's.
- **4 GB of RAM is the honest minimum.** See the next section.
- At least 6 GB free (decimal, as Android's Settings counts) before setup; the finished system uses
  2-3 GB and grows into the phone's free space from there. PocketLinux sets no quota of its own.
- Reference device: Realme C25s, Android 13, 4 GB RAM.

### About memory, and about 2 GB phones

**4 GB is the stated minimum, and a 2 GB Android Go phone is not supported.** The reason is short:
after Android itself has taken roughly 1 to 1.2 GB and the desktop has started, a 2 GB phone
usually has well under 700 MB left, and no modern full browser fits in that. Chrome installs at
about 428 MB and Firefox at about 283 MB before either one has opened a window, so the browser gets
killed, and on this kind of phone the process that gets killed often takes the whole desktop
session with it.

PocketLinux does not lock a smaller phone out, and it does not pretend either. Below 4 GB the home
screen says so in plain words: the Linux computer runs here, and the AI desktop apps will not.
ChatGPT, Claude, Cursor and Antigravity are Chromium programs and each one wants roughly 700 MB of
its own, which is a fact about those programs and not a limit this app could lift. What a phone
under 4 GB can still do is the terminal side of the list above: the desktop, a terminal, an editor,
the file manager, Python, Node, Git and SQLite. Not the command-line AI agents either, in the sense
of a promise: Anthropic's own stated minimum for the Claude Code CLI is 4 GB. For the web, use the
phone's own Android browser rather than one inside the container.

6 GB or more is better if you intend to keep an AI desktop app open beside other work.

## Build

Plain Android SDK command-line tools. No Gradle, no Maven, no AndroidX.

This sideload compiles against API 35, targets API 35 and has `minSdkVersion 29`. It targeted
API 28 while a Windows compatibility layer existed, because only that compatibility domain permits
executable mappings from a writable private rootfs, which is how a Windows program's downloaded
code was mapped. Linux needs nothing of the kind: PRoot and its loader are signed native libraries
inside the APK, extracted by the package manager. A test locks the target at 35.

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk   # needs platform 35 + build-tools 35.0.0
chmod +x build.sh
./build.sh
```

Requires JDK 17 or newer and `zip`. The build needs a signing key, and this repository does not
contain one. It used to: a keystore sat in `.signing/` with its password written in `build.sh`,
on the reasoning that Android installs an update only when it carries the same signature and
uninstalling PocketLinux deletes the whole Ubuntu container. That reasoning is real but the
conclusion was wrong. A key everyone can read is not a key: anyone at all could sign an APK that
installed straight over an existing PocketLinux and inherited the container, the apps inside it,
those apps' saved sign-ins, and the phone folders the computer can reach. It is gone, and it must
be treated as compromised wherever it was used.

Set `POCKETLINUX_KEYSTORE`, `POCKETLINUX_STORE_PASS` and `POCKETLINUX_KEY_PASS` (key alias
`pocketlinux`, or set `POCKETLINUX_KEY_ALIAS`) to sign with your own key. With none set the build
still produces an installable APK, signed with a key that exists only for that build and named
`-devkey` so it can never be handed over as a release.

Run the static tests with `bash tests/run-tests.sh`. GitHub Actions runs the same suites, builds
the signed APK and, from `main`, publishes it as a release tagged `pocketlinux-v<version>`
(`.github/workflows/pocketlinux.yml`). It signs with the repository secrets
`POCKETLINUX_KEYSTORE_B64`, `POCKETLINUX_STORE_PASS` and `POCKETLINUX_KEY_PASS`, and refuses to
publish a release at all when they are absent, because an APK signed with a throwaway key cannot
update anyone's install.

## Permissions

PocketLinux asks for the minimum set, and every one of them is visible in the app's Permissions card.

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Download Ubuntu and packages; detect Wi-Fi vs mobile data |
| `WAKE_LOCK` | Keep a long setup or desktop session from being suspended mid-write |
| `POST_NOTIFICATIONS` | Show setup progress and the session's stop button |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Required to keep the Linux process alive while you use it |
| `VIBRATE` | Right-click and long-press feedback in the desktop viewer |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Shows one yes/no prompt asking to exempt a 15-45 minute setup from battery saver. The user always chooses; nothing is exempted silently |
| `USE_BIOMETRIC` | The optional App lock's fingerprint prompt (the phone's PIN is the fallback). Granted at install, nothing is read from the sensor by the app |
| `RECORD_AUDIO` | **Optional and requested on use.** More ▾ → Microphone feeds desktop speech input; it starts only after the tap and stops as soon as the desktop screen is left |
| `MANAGE_EXTERNAL_STORAGE` (Android 11+), `READ/WRITE_EXTERNAL_STORAGE` (Android 10) | **Optional, off by default.** Settings → Permissions → Phone files: six of the phone's folders (Download, DCIM, Documents, Pictures, Music, Movies) become the Phone folder inside the Linux computer, so an AI app can attach a file from the phone. Nothing on the phone is visible to the computer until the owner allows it |

There is **no** camera, location, contacts, accessibility or overlay permission, and the app never
requests device admin. A photo uses Android's separate camera app, not camera permission. Storage
is reachable only through the optional Phone files switch above.

Anything you install inside Linux runs with this app's network access, and with its file access.
PocketLinux neither inspects nor restricts what a Linux program sends. What you type into ChatGPT,
Claude, Cursor or Antigravity goes to those companies under their own terms.

OEM auto-start is *not* a permission: it is a settings page the Permissions card links to. Every
row in that card shows an ON/OFF pill and states what changes if it is off, and a first-launch
prompt asks for what setup needs before any long download begins.

## Phone health

The app is tuned so daily multi-hour use does not damage the phone.

| Guard | Behaviour | Configurable |
| --- | --- | --- |
| Desktop resolution | Your screen's size in the current orientation, capped at a 1600 px long side, 24-bit; resized live on rotation | No |
| Window boundary | Normal apps open maximised; floating dialogs and tools are centred and moved or shrunk against the live usable work area whenever a window appears, the phone rotates or the panel moves | No |
| Desktop text size | DPI-based, so type grows without the picture being stretched to do it. Xvnc's `-dpi`, `Xft.dpi` and `gtk-xft-dpi` are all set to the same value | Yes (Compact / Normal / Large) |
| Font smoothing | Greyscale, never subpixel. Phone panels use non-standard subpixel layouts and the screen rotates, so subpixel smoothing would be wrong in one orientation whatever it did in the other | No |
| Stopping by itself | Smart (default): 25 minutes untouched, battery under 15 % off the charger, dangerous heat, or today's mobile data limit reached; or 1/2/4/6 hours; or Never. The home screen says when and why it last stopped | Yes |
| Speed | The desktop always runs **without** PRoot's seccomp accelerator: every system call is traced, which is slower, but it is the only mode Chromium and Electron apps survive. With it, `socket()` and `readlink()` return ENOSYS and ChatGPT dies before it draws a window | No |
| Temperature | Warns at 45 °C battery or Android `SEVERE` thermal state; stops only at 49 °C or `CRITICAL` | Yes (Overheat protection) |
| Low battery | Stops at 3 % when not charging; ignored while plugged in | Yes |
| Entry check | Setup needs 10 % battery, opening the desktop needs 4 %; both skipped while charging | No |
| Network | Mobile data is allowed by default; an optional switch limits large downloads to Wi-Fi | Yes |
| Wake lock | Partial only. The screen is never forced on outside the desktop screen | No |

## Other limits worth knowing

- This is a **container**, not a hardware virtual machine. It shares Android's kernel.
- PRoot is path translation for compatibility, not a strong security boundary. Do not run untrusted
  Linux binaries inside it.
- The VNC session has no password because it is not on a network at all: it is a unix socket inside
  this app's private storage, which no other app on the phone can open.
- Chromium and Electron apps run with `--no-sandbox`, because their normal Linux sandbox cannot work
  under PRoot. That weakens isolation inside the container.
- The AI desktop apps are, at the time of writing, a public preview (ChatGPT) and a beta (Claude
  Desktop) on Linux. That is their makers' current scope and it changes with their updates. This app
  cannot change anyone's plan or usage limits.
- A self-signed sideload can still show an Android or Play Protect warning. Only distribution
  through Play review removes that reliably, and PocketLinux is not distributed through Play.

## Why Linux only

Three separate facts, any one of which settles it:

1. **Real Windows needs a virtual machine**, and Android's virtualisation framework is documented as
   being for privileged and preinstalled applications. An installed app cannot start one.
2. **The one project that ran Windows programs on ARM64 dropped its Android support.**
3. **This container already traces every system call**, and an instruction translator on top of that
   is the known-broken combination.

And where a layer does work, it loses: two of the four apps ship for Windows as store packages,
which a layer installs without package identity, breaking the custom-link sign-in and the app's own
updater; and these are Chromium apps, which under translation lose their sandbox and use more memory
on a phone that has very little to spare. The one feature the Windows builds have that
the Linux ones lack, the apps' own Computer Use, works by driving *Windows* programs with *Windows*
automation, so it is the first thing that breaks inside a layer. PocketLinux supplies that capability
itself over MCP instead.

On ARM64 the Linux builds are the better supported side. Every one of these tools that ships a Linux
build ships an ARM64 one: Claude Code, Claude Desktop, Cursor, the ChatGPT desktop app and Google
Chrome all publish official ARM64 Linux packages, so they install on this phone the same way they
install on a Linux PC. macOS is licensed only for Apple's own hardware.

## Trusted downloads

- Ubuntu Base 24.04.4 ARM64 -- `https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.4-base-arm64.tar.gz`
- Expected SHA-256 -- `04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2`
- ChatGPT for Linux ARM64 -- `https://persistent.oaistatic.com/codex-app-prod/linux/deb/latest/chatgpt_arm64.deb`
- Claude Desktop -- Anthropic's apt repository, accepted only when the signing key matches the
  fingerprint `31DDDE24DDFAB679F42D7BD2BAA929FF1A7ECACE` that Anthropic publishes
- Antigravity -- Google's own apt repository (`us-central1-apt.pkg.dev/projects/antigravity-auto-updater-dev`, suite `antigravity-debian`), which publishes arm64 builds
- Google Chrome -- Google's own apt repository (`dl.google.com/linux/chrome/deb`, key `linux_signing_key.pub`), arm64

Downloads resume after a dropped connection and fail over to a second Ubuntu mirror. Every archive
is checked against the SHA-256 above before it is unpacked.

Each of those hosts sees this phone's IP address and what was requested. PocketLinux itself has no
server of its own to send anything to.

See `OPEN_SOURCE_NOTICES.md` for third-party licences and for where to get the source.

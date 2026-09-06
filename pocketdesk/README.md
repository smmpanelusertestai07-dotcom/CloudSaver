# PocketLinux

PocketLinux is a native Android app that runs a real Ubuntu 24.04 LTS ARM64 desktop on your phone,
with a built-in screen viewer, keyboard and mouse support. It is built for people who code from a
phone because they do not have a PC. It is an **agentic development environment** — the makers'
own AI desktop apps, Google Chrome and a complete practical development toolset running locally.
It is a focused computer rather than a replacement for hardware-dependent PC gaming or virtualisation.

Everything runs locally: no WebView, no cloud PC, no subscription. Linux is the only system here,
deliberately — see **Why Linux only** below, and the release notes for the full reasoning.

## What you get

| Area | Detail |
| --- | --- |
| Linux | Ubuntu 24.04.4 LTS ARM64, SHA-256 verified, running under PRoot |
| Desktop | Openbox (windows, PocketLinux themerc), tint2 panel along the bottom (pinned apps, one button per open window, tray, this phone's battery/temperature/memory/free storage, 12-hour clock, PocketLinux mark that shows the desktop; movable to the top from the wallpaper menu), plus an event-driven work-area guard that keeps Chrome, tools, dialogs and installers inside the visible portrait/landscape boundary and above the panel. LXTerminal, PCManFM file manager, dunst notifications, PulseAudio, TigerVNC. Not GNOME, KDE, Xfce or Cinnamon — those want a gigabyte and a GPU before an app opens |
| Viewer | In-app RFB 3.8 client over a unix socket in app-private storage (`…/ubuntu-rootfs/home/coder/.pocketdesk/vnc.sock`, Xtigervnc `-rfbunixpath` with `-rfbport -1`), falling back to `127.0.0.1:5901` only on a container whose server has no unix support — Android shares loopback between apps, so a port here is reachable by any other app. The desktop is born in the phone's orientation and kept the size of the screen; at 100 % it sits inside a small gap with a rounded blue border on a deep backdrop (portrait and landscape), pinch or Screen → Zoom to look closer, Fit to come back, Full screen to hide the controls |
| Controls | One bar, bottom by default (or top): Home · status · Screen ▾ (Fit, Zoom, Rotate, Full screen, bar position, Volume) · Finger/Mouse · Keyboard · Keys (Esc, Tab, Ctrl, Alt, Super, arrows, on demand) · Window ▾ (Close, Force close, Switch, All windows, Minimise all, Paste, Apps menu, Phone files, Reload the screen) |
| Input | Finger mode (tap where you touch, swipe to scroll with a fling, hold to right-click, a hand at the pointer) and Mouse mode (drag the pointer, which is the shape the desktop reports; two fingers scroll; tap-then-drag), USB and Bluetooth mouse, hardware keyboard, composing-aware phone keyboard, Android clipboard bridge |
| Viewer internals | Cursor pseudo-encoding (the desktop does not paint its pointer into the picture), double-buffered updates (an RFB update is blitted to the screen only when complete, so frames never tear) |
| Sound and microphone | PulseAudio inside Linux plays into a virtual output whose PCM is streamed over a private unix socket; the app plays it through AudioTrack while the desktop is open. Screen → Microphone explicitly streams 16 kHz speech from Android into a private FIFO/PulseAudio source; it is off at every start and stops whenever the desktop leaves the foreground |
| Tools | Installed by set-up — `bash`, Git/Git LFS, curl/wget, nano/vim, sudo/apt, zip/unzip, less/file/rsync/jq/htop/tree, SSH, build-essential, Python 3 with pip/venv, Node.js/npm, plus text editor, archives, picture viewer, calculator, task manager, screenshots and sound controls. **Tools → Software** is a lightweight store-like UI over Ubuntu's signed ARM64 apt repositories. Settings → Storage → Update the computer's basics refreshes all of it with Ubuntu security updates |
| Browser and downloads | **Google Chrome**, installed from Google's apt repository, is the one browser for links and sign-ins. Settings → Data and files → Downloads go to offers **Ask every time**, private **Computer Downloads**, or public **Phone Downloads** (`Download/PocketLinux`, with Phone files access). Chrome policy, Firefox fallback, XDG file dialogs, Files, the package installer and download watcher use the same destination; switching never moves existing files |
| Installing anything else | A .deb downloaded in Chrome opens PocketLinux's installer: name, version, publisher, size against this phone's free space, and four checks (processor, space, dependencies, unsigned source) before an *Install anyway* or a blocked install with the reason. Registered as the handler for .deb files; the Apps menu also has *Install a downloaded app* |
| Apps | Rows install the publishers' official ChatGPT (with Codex), Claude Desktop (with Claude Code), Cursor and Antigravity ARM64 Linux builds, plus Mobile app development. Install once; the same row updates in place, and install/uninstall can run beside an open desktop. The computer's own basics cannot be uninstalled — they are the computer |
| Reliability | The desktop runs **without PRoot's seccomp accelerator**, always — the accelerator breaks Chromium/Electron signal-handler resets (`socket()`/`readlink()` return ENOSYS and the app aborts), which was the "ChatGPT goes back by itself". Chromium apps also use `--no-zygote` |
| Data and privacy | Daily mobile-data limit with midnight reset; explicit download destination; app lock covering home and desktop with the phone's fingerprint/PIN; everything local and Android cloud backup off |
| Home screen | An opening (app mark and name, then Tux and "Powered by Linux · Ubuntu 24.04 LTS"), then three tabs on a bottom bar: Home (state/progress with Tux, Needs attention, mobile data meter, device compatibility and checked facts), Apps (the four AI desktop apps and mobile app development), Settings (Appearance, Running, Data/files, Privacy/safety, Permissions incl. Background activity and Auto-launch, exact crash and Linux app reports, Storage) |
| Launching | Every launcher runs through `pocketdesk-open`, which adds the correct platform-specific sandbox/rendering profile, recognises an already-open app by the process that owns its window, brings it to the front, and shows the reason on screen if the app dies. Reports are kept in `~/.pocketdesk/logs/` |
| Sign-in | The browser hands `chatgpt://`, `codex://` and `claude://` links back to the app that asked (`desktop-file-utils` + a mimeapps table rebuilt on every start), through the launcher, which forwards the callback to the existing app even when its window is hidden, preserves the browser, and reports handoff failures |
| Phone files | Optional: the phone's storage bound in as `/home/coder/Phone`, shown as **Phone files** on the desktop, the panel, the menu and Super+P, with Phone, Phone Downloads, Phone Photos and Phone Documents in every GTK file dialog's sidebar |
| Desktop | A blue Linux wallpaper with Tux, an **Apps** button wearing Tux on the panel (the full app list; also Super+A and a right-click or long press on the wallpaper), Chrome, Terminal, Files and Phone files on the panel, and the phone's own battery, temperature, free memory and network on the panel (`pocketdesk-status`, every 20 s); a memory guard that defers a new heavy Linux launch when available memory is below 700 MB without closing existing windows; an unclean stop (Android ending the app, or the display dying) is written down with the time and shown on the Home tab |

## Device requirements

- Android 10 (API 29) and above, on any brand of phone with an ARM64 processor — checked live on the home screen ("Your phone is compatible"); the tests check the app's stated minimum against the build's
- 4 GB RAM minimum; 6 GB or more is better for Electron apps such as ChatGPT
- At least 6 GB (decimal, as Android's Settings counts) free before setup; the finished system uses 2–3 GB, and grows into the phone's free space from there (PocketLinux sets no quota of its own)
- Reference device: Realme C25s, Android 13, 4 GB RAM

## Build

Plain Android SDK command-line tools — no Gradle, no Maven, no AndroidX.

This sideload compiles against API 35, targets API 35 and has `minSdkVersion 29`. It targeted
API 28 while a Windows compatibility layer existed, because only that compatibility domain permits
executable mappings from a writable private rootfs — which is how a Windows program's downloaded
code was mapped. Linux needs nothing of the kind: PRoot and its loader are signed native libraries
inside the APK, extracted by the package manager. A test locks the target at 35.

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk   # needs platform 35 + build-tools 35.0.0
chmod +x build.sh
./build.sh
```

Requires JDK 17 or newer and `zip`. The script creates a reusable local preview key under
`.signing/`. For a public release supply your own keystore through `POCKETDESK_KEYSTORE`,
`POCKETDESK_STORE_PASS` and `POCKETDESK_KEY_PASS`, and never publish the private key.

Run the static tests with `bash tests/run-tests.sh`. GitHub Actions runs the same suites and builds the release APK as an artifact on every push that touches `pocketdesk/` (`.github/workflows/pocketdesk.yml`); add `POCKETDESK_KEYSTORE_B64`, `POCKETDESK_STORE_PASS` and `POCKETDESK_KEY_PASS` as repository secrets to sign CI builds with one fixed key.

## Permissions

PocketLinux asks for the minimum set, and every one of them is visible in the app's Permissions card.

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Download Ubuntu and packages; detect Wi-Fi vs mobile data |
| `WAKE_LOCK` | Keep a long setup or desktop session from being suspended mid-write |
| `POST_NOTIFICATIONS` | Show setup progress and the session's stop button |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Required to keep the Linux process alive while you use it |
| `VIBRATE` | Right-click and long-press feedback in the desktop viewer |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Shows one yes/no prompt asking to exempt a 15–45 minute setup from battery saver. The user always chooses; nothing is exempted silently |
| `USE_BIOMETRIC` | The optional App lock's fingerprint prompt (the phone's PIN is the fallback). Granted at install, nothing is read from the sensor by the app |
| `RECORD_AUDIO` | **Optional and requested on use.** Screen → Microphone feeds desktop speech input; it starts only after the tap and stops as soon as the desktop screen is left |
| `MANAGE_EXTERNAL_STORAGE` (Android 11+), `READ/WRITE_EXTERNAL_STORAGE` (Android 10) | **Optional, off by default.** Settings → Permissions → Phone files: the phone's storage becomes the Phone folder inside the Linux computer, so an AI app can attach a file from the phone. Nothing on the phone is visible to the computer until the owner allows it |

There is **no** camera, location, contacts, accessibility or overlay permission, and the app never
requests device admin. A photo uses Android's separate camera app, not camera permission. Storage
is reachable only through the optional Phone files switch above.

OEM auto-start is *not* a permission — it is a settings page the Permissions card links to. Every
row in that card shows an ON/OFF pill and states what changes if it is off, and a first-launch
prompt asks for what setup needs before any long download begins.

## Phone health

The app is tuned so daily multi-hour use does not damage the phone.

| Guard | Behaviour | Configurable |
| --- | --- | --- |
| Desktop resolution | Your screen's size in the current orientation, capped at a 1600 px long side, 24-bit; resized live on rotation | No |
| Window boundary | Normal apps open maximised; floating dialogs/tools are centred and moved/shrunk against the live usable work area whenever a window appears, the phone rotates or the panel moves | No |
| Desktop text size | DPI-based, so type grows without the picture blurring | Yes (Compact / Normal / Large) |
| Stopping by itself | Smart (default): 25 minutes untouched, battery under 15 % off the charger, dangerous heat, or today's mobile data limit reached; or 1/2/4/6 hours; or Never. The home screen says when and why it last stopped | Yes |
| Speed | The desktop always runs **without** PRoot's seccomp accelerator: every syscall is traced, which is slower, but it is the only mode Chromium and Electron apps survive — with it, `socket()` and `readlink()` return ENOSYS and ChatGPT dies before it draws a window | No |
| Temperature | Warns at 45 °C battery or Android `SEVERE` thermal state; stops only at 49 °C or `CRITICAL` | Yes (Overheat protection) |
| Low battery | Stops at 3% when not charging; ignored while plugged in | Yes |
| Entry check | Setup needs 10% battery, opening the desktop needs 4% — both skipped while charging | No |
| Network | Mobile data is allowed by default; an optional switch limits large downloads to Wi-Fi | Yes |
| Wake lock | Partial only. The screen is never forced on outside the desktop screen | No |

## Honest limitations

- This is a **container**, not a hardware VM. It shares Android's kernel, so it cannot run Windows,
  macOS, another kernel, Docker, KVM, or anything needing kernel modules.
- PRoot is path translation for compatibility, not a strong security boundary. Do not run untrusted
  Linux binaries inside it.
- VNC has no password because it binds to `127.0.0.1` only and is never exposed to a network.
- Chromium and Electron apps run with `--no-sandbox`, because their normal Linux sandbox cannot work
  under PRoot. That weakens isolation inside the container.
- The AI desktop apps are, at the time of writing, a preview (ChatGPT) and a beta (Claude) on
  Linux; that is their makers' current scope and changes with their updates. Claude Desktop's
  Cowork tab needs hardware virtualisation (KVM), which a phone container cannot provide. This
  app cannot change anyone's plan or usage limits.
- A self-signed sideload can still show an Android or Play Protect warning. Only distribution through
  Play review removes that reliably.
- x86 emulation is not included, so amd64-only Linux software will not run.
- Windows programs do not run here, and a compatibility layer is not an option either. See
  **Why Linux only** below.

## Why Linux only

Three separate facts, any one of which settles it:

1. **Real Windows needs a virtual machine**, and Android's virtualisation framework is documented
   as being for privileged and platform applications — an installed app cannot start one.
2. **The one project that ran Windows programs on ARM64 dropped its Android support.**
3. **This container already traces every system call with ptrace**, and an instruction translator
   on top of that is the known-broken combination.

And where a layer does work, it loses: two of the four apps ship for Windows as store packages,
which a layer installs without package identity — breaking the custom-link sign-in and the app's
own updater — and these are Chromium apps, which under translation lose their sandbox and cost
roughly a third more memory on a phone with under four gigabytes. The one feature the Windows
builds have that the Linux ones lack, the apps' own Computer Use, works by driving *Windows*
programs with *Windows* automation, so it is the first thing that breaks inside a layer;
PocketLinux supplies that capability itself over MCP instead.

On ARM64 the Linux builds are simply better supported: Claude's Cowork is not supported on Windows
ARM64 at all, and Claude Code on Windows ARM64 has an open crash report. All four apps publish
official Linux ARM64 builds. macOS is licensed only for Apple's own hardware.

Ubuntu 24.04 LTS has security updates to April 2029, to April 2036 with Ubuntu Pro (free for
personal use) and to April 2039 with the Legacy add-on. Each Windows release gets about
twenty-four months. For a computer set up once and left alone, that is the whole argument.

## Trusted downloads

- Ubuntu Base 24.04.4 ARM64 — `https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.4-base-arm64.tar.gz`
- Expected SHA-256 — `04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2`
- ChatGPT for Linux ARM64 — `https://persistent.oaistatic.com/codex-app-prod/linux/deb/latest/chatgpt_arm64.deb`
- Claude Desktop — Anthropic's apt repository, accepted only when the signing key matches the
  fingerprint `31DDDE24DDFAB679F42D7BD2BAA929FF1A7ECACE` that Anthropic publishes
- Antigravity — Google's own apt repository (`us-central1-apt.pkg.dev/projects/antigravity-auto-updater-dev`, suite `antigravity-debian`), which publishes arm64 builds
- Google Chrome — Google's own apt repository (`dl.google.com/linux/chrome/deb`, key `linux_signing_key.pub`), arm64

Downloads resume after a dropped connection and fail over to a second Ubuntu mirror. Every archive is
checked against the SHA-256 above before it is unpacked.

See `OPEN_SOURCE_NOTICES.md` for third-party licences.

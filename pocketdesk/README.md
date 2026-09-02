# PocketDesk

PocketDesk is a native Android app that runs a real Ubuntu 24.04 LTS ARM64 desktop on your phone,
with a built-in screen viewer, keyboard and mouse support. It is built for people who code from a
phone because they do not have a PC.

No WebView, no cloud PC, no subscription. Everything runs locally on the device.

## What you get

| Area | Detail |
| --- | --- |
| Linux | Ubuntu 24.04.4 LTS ARM64, SHA-256 verified, running under PRoot |
| Desktop | Openbox, tint2 panel, LXTerminal, PCManFM file manager, dunst notifications, TigerVNC |
| Viewer | In-app RFB 3.8 client bound to `127.0.0.1:5901`. The desktop is born in the phone's orientation and kept the size of the screen, so the whole desktop always fits at 100 %; pinch or Screen → Zoom to look closer, Fit to come back, Full screen to hide the controls |
| Controls | One bar, top or bottom: Home · status · Screen ▾ · Finger/Mouse · Keyboard · Keys (Esc, Tab, Ctrl, Alt, Super, arrows, on demand) · Window ▾ (Close, Force close, Switch, All windows, Minimise all, Paste) |
| Input | Finger mode (tap where you touch, swipe to scroll, hold to right-click) and Mouse mode (drag the arrow, two fingers scroll, tap-then-drag), USB and Bluetooth mouse, hardware keyboard, composing-aware phone keyboard, Android clipboard bridge |
| Tools | `bash`, `git`, `curl`, `nano`, `sudo`, `apt` — install anything else yourself |
| Browser | GNOME Web (Epiphany) is installed by default because it opens in a second or two; Firefox is a one-tap extra. Downloads land in `~/Downloads` inside Linux |
| Apps | One-tap installs of the makers' own official Linux builds: ChatGPT (with Codex), Claude Desktop (with Claude Code), Cursor and Antigravity. Install once; the same row updates in place |
| Data and privacy | Daily mobile-data limit with midnight reset (stops downloads and the desktop); Downloads visible to the phone or kept inside Linux; app lock covering the whole app with the phone's fingerprint or PIN; everything local, Android cloud backup off |
| Home screen | Three tabs on a bottom bar: Home (state, Needs attention, mobile data meter, Your phone is compatible, the questions), Apps, Settings (grouped; a dot only for what Settings can fix) |
| Launching | Every launcher runs through `pocketdesk-open`, which adds the sandbox flags a Chromium-based app needs in a container, recognises an already-open app by the process that owns its window and brings it to the front, and shows the reason on screen if the app dies. Reports are kept in `~/.pocketdesk/logs/` |
| Sign-in | The browser hands `chatgpt://`, `codex://` and `claude://` links back to the app that asked (`desktop-file-utils` + a mimeapps table rebuilt on every start) |

## Device requirements

- Android 10 (API 29) and every version after it, on any brand of phone with an ARM64 processor — checked live on the home screen ("Your phone is compatible"); the tests check the app's stated minimum against the build's
- 4 GB RAM minimum; 6 GB or more is better for Electron apps such as ChatGPT
- At least 4 GB (decimal, as Android's Settings counts) free before setup; the finished system uses 1.5–3 GB
- Reference device: Realme C25s, Android 13, 4 GB RAM

## Build

Plain Android SDK command-line tools — no Gradle, no Maven, no AndroidX.

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk   # needs platform 35 + build-tools 35.0.0
chmod +x build.sh
./build.sh
```

Requires JDK 17 or newer and `zip`. The script creates a reusable local preview key under
`.signing/`. For a public release supply your own keystore through `POCKETDESK_KEYSTORE`,
`POCKETDESK_STORE_PASS` and `POCKETDESK_KEY_PASS`, and never publish the private key.

Run the static tests with `bash tests/run-tests.sh`.

## Permissions

PocketDesk asks for the minimum set, and every one of them is visible in the app's Permissions card.

| Permission | Why |
| --- | --- |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Download Ubuntu and packages; detect Wi-Fi vs mobile data |
| `WAKE_LOCK` | Keep a long setup or desktop session from being suspended mid-write |
| `POST_NOTIFICATIONS` | Show setup progress and the session's stop button |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | Required to keep the Linux process alive while you use it |
| `VIBRATE` | Right-click and long-press feedback in the desktop viewer |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Shows one yes/no prompt asking to exempt a 10–30 minute setup from battery saver. The user always chooses; nothing is exempted silently |

There is **no** storage, camera, microphone, location, contacts, accessibility or overlay
permission, and the app never requests device admin.

OEM auto-start is *not* a permission — it is a settings page the Permissions card links to. Every
row in that card shows an ON/OFF pill and states what changes if it is off, and a first-launch
prompt asks for what setup needs before any long download begins.

## Phone health

The app is tuned so daily multi-hour use does not damage the phone.

| Guard | Behaviour | Configurable |
| --- | --- | --- |
| Desktop resolution | Your screen's size in the current orientation, capped at a 1600 px long side, 24-bit; resized live on rotation | No |
| Desktop text size | DPI-based, so type grows without the picture blurring | Yes (Compact / Normal / Large) |
| Stopping by itself | Smart (default): 25 minutes untouched, battery under 15 % off the charger, dangerous heat, or today's mobile data limit reached; or 1/2/4/6 hours; or Never. The home screen says when and why it last stopped | Yes |
| Speed | The desktop session runs with PRoot's seccomp accelerator; a first start that dies without a display falls back permanently to the plain mode | No |
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
- The AI desktop apps are previews on Linux. **Computer Use** is not offered on Linux by either
  OpenAI or Anthropic, and this app cannot change anyone's plan or usage limits. Claude Desktop's
  Cowork tab needs hardware virtualisation (KVM), which a phone container cannot provide.
- A self-signed sideload can still show an Android or Play Protect warning. Only distribution through
  Play review removes that reliably.
- x86 emulation is not included, so amd64-only Linux software will not run.

## Trusted downloads

- Ubuntu Base 24.04.4 ARM64 — `https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.4-base-arm64.tar.gz`
- Expected SHA-256 — `04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2`
- ChatGPT for Linux ARM64 — `https://persistent.oaistatic.com/codex-app-prod/linux/deb/latest/chatgpt_arm64.deb`
- Claude Desktop — Anthropic's apt repository, accepted only when the signing key matches the
  fingerprint `31DDDE24DDFAB679F42D7BD2BAA929FF1A7ECACE` that Anthropic publishes
- Firefox — Mozilla's own apt repository, because Ubuntu's `firefox` package is a snap shim

Downloads resume after a dropped connection and fail over to a second Ubuntu mirror. Every archive is
checked against the SHA-256 above before it is unpacked.

See `OPEN_SOURCE_NOTICES.md` for third-party licences.

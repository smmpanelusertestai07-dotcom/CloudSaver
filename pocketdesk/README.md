# PocketDesk

PocketDesk is a native Android app that runs a real Ubuntu 24.04 LTS ARM64 desktop on your phone,
with a built-in screen viewer, keyboard and mouse support. It is built for people who code from a
phone because they do not have a PC.

No WebView, no cloud PC, no subscription. Everything runs locally on the device.

## What you get

| Area | Detail |
| --- | --- |
| Linux | Ubuntu 24.04.4 LTS ARM64, SHA-256 verified, running under PRoot |
| Desktop | Openbox, tint2 panel, LXTerminal, PCManFM file manager, TigerVNC |
| Viewer | In-app RFB 3.8 client bound to `127.0.0.1:5901`, capped at 1280×720 |
| Input | Touchpad and direct-touch modes, left/right click, two-finger scroll, USB and Bluetooth mouse, hardware keyboard, on-screen coding key row, Android clipboard bridge |
| Tools | `bash`, `git`, `curl`, `nano`, `sudo`, `apt` — install anything else yourself |
| Optional | OpenAI's official ChatGPT desktop package for Linux ARM64, which bundles Codex |

## Device requirements

- Android 10 (API 29) or newer, ARM64
- 4 GB RAM minimum; 6 GB or more is better for Electron apps such as ChatGPT
- At least 4 GB free storage before setup; the finished system uses 1.5–3 GB
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

There is **no** storage, camera, microphone, location, contacts, accessibility or overlay
permission, and the app never requests device admin.

Battery optimisation and OEM auto-start are *not* permissions — they are Android settings pages
that the Permissions card links to, so long sessions are not killed in the background.

## Phone health

The app is tuned so daily multi-hour use does not damage the phone.

| Guard | Behaviour | Configurable |
| --- | --- | --- |
| Desktop resolution | Capped at 1280×720, 24-bit, to limit RAM, bandwidth and heat | No |
| Auto-stop timer | Default 4 hours; 1/2/4/6 hours or Off | Yes |
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
- ChatGPT desktop for Linux is a preview from OpenAI. Its **Computer Use** feature is not offered on
  Linux, and this app cannot change ChatGPT or Codex plan and usage limits.
- A self-signed sideload can still show an Android or Play Protect warning. Only distribution through
  Play review removes that reliably.
- x86 emulation is not included, so amd64-only Linux software will not run.

## Trusted downloads

- Ubuntu Base 24.04.4 ARM64 — `https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.4-base-arm64.tar.gz`
- Expected SHA-256 — `04207713ece899c3740823d33690441ad3a7f0ded1101aca744e2b0f37ac7ff2`
- ChatGPT for Linux ARM64 — `https://persistent.oaistatic.com/codex-app-prod/linux/deb/latest/chatgpt_arm64.deb`

Downloads resume after a dropped connection and fail over to a second Ubuntu mirror. Every archive is
checked against the SHA-256 above before it is unpacked.

See `OPEN_SOURCE_NOTICES.md` for third-party licences.

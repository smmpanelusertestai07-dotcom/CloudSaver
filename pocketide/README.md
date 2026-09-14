# PocketIDE

**A real development environment that runs on your phone.**

Ubuntu 24.04 LTS · Visual Studio Code · any coding agent · no computer required.

PocketIDE puts a real Ubuntu ARM64 system inside an Android app, runs the open-source build of
Visual Studio Code on it, and lets you install any coding-agent extension from the Open VSX
registry — with Google's Antigravity, Anthropic's Claude Code and OpenAI's Codex set up in one
tap. Everything runs on the phone. No computer is needed at any point, including for set-up.

## Who it is for

Someone who wants to build software and does not own a computer.

Every serious way to work with a coding agent today assumes a laptop. The agent-first desktop
apps are desktop-only; the editor integrations are desktop-only; the terminal agents want a
machine you can leave running; and the phone apps those same companies ship are remote controls
— the work still happens on a computer somewhere. If you do not have one, you have nothing.
This app makes the phone that computer.

## What you get

| | |
|---|---|
| **Linux** | Ubuntu 24.04.5 LTS ARM64, from Canonical's own mirror, verified by SHA-256, under PRoot — no root, no virtual machine |
| **Editor** | code-server 4.137.0, which is Code-OSS (Visual Studio Code) 1.137.0 — MIT licensed, no Microsoft account |
| **Agents** | Antigravity, Claude Code and Codex, each the publisher's own extension from their own verified Open VSX namespace |
| **Anything else** | The whole Open VSX registry, verified publishers by default |
| **Tools** | git, python3, build-essential, ripgrep, jq, and whatever else `apt` has |
| **Staying current** | Ubuntu's security updates automatically; extensions by the editor; the editor itself when you ask |

## What you can build

Websites and web apps, Node and Python backends, APIs, scripts, bots, command-line tools, and
anything in Go, Rust, Java, C or C++ — write, compile and run, with git throughout.

Android APKs need an extra toolchain install. iOS builds go through GitHub's macOS runners,
which are free with no minute limit for public repositories — you write the app here and push.
Games work through Godot. What is not possible is compiling a native Xcode Swift project on the
phone: Xcode only runs on macOS, and no trick changes that.

### How big a project

The APK's own size is not the limit — a 200 MB APK is no harder to produce than a 2 MB one, it
is bytes through a zip. What costs memory is the compiler, and that is decided by how many
modules a project has, how many source files, how large its dependency graph is, and whether R8
has to rewrite the whole program at the end. A small app with two hundred dependencies is a
heavier build than a large app with ten.

Settings → The computer shows what your phone actually gives it — cores, memory, free space —
and the build heap and worker count worked out from them. Those same numbers are written into
`~/.gradle/gradle.properties` when the Android build tools are installed:

| Phone memory | Build heap | Modules in parallel |
|---|---|---|
| 12 GB or more | 3072 MB | 4 |
| 8 GB | 2048 MB | 3 |
| 6 GB | 1536 MB | 2 |
| 4 GB | 1024 MB | 2 |
| less | 768 MB | 1 |

What actually stops a build is memory — specifically Android taking it back. From Android 17
there is a per-app ceiling derived from the device's total RAM, applied whatever an app targets,
with no way to opt out; when it fires, the app reads Android's own record and says so rather
than leaving you to guess. Heat is second: a phone has no fan, so a long build slows down rather
than failing. Plugging the phone in and closing other apps are the two things that help most.

## Staying current

A pinned image is right on the day it is pinned and wrong a year later. Ubuntu ships a security
fix within hours of a CVE, and a machine that never runs `apt` never receives it.

- **Ubuntu's security updates** are taken automatically — on Wi-Fi, once a day, while the app is
  open and the editor is not. Security only, never a blanket upgrade.
- **Extensions** keep themselves current, by the editor, from Open VSX.
- **The editor** moves when you ask. code-server cannot update itself, so the app does it: the
  version and URL come from GitHub's release API over TLS, the bytes must match the size GitHub
  published, the archive must unpack into a runnable editor, and that editor must report the
  version that was asked for — only then is the installed one replaced, and a failed swap puts
  the previous one straight back.

Ubuntu 24.04 LTS has standard security support until June 2029. All of this runs only while the
app is open, because Linux only runs while the app is open — Android does not keep another
operating system alive behind a closed app.

## Requirements

- Android 10 or newer
- 64-bit ARM (arm64-v8a)
- About 1.4 GB free for the base and the editor, plus each agent
- An account with whichever agent you want to use

## Permissions

Internet, network state, a wake lock, notifications, a foreground service, vibration, and an
optional prompt to keep working when the battery saver would stop it. **No camera, microphone,
location, contacts, SMS, calendar or storage.**

## Privacy

No analytics, no telemetry, no account, no server. Your files live in this app's private storage
and are never synced, backed up or uploaded. The agents' models run in their own companies'
clouds, as they do everywhere; your files stay on the phone and edits happen on the phone.

## Build

```
./build.sh          # aapt2 + javac + d8 + apksigner. No Gradle, no network.
./tests/run-tests.sh   # 43 gates
```

Requires Android SDK platform 35 and build-tools 35.0.0. Every gate exists because of a specific
mistake that reached a phone; each one names the failure it guards against.

### Signing

Android refuses an update signed with a different key than the one already installed, and the
only way past that is uninstalling — which for this app means deleting the whole workspace. So
the key has to be the same one every time, and it has to live somewhere other than here: this
repository is public, and a signing key anyone can read is a signing key anyone can use to build
an "update" that installs straight over yours.

Create the key once and keep it:

```
keytool -genkeypair -v -keystore pocketide.jks -storetype JKS \
  -alias pocketide -keyalg RSA -keysize 4096 -validity 10950 \
  -dname "CN=PocketIDE, OU=PocketIDE, O=PocketIDE, C=IN"

base64 -w0 pocketide.jks
```

Then set four repository secrets — Settings → Secrets and variables → Actions:

| Secret | Value |
| --- | --- |
| `POCKETIDE_KEYSTORE_B64` | the base64 output above |
| `POCKETIDE_STORE_PASS` | the keystore password you chose |
| `POCKETIDE_KEY_PASS` | the key password you chose |
| `POCKETIDE_KEY_ALIAS` | `pocketide` |

Every build after that — in CI or on a laptop, where the same values go in `POCKETIDE_KEYSTORE`,
`POCKETIDE_STORE_PASS`, `POCKETIDE_KEY_PASS` and `POCKETIDE_KEY_ALIAS` — signs identically, and
each APK installs over the last.

Without them `build.sh` generates a throwaway key so that a first build works at all, and says
so twice while it does. That APK installs on a phone with no PocketIDE on it, and will not
install over an APK signed by any other throwaway.

## Licence

PocketIDE's own code is Apache-2.0. Everything it carries or downloads keeps its own licence —
see [OPEN_SOURCE_NOTICES.md](OPEN_SOURCE_NOTICES.md). PRoot is GPL-2.0; code-server and Code-OSS
are MIT.

Not affiliated with, endorsed by or sponsored by Microsoft, Google, Anthropic, OpenAI, Canonical,
Coder or the Eclipse Foundation.

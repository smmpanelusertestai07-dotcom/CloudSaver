# PocketAgent

> ## Signing key
>
> **This repository publishes the key its APKs are signed with**, at
> `.signing/pocketagent-local.jks`, with the password in `build.sh`. That is deliberate for a
> build nobody distributes yet — every build replaces the last one in place — and it has a cost
> worth stating plainly: anyone can sign an APK that Android will accept as an **update** to this
> app, and an update inherits the workspace and every account signed in inside it.
>
> Install only from a copy you trust. If this app is ever handed to people who are not the
> author, the key must be rotated out of the repository into a secret first.


A native Android app that runs coding agents on the phone itself and shows you **their own**
interface instead of one it drew.

- **Application ID** `com.pocketagent.doors` — installs beside PocketAgent 14.1.5, does not
  replace it.
- **Version** 15.1.5, code 500. Targets API 35, needs API 29, ARM64 only.
- **Build** `ANDROID_SDK_ROOT=… bash build.sh` — aapt2, javac, d8, apksigner. No Gradle.
- **Test** `bash tests/run-tests.sh`

## The idea

There are three ways into an agent, and each publisher has opened a different one:

**Door A — Remote Control.** A headless daemon runs in the phone's Ubuntu; the publisher's own
web or phone app is the screen. Google and Anthropic both use this name for it. One process on
the phone, and nothing else.

**Door B — Extension Host.** `code-server` runs in the phone's Ubuntu with the publisher's own
VS Code extension inside it. OpenAI publish `openai.chatgpt` and Anthropic publish
`anthropic.claude-code` on Open VSX, both with linux-arm64 builds. The interface is theirs; the
editor, terminal and diff around it are real. No X server, no Electron, no video stream.

**Door C — Desktop app.** The publisher's whole application on an X display, watched through a
viewer. The heaviest door, and the only one Cursor has.

## What this app is, and is not

It is the machine, the set-up, the process supervision, the notification with a stop button, and
the row of keys a phone keyboard lacks.

It is not an agent interface. It draws one screen of its own — the list of agents — and that
screen exists mainly to say which door each one came through and whether it has ever worked here.

## Honesty

Doors A and B are documented by their publishers and have not been run on a phone under PRoot.
The app records what happens the first time anyone opens each one, shows the daemon's own output
when a door fails, and marks every untried agent as untried. `tests/run-tests.sh` fails the build
if any agent is marked proven.

## Layout

```
app/assets/doors-bootstrap.sh      Ubuntu, Node, git -- and nothing a desktop would need
app/assets/doors-antigravity.sh    Door A
app/assets/doors-codeserver.sh     Door B
app/src/.../Doors.java             the catalog: which agent, which door, what it costs
app/src/.../Ubuntu.java            download, verify, unpack, run under PRoot
app/src/.../DoorService.java       keeps one daemon alive, visibly
app/src/.../DoorActivity.java      the publisher's page, full screen
app/src/.../KeyBar.java            Escape, Tab, arrows, Ctrl, / and @
app/src/.../Probe.java             what actually happened, remembered
```

Brand assets, the PRoot binaries and `TarGzExtractor` are shared with PocketAgent; everything
else here is new.

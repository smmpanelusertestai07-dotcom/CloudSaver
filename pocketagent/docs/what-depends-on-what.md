# What each feature depends on

A fair question about this app: if PocketAgent drives the official engines, why does one of them
have voice and another not? Why is a feature in a company's desktop app but not in the thing
PocketAgent runs?

The answer is that "the CLI" is not one thing, and not every feature lives in the same place.
There are three layers, and every feature in this app sits on one of them.

## The three layers

**1. The engine.** The program PocketAgent installs into the Ubuntu workspace and talks to:
`codex`, `claude`, `cursor-agent`. It holds the model loop, the tools, the file edits, the
sandbox and the approvals. PocketAgent speaks to it over a protocol — the Codex app server's
JSON-RPC, or ACP for Claude and Cursor — the same protocol the vendors' own editor extensions
speak. Anything the engine exposes over that protocol, PocketAgent can have.

**2. The account server.** The vendor's own service, reached over the network by the engine
itself using the credentials from your sign-in. Models, rate limits, plan entitlements, usage
counters and anything with "realtime" in the name live here. PocketAgent never holds your
password or token; the engine does, and PocketAgent asks the engine.

**3. The phone.** Android itself: the microphone, the speaker, the camera, notifications, the
file picker, biometrics, the screen. Nothing to do with any vendor. PocketAgent owns all of it.

The confusing cases are always features that look like one layer and actually live in another.

## The voice question, specifically

Three different things get called "voice", and they are on three different layers.

**Codex.** The CLI briefly had hold-space dictation of its own; that was removed, and voice moved
into the Codex desktop app and ChatGPT. If PocketAgent needed that CLI feature, it would have
nothing. But it does not: the Codex **app server** — the same interface that powers the official
VS Code extension — exposes a realtime voice session directly (`thread/realtime/start`, `/sdp`,
`/transcript/delta`, `/stop`). PocketAgent negotiates WebRTC through those methods, and the
engine holds the ChatGPT authentication and runs the tools. So Codex voice in this app is not a
copy of the desktop app's feature; it is the same layer-2 service the desktop app uses, reached
the way the extension reaches it.

**Claude Code.** `/voice` is push-to-talk inside Claude Code's own terminal interface — a layer-1
feature of the TUI. PocketAgent does not run the TUI; it runs the engine headlessly and speaks
ACP, and a terminal keybinding is not something a protocol can expose. So `/voice` is genuinely
unavailable here.

**Cursor and Antigravity.** Neither publishes a voice feature at all, in any interface.

**What PocketAgent does about it.** Dictation and read-aloud in this app are layer 3: Android's
own speech recogniser and text-to-speech, which work identically for all four agents, offline
capability depending on the phone. That is why you can talk to Claude here even though `/voice`
is not reachable — you are not using Claude's voice feature, you are using the phone's.

The general rule this illustrates: **a feature that lives in a vendor's own user interface is
unreachable; a feature that lives in the engine's protocol or on the account server is reachable.**
Desktop-app-only is not a verdict on PocketAgent — it is a statement about which layer the
vendor put the feature on.

## Feature by feature

| Feature | Layer | Why, concretely |
| --- | --- | --- |
| Sending a prompt, streaming the reply | Engine | The protocol's core method. Every agent has it |
| File edits, diffs, approvals | Engine | The engine performs the edit and reports it; PocketAgent renders and approves |
| Running commands, tests, a dev server | Engine | It runs in the Ubuntu workspace on the phone. No cloud machine anywhere |
| Model picker | Account server | The engine asks what your plan allows; PocketAgent lists what comes back |
| Usage and rate limits | Account server | Token counts arrive in the engine's own events; limits are the account's |
| Sign-in | Account server | The engine performs its own device or browser sign-in and keeps the credential |
| Free plans | Account server | Antigravity and Codex include their CLI free; Cursor's free tier is limited; Claude Code has no free tier. Nothing PocketAgent can change |
| MCP servers, skills, slash commands | Engine | Configuration the engine reads; PocketAgent writes the config and the engine honours it |
| Codex voice | Account server via engine | `thread/realtime/*` on the app server, not the removed CLI dictation |
| Claude Code `/voice` | Vendor's own interface | A keybinding in their terminal app. Not exposed to any protocol, so not reachable |
| Dictation and read-aloud for all four | Phone | Android speech recognition and text-to-speech. Works the same for every agent |
| Notifications when an agent needs you | Phone | Android notifications, driven by the engine's events |
| Photos, screenshots, files into a chat | Phone | Android's picker and camera; the bytes go to the engine as attachments |
| App lock, biometrics | Phone | Android's own prompt. Nothing leaves the device |
| The Linux desktop and its apps | Phone | An Ubuntu 24.04 ARM64 userspace under PRoot, on the phone's own storage |
| Antigravity | Not connected yet | Its CLI streams its own event format rather than a protocol this build speaks, and the adapter is not written. Ours to do, not a refusal by Google |

## The short version

If you can point at a feature in a vendor's **editor extension or their API**, PocketAgent can
have it, because that is the same protocol this app speaks. If a feature only exists inside the
vendor's **own app or terminal**, it cannot be reached from here — but very often the phone can
do the same job better, which is what the dictation in this app is.

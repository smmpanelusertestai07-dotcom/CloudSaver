# PocketAgent Doors 15.0.0 — the app stops drawing agents

Version **15.0.0**, code **500**, application ID `com.pocketagent.doors`.

This installs beside PocketAgent 14.1.5 rather than over it. The one that works today keeps
working while this one is being proved.

## What changed, in one sentence

PocketAgent no longer draws an agent's interface. It runs the agent and hands you the interface
its own publisher ships.

## Why

Every screen this app drew by hand was a screen that went stale the moment a publisher shipped
something new, and every one of them had to be designed again. Meanwhile all four publishers
already ship an interface for exactly this: OpenAI and Anthropic as VS Code extensions with
linux-arm64 builds on Open VSX, Google as a Remote Control dashboard driven by a headless
daemon. Using theirs means their features, their updates, and none of our design work.

## The three doors

| Door | What runs here | What you see |
| --- | --- | --- |
| **A · Remote Control** | one daemon | the publisher's own web or phone app |
| **B · Extension Host** | code-server and the extension | the publisher's own extension, with a real editor and terminal around it |
| **C · Desktop app** | the whole application on a display | the publisher's own application |

Door C is not wired up in this build. It is what PocketLinux already does, and it is where
Cursor will have to come through, because Cursor publishes no headless mode and no extension.

## What this build actually does

- Downloads Ubuntu 24.04.4 ARM64, verified against its published digest, resuming if the signal
  drops. About 30 MB, and roughly 1.2 GB once an agent is installed.
- Installs Node 22, git, curl and python3 — and nothing else. No X server, no window manager,
  no browser, no viewer.
- **Antigravity** through Door A: fetches Google's CLI, verified by sha512, and starts
  `agy remote-control start`. There is no systemd inside PRoot, so it tries Google's own service
  path first and falls back to running the daemon in the foreground under the app's supervision.
  Which of the two happened is written down and shown.
- **Codex** and **Claude Code** through Door B: fetches code-server, installs the publisher's
  own extension from Open VSX, and serves it on the loopback address only.
- A keyboard row above the page for the keys a touch screen has no way to reach — Escape, Tab,
  the arrows, Ctrl combinations, and the slash and at-sign every one of these agents is driven by.

## The honest part

Doors A and B are documented by their publishers and **have never been run on a phone under
PRoot**. This build does not claim they work. Each agent shows "untried" until someone opens it
here, and then shows what actually happened — including the daemon's own last words when it
failed. That record is the point of this release.

Twice during the research for this, something documented turned out not to exist:
`cursor serve-web` is in Cursor's CLI with no binary behind it, and `antigravity serve-web` is
in Antigravity's help with no binary behind it. So nothing here is asserted from documentation
alone.

## What is known not to work

- **Cursor.** No headless mode — their own answer. No published extension. Door C only.
- **Codex's own phone interface.** Its worker must be a Mac.
- **Claude Code on a free plan.** Anthropic publishes none.
- **Two agents at once.** This phone has under four gigabytes.

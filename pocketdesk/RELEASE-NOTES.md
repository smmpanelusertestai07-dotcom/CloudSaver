# PocketDesk 3.1.0 — ChatGPT opens; the finishing release

**ChatGPT's desktop app opens on the Realme C25s** — `window ready-to-show`, Codex CLI initialised,
the sign-in screen on the phone. The last fault was the GPU: on this Chromium, `--disable-gpu` alone
makes GPU access "denied", and ChatGPT's error reporter turns that into a fatal unhandled rejection
before any window exists. ChatGPT now gets SwiftShader — the software GPU its own package ships —
via `--use-gl=angle --use-angle=swiftshader`, so access stays allowed and rendering runs on the CPU.
Claude, which never asks, keeps its proven flags.

## The keyboard no longer wrecks the desktop
Opening the keyboard shrank the window, the Linux desktop was resized to the sliver above it, every
app relaid out, and a tap on a text field landed elsewhere — then it all reversed on close. The
viewer window now keeps its size (`adjustNothing`); the view slides up just enough to keep the
pointer above the keys, as a phone screen scrolls to a text field, and slides back on close.

## Touch and mouse
- Two fingers scroll unless the view is clearly zoomed in (a hair of overflow used to turn every
  scroll into a pan — the reason landscape "would not scroll"); lighter scroll steps.
- A ring marks where a finger tap landed; rotation returns to 100 % and re-centres.
- Toolbar grouped and named: **View** (− % + · Fit/Fill screen · Rotate · Full screen) and
  **Input** (Mouse/Finger · Keyboard · Paste). Fit and Fill say what they do when toggled.
- Desktop icons larger (64 px), panel launchers larger.

## New in the app
- **Your phone is compatible** — a live check on the home screen: ARM64, Android version, RAM,
  free space, with the requirements in words. Android 10 and every version after, any brand.
- **Mobile data limit per day** — No limit / 250 MB / 500 MB / 1 GB / 2 GB / 5 GB; downloads and
  installs stop at the limit on mobile data; resets at midnight; Wi-Fi never limited; today's use
  shown on the row.
- **Downloads visible to the phone** — on (Files app can see them) or off (kept inside the Linux
  computer only); files are moved, never deleted.
- **App lock** — the phone's own fingerprint or PIN when PocketDesk opens; no separate password.
- Six more answers in the app: offline use, what installs from the browser and what does not, when
  the computer stops by itself and what is kept, accounts and locks, data limits, which phones.
- The card is **AI coding & desktop apps**, described as the industry's leading tools installed the
  official way, with a note that anything else can be installed from the browser inside Linux.
- The APK is simply `PocketDesk-<version>.apk`; ARM64 is checked in the app instead.

---

# PocketDesk 3.0.0 — final

The finished app: an Ubuntu desktop on the phone, the AI desktop apps on it, and an honest
account of everything in between.

## What is in it

- **AI desktop apps** — ChatGPT (with Codex), Claude Desktop (with Claude Code), Cursor,
  Antigravity — each from the vendor's own current build; the same row updates in place, login
  and settings kept.
- **A desktop that behaves** — real icons, short names (Browser, Files), a Linux penguin, a
  12-hour Indian clock, close/minimise/maximise on every window, and a **Windows** menu: open
  windows, minimise all, close all.
- **Touch like a phone** — tap clicks, swipe scrolls, hold is right-click; Mouse mode for
  precise dragging; a solid, draggable Controls chip.
- **Smart stopping** — ends a desktop nothing has touched for 25 minutes or one below 15 %
  battery off the charger, and says which. Fixed hours and Never remain.
- **Downloads reach the phone** — `Android/data/com.pocketdesk/files/Shared/Downloads`.
- **Privacy answered in the app** — everything local, where logins live, exact paths, the full
  permission list and what is absent, what uninstalling deletes.
- **Nothing fails silently** — toasts on start, progress with free memory, a dialog naming the
  reason on death, ChatGPT's own log (`~/.local/state/codex/logs`) folded into the report, and a
  one-tap **Why an app didn't open** row that shares it all.

## ChatGPT on a 4 GB phone

Four faults were found and removed in order — the Chromium sandbox, a GPU flag that denied
access outright, stale single-instance locks, and a half-started instance that made every new
tap "succeed" into nothing. On phones with 4 GB or less ChatGPT now starts straight in the
single-process mode, with the ordinary mode as the retry; Claude is untouched. If it still does
not draw, the report now carries ChatGPT's own startup log, which names the step it stopped at.

---

# PocketDesk 1.6.0

The desktop apps are the point of this, so this release is about making the Electron ones start,
and about never again being unable to see why one did not.

## Fewer processes, because PRoot charges for every one

Chromium normally runs five or more processes: browser, zygote, GPU, renderers, utility. PRoot
traces every syscall of every one of them, so a cold start that takes six seconds on a laptop can
take minutes on a phone — which is exactly the "I waited three minutes and nothing opened"
symptom. Every Chromium-based app is now started collapsed down to the fewest processes it can
run with:

`--no-zygote --in-process-gpu --renderer-process-limit=1 --disable-gpu --disable-gpu-compositing
--disable-software-rasterizer --ozone-platform=x11`, on top of the sandbox flags from 1.5.0.

Two things the session was missing are also set now: **`XDG_RUNTIME_DIR`**, which Electron and GTK
both look for and fall back to slow paths without, and **`/dev/shm`**, which Chromium wants to
exist even when told not to depend on it.

## One tap to see why an app didn't open

New row on the home screen: **Why an app didn't open**. It shows the report Linux wrote the last
time you tapped an app — free memory at launch, the exact command, everything the app printed, and
whether a window ever appeared — and it can be shared. No file manager, no terminal.

The launcher no longer claims success at twelve seconds because a process is alive. It watches for
a real window for 150 seconds, says how it is going at thirty-second marks with the free memory,
and writes the outcome to the report either way. Where `xdotool` is not installed it says the
window state is unknown rather than guessing.

---

# PocketDesk 1.5.0

ChatGPT opens, the browser opens in a couple of seconds, every app row carries the vendor's own
logo, and the floating **Controls** chip is solid and can be dragged anywhere.

## Why ChatGPT did nothing when you tapped it

Unpacking OpenAI's own `chatgpt_arm64.deb` shows what it installs: `/usr/bin/chatgpt` is a symlink
to a two-line script that runs `/usr/lib/chatgpt/ChatGPT` — a Chromium build — with **no flags at
all**. Chromium's sandbox is built on Linux user namespaces, and a container running under PRoot
cannot create one. So Chromium failed its own sandbox check and exited before drawing a window:
the tap really did nothing, and there was nothing on screen to say why. Claude Desktop, VS Code and
Antigravity all fail the same way for the same reason.

**Every launcher now goes through `pocketdesk-open`**, a small script that:

- notices a Chromium-based app by the files Chromium keeps beside its binary
  (`chrome_100_percent.pak`, `v8_context_snapshot.bin`, `libvk_swiftshader.so`, `resources/app.asar`)
  and adds `--no-sandbox`, `--disable-setuid-sandbox`, `--disable-gpu-sandbox`,
  `--disable-dev-shm-usage`, `--disable-gpu` and `--password-store=basic`. Nothing is added to apps
  that would not understand the flags;
- shows **"Opening ChatGPT…"** as soon as you tap, so a slow first start looks like a slow start;
- writes everything the app printed to `~/.pocketdesk/logs/<app>.log`, and if the app dies within
  twelve seconds **puts the reason on screen** instead of leaving you guessing.

`--password-store=basic` matters as much as the sandbox flags: without it an Electron app waits on a
keyring service that does not exist here, which also looks like "it never opened".

This reaches an existing container automatically — the scripts are rewritten every time the desktop
starts, so there is nothing to reinstall.

## A browser that opens straight away

Firefox works now, but it takes several seconds to appear on a phone. The default browser is now
**GNOME Web** (Epiphany): about 150 MB instead of 350 MB, and it opens in a second or two. It is
set as the handler for links, and it downloads into **Downloads** like everything else.

WebKit sandboxes its web process with bubblewrap, which needs the same namespaces Chromium wants,
so the session exports `WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1` along with
`WEBKIT_DISABLE_COMPOSITING_MODE` and `WEBKIT_DISABLE_DMABUF_RENDERER` for software rendering.

**Firefox is now its own row** in the app list, for anyone who wants it, with its slower start
stated up front.

## Real logos, from the vendors' own packages

Every app row now shows the real mark, taken from the package that installs the app rather than
redrawn or guessed:

| App | Where the logo comes from |
| --- | --- |
| ChatGPT | `usr/share/pixmaps/chatgpt.png` inside OpenAI's `chatgpt_arm64.deb` |
| Claude | Anthropic's Claude app icon |
| Antigravity | `antigravity.google` |
| VS Code | `code.visualstudio.com` |
| Firefox | `firefox.com` |
| GNOME Web | `epiphany-browser-data`, rendered from the shipped SVG |

They are drawn in full colour at their own size, not tinted to match the interface.

Inside Linux the same rule holds: launchers keep each package's own `Icon=` name. Antigravity ships
as a plain tarball that registers nothing, so installing it now also writes a proper desktop entry
and icon — before this it installed successfully and then appeared nowhere.

## The Controls chip

While the bars are hidden this chip is the only way back to them, so it is no longer see-through:
solid background, a light border and a shadow. **It can now be dragged anywhere on screen** — press
and move it, tap it to bring the bars back. It stays inside the screen when you rotate.

## Messages inside Linux

The container now has a notification daemon (`dunst`), `notify-send` and `zenity`, so the desktop
can actually tell you something: a toast when an app is starting, a readable dialog when one fails.
If those are missing (a container from an older version), the report opens in a terminal window
instead — never silence.

## Tests

`tests/desktop-scripts-test.sh` runs the real scripts against a fake app tree and asserts that a
Chromium-layout app is launched with `--no-sandbox` and a plain one is not, that the exit code and
log survive a failure, that launchers route through the wrapper, that `%U` placeholders and extra
`Desktop Action` groups are dropped, and that `NoDisplay` entries stay out of the menu. Removing
`--no-sandbox` from the launcher fails the suite.

Six suites, all passing.

---

# PocketDesk 1.4.0

The browser works, apps carry their own real logos, and the desktop reads like a small PC rather
than three oversized windows.

## Firefox stopped crashing

Every tab died with "Gah. Your tab just crashed." Firefox isolates each tab in a sandboxed content
process, and PRoot cannot give that process the isolation it asks for — so it fails closed and
takes the tab with it. Fixed on both sides:

- The session exports `MOZ_FAKE_NO_SANDBOX`, `MOZ_DISABLE_CONTENT_SANDBOX`, and the GMP, RDD and
  socket-process equivalents, plus `ELECTRON_DISABLE_SANDBOX` for the AI apps, which fail the same
  way.
- A Firefox profile is created with `security.sandbox.content.level=0`, `fission.autostart=false`,
  `browser.tabs.remote.autostart=false` and `dom.ipc.processCount=1`, so pages render in the main
  process instead of a sandbox that cannot exist here. Software rendering is forced, telemetry
  prompts are off, and downloads go to **Downloads**.

## Real logos, real launch commands

The desktop used hand-made entries with a generic icon, which is why everything was the same blue
diamond and why ChatGPT would not open — the entry guessed at a command instead of using the one
its packager wrote.

The desktop now builds itself from the `.desktop` files the packages actually install: real name,
real icon, real `Exec`, for every installed application. Nothing is hand-maintained, so nothing can
drift. `%U`-style placeholders are stripped and hidden entries skipped, the right-click menu lists
everything installed, and the panel and desktop show the useful ones first.

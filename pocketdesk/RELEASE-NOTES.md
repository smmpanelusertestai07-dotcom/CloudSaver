# PocketDesk 3.3.0 — Linux only, on purpose; sound; a browser with extensions; installs beside a running desktop

The virtualisation test is gone from the home screen, and in its place the app says, in checked
facts, why the computer is Linux and only Linux. Everything else in this release is what the
final list asked for, plus what a computer is expected to have and did not yet: sound, a real
browser choice, a terminal on the desktop, app installs that do not wait for the desktop to be
stopped, and a viewer that no longer tears.

## Linux only, on purpose (the facts, confirmed 2 September 2026)
- **Every AI desktop app here ships for Linux.** OpenAI released the ChatGPT desktop app for
  Linux (with Codex) as a public preview on 11 August 2026 for Ubuntu 24.04 LTS and 26.04 LTS,
  Debian 13 and Fedora, on x64 and ARM64. Anthropic released Claude Desktop for Linux (with
  Claude Code) as a beta on 30 June 2026 for Ubuntu and Debian, x64 and ARM64, from its own apt
  repository. Cursor publishes Linux ARM64 .deb and AppImage builds. Google publishes Antigravity
  for Linux from its own apt repository, arm64 included.
- **Why not Windows or macOS:** both need a virtual machine, and Android keeps hardware
  virtualisation away from apps; emulation would be ten to fifty times slower; macOS is licensed
  only for Apple hardware. Wine can run small native ARM64 Windows programs on Linux, not the
  Windows editions of these AI apps, which are the same programs as their Linux editions anyway.
- **Ubuntu 24.04 LTS** is supported by Canonical until April 2029 (2034 with Ubuntu Pro).
- Written in a new Home card, **Linux only, on purpose**, with the answers opening in place; the
  Windows/macOS question and the honest limits were rewritten to hold only what is permanent.

## What was wrong, and what changed
- **Antigravity did not install.** The installer scraped Google's download page for an ARM64
  link; the page is drawn by a script and names none. Google runs an apt repository that
  publishes arm64 builds (1.23.2 at the time of writing); Antigravity installs from it now and
  updates in place like the others.
- **The Apps tab went grey while the desktop was open.** Installs waited for the desktop to be
  stopped because both shared one worker. An install now runs beside an open desktop in its own
  process and the new app appears on the running desktop when it is done.
- **The screen tore.** The viewer painted while an update was still landing. Updates now land
  in a back copy and are blitted to the screen copy in one go when complete (the RFB update
  boundary is now a callback), so a frame is never half old and half new.
- **The arrow in Finger mode.** The desktop no longer paints its own pointer into the picture
  (Cursor pseudo-encoding negotiated); Finger mode draws a hand at the pointer, Mouse mode draws
  the pointer the desktop reports (I-beam over text, hand over a link) or an arrow.
- **Finger scrolling stopped when the finger did.** A fast swipe now keeps scrolling and slows
  down, the way a phone page does.
- **No sound, and the volume keys did nothing.** PulseAudio plays into a virtual output and
  streams it to the phone, which plays it through the speaker while the desktop screen is open.
  The volume keys set the media volume there now, and Screen ▾ has Volume up / down.
- **"ChatGPT goes back by itself."** Nearly always the phone taking memory back. An AI app
  started with under 900 MB free now closes the browser's windows first and says so; a desktop
  that Android ended is noticed at the next open and written down on the Home tab with the time
  (a heartbeat while it runs); a display that ended on its own is written down with its exit
  code. Settings → Reports still shows how each app ended.
- **Every GTK app and web page waited on the accessibility bus** and logged it; switched off.
  GNOME Web no longer tries to save passwords through a keyring that does not exist. IPv4 is
  preferred, so a phone network with an unroutable IPv6 address no longer leaves pages loading.
- **"Android 10 and newer" read as gibberish** on the home screen; every requirement sentence
  now says "works on Android 10 and above".
- **Open dialogs with an OK button** for the compatibility detail, free space and heat are gone:
  the text opens under the row, like the questions.

## The desktop
- **Controls at the bottom by default** (Screen ▾ moves them). Window ▾ gains **Apps menu**,
  **Phone files** and **Reload the screen**.
- **Ubuntu 24.04's own wallpaper**, Tux on the panel's new **Apps** button (the whole app list,
  also on Super+A and on a right-click or long press on the wallpaper), **Phone files** with a
  phone-with-a-folder icon on the desktop and the panel, **Terminal** on the desktop and the
  panel, and the window commands, Reload screen and Refresh app list in the menu.
- **Browser choice:** Brave (Chromium with Chrome Web Store extensions, Brave's official arm64
  build, its Rewards/Wallet/VPN/AI switched off by policy) and Firefox (Mozilla's own arm64
  build) are one-tap installs on the Apps tab under **Computer basics**; whichever is installed
  becomes the computer's browser, on the desktop, the panel and for every link and sign-in,
  through the launcher so it gets the sandbox flags a container needs. GNOME Web stays as the
  light default. **Developer tools** (compilers, Python, Node.js, Git extras, SSH, vim, htop) is
  a row too.
- The launcher leaves a browser its extensions and background updates, and never closes a
  browser to make room for itself.

## The home screen
- Tux on the computer card and on the launch screen (Android 12 and up), with Larry Ewing's
  credit in Settings and the notices.
- The Apps tab names things by what they are: two AI assistants (ChatGPT with Codex, Claude
  Desktop with Claude Code) and two AI coding environments (Cursor, an AI code editor; Antigravity,
  Google's agentic development platform), then Computer basics.
- The APK is named like CloudSaver's: `PocketDesk-v3.3.0-release.apk`.

## Not done, and why
- **Windows or macOS on the phone:** not possible; the plan file's Wine/Hangover route runs
  small native ARM64 Windows programs, not Electron-based AI apps, and there is no route to macOS.
- **A microphone into the computer** (voice input): not carried yet; sound out only.
- **Kotlin:** the app stays in plain Java. A rewrite changes nothing the phone can see and would
  cost every tested behaviour its history; the build stays Gradle-free and reproducible.

# PocketDesk 3.2.0 — the last update: every item of the final prompt, done

The release that closes the list. The home screen is three tabs on a bottom bar, the desktop
screen is one row of controls, and the reasons behind "the keyboard types elsewhere", "ChatGPT
closed by itself", "text copies itself" and "the close button is off screen" were each found in
the code and removed, not worked around.

## What was actually wrong, and what changed

- **Keyboard typed in the wrong place / dropped letters.** The viewer took focus on every touch,
  so the phone keyboard was restarted against a bare fallback connection each time the desktop
  was tapped. The viewer no longer takes focus. Keyboards that hold a word as "composing" text
  (Gboard with suggestions, Samsung, SwiftKey) are now mirrored keystroke by keystroke, so what
  Linux shows is what the keyboard shows. **Finger** (tap where you touch) is the default pointer
  mode and is remembered; Ctrl, Alt and Super let go after the next key, like Shift on a phone.
- **ChatGPT closed by itself.** The launcher looked for a window *classed* "chatgpt"; ChatGPT's
  window is not, so a second tap on its icon saw "no window", called the running app a leftover
  and ended it. Windows are now matched by the process that owns them (wmctrl lists every window
  with its pid). A tap on an open app brings its window to the front and does nothing else.
- **Text copied itself in touch mode.** The display server forwarded every X11 highlight (the
  PRIMARY selection) to the phone as a copy, and Android 13 showed "Copied" each time. Started
  with `-SendPrimary=0`: only a real copy reaches the phone.
- **Desktop off screen in portrait, close button hidden.** The desktop was born landscape and the
  viewer defaulted to Fill (crop) until the resize landed, cutting both edges — and the X with
  them. Now: the desktop is born in the phone's current orientation, Fill mode is gone (the whole
  desktop always fits; zoom in from there), and the close, minimise and maximise buttons sit at
  the left edge of the title bar, where a maximised window always starts. The window rules are
  rewritten on every start, so containers built by earlier versions get them too.
- **Landscape would not scroll; Mouse mode could not drag.** Two fingers always scroll now (at the
  fingers, several notches for a fast swipe, in the phone's direction); when zoomed in, the view
  follows the arrow instead of pan stealing the gesture. Tap-then-press-and-move drags.
- **App lock did nothing useful.** It covered the home screen only, with a two-minute grace. It
  now covers the desktop too, shows a locked screen with an Unlock button (Cancel does not close
  the app), falls back to the phone's PIN screen if the fingerprint prompt cannot run, re-arms
  whenever the app leaves the foreground, and proves itself with one prompt when switched on.
- **Slow.** PRoot's seccomp accelerator was switched off since 1.0.0; the desktop session now runs
  with it (most of the difference between a desktop that lags and one that does not), and the
  first start that fails without a display falls back permanently. Chromium apps skip animated
  scrolling; the browser runs with no compositor, no WebGL and no smooth scrolling.
- **Sign-in never came back to the app.** The table that tells the browser which app answers
  `chatgpt://`, `codex://` and `claude://` links was never built. `desktop-file-utils` is installed,
  the table is rebuilt from the installed packages on every start.

## The home screen: three tabs
- **Home** — the Linux computer's state (with when it last opened and, if it stopped by itself,
  when and why), Open desktop, **Needs attention** (only while something is wrong, each row opens
  the fix), **Mobile data today** (a meter, while a limit is set), Your phone with **Your phone is
  compatible**, and **Privacy and your questions** with the answers opening under the question.
- **Apps** — the four **AI desktop apps** (ChatGPT and Claude for everyday work, Cursor and
  Antigravity for building software; the makers' own Linux apps, from the companies leading AI),
  and **Anything else, from the browser**: what installs and what does not, short.
- **Settings** — grouped: Appearance, Running, Data and files, Privacy and safety, Permissions,
  Reports, Storage, with a footer saying nothing here deletes anything. A dot on the tab only for
  what Settings can fix. **Why an app didn't open** stays, in Reports, until testing is finished.

## The desktop screen: one bar
Home · **Linux computer** (tap for details) · **Screen ▾** (Fit, Zoom in, Zoom out, Rotate,
Full screen, move the controls to the top or the bottom) · **Finger/Mouse** (hand or arrow) ·
**Keyboard** · **Keys** (shows the Esc/Tab/Ctrl/arrows row only when wanted) · **Window ▾**
(Close, **Force close** for a stuck app, Switch, All windows, Minimise all, Paste from the phone).
Zoom never goes below 100 %, because 100 % is already the whole desktop; − says so.

## Your phone's files, inside the computer
- **Phone files** (Settings → Permissions, off by default): with Android's All files access
  allowed, the phone's storage is the **Phone** folder inside the Linux computer. ChatGPT's
  attach dialog, the browser's upload dialog and Files list **Phone**, **Phone Downloads**,
  **Phone Photos** and **Phone Documents** in the left-hand list, beside Downloads and Projects:
  computer files or phone files, the choice is there every time. A Phone icon on the desktop
  and Super+P open the folder. With it off the computer cannot see a single file on the phone.

## Found by review before release
- The manifest never declared `USE_BIOMETRIC`, so the fingerprint prompt threw and only the PIN
  screen could ever appear; and that fallback dropped the caller, so the App lock switch could
  not be turned on. Both fixed; the switch proves itself with one prompt when turned on.
- A sign-in link now goes through the launcher: it is handed to the running app, and the
  browser that carried it closes itself two seconds later, giving the app back the memory.
- The launcher stays with an AI app until it ends and writes how it ended (Settings → Reports):
  a kill by the phone for memory is named as such, on screen and in the log.
- Super+F4 (Force close) clashed with Openbox's own "go to desktop 4": the default desktop keys
  are removed and there is one desktop. Force close refuses the wallpaper and the panel.
  Ordinary programs (Browser, Files) are never mistaken for "already open".
- Smart stopping's battery floor is now enforced at the door: below 15 % on battery the desktop
  says why it will not open instead of opening and stopping thirty seconds later.
- The mobile data limit counts mobile bytes only (Wi-Fi never fills the meter); a stop by the
  monitor keeps its reason; a portrait start is a portrait desktop, not a clamped 800×1200;
  the compatibility-mode fallback is remembered only when it actually worked and is cleared by
  Setup; the desktop log keeps both attempts; "Last opened" is written only when the desktop
  really opened; GNOME Web runs one web process (page opens without a 150 MB process start).

## Also
- Sizes in decimal, like Android's Settings; requirements from one set of constants, and the
  tests check the app's minimum Android version against the build's.
- Today's mobile data limit now also stops the running desktop, not only downloads.
- A long-press shortcut on the app icon: **Open desktop** (the lock still asks first).
- Windows reach the notch on Android 10–14; content stays a readable width on tablets; every
  tappable thing is at least 48 dp.
- Apps: `Close all` in the desktop menu ends a stuck app after three seconds; Super+F4 force-closes
  the window in front.

---

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

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

## A PC-sized desktop

The default density drops from 168 dpi to **120**, so noticeably more fits on screen — the point of
a PC-like layout. The setting is now **Compact · PC-like (96)**, **Normal (120)** and **Large (144)**.

## Touch

- **One tap opens an icon.** PCManFM was in double-click mode, a mouse convention that a finger
  should not have to imitate.
- The touchpad pointer is drawn as **an arrow with a dark outline**, not a floating ring, so it
  stays readable over the wallpaper.

## ChatGPT

The package is genuine — `Package: chatgpt`, `Maintainer: OpenAI`, arm64, and its `postinst`
registers OpenAI's own signed apt repository. Two things were wrong on our side:

- It needs **1.3 GB installed** on top of a 700 MB download; the free-space check asked for 2.5 GB
  and now asks for 4 GB, with the row stating both figures.
- Updates now go through `apt-get --only-upgrade` via the repository the package registered,
  instead of re-downloading 700 MB every time.

## Verified in this build

- All five checks pass
- The ChatGPT package's control metadata was read directly from the published `.deb` to confirm
  its name, architecture, installed size and repository behaviour
- javac against API 35 (min 29), D8, zipalign, APK Signature Scheme v3, `aapt2 dump badging`

# PocketDesk 1.1.1

Fixes the crash behind "PocketDesk keeps stopping" on the desktop screen, and answers "what is
happening and how long will it take" during installs.

## The crash

The desktop viewer allocated a fresh multi-megabyte pixel array for every screen update — 4.6 MB
per full frame at 1600×720. With apt working in the background, one of those allocations hit
`OutOfMemoryError`, which is not an `IOException`, escaped the viewer thread's handler, and killed
the whole process. That is why Open desktop bounced straight back, why the screen stayed black
while saying "Connected", and why Android showed "PocketDesk keeps stopping".

- The viewer now reads the screen in **reused fixed-size strips** (~750 KB, allocated once per
  connection) instead of a fresh array per frame. The multi-megabyte churn is gone.
- The viewer thread catches **everything**, ends the session with a readable message, and records
  the report — it can no longer take the app down.
- The crash recorder now installs at **process start** (Application class), whatever screen
  Android launches first.
- The desktop screen's own start-up is guarded: if it cannot build, you land back on the home
  screen with the reason recorded, not in a crash loop.
- New **"Last error report"** row under Permissions whenever a report exists: view, share or
  clear it. A shared report is exactly what turns the next "keeps stopping" into a fix.

## Install progress that answers your question

While Linux or an app installs, the card now reads like:

> **Installing ChatGPT**
> Downloading packages · 4 min so far · usually 5–15 min
> Get:12 http://ports.ubuntu.com/… libgtk-3-0 [2,845 kB]

The phase (Preparing / Downloading packages / Unpacking files / Finishing set-up) is derived from
what apt is actually printing, the elapsed time is real, and every catalogue app carries an honest
typical duration. The Ubuntu tools step says up front that it usually takes 10–25 minutes.

## Desktop view

- **Zoom out now works below 100%**: in Fill mode the picture can be pinched down until the whole
  desktop fits, instead of stopping at full-screen size.
- Toolbar buttons redrawn: ripple feedback, rounded, tinted icons, proper Fill/Fit and
  Full screen glyphs, larger − / + targets.

## In-app mark

The logo inside the app keeps its plate size but the monitor mark inside it is drawn larger, as
requested. The launcher icon is unchanged.

## Verified in this build

- All four test suites pass: `VncClientProtocolTest`, `TarGzExtractorTest`, `TreesTest`,
  `LinuxAppsTest`
- javac against API 35 (min 29), D8, zipalign, APK Signature Scheme v3, `aapt2 dump badging`

## Still needs the phone

The OutOfMemoryError diagnosis fits every symptom in the screenshots, but the definitive proof is
the new error report row: if anything stops again, open it and share the text.

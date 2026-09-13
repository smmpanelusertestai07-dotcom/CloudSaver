# Release notes

## 1.5.5

Everything here came from one screenshot and one list of complaints from a phone. All of it was
real, and the audit found that several of the symptoms shared a cause.

### The editor could never load

`ERR_CLEARTEXT_NOT_PERMITTED`, on the one screen the app exists for. The app refused cleartext
everywhere and the editor is `http://127.0.0.1:8391`. The refusal is right for the internet and
stays; loopback is now exempt, and only loopback.

### The system bars were sitting on the app

Targeting SDK 35 means Android 15 draws the app edge to edge whether it asks or not, and
`setStatusBarColor` is a no-op. There was no inset handling anywhere, so the clock was painted
over the app's title and the gesture bar over the row of destinations — the bottom of every tab
could not be tapped. Both bars now take the room the system bars need, and a hairline under the
top bar puts back the boundary that was missing in dark.

### Every dialog had no surface

`Dialogs.show()` built the card and then destroyed it two lines later. The delete dialog showed
nothing readable because nothing was there.

### Colours nobody could read

One green, one amber and one red used on both a cream card and a near-black one. The failure red
measured 2.99:1 on dark — and it was the Remove everything button. Two of each now, every one at
least 4.5:1 on its own ground, recomputed on every build.

### The editor's toolbar was one run of letters

`CommandsKeysTrackpadHome`, jammed left, labels too small. The buttons had no layout parameters
at all. A quarter of the width each, 24 dp icons, 12 sp labels.

### Opening the app showed two frames at once

The window's background follows the phone's night setting; everything the app draws follows the
app's own. Set to Dark on a light phone, the window was white for a frame. The splash theme was
also defined and never applied. Both fixed, and the app's name — which the Android 12+ splash
has no slot for — is now the first thing the app draws for itself.

### Words

One name for one thing: the Linux is called Linux everywhere, not five different things. The FAQ
went from 32 entries to 19, losing everything the screen it sits on already answered, and gained
the guidance that was missing: how to tell a verified publisher from an unverified one, the four
things to check before installing anything, and that a well-established community extension is a
reasonable choice where no official one exists. "Elapsed" is "so far".

### Asking for permission

Android's rationale flag is false before the first ask and false after a permanent refusal, so
reading it alone treated a fresh install as a refusal. And nothing was asked at the moment it
mattered: set-up began a forty-minute job without offering the notification that carries its
progress and its Stop button.

## 1.5.0

The app got a shape. 1.0.0 was a stack of separate screens reached by tapping rows and backing
out again; this one has destinations along the bottom, a screen that shows what the phone is
actually doing, a lock over all of it, and an editor that sizes itself to the screen it is on.

### The interface

- **A navigation bar**, built to Material 3 Expressive's numbers rather than its older ones:
  64 dp tall, 24 dp icons, a 56×32 indicator, labels always shown. Four destinations — Home,
  Activity, Agents, Settings.
- **The editor is not one of them**, and that is the specification rather than a preference.
  Material is explicit that navigation bars belong to primary pages and toolbars to the pages
  reached from them, and that the two must never share a screen. It opens full screen with its
  own toolbar, one tap from Home's button or from the action in the top bar.
- **The mark and the name in the top bar**, so arriving from a notification or a recents card
  tells you which app you are in.
- **The editor sizes itself.** Every phone used to get the constant `window.zoomLevel` 1.5. Now
  it is worked out: `z = log(widthDp / 300) / log(1.2)`, which lands every phone within three
  pixels of the 300 effective pixels Visual Studio Code needs to stay usable. Android's font
  scale is honoured on top, and the comment says what that costs.

### What the computer can do

- **A browser, screenshots and video**, installable from Settings. An agent can open what it
  built, screenshot it, read the page back, click through it and record the run. Headless, with
  no display server anywhere.
- **Android build tools**, for Java and Kotlin projects.
- **Two permanent noes, on the screen rather than in a footnote.** The Android emulator cannot
  run on a phone — Google ships none for arm64 Linux, and even a self-built one needs `/dev/kvm`,
  which Android denies every app on an unrooted device. Apps containing C or C++ cannot be built,
  because there is no arm64 Android NDK. The phone itself is the test device instead.

### Safety

- **An app lock** over every screen, using the phone's own fingerprint or PIN, with the app kept
  out of the recent-apps preview while it is on.
- **The phone's files** can be mounted into the workspace as `~/phone` — off by default, and the
  switch is the whole safety property.
- **Why the workspace stopped** while you were away, read from Android's own exit records:
  Android 17's memory limiter, the Task Manager's Stop button, the low-memory killer, or a real
  crash — each with what to do about it, because each needs something different.

### Correctness

- **16 KB memory pages.** `zipalign -p 4` aligns native libraries to 4 KB; Android 15 introduced
  devices with 16 KB pages, where a library at the wrong offset cannot be mapped and the app dies
  at startup. Now `-P 16`, and the build re-reads the finished APK and refuses to sign one that
  does not verify. A second condition — where `GNU_RELRO` ends — is checked separately, because
  Google's own script does not.
- **The launcher mark** was outside the safe zone every OEM mask guarantees, and read as
  oversized beside Gmail and Drive. Rebuilt to a per-asset fill model.

## 1.0.0 — first release

The first version of PocketIDE, and a different architecture from everything this project tried
before it.

### What it is

A real Ubuntu 24.04 LTS ARM64 system under PRoot, running code-server (Code-OSS 1.137.0), with
coding-agent extensions installed from Open VSX. It runs entirely on the phone.

### What changed from the approaches that did not work

- **No VNC, no X server, no Electron.** An earlier design ran a full Electron IDE over a remote
  framebuffer and it crashed against Android's process ceiling on a 3.9 GB phone. The editor here
  is a Node server whose interface is HTML, drawn by the phone's own browser engine. Real text,
  platform scrolling, the phone's keyboard.
- **The three agents arrive the same way** — as their publishers' own extensions, from their own
  verified Open VSX namespaces. Google publishes one after all; an earlier search missed it under
  507 community results and wrongly reported it did not exist.
- **Not locked to three.** The whole Open VSX registry is browsable, verified publishers by
  default, with unverified behind a warning that quotes the real 2026 counterfeit numbers.
- **Material 3 Expressive, not Liquid Glass.** Liquid Glass is Apple's; Google has ruled it out
  for Android. Glass stays as an accent.

### Safety

- Ubuntu 24.04.5 LTS, the current point release, pinned to the SHA-256 in Canonical's own
  SHA256SUMS and re-checked by hashing the downloaded bytes. code-server pinned to a SHA-256
  verified first-hand, because Coder publishes none.
- Every extension download compared against the checksum Open VSX publishes, before installing.
- The editor listens on 127.0.0.1 only, behind a password this phone generates for itself —
  Android does not keep loopback private between apps.
- You never see that password. The app writes its SHA-256 into code-server's config as
  `hashed-password` and the same digest into code-server's session cookie, which is exactly what
  code-server checks under that method, so the editor opens already signed in. Read out of the
  pinned release's own source, not assumed — and a gate holds every part of it.
- Cleartext HTTP refused outright.

### Honesty

- 43 gates, every one of them bidirectional: each was verified to fail when the thing it guards
  is broken, not merely to pass when it is not.
- The Help screen carries the mission, the FAQ, the terms, the privacy position and the full
  permission list, and the gates check that list against the manifest.

### Known and stated

- Monaco has no touch text selection — Microsoft's own open issue. The cursor trackpad is the
  mitigation, not a fix.
- OpenAI's Codex Remote needs a Mac or Windows host, so there is no phone surface for it. The app
  says so rather than pretending.
- The Android and iOS build toolchains are not in this release; they need their own testing pass
  on a real device.

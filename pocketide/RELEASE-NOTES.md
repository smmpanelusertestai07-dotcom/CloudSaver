# Release notes

## 1.6.5

An audit of what 1.6.0 shipped, and eight things it was wrong about. All eight were confirmed by
reading the code rather than taken on trust, and every one now has a check that fails against
the code as it shipped.

### The bar I had just rebuilt could not be read

The active destination's label measured **3.97:1** on the lower half of the light capsule, and
its icon measured **3.28:1** on its own indicator. Under the floor, on the one control in the
app whose entire job is to say which screen you are looking at.

Nothing caught it, because the existing contrast gate compares a colour against a flat card or
page — and the bar is neither. It is a gradient with a translucent pill on it, and the icon sits
on the pill. Measured against the card colour, both passed.

The fix is Material 3's own model rather than a nudged hex value: the indicator is a light tone
of the hue and what sits on it is a *dark* tone of the same hue, and the active label takes the
plain text colour with weight marking it as selected. 6.8:1 to 15:1 now, both themes, both ends
of the gradient. The gate composites the pill over the gradient and measures against the result,
and it reads the alpha out of `Shell.java` so changing the indicator is caught here.

### A failed update check was recorded as "everything is up to date"

The worst shape a bug can have: wrong, and sticky. The script already printed `apt_list=0` when
it could not reach Ubuntu's servers, precisely so the app could tell a real answer from no
answer — and nothing read it. A check made offline reported zero security updates, which on the
screen is the same sentence as "everything is up to date", and then stamped the clock and did
not look again for a day.

It is read now, and a check that got no answer writes nothing. The throttle keeps two clocks: a
successful check is good for a day, a failed one is tried again in an hour.

### "Everything, now" contradicted its own dialog

Its text said "the editor has to be closed for its own update, and this will not start one while
it is open". Nothing enforced it. The guard existed only on the editor row, so the one row most
likely to be tapped could pull the tree out from under a running editor. Both ends check now —
the app before the dialog and again after it, because the two are seconds apart, and the script
with `pgrep`, because that is the end that cannot be raced.

### A kill during the swap cost a 224 MB re-download

The swap is two renames with a gap between them where `/opt/code-server` does not exist. Renames
are milliseconds, but milliseconds is not never. Landing in that gap left the working editor
beside the hole under `.previous` — and the app, seeing no editor, would offer to download the
editor already on the disk. Every entry point now looks and renames it back, including starting
the editor, which is the route an owner actually takes.

### Three more

The free-space check ran before the stale staging tree was cleared, so one interrupted unpack
refused every later attempt for want of room it was itself holding. `doRun` and `collect` never
destroyed the PRoot process, so a broken pipe left apt holding the package lock — pointing at a
process the owner can neither find nor stop. And `maybeRunInBackground` claimed its slot before
`Thread.start()`, so a phone that refused to create a thread would report "already checking" for
the life of the process.

### Gates

46, with eight new checks inside them — and one of them was itself rewritten during the work: it
matched the swap recovery by function name, and a rename passed it. It matches the condition now,
and counts the call sites.

## 1.6.0

The bars, and the thing nobody had built yet: a workspace that can keep itself current.

### The bottom bar was cutting its own labels off

A screenshot showed it plainly: "Home", "Activity", "Agents" and "Settings" all missing their
descenders. The bar was fixed at 64 dp while the column inside it came to about 68, and the
overflow was silently clipped. The number was never the problem — a bar that *cannot grow* is.
It now holds 64 dp as a minimum and lets its content decide the rest, which is also what makes
it survive a phone set to large text.

Rebuilt at the same time, because the flat card-coloured strip with square corners and a
hairline above it is the 2019 bar every app has moved off: it is now a floating glass capsule,
inset 12 dp from each side, lifted 10 dp off the gesture bar, fully rounded, lit at the top
edge and clipped to its own outline so a ripple cannot square the ends off.

It still takes its own room in the layout rather than hovering over the page. A bar that floats
over a scrolling list needs every list in the app to reserve space under it, and the one that
forgets leaves its last row unreachable. The gap around the capsule is the page's own colour —
so it looks like it floats, and the layout still has an honest bottom edge.

### The `<>` in the top right was decoration

It opened the editor. Nobody could tell, because it was a bare tinted glyph with nothing around
it saying it could be pressed. It is a Material 3 filled tonal icon button now: a 40 dp
container centred in a 48 dp touch target, with the ripple masked to the circle rather than
flashing a 48 dp square.

### The tagline existed for six-tenths of a second

It appeared on the opening frame and then nowhere at all, which is a flicker rather than a
tagline. It now sits under the app's name in the top bar, in Material's own
title-and-subtitle slot.

### Nothing kept itself up to date

Everything in the workspace was pinned — a pinned Ubuntu image, a pinned editor archive,
extensions frozen at whatever version they arrived as. A pin is right on the day it is made and
wrong a year later: Ubuntu ships a security fix within hours of a CVE, and a machine that never
runs `apt` never receives it.

So the pins are now the floor rather than the ceiling:

- **Ubuntu's security updates are taken automatically** — on Wi-Fi, once a day, while the app is
  open and the editor is not. Security only, never a full upgrade, because Ubuntu's own
  maintainers have already decided which changes are safe on a stable release.
- **Extensions keep themselves current**, by the editor, with `extensions.autoUpdate` and
  `extensions.autoCheckUpdates` both on. "Update them all now" is there for a workspace that has
  not been opened in a month.
- **The editor moves when you ask.** code-server cannot update itself — it is a tarball, its own
  updater is compiled out, and `update.mode: none` is why no notification offers an update that
  could never install. The app does it instead, from Settings.

The editor update cannot be pinned by checksum the way the first install is, because nobody can
pin a version that does not exist yet. Four checks stand in for the pin, and they are named in
the script rather than left to be assumed: the version and URL come from GitHub's own release
API over TLS; the bytes on disk must match the size GitHub published for that asset; the archive
must unpack into a tree containing a runnable editor; and that editor must report the exact
version that was asked for. Only then is anything installed touched — and if the swap leaves
anything unrunnable, the previous tree goes straight back.

Updates run only while PocketIDE is open, because Linux only runs while PocketIDE is open.
Android does not keep another operating system alive behind a closed app, and an app claiming
otherwise would be describing something that cannot happen.

### The app never said what kind of computer this is

"Ubuntu on your phone" tells nobody whether their project will build. Settings → The computer
now answers it with this phone's own numbers: cores, total and free memory, free storage, and
the build heap and worker count worked out from them — the same ladder `pocketide-tools.sh`
writes into Gradle's settings, with a gate comparing the two so they cannot drift apart.

It also says the thing that is counter-intuitive enough to send someone off to buy a phone they
did not need: **the APK's size is not the limit.** A 200 MB APK is no harder to produce than a
2 MB one. What costs memory is the compiler, and that is decided by module count, source count
and the size of the dependency graph — a small app with two hundred dependencies is a heavier
build than a large app with ten.

### Gradle, sized to the phone

Installing the Android build tools now writes `~/.gradle/gradle.properties` tuned to this
phone's actual memory: the build heap and worker count from the ladder above, the Kotlin
compiler daemon bounded separately (it is a second JVM, and two unbounded JVMs is the common way
a build gets the app killed), the daemon's idle timeout cut from three hours to ninety seconds
so it is not sitting on 1.5 GB between builds, and file-system watching off because Android's
inotify limit is low enough that a large project exhausts it — and when it does, Gradle does not
fail, it stalls.

It is written only when there is no file there already. Someone who has tuned their own build
has made a decision this app does not get to overrule.

### Gates

46, up from 43. The three new ones guard the update path (that the script is actually copied
into the workspace — without which every update fails from a row that looks like it works; that
the editor swap is staged, verified and reversible; and that what happens *without being asked*
is security updates and nothing else), the build-memory ladder in the two places it has to be
identical, and the bar's ability to grow.

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

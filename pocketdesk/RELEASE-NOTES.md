# PocketLinux 12.0.0 — the whole thing, finished

Everything in this release came from one long list of things that were wrong, or missing, or
merely said wrong. They are grouped here by what an owner would notice.

## Rotation, which was two bugs wearing one name

"Portrait" was `SCREEN_ORIENTATION_USER_PORTRAIT`, and that constant does not mean portrait. It
means *portrait, either way up*. Put the phone down face-up and pick it up the other way and the
screen turned over: camera at the bottom, gestures coming from the wrong edge. Auto-rotate on the
desktop was `FULL_SENSOR`, whose entire purpose is to add the upside-down rotation a phone would
not otherwise use. Between them, that is the whole of the "phone rotate issue".

One rule now lives in `ScreenRotation`, and both screens read it:

* **Portrait** is `SCREEN_ORIENTATION_PORTRAIT`. One way up, the right one.
* **Landscape** is sensor landscape — a phone held sideways has no natural top.
* **Auto-rotate** is `SENSOR`: the three rotations a phone really has, and it keeps following
  them when the phone's own rotation lock is on, because choosing Auto-rotate *in PocketLinux* is
  the owner saying what they want.

The second half of that report was that the setting never reached the computer at all.
`LinuxService` took the desktop's shape from the phone's physical configuration alone, so
Portrait changed the phone window and left a landscape Linux desktop inside it. The setting is
the primary input now; the viewer asks the desktop to match whenever it is applied; and the one
resize sent after connecting is retried instead of dropped when the server has not yet said it
can be resized.

## The interface inside was too small, and it was arithmetic

The desktop is drawn at the phone's own pixel count — one Linux pixel per phone pixel — and the
dpi was a constant 120. So an 11-point face landed at 18 real pixels: about 1.7 mm on the
reference phone, roughly two thirds the size of Android's own body text. A phone screen is 30 cm
from a face, not a monitor at arm's length.

The default is worked out from the phone's density now, and Settings gained two larger steps
above it. Openbox's title font was the one thing that did *not* follow the dpi — it was rewritten
to a literal 14 points — so raising the dpi would have overshot it by half; it is a constant pixel
height now, which is what it was always trying to be, because Openbox sizes the title buttons from
the window font and nothing else. PCManFM's icon sizes were fixed pixels for the same reason and
now scale too.

And **Screen → Bigger interface** works on a desktop that is already running: it asks the desktop
to be smaller than the phone screen and scales it up to fill it, so type, icons, title bars and
close buttons all grow at once, with nothing cropped and nothing restarted.

## Icons that were not there

Ubuntu 24.04's Adwaita ships no full-colour application icons at all any more — only the symbolic
set, which GTK will not use for a launcher. So `Icon=system-software-install` resolved to nothing
and Software wore a blank sheet. PocketLinux draws its own now: Projects, System settings,
Software, and a parcel for "Install a downloaded app", which had been wearing Tux. Projects and
Phone files were redrawn to match, and Projects has a launcher of its own instead of arriving as
pcmanfm's grey "Documents" shortcut.

## System settings, inside the computer

The computer had settings — the theme, the bar's edge, sound, storage, software — and nothing that
looked like settings, so they were found only by someone who already knew where they were. One
dialog now opens the tools that already existed, and says plainly which two settings belong to the
phone rather than to it. The desktop also follows the app's Light/Dark/System theme, resolved on
the phone side because the container cannot see the phone's night mode.

## The phone's files, and the cloud drives that are not files

One line used to bind the entire storage card, read-write, into the container — where everything
runs as PRoot fake root with the app's own Android identity. An agent's `rm -rf` reached every
app's data folder, every backup, every photo, with no Android bin to recover from.

PRoot cannot do a read-only bind — it rewrites paths, it does not enforce permissions, and the
real write is performed by the app's own identity — and nothing inside a container can police
itself. So the lever is not "allow less", it is **name less**: six folders are bound now
(Download, DCIM, Documents, Pictures, Music, Movies) and nothing else on the phone is connected at
all. What is not bound cannot be named, however convincingly it is asked for.

Cloud drives cannot be mounted, and no permission changes that: Google Drive, OneDrive and Dropbox
are document providers behind `content://` addresses with no filesystem path for anything to bind.
What *does* reach them is Android's own picker — so **Window → Add a file from the phone or a cloud
drive** opens it, lists every cloud app on the phone, and copies what is chosen into the computer's
Cloud folder, which is in the sidebar of every Open dialog inside Linux.

## The viewer

* The volume readout moved to the right-hand corner and grew the three buttons it was missing:
  quieter, mute, louder. **Mute is on the bar itself** — it is the one control that is needed the
  moment it is needed.
* **Rotation lock** and a **screen lock** that ignores touches. The screen lock wants two taps in
  the middle to let go, because one tap is exactly what a stray touch is.
* A third way for a finger to reach Linux, called **Screen**: the button stays down for the whole
  gesture, so a swipe is a real drag. A map moves, a canvas draws, a game's control answers.
  Finger mode gained the **sideways scrolling** it never had, with the axis decided once per swipe.
* **Windows can be resized by dragging anywhere inside them.** A window edge on a phone is about a
  millimetre wide and there is no cursor to watch change shape, so aiming at one was never real.
* The **keyboard** no longer hides the bottom third of the desktop: the picture scales into the
  room above the keys and springs back, with nothing sent to the server and no Linux app relaid
  out.
* The bar is 48 dp instead of 56, and can auto-hide.

## Frames

* **CopyRect.** The viewer offered Raw and nothing else, so a page scrolling, a window moving and
  a tab switching each re-sent every pixel. CopyRect says "that block is already on your screen,
  at these other coordinates" in six bytes, and that is most of what a scroll is.
* The desktop was always about **4 % larger** than the rectangle it was drawn into, because the
  frame around it was subtracted after the size was chosen rather than before. Every frame was
  resampled and no text was ever sharp. It is 1:1 now, and smoothing is only paid for when
  something is genuinely being scaled.
* A full-screen update was 120 separate reads into the same buffer, one per row. It is one read
  per strip.
* The opening screen is 1.6 seconds instead of 3.5, a tap skips it, and a shortcut skips it
  entirely — a shortcut is an instruction, not a visit.

## Smaller phones

A 2 GB phone was refused outright, for something it was never going to be asked to do. The four
AI apps are Chromium programs needing about 700 MB each, and that is a fact about them; the Linux
computer underneath runs in a few hundred megabytes. There are two answers now and the phone is
told which one it gets. Memory is compared in bytes with a stated tolerance rather than rounded
gigabytes, which used to refuse a phone sold as 4 GB for reporting 3.6. On a phone Android calls
low-memory the two framebuffers are RGB_565, halving both of them and every frame's upload.

## Running in the background

"Smart" closed a session for being idle when nothing had been *touched* — so a build, a download
or an AI agent left running with the phone in a pocket, which is exactly when that is worth doing,
was closed at full stretch. PRoot traces every process in the container, so its own processor time
rises and falls with whatever the computer inside is doing. **Work counts as use now, because it
is.**

And an app downloaded straight from its publisher resumes. These files are 200–700 MB, usually
over mobile data, and a download that stopped at 600 MB cost 600 MB and bought nothing.

## Design and game tools

Blender, Godot, GIMP and Inkscape — all ARM64 builds from Ubuntu's own catalogue. Everything draws
on the processor, because no app in a container on an unrooted phone has a path to a graphics
chip, and the card says so before the download starts: modelling, 2D work, a game's editor and
scripting are responsive; a lit 3D viewport and a full render are slow, and a render can be left
to run. The FAQ used to say game engines and 3D were "not possible here". They run. They run
slowly. That is now what it says.

## Terms, in the fewest words that are still true

There are three sets of them and confusing the three is how people end up surprised:
PocketLinux's own, Ubuntu's, and the AI companies'. Settings → Terms says whose is whose in six
short paragraphs, and the credits paragraph that used to carry all of it is four lines now.

## The look

A `gtk.css`, which did not exist anywhere and is the one real lever GTK gives: rounder corners and
controls tall enough for a thumb. Vertical gradients on the panel, the title bars and the menu
headers, which those theme formats have always had and nothing used. Hover and pressed states on
the panel. A lit top edge instead of a dark seam. The Android app gets one glass surface behind
its cards and pill-shaped bar buttons with a hairline.

No compositor, no blur, no rounded window corners: Openbox 3's theme format has no radius, no
alpha and no shadow, and there is no compositor here to add them. Real backdrop blur is not
available to an Android View either — `RenderEffect` blurs the view's own content, not what is
behind it. So the depth comes from gradients and borders, or it does not come at all.

Opening the app on a phone in dark mode no longer flashes a white rectangle. That window exists
before `onCreate`, so no code could have fixed it; a `values-night` theme could.

## Tests

Sixteen Java test suites existed, were green, and nothing ran them: only two were named in the
runner. They are picked up by glob now, so a new one runs the moment it is written. The rotation
fix has a guard of its own — the two Android constants that mean "either way up" may not come
back.

---

# PocketLinux 11.0.5 — the computer stops stopping

The desktop was ending by itself with "The desktop display ended unexpectedly (exit 137)". The
report that came with it named the cause without meaning to:

```
Host RAM MiB: available=1223 total=3740 lowMemory=false
sampledLive=32 peak=36
pid=1076 ppid=630 procState=Z      pid=4036 ppid=630 procState=Z
pid=630  ppid=1   procState=Z      pid=1995 ppid=630 procState=Z
pid=4815 ppid=630 procState=Z
```

**It was never memory.** 1.2 GB was free and `lowMemory` was false. Exit 137 is SIGKILL, and the
number that mattered is `peak=36`: **Android 12 and later kill every one of an app's forked
processes once there are more than 32 of them**. Under PRoot each Linux process *is* one of this
app's Android processes, so that limit is the whole computer's limit — and passing it does not
slow anything down, it ends the session mid-sentence.

**Five of the seven survivors were zombies** (`procState=Z`), children of an app that had already
exited. A zombie still holds a process slot. Nothing in a container waits for a reparented
orphan, because a container has no init to do it — so they accumulated until the session was
killed for a crowd that was mostly already dead.

## Three fixes, none of them a setting

**Finished processes are cleared, continuously.** The session's last process now makes itself a
child subreaper, so orphans reparent to it instead of to a pid 1 that does not really exist, and
it clears them every turn of its loop. Processes that belong to someone — the display, the panel,
an installer — are looked at with `WNOWAIT` and left exactly as they are, so no owner ever loses a
child's exit status.

**The computer stays under the ceiling.** At 26 processes held for six seconds, one program is
closed on purpose — the browser first, because closing it loses a tab rather than a conversation —
and the owner is told which and why. Six slots of headroom, because opening an app adds several
processes between two checks, and being killed at 33 is no different, to the owner, from being
killed at 40. Desktop parts are never candidates: a computer with no panel is not a rescue.

**A session that ends by itself comes back by itself.** Twice, automatically, with the viewer
showing "The phone stopped the computer. Reopening it — nothing was lost…" instead of throwing
you to the home screen. A session that ran healthily for five minutes earns the pair back; a
computer that genuinely cannot start stops after two and says so plainly, rather than looping and
heating the phone.

## Settings → Running → Android process limit is gone

It needed developer options, it changed a setting for the entire phone, and it did nothing at all
for anyone who never found it. The computer manages itself now. A test fails the build if that row,
its helper or its phone-wide policy script ever come back.

## The package identifier is now com.pocketlinux

The name change is complete: label, package, sources, actions and the APK. **This installs as a new
app rather than an update** — Android treats a different package as a different app — so the Linux
computer is set up again from scratch and Ubuntu is downloaded once more. Use Wi-Fi for that.
Remove the old PocketDesk afterwards to get its storage back.

## Also fixed

- **Downloads were only being noticed if they were Windows programs.** The watcher's filter still
  matched `.exe` only, left over from the removed Windows layer, so a `.deb` never reached the
  installer offer and no other file was ever placed. It now sees every finished file, and ignores
  the `.crdownload`, `.part` and `.tmp` files a browser writes while a transfer is still running.
- **The runtime report stopped quoting a setting nobody can act on.** It said "Android
  child-process monitor setting: Global override unset; effective policy not verified", which is
  true and useless. It now states the ceiling and what the computer does to stay under it.

---

# PocketLinux 11.0.0 — one system, and it is Linux

The app is now **PocketLinux**. The name says what it is, and the Windows compatibility layer that
several earlier releases carried has been removed completely — not disabled, removed: every helper,
every code path, every catalogue row, and the low Android target it forced on the whole app.

This is a deliberate, final decision, and here is the whole reasoning.

## Why not Windows

**It cannot work on this hardware.** Three separate walls, any one of which is enough:

1. **Real Windows needs a virtual machine.** Android's own virtualisation framework is documented
   as being for privileged and platform applications — an installed app cannot start one.
2. **A compatibility layer instead?** The one project that ran Windows programs on ARM64 dropped
   its Android support.
3. **This container already traces every system call** with ptrace, and an instruction translator
   layered on that is the known-broken combination; it reports exactly that when tried.

**And where it does work, it loses.** The two most important apps ship for Windows as store
packages, which a compatibility layer installs without their package identity — so the sign-in that
comes back through a custom link may never reach the app, and the app's own updater stops working,
making every future update a manual download. These are Chromium apps, the hardest kind to
translate: they lose their sandbox and gain a whole second system's worth of processes, roughly a
third more memory, on a phone with under four gigabytes.

**The feature people want it for is the first to break.** The one thing the Windows builds have
that the Linux builds do not is the apps' own Computer Use — which works by driving *Windows*
programs with *Windows* automation. Inside a compatibility layer on a Linux desktop there are no
Windows programs to drive. PocketLinux supplies that capability itself instead: appshot with the
words on screen, plus click, type, key and scroll, offered to any AI agent here, natively.

**On ARM64, Linux is ahead of Windows — not behind it.** Claude's Cowork is not supported on
Windows ARM64 at all, and Claude Code on Windows ARM64 has an open crash report; both work on Linux
ARM64. And all four AI desktop apps publish official Linux ARM64 builds. That is the sentence that
ends the argument: there is nothing to gain and a great deal to lose.

**macOS was never a candidate** — it is licensed only for Apple's own hardware.

## Why Linux is the one that lasts

Ubuntu 24.04 LTS has security updates to **April 2029**, to **April 2036** with Ubuntu Pro (free
for personal use), and to **April 2039** with the Legacy add-on — fifteen years, on a base that
never forces an upgrade. Each Windows release gets about twenty-four months before the next one is
required. For an app meant to be set up once and left alone, that is not a close comparison.

## What removing the layer bought

**The app targets Android API 35 again.** It had been pinned at API 28 — the last compatibility
domain Android allows to execute files written into an app's own data — purely so a Windows layer
could map downloaded program code. Nothing else needed it: PRoot and its loader are signed native
libraries inside the APK, which the package manager extracts and every modern target permits. Being
back on 35 puts the app inside a decade of Android's own hardening, and keeps it installable as
Android raises the floor it accepts. A test locks it there.

Gone with it: about 2.4 GB of downloads the Apps tab used to offer, six helper programs, a whole
Java class, and every "this may not work" caveat attached to them. A new test fails the build if
any of it returns.

**A computer set up by an older version tidies itself.** The first desktop start after this update
removes the leftover Windows launchers and prefixes. Nothing else is touched.

## Also in this release

- **Android builds finish here.** `aapt2` is the one piece Google publishes for Intel Linux and not
  for ARM64, and Android's build plugin fetches it from Maven — which is where a good build used to
  stop. Ubuntu builds its own from the same source; PocketLinux installs it and points Gradle at it
  with `android.aapt2FromMavenOverride`.
- **Eleven phone tools for AI agents**: `phone_devices`, `phone_install`, `phone_launch`,
  `phone_screenshot`, `phone_ui`, `phone_tap`, `phone_swipe`, `phone_text`, `phone_key`,
  `phone_logcat`, `phone_shell`. `phone_ui` reads the phone's screen as a list of named elements
  with the exact point to tap, so an agent acts on names rather than guessing at pixels. "Build
  this, put it on my phone, open it and tell me what is broken" is one instruction. With nothing
  paired, every tool says how to pair rather than failing.
- **Files an AI app writes now obey the download setting too.** Chrome already did, through a
  managed policy. `pocketdesk-save` applies the same three answers — this computer, the phone as
  well, or ask — to whatever an app or a build drops into Downloads. It copies, never moves.
- **A framework race can no longer bury a real crash report.** Android's teardown error arrives
  milliseconds *after* the fault that caused it; it is now ignored when a real report was written in
  the last two minutes, so the report you read is the cause rather than the tidying-up.
- **The test suite pins a UTF-8 locale**, so a machine whose default locale is plain ASCII no longer
  fails a test for its environment rather than for the code.

## The name

**PocketLinux**, everywhere: the launcher, the opening screen, the notifications, the dialogs, the
desktop wallpaper and the APK. The package identifier is deliberately unchanged, so this installs
over an existing copy as an ordinary update — the Ubuntu system, your apps and their sign-ins are
all kept, and nothing has to be downloaded again.

---

# PocketLinux 10.5.40 — paired-phone process control and quieter background work

The 10.5.35 report shows a desktop root receiving SIGKILL while the Android app
survives. Its tracked process peak is 38. AOSP Android 13 normally monitors a
global budget of 32 child processes; pairing alone does not change this policy.
The report is consistent with process trimming, but does not identify the sender
of SIGKILL. A prior GPU child failure is separate: Codex continued working afterward.

- Settings → Running → Android process limit now reads the connected phone's
  effective AOSP override. Apply is an explicit choice: it switches off Android's
  child-process monitor for all apps on this phone and can increase battery/RAM
  use. Android's memory and thermal protection remain active. Matching boot IDs
  verify this exact phone before any setting access. Pairing and desktop startup
  do not apply this change automatically.
- Save the previous Global value atomically before Apply, verify readback, and
  provide Restore. Restore preserves an originally unset value and refuses to
  overwrite a conflicting later choice. A lost reply displays Unknown until a
  fresh check; it does not become a false success. Existing connected devices can
  be rediscovered, but an answering different selected phone is always refused.
- Bound the ADB action to 24 seconds, individual requests to 5 seconds, and
  combined command output to 16 KiB. Native Settings work has its own short
  process and CPU lease; cleanup does not stop the running Linux computer.
- Replace the permanent desktop Bash waiter with its existing Python watcher.
  Child-exit signals wake the watcher and app supervisor instead of repeated idle
  polling. Exact display exit status is retained, and slow optional download
  inspection cannot hold up display-death handling. Desktop services and app
  capabilities remain enabled.
- Resume a retained viewer with incremental pixel updates. Keep one outstanding
  framebuffer request through repeated app switches; first connection and real
  framebuffer resize still request complete pixels. Defer requests until all
  rectangles in the current update are decoded. Input stays independent.

Install as an update over the existing app. Then open Settings → Running →
Android process limit and review Apply. If Wireless debugging has disconnected,
use Phone app testing → Connect with its current connection port; an existing
pairing does not require a new pairing code. The last checked policy is labelled
as such, and the runtime report no longer treats an unset Global value as proof
that the firmware default is active.

Profiles, logins, files, downloads, Shift/Drag controls, wider workspace, GPU
process isolation, and explicit timers are retained. No extra RAM, reduced
publisher latency, immunity from firmware kills, or crash-free heavy work is
claimed. See VALIDATION-v10.5.40.txt for host checks and physical-device limits.

# PocketLinux 10.5.35 — drag controls, wider workspace and background runtime

The supplied 10.5.30 report shows a GPU child crash at 16:25:37 with the Codex
main process still working at 16:32:26. Separate GPU-process containment is kept.
The desktop root later exits 137; its sender is not established by the report.

- Add Shift and an explicit Drag/Release control. Put the pointer over a divider,
  tap Drag, swipe to resize, then tap Release. Multiple thumb strokes keep the
  button held; backgrounding, disconnection and pointer-mode changes release it.
- Screen → Wider workspace gives narrow phones a wider logical desktop so the
  publisher's sidebar and settings columns fit. Text becomes smaller; fit, pinch
  zoom and the original phone-sized mode remain available. Framebuffers remain
  within the existing 2.3-million-pixel request budget.
- Toolbar changes now recenter the fitted desktop without becoming delayed Linux
  screen-size requests. Bitmap replacement completes before decoding the next
  rectangle, preventing resized updates from being written into obsolete buffers.
- Preserve mouse button transitions and keys during pointer floods, coalescing
  only consecutive movement samples. Long IME commits and composition changes
  are batched, avoiding hundreds of queue entries for a pasted prompt.
- Hidden viewers stop requesting pixel updates and resume with a full refresh.
  The desktop service holds its own expiring CPU wake lease while the desktop is
  alive, independently of installation tasks. This prevents ordinary suspension;
  it is not extra RAM or immunity from Android process limits.
- Discard only confirmed exited process identities after collecting descendants.
  Long sessions no longer revisit every historical helper at each sample; live
  or unreadable identities remain available for safe cleanup.
- Window focus changes no longer repeatedly resize existing floating windows.
  New dialogs and real desktop work-area changes still receive fitting checks.
- Windows setup distinguishes loss of its X display from missing runtime DLLs.
  Wine's internal boot-event timeout is a failure even when its wrapper exits 0;
  these cases retain the download and do not trigger unrelated runtime repairs.

Existing login profiles, Linux files, downloads, graphics isolation and explicit
safety/timer preferences are preserved. For unattended work, Settings → When to
stop by itself → Never stop avoids the existing Smart stopping idle timeout.
Android, firmware and publisher binaries can still fail; Windows ChatGPT and
long-session physical-device operation are not certified by host tests.
See VALIDATION-v10.5.35.txt for checks and limits. Install as an update.

# PocketLinux 10.5.30 — isolate native ChatGPT graphics and preserve real resource counters

The new report and video show the ChatGPT/Codex window disappearing with exit
139 while the Linux desktop remains alive. This differs from the earlier whole
desktop SIGKILL. The exact native fault is not located by the supplied report.

- Native Linux ChatGPT no longer forces software GPU work into Electron's main
  process. Packaged ANGLE/SwiftShader stays enabled in Chromium's separate GPU
  process, allowing its normal GPU crash handling. This contains a class of
  graphics failures; it does not prove that SwiftShader caused this crash.
  The tradeoff is an additional GPU process and its memory overhead.
- Readable host /proc resource entries are no longer hidden by fixed stand-ins.
  Compatibility files remain for paths Android denies or leaves empty, using an
  actual bounded read to decide. This restores real counter inputs where available.
- Failure reports distinguish a direct child's signal from an ordinary numeric
  exit status. SIGSEGV is identified separately from SIGKILL; neither a WebGL
  warning nor an updater 404 is relabelled as the proven cause.

Microphone cancellation, bounded logging, retained failure evidence, login
profiles and the 10.5.20 desktop repairs remain. Windows and browser graphics
profiles are unchanged. No WebGL/voice/file/model feature is disabled, and no
unsafe SwiftShader opt-in, watchdog bypass, priority override or heap increase
is introduced. RAM and CPU capacity remain the phone's physical limits.

See VALIDATION-v10.5.30.txt for evidence, tests and device-testing limits. Install
as an update, preserving the existing Linux computer and account data.

# PocketLinux 10.5.25 — microphone cancellation and long-session logging

The 10.5.20 report shows the Linux root still alive and ChatGPT reaching its window;
it does not include the reported typing/model-selection crash. The Chrome fatal
in that report belongs to an older launch. This release fixes defects found in
the audio bridge and reduces diagnostic I/O, without claiming that all publisher
app crashes are resolved.

- Microphone recording now opens a nonblocking, verified FIFO before activating
  AudioRecord. Stop cancels the owning session and releases its recorder; a stale
  worker cannot disable a newer recording. Audio errors stop the worker, empty
  reads wait, and a desktop that stops consuming audio cannot block it forever.
- PulseAudio reports microphone readiness only after source loading and default
  selection succeed. Bounded command failures retain their actual output.
- Linux app stdout is drained continuously and written in batches. Each app has
  a bounded 2 MiB current log and one rotated log. A full/unwritable log destination
  does not stop draining the app's output.
- A separate, redacted failure snapshot survives a successful reopen. Reports
  distinguish retained failures and app logs from before the current desktop.
  Main-process memory/thread samples carry timestamps; they are not total app
  memory and do not identify who sent a kill signal.
- Existing startup, D-Bus, process-ownership, login handoff, rendering profiles,
  downloads, installed apps and user profiles are retained. No Android policy,
  CPU affinity, swap setting, or publisher memory limit is forced.

See VALIDATION-v10.5.25.txt for test results and device-testing limits. Install as
an update; uninstalling or resetting Linux is unnecessary.

# PocketLinux 10.5.20 — desktop startup and missing D-Bus configuration

The 10.5.15 device report showed a live Linux process spending its startup budget
before the display launched. A later session reached 38 tracked processes and
ended with SIGKILL while the sign-in page was already visible.

- Start the private display before per-user settings, app-menu generation and MCP registration.
- Parse installed app metadata once per menu rebuild and reuse the visible app list.
- Remove recursive ownership walks through Phone, Downloads, Projects and other user data.
- Restore an absent D-Bus system configuration offline from unmodified Ubuntu package data;
  preserve existing policies and live buses. Prepare messagebus identity and machine ID before
  daemon startup. Fresh installs explicitly include dbus-system-bus-common.
- Host the Linux Chromium network service in the browser process to reduce utility children.
  Windows renderer profiles keep their separate flags.
- Bound the optional Claude MCP registration and menu display probe.
- Freeze the actual failed startup stage before cleanup; show its reason in the viewer.
- Keep process state in redacted reports and report the Android child-process monitor setting.

GitHub comparison: CloudSaver main commit 771e6031d02612e57d70256dd93ce425ce3edb57,
PocketLinux 10.1.20. The older browser and ChatGPT SwiftShader flags were already the
same in the relevant areas. No GitHub files were changed.

Windows ChatGPT is not fixed or certified by this release: the supplied Wine log
still did not produce a stable product window. Linux real-device sign-in and sustained
operation also require verification on RMX3197. SIGKILL does not identify its sender.

# PocketLinux 10.5.15 — process load and forced-exit cleanup

The supplied 10.5.10 report confirms that the failure remains on RMX3197/Android 13.
Chrome's native executable received the browser request with 1231 MB available at
launch. Its network service later restarted; at 13:36:23 the established D-Bus
connection failed and ChatGPT could no longer access /tmp. The desktop returned
137. These events support a session-wide failure. They do not identify who sent
SIGKILL or prove that free RAM was sufficient at the instant of failure.

Changes in this update:

- Linux window detection parses and deduplicates window-owner PIDs in Bash,
  removing awk/sort/tr pipelines and nested polling shells. It keeps executable
  ownership checks, existing app instances and OAuth callback arguments.
- The window guard now uses Python plus its xprop event monitor, replacing three
  persistent processes with two. It batches window property queries and coalesces
  event bursts, removing sleep/sed/tr/date helpers. Decorated-window fitting,
  work-area offsets and maximized/minimized/fullscreen exclusions are retained.
- The panel's status helper reads proc/sysfs/statvfs directly in one process.
  Battery, temperature, network, free memory, storage and tooltip stay available.
- One event-driven Python watcher combines completed-download handling and the
  startup panel check. This removes one persistent process and three during the
  initial panel-check interval. It preserves the existing Windows installer
  confirmation, literal filenames, package checks and downloaded files.
- The system D-Bus initialization runs before switching to the desktop user.
  Socket connection and a bounded ListNames call replace the previous silent
  pgrep-based check. A connectable listener is retained even if its readiness
  call fails. Startup errors appear in the desktop report. The normal system
  policy remains in place; the system and session buses are kept separate.
- Android records the identities of native session children while their tracer
  is alive, with one Java-side inventory shared every two seconds. Unexpected
  tracer death triggers bounded cleanup of those previously verified children.
  A new start waits for cleanup. No process is selected by name or merely by UID;
  another live session and unrelated processes are preserved. No native monitor
  process is added. Children never observed before tracer death may remain.
- Runtime samples retain pre-exit memory, process counts, tracking state and
  explicit stop requests. Readable pressure/OOM-score metadata is context, not
  a verdict about the killer. Copy all puts this evidence first and limits the
  combined report to 16000 characters. App sections select their latest launch;
  old attempts remain in the individual reports. Credential redaction happens
  before truncation.

Install over the existing app, save work, and restart the phone once to clear
any untracked descendants left by the previous build. Do not uninstall or clear
data. Check Chrome and Linux ChatGPT sign-in, then test Windows separately using
the existing package. No publisher app, profile or downloaded package was replaced.

Validation and limits:

Host tests cover actual process ownership and external SIGKILL cleanup, late-born
children, another tracked session remaining alive, native launcher polling,
inotify download completion, panel fallback, window-event coalescing and report
budgets. X11 commands are test fixtures; no X server is available here. A live
system D-Bus host test was refused by the host's AF_UNIX policy, so its startup
state machine is tested with controlled probe/daemon results. No Realme device
or authenticated publisher app session is connected to this build environment.

Android native-child trimming and memory pressure remain possible explanations
for the reported SIGKILL. This release reduces concrete sources of process load
and fixes missing cleanup. It does not prove that the first forced kill is
prevented, that ChatGPT sign-in succeeds, or that Windows compatibility is complete.

References: [Chromium D-Bus disconnect handling](https://chromium.googlesource.com/chromium/src/+/HEAD/dbus/bus.cc),
[Android native-child tracking and trimming](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/am/PhantomProcessList.java),
[Android memory killer](https://source.android.com/docs/core/perf/lmkd),
[D-Bus daemon options](https://dbus.freedesktop.org/doc/dbus-daemon.1.html).

## 10.5.10 — startup, browser process load and PRoot shutdown repair

The owner reports that 10.5.5 did not resolve Chrome sign-in or desktop failures.
The supplied Wine diagnostic adds evidence: prefix files continue growing beyond
the old 120-second cutoff, reach 701 by 200 seconds, then all disappear from the
process's view along with /usr/bin/timeout. The early cutoff is demonstrably too
short for that attempt. The later path loss suggests a detached PRoot process;
it does not prove that a particular Wine DLL is missing or identify who killed PRoot.

This release changes the following integration paths:

- Stop, cancel and startup-failure cleanup first request PRoot's own shutdown, then
  use a bounded fallback restricted to previously identified same-UID processes,
  checked by PID and start time. A new container waits for preceding cleanup.
  The old immediate tracer SIGKILL could leave Linux children running untraced.
- Removed the recursive ownership walk through every app profile/cache on every
  desktop open. Unchanged assets are no longer rewritten/fsynced each time.
  Optional audio starts after the display, with bounded operations; readiness
  checks use elapsed deadlines. The session bus needs fewer background processes.
- Chrome's recognized official wrapper can start its adjacent native ELF without
  two persistent output-pipe children, retaining its required environment.
  Linux launches no longer need a separate GTK progress process; one supervisor
  records and redacts output/exit status, and the launcher shell can finish once
  the app window appears. Window checks inspect window-owner PIDs, not repeated
  full process scans. These changes reduce overhead and process-count pressure.
- Fixed env-prefixed desktop commands, catalogue-wrapper sandbox flags and hidden
  system/per-user protocol entries. HTTP(S) MIME links use the browser dispatcher;
  OAuth return links preserve the running app and its profile. Chrome aliases
  share a startup lock/log. The browser handoff report contains no sign-in URL.
- Viewer handshakes have a timeout, while healthy idle sessions do not. Startup
  and later connection outages have separate retry budgets. The status control
  can reconnect without leaving the desktop. Linux is not restarted automatically.
- Rapid Stop/Open requests carry task ownership: an older worker cannot clear a
  newer desktop's running state, notification or wake lock. Java and Linux output
  readers finish after their direct process exits even if a child retains stdout.
- Wine initialization gets a bounded 480-second ceiling, periodic progress samples
  and guest-filesystem checks. Lost guest access is reported as exit 76 and stops
  renderer retries, package repair and unsafe cleanup. Existing downloaded MSIX
  files remain available. Additional installed-runtime checks cover ARM64 COM,
  services/setupapi, 32-bit setupapi/rundll32, and wine.inf.
- Linux app reports now include Chrome, browser handoff, the previous desktop
  session, and a host-side runtime/viewer report. Copy all gathers bounded reports
  with URL queries redacted. Runtime metadata excludes argv, environment and tokens.
- Production compilation uses the Android SDK boot class path, so desktop JDK
  methods cannot silently compile into an APK that lacks them. Process PID lookup
  uses the Android-compatible representation and validates /proc identity.

Install as an update. After saving work, restart the phone once before testing:
that clears any detached children left by earlier force-kill attempts. Do not
uninstall PocketLinux or clear its data. Open the desktop, check Chrome, then test
the installed Linux ChatGPT login. Windows setup is a separate test using the
previously downloaded package. The new Wine ceiling is a limit, not a promise
that setup must finish in eight minutes.

Host regression tests exercise real subprocess shutdown/ownership, inherited
output pipes, browser dispatch, callback reuse, Wine stage decisions and VNC
protocol behavior. No Realme or authenticated publisher session is connected to
this build environment. Actual Chrome page loading, ChatGPT Linux sign-in,
ChatGPT Windows compatibility and sustained phone performance remain unverified.
The Android native-process limit is not disabled, and CPU/thermal safeguards
remain active. This release does not claim that all reported phone issues are solved.

Implementation references: [PRoot signal handling](https://github.com/termux/proot/blob/master/src/tracee/event.c),
[Android native-child trimming](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/am/PhantomProcessList.java),
[Chromium Linux wrapper](https://github.com/chromium/chromium/blob/main/chrome/installer/linux/common/wrapper),
[Electron URL opening](https://github.com/electron/electron/blob/main/shell/common/platform_util_linux.cc).

## 10.5.5 — Linux browser sign-in and desktop stability

This update addresses source-level causes of failed browser launches and avoidable
resource pressure. The supplied screenshot shows a disconnected viewer during ChatGPT
sign-in; it does not include a crash log or establish why the Linux process ended.

- Electron's direct xdg-open calls and the BROWSER fallback now use PocketLinux's
  browser launcher, with the existing browser profile and PRoot-compatible flags.
  Browser dispatch returns while the browser remains open. Custom app callbacks still
  use the installed package's MIME handler and the existing ChatGPT process/profile.
- Linux resolv.conf now follows the active Android network's IPv4/IPv6 DNS servers,
  including mobile/Wi-Fi changes. A temporary loss of network information retains the
  last configuration. Setup no longer overwrites carrier DNS with public DNS.
- A new browser launch is deferred below 450 MB available RAM; callbacks to an already
  running browser/app remain allowed. No other app or browser tab is closed to make room.
- ChatGPT no longer inherits the artificial 384 MB V8 old-space limit. This removes a
  possible heap-exhaustion cause; V8 selects its own limit. Software Mesa/OpenMP worker
  pools are limited to two threads. This is not a guarantee against Android memory kills.
- The keyring and desktop now use one session bus. Asset refresh replaces script files
  atomically so an app install cannot truncate a script that is still executing.
- A transport disconnect is distinguished from a stopped Linux computer. Sender-side
  connection closures now enter the existing bounded reconnect path too.

Install this APK as an update, then stop and reopen the Linux desktop once. Installed
apps, browser cookies, ChatGPT profile, Linux files, and existing Windows support are
retained. Reinstalling Linux or downloading ChatGPT again is not required for these fixes.

Host regression tests cover real launcher process reuse, browser URL argument integrity,
callback delivery at low memory, cold-browser memory rejection, DNS network handover,
and resolver symlink handling, alongside the existing suite. They do not run the
publisher's ChatGPT or Chrome ARM64 binaries on a Realme C25s. Actual account sign-in,
carrier reachability, Android process limits, and sustained device performance still
require a phone test. Linux DNS uses normal DNS queries; this is not an Android Private
DNS or VPN implementation. The APK does not make ChatGPT's online service work offline.

Implementation references: [Android network callbacks](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback),
[V8 old-space limits](https://nodejs.org/api/cli.html#--max-old-space-sizesize-in-mib),
[Mesa worker settings](https://docs.mesa3d.org/envvars.html).

## 10.5.0 — software rendering during Wine setup and current-attempt reports

The 10:10 report on 10.4.5 shows a Wine prefix timeout before ChatGPT started. EGL is
now available, but the private VNC display reports unavailable DRI3 acceleration and
Wine reports COM/RpcSs failures. These warnings do not identify a single proven cause.

The Apps-tab installer previously supplied software rendering only when launching the
app. It now supplies Mesa software rendering and the X11 platform before the private
display, setup engine, wineboot and registry configuration start. llvmpipe is limited
to two rendering threads to avoid its default per-core thread pool on this 4 GB device.
Fresh prefixes use wineboot --init; existing prefixes use --update. The 120-second
setup limit and stop-before-renderer behavior are retained.

Wine COM warnings and service startup traces are captured during initialization.
On failure the report includes the first errors plus a bounded read-only snapshot of
prefix files and identified Wine processes before cleanup. It does not read registry
contents or print process arguments/environment. E_NOINTERFACE alone can hide the
earlier factory/registration failure; the new diagnostic output is intended to expose it.

Settings → Windows install report and Copy now select the latest job, preserving its
date and final output with a bounded text size. History retains earlier attempts.
Old SIGSYS logs no longer obscure a later prefix timeout. Saved MSIX packages and
previously verified apps remain intact on failure.

Host regression tests exercise the installer/opener environment, fresh-prefix mode,
timeout propagation without renderer retries, read-only diagnosis, latest-job reports,
and existing download/app preservation. They do not execute the official ChatGPT
Windows app on RMX3197 or verify sign-in. COM/RpcSs recovery and device performance
remain unverified; this build does not claim a guaranteed ChatGPT launch.

Upstream references: [Mesa environment variables](https://docs.mesa3d.org/envvars.html),
[Mesa EGL](https://docs.mesa3d.org/egl.html),
[Ubuntu wineboot manual](https://manpages.ubuntu.com/manpages/noble/man1/wineboot-stable.1.html),
[Wine COM marshaling implementation](https://github.com/wine-mirror/wine/blob/master/dlls/combase/marshal.c).

## 10.4.5 — EGL/Mesa repair and accurate Wine setup failures

The 03:04 report on 10.4.0 reused ChatGPT-arm64.msix and started its private X display,
then wineboot failed to load libEGL.so.1 and timed out with exit 124. ChatGPT's renderer
never started. The earlier SIGSYS/profile-loop output in that report belongs to an older job.

Windows support now explicitly installs Ubuntu's libegl1, libegl-mesa0, libgl1, libglx-mesa0,
libgl1-mesa-dri and libgles2. A bounded native loader check resolves EGL/GL dispatch libraries,
the Mesa EGL vendor configuration and the software DRI driver, including their dependencies.
This catches optional dlopen libraries that Wine's --version and ldd(winex11.so) cannot prove.
It requires no display. The readiness marker is revised so older installs receive the check.

A missing graphics dependency repairs that package set before considering a Hangover bundle
download. Repair uses apt --reinstall --no-remove, retains saved app packages and existing
profiles, and stops if the native loader check still fails. A failed repair never publishes
the ready marker. The normal ChatGPT INSTALL job performs the support update automatically.

Missing EGL/GL during wineboot is now recognized as a shared-runtime failure. A Wine profile
timeout without a missing-runtime signature preserves exit 124 through the installer and queue,
reports the 120-second profile stage and stops before any renderer profiles. It no longer becomes
the generic exit 19 or falsely implies that ChatGPT's own window was tested.

Validation covers native dependency errors, Mesa configuration, a complete-layer/missing-EGL
repair with no Hangover download, failed repair without readiness publication, existing MSIX
and app preservation, and prefix timeout propagation with no renderer attempt. Host fixtures
do not prove that the official ChatGPT Windows app or its sign-in works on the Realme C25s.
The remaining COM/RpcSs errors may have another cause; installing EGL is not proof they are fixed.

Package references: [Ubuntu libegl1](https://packages.ubuntu.com/noble/libegl1),
[Mesa EGL ARM64 files](https://packages.ubuntu.com/noble/arm64/libegl-mesa0/filelist),
[Mesa DRI](https://packages.ubuntu.com/noble/libgl1-mesa-dri).

## 10.4.0 — Linux app startup, sign-in handoff and viewer stability

Linux launch supervision now identifies native executables by device/inode, with ancestor/tracer
exclusion, instead of matching shell command-line substrings. A per-app startup lock suppresses
repeat icon taps while Electron is starting. A hidden or still-starting instance receives callback
arguments without being killed. Callback exit codes are preserved, the browser is left open,
and authorization URL queries are redacted. Native startup output appends instead of replacing
the earlier crash report. Stale singleton cleanup is scoped to that app's user-data folders.

Removed the Linux no-window/CPU-idle kill and hidden second-instance/SIGKILL restart paths.
Startup observation is bounded to five minutes, after which a still-running process is kept and
reported. A new heavy Linux launch is deferred below 700 MB available memory; existing apps and
sign-in callbacks can still run. This is a guard, not a promise about any app's total memory use.
Settings → Linux app reports exposes ChatGPT, Claude, Cursor, Antigravity and desktop session logs.

The viewer closes sockets on handshake failure and attempts up to three transport reconnects to
an already-running desktop. It does not restart Linux. Framebuffers are reused at the same size,
and old buffers are freed before allocating a resized pair; UI allocation failures produce a
visible error. ServerInit and resize dimensions are checked before allocating bitmaps. Destroying
the viewer releases its framebuffers and pending view callbacks.

Validation uses native test processes, X/notification doubles, protocol tests and the existing
regression suite. These tests do not execute the official ChatGPT app or sign into an account on
a Realme C25s. Vendor crashes, ARM64/PRoot compatibility, Android process eviction and future
vendor releases remain outside a guarantee. Existing installed Linux apps and user-data are kept.

Upstream contracts reviewed: Electron's second-instance deep-link handling
(https://www.electronjs.org/docs/latest/tutorial/launch-app-from-url-in-another-app) and the
publisher's Linux package/update instructions (https://learn.chatgpt.com/docs/linux/linux-app).

# PocketLinux 10.3.5 — correct Windows launch arguments and isolated cleanup

The 10.3.0 report showed four failed launch profiles followed by a `Bad system call` cascade
and exit 137. Code inspection found two deterministic launch bugs: Chromium flags were placed
before the EXE (`wine --no-sandbox ChatGPT.exe`), and the verifier prepared an isolated Wine
prefix but launched the app with the shared prefix still inherited. Wine now receives
`wine ChatGPT.exe --no-sandbox …`; preparation, registry setup, launch and cleanup all use the
same per-app prefix.

Windows cleanup no longer signals command lines merely containing the app's folder. It requires
an exact prefix and Wine/PE executable identity, protects the supervisor's ancestor/tracer tree,
and checks PID birth time again before escalation. SIGSYS/blocked syscalls and SIGKILL abort the
job immediately instead of launching more profiles. The supplied phone log does not prove who
sent SIGKILL or what caused SIGSYS; these fixes do not claim that every compatibility failure
has been solved.

On devices with up to 4 GB RAM the smaller-memory profile runs first, using a 384 MB V8 heap limit
(not a total app-memory cap). Four bounded profiles replace five attempts; a critically low
available-memory check stops another attempt. Headless verification avoids invisible progress
dialogs/notifications, prints each attempt's actual errors immediately, and saves successful
profiles only after a stable product window. Existing downloads and previously verified apps
survive failed updates. Desktop idle/session timers are deferred while installation is active;
the job's heat, battery and data guards continue to apply.

Validation covers the real installer-to-opener command/prefix boundary using Wine/X test doubles,
real native process cleanup isolation, ancestor/tracer/PID-reuse protection, low-memory ordering,
stop-on-SIGSYS/SIGKILL, previous-install preservation and the existing regression suite. The
ARM64 Windows ChatGPT binary has **not been run on the user's Realme C25s by this build environment**.
Official Windows packages remain an experimental Wine/Hangover compatibility path; login, every
desktop integration, Windows Store updates and future vendor releases are not guaranteed.

# PocketLinux 10.3.0 — display-independent Windows-layer verification

**The exact RMX3197 `nodrv_CreateWindow` report was a verifier error, not another broken
Hangover installation.** Version 10.2.5 had already passed the package, checksum, private-library
and runner checks. Its final layer check then launched Windows `cmd.exe` from the Apps-tab
background job, where no X display intentionally exists. Even a console Windows process starts
Wine's GUI/RPC services, so Wine correctly reported that no display driver could create a window
and PocketLinux incorrectly rejected the complete layer.

**Layer setup no longer starts a Windows program without a display.** It now proves every required
Hangover package and runtime file, validates the X11 module in Wine's private loader context, and
runs the native `wine --version` plus `wineserver --version` checks only. The existing Windows-app
queue remains responsible for the stronger end-to-end proof: it starts a private Xtigervnc display,
launches the actual publisher executable there, and publishes the app icon only after a real mapped
window stays open. A regression double fails any accidental display-less PE launch with the same
`nodrv_CreateWindow` text seen on the phone.

The four installed Hangover packages, completed layer bundle and saved ChatGPT MSIX are reused.
Updating PocketLinux does not remove Ubuntu, Linux apps, app prefixes, downloads or sign-ins.

# PocketLinux 10.2.5 — correct Hangover private-library verification

**Version 10.2.0's new check found the right files but invoked `ldd` in the wrong loader
environment.** Hangover keeps its Unix-side `ntdll.so` and `win32u.so` beside `winex11.so` under
`/usr/lib/wine/aarch64-unix`; Wine adds that directory when it loads the driver. Plain `ldd` does
not, so it printed both sibling modules as “not found” even though the official packages had just
installed successfully. The RMX3197 report proved this was PocketLinux's false rejection, not
another failed Hangover download.

**The verifier now recreates Wine's real lookup context.** It prepends the module's own directory
to `LD_LIBRARY_PATH`, separately requires and package-checks `win32u.so`, then still rejects any
genuinely unresolved external X11/system dependency. A regression test uses an `ldd` double that
reports the exact two false “not found” lines unless that private directory is present.

The already installed four Hangover packages and the saved ChatGPT MSIX are reused. Because the
10.2.0 failure retained the verified layer cache and never published a ready marker, 10.2.5 simply
rechecks the installed files, runs the Windows boot smoke test and publishes readiness; it does not
need another app-package download.

# PocketLinux 10.2.0 — verified Windows runtime repair and display gate

**The exact RMX3197 `WINMM.dll` / `WS2_32.dll` / graphics-driver failure is now a layer repair,
not a ChatGPT failure.** Version 10.1.95 trusted four dpkg status strings and a one-word marker.
That database can say “installed” after a stopped or mixed same-version package transaction even
when the files Electron needs are absent. Version 10.2.0 invalidates that old marker and requires
the official Hangover runner, wineserver, native X11 modules, Windows system DLLs and all three
FEX/Box64 bridge DLLs to exist. It checks the launch-critical files against dpkg's package
checksums, rejects unresolved native libraries and runs a fresh `cmd.exe` boot smoke test before
publishing the new `hangover-runtime-v2` marker.

**Repair restores bytes even when apt says the same version is already installed.** All four
verified ARM64 packages from the current official Ubuntu 24.04 Hangover bundle are passed back to
apt with `--reinstall`. The recovery transaction keeps that same local package set attached; it no
longer runs a generic fallback that could remove Hangover and silently put native-only Ubuntu Wine
back. Downloads, Linux apps, user files and existing isolated Windows-app data are not changed.

**Wine can no longer be launched with an empty display.** The background installer proves either
the open desktop display or its private Xtigervnc display first. If neither is ready it stops before
Wine with an exact report, instead of producing `nodrv_CreateWindow` and blaming ChatGPT. If an app
launch still exposes a missing core DLL or X11 driver, the queue repairs the verified layer once
and retries the already-downloaded package once. A second runtime failure stops; it cannot loop.

The Windows layer's displayed storage figure is corrected from 900 MB to about 2.4 GB installed,
with 4 GB of temporary headroom before a first Windows-app job begins.

# PocketLinux 10.1.95 — Android no-exec policy recovery for Wine

**The new RMX3197/Android 13 report identifies the layer below Wine.** ChatGPT's official ARM64
MSIX and Hangover both installed, but Android denied executable mappings for Wine's `ntdll.dll`
and `ChatGPT.exe` with `noexec filesystem`, followed by a stack overflow. PocketLinux was compiled
for API 35 and targeted API 35; since Android 10, that target policy deliberately removes
`execute`/`execmod` permission from writable app-private files. PRoot's packaged loader kept normal
Linux commands working, but Wine must map every dynamically installed Windows PE/DLL section as
executable and cannot replace that kernel permission with a command-line flag.

**This sideload now uses Android's dedicated compatibility domain for executable containers.** The
APK still compiles against API 35, still requires Android 10/API 29, and keeps all runtime API
guards, but its target is intentionally API 28—the last AOSP `untrusted_app_27` policy that permits
`app_data_file` execute/execmod for terminal-style apps. A build test prevents a future target bump
from silently breaking Wine again. Updating in place preserves Ubuntu, downloads and logins; some
OEMs cache the old process policy, so one phone restart after this particular update is required
before retrying the already-downloaded MSIX.

**Failure is now early and exact.** The Windows report records the effective Android target and
SELinux process domain. `wineboot` is checked before any of the five renderer profiles; if an OEM
still returns `noexec filesystem`, the install stops immediately instead of spending nine minutes
on irrelevant GPU retries and distinguishes “restart once to activate the new domain” from a vendor
kernel that refuses the mapping even there. The official package remains kept and no false desktop
icon is published.

# PocketLinux 10.1.90 — Windows ChatGPT real-device launch recovery

**The exact RMX3197/Android 13 exit-19 path is fixed in the launcher.** Version 10.1.85 proved
that OpenAI's ARM64 MSIX downloaded and unpacked correctly, but then passed a Windows Electron
binary Linux-only process switches such as `--no-zygote`, `--in-process-gpu`, renderer limits and
site-isolation changes. Windows apps now use clean, independent profiles. The installer tries
SwiftShader, standard GPU, software GPU, low-memory and plain modes in a fixed bounded order, and
saves only the first profile whose real publisher window remains mapped for eight seconds.

**The current official package's startup path was inspected, not guessed.** Its Electron bootstrap
supports disabling the Windows Store updater with `CODEX_SPARKLE_ENABLED=false`. PocketLinux sets
that only in local Wine mode because an extracted MSIX has no Microsoft package identity; updates
still come from the publisher's stable ARM64 MSIX when the Apps row is tapped. Wine's isolated
prefix is initialized once before Electron is timed and declared as Windows 10, satisfying the
package's 10.0.19041 manifest floor without pretending that Wine is Microsoft Windows.

**Verification is stricter and its report is useful.** A Wine helper that owns the X window is now
matched through the app's unique `WINEPREFIX`, avoiding a false “no window” result. Native error
dialogs cannot count as the product window. Stale Chromium locks are cleared only inside that
Windows app's prefix, not from Linux Chrome. Every attempt records the exact profile, runner,
Wine version, prefix, memory, Wine errors and ChatGPT's own newest log; all attempts stop within
nine minutes and the complete app-install has a 35-minute hard ceiling. The already-downloaded
MSIX and every existing Linux app remain untouched on failure or retry.

# PocketLinux 10.1.85 — exact ChatGPT branding and real-window verification

**“Unpacked” can no longer masquerade as “Installed.”** For ChatGPT, Claude, Cursor and
Antigravity, PocketLinux now starts the verified ARM64 entry point on the open desktop or a private
off-screen X/Openbox display. The app must map a real window and keep it stable for eight seconds
before its install marker, Android **Installed** state or desktop icon is published. A splash-only
crash, blank startup or missing packaged API stays a failure; the downloaded file and previous
working copy remain untouched, and the exact per-app launch log is appended to the Windows report.

**The ChatGPT black-screen launch profile is fixed.** The old Windows wrapper forced
`--disable-gpu`, while current ChatGPT asks Electron for GPU access during startup. ChatGPT now
keeps access available through the SwiftShader renderer shipped inside OpenAI's package, with
software drawing and the Linux-only Ozone flag removed. Already installed verified launchers are
regenerated from the current APK on every desktop refresh, so launch fixes do not require another
750 MB download.

**Branding comes from the publisher package.** The row, desktop and menu now use the exact name
**ChatGPT**, not “ChatGPT + Codex · Windows”. The Android row uses the current official ChatGPT
mark from OpenAI's stable ARM64 MSIX (package `OpenAI.Codex` 26.901.2854.0 at build time); each new
MSIX install extracts its own current manifest version and largest official icon automatically.
The Windows package remains a Wine/Hangover compatibility attempt: Microsoft Store package
identity and Windows-only services cannot be made 100% equivalent without Windows, so PocketLinux
now proves what it can actually open instead of promising an untested success.

# PocketLinux 10.1.80 — installed Windows app icons repair themselves

**A desktop refresh no longer deletes Windows-app shortcuts.** The Windows installer correctly
published ChatGPT/Codex (or another verified app), but `pocketdesk-menu` then treated every
`pocketdesk-*.desktop` file as a disposable generated wrapper and deleted the permanent
`pocketdesk-win-*.desktop` entry. The verified program folder and isolated prefix survived, which
is why Android said Installed while the desktop showed no icon.

Windows launchers are now permanent inputs to the menu refresh, appear both on the desktop and in
the Tux Apps menu, and are excluded from generated-wrapper cleanup. Every refresh also reconstructs
a missing launcher from the installed folder's `entrypoint` and executable `launch.sh`; installing
this APK therefore restores an already-installed ChatGPT/Codex icon without another download or
setup. New installs retain their display name for the same repair path.

# PocketLinux 10.1.75 — Hangover replaces conflicting Ubuntu Wine reliably

**The exact 10.1.65 device failure is fixed.** The current official Hangover Noble/ARM64 tar has
four packages, but an older Ubuntu `wine` installation conflicts with `hangover-wine`; the previous
repair fallback could silently keep native Wine and remove Hangover again. PocketLinux now discovers
`.deb` files at any depth in the publisher tar, flattens them, verifies package name and ARM64
architecture, requires Wine plus the ARM64EC, x86 FEX and Box64 engines, removes only the known
native Wine conflicts, and installs the verified local package set together. Success is published
only when all engine packages are installed and an executable listed by `hangover-wine` itself is
found. A stale native `/usr/local/bin/wine` link can no longer win runner selection.

**Retry is resumable and diagnosis is exact.** A partial or already-complete `.part` bundle is
proved and reused, and the large cache is deleted only after the complete layer passes verification.
Five named phases appear with the existing elapsed timer. If installation still fails, the report
contains each required package's real dpkg state and audit output; the earlier native Wine fallback
is restored without touching Linux apps, downloaded Windows installers, prefixes, sign-ins or files.

# PocketLinux 10.1.70 — every desktop window stays inside the screen

**Chrome, tools, file pickers and Windows installers can no longer keep an off-screen edge after
rotation.** Normal app windows still open maximised; dialog and utility windows now open centred.
An event-driven boundary guard reads Openbox/tint2's live usable work area, including a top or
bottom panel, and shrinks or moves only a floating window whose decorated edge crosses it. It runs
when a window appears, when portrait/landscape changes and when the panel moves—not in a permanent
polling loop—so it does not spend battery scanning an idle desktop. Minimized and intentional
full-screen windows are left alone.

**The rule reaches old Linux computers on their next open.** The current helper and Openbox rules
are copied into an existing container without reinstalling Ubuntu. “Fit window to the screen” and
panel-edge changes also trigger an immediate fit, and stronger edge resistance makes it harder to
drag a floating window beyond the visible boundary.

# PocketLinux 10.1.65 — Windows apps install from the Android Apps tab

**The desktop no longer has to be open for a Windows app install.** ChatGPT+Codex, Claude, Cursor
and Antigravity now have first-class Windows rows in PocketLinux's Android Apps tab. A completed
matching EXE/MSIX/AppX in Computer Downloads, Shared or permitted Phone Downloads is discovered
and shown as **INSTALL** with its filename; the exact file is reused. With no local file, **ADD**
resolves the publisher's current official ARM64 package, downloads to an atomic resumable `.part`,
then installs it. The first app automatically adds the full Hangover/Wine layer before continuing.
When the desktop is closed, Wine Setup receives a small private temporary X display that is removed
at the end; this avoids the headless setup failure without holding the full desktop in memory.

**It is a real foreground job.** Home and the notification show the current phase, curl percentage
when available, and elapsed time from the first tap, including a ten-second heartbeat while a
Windows setup is otherwise silent. The partial download survives interruption, the download has a
two-hour per-job ceiling, the setup engine has a 22-minute ceiling and the whole app-install stage
has a 30-minute hard ceiling, accidental second taps are locked out, and a safe wake-lock lets the
job continue with the screen off or another app in front
(subject to Android/OEM battery settings, which the confirmation warns about).

**The exact report is outside the desktop too.** Settings → Windows install report reads, copies and
clears the raw resolver, curl, package-inspection and Wine/setup output. Compatibility-layer output
from an automatic first install is mirrored there as well. A verified entry point is still required
before **ADDED** or a desktop icon appears; a failure stays a failure and the downloaded package is
kept for retry.

# PocketLinux 10.1.60 — bounded Windows setup with visible phase

**A failed setup can no longer keep retrying for hours.** The real-device Cursor attempt exposed
that three setup formats across three Hangover engines each inherited a 20-minute timeout. Cursor's
current package is known Inno Setup, so it now receives only Inno flags, the post-install launch is
disabled, FEX remains first, and the whole setup pipeline has one 22-minute ceiling. Individual
fallbacks are shorter and the progress window names the engine and its maximum time.

**Stopping and retrying is clean.** A retry removes only disposable `.new` staging siblings left
by a stopped attempt; the downloaded installer and any published app remain untouched.

# PocketLinux 10.1.55 — local ARM64 EXE and full-trust MSIX installer

**The Windows-package route is local-first again.** PocketLinux now recognises ARM64 `.exe`,
`.msix`, `.appx` and their bundle formats. NSIS and Inno setup shells are handled separately;
when Cursor's ARM64 package arrives inside an Intel setup wrapper, the installer explicitly tries
Hangover's FEX and Box64 setup engines. It publishes an icon only after the actual application
entry point—not the setup stub or a large helper—has been verified as ARM64.

**ChatGPT/Codex MSIX is handled without pretending Microsoft Store exists.** The package or ARM64
member of a bundle is extracted with path traversal protection, its AppxManifest executable is
selected exactly, and that full-trust desktop program is launched through its own Wine prefix.
Claude and Antigravity setup styles use the same staged pipeline. Each app has a separate prefix,
settings survive an update, two taps cannot start two installers, progress stays visible, and the
complete last result is written under App reports.

**The boundary remains explicit.** These are publishers' real Windows ARM64 packages running on
the phone through a compatibility layer, not a Windows kernel. Microsoft Store identity, native
Windows notifications/shell services, kernel drivers, Hyper-V, Windows Sandbox and Windows APIs
not implemented by Wine cannot be promised. The optional real-Windows connection remains available
as a fallback but is no longer presented as the primary install route.

# PocketLinux 10.1.50 — one honest route for all four genuine Windows apps

**Real Windows connection is now a first-class mode.** The Apps tab can install Ubuntu's ARM64
FreeRDP client and creates a **Real Windows** desktop icon. It opens a Windows 10/11 Pro or
Enterprise PC (or ordinary RDP cloud machine) inside PocketLinux, with dynamic sizing, sound,
microphone, clipboard, automatic reconnect and the Linux `Projects` and permitted `Phone` folders
shared as Windows drives. The connection form remembers only the address and username; the
password is requested for each connection and passed to FreeRDP over stdin, never stored or placed
in the process command line. The server certificate uses trust-on-first-use rather than being
silently ignored.

**The two Windows routes are no longer mixed together.** Real Windows is the common route for the
genuine ChatGPT/Codex, Claude, Cursor and Antigravity Windows apps, including Store/MSIX and other
Windows-only services. Local Wine/Hangover stays available for traditional ARM64 Win32 `.exe`
files such as Cursor's ARM64 installer, with its experimental boundary still visible. PocketLinux
does not bundle Windows, a Microsoft licence or a paid cloud computer; the real-Windows mode needs
a Windows machine the owner already controls, reached over the same Wi-Fi or a private VPN.

# PocketLinux 10.1.45 — current Hangover bundle and visible Windows setup progress

**The Windows-layer update now follows Hangover's current release format.** Hangover 11.16 no
longer attaches separate `.deb` files: its Ubuntu 24.04 ARM64 release is one `.tar` containing the
four packages. The old updater searched only for `.deb` URLs, found none, and silently kept
Ubuntu's native-only Wine. That Wine can run a native ARM64 executable but cannot run Cursor's
Intel setup wrapper, producing “Cursor's Windows Setup could not run.” PocketLinux now selects the
exact Noble ARM64 bundle, extracts it, installs all contained packages together, and replaces the
stale Wine link with Hangover's runner. Older loose-`.deb` releases remain supported.

**A manual Windows install now stays visible and cannot be started twice.** A pulsing desktop
progress window remains up while the silent setup runs, the result is saved to
`App reports/windows-install.log`, and an install lock turns an accidental second tap into a clear
“already running” message. The already downloaded Cursor installer is reused.

# PocketLinux 10.1.40 — current Cursor Windows Setup completes through Hangover

**The exact 3.19.7 failure is fixed without another 195 MB download.** Cursor's current Windows
ARM64 file is an Inno Setup executable. 7z could open its outer resources but could not expose the
application, producing "Unpacking Cursor… No program was found inside Cursor." PocketLinux now tries
safe archive extraction first and, only for an official ARM64-labelled Cursor setup filename, runs
the small installer silently through the Windows layer into a staging directory. It verifies the
resulting `Cursor.exe` as ARM64 before replacing an existing install or creating the launcher.

# PocketLinux 10.1.35 — Cursor Windows ARM64 installers are recognised correctly

**Cursor's official Windows ARM64 package is supported by the experimental Windows layer.** Its
installer may be an Intel NSIS setup stub even though the real Cursor program inside is ARM64.
PocketLinux previously read only that outside stub and falsely called the whole download Intel/AMD.
It now recognises the publisher's architecture-bearing filename for preflight, unpacks nested
Electron payloads, and verifies the actual program's PE architecture before installing. The
desktop menu links to Cursor's official download page; choose **Windows (ARM64) (User)**. An actual
x86/x64 program inside is still refused without replacing an existing working install.

# PocketLinux 10.1.30 — Linux package downloads no longer organization-blocked

**The Chrome policy regression shown on the Cursor download page is fixed.** PocketLinux kept
Enhanced Safe Browsing but also forced Chrome's enterprise dangerous-download restriction. Chrome
can classify ordinary Linux installer types such as `.deb` as dangerous, so the deliberate,
official ARM64 download became an unchangeable "Blocked by your organization" result. That forced
restriction is gone while Safe Browsing remains enabled. Every desktop start rewrites the policy,
so an existing computer is repaired by updating PocketLinux and restarting it; Chrome, Linux apps,
accounts and files do not need to be reinstalled.

# PocketLinux 10.1.25 — Android 13 desktop launch fixed, honest installs, complete file flow

**The black screen / return to Home is fixed at its two causes.** `DesktopActivity` created its
microphone bridge while Java was still constructing the Activity, before Android had attached a
base context. On Android 13 that could end the Activity before `onCreate`. At the same time the
Application ran a nested main `Looper.loop()` and swallowed the first lifecycle failure, leaving
Android to report the secondary `TopResumedActivityChangeItem{onTop=false}` error shown by the
Realme RMX3197. The bridge is now created after `super.onCreate`, Android owns the only main Looper,
desktop launches are debounced/singleTop, and a launch failure stays on a recovery screen with its
original report instead of silently returning Home.

**The disproved Cursor-for-Windows shortcut is removed.** The endpoint labelled ARM64 delivered an
Intel/AMD program on the reported phone. PocketLinux no longer downloads it or advertises the other
AI apps' Windows packages. ChatGPT, Claude, Cursor and Antigravity use their supported Linux ARM64
installs. Wine remains an optional, clearly experimental route for traditional ARM64 Win32 `.exe`
files. Microsoft Store/App Installer/MSIX/AppX require real Windows services and are now refused
before anything changes. The Windows layer row also checks the marker its installer really writes.

**Downloads have one explicit destination.** Settings → Data and files → Downloads go to offers
Ask every time (recommended), private Computer Downloads, or the phone's public
`Download/PocketLinux`. Chrome policy, XDG file dialogs, Firefox fallback, Files and both installers
share it. Revoked phone access falls back safely; changing it never moves or deletes an old file.

**The computer is more complete without pretending to be Windows.** Tools → Software searches and
installs native ARM64 packages from Ubuntu's configured signed apt repositories, updates installed
software and opens downloaded packages through the safety checker. Phone app testing now installs
and automatically launches an APK, remembers its package, filters logcat to that app when possible,
and still supports another real phone and scrcpy over Wireless debugging.

# PocketLinux 10.1.20 — build an Android app here, and test it on this very phone

**Apps tab → Mobile app development** (about 700 MB) installs Java 21, Gradle, `adb`, `fastboot`,
`aapt` and `scrcpy` — all from Ubuntu's own ARM64 archive, nothing fetched from Google. Gradle is
configured for a 4 GB phone before it is ever run: no daemon, a 1 GB heap, no parallel workers.

**The part worth having: test on a real phone, including this one**

Android 11 and later have **Wireless debugging**, which listens on the phone's own network — and
this computer shares that network. So `127.0.0.1` reaches the phone it is running on. Build an APK
here, install it here, and it opens on the same screen a moment later. No cable, no PC. Another
phone on the same Wi-Fi is the same steps with its address.

Desktop → **Tools → Phone app testing**: pair a phone (it walks through turning Wireless debugging
on and takes the pairing code), connect, install an APK, watch logcat, mirror the screen with
scrcpy, or see what is connected.

**And a straight answer to "what can I actually build here?"**

A new FAQ entry, checked against what really installs and runs on ARM64 rather than what sounds
good:

- **Works properly** — web and back-end (Node, Python, Go, Rust, PHP), anything an AI agent
  writes and runs, scripts, data, APIs, bots, Git
- **Android** — Kotlin and Java compile, and you can install and test on a real device. One real
  limit: Google publishes no ARM64 Linux `aapt2`, so a full Android Gradle build may stop at that
  one tool. Said before you install, not after
- **iOS** — no, and no trick changes it. Xcode and the Simulator are macOS programs
- **Not possible** — Android emulator, Docker, virtual machines (all need hardware virtualisation
  no app on an unrooted phone can have), and anything needing a graphics chip
- **How heavy** — one AI app plus a build is the ceiling on 4 GB; a big compile takes minutes
  where a laptop takes seconds. It finishes. For more, the agents here can drive a bigger machine
  over SSH

# PocketLinux 10.1.15 — Wine was installed all along

The report finally said it, in apt's own words:

> `wine64 is already the newest version (9.0~repack-4build3).`
> `0 upgraded, 0 newly installed, 0 to remove.`

**Wine was installed.** PocketLinux was looking in the wrong place and reporting it as a failed
download.

Ubuntu's `wine64` package on ARM64 installs exactly two files:

```
/usr/lib/wine/wine64
/usr/lib/wine/wineserver64
```

and **nothing at all in `/usr/bin`**. The launcher lives in the separate `wine` package, and on
Ubuntu it is not even called `wine` — it is `wine-stable`. So `command -v wine64` found nothing on
a computer where Wine was complete and working, and the owner was told to try again on a better
connection, three times, over a download that had already finished.

**Fixed**

- The layer now looks where Wine really is: `/usr/local/bin/wine`, `/usr/bin/wine`,
  `/usr/bin/wine-stable`, `/usr/lib/wine/wine64`, `/usr/lib/wine/wine`.
- Both packages are installed, not one: `wine64` is the engine, `wine` is what puts a launcher on
  the path at all.
- Whatever the packaging called it, it gets **one name**: `/usr/local/bin/wine` is linked to the
  real binary, so the installer, the launchers it writes, and the owner in a terminal all say
  `wine`. `wineserver` too.
- The installer script looks in the same places, so a computer that already has Wine from
  somewhere else works without reinstalling anything.
- The diagnostic that said "ready-made packages found: 1" was counting the word "none". It counts
  packages now, and a failure also lists what is actually in `/usr/lib/wine`.

**A test that would have caught it.** The finder is lifted out of the install command itself —
not copied — and pointed at a tree where Wine exists only at `/usr/lib/wine/wine64` with nothing
on the path. It must find it. Put the old path-only check back and that test fails.

# PocketLinux 10.1.10 — the report now carries what the computer actually said

The Windows layer failed again, and the report said only this:

> *"The Windows layer could not be installed. Nothing on the Linux side changed… Try again on a
> better conn…"*

That is PocketLinux's own sentence, cut off at 150 characters, and none of it explains anything.
The container had said exactly what went wrong — and it was thrown away twice over.

**Two faults, both fixed**

1. **The report was being truncated.** The same 150-character trim that keeps the notification to
   one line was also applied to the copy saved for the report. A notification has to be short; a
   report has to be whole, or the owner copies out a message ending in an ellipsis and nobody can
   help them from it. The report is now untrimmed.
2. **The container's own output never reached it.** Only one of the two places a failure can be
   caught had the lines, and the Windows layer failed in the other one. The last twelve lines the
   container printed are now kept in one place that both handlers read, cleared at the start of
   every job, and recorded whether or not anything was listening — which is exactly when they
   matter, because the job is about to fail.

So the next failure of anything — a Windows layer, an app install, a set-up — carries **"What the
computer said last:"** and apt's own words underneath it.

# PocketLinux 10.1.05 — a false alarm about your own phone, silenced

The error the new report screen caught first was not PocketLinux's. It was this, on a Realme phone
running Android 13:

> `Activity client record must not be null to execute transaction item:`
> `TopResumedActivityChangeItem{onTop=false}`

That is a race inside Android itself: the system tells a screen it is no longer on top *after*
that screen's own record has already gone. No app can prevent it, and nothing the owner does
causes it. PocketLinux survived it — that part worked — but then told the owner their computer had
hit an error, which was a false alarm about their own phone.

**Now**: Android's own teardown races are still recorded in the report, because a lot of them
would mean this app is closing screens badly — but the owner is not told. Anything that really is
PocketLinux's fault still says so.

**And a guard that was missing.** Catching every main-thread error for ever sounds safer than it
is: an error that repeats on every turn of the loop would spin the processor and empty the
battery while the screen looked normal. After twelve in one minute, the next one is left alone —
Android ends the app, the report is on disk, and the owner opens it instead of watching the phone
get hot.

**A new test, CrashTest**, runs that judgement against the exact error this phone reported, a
wrapped copy of it, one recognisable only by its frames, and two real faults — because getting it
wrong in either direction is expensive: a real bug hidden for ever, or crying wolf every time
Android closes a screen awkwardly.

# PocketLinux 10.1.00 — "See Last error report" now has a report to see

The app has been telling people to look at something that did not exist. When something went
wrong it saved a full report to `last-crash.txt` — and **nothing in the app ever read it**. The
message said "See Last error report"; there was no such screen.

**Settings → Permissions → Last error report**

- Says whether anything has gone wrong, and when
- Opens the whole thing: date, Android version, phone model, PocketLinux version, and the error
- **Copy** puts it on the clipboard, so it can be pasted into a message. An owner with no PC
  cannot read a log file, but they can paste one
- **Clear** empties it

**Failures that were handled are kept too**

A set-up or an install that fails explains itself in a dialog, and the dialog took the
explanation with it when it was dismissed. Those reasons — including what the container itself
said last — are now written to the same report, so the answer is still there an hour later.

This is the release to have before reporting anything: whatever goes wrong next, the exact reason
is one tap and one Copy away.

# PocketLinux 10.0.95 — the Windows layer says why it failed

10.0.90's Windows layer failed on the reference phone with nothing but *"The Windows layer could
not be installed."* That message is useless, and this release fixes the useless part first.

**Every failure now carries what the computer actually said**

A failed install shows the last few lines the container printed, under **"What the computer said
last:"**. An install that fails with no reason leaves an owner with nowhere to go; the computer
almost always said exactly what went wrong one line earlier, and now that line reaches the
screen.

**The Windows layer itself is more robust and more talkative**

- **Nothing in it can end the command by accident any more.** It runs under `set -e`, where a
  step that simply finds nothing counts as a failure; every step is now explicitly allowed to
  come back empty, and the only exit is the deliberate one at the end that knows whether there
  is a working Wine.
- **The Hangover package match is looser.** It looked for a name with `ubuntu-24.04` before
  `arm64`, in that order. Now it takes any ARM64 `.deb` in the release, preferring one that also
  names this Ubuntu — so a change in how the project names its files cannot break it.
- **Ubuntu's Wine is tried by name and in two ways**, because the `wine` metapackage cannot be
  installed on ARM64 at all: it wants a 32-bit half that ARM64 has no version of.
- **When it still fails it prints why**: how many ready-made packages it found, and the last
  lines of apt's own error.
- **No more `su -`.** Under PRoot that goes through PAM and fails on some phones. The Wine folder
  is created and handed to the owner instead, and Wine builds its own prefix the first time a
  program starts — which is one fewer thing that can go wrong.

**A test that would have caught it**

The Windows layer is now run for real in the test suite with nothing reachable — no network, apt
failing — and must reach its own exit 16 *and* say why, rather than dying part-way and being
reported as a download problem.

# PocketLinux 10.0.90 — a Windows app installs like a Linux one now

The gap this closes: a Linux app was one tap, and a Windows app was "here is a website, good
luck". Two changes fix that.

**Cursor for Windows: one tap**

A new row on the Apps tab. Cursor publishes an endpoint that answers with the address of its
*current* Windows ARM64 build, so PocketLinux asks it, downloads the file itself and installs it —
no page to read, no version written into the app that could go stale, no file to find afterwards.
It uses the same downloader as everything else, so it resumes, it respects the mobile-data limit,
and it pauses when the phone gets hot.

**Everything else: the desktop notices the download for you**

Antigravity, Claude and ChatGPT build their download pages in the browser, so there is no fixed
address to fetch. For those, download inside the desktop's own browser (Tools → Windows apps) —
and then nothing has to be found:

The desktop now **watches the Downloads folder**. The moment a `.exe`, `.msix`, `.msixbundle` or
`.appx` lands, it reads which processor the file was built for and:

- **ARM64** → a notification, and the installer opens by itself
- **Intel / AMD only** → a notification saying it will not run here, so the next download is the
  right one

It uses inotify, not a timer: it sleeps until the kernel says a file finished writing, so it
costs nothing while nothing is downloading. Each file is offered once.

**Also**: exit codes 17, 18 and 19 say plainly whether the Windows layer was missing, the
download failed, or the file was not the ARM64 build — instead of one number.

# PocketLinux 10.0.85 — Windows where you can see it, and the phone's own words for settings

**Windows apps are on the Apps tab now**, in their own card, next to the Linux ones:

- **Windows apps support** — the layer itself, one tap, like any other app
- **Cursor, Antigravity, Claude and ChatGPT for Windows** — each a row that opens the
  publisher's own download page, and says honestly how likely it is to work: Cursor's ARM64
  installer is the best bet, ChatGPT's Store package the worst
- Every file is still checked before anything is unpacked

The Home screen says so too — the set-up card mentions Windows apps, and the opening screen now
reads **"Ubuntu 24.04 LTS · Linux and Windows apps · the whole computer is on this phone"**.

**Settings use the phone's own words**

A setting here should read exactly like the same setting on the phone:

- Theme: **"Match phone" → "System default"** — Android's own name for it
- Screen rotation: **"Automatic" → "Auto-rotate"**

**One thing that had become untrue** was fixed: the "Does not work" list still said Windows `.exe`
files do not work. They do now, when they are built for ARM64, so the line is gone.

# PocketLinux 10.0.80 — Windows apps, as a separate layer that cannot break anything

A second kind of app can now be installed: **Windows programs built for ARM64**. It sits *beside*
the Linux side, never on top of it.

**How to use it**

1. Apps tab → **Windows apps support** (about 900 MB). It installs Wine.
2. In the desktop: **Tools → Windows apps** → pick Cursor, Antigravity, Claude or ChatGPT, and
   the browser opens their download page.
3. Open the downloaded file with **Install a downloaded app**, exactly like a `.deb`.

**The processor is checked first, in one second, before anything is unpacked**

PocketLinux reads the file's own PE header — the two bytes that say which processor it was built
for — and answers before a single megabyte is spent:

- **ARM64** (also ARM64EC) → installs
- **Intel / AMD only** → refused, with the reason. Translating every instruction is not something
  this phone can do at a usable speed, so it says so instead of wasting the download
- **Not a Windows program** → refused rather than guessed at

`.exe`, `.msix`, `.msixbundle`, `.appx` all work. Installers are **unpacked, not run** — an
installer stub is often 32-bit Intel even when the program inside is ARM64, so running it would
fail on a file that would itself have worked. A Store bundle holds one program per processor;
the ARM64 one is taken out.

**Wine comes from Hangover when it can**

Hangover is Wine 11 with the newest ARM64 support and ships packages for Ubuntu 24.04 on arm64.
The release is looked up **on the phone at install time**, not written into the app, so this keeps
working when a new Hangover appears. Ubuntu's own `wine64` is the fallback — older, but always
there.

**It cannot break the computer**

Separate folder, separate prefix, separate launchers, its own stage marker, its own exit code.
If every line of the Windows layer fails, the computer is exactly as it was. Removing "Windows
apps support" removes Wine, the prefix and every Windows program with it, and touches nothing
else.

**Said plainly, in the app**

This is **experimental**. A Windows app may open, may look wrong, or may not start at all — the
FAQ says so, the install dialog says so, and the Apps row says so. All four AI apps already have
Linux ARM64 builds that run faster here, so the Windows route is really for programs that have
no Linux version at all.

**Also**: a new test suite, **WindowsApps**, builds real PE files for ARM64, ARM64EC, x64 and x86
and checks every verdict, that an Intel-only app is refused *before* unpacking, that a refused
install leaves nothing behind, and that the installer refuses outright when no Windows layer is
present.

# PocketLinux 10.0.75 — a privacy monitor, a camera that needs no camera permission, and the honest OS table

**Privacy monitor**

Settings → Permissions → **Privacy monitor** lists every permission this app holds, read off the
phone's own package rather than a hand-written list — so a permission added in a future version
appears there by itself. Each says what it is for and whether it is on right now.

It also lists what PocketLinux **never asks for**, so the absence is checkable rather than
promised: camera, location, contacts, calls, messages, sensors. A cross means the permission is
not in the app at all, so no dialog for it can ever appear.

Android's own "Only this time" / "While using the app" choice applies to the microphone — the new
**Microphone** row in Settings shows which one is in force, and both are enough.

**A camera, without a camera permission**

Screen → **Take a photo into the computer** hands you the phone's own camera app and drops the
picture straight into the computer's Pictures folder. Because it asks the camera app rather than
the camera, **PocketLinux holds no camera permission at all** — and the Privacy monitor proves it.

A live camera *inside* Linux is not possible and the app now says exactly why rather than leaving
it vague: Chrome looks for `/dev/video0`, and creating one needs a kernel module, which no app on
an unrooted phone can load. That is Android's rule, not this app's.

**Live voice and screen share — both work**

With the microphone in place, a live voice conversation runs in the browser: ChatGPT's and
Claude's own voice modes hear you and answer through the phone's speaker. Chrome can also share
this desktop's screen or a single window into a meeting, exactly as on a PC. Both are in the FAQ
with the honest boundary drawn around them.

**"Do Mac, Windows and Linux get the same features?"**

A new answer, because the pattern decides what is worth chasing:

- **Cursor and Antigravity: identical on all three.** Both are VS Code builds — one codebase ships
  everywhere at once. There is no "Mac first" here.
- **ChatGPT and Claude: macOS first, Windows next, Linux last.**
- What stays Mac-only is always the same kind of thing — something calling the OS's own
  frameworks: Codex Appshots (not even on Windows), the apps' own Computer Use, Claude's
  Dictation and Cowork, Xcode and the iOS Simulator.
- PocketLinux answers the first two itself, with its own appshot and its own click/type/scroll.

# PocketLinux 10.0.70 — the microphone, Cmd-Cmd, and sign-ins that are not in plain text

Three of the ⚠️ rows in the feature table become ✅. Everything here is local and free.

**The computer can hear you now**

The phone's microphone is handed to Linux as an ordinary recording device — inside it appears as
**"Phone microphone"**, and every program finds it: a voice reply in an AI app, a meeting page in
the browser, dictation. The desktop makes a named pipe, PulseAudio reads it as a source, and
PocketLinux's Android side records at 16 kHz mono and writes into it.

Three rules it keeps, because a microphone is the one thing an owner should never have to wonder
about:

- **Off at every start.** Nothing is remembered as "always on".
- **It asks the phone's own permission the first time**, and that can be taken back at any moment
  from the phone's app settings.
- **It stops the instant the desktop screen is left** — to another app, to the lock screen, to
  Home. The screen says so when it does. With nothing recording, the pipe has no writer and the
  microphone simply reads as silent, not as one that is listening and discarding.

Screen → **Microphone** turns it on. Android's own microphone dot shows the whole time.

**Super+Space — PocketLinux's Cmd-Cmd**

One key, and whatever is on screen goes to the AI app. It captures the window in front (never
the AI app's own window), reads its words with Tesseract, puts the picture on the clipboard, then
brings the AI app forward and pastes it. With no AI app open it stops at the clipboard and says
so, so a capture is never lost. Every one is saved in **Pictures/Appshots**, picture and text
side by side. Also in the wallpaper menu under Tools.

**Sign-ins are encrypted, not written in plain text**

Electron's `safeStorage` keeps an app's token encrypted — but only where libsecret finds a
keyring. With none, every Electron app on Linux quietly falls back to plain text, which is how
the four AI apps were storing their sign-ins here. `gnome-keyring` is installed and unlocked at
session start with a key made once on this phone, so the tokens are encrypted the way they are on
a Mac. Android's app sandbox is still the real lock; this stops a token sitting in a config file
in the clear.

**Honesty, kept**

Two privacy answers said PocketLinux holds no microphone permission. That is no longer true, so
they now say exactly what is true instead: the microphone is the one permission the app can have,
it is yours to give, and it is never active after you leave the desktop screen.

# PocketLinux 10.0.65 — set-up that still works in three years

The Ubuntu download was pinned to one point release, `24.04.4`, with its digest compiled into the
app. Canonical eventually prunes older point releases from that directory, and on the day it
prunes this one, a phone setting up for the first time would get a 404 and no computer at all —
from an app nobody had touched.

Set-up now falls back to the newest ARM64 base image Canonical publishes for 24.04 LTS, and
checks it against the digest published beside it in the same `SHA256SUMS` file, over HTTPS to
Canonical's own host. The pinned file stays the first choice, so nothing changes while it exists;
the fallback only runs when it is gone. A download that cannot be verified is still deleted.

# PocketLinux 10.0.60 — the audit release: 17 confirmed defects, including three of my own

A seven-dimension audit (55 agents, every finding re-checked by a second reviewer told to refute
it) went over 10.0.55 the day it was built. It confirmed 17 real defects — **four of them
critical, and three of those introduced by 10.0.55 itself**. All are fixed here. 10.0.55 should
not be used.

**The heat pause did not work at all**

The pause added in 10.0.55 was unreachable. The monitor's first if/else chain already ended the
job for heat and returned, so the pause code below it could never run: heat still *killed* the
set-up, exactly as before. The thermal branch is gone from that chain, and the pause is now the
only thing that answers a hot phone. If an OEM refuses to let the app signal its own child, it
falls back to the old stop with a clear message instead of working a hot phone regardless.

Two more holes in the same area, both found by the audit:

- **Stopping a paused job could wedge the app.** A frozen container can act on nothing but
  SIGKILL, and the stop path only ever sent the polite signal. It now thaws first and forces the
  kill through, and clears the pause state whenever a new job starts.
- **The wake lock expired after exactly two hours and was never renewed**, so a set-up slower
  than that lost the processor with the screen off and apt died at its own timeout — the other
  half of "it stops again near the end". It is now a lease, renewed every half minute for as
  long as the job runs.

**Chrome and the AI apps could not install at all after 10.0.55**

The freshness cache added in 10.0.55 was called by `pd_repo`, the helper that writes a new
repository and then proves it. A repository written one second ago is in no index that has been
fetched, so `apt-get install google-chrome-stable` had nothing to install — and the same held for
Claude Desktop and Antigravity, which are installed the same way. `pd_repo` now always fetches.
There is a regression test that fails if that force is ever removed again.

**The freshness cache measured the wrong thing**

It read the date on apt's index files — which apt copies from the mirror, so it is the archive's
publish date, not when this phone last fetched. A list could be called stale minutes after being
downloaded, or fresh when it was days old. PocketLinux now writes its own stamp when an update
actually succeeds, and reads that; a missing, empty, corrupt or future stamp all mean fetch.
"Update the computer's basics" always forces a real fetch, because finding new versions is its
whole job.

**Losing the set-up's proof cost 550 MB**

The only record that Ubuntu had finished unpacking was a preference written asynchronously, and
starting set-up overwrote it with "started" before anything else. If Android ended the app in
that window, the next run saw no proof, deleted the whole system and downloaded it again. The
proof is now a file inside the container itself, written and flushed to disk; the preference
stays as a fallback so a phone already part-way through is not wiped by this update.

**Less data still**

- `restricted` and `noble-backports` are no longer fetched: on arm64 they supply not one package
  this computer installs, and cost about **4.7 MB of every package-list download**. `multiverse`
  stays, so "apt install any ARM64 program" keeps meaning what it says.
- **`locales` (4.2 MB) and `xfonts-base` (5.9 MB) are no longer installed** — nothing here used
  either; the computer runs on the C.UTF-8 built into libc, and every font is named through
  fontconfig.
- The kept package lists are **stored gzipped**, about a quarter of the space.
- **Chrome's 133 MB download gets three attempts**, like every other step. It had exactly one.

**Man pages really work now**

`man-db` and the manuals were downloaded and then thrown away: the base image ships its own dpkg
rule dropping every man page, and dpkg reads the directory in name order, so that rule was read
after PocketLinux's and won. PocketLinux's fragment is renamed so it is read last. `man git`,
`man apt`, `man bash` — the claim 10.0.50 made is true from this release.

**And two ways out of a dead end**

- A Chrome failure now says *"Google Chrome did not install"* on screen. It was being shown as
  *"Installing Google Chrome"*, because the failure line contains the word Chrome.
- Settings → **Update the computer's basics** now appears whenever the computer has no browser at
  all, not only when the app version has moved on — so there is a way to try Chrome again.

# PocketLinux 10.0.55 — the set-up finally finishes, and the AI can see the screen

**The set-up that kept stopping**

The cause was PocketLinux's own heat guard. A Helio G85 running `dpkg` under PRoot for half an
hour gets hot — hotter still on the charger the app tells you to use — and at 49 °C the guard
**ended** the set-up: it destroyed the container mid-`apt`, which is why it stopped part way,
stopped again near the end on Continue, and why the same packages were paid for twice on mobile
data.

Heat now **pauses** the work instead of ending it. The container is frozen with SIGSTOP, so it
uses no processor at all and the phone cools fast; every byte already downloaded is kept, and
`dpkg` is never cut off between unpacking and configuring. Below 43 °C it carries on by itself.
The screen says so, with the temperature. Only a phone that stays too hot for 45 minutes is
stopped for real — and even then nothing is downloaded twice.

**Less mobile data, every time**

- **The package lists are kept.** They were deleted at the end of every set-up and every app
  install, which meant apt had to fetch about 40 MB of index again before the *next* install
  could start. The 550 MB of `.deb` files is still reclaimed; only the index stays.
- **A list less than 12 hours old is reused**, so installing two apps in an evening downloads
  the index once, not twice.
- **No more pipelining.** Carrier proxies mangle pipelined requests, apt reads that as a
  corrupted package and downloads it again. One request at a time is slightly slower per package
  and much cheaper overall.
- **IPv4 is forced for apt too.** A mobile network that advertises IPv6 it cannot route made
  every fetch wait for the v6 attempt to time out.
- **The screen now counts the megabytes** as they arrive: "Downloading packages · 180 MB
  downloaded · 12 min so far". You can see it moving, and see it stop.

**PocketLinux's own Appshot and Computer Use**

Codex's Appshots are macOS-only; Claude Desktop's Computer Use is not in the Linux beta. Neither
is coming to a phone, so PocketLinux provides the capability itself, over MCP, from parts the
desktop already had:

- **appshot** — a picture of the window in front *and* the words on it (read on the phone by
  Tesseract, which set-up now installs)
- **click, type_text, press_key, scroll** — working that window, with clicks outside it refused
  unless the agent says it means it
- **list_windows, focus_window, run_in_terminal**

It registers itself for **Codex** (`~/.codex/config.toml`) and **Claude Code** (`claude mcp add`)
at every desktop start, and any other agent can use it with `python3 /usr/local/bin/pocketdesk-mcp`.
Tools → **AI computer use** on the desktop shows whether each one is wired up. Nothing is watched
in the background and no picture leaves the phone to be read.

**A terminal worth working in**

Git branch and a red mark for a failed command in the prompt; 50,000 lines of history shared
between windows; colour `ls` and `grep`; `ll`, `la`, `..`, `df`, `free`; `EDITOR`, `PAGER`,
`LESS`, a UTF-8 locale so Devanagari and emoji render; `TERM=xterm-256color` and `COLORTERM` so
an agent's output is in colour; bash completion; and `keep`, which is `tmux` — a command an agent
starts survives the session being stopped.

**Also**

- New tools installed with the basics: `tmux`, `tesseract-ocr`.
- New test suite **McpServer**: the desktop's MCP server is exercised the way an agent meets it —
  a real `initialize` and `tools/list` over stdin — so a broken one fails the build.
- Open-source notices now cover the MCP server, Tesseract and tmux.

# PocketLinux 10.0.50 — the desktop release: a finished computer, in its own words

Nine researchers looked at how real Linux desktops are built and what this one was missing, and
the twenty-three changes they agreed on are here. Nothing needs to be set up again for most of
it: start the desktop once and it rebuilds its own bar, theme and menus.

**The desktop itself**
- **A real bar along the bottom.** Openbox's windows now sit above a tint2 panel that carries the
  Apps button, the browser, Files, the Terminal and your phone's folder; a button for every open
  window; the system tray; this phone's battery, temperature, free memory and free storage; a
  12-hour clock; and the PocketLinux mark in the far corner, which shows the desktop. Tap a window
  button to raise it, hold it to minimise. **The bar can move to the top**: long-press the
  wallpaper and choose "Move the bar to the top" — the desktop remembers.
- **A watchdog for the bar.** If tint2 ever refuses PocketLinux's settings, the desktop notices
  within twelve seconds, sets that file aside and starts tint2 with its own defaults. There is no
  longer a way to end up with no bar at all.
- **No more washed-out white.** GTK is told to use Adwaita **dark** — the old line asked for a
  theme called "Adwaita:dark", which does not exist, so every GTK app fell back to white. The
  root window, the terminal's palette, the on-screen messages, the window frames (a PocketLinux
  Openbox theme) and the tooltips are all one dark navy set now.
- **A new wallpaper**, drawn for this release: 1600×1600, PocketLinux's navy with a soft blue
  glow, Tux, and the words "PocketLinux · Ubuntu 24.04 LTS".
- **Bigger, tappable title bars** — 14 pt, with minimise and close at the left edge where a
  maximised window always starts.
- **Both bottom corners do something.** Bottom left is the Apps button; bottom right is the
  PocketLinux mark that minimises everything and shows the desktop.
- **New in the menu:** Fit window to the screen (Super+F), Minimise this window (Super+M),
  Screenshot (Super+S, saved to Pictures), Storage, and a Tools submenu so the four AI apps stay
  at the top where they belong.

**What you can see about this phone, inside the computer**
- The bar shows battery %, free storage, battery temperature and free memory in two short lines,
  and the full picture in its tooltip. **Tapping the numbers opens Storage**, which explains how
  much room the computer may use — all of the phone's free space, with no quota of its own.
- The Settings tab says the same thing in the app: what the computer is using, what this phone
  has free, and what happens as that runs down.

**Sound and volume**
- The volume keys now show **"Media volume · 60 %"** with a bar, the step (6 of 15) and the
  stream named, because media is the only sound this app carries. **Mute** was added to the keys
  and to the Screen menu, and the keys keep working while App lock is covering the desktop.
- A volume key can no longer crash the desktop: if Do Not Disturb is holding media volume,
  the desktop says so instead of stopping.
- The desktop's own output is set to full and unmuted at every start, so the phone's keys stay
  the one control that matters.

**More of a computer, out of the box**
- Set-up now installs colour emoji and Noto's Indian and other scripts (Devanagari, Bengali,
  Tamil, Arabic, Hebrew, Thai and more — Hinglish and Hindi text finally render), the manual
  pages, locales, `lsb-release` and bash completion; a text editor, an archive tool, a picture
  viewer, a calculator, a task manager, the volume mixer, screenshots, `ripgrep`, `xclip`; and
  `python3-dev` and `sqlite3` for the developer tools.
- **A failure in the everyday programs can no longer cost you a desktop**: that step is allowed
  to fail and set-up carries on, saying so in the log.
- Man pages work: `man git`, `man apt`, `man bash`. They were being thrown away at unpack time
  to save about 3 MB, which was never worth it.

**Words that are now exact**
- The app says what this is and what it is not: a real Ubuntu 24.04 LTS system on the phone's own
  Linux kernel, in a PRoot container — not a second operating system, not dual boot, not an
  emulator, not Canonical's own Ubuntu Desktop.
- New answers: **"Is the desktop GNOME, KDE or Cinnamon?"** (none of them — Openbox, tint2,
  PCManFM, LXTerminal, dunst, PulseAudio and TigerVNC, each named), **"Is this a complete
  operating system?"**, **"How many apps can I have open at once?"** (one AI app plus about three
  light windows, and why), and **"Is this a basic computer or a full one?"** — basic on purpose,
  complete for the job, and yours to make as advanced as you like with `apt`.
- The size figures are the measured ones: about 30 MB, then about 550 MB of packages, 2–3 GB
  finished, 15–45 minutes.
- A **Terminology test** now fails the build if a banned claim ever reaches the app's text, if an
  Ubuntu-branded image is ever added, or if the Canonical trademark line goes missing.

**Fixed**
- The desktop's app list could not be built at all when the panel-edge setting was read after the
  menu that offers it — a `set -u` failure that would have left the desktop without menus.
- Storage is now measured by asking Android (the same figure its own Settings screen shows)
  instead of walking 200,000 files; the walk is still there for any phone that will not answer.
- An installed computer with 5 GB free no longer shows an orange "low space" tile: the 6 GB line
  is what set-up needs, not what running needs.

# PocketLinux 10.0.45 — the audit release: 53 confirmed defects fixed

A ten-dimension audit of the whole app (77 agents, every finding re-checked against the code by
a second reviewer that tried to refute it) found 53 real defects. All of them are fixed here.
The ones that would have cost the owner something:

**Security**
- **The desktop and its sound no longer listen on a network port.** Android does not keep
  loopback apart between apps, so 127.0.0.1:5901 (with no password) and the audio port could be
  opened by any other app on the phone holding the ordinary internet permission — the screen,
  the keyboard and the sound of every AI conversation. Both now travel over unix sockets inside
  this app's private storage, which no other app can open; a container too old for that falls
  back to the port, so sound and screen never simply stop working.
- **The app lock hides the window from the recents list whenever it is on**, not only once the
  lock screen is already up — Android takes that thumbnail as the app leaves the foreground.
- **Sign-in callbacks are no longer written to the app log.** A chatgpt:// or claude:// callback
  carries the account's authorisation code, and the log is shareable.
- **Every claim about protection is now literally true.** "No open network ports" was false;
  "verified by their signature" was false for ChatGPT and Cursor, which publish a plain .deb
  download (Claude, Antigravity and Chrome do come from signed repositories, and now say so
  separately); "Phone files shares Download, DCIM and Documents" understated a share of the
  phone's whole shared storage.

**Nothing of the owner's is ever deleted by accident**
- **Set-up can no longer wipe a working container.** If the file that proves the desktop is
  installed went missing (an apt removal, an interrupted upgrade), the home screen said "Not set
  up" and its only button deleted 4.5 GB — every app, sign-in and project. It now repairs the
  computer instead, and deleting stays behind the Settings dialog that says it cannot be undone.
- **"Copy your files into Downloads/Shared before uninstalling" was wrong in three places.**
  Both folders belong to the app and Android deletes them with it; the app and the quick start
  now say to move files onto the phone itself.
- **Rebuilding the app list no longer deletes files the owner put on their desktop** — only the
  entries PocketLinux itself wrote are cleared.

**Set-up, installs and the desktop**
- Set-up no longer fails at the last step when Phone files is on: the final ownership pass used
  to walk into the phone's own storage, hit a folder Android hides, and throw away a finished
  40-minute install.
- A vendor repository that stops answering is removed again instead of being left behind to
  fail every later install; a container already poisoned by one heals itself.
- Installing Claude no longer drags in 149 KDE packages (~154 MB) through an unnamed alternative.
- Installing an app while the desktop is open no longer kills the panel for the rest of the
  session, and a panel that died can now come back.
- The desktop waits up to two minutes for its display instead of four seconds, so a slow first
  start no longer produces a desktop with no wallpaper, no panel and no icons.
- Set-up and installs are now covered by the phone's safety stops (heat, 3 % battery, the daily
  mobile-data limit) — the longest and hottest job in the app had none.
- The 3 % battery stop no longer switches itself off with Overheat protection, and an overheat
  message names the sensor that actually tripped.
- An install and a desktop session each hold their own wake lock, so one finishing can no longer
  suspend the other's download; whichever finishes last takes the notification down.
- Deleting the computer or starting set-up while the desktop runs is refused with a reason
  instead of being queued behind the whole session, which used to leave every button grey.

**The screen and the keyboard**
- Showing the key row or hiding the bars no longer resizes the whole Linux desktop and throws
  away the owner's zoom; only a real rotation does that.
- The control bar and key row ride above the on-screen keyboard instead of hiding under it.
- A paired keyboard or mouse now counts as the owner being there, so Smart stopping cannot close
  a session someone is typing in.
- The keyboard field no longer swallows the volume rocker.
- A session that has ended is dimmed and says so, instead of showing a frozen picture that
  answers nothing.
- Copy and paste of non-ASCII text is no longer mangled (RFB clipboard is Latin-1, not UTF-8).

**Everything else**
- The launcher's "Open desktop" shortcut works on a cold start again (it did nothing, then
  opened the desktop unasked at some later moment).
- "Screen rotation → Automatic" takes effect immediately instead of at the next app start.
- The container's clock follows the phone's own time zone instead of being fixed to one country.
- The size of the computer is measured once a minute at most, never twice at once, and never by
  a thread that holds the screen it was started from.
- The first-run permission walkthrough is marked as seen when it is answered, not before it is
  shown.
- The panel's temperature reading no longer prints 2500 °C on kernels that report thousandths.
- Force close and Close all no longer skip a window because its title contains "Desktop".
- The installer: dialogs no longer vanish because a Maintainer's e-mail looks like Pango markup;
  a package that would delete software already installed is blocked with its name; a control
  tarball cannot hide its setup scripts from the warning; and a failed install repairs dpkg
  instead of claiming nothing was changed.
- The open-source notices now ship inside the APK (Settings → Open-source notices) and name this
  app rather than a different product; the credits line that promised them is true.
- The signing key is kept with the project, and a build that cannot find it says so loudly and
  names its APK -devkey, so an APK that cannot be installed over an existing PocketLinux can
  never be handed over as the release.
- Tests: a version-agreement suite (build.sh, MainActivity and the release notes must match, and
  the number must end in 0 or 5), the sound port is read from the Java constant instead of being
  copied, and the desktop's private sockets are asserted.
- Version 10.0.45 (code 145).

# PocketLinux 10.0.40 — install an app you downloaded, the way a phone does it

- **An installer for apps you download yourself.** Downloading a .deb in Chrome inside the
  desktop and tapping it used to do nothing at all; the advice was a terminal command. Now it
  opens PocketLinux's own installer — the screen Android shows for an APK, which a Linux desktop
  has never had. It names the app, its version and its publisher, and shows its size against
  the space this phone has free at that moment.
- **Four checks before anything is installed**, each with a plain reason:
  processor (a build for Intel and AMD is blocked — a phone needs the ARM64 one), space
  (blocked when it needs more than the phone has, both numbers shown), what it needs (the
  install is simulated first, so missing software is named instead of leaving a half-installed
  app), and where it came from (a downloaded file carries no signature of its own, and if the
  app is one of the four in the Apps tab it points at the signed copy there).
- **Install anyway, or blocked with the reason.** A risk you can judge ends in *Install anyway*,
  exactly as Android does for an app from outside the Play Store. Something that cannot work
  here is blocked, never half-done. AppImage files are refused with the reason (they need FUSE,
  which a phone container cannot provide).
- **It is the handler for .deb files**, so Chrome's Open and a tap in the file manager both
  reach it, and the Apps menu gained *Install a downloaded app* for picking a file directly.
- **Every number about your phone is now read from your phone.** The install dialog says "On
  this phone: 74.0 GB free — enough (it needs 4.0 GB)", or, when it is not, what to free. The
  app's own size is the same on every phone; whether it fits is not, and the app no longer
  pretends otherwise.
- **What protects what, stated exactly.** Google Play Protect scans PocketLinux itself on the
  phone, at install and in the background — but it cannot look inside the Linux computer,
  because Android keeps every app's private files private (the same rule that stops any other
  app reading yours). So the checking inside is PocketLinux's: publisher-signed repositories for
  the Apps tab, the installer's safety check for anything you download, Chrome's Safe Browsing
  at its Enhanced level for the web, and Ubuntu's security updates with the basics update.
- New answer: *Can I install an app I downloaded myself?* — the whole flow, the four checks,
  and which numbers are per-phone.
- Tests: the installer is exercised against real packages built during the run — an ARM64
  package installs with a warning, an amd64 one is blocked naming the processor, one larger
  than the free space is blocked, a hand-downloaded copy of a published app points at the Apps
  tab, and a non-package and an AppImage are refused with their own reasons.
- **How far a bad app could get, answered honestly.** A new question sets out the boundary:
  everything in the computer runs as PocketLinux's own Android user inside its private storage,
  so it cannot read another app's data, change the phone's system or become root; what it
  could reach is what is inside the computer, plus the phone folders shared in while Phone
  files is on — which is why that is off until you turn it on; and what it can never reach is
  the camera, microphone, location, contacts, messages or your other apps, because PocketLinux
  holds no permission for any of them.
- Version 10.0.40 (code 140).

# PocketLinux 10.0.30 — a set-up that continues, and protection that is on by default

- **Set-up continues where it stopped.** It is now a chain of steps that each record when they
  finish, inside the container. Stop it, run out of battery, lose the network or have Android
  end the app: the Home card says **Part way / Continue set-up**, and the next run skips the
  30 MB download, the unpacking and every package step that already finished. The old flow
  deleted the unpacked Ubuntu and started the whole 700 MB again.
- **The "exited with code 1" set-up failure is fixed at the source.** Every package step first
  repairs an install that was cut off half-applied (`dpkg --configure -a`, `apt-get -f install`)
  — until now that state made the next attempt fail instantly — then retries three times on a
  bad connection, and gives up with its own code so the phone can say *which* part failed and
  what to do, instead of "code 1. Check Wi-Fi and free storage".
- **Set-up is faster.** dpkg no longer waits for the phone's slow storage after every single
  file (`force-unsafe-io`, what container images use), manuals and documentation are not
  unpacked at all (copyright files are kept), translation indexes are not downloaded, and no
  step is ever repeated. Same result, less time and less space.
- **Virus and malware protection, on by default.** Google Chrome inside the computer now runs
  Safe Browsing at its Enhanced level, blocks dangerous downloads, and will not let a malware or
  phishing warning be clicked through — enforced by policy, not a setting anyone has to find.
  Ubuntu's security updates come with the basics update. A new question, *Is there virus and
  malware protection?*, lays out every layer, including why a separate antivirus is deliberately
  not there on a 4 GB phone.
- **App lock now comes when it should.** With App lock on, the opening screen plays first and
  the fingerprint or PIN prompt follows it, then again every time PocketLinux comes back to the
  front — the home screen and the desktop both. Before, a locked app skipped its own opening
  screen and the prompt could be missed entirely.
- **The basics update appears only when there is one.** The computer records which version of
  the app built it, so Settings → Storage shows **Update the computer's basics** with an UPDATE
  badge only when this version has something newer — and says "Its basics are up to date"
  otherwise. That update now also installs Ubuntu's security updates.
- **Opening an app looks like opening an app.** The desktop shows the round watch pointer and a
  pulsing window — "Opening ChatGPT… 25s so far, usually 30-90 seconds, and longer the first
  time after installing" — both gone the moment the window appears. The times are per app, not
  one number for everything.
- **Words that mean what they say.** "The makers' own apps" is gone: apps are the **publisher's**
  official Linux build everywhere. AI apps are **uninstalled** (Android's word), the whole
  computer is **deleted**, and the computer's basics have no uninstall at all — they are the
  computer. The developer tools are named as *Python, Node.js, Git and a C/C++ compiler* rather
  than a list of package names.
- **"Downloads visible to the phone" is gone.** One folder, one rule: what the computer
  downloads stays inside it, where no other app on the phone can read it. The **Shared** folder
  (now bookmarked in the file manager) is the way out to the phone's Files app, and Phone files
  is the way in. *Where do my files go?* and *What if I uninstall PocketLinux?* say exactly that.
- **Why Ubuntu, answered with checked facts.** A new line in the Linux-only card: cloud AI
  agents work in Ubuntu containers (OpenAI's Codex cloud image is built on Ubuntu 24.04 — the
  release in this app), Ubuntu is the Linux developers use most (about 28 % in Stack Overflow's
  2025 survey of 49,000 developers), every publisher ships for Ubuntu and Debian first, and
  24.04 LTS has security updates until April 2029.
- Tests: the resume and retry helpers are now executed for real against a stand-in `apt-get`
  (a finished step is skipped, a flaky one is retried, a hopeless one gives up and is not
  recorded), the basics are asserted to be non-uninstallable while every AI app is, Chrome's
  protection policies are asserted, and the launcher's busy indicator is checked.
- A set-up is only continued when the unpacking is known to have finished: the recorded
  step and the unpacked system must agree, or Ubuntu is written out again from the archive
  already on the phone (no new download).
- Version 10.0.30 (code 130).

# PocketLinux 10.0.20 — one set-up does it all

- **Set up Linux installs everything.** The one button on the Home tab now brings the desktop,
  sound, Google Chrome and the developer tools (gcc and make, Python 3 with pip and venv,
  Node.js with npm, Git and Git LFS, SSH, jq, htop, tree, vim, rsync) in one go. About 700 MB of
  packages after the 30 MB base; 3.5–4.5 GB when finished; 15–40 minutes. Set-up now asks for
  6 GB free.
- **The Apps tab is the four AI apps, nothing else.** Computer basics and Developer tools are
  gone from it; a line under the intro says what set-up already installed. Settings → Storage
  gains **Update the computer's basics** for a computer built by an earlier version (it runs
  beside an open desktop like every other install).
- **What this computer is, said where people read it:** an agentic development environment —
  the makers' own AI desktop apps, Google Chrome and the developer tools they use, running
  locally — and not a feature-rich general-purpose desktop. In the Linux-only card, in "What
  exactly is this?", and in the honest limits.
- Version 10.0.20 (code 120).

# PocketLinux 10.0.15 — plain error messages, and CI

- **When an app cannot open, the desktop now says so plainly.** One title ("ChatGPT could not
  open"), one reason in ordinary words ("the phone closed it to free memory", "it crashed while
  starting", "it stopped itself with an error", or the error number), and one line of what to
  do. No log lines, no memory figures, no file paths. The other desktop toasts were shortened
  the same way ("ChatGPT is opening · 60 seconds so far. Please wait.").
- **CI for PocketLinux.** A GitHub Actions workflow (`.github/workflows/pocketdesk.yml`) runs
  the seven test suites on every push that touches `pocketdesk/`, then builds the release APK
  with the Android SDK and publishes it as a downloadable artifact. It signs with the
  repository's key when the `POCKETDESK_KEYSTORE_B64` / `POCKETDESK_STORE_PASS` /
  `POCKETDESK_KEY_PASS` secrets exist, otherwise with a key made for that run (such an APK
  cannot install over one signed with a different key; the APKs delivered in this session
  share one key).
- Version 10.0.15 (code 115).

# PocketLinux 10.0.5 — final check pass

- An app install or removal started while the desktop is open now holds a wake lock for its
  duration. The desktop itself holds none once it is up (the screen does while it is on), so a
  long download with the screen off could have stalled.
- A last sweep for leftovers from earlier rounds (Brave, Firefox, Reports, Faster desktop) in
  the app's text and the documents; one line in the quick start was still naming Brave.
- All seven test suites pass; every download endpoint was re-checked live.

# PocketLinux 10.0.1 — hotfix

- The four rows of **Linux only, on purpose** did nothing when tapped: the questions card was
  resetting the page's shared list of expandable answers after those rows had been added, so
  their taps opened the wrong answers far below. The list is now reset once, before the page is
  built. Nothing else changed.

# PocketLinux 10.0.0 — the last release

Everything from the final round of device screenshots, and nothing left that was there only for
testing. This is the version to keep.

## What changed
- **The opening.** The app's mark and name first, then Tux with "Powered by Linux · Ubuntu
  24.04 LTS" for a moment, then Home; about three seconds, on a cold start only. The system's
  own launch screen shows the app mark on the same deep blue, so the two read as one.
- **Google Chrome lives in Desktop basics only.** The browser is part of the computer, installed
  by set-up from Google's own ARM64 repository, updated by the Desktop basics row; it is no
  longer a second row of its own. Computer basics is now two rows: Desktop basics and
  Developer tools.
- **The phone's battery controls, in the phone's own words.** Settings → Permissions now has
  Notifications, Battery usage (Unrestricted), **Background activity** (Allow foreground
  activity and Allow background activity, on the phone's battery page for PocketLinux),
  **Auto-launch** (some phones call it Auto-start), Phone files and App info. The first-launch
  prompt asks for all three battery-related settings. The Background activity row opens the
  ColorOS battery page directly where the phone offers it, else App info.
- **No Faster desktop switch.** The seccomp accelerator is never used now: it is what broke
  every Chromium app, so there is no setting that can bring that back.
- **No Reports group.** The "Why an app didn't open" and "Last error report" rows are gone, as
  asked; the desktop itself still says on screen when an app is closed by the phone, and the
  Home tab still says when and why the computer last stopped.
- Every download endpoint was checked live while building this release: Ubuntu base, ChatGPT's
  arm64 .deb, Claude's key and arm64 index, Cursor's arm64 .deb, Antigravity's arm64 index,
  Chrome stable arm64 (152.0.7977.75).

## Verified again, unchanged
ChatGPT and every Chromium app start with the accelerator off and `--no-zygote` (the auto-back
root cause), uninstall with Remove, branded dialogs, chevrons that turn while open, the Apps tab
line that says why rows are grey, clear progress text, the framed viewer, the blue Tux
wallpaper, sound to the phone, the phone's battery and memory on the desktop panel.

## Permanent limits
Windows or macOS cannot run on a phone. A microphone into the computer is not carried.

# PocketLinux 5.0.0 — the final release: Google Chrome as the computer's browser, and every screen from the device screenshots fixed

Built from a second round of real-device screenshots. Version 5 because it closes the list.

## The browser: Google Chrome, and only Chrome
Google's own apt repository has published Chrome for Linux **ARM64** since July 2026, so the
answer to "can it be Chrome?" is now yes. Set-up installs **Google Chrome** (from Google's
repository, signed by Google) as the computer's one browser; the built-in GNOME Web is gone from
new set-ups and removed from older ones once Chrome is present. Brave and Firefox are gone from
the catalogue. Chrome opens every link and sign-in, keeps its extensions and sync, and runs with
the flags a container needs (`--no-sandbox`, `--no-zygote`, software rendering). Policies: blank
start page, no background mode, no "make default" prompt, no metrics, no GPU. Its package's own
amd64 repository line is replaced by the arm64 one. The **Google Chrome** row on the Apps tab
updates it, or brings it back after a Remove.

## What the screenshots showed, and what changed
- **The launch screen looked wrong** (a big app tile with a small Tux under it). It is now the
  app mark and Tux side by side, the same height, settling in with a short animation, on deep
  blue. The still frame looks right even where the animation does not play.
- **The chevron kept pointing right on an open answer.** Every question, the compatibility
  row and the attention rows now turn their chevron down while they are open.
- **The Apps tab greyed its rows with no explanation while the computer was setting up.** A line
  at the top now says why: set-up in progress, set up Linux first, an app is installing, or
  another task is running.
- **The progress card showed "Preparing … Selecting previously unselected package
  humanity-icon-theme."** Raw package lines are gone. It reads "Setting up the desktop —
  Getting packages ready · 6 min so far · usually 10–25 min", and the Set up button hides while
  work is running.
- **The AI apps card was a wall of text.** One sentence and one line now; each row carries its
  own short description ("AI assistant by OpenAI, with the Codex coding agent · 700 MB").
- **The requirements sentence appeared twice** under Your phone. Once now, inside the detail.
- **The desktop panel** now shows the phone's own battery, temperature, free memory and network
  (Wi-Fi or mobile data), read from the phone's kernel every 20 seconds.

## Also in this release
- **Terms**: ChatGPT and Claude Desktop are AI assistants with their makers' coding agents;
  Cursor is "the AI code editor"; Antigravity is "Google's agentic development platform: AI
  agents plan, write, run and test software".
- **Desktop basics** (the row) now includes Chrome, and its "installed" mark is the sound tools
  rather than the old browser.
- Sizes on rows are the download size only; installed sizes are in the confirmation.

## Verified again, unchanged
Seccomp accelerator off by default (the ChatGPT auto-back root cause), Faster desktop toggle,
uninstall with Remove, branded dialogs, framed viewer with border, blue Tux wallpaper, sound to
the phone, installs beside a running desktop, app lock that cannot throw.

## Not possible, permanently
Windows or macOS on a phone; a microphone into the computer (not carried yet).

# PocketLinux 3.4.0 — the ChatGPT auto-back root cause, one browser, uninstall, and a themed, framed UI

This release starts from real-device evidence: a ChatGPT crash log and screenshots from a Realme
C25s. The log named the cause, so this is a root-cause fix, not another guess.

## ChatGPT (and every Chromium app) closing by itself — found and fixed
The log showed the fatal line: `socket() failed: Function not implemented (38)`, with the same
`Function not implemented` on `readlink` and `unlink` of ChatGPT's own lock file, and a flood of
"Error reading message from browser: Function not implemented" from its zygote. The cause is
PRoot's **seccomp accelerator**: Chromium and Electron reset their signal handlers at startup,
which breaks the accelerator's SIGSYS-based syscall emulation, so ordinary syscalls come back
"not implemented" and the app aborts — dropping the screen back to Home.

- The desktop now runs **without the seccomp accelerator by default**, so every app's syscalls
  are traced correctly and Chromium apps actually run. A little slower, reliably working.
- A **Settings → Running → Faster desktop (experimental)** toggle turns the accelerator back on
  for anyone who wants to trade reliability for speed; a start that fails still falls back.
- Chromium apps also launch with **`--no-zygote`**, which removes the failing zygote path.

## One browser, not three
"Keep one, the best." Firefox is removed. **Brave** is the single full browser: a Chromium engine
(best compatibility with the AI sign-in pages and the largest extension library), Brave's official
ARM64 apt repository, with Rewards, Wallet, VPN, news and AI chat switched off by policy. The
built-in GNOME Web stays as the instant, lightweight default until Brave is installed; whichever
is present is the computer's one browser everywhere.

## Uninstall an app
Every installed AI app and Brave now has a **Remove** button (tap an installed row). It frees the
space and its sign-in; the computer and everything else stay. The remove runs beside an open
desktop, like installs do.

## The app-lock crash
An earlier build could crash on resume with `SecurityException: Must have USE_BIOMETRIC` on some
Realme/ColorOS phones. The lock now checks the permission first and, if it is missing, goes
straight to the phone's PIN screen — it can never throw.

## Correct terms
- ChatGPT and Claude Desktop are **AI assistants**, each with its maker's coding agent (Codex,
  Claude Code). **Cursor** is "the AI code editor". **Antigravity** is Google's "agentic
  development platform", where AI agents plan, write, run and test software.
- The Apps tab carries a quiet "ARM64 · runs locally on your phone · updates from the maker" line.
- Settings' footer credit is shorter and generic; "Android 10 and above" everywhere.

## A themed, framed desktop
- **Dialogs are branded** now (dark card, blue accent, rounded) instead of the grey Material box —
  the "Linux computer" details, every chooser and confirmation.
- The viewer no longer fills the screen edge to edge: at 100 % the desktop sits inside a small
  gap with a **rounded blue border** on a deep backdrop, in portrait and landscape, so the framed
  screen is deliberate. A **hairline divides** the desktop from the control bar.
- The desktop **wallpaper is now a deep-blue Linux one with Tux** (replacing the maroon), the
  desktop and panel **icons are larger**, and the **launch screen shows the app mark with Tux** on
  blue. The **Phone files icon** was redrawn. On the home screen the hero card and Your phone card
  have room to breathe.

## Not done, and why (unchanged and permanent)
- Windows or macOS on a phone: impossible (no hardware virtualisation for apps; macOS is
  Apple-only). Wine runs small native ARM64 Windows programs, not these Electron AI apps.
- Sound plays out to the phone; a **microphone into the computer** is not carried yet.
- The app stays plain Java (Gradle-free, reproducible); a Kotlin rewrite would change nothing
  visible and cost every tested behaviour its history.

# PocketLinux 3.3.0 — Linux only, on purpose; sound; a browser with extensions; installs beside a running desktop

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
- The APK is named like CloudSaver's: `PocketLinux-v3.3.0-release.apk`.

## Not done, and why
- **Windows or macOS on the phone:** not possible; the plan file's Wine/Hangover route runs
  small native ARM64 Windows programs, not Electron-based AI apps, and there is no route to macOS.
- **A microphone into the computer** (voice input): not carried yet; sound out only.
- **Kotlin:** the app stays in plain Java. A rewrite changes nothing the phone can see and would
  cost every tested behaviour its history; the build stays Gradle-free and reproducible.

# PocketLinux 3.2.0 — the last update: every item of the final prompt, done

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

# PocketLinux 3.1.0 — ChatGPT opens; the finishing release

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
- **App lock** — the phone's own fingerprint or PIN when PocketLinux opens; no separate password.
- Six more answers in the app: offline use, what installs from the browser and what does not, when
  the computer stops by itself and what is kept, accounts and locks, data limits, which phones.
- The card is **AI coding & desktop apps**, described as the industry's leading tools installed the
  official way, with a note that anything else can be installed from the browser inside Linux.
- The APK is simply `PocketLinux-<version>.apk`; ARM64 is checked in the app instead.

---

# PocketLinux 3.0.0 — final

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
- **Downloads reach the phone** — `Android/data/com.pocketlinux/files/Shared/Downloads`.
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

# PocketLinux 1.6.0

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

# PocketLinux 1.5.0

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

# PocketLinux 1.4.0

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

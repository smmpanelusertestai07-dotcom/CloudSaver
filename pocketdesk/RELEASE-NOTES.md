# PocketDesk 10.0.55 — the set-up finally finishes, and the AI can see the screen

**The set-up that kept stopping**

The cause was PocketDesk's own heat guard. A Helio G85 running `dpkg` under PRoot for half an
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

**PocketDesk's own Appshot and Computer Use**

Codex's Appshots are macOS-only; Claude Desktop's Computer Use is not in the Linux beta. Neither
is coming to a phone, so PocketDesk provides the capability itself, over MCP, from parts the
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

# PocketDesk 10.0.50 — the desktop release: a finished computer, in its own words

Nine researchers looked at how real Linux desktops are built and what this one was missing, and
the twenty-three changes they agreed on are here. Nothing needs to be set up again for most of
it: start the desktop once and it rebuilds its own bar, theme and menus.

**The desktop itself**
- **A real bar along the bottom.** Openbox's windows now sit above a tint2 panel that carries the
  Apps button, the browser, Files, the Terminal and your phone's folder; a button for every open
  window; the system tray; this phone's battery, temperature, free memory and free storage; a
  12-hour clock; and the PocketDesk mark in the far corner, which shows the desktop. Tap a window
  button to raise it, hold it to minimise. **The bar can move to the top**: long-press the
  wallpaper and choose "Move the bar to the top" — the desktop remembers.
- **A watchdog for the bar.** If tint2 ever refuses PocketDesk's settings, the desktop notices
  within twelve seconds, sets that file aside and starts tint2 with its own defaults. There is no
  longer a way to end up with no bar at all.
- **No more washed-out white.** GTK is told to use Adwaita **dark** — the old line asked for a
  theme called "Adwaita:dark", which does not exist, so every GTK app fell back to white. The
  root window, the terminal's palette, the on-screen messages, the window frames (a PocketDesk
  Openbox theme) and the tooltips are all one dark navy set now.
- **A new wallpaper**, drawn for this release: 1600×1600, PocketDesk's navy with a soft blue
  glow, Tux, and the words "PocketDesk · Ubuntu 24.04 LTS".
- **Bigger, tappable title bars** — 14 pt, with minimise and close at the left edge where a
  maximised window always starts.
- **Both bottom corners do something.** Bottom left is the Apps button; bottom right is the
  PocketDesk mark that minimises everything and shows the desktop.
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

# PocketDesk 10.0.45 — the audit release: 53 confirmed defects fixed

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
  entries PocketDesk itself wrote are cleared.

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
  names its APK -devkey, so an APK that cannot be installed over an existing PocketDesk can
  never be handed over as the release.
- Tests: a version-agreement suite (build.sh, MainActivity and the release notes must match, and
  the number must end in 0 or 5), the sound port is read from the Java constant instead of being
  copied, and the desktop's private sockets are asserted.
- Version 10.0.45 (code 145).

# PocketDesk 10.0.40 — install an app you downloaded, the way a phone does it

- **An installer for apps you download yourself.** Downloading a .deb in Chrome inside the
  desktop and tapping it used to do nothing at all; the advice was a terminal command. Now it
  opens PocketDesk's own installer — the screen Android shows for an APK, which a Linux desktop
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
- **What protects what, stated exactly.** Google Play Protect scans PocketDesk itself on the
  phone, at install and in the background — but it cannot look inside the Linux computer,
  because Android keeps every app's private files private (the same rule that stops any other
  app reading yours). So the checking inside is PocketDesk's: publisher-signed repositories for
  the Apps tab, the installer's safety check for anything you download, Chrome's Safe Browsing
  at its Enhanced level for the web, and Ubuntu's security updates with the basics update.
- New answer: *Can I install an app I downloaded myself?* — the whole flow, the four checks,
  and which numbers are per-phone.
- Tests: the installer is exercised against real packages built during the run — an ARM64
  package installs with a warning, an amd64 one is blocked naming the processor, one larger
  than the free space is blocked, a hand-downloaded copy of a published app points at the Apps
  tab, and a non-package and an AppImage are refused with their own reasons.
- **How far a bad app could get, answered honestly.** A new question sets out the boundary:
  everything in the computer runs as PocketDesk's own Android user inside its private storage,
  so it cannot read another app's data, change the phone's system or become root; what it
  could reach is what is inside the computer, plus the phone folders shared in while Phone
  files is on — which is why that is off until you turn it on; and what it can never reach is
  the camera, microphone, location, contacts, messages or your other apps, because PocketDesk
  holds no permission for any of them.
- Version 10.0.40 (code 140).

# PocketDesk 10.0.30 — a set-up that continues, and protection that is on by default

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
  the fingerprint or PIN prompt follows it, then again every time PocketDesk comes back to the
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
  is the way in. *Where do my files go?* and *What if I uninstall PocketDesk?* say exactly that.
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

# PocketDesk 10.0.20 — one set-up does it all

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

# PocketDesk 10.0.15 — plain error messages, and CI

- **When an app cannot open, the desktop now says so plainly.** One title ("ChatGPT could not
  open"), one reason in ordinary words ("the phone closed it to free memory", "it crashed while
  starting", "it stopped itself with an error", or the error number), and one line of what to
  do. No log lines, no memory figures, no file paths. The other desktop toasts were shortened
  the same way ("ChatGPT is opening · 60 seconds so far. Please wait.").
- **CI for PocketDesk.** A GitHub Actions workflow (`.github/workflows/pocketdesk.yml`) runs
  the seven test suites on every push that touches `pocketdesk/`, then builds the release APK
  with the Android SDK and publishes it as a downloadable artifact. It signs with the
  repository's key when the `POCKETDESK_KEYSTORE_B64` / `POCKETDESK_STORE_PASS` /
  `POCKETDESK_KEY_PASS` secrets exist, otherwise with a key made for that run (such an APK
  cannot install over one signed with a different key; the APKs delivered in this session
  share one key).
- Version 10.0.15 (code 115).

# PocketDesk 10.0.5 — final check pass

- An app install or removal started while the desktop is open now holds a wake lock for its
  duration. The desktop itself holds none once it is up (the screen does while it is on), so a
  long download with the screen off could have stalled.
- A last sweep for leftovers from earlier rounds (Brave, Firefox, Reports, Faster desktop) in
  the app's text and the documents; one line in the quick start was still naming Brave.
- All seven test suites pass; every download endpoint was re-checked live.

# PocketDesk 10.0.1 — hotfix

- The four rows of **Linux only, on purpose** did nothing when tapped: the questions card was
  resetting the page's shared list of expandable answers after those rows had been added, so
  their taps opened the wrong answers far below. The list is now reset once, before the page is
  built. Nothing else changed.

# PocketDesk 10.0.0 — the last release

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
  activity and Allow background activity, on the phone's battery page for PocketDesk),
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

# PocketDesk 5.0.0 — the final release: Google Chrome as the computer's browser, and every screen from the device screenshots fixed

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

# PocketDesk 3.4.0 — the ChatGPT auto-back root cause, one browser, uninstall, and a themed, framed UI

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

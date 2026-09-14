# Open-source notices -- PocketLinux

## What actually ships inside the APK

Four compiled files, and one configuration file. Everything else PocketLinux uses is downloaded to
the phone at set-up from its own publisher, and is covered further down under
[What set-up installs inside the Linux computer](#what-set-up-installs-inside-the-linux-computer).

| Component | Bundled version | Licence | Upstream |
|---|---:|---|---|
| PRoot (Termux build) -- `libproot.so`, `libproot-loader.so` | 5.1.107.92 | GPL-2.0-or-later | https://github.com/termux/proot and https://github.com/termux/termux-packages/tree/master/packages/proot |
| libandroid-shmem -- `libandroid-shmem.so` | 0.7 | BSD-3-Clause | https://github.com/termux/libandroid-shmem |
| talloc runtime -- `libtallocxx.so` | 2.4.3 | LGPL-3.0-or-later | https://talloc.samba.org/ |
| D-Bus system bus configuration -- `app/assets/dbus-system.conf` | 1.14.10-4ubuntu4.1 | AFL-2.1 (elected; the file is dual GPL-2.0-or-later **or** AFL-2.1) | https://packages.ubuntu.com/noble-updates/all/dbus-system-bus-common/filelist |

`libandroid-shmem` is BSD-3-Clause, not Apache-2.0. An earlier version of this file said Apache-2.0
and was wrong. Its copyright notice must be reproduced in full, and both lines of it are:

```
Copyright (c) 2013, Sergii Pylypenko
Copyright (c) 2017, Fredrik Fornwall
```

The full BSD-3-Clause text that goes with those lines is the file `LICENSE` in the upstream
repository above.

## Modifications PocketLinux made, and when

PocketLinux does not compile PRoot or talloc itself. It takes the official Termux ARM64 packages and
changes three strings inside the compiled files, each replaced by a string of exactly the same
length so nothing else in the binary moves:

- PRoot's compiled private-prefix string `com.termux` becomes `com.ndockx`.
- The runtime search path (RPATH) becomes `$ORIGIN`, so each file finds its neighbours where
  Android actually puts them.
- The dependency name `libtalloc.so.2` becomes `libtallocxx.so`, and talloc's own SONAME is renamed
  to match.

The purpose is Android's, not ours: every executable component has to sit in the app's signed
native-library directory, and Android will only load a file from there whose name matches
`lib*.so`.

**Date of modification, as GPL-2.0 section 2(a) requires.** These binaries entered this repository
on **1 September 2026** and have not changed by a single byte since. The four files as they ship,
with the SHA-256 digests a rebuild has to reproduce:

- `libproot.so` -- `f12d165dfd34062be3da5e838fbeec429f1b7b9ba5b95371fbd253fb4a9cafc2`
- `libproot-loader.so` -- `44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04`
- `libandroid-shmem.so` -- `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731`
- `libtallocxx.so` -- `3c9b207c0a6ea2896b7523e03f55d9ab0d9e88baa115d4c32b84058ff4246fbb`

The unmodified Termux packages they were made from, with the digests recorded when they were
fetched:

- `proot_5.1.107.92_aarch64.deb` -- SHA-256 `1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9`
- `libandroid-shmem_0.7_aarch64.deb` -- SHA-256 `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6`
- `libtalloc_2.4.3_aarch64.deb` -- SHA-256 `ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da`

All three come from `https://packages.termux.dev/apt/termux-main/`.

talloc is linked the ordinary way, as a separate shared library found through `$ORIGIN`. That is the
shared-library mechanism LGPL-3.0 section 4(d)(1) describes, so anyone may replace `libtallocxx.so`
with their own interface-compatible build of talloc and the app will use it.

## How to get the source

This is the section that has to work, so it is written plainly.

**PocketLinux's own source code** is published at
`https://github.com/smmpanelusertestai07-dotcom/CloudSaver`, in the `pocketlinux/` folder: every
Java file, every shell and Python script shipped into the container, the icons, and `build.sh`,
which is the whole build. The repository carries an Apache License 2.0 text at its root. The
`pocketlinux/` folder carries no separate licence file of its own.

**The source of the GPL and LGPL components bundled in the APK** is, in order:

1. The upstream projects linked in the table above, at the exact versions listed there:
   PRoot 5.1.107.92, talloc 2.4.3, libandroid-shmem 0.7.
2. Termux's packaging for those versions, at
   `https://github.com/termux/termux-packages/tree/master/packages/proot` and its neighbours, which
   is what produced the `.deb` files listed above.
3. The three string substitutions described in the previous section, applied to the binaries out of
   those `.deb` files. They are byte-for-byte substitutions of equal-length strings, and the
   resulting digests are printed above, so a rebuild can be checked against them exactly.

**The licence texts themselves.** GPL-2.0 section 1 requires that a copy of the licence go with the
program, and LGPL-3.0 section 4(b) requires both the LGPL and the GPL. They are not shipped as files
inside the APK. Their canonical texts are:

- GPL-2.0 -- `https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt`
- LGPL-3.0 -- `https://www.gnu.org/licenses/lgpl-3.0.txt`
- GPL-3.0 -- `https://www.gnu.org/licenses/gpl-3.0.txt`
- BSD-3-Clause, for libandroid-shmem -- the `LICENSE` file at
  `https://raw.githubusercontent.com/termux/libandroid-shmem/master/LICENSE`
- AFL-2.1, for the D-Bus configuration -- the full Ubuntu copyright notice is shipped in the APK as
  `app/assets/dbus-copyright.txt`

**The route, in licence terms.** GPL-2.0 section 3 says that where object code is offered by giving
access to copy it from a designated place, offering the source from the same place counts as
distributing the source. That is the route this project takes. The designated place is the GitHub
repository named above, which is where the APK is published; the three items listed above are what
make up the complete corresponding source for the bundled GPL and LGPL components. No written
three-year offer is made, because none is needed on this route, and a final release with no
maintainer is the worst possible place to put an open-ended obligation. If any of the addresses
above stops resolving, open an issue on that repository.

## Why almost nothing is bundled

Ubuntu, TigerVNC, Openbox, Google Chrome, Claude Desktop, ChatGPT, Cursor and Antigravity are **not**
inside this APK. The phone fetches each of them from its own publisher, over HTTPS, at set-up or
when a row in the Apps tab is tapped. That is a deliberate choice and not a loophole:

- PocketLinux never conveys Ubuntu, so Canonical's redistribution terms are not engaged and the
  copyleft obligations of the hundreds of GPL packages inside Ubuntu stay with Canonical, who does
  convey them.
- Bundling the root filesystem would reverse all of that, and the APK would be far past any
  reasonable size.

What it does not change is what a user is agreeing to. PocketLinux scripts the install of proprietary
software, so the user may never see, for example, Google Chrome's own terms. Chrome is proprietary
software from Google LLC, installed from Google's own signed repository under Google's terms, and it
is not open source and not redistributed here. The same is true of Claude Desktop, ChatGPT, Cursor
and Antigravity: each is its publisher's software under its publisher's terms and account rules.

## Marks this app does and does not show

PocketLinux shows its own mark (`icon_in_app.png`, which is also the launcher icon, the opening
screen and `pocketlinux-mark.png` in the corner of the desktop's bar) and Tux, the Linux mascot,
credited below.

**Canonical.** PocketLinux ships no Canonical mark. The Ubuntu logo, the "Circle of Friends", is
Canonical's trademark, and Canonical's intellectual property rights policy grants its use only "in
accordance with Canonical's brand guidelines, with Canonical's permission in writing". PocketLinux
has no such permission and ships no Ubuntu-branded image. It uses the word "Ubuntu" only to state
which system it runs, which that policy allows as discussion provided no endorsement is implied, and
the app says in its own credits that it is not affiliated with, endorsed by or sponsored by
Canonical. The policy's redistribution clauses, which require trademarks to be removed from a
modified Ubuntu before it is passed on, are not engaged here: the phone downloads an unmodified
`ubuntu-base` archive straight from `cdimage.ubuntu.com`, and the policy expressly permits modifying
Ubuntu for personal or internal use, which is what happens afterwards on the user's own device.
Canonical's policy was last revised on 15 July 2015 and is still the live text.

**Linux.** Linux is the registered trademark of Linus Torvalds in the U.S. and other countries,
administered by the Linux Foundation. This app runs the Linux kernel and says so, which is factual
use and needs no permission.

The product name is a different matter. An earlier version of this file said the Linux Foundation
"grants a free, perpetual, worldwide sublicense for exactly that"; that overstated the position and
is withdrawn. The sublicence programme is real, but the Foundation's published trademark usage
guidelines say a mark "should not be combined with any other mark, hyphenated, abbreviated or
displayed in
parts", that it "should never be used as a verb or noun" but "only as an adjective followed by the
generic name/noun", and that it "should not be used as part of your product name". Their own example
is that "Super Dooper Linux OS" is acceptable while "Super Dooper Linux" is not. "PocketLinux" is a
fused compound that uses the word as a noun inside a product name, so it does not match those
published rules. The Linux Mark Institute publishes no register of approved or refused marks, so
what it would actually decide is unknown; what can be stated is that the name as written does not
follow the published guidance. Anyone distributing this app publicly under this name should either
apply for a sublicence at `linuxfoundation.org/legal/the-linux-mark`, present the product as
"PocketLinux OS", or use the word Linux only factually in the tagline.

PocketLinux is not affiliated with, endorsed by or sponsored by Linus Torvalds, the Linux Foundation
or Canonical Ltd.

## Application logos

PocketLinux's app list shows each application's own logo so a row is recognisable at a glance. The
images are taken from the vendor's own distribution, either the package PocketLinux installs or the
vendor's own site, and are used unmodified apart from being trimmed and scaled to 128x128. They
identify the applications they name; they are not PocketLinux's own marks, and each remains the
property and trademark of its owner.

| File | Identifies | Taken from |
| --- | --- | --- |
| `logo_chatgpt.png` | ChatGPT (OpenAI) | Largest PNG frame of the icon shipped inside OpenAI's official ChatGPT desktop package |
| `logo_claude.png` | Claude (Anthropic) | Anthropic's published Claude app icon |
| `logo_antigravity.png` | Antigravity (Google) | `antigravity.google` |
| `logo_vscode.png` | Visual Studio Code (Microsoft) | `code.visualstudio.com` |
| `logo_firefox.png` | Firefox (Mozilla) | `firefox.com` |
| `logo_web.png` | GNOME Web / Epiphany | `epiphany-browser-data`, CC BY-SA 4.0, rendered from the shipped SVG (no longer shown) |
| `logo_chrome.png` | Google Chrome (Google) | Google's published Chrome icon, via Wikimedia Commons |
| `logo_brave.png` | Brave (Brave Software) | Brave's published product logo (no longer shown) |

`app/assets/antigravity.png` is the same Antigravity mark, shipped into the container because
Antigravity is distributed as a tarball that registers no icon of its own.

## Tux, the Linux mascot

`app/res/drawable-nodpi/tux.png` (the home screen), `app/res/drawable-nodpi/splash_combined.png`
(the launch screen, beside the app mark), `app/assets/wallpaper.jpg` (the desktop wallpaper) and
`app/assets/pocketlinux-linux.png` (the desktop panel's Apps button) are Tux, the Linux mascot,
created by Larry Ewing with The GIMP, in the vector rendering by Larry Ewing, Simon Budig and
Garrett LeSage published on Wikimedia Commons (`Tux.svg`). Its licence: permission to use and/or
modify the image is granted provided Larry Ewing (lewing@isc.tamu.edu) and The GIMP are
acknowledged, which the app's Settings tab does. Tux is not a PocketLinux mark.

## Wallpaper

`app/assets/wallpaper.jpg` is PocketLinux's own composition: a 1600x1600 square, a dark-blue radial
glow on `#0b1320` with Tux and the words "PocketLinux" and "Ubuntu 24.04 LTS · Linux". Built by
`tools/make_wallpaper.py` in this repository, from Tux (Larry Ewing and The GIMP, acknowledged
above) and the DejaVu fonts. No Canonical artwork is used.

## PocketLinux's own desktop tools

`app/assets/pocketlinux-mcp.py` is PocketLinux's own work, written for this app. It is a Model
Context Protocol server with no third-party dependencies: MCP is JSON-RPC 2.0 over standard input
and output, implemented here in the Python 3 standard library so that nothing has to be downloaded
for it. It drives programs that are already part of the computer -- `xdotool` (pointer and
keyboard), `wmctrl` (the window list), `scrot` (the picture) and, when installed, `tesseract-ocr`
(the words) -- each under its own licence, from Ubuntu's own archive. The Model Context Protocol
specification is published by Anthropic under the MIT licence; this is an independent implementation
of it and carries no Anthropic code.

The same is true of `pocketlinux-agent.sh`, `pocketlinux-storage.sh`, `pocketlinux-shot.sh`,
`pocketlinux-windows.sh`, `pocketlinux-menu.sh`, `pocketlinux-desktop.sh`, `pocketlinux-open.sh`,
`pocketlinux-install.sh`, `pocketlinux-software.sh` and `pocketlinux-adb.sh`: all PocketLinux's own.

## Icons

The line icons under `app/res/drawable/` (`ic_*.xml`) are Material Design icons by Google,
Apache License 2.0.

## What set-up installs inside the Linux computer

Nothing below ships inside this APK. Each package is downloaded at set-up from Ubuntu's own archive,
or for Google Chrome from Google's own repository, and stays under its own licence, with its full
licence text kept on the computer itself at `/usr/share/doc/<package>/copyright`.

The desktop: `tigervnc-standalone-server`, `openbox`, `tint2`, `pcmanfm`, `libfm-modules`,
`lxterminal`, `dunst`, `libnotify-bin`, `pulseaudio`, `pulseaudio-utils`, `dbus-x11`,
`x11-xserver-utils`, `x11-utils`, `xdotool`, `wmctrl`, `zenity`, `xdg-utils`,
`desktop-file-utils`, `librsvg2-common`, `adwaita-icon-theme`, `gnome-themes-extra-data`,
`dmz-cursor-theme`, `fonts-dejavu-core`, `fonts-noto-color-emoji`, `fonts-noto-core`,
`bash-completion`, `lsb-release`, `tzdata`.

The everyday programs: `mousepad`, `xarchiver`, `7zip`, `gpicview`, `galculator`, `lxtask`,
`lxappearance`, `pavucontrol`, `scrot`, `xclip`, `xsel`, `ripgrep`, `man-db`, `manpages`, `tmux`,
`inotify-tools`, `tesseract-ocr` and `tesseract-ocr-eng` (Apache-2.0; used only by PocketLinux's own
appshot, to read the words on a window on the phone itself), and `gnome-keyring`, `libsecret-1-0`
and `libsecret-tools` (LGPL-2.1+), which give Electron's `safeStorage` a real keyring so the AI
apps' sign-in tokens are encrypted rather than written in plain text.

The developer tools: `build-essential`, `pkg-config`, `python3`, `python3-pip`, `python3-venv`,
`python3-dev`, `nodejs`, `npm`, `git`, `git-lfs`, `openssh-client`, `jq`, `htop`, `tree`, `vim`,
`nano`, `rsync`, `sqlite3`, `sudo`, `curl`, `wget`, `gnupg`, `ca-certificates`, `less`, `file`,
`unzip`, `zip`.

### Mobile app development

"Mobile app development" installs, from Ubuntu's own archive: `openjdk-21-jdk-headless` (GPL-2.0
with Classpath Exception), `gradle` (Apache-2.0), and from Android's own open-source build tools
`adb`, `fastboot`, `aapt` and **`aapt2`** (all Apache-2.0), plus `scrcpy` (Apache-2.0) and
`android-sdk-libsparse-utils` (Apache-2.0). None of it ships inside this APK, and removing the row
removes all of it.

`aapt2` was omitted from this list until now, and it deserves its own line, because it is the piece
that decides whether an Android build finishes here at all. Google publishes `aapt2` for Intel Linux
only, and Android's Gradle plugin fetches that Intel binary from Google's Maven repository, so an
otherwise healthy build fails on a processor the tool was never shipped for. The row therefore asks
apt for `aapt2` as well, and when it is present it writes `android.aapt2FromMavenOverride` into
`~/.gradle/gradle.properties` so Gradle uses the ARM64 binary instead of Google's Intel one. When it
is not present the row prints `aapt2: not installed` rather than leaving a build to fail later. No
Android SDK is downloaded from Google at all: a half-installed SDK is worse than none. Android, adb
and the Android robot are trademarks of Google LLC; PocketLinux shows no Google mark and is not
affiliated with Google.

### Design and game tools

"Design and game tools" installs `blender`, `godot3`, `gimp` and `inkscape` from Ubuntu's own ARM64
archive, each under its own licence. There is no graphics chip available inside the container, so
these draw on the processor. The row says so, and so does the README.

## Privacy, in one line, with the detail elsewhere

PocketLinux has no account, no server, no analytics and no telemetry. It contacts
`cdimage.ubuntu.com` and a mirror, Ubuntu's ports archive, and the publishers' repositories at
`dl.google.com`, `downloads.claude.ai`, `persistent.oaistatic.com`, `api2.cursor.sh` and
`us-central1-apt.pkg.dev`. Each of those sees this phone's IP address and what was requested.
Software the user installs inside Linux runs with this app's network and file access, and
PocketLinux neither inspects nor restricts what it sends. The app's own Privacy screen, reachable
from Settings, is the full statement.

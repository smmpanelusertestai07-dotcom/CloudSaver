# Open-source notices — PocketLinux

PocketLinux bundles runtime components extracted from official Termux ARM64 packages. PRoot's compiled private-prefix string was changed from the same-length `com.termux` to `com.ndockx`. Its runtime search path was changed to `$ORIGIN`, and the equal-length `libtalloc.so.2` dependency name was changed to `libtallocxx.so`, so all executable components can remain in Android's signed native-library area. These byte-level substitutions are reproducible from the listed Termux package and fingerprints.

| Component | Bundled version | License / source |
|---|---:|---|
| PRoot (Termux build) | 5.1.107.92 | GPL-2.0-or-later; https://github.com/termux/proot and https://github.com/termux/termux-packages/tree/master/packages/proot |
| libandroid-shmem | 0.7 | Apache-2.0; https://github.com/termux/libandroid-shmem |
| talloc runtime | 2.4.3 | LGPL-3.0-or-later; https://talloc.samba.org/ |

Binary package sources and recorded fingerprints:

- `proot_5.1.107.92_aarch64.deb` — SHA-256 `1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9`
- `libandroid-shmem_0.7_aarch64.deb` — SHA-256 `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6`
- `libtalloc_2.4.3_aarch64.deb` — SHA-256 `ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da`

Packages were obtained from `https://packages.termux.dev/apt/termux-main/`. Corresponding source and license texts are available from the linked upstream projects and Termux packaging repository. These components remain under their respective licenses. PocketLinux's Java application sources are provided alongside the APK for inspection and reproducible rebuilding.

## Application logos

PocketLinux's app list shows each application's own logo so a row is recognisable at a glance. The
images are taken from the vendor's own distribution — the package PocketLinux installs, or the
vendor's own site — and are used unmodified apart from being trimmed and scaled to 128×128. They
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

`app/res/drawable-nodpi/tux.png` (the home screen), `app/res/drawable-nodpi/splash_combined.png` (the launch screen, beside the app mark), `app/assets/wallpaper.jpg` (the desktop wallpaper) and
`app/assets/pocketdesk-linux.png` (the desktop panel's Apps button) are Tux, the Linux mascot,
created by Larry Ewing with The GIMP, in the vector rendering by Larry Ewing, Simon Budig and
Garrett LeSage published on Wikimedia Commons (`Tux.svg`). Its licence: permission to use and/or
modify the image is granted provided Larry Ewing (lewing@isc.tamu.edu) and The GIMP are
acknowledged, which the app's Settings tab does. Tux is not a PocketLinux mark.

## Wallpaper

`app/assets/wallpaper.jpg` is PocketLinux's own composition: a 1600x1600 square, a dark-blue
radial glow on `#0b1320` with Tux and the words "PocketLinux" and "Ubuntu 24.04 LTS · Linux".
Built by `tools/make_wallpaper.py` in this repository, from Tux (Larry Ewing and The GIMP,
acknowledged above) and the DejaVu fonts. No Canonical artwork is used.

## PocketLinux's own desktop tools

`app/assets/pocketdesk-mcp.py` is PocketLinux's own work, written for this app and covered by this
project's licence. It is a Model Context Protocol server with no third-party dependencies: MCP is
JSON-RPC 2.0 over standard input and output, implemented here in the Python 3 standard library so
that nothing has to be downloaded for it. It drives programs that are already part of the
computer — `xdotool` (pointer and keyboard), `wmctrl` (the window list), `scrot` (the picture)
and, when installed, `tesseract-ocr` (the words) — each under its own licence, from Ubuntu's own
archive. The Model Context Protocol specification is published by Anthropic under the MIT
licence; this is an independent implementation of it and carries no Anthropic code.

The same is true of `pocketdesk-agent.sh`, `pocketdesk-storage.sh`, `pocketdesk-shot.sh`,
`pocketdesk-windows.sh`, `pocketdesk-menu.sh`, `pocketdesk-desktop.sh`, `pocketdesk-open.sh` and
`pocketdesk-install.sh`: all PocketLinux's own.

## Mobile app development

"Mobile app development" installs, from Ubuntu's own archive: `openjdk-21-jdk-headless`
(GPL-2.0 with Classpath Exception), `gradle` (Apache-2.0), `adb` and `fastboot` from
android-platform-tools (Apache-2.0), `aapt` (Apache-2.0), `scrcpy` (Apache-2.0) and
`android-sdk-libsparse-utils` (Apache-2.0). None of it ships inside this APK, and removing the
row removes all of it.

No Android SDK is downloaded from Google, on purpose: Google publishes no ARM64 Linux build of
`aapt2`, and a half-installed SDK is worse than none. Android, adb and the Android robot are
trademarks of Google LLC; PocketLinux shows no Google mark and is not affiliated with Google.

## Marks this app does and does not show

PocketLinux shows its own mark (`icon_in_app.png`, which is also the launcher icon, the opening
screen and `pocketdesk-mark.png` in the corner of the desktop's bar) and Tux, the Linux mascot,
credited above. It shows no Canonical mark: the Ubuntu logo, the "Circle of Friends", is
Canonical's trademark, and Canonical's intellectual property rights policy grants its use only
"in accordance with Canonical's brand guidelines, with Canonical's permission in writing".
PocketLinux has no such permission and ships no Ubuntu-branded image; it uses the word "Ubuntu"
only to state which system it runs, which that policy allows as discussion provided no
endorsement is implied — and the app says in its own credits that it is not affiliated with,
endorsed by or sponsored by Canonical. Linux® is the registered trademark of Linus Torvalds in
the U.S. and other countries, administered by the Linux Foundation. This app runs the Linux
kernel and says so, which is factual use. Its name also contains the word, which is use *as*
part of a mark: the Linux Foundation grants a free, perpetual, worldwide sublicense for exactly
that, and any public distribution of this app under this name should hold one. PocketLinux is
not affiliated with, endorsed by or sponsored by Linus Torvalds or the Linux Foundation.

## What set-up installs inside the Linux computer

Nothing below ships inside this APK. Each package is downloaded at set-up from Ubuntu's own
archive (or, for Google Chrome, from Google's own repository) and stays under its own licence,
with its full licence text kept on the computer itself at `/usr/share/doc/<package>/copyright`.

The desktop: `tigervnc-standalone-server`, `openbox`, `tint2`, `pcmanfm`, `libfm-modules`,
`lxterminal`, `dunst`, `libnotify-bin`, `pulseaudio`, `pulseaudio-utils`, `dbus-x11`,
`x11-xserver-utils`, `x11-utils`, `xdotool`, `wmctrl`, `zenity`, `xdg-utils`,
`desktop-file-utils`, `librsvg2-common`, `adwaita-icon-theme`, `gnome-themes-extra-data`,
`dmz-cursor-theme`, `fonts-dejavu-core`, `fonts-noto-color-emoji`,
`fonts-noto-core`, `bash-completion`, `lsb-release`, `tzdata`.

The everyday programs: `mousepad`, `xarchiver`, `7zip`, `gpicview`, `galculator`, `lxtask`,
`lxappearance`, `pavucontrol`, `scrot`, `xclip`, `xsel`, `ripgrep`, `man-db`, `manpages`,
`tmux`, `tesseract-ocr` and `tesseract-ocr-eng` (Apache-2.0; used only by PocketLinux's own
appshot, to read the words on a window on the phone itself), and `gnome-keyring`,
`libsecret-1-0` and `libsecret-tools` (LGPL-2.1+), which give Electron's `safeStorage` a real
keyring so the AI apps' sign-in tokens are encrypted rather than written in plain text.

The developer tools: `build-essential`, `pkg-config`, `python3`, `python3-pip`, `python3-venv`,
`python3-dev`, `nodejs`, `npm`, `git`, `git-lfs`, `openssh-client`, `jq`, `htop`, `tree`, `vim`,
`nano`, `rsync`, `sqlite3`, `sudo`, `curl`, `wget`, `gnupg`, `ca-certificates`, `less`, `file`,
`unzip`, `zip`.

Google Chrome is proprietary software from Google LLC, installed from Google's own signed
repository under its own terms of service; it is not open source and is not redistributed here.

## Icons

The line icons under `app/res/drawable/` (`ic_*.xml`) are Material Design icons by Google,
Apache License 2.0.

## D-Bus system configuration

`app/assets/dbus-system.conf` is the unmodified, architecture-independent system bus
configuration from Ubuntu 24.04 package `dbus-system-bus-common` version
`1.14.10-4ubuntu4.1`. It restores only a missing distro file on existing installations.
SHA-256: `c0a02340950ce376ccee26d58df2c77466c534dcd368b3486b4b6a60d3741f6b`. Ubuntu's complete copyright and license
notice is included in `app/assets/dbus-copyright.txt`. D-Bus is dual licensed under
the GPL-2.0-or-later or AFL-2.1; see that notice for file-level terms.
Source: https://packages.ubuntu.com/noble-updates/all/dbus-system-bus-common/filelist

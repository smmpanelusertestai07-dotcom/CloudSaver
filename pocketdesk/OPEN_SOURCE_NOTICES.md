# Open-source notices — PocketDesk

PocketDesk bundles runtime components extracted from official Termux ARM64 packages. PRoot's compiled private-prefix string was changed from the same-length `com.termux` to `com.ndockx`. Its runtime search path was changed to `$ORIGIN`, and the equal-length `libtalloc.so.2` dependency name was changed to `libtallocxx.so`, so all executable components can remain in Android's signed native-library area. These byte-level substitutions are reproducible from the listed Termux package and fingerprints.

| Component | Bundled version | License / source |
|---|---:|---|
| PRoot (Termux build) | 5.1.107.92 | GPL-2.0-or-later; https://github.com/termux/proot and https://github.com/termux/termux-packages/tree/master/packages/proot |
| libandroid-shmem | 0.7 | Apache-2.0; https://github.com/termux/libandroid-shmem |
| talloc runtime | 2.4.3 | LGPL-3.0-or-later; https://talloc.samba.org/ |

Binary package sources and recorded fingerprints:

- `proot_5.1.107.92_aarch64.deb` — SHA-256 `1f1c983509701f6826f568482c70673ee453a9ba38c9f5fa445a472d6b7524e9`
- `libandroid-shmem_0.7_aarch64.deb` — SHA-256 `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6`
- `libtalloc_2.4.3_aarch64.deb` — SHA-256 `ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da`

Packages were obtained from `https://packages.termux.dev/apt/termux-main/`. Corresponding source and license texts are available from the linked upstream projects and Termux packaging repository. These components remain under their respective licenses. PocketDesk's Java application sources are provided alongside the APK for inspection and reproducible rebuilding.

## Application logos

PocketDesk's app list shows each application's own logo so a row is recognisable at a glance. The
images are taken from the vendor's own distribution — the package PocketDesk installs, or the
vendor's own site — and are used unmodified apart from being trimmed and scaled to 128×128. They
identify the applications they name; they are not PocketDesk's own marks, and each remains the
property and trademark of its owner.

| File | Identifies | Taken from |
| --- | --- | --- |
| `logo_chatgpt.png` | ChatGPT (OpenAI) | `usr/share/pixmaps/chatgpt.png` in `chatgpt_arm64.deb` |
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
acknowledged, which the app's Settings tab does. Tux is not a PocketDesk mark.

## Wallpaper

`app/assets/wallpaper.jpg` is PocketDesk's own composition: a 1600x1600 square, a dark-blue
radial glow on `#0b1320` with Tux and the words "PocketDesk" and "Ubuntu 24.04 LTS · Linux".
Built by `tools/make_wallpaper.py` in this repository, from Tux (Larry Ewing and The GIMP,
acknowledged above) and the DejaVu fonts. No Canonical artwork is used.

## PocketDesk's own desktop tools

`app/assets/pocketdesk-mcp.py` is PocketDesk's own work, written for this app and covered by this
project's licence. It is a Model Context Protocol server with no third-party dependencies: MCP is
JSON-RPC 2.0 over standard input and output, implemented here in the Python 3 standard library so
that nothing has to be downloaded for it. It drives programs that are already part of the
computer — `xdotool` (pointer and keyboard), `wmctrl` (the window list), `scrot` (the picture)
and, when installed, `tesseract-ocr` (the words) — each under its own licence, from Ubuntu's own
archive. The Model Context Protocol specification is published by Anthropic under the MIT
licence; this is an independent implementation of it and carries no Anthropic code.

The same is true of `pocketdesk-agent.sh`, `pocketdesk-storage.sh`, `pocketdesk-shot.sh`,
`pocketdesk-windows.sh`, `pocketdesk-menu.sh`, `pocketdesk-desktop.sh`, `pocketdesk-open.sh` and
`pocketdesk-install.sh`: all PocketDesk's own.

## Marks this app does and does not show

PocketDesk shows its own mark (`icon_in_app.png`, which is also the launcher icon, the opening
screen and `pocketdesk-mark.png` in the corner of the desktop's bar) and Tux, the Linux mascot,
credited above. It shows no Canonical mark: the Ubuntu logo, the "Circle of Friends", is
Canonical's trademark, and Canonical's intellectual property rights policy grants its use only
"in accordance with Canonical's brand guidelines, with Canonical's permission in writing".
PocketDesk has no such permission and ships no Ubuntu-branded image; it uses the word "Ubuntu"
only to state which system it runs, which that policy allows as discussion provided no
endorsement is implied — and the app says in its own credits that it is not affiliated with,
endorsed by or sponsored by Canonical. "Linux" is a registered trademark of Linus Torvalds,
used here as a factual statement about what the app runs; PocketDesk's name contains no
trademark of another party.

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
`tmux`, `tesseract-ocr` and `tesseract-ocr-eng` (Apache-2.0; used only by PocketDesk's own
appshot, to read the words on a window on the phone itself).

The developer tools: `build-essential`, `pkg-config`, `python3`, `python3-pip`, `python3-venv`,
`python3-dev`, `nodejs`, `npm`, `git`, `git-lfs`, `openssh-client`, `jq`, `htop`, `tree`, `vim`,
`nano`, `rsync`, `sqlite3`, `sudo`, `curl`, `wget`, `gnupg`, `ca-certificates`, `less`, `file`,
`unzip`, `zip`.

Google Chrome is proprietary software from Google LLC, installed from Google's own signed
repository under its own terms of service; it is not open source and is not redistributed here.

## Icons

The line icons under `app/res/drawable/` (`ic_*.xml`) are Material Design icons by Google,
Apache License 2.0.

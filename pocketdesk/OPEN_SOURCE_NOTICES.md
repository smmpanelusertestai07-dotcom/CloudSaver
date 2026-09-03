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

`app/assets/wallpaper.jpg` is PocketDesk's own composition: a dark-blue gradient with Tux, the
Linux mascot, centred (Tux credited above). The desktop runs Ubuntu 24.04 LTS; "Ubuntu" is a
trademark of Canonical Ltd, and PocketDesk is not affiliated with or endorsed by Canonical.

## Icons

The line icons under `app/res/drawable/` (`ic_*.xml`) are Material Design icons by Google,
Apache License 2.0.

# Open-source notices — PocketIDE

PocketIDE's own code is licensed under the Apache License, Version 2.0.

This app carries, downloads and runs software written by other people. Each keeps its own
licence, and those licences are reproduced or pointed to below. Where a licence requires that
its notice reach whoever receives the program, that notice ships inside the APK — the APK being
the only thing anyone receives.

## Application mark

`app/res/mipmap-*/ic_launcher*.png`, `app/res/drawable-*/ic_stat_pocketide.png` and
`app/res/drawable-*/ic_splash.png` are PocketIDE's own mark, drawn by `plan/make_icon.py` in
this repository, under the Apache License 2.0 with the rest of this app's code.

## Chromium, and Playwright

Neither ships in the APK. Both are downloaded into the workspace only if the owner asks for them
in Settings, and each keeps its own licence.

Chromium is **BSD-3-Clause** with a large set of third-party components under their own terms;
its source and the full notice are at https://chromium.googlesource.com/chromium/src. The build
installed here is the `chromium` package from the `ppa:xtradeb/apps` archive, which is the
aarch64 Ubuntu Noble build. Ubuntu's own Chromium is published only as a snap, and a snap cannot
run inside this kind of container at all, which is why a third-party archive is used and why the
app says so on the screen that installs it.

Playwright is **Apache-2.0**, from https://github.com/microsoft/playwright, and brings its own
arm64 browser build and its own `ffmpeg` (**LGPL-2.1-or-later**, https://ffmpeg.org) for
recording.

## PRoot

`app/lib/arm64-v8a/libproot.so`, `libproot-loader.so`, `libandroid-shmem.so` and
`libtallocxx.so` are builds of PRoot and its supporting libraries, licensed under the
**GNU General Public License, version 2**. PRoot's source is at https://github.com/proot-me/proot
and the Termux packaging used for these ARM64 builds is at
https://github.com/termux/proot. A copy of the GPL-2.0 text accompanies that source.

PRoot is what lets a complete Ubuntu system run on the phone's own kernel without root and
without a virtual machine.

## Ubuntu

Set-up downloads `ubuntu-base-24.04.5-base-arm64.tar.gz` from Canonical's own mirror at
https://cdimage.ubuntu.com and verifies it against the SHA-256 published with it. Ubuntu is a
registered trademark of Canonical Ltd. This app is not produced by, endorsed by or affiliated
with Canonical. Every package inside that image carries its own licence, readable inside the
workspace at `/usr/share/doc/<package>/copyright`.

## code-server

Set-up downloads `code-server-4.137.0-linux-arm64.tar.gz` from Coder's own releases at
https://github.com/coder/code-server and verifies it against a SHA-256 pinned in
`app/assets/pocketide-editor.sh`. code-server is **MIT licensed**, © 2019 Coder Technologies Inc.
Its `LICENSE` and `ThirdPartyNotices.txt` are included in that archive and are on the phone at
`/opt/code-server/` after installation.

## Code - OSS (Visual Studio Code)

code-server packages Code - OSS, the open-source source of Visual Studio Code, **MIT licensed**,
© 2015 – present Microsoft Corporation. Its source is at https://github.com/microsoft/vscode.

This is **not** Microsoft's branded Visual Studio Code build, and no Microsoft licence, account
or activation is used or required. Microsoft's own proprietary components — their marketplace,
the C# and Windows C++ debuggers, Remote Development and Live Share — are not included and do
not work in a non-Microsoft build.

## Open VSX Registry

Extensions are installed from https://open-vsx.org, the extension registry operated by the
**Eclipse Foundation**. The registry software is open source at
https://github.com/eclipse/openvsx. Each extension carries its own licence, shown on its
registry page; the three recommended agent extensions are proprietary software published by
Google, Anthropic and OpenAI respectively.

## Tux, the Linux mascot

`app/res/drawable-nodpi/tux.png` is Tux, created by Larry Ewing with The GIMP, in the vector
rendering by Larry Ewing, Simon Budig and Garrett LeSage published on Wikimedia Commons
(`Tux.svg`). Permission to use and/or modify the image is granted provided Larry Ewing
(lewing@isc.tamu.edu) and The GIMP are acknowledged in the document or on the web page; this
notice is that acknowledgement.

## Marks this app does and does not show

The app shows no company's product mark: not Microsoft's Visual Studio Code icon, which belongs
to a build this is not, and not a publisher's, which the app has no need to draw when the
editor's own Extensions view and each extension's own panel already carry it. Names — Visual
Studio Code, Code - OSS, code-server, Ubuntu, Claude Code, Codex, Antigravity — are used only to
identify whose software is being installed or run, which is nominative use. No endorsement,
sponsorship or affiliation is claimed or implied, and this app is not produced by any of them.
The app puts no company's mark on its own icon, its own splash screen or its own name either,
because those would suggest an association that does not exist.

## What set-up installs inside the workspace

The bootstrap installs, from Ubuntu's own repositories: `ca-certificates`, `curl`, `wget`,
`gnupg`, `git`, `openssh-client`, `xz-utils`, `unzip`, `tar`, `less`, `ripgrep`, `jq`,
`procps`, `python3`, `python3-pip`, `python3-venv`, `build-essential` and `pkg-config`. Each
carries its own licence, readable on the phone under `/usr/share/doc`.

## Icons

The interface icons under `app/res/drawable/ic_*.xml` are redrawn from Google's Material Symbols
set, licensed under the **Apache License 2.0**.

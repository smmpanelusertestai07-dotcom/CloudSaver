# PocketDesk 1.1.0

The Ubuntu install now runs to the end, the desktop fills the screen, and Linux apps beyond
ChatGPT can be installed from the app.

## The install completes

- **`Unsafe path in archive: etc/alternatives/pager`** — the path check resolved the whole path,
  including the final component. Ubuntu fills `/etc/alternatives` with links to absolute guest
  paths such as `/usr/bin/pager`, so as soon as one of those links existed, the archive's own
  layout was reported as an escape and the install stopped. Only the directory part is resolved
  now; it is still range-checked, and `cleanName` still rejects any `..`, so a real escape is
  refused. `TarGzExtractorTest` reproduces it by extracting the same archive twice.

## The desktop fills the screen

- The framebuffer is built from **your phone's own screen size** instead of a fixed 1280×720. In
  landscape that is a 1:1 match — sharp, no letterbox, no black bars.
- Type and controls are made large by raising the desktop's **DPI**, not by scaling a smaller
  picture up, so everything is bigger *and* stays crisp. New **Desktop text size** setting:
  Normal, Large (default) or Extra large.
- New view controls in the desktop toolbar: **pinch to zoom**, `−` / `+`, a percentage that
  resets on tap, a **Fill / Fit** toggle, and **Full screen** to hide both bars. Two fingers pan
  once the picture is larger than the screen, and still scroll when it is not.
- The toolbar scrolls sideways, so no control is ever cut off on a narrow phone.
- Rotating re-centres instead of leaving the desktop pinned to a corner.
- LXTerminal, GTK and the panel are configured with readable font sizes and a dark palette.
- `groups: cannot find name for group ID 3003` no longer greets you in every terminal: Android's
  supplementary group ids are given names inside the container.

## Linux apps, always the newest build

A new **Linux apps** card installs desktop apps into the container. Each row installs *and*
updates — every entry resolves the latest build rather than a pinned version.

| App | Source | Note |
| --- | --- | --- |
| ChatGPT | OpenAI's `latest` ARM64 `.deb` | includes Codex |
| Claude Desktop | Anthropic's apt repository, signature pinned to the published fingerprint | Linux beta; Cowork needs hardware virtualisation a phone cannot give |
| Antigravity | the ARM64 tarball named on Google's download page | resolved at install time, never pinned |
| VS Code | Microsoft's `latest` ARM64 `.deb` | |
| Firefox | Mozilla's own apt repository | Ubuntu's `firefox` package is a snap shim that cannot work in a container |
| Developer tools | apt | Node.js, Python, pip, compiler |

Each app states its download size, the free space it really needs, and any real limitation before
you agree to install it. `LinuxAppsTest` checks every command is valid shell and that none pins a
version.

## Also

- The in-app mark is larger, matching the launcher icon more closely.
- Free-space checks are per app instead of a blanket 2.5 GB.

## Verified in this build

- `tests/run-tests.sh` — `VncClientProtocolTest`, `TarGzExtractorTest`, `TreesTest` and the new
  `LinuxAppsTest` all pass
- The generated desktop script is syntax-checked with `bash -n`
- Mozilla's apt repository confirmed to publish an arm64 Firefox; Anthropic's and Google's ARM64
  Linux downloads confirmed from their own documentation
- javac against API 35 (min 29), D8, zipalign, APK Signature Scheme v3, `aapt2 dump badging`

## Still needs a device

A full Ubuntu install running to completion, and each AI app actually launching, still need
testing on the phone.

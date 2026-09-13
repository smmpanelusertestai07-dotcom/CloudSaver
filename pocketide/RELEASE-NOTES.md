# Release notes

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

- 36 gates, every one of them bidirectional: each was verified to fail when the thing it guards
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

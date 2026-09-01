# PocketDesk 1.2.0

Automatic rotation genuinely works, the picture stops corrupting itself, and installed apps show
up in three places on the desktop.

## Automatic rotation, done properly

Previous versions built the Linux desktop at one fixed landscape size. Portrait could then only
show that picture as a thin strip or a heavy crop — so 1.1.2 forced landscape, which is not what
"Automatic" means.

The desktop now **resizes itself to match the phone**, using the RFB `SetDesktopSize` extension:

- The viewer asks for `ExtendedDesktopSize` at connect and asks the desktop to become exactly the
  size of the view, again after every rotation (debounced, since a rotation delivers several).
- Hold the phone in portrait and you get a portrait Linux desktop filling the screen; rotate and
  it becomes a landscape one. Both are an exact 1:1 fill — nothing letterboxed, nothing cropped,
  nothing blurred.
- **Automatic is automatic again.** Portrait and Landscape still force their orientation.
- If a desktop ever refuses to resize, the viewer falls back to fit/fill with zoom as before.

## The picture stops corrupting itself

1.1.1 replaced a per-frame allocation with one reused buffer — which fixed the memory crash but
introduced a race: the buffer was handed to the main thread and then refilled before that thread
had read it, so screen updates could overwrite each other. That is why the terminal came up blank
or half-drawn.

The framebuffer is now written straight from the network thread under a lock that the drawing pass
also takes. No shared buffer in flight, no one-second wait per update, and bitmap swaps take the
same lock so a reader can never write into a recycled bitmap.

## Finding your apps — three ways

- **Taskbar launcher icons** along the bottom panel, one per installed app.
- **Right-click (two-finger tap) the desktop** for a menu of Terminal, Files and every app.
- **Desktop icons**, now that the desktop folder is declared where pcmanfm actually looks.

All three are generated from what is really installed, and rebuilt the moment an install finishes —
the taskbar restarts itself to pick them up. App entries use an icon name the theme actually ships,
because a missing icon made the taskbar drop the launcher silently.

**Existing installs heal themselves**: every desktop start now rewrites the desktop scripts and the
launcher for each installed app. If ChatGPT was installed under an older version, just open the
desktop once and it appears — no reinstall.

## Verified in this build

- All five checks pass. `VncClientProtocolTest` caught a real regression while writing this
  release — the fake server read a fixed-length `SetEncodings` and desynchronised when a fourth
  encoding was added — and now reads the declared count, so it cannot break that way again. It
  also asserts `ExtendedDesktopSize` is offered, which is what makes rotation work.
- javac against API 35 (min 29), D8, zipalign, APK Signature Scheme v3, `aapt2 dump badging`
- Both desktop shell scripts lint clean with `bash -n`

## If something still goes wrong

The error report is still there under Permissions, and still appears by itself once after any
problem. Tap **Share** and send the text — it names the exact line.

# PocketDesk 1.1.2

The desktop screen keeps the phone's status bar, opens in landscape at a true 1:1 fill, has a real
right-click menu with your installed apps, and can no longer be killed by a stray error.

## The app can no longer die on you

Every earlier fix removed one *cause* of a crash. This one removes the *consequence*: PocketDesk
now re-enters Android's main message loop after an unhandled UI error instead of letting the
process end. A stray exception shows a short notice, records the stack, and the app carries on —
no more silently bouncing back from the desktop, no more "PocketDesk keeps stopping".

The recorded report is now shown **automatically, once**, the next time you open the app, with
**Share** on it. If anything still misbehaves, that text names the exact line.

## The desktop looks right

- **Your phone's clock, battery and signal stay visible.** The desktop screen no longer forces
  full-screen, and the toolbar and key row are padded clear of the status and gesture bars.
- **It opens in landscape.** The Linux screen is built at your phone's landscape size, so
  landscape is an exact 1:1 fill — sharp, edge to edge, nothing letterboxed. Portrait could only
  ever show that picture as a thin strip or a heavy crop, which is what you were seeing. A new
  **rotate button** in the toolbar switches whenever you want, and the Screen rotation setting
  still forces portrait if you prefer it.
- **Windows open maximised.** On a phone-sized screen a floating half-size terminal is wasted
  space, so Openbox now maximises by default.
- **The desktop has a background and readable icon labels** instead of flat black, with larger
  window title and menu fonts.

## Finding your apps

- **Right-click (two-finger tap) anywhere on the desktop** opens a menu listing Terminal, Files
  and every app you have installed — ChatGPT, Claude Desktop, Antigravity and the rest appear
  there automatically as soon as they finish installing, without restarting the desktop.
- Each app also gets a desktop icon and a system menu entry, so there are three ways to reach it.
- The menu rebuilds itself from the installed launchers, so it can never fall out of step with
  what is actually on the system.

## Under the hood

The two desktop shell scripts moved out of escaped Java strings into real files under
`app/assets/`, where they can be read and reviewed normally. The test suite now lints them with
`bash -n`, so a syntax error can no longer reach the phone.

## Verified in this build

- All five checks pass: `VncClientProtocolTest`, `TarGzExtractorTest`, `TreesTest`,
  `LinuxAppsTest`, and shell syntax on both desktop scripts
- javac against API 35 (min 29), D8, zipalign, APK Signature Scheme v3, `aapt2 dump badging`
- The asset scripts are confirmed present in the packaged APK

## If something still goes wrong

Open PocketDesk, let the error report appear, tap **Share**, and send the text. It contains the
exact failure — which is the one thing screenshots cannot show.

# PocketDesk 1.2.1

The shared error report named the exact bug, and it turns out to be the one that was there from
the very beginning: **touching the desktop performed a network write on Android's main thread.**

## The real crash, finally

The report showed `android.os.NetworkOnMainThreadException` at `VncClient.sendPointer`, raised
from `onTouchEvent`. Every pointer, key, paste and resize message was written to the VNC socket on
the UI thread — which Android forbids outright. So **every tap on the desktop has thrown since the
first NexaDesk build**; before 1.1.2's guard it killed the process ("keeps stopping", the instant
bounce back home), and after the guard it surfaced as the "hit an error and kept running" notice.

All input messages now ride a **dedicated sender thread** with a bounded queue: taps, key presses,
clipboard pastes and resize requests are enqueued and written off the main thread, in order. A
flooded queue drops a pointer move rather than ever blocking the UI.

`VncClientProtocolTest` now proves it: the fake server asserts that a pointer press sent from the
test's own thread — the stand-in for the UI thread — actually arrives, in either order with the
reader's follow-up update request.

## The black screen after resize

The rotation resize itself was working — the toolbar showed `720×1238` — but the screen stayed
black, because a resized framebuffer is brand new and only a **full** (non-incremental) update
request repaints it; the client only ever asked incrementally after connect. Both resize paths now
request a full repaint, so portrait fits the screen with content actually on it, on any device and
any Android version — the size is taken from the view itself, not a device table.

## Housekeeping

The error-report flow did exactly what it was built for — one shared stack replaced four rounds of
guessing. It stays as is: recorded at process level, surfaced once, shareable.

## Verified in this build

- All five checks pass, including the new queued-pointer protocol coverage
- javac against API 35 (min 29), D8, zipalign, APK Signature Scheme v3, `aapt2 dump badging`

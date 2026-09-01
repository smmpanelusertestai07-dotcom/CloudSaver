# PocketDesk 1.0.0

First release under the PocketDesk name. Rebuilt from NexaDesk Linux 0.3.0.

## New name and mark

- Renamed to **PocketDesk** — short, and it says what the app is: a desktop in your pocket.
- Package renamed to `com.pocketdesk`, so **any earlier NexaDesk or NexaDock build must be
  uninstalled first**. It is a different app to Android, not an update.
- New minimalist icon: a deep navy-to-blue gradient with a white monitor and a `>_` prompt.
  Generated from code and exported to every launcher density, the adaptive foreground, the
  themed-icon monochrome layer, the notification icon and the in-app logo, so the mark on the
  home screen is the same mark inside the app.

## Interface

- New deep-blue palette with matched light and dark surfaces, and an explicit theme switch in
  the header (Match phone / Light / Dark).
- Live status strip at the top of the home screen: **Network, Battery %, Free space and
  Temperature**, refreshed every 5 seconds and coloured when a value needs attention.
- Every dropdown was replaced by a chooser sheet where **each option has its own icon** next to
  its word, plus a check mark on the current choice. No more plain-text lists.
- Every settings row, permission row and toolbar button now pairs an icon with a plain-English
  term, with ripple feedback on touch.
- Storage is now stated in MB and GB everywhere: free space, download progress, and the measured
  size of the installed Linux system.
- Setup progress shows transferred size, live speed and an estimate, for example
  `142 MB of 289 MB · 1.4 MB/s · about 2 min left`, and the package step now shows what apt is
  actually doing instead of a silent bar.
- Re-opening the app mid-setup restores the running job's progress immediately.
- Desktop toolbar buttons carry icons: back, pointer mode, keyboard and paste.

## Downloads

- Downloads resume from the byte they stopped at using HTTP range requests, so a dropped mobile
  connection no longer restarts the archive.
- Six attempts with backoff, failing over to a second Ubuntu mirror; every attempt is still
  verified against the same SHA-256.
- 256 KB buffers, and `apt` is configured with 5 retries and 40 s timeouts inside the container.

## Phone health, relaxed

The 0.3 guards stopped sessions too eagerly. Now:

| Guard | 0.3.0 | 1.0.0 |
| --- | --- | --- |
| Thermal stop | Android `SEVERE` | `CRITICAL` — `SEVERE` only warns |
| Battery temperature stop | 45 °C | 49 °C — 45 °C only warns |
| Low-battery stop | 5% | 3%, and ignored while charging |
| Setup battery floor | 15% | 10%, skipped while charging |
| Desktop battery floor | 5% | 4%, skipped while charging |
| Default auto-stop | 3 hours | 4 hours (1/2/4/6 or Off) |

## Permissions and storage

- New Permissions card: notification access, battery usage, OEM auto-start and App info, each
  showing its current state and opening the right Android page. Realme, OPPO, Xiaomi, vivo,
  Huawei and Samsung auto-start pages are all attempted before falling back to App info.
- New **Remove Linux** action that deletes the container and reports the space freed.
- The card shows how much storage the installed Linux is really using, measured off the main thread.

## Reliability

- A global crash handler records the last fatal error, and the recovery screen shows it instead of
  the app closing.
- `onCreate` stays wrapped in a guard, keeping the 0.2.1 fix for the Realme Android 13 startup
  crash: no call into the OEM `WindowInsetsController` path.
- `largeHeap` is enabled for the desktop framebuffer, and Android 13 predictive back is declared.

## Verified in this build

- Java compilation against Android API 35, minimum API 29
- D8 dexing, zipalign, APK Signature Scheme v3, `aapt2 dump badging`
- RFB 3.8 negotiation and raw framebuffer test
- POSIX/PAX tar.gz regular file, long path, symlink and hard-link test
- Launcher assets present at mdpi through xxxhdpi plus the adaptive and monochrome layers
- Ubuntu mirrors and the ChatGPT ARM64 package URL confirmed reachable and range-capable

## Still needs a physical device

Compilation and static checks cannot replace a Realme C25s install. If the recovery screen appears,
send its diagnostic line — it now names the exact failure.

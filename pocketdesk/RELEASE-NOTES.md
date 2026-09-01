# PocketDesk 1.3.0

The container now boots a desktop that behaves like a computer: wallpaper, arrow cursor, real
icons, a browser, and a clock in your own time.

## It looks and works like a computer

- **Firefox is installed with Linux.** A computer without a browser is not much of a computer, so
  it now comes with one, from Mozilla's own repository (Ubuntu's `firefox` package is a snap shim
  that cannot run in a container).
- **Wallpaper** instead of flat black.
- **A normal arrow cursor.** The old X11 cross is gone: `dmz-cursor-theme` plus an Adwaita cursor
  default, applied to both X and GTK.
- **Real icons.** Every launcher was a generic blue diamond because no icon theme was installed —
  `adwaita-icon-theme` is now part of the setup.
- **The clock reads 06:06 pm**, not 18:06, and the timezone is Asia/Kolkata.
- **A proper taskbar**: app launchers, the window list, a system tray and that clock, at 48px with
  34px icons so it is usable with a finger.
- Home is laid out like one: **Projects** for your work and **Downloads** for what the browser
  saves, both shown in the file manager and the desktop.

## Fixed

- **Tapping a desktop icon opened an "Execute File" prompt** asking whether to run the script.
  That is PCManFM refusing to trust a `.desktop` file; `quick_exec` is now on, so an icon just
  opens its app.
- **Rotation needed the screen reopened.** "Automatic" used `SCREEN_ORIENTATION_USER`, which obeys
  the phone's rotation lock. It now follows the sensor directly, so turning the phone turns the
  desktop straight away and the desktop resizes to match.
- **The minus button did nothing.** Once the desktop matches the screen exactly, fit and fill are
  the same size, and the old floor of 100% left nothing to zoom out to. The floor is now 40%.
- **Install progress showed a wall of numbers** — raw `curl` output. Transfer lines are recognised
  and dropped; the phase, elapsed time and expected duration remain.
- App launchers now search several locations for the real binary instead of one fixed path, and
  say so plainly in a terminal if an app is not installed.

## For a Linux you already installed

New setups get all of the above. An existing container catches up through a new **Desktop
essentials** row at the top of Linux apps — browser, icons, cursor and Indian time in one tap.
Nothing is rebuilt and nothing is lost.

## Where your files are

Now stated in About: Linux lives at `/home/coder`, inside this app's own private storage. **Projects**
holds your work, **Downloads** holds what the browser saves. Removing Linux deletes both, so copy
anything you want to keep out first.

## Also

Every button in the desktop toolbar now carries an icon *and* a word — Home, Fill/Fit, Touchpad,
Keyboard, Rotate, Full screen — and the key row gains Home and End.

## Verified in this build

- All five checks pass
- javac against API 35 (min 29), D8, zipalign, APK Signature Scheme v3, `aapt2 dump badging`
- Wallpaper and both desktop scripts confirmed packaged in the APK

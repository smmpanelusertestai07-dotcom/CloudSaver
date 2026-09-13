# PocketAgent 14.1.0 — one palette, one mark, and something that refuses the drift

Version **14.1.0**, code **440**.

## A new mark, and a colour chosen rather than inherited

The icon was two brackets and a spark in a five-stop cobalt gradient on a grey square. The
gradient was doing the work a shape should do, and the colour was the wrong one twice over.

It was wrong against the agents this app hosts. PocketAgent's mark sits on the same screens as
Claude's terracotta, Codex's and Cursor's monochrome, and Antigravity's blue-violet — and a blue
mark among them read as one of Antigravity's, which is exactly the thing an app that hosts four
agents equally must not do. It was wrong against the app's own interior too: a cobalt icon opened
onto a warm cream-and-ink interface that shared none of it.

The mark is now two brackets around a four-point spark — the software, and the agent inside it —
in warm bone on a deep teal, `#0E6E78` to `#073F4C`. Teal belongs to none of the four. It is also
a long way from the only three colours this app allows to mean anything: green for a line added,
amber for something that needs you, red for something removed or failed.

## The interface stays colourless, and that is the design

A button here is ink on cream, or bone on ink. Nothing decorative is coloured. That restraint is
what lets green, amber and red be trusted — if the brand owned a colour in the interface, a
status would be one more decoration. The test now fails if a brand colour turns up in a theme.

## Everything is drawn from one file

`branding/tokens.json` holds the colours and the mark's geometry. `tools/make_brand.py` draws the
launcher icon, the themed icon, the Android 12+ splash, the notification silhouette, the five
legacy densities in both shapes, the Play listing image, the Linux desktop icon and the desktop
wallpaper — all from that one artboard, so none of them can drift apart by being edited alone.

The Linux desktop inside the container came with a navy palette inherited from the app this one
grew out of. Its whole chrome is the brand's hue now. The terminal's sixteen ANSI colours are
deliberately left alone: every program on that system expects them to be what they are.

## The app icon, inside the app

First run, the home header, App lock and the recovery screen all show the launcher icon itself
rather than a flat silhouette. The first-run screen was navy; it is now the same ramp the icon is
drawn on, in the same direction, so tapping the icon continues it instead of cutting to a colour
the phone has not seen.

## A test that refuses the drift

`tests/brand-assets-test.py` replaces a check that needed an npm package, was never wired into
the suite, and had therefore never run. The new one needs nothing but Python, runs on every push,
and holds: the same colours in `tokens.json`, `Brand.java` and `colors.xml`; the mark inside the
adaptive icon's 66 dp keyline, the splash's 192 dp circle and the notification's 24 dp canvas —
and large enough to read in each; ten launcher bitmaps at their exact densities; a Play image that
is 512 px, 32-bit, sRGB-tagged and under a megabyte; a notification icon that is white alpha only,
because Android recolours it anyway; no survivor of the retired palette across 229 files; no blue
left in the desktop scripts; and the icon actually present on the four screens that should show it.

## The build that never ran

Every PocketAgent run of the workflow had failed, all four of them, on `./build.sh: Permission
denied`. The file was committed without its executable bit while PocketLinux's has it. The bit is
set, and the workflow now calls the script through `bash` so losing it again cannot fail the job.

44 gates, exit 0.

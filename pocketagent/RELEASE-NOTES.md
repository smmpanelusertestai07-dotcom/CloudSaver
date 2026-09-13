# PocketAgent 14.2.0 — sparkle on dark

Version **14.2.0**, code **450**.

## The icon

The tile is near-black now, `#23232C` to `#121217`, and the colour has moved off it and into the
mark: bone brackets around one violet spark, `#8B7BFF`.

Two reasons. The dark ground is the same world as the app's own interior and as the phones these
agents are actually used on, so the icon stops being a bright square that belongs to nothing.
And a single coloured element is easier to recognise at 48 px than a coloured field — the eye
goes to the mark, so that is where the colour should be.

Violet still, and for the same reason as before: rendered beside the four agents this app hosts,
at launcher size, Codex and Cursor are dark tiles with pale marks, Claude is terracotta, and
Antigravity's arch is a multicolour rainbow. A violet spark is the one thing none of them has.
The dark tile puts PocketAgent in the same family as Codex and Cursor without being mistaken for
either, because neither of them has a coloured element at all.

## Everything follows from one file, again

`branding/tokens.json` gained a `spark` colour, and `tools/make_brand.py` learned that the
brackets and the spark are two colours rather than one. It redrew the launcher icon, the themed
icon, the splash, the notification silhouette, ten legacy bitmaps, the Play image, the Linux
desktop icon and the wallpaper. `Brand.java` and `colors.xml` carry the same values, and
`tests/brand-assets-test.py` fails if any of the three ever disagree — which it did, immediately,
until all three were updated.

The notification and themed icons stay a single white silhouette: Android recolours both, and a
second colour there would simply be thrown away.

44 gates, exit 0.

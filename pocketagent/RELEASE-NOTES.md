# PocketAgent 14.1.5 — the colour, settled by looking rather than reasoning

Version **14.1.5**, code **445**.

## Violet, and the two things the test corrected

14.1.0 shipped a deep teal, chosen by reasoning about the four agents this app hosts. This
release renders the mark beside their actual logos, at launcher size, and looks at the row. Two
beliefs behind the teal turned out to be wrong.

Antigravity's logo was assumed to be blue-violet. It is a multicolour rainbow arch — blue,
green, yellow, red — with no violet in it. The main argument against a violet mark did not exist.

And Codex and Cursor are *both* dark tiles. Black, the obvious safe choice for a developer tool
and the colour that reads as "serious", is therefore the single colour that would make
PocketAgent disappear between the two agents its owner is most likely to have installed.

Claude is terracotta. Violet is the one slot on that row nothing else occupies, so the mark is
now **`#7A3CD6` to `#33146F`** — hue 264 degrees, deliberately off the blue-violet axis at 250
where a mark starts reading as somebody's blue.

## The tile is three layers now, not a gradient

A flat ramp reads as a swatch. Every tile — launcher, Play image, in-app icon, adaptive
background — is the ramp, then a light from the top-left at 18%, then a shadow into the
bottom-right at 16%, keeping the 12% rim that gives it an edge on a dark wallpaper. It reads as
an object with light falling on it, and it survives to 48 px where a gradient alone would not.

The mark itself is heavier for the same reason: stroke 34 → 38, and the spark's control offset
15 → 19. At 48 px the old stroke went spindly and the old spark collapsed into a dot.

## Everywhere, from the one file

`branding/tokens.json` changed; `tools/make_brand.py` redrew the launcher icon, the themed icon,
the splash, the notification silhouette, ten legacy bitmaps, the Play image, the Linux desktop
icon and the wallpaper. The Ubuntu desktop's chrome moved with it: 36 colours rotated onto the
brand hue, with the terminal's ANSI palette untouched.

## The gate is a rule now, not a blacklist

The previous test refused a list of retired colours, which would have gone stale the moment the
brand changed — as it just did. It now states the requirement positively: every colour in the
desktop scripts must sit within 26 degrees of the brand hue, or be near-neutral, or be one of the
named status hues. Reds and ambers are named rather than inferred from distance, because a
distance rule would have let a stray teal through on the same argument.

44 gates, exit 0.

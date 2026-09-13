# PocketAgent's design system

Everything visual in this app comes from one file. `tokens.json` holds the colours and the
geometry of the mark; `tools/make_brand.py` draws every asset from it; `tests/brand-assets-test.py`
refuses anything that has drifted away from it. There is no second place to remember, and no way
to change a colour in one surface and forget another -- the test fails first.

```sh
pip install cairosvg pillow
python3 tools/make_brand.py        # redraw every asset from tokens.json
python3 tests/brand-assets-test.py # runs on every push; needs nothing but Python
```

## The mark

Two brackets around a four-point spark: the software, and the agent inside it. Two shapes, one
weight, one gap. It is drawn once on a 512-unit artboard and every other size is that artboard
scaled, so the launcher icon, the splash, the notification and the Linux desktop icon cannot
drift apart.

The mark's bounding circle has a radius of exactly half its width, which is what makes every
Android safe-zone rule easy to satisfy honestly rather than by eye.

## The colour

One deep violet, `#7A3CD6` to `#33146F`.

It was settled by rendering the mark beside the four agents this app hosts, at launcher size, and
looking at the row rather than reasoning about it from memory. That test corrected two beliefs.
Antigravity's logo is a multicolour rainbow arch, not the blue-violet it had been assumed to be,
so violet collides with nothing. And Codex and Cursor are *both* dark tiles -- which makes black,
the obvious safe choice for a developer tool, the single colour that would disappear between them
on a home screen. Claude is terracotta. Violet is the slot nothing else occupies.

The hue sits at 264 degrees, deliberately off the blue-violet axis at 250 where a mark reads as
somebody's blue. It is also far from the three colours that mean something inside the app: green
says a line was added, amber says something needs you, red says something was removed or failed.
A brand colour near any of those would make a status hard to trust.

## The tile is three layers, not a gradient

A flat fill reads as a swatch. The tile is the ramp, then a light from the top-left at 18%, then
a shadow into the bottom-right at 16%, and a 12% rim so it still has an edge on a dark wallpaper.
That is what makes it read as an object with light falling on it, and it survives all the way
down to 48 px where a gradient alone would not. The stroke is 38 and the spark's control offset
19 for the same reason: at 48 px a 34 stroke went spindly and a 15 offset collapsed the spark
into a dot.

## The three families, and why the interface has no colour of its own

| Family | Where it is allowed | Why |
| --- | --- | --- |
| Brand | Launcher icon, splash, first-run screen, Linux wallpaper | The product's signature, never a status |
| Neutral | Every surface, every button, every line of text | Warm bone and ink. A button is ink on cream or bone on ink |
| Semantic | Only green, amber and red, one meaning each | Because nothing decorative is coloured, a coloured pixel is always information |

The interface being colourless is the point, not an omission. It is what lets the third family
carry meaning, and it is why the test fails if a brand colour turns up in a theme.

## What gets drawn, and the rule each surface follows

| Asset | Canvas | Mark across | The rule it satisfies |
| --- | --- | --- | --- |
| Adaptive foreground and monochrome | 108 dp | 54 dp | Android masks to the central 72 dp; 54 is three quarters of what a person sees |
| Adaptive background | 108 dp | -- | The brand ramp, no baked corners |
| Legacy launcher | 48/72/96/144/192 px | 58.6% | Square and round, for Android 7.1 and below |
| Notification | 24 dp | 22 dp | Android's own figure; white alpha only, because the system recolours it |
| Android 12+ splash | 288 dp | 132 dp | Inside the 192 dp circle the splash guarantees |
| In-app icon (`pocketagent_icon`) | vector | 58.6% | The launcher tile itself, shown on first run, the home header, App lock and recovery |
| Play listing | 512 px square | 58.6% | 32-bit PNG, sRGB, under 1 MiB, no corners of our own -- the store rounds it |
| Linux desktop mark and wallpaper | 512 px, 1600 px | -- | The wallpaper's edge is the desktop's own ground colour |

## The Linux desktop

The Ubuntu desktop inside the container is part of the same product, so its chrome carries the
same hue. The test states that as a positive requirement rather than a blacklist -- every colour
must sit within 26 degrees of the brand hue -- because a blacklist goes stale the day the brand
changes and this does not. Two exceptions are named rather than inferred: reds and ambers, which
are status there exactly as they are in the app, and the terminal's ANSI palette, which every
program on the system expects to be the sixteen colours it has always been.

References: [Android adaptive icons](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive),
[splash screens](https://developer.android.com/develop/ui/views/launch/splash-screen),
[Google Play icon specifications](https://developer.android.com/distribute/google-play/resources/icon-design-specifications).

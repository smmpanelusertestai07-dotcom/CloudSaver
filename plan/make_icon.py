#!/usr/bin/env python3
"""Draws every PocketIDE icon asset from one definition.

There is one mark and one palette here, and every file below is derived from them, so the
launcher, the Play listing, the themed icon, the notification and the splash can never drift
apart the way hand-exported assets do.

The mark is a prompt chevron held between two square brackets: [ > ]. Brackets because that is
what code looks like at a glance, and a chevron because it is the glyph every terminal and every
run button in the world uses for "go". Together they are the name too -- a pocket, holding the
thing you work with.

The chevron is doing more work than it looks. The first draft used an upright caret, and at
launcher size the mark collapsed into three identical vertical bars -- unreadable, and nothing
like brackets. An angled shape in the middle breaks that rhythm, so the eye separates three
elements instead of counting stripes. The arms are short and the gaps are wide for the same
reason: at 24 px in the status bar, every gap here is one pixel, and a gap that closes is a
blob.

Geometry is defined once on a 1024 px master and scaled down, never up.
"""

import math
import os
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon")

# ---------------------------------------------------------------- palette
#
# Violet, and the reason is the home screen rather than taste. Rendered beside the agents this
# app hosts, the row is: Codex a dark tile, Claude terracotta, Antigravity a multicolour arch,
# VS Code blue. Violet is the slot none of them occupy. Black -- the obvious choice for a
# developer tool -- is the one colour that would disappear between Codex and Cursor.

TILE_TOP = (0x7A, 0x3C, 0xD6)     # #7A3CD6
TILE_BOTTOM = (0x33, 0x14, 0x6F)  # #33146F
TILE_FLAT = (0x56, 0x28, 0x9F)    # #56289F -- a splash takes a colour, not a ramp
MARK = (0xF7, 0xF3, 0xEB)         # #F7F3EB warm bone, never pure white

M = 1024  # master size; every export scales down from here

# ---------------------------------------------------------------- the mark
#
# Sized against the adaptive-icon safe zone: of the 108 dp layer, only the middle 66 dp is
# guaranteed to survive every OEM mask shape. 66/108 of 1024 is 626 px, so the mark lives
# inside a 626 px circle and nothing important reaches past it.

SAFE = M * 66 / 108           # 625.8 px -- the circle no mask ever clips
STROKE = 66                   # bracket and chevron weight
BRACKET_H = 400               # outer height of a bracket
ARM = 74                      # short: a long arm closes the gap to the chevron
CHEV_W = 132                  # horizontal reach of the >
CHEV_H = 230                  # vertical span of the >
GAP = 118                     # bracket inner edge to the chevron's leftmost point


def draw_mark(draw, cx, cy, colour, scale=1.0):
    """Draws [ > ] centred on (cx, cy). One routine for every asset, so they cannot diverge."""
    s = lambda v: v * scale
    half_h = s(BRACKET_H) / 2
    stroke = s(STROKE)
    arm = s(ARM)
    chev_w = s(CHEV_W)
    chev_h = s(CHEV_H)
    gap = s(GAP)
    r = stroke / 2  # rounded ends, so the mark reads as drawn rather than cut

    # Brackets. The spine sits a full gap outside the chevron's own bounding box.
    spine_offset = chev_w / 2 + gap + stroke / 2
    for side in (-1, 1):
        x = cx + side * spine_offset
        draw.rounded_rectangle(
            [x - stroke / 2, cy - half_h, x + stroke / 2, cy + half_h],
            radius=r, fill=colour)
        inner = x - side * arm
        for y in (cy - half_h, cy + half_h - stroke):
            x0, x1 = sorted((x - side * stroke / 2, inner))
            draw.rounded_rectangle([x0, y, x1, y + stroke], radius=r, fill=colour)

    # The chevron: two strokes meeting at a rounded point on the right.
    tip = (cx + chev_w / 2, cy)
    for end in ((cx - chev_w / 2, cy - chev_h / 2), (cx - chev_w / 2, cy + chev_h / 2)):
        _thick_line(draw, end, tip, stroke, colour)
    # A disc at the join, so the corner is round rather than notched.
    draw.ellipse([tip[0] - r, tip[1] - r, tip[0] + r, tip[1] + r], fill=colour)


def _thick_line(draw, a, b, width, colour):
    """A line with round caps. PIL's own round joint mode is unreliable at this scale."""
    draw.line([a, b], fill=colour, width=int(round(width)))
    for p in (a, b):
        draw.ellipse([p[0] - width / 2, p[1] - width / 2,
                      p[0] + width / 2, p[1] + width / 2], fill=colour)


def gradient_tile(size):
    """The violet tile, top-left to bottom-right. No alpha: Play rejects transparency."""
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            px[x, y] = tuple(
                round(TILE_TOP[i] + (TILE_BOTTOM[i] - TILE_TOP[i]) * t) for i in range(3))
    return img


def supersampled(size, paint):
    """Draws at 4x and scales down, because PIL has no antialiased shape fill."""
    big = Image.new("RGBA", (size * 4, size * 4), (0, 0, 0, 0))
    paint(ImageDraw.Draw(big), size * 4)
    return big.resize((size, size), Image.LANCZOS)


def write(img, *path):
    p = os.path.join(OUT, *path)
    os.makedirs(os.path.dirname(p), exist_ok=True)
    img.save(p)
    return p, os.path.getsize(p)


def main():
    os.makedirs(OUT, exist_ok=True)
    made = []

    # ---- 1. Play Store listing icon: 512x512, full square, no alpha, no rounded corners.
    #         Play applies its own mask, so a pre-rounded icon ends up double-masked.
    tile = gradient_tile(512)
    mark = supersampled(512, lambda d, n: draw_mark(d, n / 2, n / 2, MARK, scale=n / M))
    play = tile.copy()
    play.paste(mark, (0, 0), mark)
    made.append(write(play.convert("RGB"), "play-store-icon-512.png"))

    # ---- 2. Adaptive icon layers, 108 dp at every density.
    #         Background is the tile; foreground is the mark alone on transparent, scaled so it
    #         stays inside the 66 dp safe circle even when a round mask crops to it.
    adaptive = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
    for density, px in adaptive.items():
        made.append(write(gradient_tile(px).convert("RGBA"),
                          f"mipmap-{density}", "ic_launcher_background.png"))
        fg = supersampled(px, lambda d, n: draw_mark(d, n / 2, n / 2, MARK, scale=n / M))
        made.append(write(fg, f"mipmap-{density}", "ic_launcher_foreground.png"))
        # Themed icon (Android 13+): the same mark, solid white, for the system to tint.
        mono = supersampled(px, lambda d, n: draw_mark(d, n / 2, n / 2, (255, 255, 255, 255),
                                                       scale=n / M))
        made.append(write(mono, f"mipmap-{density}", "ic_launcher_monochrome.png"))

    # ---- 3. Legacy launcher icons, for launchers that ignore adaptive icons.
    legacy = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for density, px in legacy.items():
        t = gradient_tile(px).convert("RGBA")
        # A legacy icon draws its own corners; adaptive ones must not.
        mask = supersampled(px, lambda d, n: d.rounded_rectangle(
            [0, 0, n - 1, n - 1], radius=n * 0.22, fill=(255, 255, 255, 255)))
        m = supersampled(px, lambda d, n: draw_mark(d, n / 2, n / 2, MARK, scale=n / M * 0.82))
        t.paste(m, (0, 0), m)
        rounded = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        rounded.paste(t, (0, 0), mask)
        made.append(write(rounded, f"mipmap-{density}", "ic_launcher.png"))

    # ---- 4. Notification icon: white silhouette on transparent. Android tints it, so any
    #         colour here would be thrown away, and any gradient would turn to noise.
    notif = {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}
    for density, px in notif.items():
        # Heavier stroke at this size: a hairline disappears in the status bar.
        n_img = supersampled(px, lambda d, n: draw_mark(
            d, n / 2, n / 2, (255, 255, 255, 255), scale=n / M * 1.05))
        made.append(write(n_img, f"drawable-{density}", "ic_notification.png"))

    # ---- 5. Splash: a flat colour, because a window background cannot take a ramp.
    for density, px in {"mdpi": 192, "hdpi": 288, "xhdpi": 384, "xxhdpi": 576,
                        "xxxhdpi": 768}.items():
        sp = supersampled(px, lambda d, n: draw_mark(d, n / 2, n / 2, MARK, scale=n / M * 0.9))
        made.append(write(sp, f"drawable-{density}", "ic_splash.png"))

    # ---- 6. A contact sheet, so the whole set can be judged in one look.
    sizes = [512, 192, 144, 96, 72, 48, 24]
    sheet_w = sum(sizes) + 20 * (len(sizes) + 1)
    sheet = Image.new("RGB", (sheet_w, 560), (0x1A, 0x16, 0x20))
    x = 20
    for s in sizes:
        t = gradient_tile(s).convert("RGBA")
        m = supersampled(s, lambda d, n: draw_mark(d, n / 2, n / 2, MARK, scale=n / M))
        t.paste(m, (0, 0), m)
        sheet.paste(t.convert("RGB"), (x, 40))
        # the same size again as the status-bar silhouette would appear
        w = supersampled(s, lambda d, n: draw_mark(d, n / 2, n / 2, (255, 255, 255, 255),
                                                   scale=n / M * 1.05))
        white = Image.new("RGB", (s, s), (0x1A, 0x16, 0x20))
        white.paste(w, (0, 0), w)
        sheet.paste(white, (x, 40 + s + 24))
        x += s + 20
    made.append(write(sheet, "contact-sheet.png"))

    for p, n in made:
        print(f"  {os.path.relpath(p, OUT):<48} {n:>9,} B")
    print(f"\n{len(made)} files -> {OUT}")


if __name__ == "__main__":
    main()

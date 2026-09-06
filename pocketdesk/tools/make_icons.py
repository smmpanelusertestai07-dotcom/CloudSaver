#!/usr/bin/env python3
"""Draws PocketLinux's own desktop icons.

The desktop borrows Adwaita for everything Ubuntu ships, but the four entries PocketLinux adds
itself have no theme icon that fits: Projects had none at all and fell back to a blank sheet,
Software asked for "system-software-install" (a name Adwaita does not always carry, which is the
grey question mark on the desktop), and Settings did not exist. These are drawn here instead of
being taken from a theme, so they are the same on any Ubuntu the container ends up with.

Drawn at 4x and scaled down, which is the whole anti-aliasing story: Pillow's draw has no
smoothing of its own, and a 128-pixel icon drawn directly has ragged edges on a phone screen.

    python3 tools/make_icons.py
"""
import math
import os

from PIL import Image, ImageDraw

SIZE = 256
SS = 4          # supersampling
W = SIZE * SS
HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(HERE, "app", "assets")

AMBER = (243, 176, 51, 255)
AMBER_DEEP = (222, 148, 26, 255)
INK = (23, 32, 61, 255)
BLUE = (58, 92, 214, 255)
BLUE_DEEP = (36, 60, 152, 255)
STEEL = (108, 132, 190, 255)
WHITE = (255, 255, 255, 255)
ORANGE = (233, 84, 32, 255)
ORANGE_DEEP = (188, 60, 16, 255)
SLATE = (44, 56, 92, 255)


def canvas():
    image = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    return image, ImageDraw.Draw(image)


def save(image, name):
    image.resize((SIZE, SIZE), Image.LANCZOS).save(os.path.join(OUT, name))
    print("wrote", name)


def s(value):
    """A length given in 256-pixel units, in supersampled pixels."""
    return int(round(value * SS))


def folder(draw, body, flap):
    """The folder every file icon here is built on: a raised tab and a deep front."""
    draw.rounded_rectangle([s(22), s(58), s(234), s(212)], radius=s(18), fill=flap)
    draw.rounded_rectangle([s(22), s(48), s(116), s(86)], radius=s(12), fill=flap)
    draw.rounded_rectangle([s(22), s(88), s(234), s(212)], radius=s(18), fill=body)


def projects():
    """Projects: a folder with the mark every editor in the world puts on source code."""
    image, draw = canvas()
    folder(draw, AMBER, AMBER_DEEP)
    pen = s(11)
    # < > around a slash, in the ink of the rest of the set so it reads at panel size.
    draw.line([(s(104), s(126)), (s(80), s(150)), (s(104), s(174))], fill=INK,
              width=pen, joint="curve")
    draw.line([(s(152), s(126)), (s(176), s(150)), (s(152), s(174))], fill=INK,
              width=pen, joint="curve")
    draw.line([(s(138), s(118)), (s(118), s(182))], fill=INK, width=pen)
    save(image, "pocketdesk-projects.png")


def settings():
    """Settings: a gear, which is the one shape every desktop agrees means settings."""
    image, draw = canvas()
    cx = cy = s(128)
    teeth = 8
    outer, inner = s(104), s(80)
    points = []
    for step in range(teeth * 4):
        angle = step * (2 * math.pi / (teeth * 4)) - math.pi / 8
        radius = outer if (step % 4) in (0, 1) else inner
        points.append((cx + radius * math.cos(angle), cy + radius * math.sin(angle)))
    draw.polygon(points, fill=BLUE_DEEP)
    draw.ellipse([cx - s(86), cy - s(86), cx + s(86), cy + s(86)], fill=BLUE)
    draw.ellipse([cx - s(38), cy - s(38), cx + s(38), cy + s(38)], fill=WHITE)
    draw.ellipse([cx - s(22), cy - s(22), cx + s(22), cy + s(22)], fill=BLUE_DEEP)
    save(image, "pocketdesk-settings.png")


def software():
    """Software: a box being opened, with the arrow that means "bring it down and install it"."""
    image, draw = canvas()
    draw.rounded_rectangle([s(30), s(92), s(226), s(224)], radius=s(20), fill=ORANGE)
    draw.rounded_rectangle([s(30), s(92), s(226), s(126)], radius=s(16), fill=ORANGE_DEEP)
    # The handle: a strap over the lid, so it is a box and not a plain rectangle.
    draw.arc([s(88), s(38), s(168), s(126)], start=180, end=360, fill=ORANGE_DEEP, width=s(13))
    pen = s(15)
    draw.line([(s(128), s(140)), (s(128), s(190))], fill=WHITE, width=pen)
    draw.line([(s(100), s(166)), (s(128), s(194)), (s(156), s(166))], fill=WHITE,
              width=pen, joint="curve")
    draw.line([(s(92), s(210)), (s(164), s(210))], fill=WHITE, width=s(12))
    save(image, "pocketdesk-software.png")


def package():
    """Install a downloaded app: a parcel, which is what a .deb is. It wore Tux before, and Tux
    is Linux itself rather than a package, so the desktop had the mascot on two different things.
    """
    image, draw = canvas()
    draw.rounded_rectangle([s(28), s(74), s(228), s(220)], radius=s(18), fill=(150, 104, 62, 255))
    draw.rounded_rectangle([s(28), s(74), s(228), s(118)], radius=s(14), fill=(186, 132, 78, 255))
    # The tape down the middle, the one mark that makes a rectangle read as a parcel.
    draw.rectangle([s(112), s(74), s(144), s(220)], fill=(214, 168, 108, 255))
    draw.rectangle([s(28), s(112), s(228), s(126)], fill=(214, 168, 108, 255))
    pen = s(14)
    draw.line([(s(128), s(146)), (s(128), s(186))], fill=WHITE, width=pen)
    draw.line([(s(104), s(166)), (s(128), s(190)), (s(152), s(166))], fill=WHITE,
              width=pen, joint="curve")
    save(image, "pocketdesk-package.png")


def phone_files():
    """Phone files: the phone itself, with a folder on its screen.

    Redrawn heavier than the first one, which was a thin outline that disappeared against the
    wallpaper at panel size and read as an empty rectangle on the desktop.
    """
    image, draw = canvas()
    draw.rounded_rectangle([s(58), s(14), s(198), s(242)], radius=s(30), fill=INK)
    draw.rounded_rectangle([s(70), s(38), s(186), s(212)], radius=s(12), fill=(238, 243, 252, 255))
    draw.rounded_rectangle([s(108), s(22), s(148), s(30)], radius=s(4), fill=STEEL)
    draw.ellipse([s(118), s(218), s(138), s(238)], fill=STEEL)
    # The folder on the screen, the same folder as everywhere else in the set.
    draw.rounded_rectangle([s(86), s(96), s(170), s(180)], radius=s(10), fill=AMBER_DEEP)
    draw.rounded_rectangle([s(86), s(88), s(124), s(108)], radius=s(6), fill=AMBER_DEEP)
    draw.rounded_rectangle([s(86), s(110), s(170), s(180)], radius=s(10), fill=AMBER)
    save(image, "pocketdesk-phone.png")


def home_files():
    """The file manager: the same folder, on its own, so the two read as one family."""
    image, draw = canvas()
    folder(draw, AMBER, AMBER_DEEP)
    save(image, "pocketdesk-files.png")


if __name__ == "__main__":
    projects()
    settings()
    software()
    package()
    phone_files()
    home_files()

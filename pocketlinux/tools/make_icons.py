#!/usr/bin/env python3
"""Draws PocketLinux's own desktop icons.

The desktop borrows Adwaita for everything Ubuntu ships, but the seven entries PocketLinux adds
itself have no theme icon that fits: Projects had none at all and fell back to a blank sheet,
Software asked for "system-software-install" (a name Adwaita does not always carry, which is the
grey question mark on the desktop), the Bin asked for "user-trash" and came up empty on the
phones whose Adwaita has no full-colour copy of it, and Settings did not exist. So all seven are
drawn here instead of being taken from a theme -- Projects, Settings, Software, the installer,
Phone files, Files and the Bin -- and they are the same on any Ubuntu the container ends up with.

Tux on the panel's Apps button and the PocketLinux mark in the panel corner are shipped pictures,
not drawn by this script.

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
STEEL_DEEP = (74, 96, 150, 255)
WHITE = (255, 255, 255, 255)
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
    save(image, "pocketlinux-projects.png")


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
    save(image, "pocketlinux-settings.png")


def software():
    """Software: a shop bag carrying a grid of app tiles, which is the place you go to get apps.

    It was a saturated orange box with a heavy white down-arrow, and the owner asked whether it
    was a warning. Two things were wrong with it. Saturated orange is the colour a phone warns
    you in, and every other icon here is amber, blue or brown. And the installer beside it is
    also a container carrying the same white arrow, so the two easiest things to mix up on the
    desktop looked alike. The arrow is the installer's alone now.
    """
    image, draw = canvas()
    draw.rounded_rectangle([s(44), s(86), s(212), s(226)], radius=s(18), fill=BLUE)
    draw.rounded_rectangle([s(44), s(86), s(212), s(120)], radius=s(14), fill=BLUE_DEEP)
    # The handle over the mouth, which is what makes it a bag and not another parcel. Its ends run
    # below the top edge so the stroke joins the bag instead of floating above it.
    draw.arc([s(92), s(36), s(164), s(140)], start=180, end=360, fill=BLUE_DEEP, width=s(13))
    # Four tiles: the app grid every phone owner reads as "apps" without being told.
    for top in (s(128), s(180)):
        for left in (s(82), s(134)):
            draw.rounded_rectangle([left, top, left + s(40), top + s(40)], radius=s(6), fill=WHITE)
    save(image, "pocketlinux-software.png")


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
    save(image, "pocketlinux-package.png")


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
    save(image, "pocketlinux-phone.png")


def home_files():
    """The file manager: the same folder, on its own, so the two read as one family."""
    image, draw = canvas()
    folder(draw, AMBER, AMBER_DEEP)
    save(image, "pocketlinux-files.png")


def bin_icon():
    """The Bin: a lid, a body and three ribs.

    The Bin was the last launcher still asking the icon theme for a name, and on the phones whose
    Adwaita carries no full-colour user-trash it came up blank, which is the whole reason this
    file exists. Grey-blue rather than the amber of the folders, because what is in here is on its
    way out.
    """
    image, draw = canvas()
    draw.rounded_rectangle([s(106), s(40), s(150), s(58)], radius=s(6), fill=STEEL_DEEP)
    draw.rounded_rectangle([s(40), s(58), s(216), s(90)], radius=s(12), fill=STEEL_DEEP)
    draw.rounded_rectangle([s(56), s(96), s(200), s(224)], radius=s(18), fill=STEEL)
    # The ribs down the front. Without them the body is a plain cup at panel size.
    for left in (s(84), s(122), s(160)):
        draw.rounded_rectangle([left, s(124), left + s(14), s(196)], radius=s(7), fill=WHITE)
    save(image, "pocketlinux-bin.png")


if __name__ == "__main__":
    projects()
    settings()
    software()
    package()
    phone_files()
    home_files()
    bin_icon()

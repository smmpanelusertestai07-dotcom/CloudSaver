#!/usr/bin/env python3
"""Checks that PocketAgent still looks like one product.

A design system is only real if something refuses the drift. This reads branding/tokens.json --
the file tools/make_brand.py draws everything from -- and holds the rest of the repository to
it: the same colours in Java and in resources, the mark inside every Android safe zone, a
notification icon that is pure white because Android will recolour it anyway, no survivor of the
palette this app inherited, and the app's own icon actually on the screens that should show it.

Standard library only, on purpose: it runs on every push, where cairosvg and Pillow are not
installed. The generator needs them; this does not.

    python3 tests/brand-assets-test.py
"""
import colorsys
import json
import os
import re
import struct
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKENS = json.load(open(os.path.join(ROOT, "branding", "tokens.json")))
BRAND, GEOMETRY, DESKTOP = TOKENS["brand"], TOKENS["geometry"], TOKENS["desktop"]
failures = []


def check(condition, message):
    if not condition:
        failures.append(message)


def read(*parts):
    with open(os.path.join(ROOT, *parts), encoding="utf-8") as handle:
        return handle.read()


def colours(text):
    return {value.upper() for value in re.findall(r"#[0-9a-fA-F]{6,8}", text)}


# ---------------------------------------------------------------- one palette, three files
java = read("app", "src", "com", "pocketagent", "mobile", "Brand.java")
resources = read("app", "res", "values", "colors.xml")
for name, value in BRAND.items():
    if name == "_":
        continue
    check(value.upper() in colours(java),
          f"Brand.java does not carry {name} = {value} from branding/tokens.json")
    check(value.upper() in colours(resources),
          f"colors.xml does not carry {name} = {value} from branding/tokens.json")

# The interface accent stays neutral by design: a brand colour used for a button would compete
# with the three status colours, which are the only colours in this app that mean anything.
for style_file in ("values/styles.xml", "values-night/styles.xml", "values-v31/styles.xml"):
    used = colours(read("app", "res", style_file))
    for name in ("tile_top", "tile_bottom", "accent", "accent_on_dark"):
        check(BRAND[name].upper() not in used,
              f"{style_file} paints the interface with brand {name}; the interface is neutral")


# ---------------------------------------------------------------- the mark, inside every safe zone
def vector_reach(path):
    """Half the width of the smallest circle round the mark, in the vector's own dp units."""
    text = read(*path.split("/"))
    group = re.search(r'<group android:scaleX="([-\d.]+)"[^>]*?android:translateX="([-\d.]+)"'
                      r'\s+android:translateY="([-\d.]+)"', text, re.S)
    check(group is not None, f"{path}: no <group> transform to measure")
    if group is None:
        return 0, text
    scale, dx, dy = (float(group.group(i)) for i in (1, 2, 3))
    stroke = max([float(w) for w in re.findall(r'android:strokeWidth="([\d.]+)"', text)] or [0])
    reach = 0.0
    for data in re.findall(r'android:pathData="([^"]+)"', text):
        # Only M/L/Q/Z appear in the mark, so every number is half of an x,y pair. An arc would
        # break that, so say so rather than measure something false.
        check(not re.search(r"[AaCcSsTtHhVv]", data), f"{path}: unmeasurable path command")
        numbers = [float(n) for n in re.findall(r"-?\d+(?:\.\d+)?", data)]
        for x, y in zip(numbers[0::2], numbers[1::2]):
            px, py = x * scale + dx, y * scale + dy
            reach = max(reach, ((px - CENTRE[path]) ** 2 + (py - CENTRE[path]) ** 2) ** 0.5)
    return reach + stroke * scale / 2, text


# canvas size, and the radius Android guarantees is visible on that canvas
SURFACES = {
    "app/res/drawable/ic_launcher_foreground.xml": (108, 33),    # adaptive keyline circle, 66 dp
    "app/res/drawable/ic_launcher_monochrome.xml": (108, 33),
    "app/res/drawable/ic_splash_pocketagent.xml": (288, 96),     # splash inner circle, 192 dp
    "app/res/drawable/ic_stat_pocketagent.xml": (24, 12),        # the whole notification canvas
}
CENTRE = {path: canvas / 2 for path, (canvas, _) in SURFACES.items()}
for path, (canvas, safe) in SURFACES.items():
    reach, text = vector_reach(path)
    check(reach <= safe, f"{path}: mark reaches {reach:.1f} dp, outside the {safe} dp safe circle")
    check(reach >= safe * 0.55, f"{path}: mark is only {reach:.1f} dp of {safe} dp, too small to read")
    check(f'android:width="{canvas}dp"' in text, f"{path}: canvas is not {canvas}dp")

notification = read("app/res/drawable/ic_stat_pocketagent.xml".replace("/", os.sep))
check("<gradient" not in notification, "Notification icon must be flat; Android recolours it")
check(colours(notification) == {"#FFFFFFFF"},
      f"Notification icon must be white alpha only, found {sorted(colours(notification))}")

for name in ("ic_launcher", "ic_launcher_round"):
    v26 = read("app", "res", "mipmap-anydpi-v26", f"{name}.xml")
    v33 = read("app", "res", "mipmap-anydpi-v33", f"{name}.xml")
    check("@drawable/launcher_gradient" in v26 and "@drawable/launcher_gradient" in v33,
          f"{name}: the adaptive background should be the brand gradient")
    check("@drawable/ic_launcher_monochrome" in v33, f"{name}: Android 13 themed icon missing")
    check("monochrome" not in v26, f"{name}: v26 must not claim a monochrome layer")


# ---------------------------------------------------------------- the bitmaps
def png_header(*parts):
    with open(os.path.join(ROOT, *parts), "rb") as handle:
        data = handle.read()
    width, height = struct.unpack(">II", data[16:24])
    chunks, offset = [], 8
    while offset < len(data):
        length = struct.unpack(">I", data[offset:offset + 4])[0]
        chunks.append(data[offset + 4:offset + 8].decode("ascii"))
        offset += 12 + length
    return width, height, data[24], data[25], chunks, len(data)

for density, size in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)):
    for suffix in ("", "_round"):
        w, h, depth, kind, _, _ = png_header("app", "res", f"mipmap-{density}", f"ic_launcher{suffix}.png")
        check((w, h, depth, kind) == (size, size, 8, 6),
              f"mipmap-{density}/ic_launcher{suffix}.png is {w}x{h} depth {depth} type {kind}, "
              f"expected {size}x{size} 8-bit RGBA")

w, h, depth, kind, chunks, length = png_header("branding", "play-store-icon.png")
check((w, h, depth, kind) == (512, 512, 8, 6), "Play listing icon must be a 512x512 32-bit PNG")
check("sRGB" in chunks, "Play listing icon must be tagged sRGB")
check(length <= 1024 * 1024, "Play listing icon must stay under 1 MiB")


# ---------------------------------------------------------------- nothing left of the old palette
RETIRED = {
    "#4D7FF3": "the cobalt the mark used to be drawn in",
    "#315BDC": "the cobalt the mark used to be drawn in",
    "#79AAFF": "the cobalt the mark used to be drawn in",
    "#B5D8FF": "the cobalt the mark used to be drawn in",
    "#191A1B": "the grey the launcher background used to be",
    "#0B1320": "the navy the Linux desktop used to be",
    "#E6ECF7": "the navy desktop's text colour",
    "#1746C4": "the navy desktop's accent",
}
searched = []
searched += [os.path.join(ROOT, name) for name in os.listdir(ROOT) if name.endswith(".md")]
for folder in ("app/res", "app/src", "app/assets", "branding", "docs"):
    for base, _, names in os.walk(os.path.join(ROOT, folder)):
        if "__pycache__" in base:
            continue
        for name in names:
            if name.rsplit(".", 1)[-1] in ("xml", "java", "sh", "py", "svg", "json", "md"):
                searched.append(os.path.join(base, name))
for path in searched:
    try:
        found = colours(open(path, encoding="utf-8").read())
    except (UnicodeDecodeError, OSError):
        continue
    for dead, why in RETIRED.items():
        check(dead not in found,
              f"{os.path.relpath(path, ROOT)} still carries {dead} -- {why}")

# The desktop's chrome must BE the brand's hue, rather than merely not be some retired one: a
# blacklist goes stale the day the brand changes, a positive rule does not. Near-neutral greys
# are exempt because they have no hue to be wrong about, and so is the terminal's ANSI palette,
# which every program on the system expects to be the sixteen colours it has always been.
# The four colours the desktop is actually built from have to be the ones named here, not
# whatever a re-tint happened to land on: without this the scripts drift a shade at a time.
desktop_scripts = read("app", "assets", "pocketagent-desktop.sh") + read("app", "assets", "pocketagent-menu.sh")
for name in ("ground", "ground_deep", "on_ground", "on_ground_muted"):
    check(DESKTOP[name].upper() in colours(desktop_scripts),
          f"the desktop scripts do not use {name} = {DESKTOP[name]} from branding/tokens.json")

brand_hue = DESKTOP["hue_degrees"]
tolerance = DESKTOP["hue_tolerance"]
for script in ("pocketagent-desktop.sh", "pocketagent-menu.sh"):
    for number, line in enumerate(read("app", "assets", script).split("\n"), 1):
        if DESKTOP["exempt_lines_containing"] in line:
            continue
        for value in re.findall(r"#[0-9a-fA-F]{6}", line):
            r, g, b = (int(value[i:i + 2], 16) / 255 for i in (1, 3, 5))
            hue, _, saturation = colorsys.rgb_to_hls(r, g, b)
            if saturation <= DESKTOP["saturation_floor"]:
                continue
            degrees = hue * 360
            distance = min(abs(degrees - brand_hue), 360 - abs(degrees - brand_hue))
            # Reds and ambers survive by being named, not by being far away: a distance rule
            # would let a stray teal through on the same argument.
            status = any((low <= degrees or degrees <= high) if low > high else (low <= degrees <= high)
                         for low, high in DESKTOP["status_hue_bands"])
            check(status or distance <= tolerance,
                  f"{script}:{number} paints {value} at {degrees:.0f} degrees, "
                  f"{distance:.0f} off the brand's {brand_hue:.0f}")


# ---------------------------------------------------------------- the icon, inside the app
SHOWS_THE_ICON = {
    "app/src/com/pocketagent/mobile/MainActivity.java": 3,   # first run, home header, recovery
    "app/src/com/pocketagent/mobile/AppLock.java": 1,
}
for path, count in SHOWS_THE_ICON.items():
    text = read(*path.split("/"))
    check(text.count("R.drawable.pocketagent_icon") == count,
          f"{path} should show the app's own icon {count} time(s)")

intro = read("app/src/com/pocketagent/mobile/MainActivity.java")
check("Brand.TILE_TOP" in intro and "Brand.TILE_BOTTOM" in intro,
      "The first-run screen should continue the launcher icon's own ramp")
check("Color.rgb(13, 27, 62)" not in intro, "The first-run screen is still painted the old navy")

if failures:
    print("FAIL BrandAssets")
    for line in failures:
        print("  " + line)
    sys.exit(1)
print(f"PASS BrandAssets ({len(SURFACES)} safe zones, 10 bitmaps, {len(searched)} files swept "
      f"for the retired palette)")

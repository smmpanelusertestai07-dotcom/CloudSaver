#!/usr/bin/env python3
# Builds app/assets/wallpaper.jpg: a 1600x1600 square whose four edges are exactly the desktop's
# own background (#0b1320), so pcmanfm's "fit" mode letterboxes it invisibly on any screen shape
# -- 720x1600 held upright, 1600x720 turned over -- with nothing cropped and nothing stretched.
# Run from the repository root:  python3 tools/make_wallpaper.py
from PIL import Image, ImageDraw, ImageFont

SIZE = 1600
BASE = (11, 19, 32)        # #0b1320 -- pcmanfm desktop_bg, the colour of the letterbox bands
GLOW = (27, 58, 134)       # #1b3a86 -- the brand blue, at the centre of the glow
TUX = "app/res/drawable-nodpi/tux.png"
OUT = "app/assets/wallpaper.jpg"
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

N = 256
mask = Image.new("L", (N, N), 0)
px = mask.load()
cx = cy = (N - 1) / 2.0
for y in range(N):
    for x in range(N):
        d = (((x - cx) ** 2 + (y - cy) ** 2) ** 0.5) / (N / 2.0)
        v = max(0.0, 1.0 - d)
        px[x, y] = int(255 * (v ** 1.8))
mask = mask.resize((SIZE, SIZE), Image.BICUBIC)

image = Image.new("RGB", (SIZE, SIZE), BASE)
image = Image.composite(Image.new("RGB", (SIZE, SIZE), GLOW), image, mask)

tux = Image.open(TUX).convert("RGBA")
image.paste(tux, ((SIZE - tux.width) // 2, 470), tux)

draw = ImageDraw.Draw(image)
name = ImageFont.truetype(FONT_BOLD, 76)
line = ImageFont.truetype(FONT, 34)
for text, font, y, fill in (("PocketDesk", name, 1055, (230, 236, 247)),
                            ("Ubuntu 24.04 LTS  ·  Linux", line, 1160, (122, 145, 190))):
    w = draw.textbbox((0, 0), text, font=font)[2]
    draw.text(((SIZE - w) // 2, y), text, font=font, fill=fill)

image.save(OUT, "JPEG", quality=92, optimize=True, progressive=True)
print("wrote", OUT, image.size)

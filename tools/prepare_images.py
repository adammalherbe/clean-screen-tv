"""Builds the bundled backgrounds, frosted-glass backdrops, banner and icons.

Usage: python prepare_images.py <dir with mountain/forest/coast/desert/aurora source jpgs> <project root>
"""
import sys
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont

src, root = sys.argv[1], sys.argv[2]
assets = f"{root}/app/src/main/assets"
res = f"{root}/app/src/main/res"
# Vertical crop anchor per image (0 = top, 1 = bottom) when cutting to 16:9.
# Five scenes, all CC0 (see CREDITS.md); the app tints them by time of day.
ANCHOR = {"mountain": 0.5, "forest": 0.45, "coast": 0.5, "desert": 0.5, "aurora": 0.4}

def crop169(im, anchor):
    w, h = im.size
    th = round(w * 9 / 16)
    if th <= h:
        y = round((h - th) * anchor)
        return im.crop((0, y, w, y + th))
    tw = round(h * 16 / 9)
    x = (w - tw) // 2
    return im.crop((x, 0, x + tw, h))

for name, anchor in ANCHOR.items():
    im = crop169(Image.open(f"{src}/{name}.jpg").convert("RGB"), anchor)
    bg = im.resize((1920, 1080), Image.LANCZOS)
    bg.save(f"{assets}/bg/{name}.jpg", quality=88, optimize=True, subsampling=0)
    frost = im.resize((480, 270), Image.LANCZOS).filter(ImageFilter.GaussianBlur(5))
    frost = ImageEnhance.Color(frost).enhance(1.15)
    frost = ImageEnhance.Brightness(frost).enhance(1.0)
    frost.save(f"{assets}/frost/{name}.jpg", quality=92)

def font(size):
    for path, idx in [("/System/Library/Fonts/HelveticaNeue.ttc", 7), ("/System/Library/Fonts/HelveticaNeue.ttc", 1)]:
        try:
            return ImageFont.truetype(path, size, index=idx)
        except Exception:
            pass
    return ImageFont.load_default()

def art(w, h):
    im = crop169(Image.open(f"{src}/mountain.jpg").convert("RGB"), 0.5)
    scale = max(w / im.width, h / im.height)
    im = im.resize((round(im.width * scale) + 1, round(im.height * scale) + 1), Image.LANCZOS)
    x, y = (im.width - w) // 2, (im.height - h) // 2
    im = im.crop((x, y, x + w, y + h))
    return Image.blend(im, Image.new("RGB", im.size, "black"), 0.25)

# Store icons for tiles whose app isn't installed yet (shown dimmed), rounded like app icons.
import os
for name, src_name in [("app_spotify", "com.spotify.tv.android.png"), ("app_drtv", "dk.dr.tvplayer.png")]:
    path = f"{src}/../icons/{src_name}"
    if not os.path.exists(path):
        continue
    im = Image.open(path).convert("RGBA").resize((192, 192), Image.LANCZOS)
    mask = Image.new("L", (192 * 4, 192 * 4), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 192 * 4 - 1, 192 * 4 - 1), radius=192 * 4 * 0.22, fill=255)
    out = Image.new("RGBA", (192, 192), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask.resize((192, 192), Image.LANCZOS))
    os.makedirs(f"{res}/drawable-nodpi", exist_ok=True)
    out.save(f"{res}/drawable-nodpi/{name}.png")

# Leanback banner, 320x180 at xhdpi.
banner = art(320, 180)
d = ImageDraw.Draw(banner)
f = font(46)
d.text((160, 90), "Adam TV", font=f, fill="white", anchor="mm")
banner.save(f"{res}/drawable-xhdpi/banner.png")

# Launcher icon.
for folder, size in [("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)]:
    big = size * 4
    base = art(big, big).convert("RGBA")
    ImageDraw.Draw(base).text((big / 2, big / 2), "A", font=font(int(big * 0.55)), fill="white", anchor="mm")
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, big - 1, big - 1), radius=big * 0.22, fill=255)
    icon = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    icon.paste(base, (0, 0), mask)
    icon.resize((size, size), Image.LANCZOS).save(f"{res}/{folder}/ic_launcher.png")
print("ok")

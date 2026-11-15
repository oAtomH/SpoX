"""Pixel icons for Android's media controls (notification, lock screen, Now Bar), cut from the
skin's own buttons: the light glyph of each button becomes a white mask, scaled up with no
smoothing. Android tints these itself. Run from the repo root after changing the buttons."""
from PIL import Image

FRAME = {(31, 33, 35), (52, 54, 59), (88, 91, 99)}
OUT = "android/app/src/main/res/drawable-nodpi/"

def glyph(name):
    im = Image.open(f"skin/{name}.png").convert("RGBA")
    w, h = im.size
    # skip the bevel round the edge; keep the glyph inside
    pts = [(x, y) for x in range(2, w - 2) for y in range(2, h - 2) if im.getpixel((x, y))[3] and im.getpixel((x, y))[:3] not in FRAME]
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    x0, y0 = min(xs), min(ys)
    return {(x - x0, y - y0) for x, y in pts}, max(xs) - x0 + 1, max(ys) - y0 + 1

def save(name, pts, gw, gh, bar=False):
    size = max(gw, gh + (3 if bar else 0)) + 4
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ox, oy = (size - gw) // 2, (size - gh - (3 if bar else 0)) // 2
    for x, y in pts:
        im.putpixel((ox + x, oy + y), (255, 255, 255, 255))
    if bar:  # "on": a small bar under the glyph
        for x in range(size // 2 - 2, size // 2 + 2):
            im.putpixel((x, oy + gh + 2), (255, 255, 255, 255))
    k = max(1, 96 // size)
    im.resize((size * k, size * k), Image.NEAREST).save(OUT + name + ".png")

import os
os.makedirs(OUT, exist_ok=True)
for b in ["play", "pause", "next", "prev", "shuffle", "repeat"]:
    p, w, h = glyph("btn_" + b)
    save("ic_px_" + b, p, w, h)
    if b in ("shuffle", "repeat"):
        save("ic_px_" + b + "_on", p, w, h, bar=True)
print("done")

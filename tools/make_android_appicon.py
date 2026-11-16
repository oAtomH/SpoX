"""SpoX's Android icons from the pixel art in skin/icon.png (run from the repo root):
 - the launcher icon as an "adaptive" icon: the vinyl on a matching dark background, so phones
   (One UI included) don't put it on a white plate
 - a themed (one-colour) version for Android's themed icons
 - the small status-bar / notification icon: the vinyl's outline and highlights, not a blob"""
from PIL import Image
import os

src = Image.open("skin/icon.png").convert("RGBA")
W, H = src.size
res = "android/app/src/main/res/"
os.makedirs(res + "drawable-nodpi", exist_ok=True)
os.makedirs(res + "mipmap-anydpi-v26", exist_ok=True)
os.makedirs(res + "values", exist_ok=True)

def place(img, canvas, scale):
    big = img.resize((W * scale, H * scale), Image.NEAREST)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.paste(big, ((canvas - big.width) // 2, (canvas - big.height) // 2), big)
    return out

# adaptive foreground: 108dp canvas (432 px), the vinyl 16 px * 18 = 288 px (fills the visible area)
place(src, 432, 18).save(res + "drawable-nodpi/ic_launcher_fg.png")

opaque = lambda x, y: 0 <= x < W and 0 <= y < H and src.getpixel((x, y))[3] > 0
light = {(88, 91, 99), (46, 122, 79)}
mask = Image.new("RGBA", (W, H), (0, 0, 0, 0))
for y in range(H):
    for x in range(W):
        c = src.getpixel((x, y))
        if not c[3]:
            continue
        edge = any(not opaque(x + dx, y + dy) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)))
        if edge or c[:3] in light:
            mask.putpixel((x, y), (255, 255, 255, 255))
place(mask, 432, 18).save(res + "drawable-nodpi/ic_launcher_mono.png")
place(mask, 96, 5).save(res + "drawable/ic_stat_spox.png")

# background = the darkest colour of the art, so the edge of the vinyl melts into it
dark = min((src.getpixel((x, y)) for x in range(W) for y in range(H) if src.getpixel((x, y))[3]), key=lambda c: sum(c[:3]))
open(res + "values/ic_colors.xml", "w").write(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_bg">#%02X%02X%02X</color>\n</resources>\n' % dark[:3])
open(res + "mipmap-anydpi-v26/ic_launcher.xml", "w").write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_bg" />
    <foreground android:drawable="@drawable/ic_launcher_fg" />
    <monochrome android:drawable="@drawable/ic_launcher_mono" />
</adaptive-icon>
''')
print("icons done, background", dark[:3])

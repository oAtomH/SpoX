"""Redraws skin/_guides/*.png from the CURRENT skin (your art) + skin.json. Safe: never touches your PNGs.
Usage: python tools/make_guides.py skin"""
import json, os, sys
from PIL import Image, ImageDraw, ImageFont
D = sys.argv[1] if len(sys.argv) > 1 else "skin"
j = json.load(open(os.path.join(D, "skin.json")))
S = 4
def sz(n):
    return Image.open(os.path.join(D, n + ".png")).size
def guide(bg, items, fname):
    im = Image.open(os.path.join(D, bg + ".png")).convert("RGBA")
    im = im.resize((im.width * S, im.height * S), Image.NEAREST)
    d = ImageDraw.Draw(im); f = ImageFont.load_default()
    for name, (x, y, w, h) in items:
        p = os.path.join(D, name + ".png")
        if os.path.exists(p) and name not in ("digits",):
            el = Image.open(p).convert("RGBA").resize((w * S, h * S), Image.NEAREST)
            im.alpha_composite(el, (x * S, y * S))
    for name, (x, y, w, h) in items:
        d.rectangle([x * S, y * S, (x + w) * S - 1, (y + h) * S - 1], outline=(255, 0, 255, 255))
        d.text((x * S + 2, y * S + 1), f"{name} {w}x{h}", fill=(255, 0, 255, 255), font=f)
    os.makedirs(os.path.join(D, "_guides"), exist_ok=True)
    im.save(os.path.join(D, "_guides", fname))
pos, rect = j["pos"], j["rect"]
main = [(n, (*pos[n], *sz(n))) for n in ["btn_menu", "btn_pin", "btn_min", "btn_close", "btn_shuffle", "btn_repeat", "btn_prev", "btn_play", "btn_pause", "btn_next", "btn_like", "btn_add", "btn_link", "btn_panel", "btn_mini"] if n in pos]
dw = j["digits"]["width"]; cw = j["digits"].get("colon_width", dw)
main += [(n, (*pos[n], *sz(n))) for n in ["btn_like", "btn_add", "btn_link"] if n in pos]
main += [("art_empty", tuple(rect["art"])),
         ("timer", (*pos["timer"], 4 * dw + cw, sz("digits")[1])), ("vis", tuple(rect["vis"]))]
main += [(k, tuple(rect[k])) for k in ["vol", "seek", "title", "artist", "album", "info", "infobox", "pfp"] if k in rect]
guide("main_bg", main, "layout_main.png")
# the panel is an accordion now: rows of 16px + 4px gaps, open sections below their row
acc = j.get("accordion", {}); hh = acc.get("header_h", 20); ch = acc.get("content_h", 96)
y = 4; panel = []
for name in ["tab_playlist", "tab_queue", "tab_lyrics"]:
    panel.append((name + " row", (8, y, 272, hh - 4))); y += hh
    if name == "tab_playlist":
        panel += [("list_bg (open section)", (8, y, 260, ch)), ("scroll_bg", (272, y, 8, ch))]; y += ch + 4
guide("panel_bg", panel, "layout_panel.png")
print("guides updated")

# NOTE: this regenerates the ORIGINAL default skin and would overwrite your art. Use make_guides.py instead.
"""Generates SpoX's default skin: every GUI element as its own PNG + layout guides.
Usage: python3 gen_skin.py <out_skin_dir>
"""
import json, os, sys, unicodedata
from PIL import Image, ImageDraw, ImageFont
sys.path.insert(0, os.path.dirname(__file__))
from font_glyphs import G, ACCENTS

OUT = sys.argv[1]
os.makedirs(os.path.join(OUT, "_guides"), exist_ok=True)

def hexc(h, a=255):
    h = h.lstrip('#'); return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)

# ---------- palette ----------
P = dict(
    edge="#0A0A0B", face="#25262A", hi="#3E4046", lo="#151619",
    title="#1B1C1F", rule="#34363B", rule2="#2A2C30",
    disp="#0B0C0D", disp_edge="#050506",
    icon="#C3C6CC", icon_dim="#6E727A", green="#5BE38E", green_dim="#2E7A4F",
    btn="#2C2D32", btn_down="#1F2023",
)
C = {k: hexc(v) for k, v in P.items()}

def save(img, name):
    img.save(os.path.join(OUT, name + ".png"))

def blank(w, h, col=(0, 0, 0, 0)):
    return Image.new("RGBA", (w, h), col)

def bevel(d, x, y, w, h, light, dark, fill=None):
    if fill: d.rectangle([x, y, x + w - 1, y + h - 1], fill=fill)
    d.line([x, y, x + w - 1, y], fill=light); d.line([x, y, x, y + h - 1], fill=light)
    d.line([x, y + h - 1, x + w - 1, y + h - 1], fill=dark); d.line([x + w - 1, y, x + w - 1, y + h - 1], fill=dark)

def stamp(img, rows, x, y, col):
    for j, r in enumerate(rows):
        for i, ch in enumerate(r):
            if ch == '#': img.putpixel((x + i, y + j), col)

# ---------- font ----------
CELL_W, CELL_H, TOP = 8, 16, 2   # rows 0-1 cap accents, 2-8 main, 9-10 descenders, 11-15 spare
FIRST, LAST, COLS = 32, 383, 16

def pad_rows(rows, w):
    return [r.ljust(w, '.') for r in rows]

def compose(ch):
    if ch in G: return G[ch]
    d = unicodedata.decomposition(ch)
    if not d or d.startswith('<'): return None
    parts = d.split()
    base = chr(int(parts[0], 16)); marks = [int(p, 16) for p in parts[1:]]
    if base == 'i': base = 'ı'
    if base == 'j': base = 'j'
    bg = compose(base)
    if not bg: return None
    rows, desc = list(bg[0]), list(bg[1])
    top_extra = [] # cap accent rows (cell rows 0-1)
    w = max(len(r) for r in rows)
    for m in marks:
        if m not in ACCENTS: return None
        acc, where = ACCENTS[m]
        aw = len(acc[0])
        if w < aw:
            l = (aw - w) // 2
            rows = ['.' * l + r.ljust(w, '.') + '.' * (aw - w - l) for r in rows]
            desc = ['.' * l + r.ljust(w, '.') + '.' * (aw - w - l) for r in desc]
            w = aw
        off = (w - aw) // 2
        accp = ['.' * off + a + '.' * (w - aw - off) for a in acc]
        if where == 'bottom':
            desc = pad_rows(desc, w) if desc else ['.' * w, '.' * w]
            cut = acc
            if w < len(acc[0]):
                o = (len(acc[0]) - w) // 2; cut = [a[o:o + w] for a in acc]
            else:
                o = (w - len(acc[0])) // 2; cut = ['.' * o + a + '.' * (w - len(a) - o) for a in acc]
            desc = [''.join('#' if (p == '#' or q == '#') else '.' for p, q in zip(dr.ljust(w, '.'), cr)) for dr, cr in zip(desc, cut)]
        else:
            lower_free = all(c == '.' for c in rows[0]) and all(c == '.' for c in rows[1])
            if lower_free:
                rows = accp + rows[2:]
            else:
                top_extra = accp
    return (rows, desc, top_extra)

def glyph_rows(ch):
    r = compose(ch)
    if r is None: return None
    if len(r) == 2: return (r[0], r[1], [])
    return r

font = blank(COLS * CELL_W, ((LAST - FIRST + 1 + COLS - 1) // COLS) * CELL_H)
white = (255, 255, 255, 255)
missing = []
for cp in range(FIRST, LAST + 1):
    ch = chr(cp)
    idx = cp - FIRST
    cx, cy = (idx % COLS) * CELL_W, (idx // COLS) * CELL_H
    gr = glyph_rows(ch)
    if gr is None:
        if ch != ' ': missing.append(ch)
        continue
    rows, desc, top = gr
    stamp(font, top, cx, cy, white)
    stamp(font, rows, cx, cy + TOP, white)
    stamp(font, desc, cx, cy + TOP + 7, white)
save(font, "font")

def text_w(s):
    w = 0
    for ch in s:
        if ch == ' ': w += 4; continue
        gr = glyph_rows(ch)
        if gr: w += max(len(r) for r in gr[0]) + 1
    return max(0, w - 1)

def draw_text(img, s, x, y, col, scale=1):
    """y = top of main (cap) row"""
    for ch in s:
        if ch == ' ': x += 4 * scale; continue
        gr = glyph_rows(ch)
        if not gr: continue
        rows, desc, top = gr
        for j, r in enumerate(top + rows + desc):
            for i, c in enumerate(r):
                if c == '#':
                    yy = y + (j - len(top)) * scale
                    for a in range(scale):
                        for b in range(scale):
                            img.putpixel((x + i * scale + a, yy + b), col)
        x += (max(len(r) for r in rows) + 1) * scale

# ---------- LED digits (9x13, tinted at runtime) ----------
SEG = {  # 7 segments a..g
    '0': "abcdef", '1': "bc", '2': "abged", '3': "abgcd", '4': "fgbc", '5': "afgcd",
    '6': "afgedc", '7': "abc", '8': "abcdefg", '9': "abcdfg", '-': "g", ' ': "",
}
def seg_pixels(s):
    px = set()
    for x in range(2, 6):
        if 'a' in s: px.add((x, 1))
        if 'g' in s: px.add((x, 7))
        if 'd' in s: px.add((x, 13))
    for y in range(2, 7):
        if 'f' in s: px.add((1, y))
        if 'b' in s: px.add((6, y))
    for y in range(8, 13):
        if 'e' in s: px.add((1, y))
        if 'c' in s: px.add((6, y))
    return px
order = list("0123456789") + [':', '-', ' ']
digits = blank(8 * len(order), 16)
alls = seg_pixels("abcdefg")
for i, ch in enumerate(order):
    ox = i * 8
    if ch == ':':
        for (x, y) in [(3, 4), (4, 4), (3, 5), (4, 5), (3, 10), (4, 10), (3, 11), (4, 11)]:
            digits.putpixel((ox + x, y), white)
        continue
    on = seg_pixels(SEG[ch])
    for (x, y) in alls:
        digits.putpixel((ox + x, y), white if (x, y) in on else (255, 255, 255, 14))
save(digits, "digits")

# ---------- icons ----------
ICONS = {
 'prev':  ["#....#", "#...##", "#..###", "#.####", "#..###", "#...##", "#....#"],
 'play':  ["#....", "##...", "###..", "####.", "###..", "##...", "#...."],
 'pause': ["##.##", "##.##", "##.##", "##.##", "##.##", "##.##", "##.##"],
 'next':  ["#....#", "##...#", "###..#", "####.#", "###..#", "##...#", "#....#"],
 'disc':  ["..###..", ".#...#.", "#..#..#", "#.#.#.#", "#..#..#", ".#...#.", "..###.."],
 'queue': ["####...", ".......", "####...", ".....#.", "####.##", ".....#.", "......."],
 'mic':   [".###.", ".###.", ".###.", "#.#.#", "#...#", ".###.", "..#..", ".###."],
 'shuffle': ["......#.", "##...####", "..#.#..#.", "...#.....", "..#.#..#.", "##...####", "......#.."],
 'repeat':  ["..#.....", ".#######", "#.#....#", "#......#", "#....#.#", "#######.", ".....#.."],
 'repeat1': ["..#.....", ".#######", "#.#....#", "#...#..#", "#..##.#.", "####.##.", "....##.."],
 'menu':  ["#####", ".....", "#####", ".....", "#####"],
 'pin':   [".###.", ".###.", ".###.", "#####", "..#..", "..#.."],
 'min':   [".....", ".....", ".....", ".....", "#####"],
 'close': ["#...#", ".#.#.", "..#..", ".#.#.", "#...#"],
 'stop':  ["#####", "#####", "#####", "#####", "#####"],
 'buffer':[".....", ".....", ".....", "#.#.#", "....."],
 'note':  ["...####", "...#..#", "...#..#", "...#..#", ".###.##", "####.##", ".##...."],
}
def icon_size(n):
    r = ICONS[n]; return max(len(x) for x in r), len(r)

def button(w, h, icon, down=False, icon_col=None, face=None):
    im = blank(w, h); d = ImageDraw.Draw(im)
    e = 0 if min(w, h) <= 8 else 1   # tiny buttons: no outer edge, just the bevel
    if e: d.rectangle([0, 0, w - 1, h - 1], fill=C['edge'])
    if down: bevel(d, e, e, w - 2 * e, h - 2 * e, C['lo'], C['hi'], fill=face or C['btn_down'])
    else:    bevel(d, e, e, w - 2 * e, h - 2 * e, C['hi'], C['lo'], fill=face or C['btn'])
    if icon:
        iw, ih = icon_size(icon)
        x = (w - iw) // 2 + (1 if down else 0); y = (h - ih) // 2 + (1 if down else 0)
        stamp(im, ICONS[icon], x, y, icon_col or C['icon'])
    return im

for name in ['prev', 'play', 'pause', 'next']:
    save(button(24, 16, name), f"btn_{name}")
    save(button(24, 16, name, True), f"btn_{name}_down")
save(button(24, 16, 'disc'), "btn_panel")
save(button(24, 16, 'disc', True), "btn_panel_down")
save(button(24, 16, 'disc', icon_col=C['green']), "btn_panel_on")
for name in ['menu', 'pin', 'min', 'close']:
    save(button(8, 8, name), f"btn_{name}")
    save(button(8, 8, name, True), f"btn_{name}_down")
save(button(8, 8, 'pin', icon_col=C['green']), "btn_pin_on")
save(button(32, 16, 'shuffle', icon_col=C['icon_dim']), "btn_shuffle")
save(button(32, 16, 'shuffle', icon_col=C['green']), "btn_shuffle_on")
save(button(32, 16, 'shuffle', True, icon_col=C['icon_dim']), "btn_shuffle_down")
save(button(32, 16, 'repeat', icon_col=C['icon_dim']), "btn_repeat")
save(button(32, 16, 'repeat', icon_col=C['green']), "btn_repeat_on")
save(button(32, 16, 'repeat1', icon_col=C['green']), "btn_repeat_track")
save(button(32, 16, 'repeat', True, icon_col=C['icon_dim']), "btn_repeat_down")

def status(n, col):
    im = blank(8, 8); iw, ih = icon_size(n)
    src = ICONS[n]
    if n == 'play': src = ["#....", "##...", "###..", "####.", "###..", "##...", "#...."]; iw, ih = 5, 7
    stamp(im, src, (8 - iw) // 2, (8 - ih) // 2, col); return im
save(status('play', C['green']), "status_play")
save(status('pause', C['green']), "status_pause")
save(status('stop', C['green_dim']), "status_stop")
save(status('buffer', C['green']), "status_buffer")

def tab(icon, on):
    im = blank(16, 16); d = ImageDraw.Draw(im)
    d.rectangle([0, 0, 15, 15], fill=C['edge'])
    if on: bevel(d, 1, 1, 14, 14, C['lo'], C['hi'], fill=C['btn_down'])
    else:  bevel(d, 1, 1, 14, 14, C['hi'], C['lo'], fill=C['btn'])
    iw, ih = icon_size(icon)
    stamp(im, ICONS[icon], (16 - iw) // 2, (16 - ih) // 2, C['green'] if on else C['icon_dim'])
    return im
for n, ic in [('playlist', 'disc'), ('queue', 'queue'), ('lyrics', 'mic')]:
    save(tab(ic, False), f"tab_{n}")
    save(tab(ic, True), f"tab_{n}_on")

def track(w, h, fill):
    im = blank(w, h); d = ImageDraw.Draw(im)
    bevel(d, 0, 0, w, h, C['disp_edge'], C['hi'], fill=fill)
    return im
save(track(64, 8, C['disp']), "vol_track")
f = blank(64, 8); d = ImageDraw.Draw(f); d.rectangle([1, 3, 62, 4], fill=C['green_dim']); save(f, "vol_fill")
save(button(8, 8, None), "vol_thumb")
save(track(272, 8, C['disp']), "seek_track")
f = blank(272, 8); d = ImageDraw.Draw(f); d.rectangle([1, 3, 270, 4], fill=C['green']); save(f, "seek_fill")
save(button(16, 8, None), "seek_thumb")
save(button(8, 16, None), "scroll_thumb")

vis = blank(8, 24)
top, bot = hexc("#E9F27A"), hexc("#2E9C5C")
for y in range(24):
    t = y / 23
    for vx in range(8): vis.putpixel((vx, y), tuple(int(top[i] * (1 - t) + bot[i] * t) for i in range(3)) + (255,))
save(vis, "vis_colors")

art = blank(48, 48, C['disp']); d = ImageDraw.Draw(art)
d.rectangle([0, 0, 47, 47], outline=C['rule'])
note = ICONS['note']
for j, r in enumerate(note):
    for i, c in enumerate(r):
        if c == '#': d.rectangle([13 + i * 3, 13 + j * 3, 15 + i * 3, 15 + j * 3], fill=C['green_dim'])
save(art, "art_empty")

logo = blank(64, 16)
lw = text_w("SPOX") * 2
draw_text(logo, "SPOX", (64 - lw) // 2, 1, C['green'], scale=2)
save(logo, "logo")

# ---------- backgrounds ----------
MW, MH, PW, PH = 288, 112, 288, 128
def frame(w, h):
    im = blank(w, h); d = ImageDraw.Draw(im)
    d.rectangle([0, 0, w - 1, h - 1], fill=C['edge'])
    bevel(d, 1, 1, w - 2, h - 2, C['hi'], C['lo'], fill=C['face'])
    return im, d
def recess(d, x, y, w, h):
    bevel(d, x, y, w, h, C['disp_edge'], C['hi'], fill=C['disp'])

main, d = frame(MW, MH)
d.rectangle([2, 2, MW - 3, 15], fill=C['title'])
tw = text_w("SPOX"); tx = (MW - tw) // 2
for yy, col in [(7, C['rule']), (10, C['rule2'])]:
    d.line([20, yy, tx - 8, yy], fill=col); d.line([tx + tw + 8, yy, 244, yy], fill=col)
draw_text(main, "SPOX", tx, 5, hexc("#9A9DA4"))
recess(d, 8, 20, 128, 56)
recess(d, 140, 20, 140, 32)
save(main, "main_bg")

panel, d = frame(PW, PH)
recess(d, 8, 24, 264, 96)
recess(d, 276, 24, 8, 96)
save(panel, "panel_bg")

# ---------- layout (skin.json) ----------
layout = {
  "_readme": "Positions are in 1x pixels. Each element's size comes from its PNG, so draw bigger PNGs if you need room. Colors are #RRGGBB. Press F5 in SpoX to reload the skin.",
  "main_size": [MW, MH],
  "panel_size": [PW, PH],
  "pos": {
    "btn_menu": [4, 4], "btn_pin": [252, 4], "btn_min": [264, 4], "btn_close": [276, 4],
    "status": [64, 28], "minus": [72, 24], "timer": [80, 24],
    "vol": [140, 60], "btn_shuffle": [212, 56], "btn_repeat": [248, 56],
    "seek": [8, 80],
    "btn_prev": [8, 92], "btn_play": [32, 92], "btn_pause": [56, 92], "btn_next": [80, 92],
    "btn_panel": [112, 92], "logo": [216, 92],
    "tab_playlist": [8, 4], "tab_queue": [28, 4], "tab_lyrics": [48, 4]
  },
  "rect": {
    "drag": [0, 0, 288, 16],
    "art": [12, 24, 48, 48],
    "vis": [64, 48, 64, 24],
    "title": [144, 24, 132, 10],
    "info": [144, 37, 132, 10],
    "header": [72, 8, 208, 10],
    "list": [11, 27, 258, 90],
    "scroll": [276, 24, 8, 96]
  },
  "colors": {
    "title": "#5BE38E", "info": "#3E9A65", "digits": "#5BE38E",
    "list": "#9FD9B4", "list_dim": "#4F7A60", "list_current": "#5BE38E",
    "list_selected_bg": "#1C3527", "header": "#C3C6CC",
    "lyric": "#4F7A60", "lyric_current": "#E9F27A",
    "vis_peak": "#C9D6CF", "menu_bg": "#1B1C1F", "menu_text": "#C3C6CC", "menu_hover": "#1C3527",
    "input_bg": "#050506", "input_text": "#5BE38E"
  },
  "font": {"cell": [CELL_W, CELL_H], "first": FIRST, "cols": COLS, "top_rows": TOP, "space": 4, "spacing": 1, "line_height": 10},
  "digits": {"width": 8, "order": "0123456789:- "},
  "vis": {"bars": 16, "bar_width": 3, "gap": 1},
  "art_pixels": 24,
  "uppercase_display": False,
  "timer_blink_when_paused": True
}
with open(os.path.join(OUT, "skin.json"), "w") as fh:
    json.dump(layout, fh, indent=2)

# ---------- guides ----------
S = 4
def guide(bg_name, size, items, fname):
    bg = Image.open(os.path.join(OUT, bg_name + ".png")).resize((size[0] * S, size[1] * S), Image.NEAREST)
    for name, (x, y, w, h) in items:
        try:
            el = Image.open(os.path.join(OUT, name + ".png")).resize((w * S, h * S), Image.NEAREST)
            bg.alpha_composite(el, (x * S, y * S))
        except FileNotFoundError:
            pass
    d = ImageDraw.Draw(bg)
    fnt = ImageFont.load_default()
    for name, (x, y, w, h) in items:
        d.rectangle([x * S, y * S, (x + w) * S - 1, (y + h) * S - 1], outline=(255, 0, 255, 255))
        d.text((x * S + 2, y * S + 1), name, fill=(255, 0, 255, 255), font=fnt)
    bg.save(os.path.join(OUT, "_guides", fname))

def sz(n):
    return Image.open(os.path.join(OUT, n + ".png")).size
pos, rect = layout["pos"], layout["rect"]
main_items = []
for n in ["btn_menu", "btn_pin", "btn_min", "btn_close", "btn_shuffle", "btn_repeat", "btn_prev", "btn_play", "btn_pause", "btn_next", "btn_panel", "logo"]:
    main_items.append((n, (*pos[n], *sz(n))))
main_items += [("vol_track", (*pos["vol"], *sz("vol_track"))), ("seek_track", (*pos["seek"], *sz("seek_track"))),
               ("status_play", (*pos["status"], 8, 8)), ("art_empty", tuple(rect["art"])),
               ("timer (digits)", (*pos["timer"], 40, 16)), ("vis", tuple(rect["vis"])),
               ("title text", tuple(rect["title"])), ("info text", tuple(rect["info"]))]
guide("main_bg", (MW, MH), main_items, "layout_main.png")
panel_items = [(n, (*pos[n], 16, 16)) for n in ["tab_playlist", "tab_queue", "tab_lyrics"]]
panel_items += [("header text", tuple(rect["header"])), ("list", tuple(rect["list"])), ("scroll", tuple(rect["scroll"]))]
guide("panel_bg", (PW, PH), panel_items, "layout_panel.png")

# font guide: sheet at 6x with cell grid, baseline in red
fg = Image.new("RGBA", font.size, (11, 12, 13, 255)); fg.alpha_composite(font)
fg = fg.resize((font.size[0] * 6, font.size[1] * 6), Image.NEAREST)
d = ImageDraw.Draw(fg)
for cx in range(0, font.size[0] + 1, CELL_W): d.line([cx * 6, 0, cx * 6, fg.size[1]], fill=(80, 80, 90, 255))
for cy in range(0, font.size[1] + 1, CELL_H):
    d.line([0, cy * 6, fg.size[0], cy * 6], fill=(80, 80, 90, 255))
    if cy < font.size[1]:
        d.line([0, (cy + TOP + 7) * 6, fg.size[0], (cy + TOP + 7) * 6], fill=(170, 40, 40, 255))
fg.save(os.path.join(OUT, "_guides", "font_grid.png"))

print("missing glyphs:", ''.join(missing))

# ---------- window icon (32x32) ----------
ico = blank(32, 32)
disc = ["....######....", "..##......##..", ".#..........#.", ".#....##....#.", "#....#..#....#", "#...#....#...#",
        "#...#....#...#", "#....#..#....#", ".#....##....#.", ".#..........#.", "..##......##..", "....######...."]
d = ImageDraw.Draw(ico)
d.ellipse([1, 1, 30, 30], fill=C['face'], outline=C['edge'])
d.ellipse([5, 5, 26, 26], outline=C['green'])
d.ellipse([12, 12, 19, 19], fill=C['green'])
d.ellipse([14, 14, 17, 17], fill=C['edge'])
save(ico, "icon")

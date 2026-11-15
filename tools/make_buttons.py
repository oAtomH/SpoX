"""Redraws SpoX's extra buttons in Alessio's button style.

Base bevel comes from his own btn_pause / btn_pause_down (icon wiped), the icon colours are
sampled from his icons (grey up, green pressed), icons use his 2 px strokes, and pressed icons
drop 1 px. Only touches buttons that SpoX drew (never his own art). Run: python tools/make_buttons.py
"""
from PIL import Image
import os, sys

SK = os.path.join(os.path.dirname(__file__), '..', 'skin')

def load(n):
    return Image.open(os.path.join(SK, n + '.png')).convert('RGBA')

up, dn = load('btn_pause'), load('btn_pause_down')
ICON_UP = up.getpixel((8, 4))
ICON_DN = dn.getpixel((8, 5))
FILL_UP = up.getpixel((3, 3))
FILL_DN = dn.getpixel((3, 3))
BEV_HI, BEV_LO = up.getpixel((1, 1)), up.getpixel((22, 14))
EDGE = up.getpixel((0, 0))

def wipe(im, icon, fill):
    im = im.copy()
    for y in range(im.height):
        for x in range(im.width):
            if im.getpixel((x, y)) == icon:
                im.putpixel((x, y), fill)
    return im

BASE_UP, BASE_DN = wipe(up, ICON_UP, FILL_UP), wipe(dn, ICON_DN, FILL_DN)

def small_base(pressed):
    """16x16 version of the same bevel for the taskbar player."""
    im = Image.new('RGBA', (16, 16), EDGE)
    hi, lo = (BEV_LO, BEV_HI) if pressed else (BEV_HI, BEV_LO)
    fill = FILL_DN if pressed else FILL_UP
    for y in range(1, 15):
        for x in range(1, 15):
            im.putpixel((x, y), fill)
    for x in range(1, 15):
        im.putpixel((x, 1), hi)
        im.putpixel((x, 14), lo)
    for y in range(1, 15):
        im.putpixel((1, y), hi)
        im.putpixel((14, y), lo)
    im.putpixel((14, 1), lo)
    im.putpixel((1, 14), lo)
    return im

# icons: '#' = icon pixel. Kept to 9 rows like his.
ICONS = {
    'like': [
        ".##...##.",
        "####.####",
        "#########",
        "#########",
        ".#######.",
        "..#####..",
        "...###...",
        "....#....",
    ],
    'add': [
        "...##...",
        "...##...",
        "...##...",
        "########",
        "########",
        "...##...",
        "...##...",
        "...##...",
    ],
    'link': [
        ".....###",
        "....##.#",
        "...##.##",
        "..#.##..",
        "..##.#..",
        "##.##...",
        "#.##....",
        "###.....",
    ],
    'minus': [
        "........",
        "........",
        "........",
        "########",
        "########",
        "........",
        "........",
        "........",
    ],
    'shuffle': [
        "........#.",
        "##.....###",
        "..#...#.#.",
        "...#.#....",
        "....#.....",
        "...#.#....",
        "..#...#.#.",
        "##.....###",
        "........#.",
    ],
    'mini': [
        "##########",
        "#........#",
        "#........#",
        "#........#",
        "##########",
        "..........",
        "##.#######",
        "##.#######",
    ],
    'smart': [
        "##....###.",
        "..#..#..##",
        "...##.....",
        "...##..#..",
        "..#..####.",
        "##....###.",
        ".......#..",
    ],
    'prev': [
        "##...#",
        "##..##",
        "##.###",
        "######",
        "##.###",
        "##..##",
        "##...#",
    ],
    'play': [
        "#...",
        "##..",
        "###.",
        "####",
        "###.",
        "##..",
        "#...",
    ],
    'pause': [
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
        "##.##",
    ],
    'next': [
        "#...##",
        "##..##",
        "###.##",
        "######",
        "###.##",
        "##..##",
        "#...##",
    ],
}

def stamp(base, icon, col, top, area_w, x0=0):
    im = base.copy()
    w = max(len(r) for r in icon)
    h = len(icon)
    ox = x0 + (area_w - w) // 2
    oy = top
    for y, r in enumerate(icon):
        for x, c in enumerate(r):
            if c == '#':
                im.putpixel((ox + x, oy + y), col)
    return im

def big(name, out, rows_top=3):
    ic = ICONS[name]
    top = rows_top + (9 - len(ic)) // 2
    stamp(BASE_UP, ic, ICON_UP, top, 20, 2).save(os.path.join(SK, out + '.png'))
    stamp(BASE_DN, ic, ICON_DN, top + 1, 20, 2).save(os.path.join(SK, out + '_down.png'))

def small(name, out):
    ic = ICONS[name]
    top = 2 + (11 - len(ic)) // 2
    stamp(small_base(False), ic, ICON_UP, top, 12, 2).save(os.path.join(SK, out + '.png'))
    stamp(small_base(True), ic, ICON_DN, top + 1, 12, 2).save(os.path.join(SK, out + '_down.png'))

if __name__ == '__main__':
    for n in ['like', 'add', 'link', 'mini', 'smart']:
        big(n, 'btn_' + n)
    # the "smart" state of the shuffle button is the pressed look
    # shuffle in "SpoX smart" mode = pressed shuffle + a little bright spark in the corner
    sm = load('btn_shuffle_down')
    spark = (91, 227, 142, 255)
    for (x, y) in [(4, 3), (3, 4), (4, 4), (5, 4), (4, 5)]:
        sm.putpixel((x, y), spark)
    sm.save(os.path.join(SK, 'btn_shuffle_smart.png'))
    for n in ['prev', 'play', 'pause', 'next', 'shuffle']:
        small(n, 'tb_' + n)
    # + turns into - when the song is already in the playlist you're playing (pressed look)
    ic = ICONS['minus']
    for out in ['btn_remove', 'btn_remove_down']:
        stamp(BASE_DN, ic, ICON_DN, 3 + (9 - len(ic)) // 2 + 1, 20, 2).save(os.path.join(SK, out + '.png'))
    # taskbar shuffle: on = pressed, smart = pressed + spark
    on = Image.open(os.path.join(SK, 'tb_shuffle_down.png')).convert('RGBA')
    on.save(os.path.join(SK, 'tb_shuffle_on.png'))
    sm = on.copy()
    for (x, y) in [(3, 2), (2, 3), (3, 3), (4, 3), (3, 4)]:
        sm.putpixel((x, y), (91, 227, 142, 255))
    sm.save(os.path.join(SK, 'tb_shuffle_smart.png'))
    print('done')

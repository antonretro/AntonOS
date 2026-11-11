#!/usr/bin/env python3
"""Makes the art shared by every built-in game, plus Totem Tide's logo and scene (all original).

    python launcher/games/make_shared_assets.py

shared/font.png   5x7 pixel font: one row of 6x8 cells, characters in CHARS order (white on transparent)
shared/icons.png  8x8 icons in a row: score, best, speed, chain, danger
tiki/logo.png     the Totem Tide logo
tiki/scene.png    320x180 night island scene behind the board (the sea is animated in code)
Layouts are documented in launcher/games/ASSETS.md.
"""
import os, random
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'games')
os.makedirs(os.path.join(ASSETS, 'shared'), exist_ok=True)
os.makedirs(os.path.join(ASSETS, 'tiki'), exist_ok=True)

# ------------------------------------------------------------------ palette (one for all games)
INK = (43, 20, 8); BONE = (244, 234, 213)
WOOD = (120, 70, 34); WOOD_L = (168, 108, 58); WOOD_D = (72, 38, 16)
GOLD = (255, 204, 92); TEAL = (40, 156, 170); TEAL_D = (18, 70, 84)
NIGHT0 = (10, 18, 36); NIGHT1 = (22, 40, 66); NIGHT2 = (36, 62, 84)

CHARS = " ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,!?:-+/x'%()<>^*"
G = {
 'A': [".###.","#...#","#...#","#####","#...#","#...#","#...#"],
 'B': ["####.","#...#","#...#","####.","#...#","#...#","####."],
 'C': [".###.","#...#","#....","#....","#....","#...#",".###."],
 'D': ["####.","#...#","#...#","#...#","#...#","#...#","####."],
 'E': ["#####","#....","#....","####.","#....","#....","#####"],
 'F': ["#####","#....","#....","####.","#....","#....","#...."],
 'G': [".###.","#...#","#....","#.###","#...#","#...#",".####"],
 'H': ["#...#","#...#","#...#","#####","#...#","#...#","#...#"],
 'I': [".###.","..#..","..#..","..#..","..#..","..#..",".###."],
 'J': ["..###","...#.","...#.","...#.","#..#.","#..#.",".##.."],
 'K': ["#...#","#..#.","#.#..","##...","#.#..","#..#.","#...#"],
 'L': ["#....","#....","#....","#....","#....","#....","#####"],
 'M': ["#...#","##.##","#.#.#","#.#.#","#...#","#...#","#...#"],
 'N': ["#...#","#...#","##..#","#.#.#","#..##","#...#","#...#"],
 'O': [".###.","#...#","#...#","#...#","#...#","#...#",".###."],
 'P': ["####.","#...#","#...#","####.","#....","#....","#...."],
 'Q': [".###.","#...#","#...#","#...#","#.#.#","#..#.",".##.#"],
 'R': ["####.","#...#","#...#","####.","#.#..","#..#.","#...#"],
 'S': [".####","#....","#....",".###.","....#","....#","####."],
 'T': ["#####","..#..","..#..","..#..","..#..","..#..","..#.."],
 'U': ["#...#","#...#","#...#","#...#","#...#","#...#",".###."],
 'V': ["#...#","#...#","#...#","#...#","#...#",".#.#.","..#.."],
 'W': ["#...#","#...#","#...#","#.#.#","#.#.#","#.#.#",".#.#."],
 'X': ["#...#","#...#",".#.#.","..#..",".#.#.","#...#","#...#"],
 'Y': ["#...#","#...#",".#.#.","..#..","..#..","..#..","..#.."],
 'Z': ["#####","....#","...#.","..#..",".#...","#....","#####"],
 '0': [".###.","#...#","#..##","#.#.#","##..#","#...#",".###."],
 '1': ["..#..",".##..","..#..","..#..","..#..","..#..",".###."],
 '2': [".###.","#...#","....#","...#.","..#..",".#...","#####"],
 '3': ["#####","...#.","..#..","...#.","....#","#...#",".###."],
 '4': ["...#.","..##.",".#.#.","#..#.","#####","...#.","...#."],
 '5': ["#####","#....","####.","....#","....#","#...#",".###."],
 '6': ["..##.",".#...","#....","####.","#...#","#...#",".###."],
 '7': ["#####","....#","...#.","..#..",".#...",".#...",".#..."],
 '8': [".###.","#...#","#...#",".###.","#...#","#...#",".###."],
 '9': [".###.","#...#","#...#",".####","....#","...#.",".##.."],
 ' ': ["....."] * 7,
 '.': [".....",".....",".....",".....",".....",".##..",".##.."],
 ',': [".....",".....",".....",".....",".##..","..#..",".#..."],
 '!': ["..#..","..#..","..#..","..#..","..#..",".....","..#.."],
 '?': [".###.","#...#","....#","...#.","..#..",".....","..#.."],
 ':': [".....",".##..",".##..",".....",".##..",".##..","....."],
 '-': [".....",".....",".....","#####",".....",".....","....."],
 '+': [".....","..#..","..#..","#####","..#..","..#..","....."],
 '/': ["....#","...#.","...#.","..#..",".#...",".#...","#...."],
 'x': [".....",".....","#...#",".#.#.","..#..",".#.#.","#...#"],
 "'": ["..#..","..#..",".#...",".....",".....",".....","....."],
 '%': ["##..#","##..#","...#.","..#..",".#...","#..##","#..##"],
 '(': ["...#.","..#..",".#...",".#...",".#...","..#..","...#."],
 ')': [".#...","..#..","...#.","...#.","...#.","..#..",".#..."],
 '<': ["....#","...#.","..#..",".#...","..#..","...#.","....#"],
 '>': ["#....",".#...","..#..","...#.","..#..",".#...","#...."],
 '^': ["..#..",".#.#.","#...#",".....",".....",".....","....."],
 '*': [".....","..#..","#.#.#",".###.","#.#.#","..#..","....."],
}
assert all(ch in G for ch in CHARS), [ch for ch in CHARS if ch not in G]

font = Image.new('RGBA', (6 * len(CHARS), 8), (0, 0, 0, 0)); fp = font.load()
for i, ch in enumerate(CHARS):
    for y, row in enumerate(G[ch]):
        for x, v in enumerate(row):
            if v == '#': fp[i * 6 + x, y] = (255, 255, 255, 255)
font.save(os.path.join(ASSETS, 'shared', 'font.png'))
open(os.path.join(ASSETS, 'shared', 'font.txt'), 'w').write(CHARS)

# ------------------------------------------------------------------ icons (8x8 each): score shell, best crown, speed wave, chain links, danger
ICONS = [
 ["..####..",".#oooo#.","#oo#oo#.","#o#o#oo#","#oo#o#o#",".#ooo#o#","..####o.","........"],   # shell
 ["........","#..#..#.","##.#.##.","#######.","#oooooo#","#######.","........","........"],   # crown
 ["........","...##...","..#oo#..",".#oo#o#.","#oo#..#o","o#....oo","........","........"],   # wave
 ["........",".###....","#o..#...","#o.###..",".###o.#.","...#.o#.","....###.","........"],   # chain
 ["...#....","..#o#...","..#o#...",".#ooo#..",".#o#o#..","#ooooo#.","#######.","........"],   # danger
]
ICOL = [(BONE, (236, 112, 150)), (INK, GOLD), (INK, (110, 214, 214)), (INK, (200, 200, 210)), (INK, (255, 120, 90))]
icons = Image.new('RGBA', (8 * len(ICONS), 8), (0, 0, 0, 0)); ip = icons.load()
for i, ic in enumerate(ICONS):
    ink, fill = ICOL[i]
    for y, row in enumerate(ic):
        for x, v in enumerate(row):
            if v == '#': ip[i * 8 + x, y] = ink + (255,) if i else (INK + (255,))
            elif v == 'o': ip[i * 8 + x, y] = fill + (255,)
icons.save(os.path.join(ASSETS, 'shared', 'icons.png'))

# ------------------------------------------------------------------ Totem Tide logo: carved letters, 3x font, wood light-to-dark, ink outline, wave underline
def logo(text_rows):
    """Carved letters at 3x the font: TOTEM in wood with gold tops, TIDE in sea teal; ink outline, drop shadow, a wave underneath."""
    S = 3
    PALS = [(GOLD, WOOD_L, WOOD, WOOD_D), ((200, 248, 244), (110, 214, 214), TEAL, TEAL_D)]
    w = max(len(r) for r in text_rows) * 6 * S + 4; h = len(text_rows) * (8 * S + 3) + 12
    im = Image.new('RGBA', (w, h), (0, 0, 0, 0)); px = im.load()
    def put(x, y, c):
        if 0 <= x < w and 0 <= y < h: px[x, y] = c + (255,)
    rows = []
    for ri, row in enumerate(text_rows):
        f = set(); ox = (w - len(row) * 6 * S) // 2; oy = 2 + ri * (8 * S + 3)
        for ci, ch in enumerate(row):
            for y, r in enumerate(G[ch]):
                for x, v in enumerate(r):
                    if v == '#':
                        for dy in range(S):
                            for dx in range(S): f.add((ox + ci * 6 * S + x * S + dx, oy + y * S + dy))
        rows.append(f)
    allf = set().union(*rows)
    for (x, y) in allf: put(x + 1, y + 2, (8, 10, 20))
    for (x, y) in allf:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            if (x + dx, y + dy) not in allf: put(x + dx, y + dy, INK)
    for ri, f in enumerate(rows):
        top, light, body, dark = PALS[ri % len(PALS)]
        ys = [y for _, y in f]; y0, y1 = min(ys), max(ys)
        for (x, y) in f:
            k = (y - y0) / max(1, y1 - y0)
            c = top if k < .2 else light if k < .5 else body
            if ri == 0 and (x + y) % 7 == 0 and k > .3: c = dark          # wood grain
            if ri == 1 and (x // 3 + y) % 9 == 0 and k > .35: c = top     # glints on the water
            put(x, y, c)
    for x in range(3, w - 3):
        y = h - 7 + (1 if (x // 4) % 2 else 0)
        put(x, y - 1, (200, 248, 244)); put(x, y, TEAL); put(x, y + 1, TEAL); put(x, y + 2, TEAL_D)
    return im
logo(["TOTEM", "TIDE"]).save(os.path.join(ASSETS, 'tiki', 'logo.png'))

# ------------------------------------------------------------------ scene: night sky, moon, stars, islands with palms and totem silhouettes (sea animated in code)
W, H = 320, 180
sc = Image.new('RGB', (W, H)); sp = sc.load()
HORIZON = 132
for y in range(H):
    k = y / HORIZON
    for x in range(W):
        # banded sky with an ordered dither between bands (no smooth gradients: it's pixel art)
        band = min(2, int(k * 3)); f = k * 3 - band
        a, b = [(NIGHT0, NIGHT1), (NIGHT1, NIGHT2), (NIGHT2, NIGHT2)][band]
        bayer = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]][y % 4][x % 4] / 16
        sp[x, y] = b if f > bayer else a
r = random.Random(11)
for _ in range(70):
    x, y = r.randrange(W), r.randrange(HORIZON - 30)
    sp[x, y] = (200, 214, 230) if r.random() < .7 else (255, 236, 180)
# moon (upper right, behind the HUD side), with craters
mx, my, mr = 262, 30, 11
for y in range(my - mr, my + mr + 1):
    for x in range(mx - mr, mx + mr + 1):
        d = ((x - mx) ** 2 + (y - my) ** 2) ** .5
        if d <= mr: sp[x, y] = (236, 230, 200) if d < mr - 1 else (196, 190, 168)
for (cx, cy) in [(258, 26), (266, 34), (261, 36)]: sp[cx, cy] = (196, 190, 168); sp[cx + 1, cy] = (196, 190, 168)
# islands (dark silhouettes) with palms and little totems
SIL = (8, 16, 26); SIL2 = (12, 24, 36)
def island(x0, x1, top, col):
    for x in range(x0, x1):
        t = (x - x0) / (x1 - x0); hgt = int(top * (1 - (2 * t - 1) ** 2))
        if 0 <= x < W:
            for y in range(HORIZON - hgt, HORIZON): sp[x, y] = col
island(-10, 90, 16, SIL2); island(230, 340, 20, SIL2); island(20, 70, 9, SIL); island(250, 300, 12, SIL)
def palm(x, base, h, col):
    for y in range(base - h, base): sp[x + (1 if y < base - h * .6 else 0), y] = col
    tx, ty = x + 1, base - h
    for dx, dy in [(-4, 1), (-3, 0), (-2, 0), (-1, 0), (2, 0), (3, 0), (4, 1), (5, 2), (-5, 2), (0, -1), (1, -1)]: sp[tx + dx, ty + dy] = col
palm(30, HORIZON - 9, 16, SIL); palm(58, HORIZON - 6, 12, SIL); palm(272, HORIZON - 12, 18, SIL); palm(296, HORIZON - 8, 13, SIL)
def totem(x, base, h, col):
    for y in range(base - h, base):
        for dx in range(5): sp[x + dx, y] = col
    for k in range(base - h + 2, base, 5): sp[x + 1, k] = NIGHT1; sp[x + 3, k] = NIGHT1      # carved eyes, faintly lit
    for dx in range(-1, 6): sp[x + dx, base - h] = col
totem(8, HORIZON - 4, 22, SIL); totem(304, HORIZON - 6, 26, SIL); totem(42, HORIZON - 7, 14, SIL)
# the sea (static base; the waves move in code)
for y in range(HORIZON, H):
    k = (y - HORIZON) / (H - HORIZON)
    for x in range(W): sp[x, y] = (10, 34 + int(14 * k), 52 + int(10 * k))
sc.save(os.path.join(ASSETS, 'tiki', 'scene.png'))
# big previews for checking by eye (not shipped)
sc.resize((W * 3, H * 3), Image.NEAREST).save(os.path.join(HERE, 'scene-preview.png'))
l = Image.open(os.path.join(ASSETS, 'tiki', 'logo.png')); l.resize((l.width * 4, l.height * 4), Image.NEAREST).save(os.path.join(HERE, 'logo-preview.png'))
print('ok')

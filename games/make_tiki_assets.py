#!/usr/bin/env python3
"""Makes Totem Tide's pixel art and sounds (all original) into the app's assets.

    python launcher/games/make_tiki_assets.py

Art: one sheet, tiles.png, 6 columns x 3 rows of 14x14 px tiles (layout in launcher/games/ASSETS.md).
Sounds: short synthesised mono WAVs (wood knocks and marimba-like tones), 22050 Hz, 16-bit.
"""
import math, os, random, struct, wave
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'games', 'tiki')
os.makedirs(OUT, exist_ok=True)
T = 14

# ------------------------------------------------------------------ palette (shared by all built-in games)
INK = (43, 20, 8)          # outline and carved lines
BONE = (244, 234, 213)     # eye whites, teeth
# per mask: body, light, dark, accent
MASKS = [
    ('ember', (214, 72, 52), (240, 128, 92), (140, 38, 30), (255, 214, 102)),
    ('sun',   (232, 170, 40), (252, 216, 110), (160, 104, 22), (255, 246, 196)),
    ('fern',  (88, 166, 72), (148, 210, 112), (46, 104, 44), (220, 244, 140)),
    ('wave',  (40, 156, 170), (110, 214, 214), (24, 92, 112), (200, 248, 244)),
    ('moon',  (130, 92, 196), (180, 150, 236), (78, 52, 132), (250, 236, 150)),
    ('shell', (236, 112, 150), (252, 170, 196), (160, 62, 98), (255, 230, 236)),
]

BASE = [
    "...########...",
    "..#LLLLLLLL#..",
    ".#LLLLLLLLLL#.",
    ".#BBBBBBBBBB#.",
    ".#BBBBBBBBBB#.",
    ".#BBBBBBBBBB#.",
    ".#BBBBBBBBBB#.",
    ".#BBBBBBBBBB#.",
    ".#BBBBBBBBBB#.",
    ".#BBBBBBBBBB#.",
    ".#DBBBBBBBBD#.",
    "..#DDDDDDDD#..",
    "...########...",
    "..............",
]

# faces: rows 3..10 inside the body. Letters: k = ink, w = bone, a = accent, d = dark, . = keep
FACES = {
    'ember': [  # round eyes, wide toothy grin
        "..............",
        "..............",
        "..............",
        "...kkkk.kkkk..",  # wait: overwritten below per-row index 3
    ],
}

def face_rows(name):
    # each list is 14 chars wide, for rows 3..10 of the tile
    f = {
        'ember': ["..kkk..kkk....",
                  "..kwk..kwk....",
                  "..kkk..kkk....",
                  "......d.......",
                  "..kkkkkkkk....",
                  "..kwkwkwkk....",
                  "...kkkkkk.....",
                  ".............."],
        'sun':   [".kkkk..kkkk...",
                  "..kk....kk....",
                  "..............",
                  "......dd......",
                  ".....kkkk.....",
                  ".....k..k.....",
                  ".....kkkk.....",
                  ".............."],
        'fern':  ["..k......k....",
                  "..kk....kk....",
                  "..kkk..kkk....",
                  "......d.......",
                  "..k.k.k.k.k...",
                  "...k.k.k.k....",
                  "..............",
                  ".............."],
        'wave':  ["..aaaaaaaaa...",
                  "..............",
                  "..kkk..kkk....",
                  "......d.......",
                  "..............",
                  "...k.....k....",
                  "....kkkkk.....",
                  ".............."],
        'moon':  ["......a.......",
                  "..kkk..kkk....",
                  "..kwk..kwk....",
                  "..kwk..kwk....",
                  "..kkk..kkk....",
                  "..............",
                  "...kkkkkkk....",
                  ".............."],
        'shell': ["..............",
                  "..kk....kk....",
                  "..kk....kk....",
                  ".a....d....a..",
                  "....kkkkk.....",
                  "....kaaak.....",
                  ".....kak......",
                  ".............."],
    }[name]
    # the faces are drawn left-heavy in the strings above; centre them inside the 10-px body (cols 2..11)
    out = []
    for r in f:
        r = r.rstrip('.')
        r = r.lstrip('.')
        width = len(r)
        pad = (14 - width) // 2
        out.append(('.' * pad + r).ljust(14, '.'))
    return out

def crest(name):
    """A little carved top ornament per mask (row 0..1), part of what tells them apart without colour."""
    return {
        'ember': [(5, 0), (8, 0)],
        'sun':   [(3, 0), (6, 0), (7, 0), (10, 0)],
        'fern':  [(6, 0), (7, 0), (7, 1)],
        'wave':  [(4, 0), (9, 0)],
        'moon':  [(5, 0), (6, 0)],
        'shell': [(6, 0), (7, 0), (8, 0)],
    }[name]

def mask_tile(m, mode):
    name, body, light, dark, acc = m
    def tone(c):
        if mode == 'flash': return tuple(min(255, int(v * .45 + 255 * .55)) for v in c)
        if mode == 'dim': return tuple(int(v * .45) for v in c)
        return c
    im = Image.new('RGBA', (T, T), (0, 0, 0, 0)); px = im.load()
    cols = {'#': INK, 'B': body, 'L': light, 'D': dark}
    for y, row in enumerate(BASE):
        for x, ch in enumerate(row):
            if ch in cols: px[x, y] = tone(cols[ch]) + (255,)
    for y, row in enumerate(face_rows(name)):
        for x, ch in enumerate(row):
            c = {'k': INK, 'w': BONE, 'a': acc, 'd': dark}.get(ch)
            if c and px[x, y + 3][3] > 0: px[x, y + 3] = tone(c) + (255,)
    for (x, y) in crest(name):
        px[x, y] = tone(acc) + (255,)
    return im

sheet = Image.new('RGBA', (T * 6, T * 3), (0, 0, 0, 0))
for i, m in enumerate(MASKS):
    for r, mode in enumerate(['normal', 'flash', 'dim']):
        sheet.paste(mask_tile(m, mode), (i * T, r * T))
sheet.save(os.path.join(OUT, 'tiles.png'))
# a big preview for checking by eye (not shipped)
sheet.resize((sheet.width * 8, sheet.height * 8), Image.NEAREST).save(os.path.join(HERE, 'tiki-preview.png'))

# ------------------------------------------------------------------ sounds
SR = 22050
def save(name, samples):
    with wave.open(os.path.join(OUT, name + '.wav'), 'w') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes(b''.join(struct.pack('<h', int(max(-1, min(1, s)) * 26000)) for s in samples))

def marimba(f, dur, vol=.5):
    n = int(SR * dur)
    return [vol * math.exp(-t / SR * 9) * (math.sin(2 * math.pi * f * t / SR) + .35 * math.sin(2 * math.pi * f * 4 * t / SR) * math.exp(-t / SR * 30)) for t in range(n)]

def knock(f, dur=.07, vol=.45):
    random.seed(f)
    n = int(SR * dur)
    return [vol * math.exp(-t / SR * 60) * (.6 * math.sin(2 * math.pi * f * t / SR) + .4 * (random.random() * 2 - 1)) for t in range(n)]

def seq(parts, gap=.0):
    out = []
    for p in parts:
        out += p + [0.0] * int(SR * gap)
    return out

def mix(a, b):
    n = max(len(a), len(b)); return [(a[i] if i < len(a) else 0) + (b[i] if i < len(b) else 0) for i in range(n)]

save('swap', knock(420))
save('clear', seq([marimba(523, .09), marimba(659, .09), marimba(784, .22)]))
save('chain', seq([marimba(659, .08), marimba(784, .08), marimba(988, .08), marimba(1319, .3)]))
save('raise', knock(160, .12, .5))
save('land', knock(260, .05, .3))
save('danger', seq([marimba(330, .12, .35), marimba(311, .2, .35)]))
save('over', seq([marimba(392, .18), marimba(330, .18), marimba(262, .45)]))
print('ok', os.path.abspath(OUT))

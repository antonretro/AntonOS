#!/usr/bin/env python3
"""Gear & Ghost's sounds and music, synthesised here (original; the pictures are drawn in code, see GearArt.kt).

    python games/make_gear_assets.py

8-bit mono WAV at 11025 Hz. Music: a mysterious workshop tune in A minor (title, calm) and a busier ticking
loop for play. Sounds: turn, gear, place, power, portal, fail, bonk, step, and the shell's menu swap/land.
"""
import math, os, random, wave

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'games', 'gear')
os.makedirs(OUT, exist_ok=True)
SR = 11025
NOTE = {n: i for i, n in enumerate(['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'])}

def freq(n):
    if n in ('-', '.'): return 0.0
    return 440.0 * 2 ** ((NOTE[n[:-1]] + 12 * (int(n[-1]) + 1) - 69) / 12)

def square(f, i, duty=.25): return 1.0 if (f * i / SR) % 1 < duty else -1.0
def tri(f, i): p = (f * i / SR) % 1; return 4 * p - 1 if p < .5 else 3 - 4 * p

def save(name, samples):
    peak = max(1e-6, max(abs(s) for s in samples))
    with wave.open(os.path.join(OUT, name + '.wav'), 'w') as w:
        w.setnchannels(1); w.setsampwidth(1); w.setframerate(SR)
        w.writeframes(bytes(int(128 + max(-1, min(1, s / peak * .85)) * 120) for s in samples))

def render(bpm, lead, bass, ticks, bars, duty=.25, lead_vol=.3, bass_vol=.36, arp=None):
    step = 60 / bpm / 2
    total = int(SR * step * 8 * bars); out = [0.0] * total
    def place(seq, voice, vol, length):
        pos = 0
        for n in seq:
            f = freq(n); start = int(pos * step * SR); dur = int(length * step * SR)
            if f > 0:
                for k in range(dur):
                    i = start + k
                    if i >= total: break
                    env = min(1.0, k / (SR * .006)) * (1.0 if k < dur * .65 else max(0, (dur - k) / (dur * .35)))
                    out[i] += vol * env * (square(f, i, duty) if voice == 'lead' else tri(f, i))
            pos += length
    place(lead, 'lead', lead_vol, 2)
    place(bass, 'bass', bass_vol, 4)
    if arp: place(arp, 'lead', lead_vol * .35, 1)
    rnd = random.Random(5)
    for b, hit in enumerate(ticks * (bars * 8 // len(ticks))):
        if hit == '.': continue
        start = int(b * step * SR)
        if hit == 't':   # clock tick: a tiny high click
            for k in range(int(SR * .012)):
                i = start + k
                if i < total: out[i] += .16 * (1 if (k // 3) % 2 else -1) * (1 - k / (SR * .012))
        else:            # 'k' a soft knock
            dur = int(SR * .07)
            for k in range(dur):
                i = start + k
                if i < total: out[i] += .2 * math.sin(2 * math.pi * 90 * k / SR) * (1 - k / dur) + .05 * (rnd.random() * 2 - 1) * (1 - k / dur)
    return out

# title: slow and curious, A minor, 8 bars
title_lead = ['A4','C5','E5','D5', 'C5','B4','A4','-', 'F4','A4','C5','B4', 'A4','G#4','E4','-',
              'A4','C5','E5','G5', 'F5','E5','D5','-', 'C5','B4','A4','G#4', 'A4','-','-','-']
title_bass = ['A2','A2','F2','F2', 'D2','D2','E2','E2', 'A2','A2','F2','F2', 'D2','E2','A2','A2']
save('music_title', render(96, title_lead, title_bass, ['t','.','t','.','t','.','t','.'], 8, duty=.5, lead_vol=.24))

# play: ticking clockwork, light and steady, 8 bars
play_lead = ['E5','-','A5','G5', 'E5','D5','C5','D5', 'E5','-','C5','D5', 'B4','-','G4','-',
             'A4','C5','E5','A5', 'G5','E5','D5','C5', 'D5','E5','C5','B4', 'A4','-','-','-']
play_bass = ['A2','E3','A2','E3', 'F2','C3','G2','D3', 'A2','E3','F2','C3', 'D2','A2','E2','E2']
play_arp = (['A4','C5','E5','C5'] * 4 + ['F4','A4','C5','A4'] * 4 + ['G4','B4','D5','B4'] * 4 + ['E4','G#4','B4','G#4'] * 4) * 2
save('music_play', render(118, play_lead, play_bass, ['k','t','t','t','k','t','k','t'], 8, lead_vol=.26, arp=play_arp))

# ------------------------------------------------------------------ sounds
def tone(f0, f1, dur, vol=.6, kind='sq', duty=.5):
    n = int(SR * dur); out = []
    ph = 0.0
    for k in range(n):
        f = f0 + (f1 - f0) * k / n; ph += f / SR
        v = (1 if (ph % 1) < duty else -1) if kind == 'sq' else (4 * (ph % 1) - 1 if (ph % 1) < .5 else 3 - 4 * (ph % 1))
        out.append(vol * v * min(1, k / (SR * .003)) * (1 - k / n))
    return out

def noise(dur, vol=.4, seed=1):
    r = random.Random(seed); n = int(SR * dur)
    return [vol * (r.random() * 2 - 1) * (1 - k / n) for k in range(n)]

def mix(*parts):
    n = max(len(p) for p in parts); out = [0.0] * n
    for p in parts:
        for i, s in enumerate(p): out[i] += s
    return out

def seq(*parts):
    out = []
    for p in parts: out += p
    return out

save('turn', seq(noise(.012, .5, 2), tone(900, 700, .03, .35), [0.0] * 200, noise(.01, .35, 3), tone(700, 600, .025, .25)))
save('gear', mix(tone(110, 80, .18, .5, 'tri'), seq(*[noise(.008, .4, i) + [0.0] * int(SR * .022) for i in range(6)])))
save('place', mix(tone(160, 70, .14, .6, 'tri'), noise(.05, .35, 9)))
save('power', seq(tone(300, 900, .12, .4), tone(900, 1300, .08, .3), tone(1300, 1300, .1, .2, duty=.25)))
save('portal', seq(*[tone(f, f, .07, .35, duty=.25) for f in (523, 659, 784, 1047, 1319, 1568)], tone(1568, 1568, .25, .25, 'tri')))
save('fail', seq(tone(400, 300, .14, .4), tone(300, 220, .14, .4), tone(220, 120, .3, .45)))
save('bonk', mix(tone(140, 110, .09, .5, 'tri'), noise(.02, .3, 4)))
save('step', tone(1400, 1600, .02, .25, duty=.25))
save('swap', tone(880, 1100, .035, .3, duty=.25))
save('land', seq(tone(660, 660, .03, .3), tone(990, 990, .05, .3)))
print('ok', sorted(os.listdir(OUT)))

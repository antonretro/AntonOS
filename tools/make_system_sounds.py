#!/usr/bin/env python3
"""Synthesises the short sounds played under the system intro before a game starts (original, from scratch:
not any console's real start-up sound). Each family gets its own colour so you can hear which system is starting:

  8-bit (NES, Master System):   a quick square-wave rising arpeggio
  16-bit (SNES, Mega Drive, PC Engine): a warm FM-style chord swell
  handheld (Game Boy, Color):   two clean pulse blips
  GBA, Game Gear:               a bright sparkle run
  PlayStation:                  a deep soft pad with a shimmer

Writes app/src/main/res/raw/sys_*.wav (22 kHz mono 16-bit).

  python tools/make_system_sounds.py
"""
import os, wave
import numpy as np

SR = 22050
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'raw')

def hz(n): return 440.0 * 2 ** ((n - 69) / 12)
def t_(d): return np.arange(int(SR * d)) / SR

def env(t, a, d):
    return np.minimum(1, t / a) * np.exp(-np.maximum(0, t - a) / d)

def square(note, dur, duty=.5, decay=.25):
    t = t_(dur); ph = (hz(note) * t) % 1
    return np.where(ph < duty, 1.0, -1.0) * env(t, .003, decay) * .5

def fm(note, dur, ratio=2.0, index=1.6, a=.02, decay=.8):
    t = t_(dur); f = hz(note)
    mod = np.sin(2 * np.pi * f * ratio * t) * index * np.exp(-t / .5)
    return np.sin(2 * np.pi * f * t + mod) * env(t, a, decay)

def sine(note, dur, a=.01, decay=.5, harm=(1, .3, .1)):
    t = t_(dur); f = hz(note)
    return sum(h * np.sin(2 * np.pi * f * (i + 1) * t) for i, h in enumerate(harm)) * env(t, a, decay)

def place(total, parts):
    out = np.zeros(int(SR * total))
    for start, x in parts:
        s = int(SR * start); n = min(len(x), len(out) - s); out[s:s + n] += x[:n]
    return out

def write(name, x, peak=.55):
    x = x / (np.max(np.abs(x)) + 1e-9) * peak
    fade = int(SR * .05); x[-fade:] *= np.linspace(1, 0, fade)
    with wave.open(os.path.join(OUT, f'sys_{name}.wav'), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x * 32767).astype(np.int16).tobytes())

# 8-bit: C major-ish rising arpeggio, then a held fifth
write('8bit', place(1.1, [(0, square(72, .12, .25, .08)), (.07, square(76, .12, .25, .08)), (.14, square(79, .12, .25, .08)),
                          (.21, square(84, .6, .5, .25)), (.21, square(79, .6, .125, .25) * .5)]))
# 16-bit: FM chord swelling in (Fmaj9 colour), slow release
write('16bit', place(1.4, [(0, fm(65, 1.4, 2, 1.2, .08, .7)), (.03, fm(69, 1.4, 2, 1.0, .08, .7)), (.06, fm(72, 1.4, 3, .8, .08, .7)),
                           (.09, fm(76, 1.4, 1, .6, .08, .7) * .7), (.0, fm(53, 1.4, 1, .4, .1, .8) * .6)]))
# handheld: two clean pulse blips, low then high
write('handheld', place(.8, [(0, square(76, .09, .5, .05)), (.11, square(88, .5, .5, .18))]), .45)
# GBA / Game Gear: bright sparkle run up
write('sparkle', place(1.0, [(i * .045, sine(84 + n, .5, .004, .18, (1, .2))) for i, n in enumerate([0, 4, 7, 11, 12, 16, 19])]))
# PlayStation: deep pad with a slow shimmer on top
pad = place(1.6, [(0, sine(45, 1.6, .25, 1.2, (1, .5, .25, .1))), (0, sine(52, 1.6, .3, 1.2, (1, .3))), (.25, sine(81, 1.2, .2, .6, (1,)) * .25)])
write('ps', pad)
print('ok')

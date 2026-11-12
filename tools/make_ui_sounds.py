#!/usr/bin/env python3
"""Synthesises AntonOS's interface sounds from scratch (original, no samples, no console's real sounds).

Soft, short, tonal: a quiet glassy tick to move (a touch higher going right/down), a two-note lift to select,
a falling pair for back, an airy whoosh for a tab change, a dull low bump at the end of a row, and a small
bright pop for a notice. Writes app/src/main/res/raw/ui_*.wav (22 kHz mono 16-bit, a few KB each).

  python tools/make_ui_sounds.py
"""
import os, wave
import numpy as np

SR = 22050
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'raw')
rng = np.random.default_rng(2026)

def hz(n): return 440.0 * 2 ** ((n - 69) / 12)

def tone(note, dur, decay, harm=(1, .35, .12), detune=0.0):
    t = np.arange(int(SR * dur)) / SR
    f = hz(note) * (1 + detune)
    x = sum(a * np.sin(2 * np.pi * f * (i + 1) * t) for i, a in enumerate(harm))
    attack = np.minimum(1, t / 0.004)              # 4 ms: soft, no click
    return x * attack * np.exp(-t / decay)

def place(total, parts):
    out = np.zeros(int(SR * total))
    for start, x in parts:
        s = int(SR * start); out[s:s + len(x)] += x[:max(0, len(out) - s)]
    return out

def write(name, x, peak):
    x = x / (np.max(np.abs(x)) + 1e-9) * peak
    fade = min(len(x), int(SR * 0.01)); x[-fade:] *= np.linspace(1, 0, fade)
    with wave.open(os.path.join(OUT, f'ui_{name}.wav'), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x * 32767).astype(np.int16).tobytes())

# move: a tiny glassy tick (two pitches so direction can be heard)
write('move', tone(96, .07, .018, (1, .5, .2)), .22)
write('move2', tone(99, .07, .018, (1, .5, .2)), .22)
# select: a quick rising pair, warm
write('select', place(.22, [(0, tone(84, .18, .06)), (.045, tone(91, .17, .07))]), .36)
# back: the same pair falling, a little softer
write('back', place(.2, [(0, tone(89, .16, .05)), (.04, tone(82, .16, .06))]), .30)
# tab: an airy band-limited whoosh with a soft tone under it
n = int(SR * .22); t = np.arange(n) / SR
noise = rng.standard_normal(n)
k = np.ones(12) / 12; air = np.convolve(noise, k, 'same') - np.convolve(noise, np.ones(60) / 60, 'same')
shape = np.sin(np.pi * np.minimum(1, t / .22)) ** 2
write('tab', air * shape * .6 + tone(79, .22, .08) * .35, .20)
# bump: can't go further (low, muffled, short)
write('bump', tone(52, .09, .025, (1, .2)), .28)
# notice: small bright pop
write('notice', place(.25, [(0, tone(93, .2, .05)), (.06, tone(100, .2, .06))]), .26)
print('ok')

#!/usr/bin/env python3
"""Synthesises Orchard Swap's sound effects (original): soft fruity pops and chimes, no samples.
Writes app/src/main/assets/games/orchard/*.wav (22 kHz mono 16-bit).

  python tools/make_orchard_sounds.py
"""
import os, wave
import numpy as np

SR = 22050
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'games', 'orchard')
os.makedirs(OUT, exist_ok=True)
rng = np.random.default_rng(7)
def hz(n): return 440.0 * 2 ** ((n - 69) / 12)
def t_(d): return np.arange(int(SR * d)) / SR

def knock(freq, dur, decay):
    """a wooden knock: a short pitched body plus a click of filtered noise"""
    t = t_(dur)
    body = np.sin(2 * np.pi * freq * t) * np.exp(-t / decay) + .4 * np.sin(2 * np.pi * freq * 2.3 * t) * np.exp(-t / (decay * .6))
    click = rng.standard_normal(len(t)) * np.exp(-t / .004)
    click = np.convolve(click, np.ones(6) / 6, 'same')
    return body + .5 * click

def tone(note, dur, decay):
    t = t_(dur); f = hz(note)
    return (np.sin(2 * np.pi * f * t) + .3 * np.sin(4 * np.pi * f * t)) * np.minimum(1, t / .004) * np.exp(-t / decay)

def seq(parts, total):
    out = np.zeros(int(SR * total))
    for start, x in parts:
        s = int(SR * start); out[s:s + len(x)] += x[:max(0, len(out) - s)]
    return out

def write(name, x, peak):
    x = x / (np.max(np.abs(x)) + 1e-9) * peak
    f = min(len(x), int(SR * .01)); x[-f:] *= np.linspace(1, 0, f)
    with wave.open(os.path.join(OUT, name + '.wav'), 'wb') as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes((x * 32767).astype(np.int16).tobytes())

write('pick', tone(84, .08, .03), .35)
write('swap', seq([(0, tone(79, .08, .03)), (.03, tone(84, .08, .03))], .14), .4)
write('nope', seq([(0, tone(70, .1, .04)), (.06, tone(66, .12, .05))], .22), .4)
write('pop', seq([(0, knock(700, .06, .01)), (0, tone(88, .14, .05))], .16), .55)
write('cascade', seq([(i * .05, tone(n, .16, .06)) for i, n in enumerate([84, 88, 91, 96])], .4), .6)
write('big', seq([(0, knock(300, .12, .03))] + [(i * .06, tone(n, .25, .09)) for i, n in enumerate([79, 84, 88, 91, 96])], .6), .7)
write('round', seq([(i * .1, tone(n, .35, .14)) for i, n in enumerate([72, 76, 79, 84])], .8), .6)
write('over', seq([(i * .16, tone(n, .4, .15)) for i, n in enumerate([79, 74, 70, 67])], 1.0), .6)
print('ok')

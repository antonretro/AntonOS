#!/usr/bin/env python3
"""Synthesises Crate Cascade's sound effects (original): wooden knocks and soft tonal pops, no samples.
Writes app/src/main/assets/games/crate/*.wav (22 kHz mono 16-bit).

  python tools/make_crate_sounds.py
"""
import os, wave
import numpy as np

SR = 22050
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'games', 'crate')
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

write('move', knock(900, .05, .008), .35)
write('turn', knock(1200, .06, .01), .4)
write('lock', knock(220, .14, .03), .7)
write('drop', seq([(0, knock(160, .2, .05)), (.02, knock(320, .12, .02))], .22), .8)
write('hold', seq([(0, tone(76, .12, .04)), (.05, tone(81, .12, .05))], .2), .45)
write('clear', seq([(0, tone(79, .2, .06)), (.06, tone(84, .2, .07)), (.12, tone(88, .25, .09))], .4), .6)
write('four', seq([(i * .07, tone(n, .3, .1)) for i, n in enumerate([72, 76, 79, 84, 88, 91])], .75), .7)
write('over', seq([(i * .16, tone(n, .4, .15)) for i, n in enumerate([79, 74, 70, 67])], 1.0), .6)
print('ok')

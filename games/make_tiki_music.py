#!/usr/bin/env python3
"""Totem Tide's music: three short original chiptune loops (title, play, danger), synthesised here.

    python launcher/games/make_tiki_music.py

8-bit mono WAV at 11025 Hz (small: ~175 KB each). Square-wave lead, triangle bass, soft noise percussion.
The melodies are original, written for this game on a C major pentatonic "island" feel.
"""
import math, os, random, struct, wave

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'assets', 'games', 'tiki')
SR = 11025
NOTE = {n: i for i, n in enumerate(['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'])}
def freq(n):
    if n in ('-', '.'): return 0.0
    name, octv = n[:-1], int(n[-1])
    return 440.0 * 2 ** ((NOTE[name] + 12 * (octv + 1) - 69) / 12)

def square(f, i, duty=.25): return 1.0 if (f * i / SR) % 1 < duty else -1.0
def tri(f, i): p = (f * i / SR) % 1; return 4 * p - 1 if p < .5 else 3 - 4 * p

def render(bpm, lead, bass, drums, bars, lead_duty=.25, swing=0.0, lead_vol=.32, bass_vol=.34):
    beat = 60 / bpm; step = beat / 2                 # eighth notes
    total = int(SR * step * 8 * bars)
    out = [0.0] * total
    def place(seq, voice, vol, length_steps):
        pos = 0
        for n in seq:
            f = freq(n)
            start = int(pos * step * SR); dur = int(length_steps * step * SR)
            if n == '.':   # tie: extend the previous note (handled by '-' rests elsewhere)
                pos += length_steps; continue
            if f > 0:
                for k in range(dur):
                    i = start + k
                    if i >= total: break
                    env = min(1.0, k / (SR * .005)) * (1.0 if k < dur * .7 else max(0, (dur - k) / (dur * .3)))
                    out[i] += vol * env * (square(f, i, lead_duty) if voice == 'lead' else tri(f, i))
            pos += length_steps
    place(lead, 'lead', lead_vol, 2)       # lead in quarter notes
    place(bass, 'bass', bass_vol, 4)       # bass in half notes
    rnd = random.Random(3)
    for b, hit in enumerate(drums * (bars * 8 // len(drums))):
        start = int(b * step * SR)
        if hit == '.': continue
        dur = int(SR * (.05 if hit == 'h' else .09)); vol = .12 if hit == 'h' else .22
        for k in range(dur):
            i = start + k
            if i < total: out[i] += vol * (rnd.random() * 2 - 1) * (1 - k / dur)
    peak = max(1e-6, max(abs(s) for s in out))
    return [s / peak * .85 for s in out]

def save(name, samples):
    with wave.open(os.path.join(OUT, name + '.wav'), 'w') as w:
        w.setnchannels(1); w.setsampwidth(1); w.setframerate(SR)
        w.writeframes(bytes(int(128 + max(-1, min(1, s)) * 120) for s in samples))

# title: calm, 8 bars at 112 bpm
title_lead = ['E5','G5','A5','G5', 'E5','D5','C5','-', 'A4','C5','D5','C5', 'D5','E5','D5','-',
              'E5','G5','A5','C6', 'A5','G5','E5','-', 'D5','E5','G5','E5', 'C5','-','-','-']
title_bass = ['C3','C3', 'A2','A2', 'F2','F2', 'G2','G2', 'C3','C3', 'A2','A2', 'F2','G2', 'C3','C3']
save('music_title', render(112, title_lead, title_bass, ['h','.','h','.','h','.','h','h'], 8, lead_duty=.5, lead_vol=.26))

# play: more drive, 8 bars at 132 bpm, a busier tune over the same island chords
play_lead = ['C5','E5','G5','E5', 'A5','G5','E5','D5', 'C5','D5','E5','G5', 'D5','-','E5','-',
             'G5','A5','C6','A5', 'G5','E5','D5','E5', 'C5','E5','D5','A4', 'C5','-','G4','-']
play_bass = ['C3','G2', 'A2','E2', 'F2','C3', 'G2','D3', 'C3','G2', 'A2','E2', 'F2','G2', 'C3','G2']
save('music_play', render(132, play_lead, play_bass, ['k','h','s','h','k','k','s','h'], 8))

# danger: the play tune faster and higher, over a tense minor bass
danger_lead = ['A5','C6','E6','C6', 'D6','C6','A5','G5', 'A5','C6','E6','G6', 'E6','-','D6','-',
               'C6','D6','E6','D6', 'C6','A5','G5','A5', 'E5','G5','A5','C6', 'A5','-','E5','-']
danger_bass = ['A2','E2', 'A2','E2', 'F2','C3', 'G2','E2', 'A2','E2', 'A2','E2', 'F2','G2', 'A2','E2']
save('music_danger', render(164, danger_lead, danger_bass, ['k','h','s','h','k','h','s','s'], 8, lead_duty=.125))
for n in ['music_title', 'music_play', 'music_danger']:
    print(n, os.path.getsize(os.path.join(OUT, n + '.wav')) // 1024, 'KB')

#!/usr/bin/env python3
"""Synthesises Retro Launcher's start-up chime from scratch (original, no samples).

A warm late-90s style start-up: a swelling detuned-saw pad on an Eb major 9 chord,
a rising FM-bell arpeggio over it, a soft airy whoosh, and a small reverb.
Writes app/src/main/res/raw/startup_chime.ogg (needs ffmpeg on PATH).

  python tools/make_chime.py
"""
import os, subprocess, sys, tempfile, wave
import numpy as np

SR = 44100
LEN = 5.2
t = np.arange(int(SR * LEN)) / SR
rng = np.random.default_rng(1995)

def hz(n):  # MIDI note -> Hz
    return 440.0 * 2 ** ((n - 69) / 12)

def env(start, attack, hold, release):
    e = np.zeros_like(t)
    a0, a1 = start, start + attack
    r0, r1 = a1 + hold, a1 + hold + release
    m = (t >= a0) & (t < a1); e[m] = ((t[m] - a0) / attack) ** 2
    m = (t >= a1) & (t < r0); e[m] = 1
    m = (t >= r0) & (t < r1); e[m] = (1 - (t[m] - r0) / release) ** 2
    return e

def lowpass(x, cutoff):
    a = np.exp(-2 * np.pi * cutoff / SR); y = np.empty_like(x); acc = 0.0
    for i, v in enumerate(x):
        acc = (1 - a) * v + a * acc; y[i] = acc
    return y

def saw(f, phase=0.0):
    return 2 * ((t * f + phase) % 1.0) - 1

def pad(channel):
    chord = [51, 55, 58, 62, 65, 70]          # Eb3 G3 Bb3 D4 F4 Bb4: Eb maj9
    out = np.zeros_like(t)
    for n in chord:
        for d in (-7, 0, 7):                     # cents of detune
            f = hz(n) * 2 ** ((d + (3 if channel else -3)) / 1200)
            out += saw(f, rng.random()) / 3
    out = lowpass(lowpass(out, 1400), 2600) / len(chord)
    return out * env(0.05, 1.2, 1.6, 2.2) * 0.55

def bell(note, start, amp):
    f = hz(note); e = np.clip(t - start, 0, None)
    gate = (t >= start).astype(float)
    mod = np.sin(2 * np.pi * f * 3.5 * e) * 2.2 * np.exp(-e * 5)
    return np.sin(2 * np.pi * f * e + mod) * np.exp(-e * 2.4) * gate * amp

def whoosh():
    n = rng.standard_normal(len(t))
    return lowpass(n, 900) * env(0.0, 0.9, 0.2, 1.4) * 0.35

def reverb(x):
    out = x.copy()
    for d, g in ((0.0297, .72), (0.0371, .70), (0.0411, .68), (0.0437, .66)):
        k = int(d * SR); y = x.copy()
        for i in range(k, len(y)):
            y[i] += g * y[i - k]
        out += y * 0.18
    return out

def main():
    arp = [(70, .35), (75, .50), (79, .65), (82, .80), (86, .98), (89, 1.25)]   # Bb4 Eb5 G5 Bb5 D6 F6
    chans = []
    for ch in (0, 1):
        x = pad(ch) + whoosh()
        for i, (n, s) in enumerate(arp):
            x += bell(n, s + (0.012 if ch else 0), 0.20 if i < 5 else 0.26)
        x = reverb(x)
        chans.append(x)
    st = np.stack(chans, axis=1)
    fade = np.ones(len(t)); fl = int(0.6 * SR); fade[-fl:] = np.linspace(1, 0, fl)
    st *= fade[:, None]
    st /= np.max(np.abs(st)) / 0.85
    pcm = (st * 32767).astype(np.int16)
    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.join(here, "..", "app", "src", "main", "res", "raw", "startup_chime.ogg")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with tempfile.TemporaryDirectory() as d:
        wav = os.path.join(d, "chime.wav")
        with wave.open(wav, "wb") as w:
            w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", "4", out], check=True)
    print("wrote", os.path.normpath(out), os.path.getsize(out), "bytes")

if __name__ == "__main__":
    sys.exit(main())

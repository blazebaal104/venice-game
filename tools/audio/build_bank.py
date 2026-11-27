#!/usr/bin/env python3
"""Builds app/src/main/assets/sfx.bank from CC0 field recordings.

Sources: the CC0 Freesound excerpts collected (and licence-verified) by the LuminaBench/lumina-sfx repository
(sources/recordings.json, commit 9ee63012e36b4d67a025a1a135750f980c511512); clone it and extract them with extract_lumina.py into a folder of mono WAVs, then
    python3 build_bank.py <wav dir> <out sfx.bank> <out CREDITS.md>

Bank format (little endian):  'SFX1' u16 count, then per entry:
    u8 name length, name (ascii), u32 sample rate, u32 frames, u32 marks, i32 marks[] , i16 pcm[frames]
Loops are made seamless here (equal-power crossfade of the tail into the head), one-shots are trimmed and faded.
'marks' for the outboard grain source are firing pulse positions (pitch marks for PSOLA); for the horn they are
the sustain loop start/end; otherwise empty.
"""
import sys, os, json, wave, struct
import numpy as np, scipy.signal as ss

RAW, OUT, CRED = sys.argv[1], sys.argv[2], sys.argv[3]
credits = json.load(open(os.path.join(RAW, 'credits.json')))
SRMAX = 24000

def load(name):
    w = wave.open(os.path.join(RAW, name + '.wav')); sr = w.getframerate()
    x = np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float64) / 32768
    if sr > SRMAX:
        g = np.gcd(sr, SRMAX); x = ss.resample_poly(x, SRMAX // g, sr // g); sr = SRMAX
    b, a = ss.butter(2, 25 / (sr / 2), 'high'); x = ss.filtfilt(b, a, x)        # no DC / sub rumble
    return x, sr

def rms(x): return float(np.sqrt(np.mean(x ** 2)) + 1e-12)
def norm_rms(x, db=-20.0): x = x * (10 ** (db / 20) / rms(x)); return x / max(1.0, np.abs(x).max() / 0.98)
def norm_peak(x, p=0.9): return x * (p / (np.abs(x).max() + 1e-12))

def loop(x, sr, xf=0.3):
    L = int(xf * sr); n = len(x)
    if n < 3 * L: L = n // 4
    t = np.linspace(0, np.pi / 2, L)
    y = x[:n - L].copy()
    y[:L] = x[:L] * np.sin(t) + x[n - L:] * np.cos(t)
    return y

def oneshot(x, sr, thr=0.02, fade=0.02):
    a = np.abs(x); m = a.max()
    i0 = max(0, int(np.argmax(a > thr * m)) - int(0.002 * sr))
    # end: last point above 0.5% of the peak (smoothed)
    k = max(1, int(0.01 * sr)); e = np.convolve(a, np.ones(k) / k, 'same')
    above = np.nonzero(e > 0.005 * m)[0]; i1 = min(len(x), above[-1] + int(0.03 * sr)) if len(above) else len(x)
    y = x[i0:i1].copy(); f = min(len(y) // 3, int(fade * sr))
    if f > 0: y[-f:] *= np.linspace(1, 0, f)
    f0 = min(len(y) // 10, int(0.001 * sr))
    if f0 > 0: y[:f0] *= np.linspace(0, 1, f0)
    return y

def pitch_marks(x, sr, f_guess, hop=0.1, win=0.45):
    """firing pulses of a steady engine: local period from envelope autocorrelation, integrated to a phase;
    marks where the phase crosses whole cycles, aligned to where the envelope rises most"""
    b, a = ss.butter(2, [150 / (sr / 2), 4000 / (sr / 2)], 'band'); y = ss.filtfilt(b, a, x)
    env = np.abs(ss.hilbert(y)); k = int(sr * 0.0015); env = np.convolve(env, np.ones(k) / k, 'same')
    n = len(x); W = int(win * sr); H = int(hop * sr); per = []; cen = []
    lo, hi = int(sr / (f_guess * 1.3)), int(sr / (f_guess * 0.75))
    for s in range(0, n - W, H):
        e = env[s:s + W] - env[s:s + W].mean(); ac = np.correlate(e, e, 'full')[W - 1:]
        i = lo + int(np.argmax(ac[lo:hi]))
        if lo < i < hi - 1:
            y0, y1, y2 = ac[i - 1], ac[i], ac[i + 1]; i = i + 0.5 * (y0 - y2) / (y0 - 2 * y1 + y2 + 1e-12)
        per.append(i); cen.append(s + W / 2)
    per = ss.medfilt(np.array(per), 5)
    P = np.interp(np.arange(n), cen, per); ph = np.cumsum(1.0 / P)
    d = np.diff(env, prepend=env[0]); d[d < 0] = 0
    def at(o): m = np.searchsorted(ph, np.arange(np.ceil(ph[0]) + 1, ph[-1] - 1) + o); return m[(m > 0) & (m < n)]
    o = max(np.linspace(0, 1, 64, endpoint=False), key=lambda o: d[at(o)].sum())
    m = at(o)
    # keep marks with a full period on both sides
    m = m[(m > P[m] * 1.1) & (m < n - P[m] * 1.1)]
    return m.astype(np.int64), P[m]

entries = []   # (name, sr, pcm float, marks list)
used = {}
def add(name, x, sr, marks=(), src=None):
    entries.append((name, sr, x, list(marks)))
    for s in (src if isinstance(src, (list, tuple)) else [src]):
        if s: used.setdefault(s.split('__')[0], []).append(name)

# ---------------------------------------------------------------- your outboard
# grain source: a river boat's outboard running steady (~103 firings/s, 4-cylinder 4-stroke at ~3100 rpm)
x, sr = load('motion-flatboat-throttle__0'); x = norm_rms(x, -16)
m, P = pitch_marks(x, sr, 103)
marks = []
for a, p in zip(m, P): marks += [int(a), int(round(p * 16))]      # pairs: position, period*16
add('eng_grain', x, sr, marks, 'motion-flatboat-throttle__0')
# low rumble bed for idle and low revs
x, sr = load('motion-marine-engine__0'); add('eng_idle', norm_rms(loop(x, sr), -18), sr, (), 'motion-marine-engine__0')

# ---------------------------------------------------------------- ACTV diesels (one character per boat type)
for nm, src in (('dsl_big', 'field-diesel-idle__0'), ('dsl_bus', 'city-bus-engine-idle-loop__0'), ('dsl_hood', 'motion-diesel-hood__0'), ('dsl_tractor', 'isolated-tractor-diesel-idle-loop__0')):
    x, sr = load(src); add(nm, norm_rms(loop(x, sr), -18), sr, (), src)

# ---------------------------------------------------------------- water
x, sr = load('milestone-bow__0'); add('bow', norm_rms(loop(x, sr, 0.5), -18), sr, (), 'milestone-bow__0')
x, sr = load('motion-sailboat-cabin__0'); add('cabin', norm_rms(loop(x, sr, 0.5), -24), sr, (), 'motion-sailboat-cabin__0')
a, sr = load('atlas-pebble__0'); b, _ = load('atlas-pebble__1')
add('lap', norm_rms(loop(np.concatenate([a, b]), sr, 0.4), -22), sr, (), ['atlas-pebble__0', 'atlas-pebble__1'])
for i in range(4):
    x, sr = load(f'heavy-puddle-stomp__{i}'); add(f'slap{i}', norm_peak(oneshot(x, sr)), sr, (), f'heavy-puddle-stomp__{i}')
x, sr = load('milestone-smallwater__0'); add('slap4', norm_peak(oneshot(x, sr)), sr, (), 'milestone-smallwater__0')
for i in range(2):
    x, sr = load(f'water-drops-from-spout__{i}'); add(f'plip{i}', norm_peak(oneshot(x, sr)), sr, (), f'water-drops-from-spout__{i}')
for nm, src in (('splash0', 'isolated-swimmer-entry__0'), ('splash1', 'well-bucket-lower__0'), ('splash2', 'milestone-snapwater__0'), ('splash3', 'milestone-snapwater__1')):
    x, sr = load(src); add(nm, norm_peak(oneshot(x, sr)), sr, (), src)

# ---------------------------------------------------------------- weather
a, sr = load('isolated-garden-shower__0'); b, _ = load('isolated-garden-shower__1')
add('rain', norm_rms(loop(np.concatenate([a, b]), sr, 0.6), -20), sr, (), ['isolated-garden-shower__0', 'isolated-garden-shower__1'])
for i in range(10):
    x, sr = load(f'isolated-canopy-droplets__{i}'); add(f'drop{i}', norm_peak(oneshot(x, sr, 0.05, 0.005)), sr, (), f'isolated-canopy-droplets__{i}')
x, sr = load('demo-sheltered-wind__0'); add('wind', norm_rms(loop(x, sr, 0.8), -20), sr, (), 'demo-sheltered-wind__0')
for nm, src in (('thA', 'atlas-thunder-left__0'), ('thB', 'atlas-thunder-right__0'), ('thR0', 'atlas-thunder-roll__0'), ('thR1', 'atlas-thunder-roll__1'),
                ('thS0', 'isolated-storm-body__0'), ('thS1', 'isolated-storm-body__1'), ('thX', 'isolated-thunder-attack__0')):
    x, sr = load(src); add(nm, norm_peak(oneshot(x, sr, 0.01, 0.3), 0.95), sr, (), src)

# ---------------------------------------------------------------- impacts, wood, metal, ropes
oneshots = [('stone0', 'rock-pile-impact__0'), ('stone1', 'rock-pile-impact__1'), ('stone2', 'rock-pile-impact__2'), ('stone3', 'atlas-stone__0'), ('stone4', 'atlas-stone__1'),
            ('wood0', 'wooden-crate-smash__0'), ('wood1', 'slatted-crate-settle__0'), ('wood2', 'slatted-crate-settle__1'), ('wood3', 'wood-platform-landing__0'),
            ('thock0', 'hatchet-greenwood__0'), ('thock1', 'hatchet-greenwood__1'),
            ('metal0', 'heavy-metal-door-slam__0'), ('metal1', 'atlas-scrap__0'), ('metal2', 'atlas-scrap__1'), ('metal3', 'atlas-scrap__2'), ('bump', 'car-bumper-bump__0'),
            ('scrape', 'car-sideswipe-scrape__0'), ('creak0', 'barn-timber-creak__0'), ('creak1', 'paddock-gate-swing__0'),
            ('chain0', 'motion-chain-link__0'), ('chain1', 'motion-chain-link__1'), ('clank0', 'chain-rattle-short__0'), ('clank1', 'chain-rattle-short__1'),
            ('wings0', 'field-wings__0'), ('wings1', 'field-wings__1')]
for nm, src in oneshots:
    x, sr = load(src); add(nm, norm_peak(oneshot(x, sr)), sr, (), src)

# ---------------------------------------------------------------- horn: an air horn with a sustain loop
x, sr = load('heavy-truck-horn__0'); x = norm_peak(oneshot(x, sr, 0.05, 0.06))
h = int(0.01 * sr); e = np.array([rms(x[i:i + h]) for i in range(0, len(x) - h, h)])
on = np.nonzero(e > 0.35 * e.max())[0]; s0, s1 = int(on[0] * h + 0.03 * sr), int(on[-1] * h - 0.02 * sr)
# loop points on rising zero crossings, crossfaded so the sustain can run for any length
zc = np.nonzero((x[:-1] < 0) & (x[1:] >= 0))[0]
s0 = int(zc[np.argmin(np.abs(zc - s0))]); s1 = int(zc[np.argmin(np.abs(zc - s1))])
add('horn', x, sr, [s0, s1], 'heavy-truck-horn__0')

# ---------------------------------------------------------------- write
with open(OUT, 'wb') as f:
    f.write(b'SFX1'); f.write(struct.pack('<H', len(entries)))
    for name, sr, x, mk in entries:
        pcm = np.clip(np.round(x * 32767), -32768, 32767).astype('<i2')
        nb = name.encode('ascii'); f.write(struct.pack('<B', len(nb))); f.write(nb)
        f.write(struct.pack('<III', sr, len(pcm), len(mk))); f.write(np.array(mk, dtype='<i4').tobytes()); f.write(pcm.tobytes())
tot = sum(len(e[2]) for e in entries); print(len(entries), 'entries', round(sum(len(e[2]) / e[1] for e in entries), 1), 's', os.path.getsize(OUT) // 1024, 'KB')
for name, sr, x, mk in entries: print(f'  {name:12s} {len(x) / sr:6.2f}s {sr}  marks {len(mk)}')
with open(CRED, 'w') as f:
    f.write('# Sound credits\n\nAll recordings are **CC0 1.0 (public domain)** field recordings from [Freesound](https://freesound.org), '
            'as excerpted and licence-verified by [LuminaBench/lumina-sfx](https://github.com/LuminaBench/lumina-sfx). '
            'Attribution is not required; it is given here with thanks. They were trimmed, looped, filtered and level-matched for the game '
            '(tools/audio/build_bank.py) and are played back pitched, granulated and mixed by app/.../audio/Audio.kt.\n\n'
            '| Used as | Title | Author | Source |\n|---|---|---|---|\n')
    for k, names in sorted(used.items()):
        c = credits[k]; f.write(f"| {', '.join(sorted(set(names)))} | {c['title']} | {c['author']} | {c['url']} |\n")

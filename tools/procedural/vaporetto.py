"""ACTV vaporetto (motobattello, the classic line-1/2 boat): ~23.5 m long, 4.2 m beam, white hull with the heavy black
rubber fender all round, enclosed passenger cabin with a band of windows, raised wheelhouse at the front of the roof,
open bow deck and an open aft deck with railings. +x = bow, z = metres above the waterline.
Run:  python3 tools/procedural/vaporetto.py  -> app/src/main/assets/live/vaporetto.mesh"""
import math, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from meshlib import *

L, B = 23.5, 4.2
def half_beam(x):                     # plan shape: full-bodied, rounded bow, slightly rounded stern
    t = x / (L / 2)
    if t > 0.45: return B / 2 * math.sqrt(max(0.0, 1 - ((t - 0.45) / 0.55) ** 2.2))
    if t < -0.85: return B / 2 * math.sqrt(max(0.0, 1 - ((-t - 0.85) / 0.15) ** 2)) * 0.15 + B / 2 * 0.85
    return B / 2
N = 64
xs = [-L / 2 + L * i / N for i in range(N + 1)]
m = Mesh()
WHITE = (0.90, 0.91, 0.90); GREY = (0.32, 0.34, 0.36); DARK = (0.06, 0.07, 0.08); GLASS = (0.10, 0.14, 0.17)
# hull sides: dark below the waterline-ish band, white topsides; deck at 1.05
for i in range(N):
    a, b = xs[i], xs[i + 1]
    for s in (-1, 1):
        ya, yb = s * half_beam(a), s * half_beam(b)
        m.color(*GREY, gloss=0.3)
        m.quad((a, ya * 0.86, -0.55), (b, yb * 0.86, -0.55), (b, yb, 0.15), (a, ya, 0.15))
        m.color(*WHITE, gloss=0.45)
        m.quad((a, ya, 0.15), (b, yb, 0.15), (b, yb, 1.05), (a, ya, 1.05))
    # bottom and deck
    m.color(*GREY); m.quad((a, -half_beam(a) * 0.86, -0.55), (b, -half_beam(b) * 0.86, -0.55), (b, half_beam(b) * 0.86, -0.55), (a, half_beam(a) * 0.86, -0.55))
    m.mat(PLANKS, 0.55, 0.52, 0.5); m.quad((a, -half_beam(a), 1.05), (b, -half_beam(b), 1.05), (b, half_beam(b), 1.05), (a, half_beam(a), 1.05))
# transom
m.color(*WHITE, gloss=0.45); hb = half_beam(xs[0]); m.quad((xs[0], -hb, -0.55), (xs[0], hb, -0.55), (xs[0], hb, 1.05), (xs[0], -hb, 1.05))
# the black rubber fender all round
m.color(*DARK, gloss=0.2)
ring = [(x, half_beam(x) + 0.12, 0.72) for x in xs] + [(x, -half_beam(x) - 0.12, 0.72) for x in reversed(xs)]
m.tube(ring + [ring[0]], 0.2, 8, caps=False)
# passenger cabin
X0, X1, W = -5.6, 6.2, 1.85
m.color(*WHITE, gloss=0.4)
for s in (-1, 1):
    m.quad((X0, s * W, 1.05), (X1, s * W, 1.05), (X1, s * W, 1.75), (X0, s * W, 1.75))
    m.quad((X0, s * W, 2.75), (X1, s * W, 2.75), (X1, s * W, 3.15), (X0, s * W, 3.15))
    m.color(*GLASS, gloss=0.9); m.quad((X0, s * W * 1.002, 1.75), (X1, s * W * 1.002, 1.75), (X1, s * W * 1.002, 2.75), (X0, s * W * 1.002, 2.75)); m.color(*WHITE, gloss=0.4)
    x = X0 + 1.2
    while x < X1 - 0.5: m.box(x, s * W * 1.004, 2.25, 0.06, 0.02, 0.5); x += 1.45      # window mullions
for xe in (X0, X1):
    m.color(*WHITE, gloss=0.4); m.quad((xe, -W, 1.05), (xe, W, 1.05), (xe, W, 3.15), (xe, -W, 3.15))
    m.color(*GLASS, gloss=0.9); m.quad((xe + (0.01 if xe > 0 else -0.01), -0.6, 1.4), (xe + (0.01 if xe > 0 else -0.01), 0.6, 1.4), (xe + (0.01 if xe > 0 else -0.01), 0.6, 2.9), (xe + (0.01 if xe > 0 else -0.01), -0.6, 2.9))
# roof (overhanging), light grey
m.color(0.78, 0.79, 0.80, gloss=0.3); m.box((X0 + X1) / 2, 0, 3.22, (X1 - X0) / 2 + 0.3, W + 0.15, 0.08)
# wheelhouse on the roof, towards the bow, with the line-number board on its front
m.color(*WHITE, gloss=0.4); m.box(4.6, 0, 3.95, 1.35, 1.05, 0.65)
m.color(*GLASS, gloss=0.9); m.box(4.62, 0, 4.1, 1.37, 1.07, 0.32)
m.color(0.95, 0.78, 0.10, gloss=0.3); m.box(5.98, 0, 4.72, 0.04, 0.55, 0.16)      # ACTV yellow line board
m.color(0.75, 0.76, 0.77, gloss=0.3); m.box(4.6, 0, 4.64, 1.45, 1.15, 0.05)
# mast with the navigation light, horn
m.color(0.85, 0.85, 0.85, gloss=0.4); m.tube([(3.4, 0, 3.3), (3.4, 0, 6.0)], 0.05, 6)
m.color(1.0, 0.95, 0.85, gloss=0.6); m.box(3.4, 0, 6.05, 0.08, 0.08, 0.08)
# railings around the open bow and aft decks
m.color(0.82, 0.83, 0.84, gloss=0.6)
for (xa, xb) in ((X1 + 0.3, L / 2 - 1.2), (-L / 2 + 0.4, X0 - 0.3)):
    seg = [x for x in xs if xa <= x <= xb]
    for s in (-1, 1):
        pts = [(x, s * (half_beam(x) - 0.08), 2.05) for x in seg]
        if len(pts) > 1:
            m.tube(pts, 0.035, 6)
            for x in seg[::3]: m.tube([(x, s * (half_beam(x) - 0.08), 1.05), (x, s * (half_beam(x) - 0.08), 2.05)], 0.025, 5, caps=False)
# aft benches and the bow bollards
m.color(0.55, 0.38, 0.22, gloss=0.3)
for s in (-1, 1): m.box(-8.6, s * 1.45, 1.45, 2.4, 0.25, 0.08)
m.color(*DARK, gloss=0.4)
for x in (9.0, -10.6):
    for s in (-1, 1): m.tube([(x, s * 1.3, 1.05), (x, s * 1.3, 1.45)], 0.12, 8)
out = os.path.join(os.path.dirname(__file__), '../../app/src/main/assets/live/vaporetto.mesh')
os.makedirs(os.path.dirname(out), exist_ok=True)
print('vertices', m.write(out))

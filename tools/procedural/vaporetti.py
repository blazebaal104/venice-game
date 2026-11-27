"""ACTV waterbuses, built to their real outer dimensions (fender included in the beam):
  motobattello  - the classic "vaporetto" (lines 1, 2, ...): 23.9 m x 4.2 m, open bow and aft decks, central cabin,
                  raised wheelhouse;
  motoscafo     - slimmer and lower, for the circular lines under low bridges (3, 4.1/4.2, 5.1/5.2, 6):
                  21.1 m x 3.75 m, long low enclosed cabin, small wheelhouse at the front;
  motonave      - the big lagoon boats (lines 11-19, "caffettiera" style foraneo): 31 m x 5.5 m, main cabin plus an
                  open upper deck with railings and the wheelhouse on top.
+x = bow, z = metres above the waterline. Run: python3 tools/procedural/vaporetti.py -> assets/live/*.mesh"""
import math, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from meshlib import *

WHITE = (0.90, 0.91, 0.90); GREY = (0.30, 0.32, 0.34); DARK = (0.06, 0.07, 0.08); GLASS = (0.10, 0.14, 0.17)
RAIL = (0.82, 0.83, 0.84); ROOF = (0.78, 0.79, 0.80); YELLOW = (0.95, 0.78, 0.10)

def hull(m, L, B, deck=1.0, bow_round=0.45, fender=0.16, N=64):
    hb_max = B / 2 - fender
    def hb(x):
        t = x / (L / 2)
        if t > bow_round: return hb_max * math.sqrt(max(0.0, 1 - ((t - bow_round) / (1 - bow_round)) ** 2.2))
        if t < -0.88: return hb_max * (0.86 + 0.14 * math.sqrt(max(0.0, 1 - ((-t - 0.88) / 0.12) ** 2)))
        return hb_max
    xs = [-L / 2 + L * i / N for i in range(N + 1)]
    for i in range(N):
        a, b = xs[i], xs[i + 1]
        for s in (-1, 1):
            m.color(*GREY, gloss=0.3); m.quad((a, s * hb(a) * 0.86, -0.55), (b, s * hb(b) * 0.86, -0.55), (b, s * hb(b), 0.15), (a, s * hb(a), 0.15))
            m.color(*WHITE, gloss=0.45); m.quad((a, s * hb(a), 0.15), (b, s * hb(b), 0.15), (b, s * hb(b), deck), (a, s * hb(a), deck))
        m.color(*GREY); m.quad((a, -hb(a) * 0.86, -0.55), (b, -hb(b) * 0.86, -0.55), (b, hb(b) * 0.86, -0.55), (a, hb(a) * 0.86, -0.55))
        m.mat(PLANKS, 0.55, 0.52, 0.5); m.quad((a, -hb(a), deck), (b, -hb(b), deck), (b, hb(b), deck), (a, hb(a), deck))
    m.color(*WHITE, gloss=0.45); h0 = hb(xs[0]); m.quad((xs[0], -h0, -0.55), (xs[0], h0, -0.55), (xs[0], h0, deck), (xs[0], -h0, deck))
    # the heavy black rubber fender, inside the overall beam
    m.color(*DARK, gloss=0.2)
    ring = [(x, hb(x) + fender * 0.5, deck - 0.3) for x in xs] + [(x, -hb(x) - fender * 0.5, deck - 0.3) for x in reversed(xs)]
    m.tube(ring + [ring[0]], fender * 0.85, 8, caps=False)
    return hb, xs

def cabin(m, x0, x1, w, z0, z1, win0, win1, pitch=1.45, rounded_front=False):
    for s in (-1, 1):
        m.color(*WHITE, gloss=0.4)
        m.quad((x0, s * w, z0), (x1, s * w, z0), (x1, s * w, win0), (x0, s * w, win0))
        m.quad((x0, s * w, win1), (x1, s * w, win1), (x1, s * w, z1), (x0, s * w, z1))
        m.color(*GLASS, gloss=0.9); m.quad((x0, s * w * 1.002, win0), (x1, s * w * 1.002, win0), (x1, s * w * 1.002, win1), (x0, s * w * 1.002, win1))
        m.color(*WHITE, gloss=0.4)
        x = x0 + pitch * 0.8
        while x < x1 - 0.4: m.box(x, s * w * 1.004, (win0 + win1) / 2, 0.06, 0.02, (win1 - win0) / 2); x += pitch
    for xe, sg in ((x0, -1), (x1, 1)):
        m.color(*WHITE, gloss=0.4); m.quad((xe, -w, z0), (xe, w, z0), (xe, w, z1), (xe, -w, z1))
        m.color(*GLASS, gloss=0.9); m.quad((xe + sg * 0.01, -w * 0.6, win0), (xe + sg * 0.01, w * 0.6, win0), (xe + sg * 0.01, w * 0.6, win1), (xe + sg * 0.01, -w * 0.6, win1))
    m.color(*ROOF, gloss=0.3); m.box((x0 + x1) / 2, 0, z1 + 0.07, (x1 - x0) / 2 + 0.25, w + 0.12, 0.07)

def railing(m, hb, xs, xa, xb, z0, h=1.0, inset=0.08):
    seg = [x for x in xs if xa <= x <= xb]
    if len(seg) < 2: return
    m.color(*RAIL, gloss=0.6)
    for s in (-1, 1):
        m.tube([(x, s * (hb(x) - inset), z0 + h) for x in seg], 0.035, 6)
        for x in seg[::3]: m.tube([(x, s * (hb(x) - inset), z0), (x, s * (hb(x) - inset), z0 + h)], 0.025, 5, caps=False)

def wheelhouse(m, x, w, d, z0, h):
    m.color(*WHITE, gloss=0.4); m.box(x, 0, z0 + h / 2, d, w, h / 2)
    m.color(*GLASS, gloss=0.9); m.box(x + 0.02, 0, z0 + h * 0.62, d + 0.02, w + 0.02, h * 0.22)
    m.color(*YELLOW, gloss=0.3); m.box(x + d + 0.03, 0, z0 + h - 0.12, 0.04, min(0.55, w * 0.6), 0.14)   # line board
    m.color(*ROOF, gloss=0.3); m.box(x, 0, z0 + h + 0.04, d + 0.1, w + 0.1, 0.04)
    m.color(0.85, 0.85, 0.85, gloss=0.4); m.tube([(x - d * 0.6, 0, z0 + h), (x - d * 0.6, 0, z0 + h + 1.6)], 0.045, 6)
    m.color(1.0, 0.95, 0.85, gloss=0.6); m.box(x - d * 0.6, 0, z0 + h + 1.65, 0.07, 0.07, 0.07)

def motobattello():
    m = Mesh(); L, B = 23.9, 4.2
    hb, xs = hull(m, L, B, deck=1.0)
    cabin(m, -5.4, 6.0, 1.72, 1.0, 3.05, 1.7, 2.65)
    wheelhouse(m, 4.5, 1.0, 1.3, 3.12, 1.25)
    railing(m, hb, xs, 6.3, L / 2 - 1.0, 1.0); railing(m, hb, xs, -L / 2 + 0.4, -5.7, 1.0)
    m.color(0.55, 0.38, 0.22, gloss=0.3)
    for s in (-1, 1): m.box(-8.6, s * 1.35, 1.4, 2.4, 0.22, 0.07)
    return m

def motoscafo():
    m = Mesh(); L, B = 21.1, 3.75
    hb, xs = hull(m, L, B, deck=0.85, bow_round=0.35)
    cabin(m, -6.8, 6.2, 1.5, 0.85, 2.45, 1.35, 2.1, pitch=1.3)
    # integrated low wheelhouse at the front of the cabin
    m.color(*WHITE, gloss=0.4); m.box(6.9, 0, 1.75, 0.7, 1.1, 0.9)
    m.color(*GLASS, gloss=0.9); m.box(6.92, 0, 2.05, 0.72, 1.12, 0.3)
    m.color(*YELLOW, gloss=0.3); m.box(7.63, 0, 2.5, 0.04, 0.5, 0.13)
    railing(m, hb, xs, -L / 2 + 0.4, -7.1, 0.85, 0.9); railing(m, hb, xs, 7.7, L / 2 - 1.0, 0.85, 0.9)
    return m

def motonave():
    m = Mesh(); L, B = 31.0, 5.5
    hb, xs = hull(m, L, B, deck=1.2, bow_round=0.5, fender=0.2)
    cabin(m, -9.5, 8.5, 2.35, 1.2, 3.4, 1.9, 2.95, pitch=1.6)
    # open upper deck on the cabin roof, railings all round, wheelhouse forward
    railing(m, lambda x: 2.45, [-9.4 + 0.5 * i for i in range(37)], -9.4, 8.4, 3.55, 1.0, inset=0)
    m.color(*RAIL, gloss=0.6)
    for xe in (-9.4, 8.4): m.tube([(xe, -2.45, 4.55), (xe, 2.45, 4.55)], 0.035, 6)
    wheelhouse(m, 6.4, 1.3, 1.6, 3.55, 1.5)
    m.color(0.55, 0.38, 0.22, gloss=0.3)
    for k in range(4): m.box(-6 + k * 3.2, 0, 3.85, 0.25, 1.6, 0.25)          # benches on the upper deck
    m.color(*DARK, gloss=0.3); m.box(-6.8, 0, 4.6, 0.35, 0.35, 1.0)           # funnel
    railing(m, hb, xs, 8.8, L / 2 - 1.2, 1.2); railing(m, hb, xs, -L / 2 + 0.4, -9.8, 1.2)
    return m

out = os.path.join(os.path.dirname(__file__), '../../app/src/main/assets/live')
os.makedirs(out, exist_ok=True)
for name, f in (('motobattello', motobattello), ('motoscafo', motoscafo), ('motonave', motonave)):
    print(name, f().write(os.path.join(out, name + '.mesh')))

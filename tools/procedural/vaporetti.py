"""ACTV waterbuses, modelled on reference photos of the real fleet, one livery per type:
  motobattello  - the classic "vaporetto" (lines 1, 2, N...): cream topsides over a dark green band, black hull,
                  red-brown antifouling at the waterline; open fore and aft decks with railings capped in wood; a grey
                  wheelhouse with big windows forward of the long windowed cabin; light-grey roof with red life rafts;
                  black vertical rubber bump stops along the hull. (Actv 43, 90, 5...)
  motoscafo     - lower and slimmer, for the circular lines under the low bridges (3, 4.1/4.2, 5.1/5.2, 6, 7...):
                  white upperworks, green/black hull with a red stripe, long low cabin, a black rubber bumper on the
                  nose, red rafts on the roof. (VE 9021, 275...)
  lancione      - the "Gran Turismo" type (lines 9, 20...): white with a black hull and red stripe, a raised wheelhouse
                  amidships, open aft deck. (VE 8001 "207", "203")
  motonave      - the big lagoon boats (lines 11-19): main cabin, open upper deck with railings, wheelhouse on top.
All are built at their real size, then scaled by SCALE (slightly under 1:1, they read better next to the player boat).
+x = bow, z = metres above the waterline. The exhaust outlet position of each type is written to fleet.txt
(LiveBoats reads it for the smoke). Run: python3 tools/procedural/vaporetti.py -> assets/live/*"""
import math, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from meshlib import *

SCALE = 0.9
CREAM = (0.86, 0.83, 0.71); WHITE = (0.91, 0.92, 0.91); GREEN = (0.08, 0.26, 0.18); BLACK = (0.05, 0.055, 0.06)
ANTIFOUL = (0.42, 0.14, 0.09); RED = (0.72, 0.10, 0.08); RAFT = (0.80, 0.16, 0.08)
GLASS = (0.08, 0.11, 0.13); WHEEL_GREY = (0.56, 0.58, 0.59); ROOF = (0.80, 0.81, 0.81); RAIL = (0.78, 0.79, 0.80)
WOOD = (0.46, 0.29, 0.16); RUBBER = (0.04, 0.04, 0.045); YELLOW = (0.95, 0.78, 0.10)

def hull(m, L, B, deck, bands, bow_round=0.45, fender=0.16, N=64, nose_bumper=False, bump_stops=0):
    """bands: list of (z0, z1, colour, gloss) up the topsides from the waterline (z=0) to the deck edge."""
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
            m.color(*ANTIFOUL, gloss=0.2); m.quad((a, s * hb(a) * 0.86, -0.55), (b, s * hb(b) * 0.86, -0.55), (b, s * hb(b), 0.12), (a, s * hb(a), 0.12))
            for z0, z1, c, g in bands:
                m.color(*c, gloss=g); m.quad((a, s * hb(a), z0), (b, s * hb(b), z0), (b, s * hb(b), z1), (a, s * hb(a), z1))
        m.color(*ANTIFOUL); m.quad((a, -hb(a) * 0.86, -0.55), (b, -hb(b) * 0.86, -0.55), (b, hb(b) * 0.86, -0.55), (a, hb(a) * 0.86, -0.55))
        m.mat(PLANKS, 0.55, 0.52, 0.5); m.quad((a, -hb(a), deck), (b, -hb(b), deck), (b, hb(b), deck), (a, hb(a), deck))
    h0 = hb(xs[0])
    for z0, z1, c, g in [(-0.55, 0.12, ANTIFOUL, 0.2)] + bands:
        m.color(*c, gloss=g); m.quad((xs[0], -h0, z0), (xs[0], h0, z0), (xs[0], h0, z1), (xs[0], -h0, z1))
    # the heavy black rubber fender round the deck edge
    m.color(*RUBBER, gloss=0.15)
    ring = [(x, hb(x) + fender * 0.5, deck - 0.3) for x in xs] + [(x, -hb(x) - fender * 0.5, deck - 0.3) for x in reversed(xs)]
    m.tube(ring + [ring[0]], fender * 0.85, 8, caps=False)
    if nose_bumper:
        m.tube([(L / 2 - 0.9, -0.7, deck - 0.35), (L / 2 + 0.12, 0, deck - 0.35), (L / 2 - 0.9, 0.7, deck - 0.35)], 0.22, 8)
    for k in range(bump_stops):           # black vertical rubber bump stops down the sides
        x = -L / 2 + 2.2 + k * (L - 5.0) / max(1, bump_stops - 1)
        for s in (-1, 1): m.box(x, s * (hb(x) + 0.05), deck * 0.45, 0.12, 0.07, deck * 0.42)
    return hb, xs

def cabin(m, x0, x1, w, z0, z1, win0, win1, body=CREAM, pitch=1.45, posts=True):
    for s in (-1, 1):
        m.color(*body, gloss=0.4)
        m.quad((x0, s * w, z0), (x1, s * w, z0), (x1, s * w, win0), (x0, s * w, win0))
        m.quad((x0, s * w, win1), (x1, s * w, win1), (x1, s * w, z1), (x0, s * w, z1))
        m.color(*GLASS, gloss=0.9); m.quad((x0, s * w * 1.002, win0), (x1, s * w * 1.002, win0), (x1, s * w * 1.002, win1), (x0, s * w * 1.002, win1))
        if posts:
            m.color(*body, gloss=0.4)
            x = x0 + pitch * 0.8
            while x < x1 - 0.4: m.box(x, s * w * 1.004, (win0 + win1) / 2, 0.07, 0.02, (win1 - win0) / 2); x += pitch
    for xe, sg in ((x0, -1), (x1, 1)):
        m.color(*body, gloss=0.4); m.quad((xe, -w, z0), (xe, w, z0), (xe, w, z1), (xe, -w, z1))
        m.color(*GLASS, gloss=0.9); m.quad((xe + sg * 0.01, -w * 0.6, win0), (xe + sg * 0.01, w * 0.6, win0), (xe + sg * 0.01, w * 0.6, win1), (xe + sg * 0.01, -w * 0.6, win1))
    m.color(*ROOF, gloss=0.3); m.box((x0 + x1) / 2, 0, z1 + 0.07, (x1 - x0) / 2 + 0.25, w + 0.12, 0.07)

def railing(m, hb, xs, xa, xb, z0, h=1.0, inset=0.08, cap=WOOD):
    seg = [x for x in xs if xa <= x <= xb]
    if len(seg) < 2: return
    for s in (-1, 1):
        m.color(*cap, gloss=0.35); m.tube([(x, s * (hb(x) - inset), z0 + h) for x in seg], 0.045, 6)
        m.color(*RAIL, gloss=0.6)
        m.tube([(x, s * (hb(x) - inset), z0 + h * 0.5) for x in seg], 0.02, 5)
        for x in seg[::3]: m.tube([(x, s * (hb(x) - inset), z0), (x, s * (hb(x) - inset), z0 + h)], 0.025, 5, caps=False)

def wheelhouse(m, x, w, d, z0, h, body=WHEEL_GREY, mast=True):
    m.color(*body, gloss=0.4); m.box(x, 0, z0 + h / 2, d, w, h / 2)
    m.color(*GLASS, gloss=0.9)
    m.box(x + 0.02, 0, z0 + h * 0.6, d + 0.02, w + 0.02, h * 0.28)                   # big windows all round
    m.color(*YELLOW, gloss=0.3); m.box(x + d + 0.03, 0, z0 + h - 0.1, 0.04, min(0.55, w * 0.6), 0.1)   # line board
    m.color(*ROOF, gloss=0.3); m.box(x, 0, z0 + h + 0.04, d + 0.12, w + 0.12, 0.04)
    if mast:
        m.color(0.85, 0.85, 0.85, gloss=0.4); m.tube([(x - d * 0.6, 0, z0 + h), (x - d * 0.6, 0, z0 + h + 1.5)], 0.045, 6)
        m.color(1.0, 0.95, 0.85, gloss=0.6); m.box(x - d * 0.6, 0, z0 + h + 1.55, 0.07, 0.07, 0.07)

def rafts(m, xs_, z, y=0.55):
    for x in xs_:
        for s in (-1, 1):
            m.color(*RAFT, gloss=0.35); m.tube([(x - 0.55, s * y, z + 0.28), (x + 0.55, s * y, z + 0.28)], 0.28, 10)
            m.color(0.9, 0.9, 0.9, gloss=0.3); m.box(x, s * y, z + 0.28, 0.05, 0.3, 0.3)

def exhaust(m, x, y, z, h=0.7):
    m.color(*BLACK, gloss=0.4); m.tube([(x, y, z), (x, y, z + h)], 0.09, 8)
    return (x, y, z + h)

def motobattello():
    m = Mesh(); L, B = 23.9, 4.2
    hb, xs = hull(m, L, B, 1.0, [(0.12, 0.42, BLACK, 0.4), (0.42, 0.62, GREEN, 0.45), (0.62, 1.0, CREAM, 0.45)], bump_stops=7)
    cabin(m, -5.6, 3.6, 1.72, 1.0, 3.0, 1.62, 2.65)
    wheelhouse(m, 4.6, 1.25, 1.0, 1.0, 2.35)                  # grey wheelhouse at deck level, ahead of the cabin
    railing(m, hb, xs, 5.8, L / 2 - 1.0, 1.0); railing(m, hb, xs, -L / 2 + 0.4, -5.9, 1.0)
    rafts(m, (-3.4, 1.6), 3.07)
    m.color(*WOOD, gloss=0.3)
    for s in (-1, 1): m.box(-8.6, s * 1.35, 1.4, 2.4, 0.22, 0.07)       # aft deck benches
    return m, exhaust(m, -5.2, 0.9, 3.07)

def motoscafo():
    m = Mesh(); L, B = 21.1, 3.75
    hb, xs = hull(m, L, B, 0.85, [(0.12, 0.38, BLACK, 0.4), (0.38, 0.5, RED, 0.45), (0.5, 0.62, GREEN, 0.4), (0.62, 0.85, WHITE, 0.45)],
                  bow_round=0.35, nose_bumper=True, bump_stops=6)
    cabin(m, -6.8, 6.2, 1.5, 0.85, 2.4, 1.35, 2.08, body=WHITE, pitch=1.3)
    m.color(*WHITE, gloss=0.4); m.box(6.9, 0, 1.72, 0.7, 1.1, 0.87)
    m.color(*GLASS, gloss=0.9); m.box(6.92, 0, 2.02, 0.72, 1.12, 0.3)
    m.color(*YELLOW, gloss=0.3); m.box(7.63, 0, 2.45, 0.04, 0.5, 0.12)
    rafts(m, (-4.0, -0.6, 2.8), 2.47, 0.6)
    railing(m, hb, xs, -L / 2 + 0.4, -7.1, 0.85, 0.9, cap=RAIL); railing(m, hb, xs, 7.7, L / 2 - 1.0, 0.85, 0.9, cap=RAIL)
    return m, exhaust(m, -6.5, 0.8, 2.47, 0.45)

def lancione():
    m = Mesh(); L, B = 19.6, 4.3
    hb, xs = hull(m, L, B, 0.95, [(0.12, 0.45, BLACK, 0.4), (0.45, 0.58, RED, 0.45), (0.58, 0.95, WHITE, 0.45)], bow_round=0.4, bump_stops=5)
    cabin(m, -2.6, 6.8, 1.75, 0.95, 2.55, 1.5, 2.25, body=WHITE, pitch=1.25)
    wheelhouse(m, -1.5, 1.3, 1.1, 2.62, 1.35, body=WHITE)      # raised wheelhouse amidships, on the cabin roof
    railing(m, hb, xs, -L / 2 + 0.4, -3.0, 0.95, 1.0, cap=RAIL)  # open aft deck
    railing(m, hb, xs, 7.2, L / 2 - 0.9, 0.95, 0.9, cap=RAIL)
    rafts(m, (3.2, 5.6), 2.62, 0.7)
    m.color(*WOOD, gloss=0.3)
    for s in (-1, 1): m.box(-6.2, s * 1.4, 1.35, 2.6, 0.22, 0.07)
    return m, exhaust(m, -2.4, -0.9, 2.62, 0.6)

def motonave():
    m = Mesh(); L, B = 31.0, 5.5
    hb, xs = hull(m, L, B, 1.2, [(0.12, 0.5, BLACK, 0.4), (0.5, 0.72, GREEN, 0.45), (0.72, 1.2, WHITE, 0.45)], bow_round=0.5, fender=0.2, bump_stops=9)
    cabin(m, -9.5, 8.5, 2.35, 1.2, 3.4, 1.9, 2.95, body=WHITE, pitch=1.6)
    railing(m, lambda x: 2.45, [-9.4 + 0.5 * i for i in range(37)], -9.4, 8.4, 3.55, 1.0, inset=0, cap=RAIL)
    m.color(*RAIL, gloss=0.6)
    for xe in (-9.4, 8.4): m.tube([(xe, -2.45, 4.55), (xe, 2.45, 4.55)], 0.035, 6)
    wheelhouse(m, 6.4, 1.3, 1.6, 3.55, 1.5, body=WHITE)
    m.color(*WOOD, gloss=0.3)
    for k in range(4): m.box(-6 + k * 3.2, 0, 3.85, 0.25, 1.6, 0.25)
    rafts(m, (-8.0, 2.5), 3.55, 1.9)
    m.color(*BLACK, gloss=0.3); m.box(-6.8, 0, 4.6, 0.35, 0.35, 1.0)           # funnel
    railing(m, hb, xs, 8.8, L / 2 - 1.2, 1.2, cap=RAIL); railing(m, hb, xs, -L / 2 + 0.4, -9.8, 1.2, cap=RAIL)
    return m, (-6.8, 0.0, 5.6)

def scaled(m, k):
    m.v = [(v[0] * k, v[1] * k, v[2] * k) + tuple(v[3:]) for v in m.v]
    return m

out = os.path.join(os.path.dirname(__file__), '../../app/src/main/assets/live')
os.makedirs(out, exist_ok=True)
lines = ["# type length beam exhaust_x exhaust_y exhaust_z  (metres, scaled; written by tools/procedural/vaporetti.py)"]
dims = {'motobattello': (23.9, 4.2), 'motoscafo': (21.1, 3.75), 'motonave': (31.0, 5.5), 'lancione': (19.6, 4.3)}
for name, f in (('motobattello', motobattello), ('motoscafo', motoscafo), ('motonave', motonave), ('lancione', lancione)):
    m, ex = f()
    print(name, scaled(m, SCALE).write(os.path.join(out, name + '.mesh')))
    L, B = dims[name]
    lines.append(f"{name} {L * SCALE:.2f} {B * SCALE:.2f} {ex[0] * SCALE:.2f} {ex[1] * SCALE:.2f} {ex[2] * SCALE:.2f}")
open(os.path.join(out, 'fleet.txt'), 'w').write('\n'.join(lines) + '\n')

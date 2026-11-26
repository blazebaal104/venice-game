"""Ponte della Costituzione (Santiago Calatrava, 2008) built from its published dimensions:
94 m long, 81 m central span (arch radius ~180 m), deck rising from 3.2 m at the springings to 9.28 m at the crown,
5.58 m wide at the springings widening to 9.38 m at the crown, the stairs fanning out to 17.68 m on the fondamenta.
Steps: trachyte with Istrian-stone nosings (the glass treads were replaced with stone from December 2024).
Structure: red steel box spine + two lateral tubular arches tied by transverse ribs ("fish bone" seen from the canal),
glass parapets with a bronze handrail, abutments clad in Istrian stone.
Local frame: +x along the walkway (centre at 0), +y across, z metres above mean water (fondamenta at 1.1 m).
Run:  python3 tools/procedural/costituzione.py  -> app/src/main/assets/bridges/bridge_costituzione.mesh"""
import math, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from meshlib import *

HALF_SPAN = 40.5          # springings (81 m span)
HALF_LEN = 51.0           # foot of the stairs on the fondamenta (OSM way is ~102 m)
Z_SPRING, Z_CROWN, Z_QUAY = 3.2, 9.28, 1.12
RISER = 0.155

def deck(x):
    a = abs(x)
    if a <= HALF_SPAN: return Z_CROWN - (Z_CROWN - Z_SPRING) * (a / HALF_SPAN) ** 2
    t = (a - HALF_SPAN) / (HALF_LEN - HALF_SPAN)          # the end flights: steeper, down to the fondamenta
    return Z_SPRING + (Z_QUAY - Z_SPRING) * (t * 0.9 + 0.1 * t * t)

def half_width(x):
    a = abs(x)
    if a <= HALF_SPAN: return 4.69 - (4.69 - 2.79) * (a / HALF_SPAN) ** 1.6
    t = (a - HALF_SPAN) / (HALF_LEN - HALF_SPAN)
    return 2.79 + (8.84 - 2.79) * t ** 1.7                 # the stairs fan out onto the fondamenta

def depth(x):                                              # structural depth below the deck
    a = min(abs(x), HALF_SPAN)
    return 0.875 + (2.084 - 0.875) * (1 - (a / HALF_SPAN) ** 2)

m = Mesh()
RED = (0.55, 0.09, 0.06)
BRONZE = (0.42, 0.30, 0.17)
GLASS = (0.16, 0.22, 0.23)

# ---------------------------------------------------------------- steps
xs = [-HALF_LEN + i * 0.05 for i in range(int(2 * HALF_LEN / 0.05) + 1)]
q = [round(deck(x) / RISER) * RISER for x in xs]
runs = []                      # (xa, xb, z)
start = 0
for i in range(1, len(xs)):
    if q[i] != q[start] or i == len(xs) - 1:
        runs.append((xs[start], xs[i], q[start])); start = i
for (xa, xb, z) in runs:
    ha, hb = half_width(xa), half_width(xb)
    outer_left = abs(xa) > abs(xb)                         # nosing on the downhill (outer) edge
    nose = min(0.14, (xb - xa) * 0.4)
    if outer_left: na, nb = xa, xa + nose
    else: na, nb = xb - nose, xb
    hn_a, hn_b = half_width(na), half_width(nb)
    # trachyte tread
    ta, tb = (nb, xb) if outer_left else (xa, na)
    m.mat(TRACHYTE_DARK, 0.92, 0.92, 0.95)
    m.quad((ta, -half_width(ta), z), (tb, -half_width(tb), z), (tb, half_width(tb), z), (ta, half_width(ta), z))
    # Istrian nosing strip
    m.mat(ISTRIAN, 1.0, 0.99, 0.95)
    m.quad((na, -hn_a, z), (nb, -hn_b, z), (nb, hn_b, z), (na, hn_a, z))
# risers
for i in range(len(runs) - 1):
    x = runs[i][1]; z0, z1 = runs[i][2], runs[i + 1][2]
    h = half_width(x)
    m.mat(ISTRIAN, 0.96, 0.95, 0.92)
    m.quad((x, -h, z0), (x, h, z0), (x, h, z1), (x, -h, z1))

# ---------------------------------------------------------------- steel: edge girders, underside, spine, ribs, arches
N = 160
sx = [-HALF_SPAN - 1.2 + (2 * HALF_SPAN + 2.4) * i / N for i in range(N + 1)]
m.color(*RED, gloss=0.35)
for i in range(N):
    a, b = sx[i], sx[i + 1]
    for side in (-1, 1):
        ya, yb = side * half_width(a), side * half_width(b)
        # outer fascia of the deck edge
        m.quad((a, ya, deck(a) - 0.55), (b, yb, deck(b) - 0.55), (b, yb, deck(b) - 0.02), (a, ya, deck(a) - 0.02))
        # underside: from the edge down to the spine (a shallow V, the box section)
        m.quad((a, ya, deck(a) - 0.55), (b, yb, deck(b) - 0.55), (b, side * 1.1, deck(b) - depth(b)), (a, side * 1.1, deck(a) - depth(a)))
    m.quad((a, -1.1, deck(a) - depth(a)), (b, -1.1, deck(b) - depth(b)), (b, 1.1, deck(b) - depth(b)), (a, 1.1, deck(a) - depth(a)))
# central tubular arch under the spine and two lateral tubes along the edges
arc = [(x, 0.0, deck(x) - depth(x) - 0.35) for x in sx]
m.tube(arc, 0.55, 14)
for side in (-1, 1):
    m.tube([(x, side * (half_width(x) - 0.45), deck(x) - 0.85) for x in sx], 0.26, 10)
# transverse ribs every ~2.7 m ("fish bone")
k = -HALF_SPAN + 0.6
while k <= HALF_SPAN - 0.6:
    for side in (-1, 1):
        p0 = (k, 0.0, deck(k) - depth(k) - 0.1); p1 = (k, side * (half_width(k) - 0.45), deck(k) - 0.85)
        m.tube([p0, ((p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2, (p0[2] + p1[2]) / 2 + 0.25), p1], [0.16, 0.12, 0.1], 6, caps=False)
    k += 2.7

# ---------------------------------------------------------------- glass parapets + bronze handrail
for i in range(len(xs) - 1):
    a, b = xs[i], xs[i + 1]
    if i % 4: continue
    b = xs[min(i + 4, len(xs) - 1)]
    for side in (-1, 1):
        ya, yb = side * (half_width(a) - 0.06), side * (half_width(b) - 0.06)
        za, zb = deck(a), deck(b)
        m.color(*GLASS, gloss=0.95)
        m.quad((a, ya, za), (b, yb, zb), (b, yb, zb + 1.02), (a, ya, za + 1.02))
rail = lambda side: [(x, side * (half_width(x) - 0.06), deck(x) + 1.08) for x in xs[::8]] + [(xs[-1], side * (half_width(xs[-1]) - 0.06), deck(xs[-1]) + 1.08)]
m.color(*BRONZE, gloss=0.6)
for side in (-1, 1):
    m.tube(rail(side), 0.045, 8)
    # bronze posts every ~2 m
    for x in [-HALF_LEN + 0.3 + 2.0 * j for j in range(int(2 * HALF_LEN / 2.0))]:
        y = side * (half_width(x) - 0.06)
        m.box(x, y, deck(x) + 0.53, 0.03, 0.03, 0.53)
    # the lit strip at the foot of the glass
    m.color(0.85, 0.82, 0.7, gloss=0.5)
    m.tube([(x, side * (half_width(x) - 0.12), deck(x) + 0.06) for x in xs[::10]], 0.035, 6)
    m.color(*BRONZE, gloss=0.6)

# ---------------------------------------------------------------- Istrian-stone abutments under the end flights
m.mat(ISTRIAN_WORN, 0.97, 0.96, 0.92)
for sgn in (-1, 1):
    xa = sgn * (HALF_SPAN - 0.8)
    pts = [sgn * (HALF_SPAN - 0.8 + i * 0.25) for i in range(int((HALF_LEN - HALF_SPAN + 0.8) / 0.25) + 1)]
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        for side in (-1, 1):
            m.quad((a, side * half_width(a), -1.8), (b, side * half_width(b), -1.8), (b, side * half_width(b), deck(b) - 0.02), (a, side * half_width(a), deck(a) - 0.02))
    # face towards the canal
    h = half_width(xa) + 0.3
    m.quad((xa, -h, -1.8), (xa, h, -1.8), (xa, h, deck(xa) - depth(xa) + 0.2), (xa, -h, deck(xa) - depth(xa) + 0.2))

out = os.path.join(os.path.dirname(__file__), '../../app/src/main/assets/bridges/bridge_costituzione.mesh')
print('vertices', m.write(out))

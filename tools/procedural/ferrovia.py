"""Venezia Santa Lucia railway station (Mazzoni / Vallot / Perilli, completed 1952), the front passenger building:
a low, wide rationalist block in Istrian stone - solid flank pavilions (with the winged lions of St Mark), a long
stone band with a ribbon of square windows over a recessed glazed hall, a thin cornice and a flat roof - above the
12 wide steps to the Fondamenta Santa Lucia, covered by the glass-tile canopy ("the aquarium") on slender columns.
The rest of the OSM footprint (ticket hall, offices) is extruded behind it.
Local frame: origin at the facade's south corner (OSM vertex), +x along the facade (93 m), +y into the building,
-y towards the Grand Canal. z in metres above mean water (fondamenta 1.1 m).
Run:  python3 tools/procedural/ferrovia.py  -> app/src/main/assets/landmarks/ferrovia.mesh"""
import math, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from meshlib import *

# OSM footprint (world metres) and the facade edge direction
FOOT = [(-1418.3, 756.2), (-1360.1, 828.9), (-1395.1, 856.7), (-1469.7, 763.7), (-1460.3, 756.2), (-1456.9, 760.4),
        (-1451.1, 755.7), (-1427.3, 736.3), (-1414.0, 752.8)]
ROT = math.radians(51.3)
O = FOOT[0]
def local(p):
    dx, dy = p[0] - O[0], p[1] - O[1]
    return (dx * math.cos(ROT) + dy * math.sin(ROT), -dx * math.sin(ROT) + dy * math.cos(ROT))

G = 1.1                    # fondamenta
FLOOR = G + 12 * 0.15      # 12 steps up to the station floor
L = 93.0
TOP = 14.5
m = Mesh()
STONE = lambda: m.mat(ISTRIAN, 1.0, 0.99, 0.95)
GLASS = lambda: m.color(0.10, 0.14, 0.16, gloss=0.92)
BRONZE = lambda: m.color(0.36, 0.27, 0.17, gloss=0.55)

# ---------------------------------------------------------------- the rest of the footprint, extruded
poly = [local(p) for p in FOOT]
m.mat(ISTRIAN_WORN, 0.93, 0.91, 0.86)
for i in range(len(poly)):
    a, b = poly[i], poly[(i + 1) % len(poly)]
    m.quad((a[0], a[1], -0.5), (b[0], b[1], -0.5), (b[0], b[1], 10.0), (a[0], a[1], 10.0))
# flat roof (fan from the centroid: the footprint is convex enough)
cx = sum(p[0] for p in poly) / len(poly); cy = sum(p[1] for p in poly) / len(poly)
m.mat(LEAD, 0.75, 0.75, 0.75)
for i in range(len(poly)):
    a, b = poly[i], poly[(i + 1) % len(poly)]
    m.tri((cx, cy, 10.0), (a[0], a[1], 10.0), (b[0], b[1], 10.0))

# ---------------------------------------------------------------- front block
D = 14.0
STONE()
for (x0, x1) in ((0.0, 7.0), (L - 7.0, L)):                 # flank pavilions, full height
    m.box((x0 + x1) / 2, D / 2, (G - 0.5 + TOP) / 2, (x1 - x0) / 2, D / 2, (TOP - G + 0.5) / 2)
m.box(L / 2, D / 2, (8.0 + TOP) / 2, (L - 14.0) / 2, D / 2, (TOP - 8.0) / 2)      # the long stone band
m.box(L / 2, D / 2 + 1.0, (G - 0.5 + FLOOR) / 2, (L - 14.0) / 2, D / 2 - 1.0, (FLOOR - G + 0.5) / 2)   # podium
# cornice and roof
m.box(L / 2, D / 2 - 0.3, TOP + 0.2, L / 2 + 0.4, D / 2 + 0.3, 0.2)
m.mat(LEAD, 0.7, 0.7, 0.72); m.box(L / 2, D / 2, TOP + 0.42, L / 2 - 0.3, D / 2 - 0.3, 0.02)
# the band: two long ribbon windows (thin bronze mullions every 1.55 m) between courses of stone
for z0, z1 in ((9.2, 10.6), (11.6, 13.0)):
    GLASS(); m.box(L / 2, -0.03, (z0 + z1) / 2, (L - 16.0) / 2, 0.05, (z1 - z0) / 2)
    BRONZE()
    x = 8.0
    while x <= L - 8.0 + 0.01:
        m.box(x, -0.09, (z0 + z1) / 2, 0.05, 0.05, (z1 - z0) / 2); x += 1.55
    m.box(L / 2, -0.09, (z0 + z1) / 2, (L - 16.0) / 2, 0.04, 0.035)
# horizontal stone coursing: shallow joints every 0.6 m on the band and the flank pavilions
m.color(0.62, 0.61, 0.58, gloss=0.1)
z = 8.3
while z < TOP:
    if not (9.1 < z < 10.7 or 11.5 < z < 13.1): m.box(L / 2, -0.02, z, (L - 14.0) / 2, 0.03, 0.015)
    z += 0.6
for xc in (3.5, L - 3.5):
    z = G + 0.6
    while z < TOP:
        if not (8.8 < z < 12.4): m.box(xc, -0.02, z, 3.5, 0.03, 0.015)
        z += 0.6
# the flank pavilions: a tall narrow window on each return and the door to the side wings
GLASS()
for xc in (3.5, L - 3.5):
    m.box(xc, -0.03, G + 3.4, 1.3, 0.05, 2.3)
BRONZE()
for xc in (3.5, L - 3.5):
    m.box(xc, -0.08, G + 3.4, 1.38, 0.04, 0.05); m.box(xc, -0.08, G + 5.75, 1.38, 0.05, 0.08)
# recessed glazed hall: glass wall 2 m behind the facade line, bronze mullions
m.quad((7.0, 2.0, FLOOR), (L - 7.0, 2.0, FLOOR), (L - 7.0, 2.0, 8.0), (7.0, 2.0, 8.0))
BRONZE()
x = 7.0
while x <= L - 7.0 + 0.01:
    m.box(x, 1.95, (FLOOR + 8.0) / 2, 0.07, 0.07, (8.0 - FLOOR) / 2); x += 3.1
m.box(L / 2, 1.95, 5.6, (L - 14.0) / 2, 0.06, 0.06)            # transom
m.box(L / 2, 1.95, FLOOR + 2.6, (L - 14.0) / 2, 0.06, 0.06)     # door head
# the entrance doors: darker bronze panels at the bottom of every other bay
m.color(0.22, 0.17, 0.11, gloss=0.5)
x = 7.0 + 3.1
while x < L - 7.0 - 1.0:
    m.box(x + 1.55, 1.93, FLOOR + 0.25, 1.45, 0.04, 0.25); x += 6.2
STONE(); m.box(L / 2, 1.0, 7.85, (L - 14.0) / 2, 1.0, 0.15)     # soffit edge under the band
# soffit of the recess
m.mat(ISTRIAN_WORN, 0.9, 0.9, 0.88); m.quad((7.0, 0.0, 7.99), (L - 7.0, 0.0, 7.99), (L - 7.0, 2.0, 7.99), (7.0, 2.0, 7.99))
# winged lions of St Mark on the flanks: bronze relief panels
BRONZE()
for xc in (3.5, L - 3.5):
    m.box(xc, -0.08, 10.6, 2.1, 0.08, 1.7)
    m.box(xc, -0.16, 10.6, 1.4, 0.06, 1.1)

# ---------------------------------------------------------------- the 12 steps down to the fondamenta
SX0, SX1 = 9.0, L - 9.0
for k in range(12):
    y0 = -0.6 * (12 - k) ; y1 = y0 + 0.6
    z = G + 0.15 * (k + 1)
    STONE()
    m.box((SX0 + SX1) / 2, (y0 + y1) / 2 - 0.0, (G - 0.3 + z) / 2, (SX1 - SX0) / 2, 0.3, (z - G + 0.3) / 2)
# landing between the steps and the glass wall
m.box((SX0 + SX1) / 2, 1.0, (G + FLOOR) / 2 - 0.02, (SX1 - SX0) / 2, 1.0, (FLOOR - G) / 2)
# side cheeks
for xs in (SX0 - 0.3, SX1 + 0.3):
    m.box(xs, -3.6, (G + FLOOR + 0.3) / 2, 0.3, 3.6, (FLOOR + 0.3 - G) / 2)

# ---------------------------------------------------------------- the glass-tile canopy over the steps
# ("the aquarium"): three groups of six panels of glass tiles, separated by the support pillars, on a thin
# metal frame that juts out over the steps towards the canal
CZ = 7.55; CY0, CY1 = -9.0, 1.8
W = SX1 - SX0 + 2.0; X0 = L / 2 - W / 2
gap = 1.2; panel = (W - 2 * gap) / 18
for g in range(3):
    for k in range(6):
        x0 = X0 + g * (6 * panel + gap) + k * panel
        m.color(0.42, 0.56, 0.52, gloss=0.95)                             # glass tiles, greenish
        m.box(x0 + panel / 2, (CY0 + CY1) / 2, CZ, panel / 2 - 0.06, (CY1 - CY0) / 2, 0.05)
        BRONZE()
        m.box(x0, (CY0 + CY1) / 2, CZ - 0.05, 0.05, (CY1 - CY0) / 2, 0.09)  # ribs
        yy = CY0 + 0.9
        while yy < CY1:
            m.box(x0 + panel / 2, yy, CZ - 0.03, panel / 2, 0.025, 0.04); yy += 0.9   # tile grid
    if g < 2:                                                              # the solid strip over each pillar pair
        xb = X0 + (g + 1) * (6 * panel + gap) - gap / 2
        m.mat(ISTRIAN, 0.95, 0.95, 0.93); m.box(xb, (CY0 + CY1) / 2, CZ, gap / 2, (CY1 - CY0) / 2, 0.16)
BRONZE()
m.box(L / 2, CY0, CZ - 0.05, W / 2, 0.12, 0.3)                            # deep front fascia
m.box(L / 2, (CY0 + CY1) / 2, CZ - 0.05, 0.05, 0.05, 0.05)
for xe in (X0, X0 + W):
    m.box(xe, (CY0 + CY1) / 2, CZ - 0.05, 0.12, (CY1 - CY0) / 2, 0.3)     # side fascias
# FS emblem on the front fascia, centred
m.color(0.85, 0.86, 0.86, gloss=0.5); m.box(L / 2, CY0 - 0.14, CZ - 0.05, 1.1, 0.02, 0.2)
# pillars: pairs at the group joints and at both ends, standing on the steps' landing and on the fondamenta
m.color(0.80, 0.80, 0.78, gloss=0.4)
for g in range(4):
    xb = X0 + g * (6 * panel + gap) - (gap / 2 if 0 < g < 3 else 0) + (0.4 if g == 0 else -0.4 if g == 3 else 0)
    for yp, zb in ((CY0 + 1.0, G), (CY1 - 1.0, FLOOR)):
        m.tube([(xb, yp, zb), (xb, yp, CZ - 0.1)], 0.16, 10)
        m.box(xb, yp, zb + 0.06, 0.3, 0.3, 0.06)

# ---------------------------------------------------------------- forecourt furniture
# low stone benches along the cheeks of the steps
STONE()
for xs in (SX0 - 2.6, SX1 + 2.6):
    m.box(xs, -4.5, G + 0.23, 1.6, 0.45, 0.23)


out = os.path.join(os.path.dirname(__file__), '../../app/src/main/assets/landmarks/ferrovia.mesh')
print('vertices', m.write(out), 'origin', O, 'rot', math.degrees(ROT))

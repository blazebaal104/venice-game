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
# ribbon of square windows in the band
GLASS()
x = 8.6
while x < L - 8.0:
    m.box(x + 0.8, -0.04, 11.0, 0.8, 0.05, 0.8); x += 3.1
# recessed glazed hall: glass wall 2 m behind the facade line, bronze mullions
m.quad((7.0, 2.0, FLOOR), (L - 7.0, 2.0, FLOOR), (L - 7.0, 2.0, 8.0), (7.0, 2.0, 8.0))
BRONZE()
x = 7.0
while x <= L - 7.0 + 0.01:
    m.box(x, 1.95, (FLOOR + 8.0) / 2, 0.07, 0.07, (8.0 - FLOOR) / 2); x += 3.1
m.box(L / 2, 1.95, 5.6, (L - 14.0) / 2, 0.06, 0.06)            # transom
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
CZ = 7.55
m.color(0.30, 0.42, 0.40, gloss=0.9)
m.box(L / 2, -3.6, CZ, (SX1 - SX0) / 2 + 1.0, 4.4, 0.12)
BRONZE()
m.box(L / 2, -8.0, CZ - 0.18, (SX1 - SX0) / 2 + 1.0, 0.1, 0.22)        # front edge beam
x = SX0
while x <= SX1 + 0.01:
    m.box(x, -3.6, CZ - 0.16, 0.05, 4.4, 0.05); x += 2.0                 # the grid holding the glass tiles
m.color(0.80, 0.80, 0.78, gloss=0.4)
for k in range(9):
    xc = SX0 + 2.0 + k * (SX1 - SX0 - 4.0) / 8
    m.tube([(xc, -7.6, G), (xc, -7.6, CZ - 0.1)], 0.11, 8)              # slender columns on the fondamenta

out = os.path.join(os.path.dirname(__file__), '../../app/src/main/assets/landmarks/ferrovia.mesh')
print('vertices', m.write(out), 'origin', O, 'rot', math.degrees(ROT))

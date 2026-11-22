"""Campanile di San Marco - built from photo references (Wikimedia Commons: drone views of the top, south side, full
height from the Piazza, shaft close-up).  Local frame: +X along the face that runs at 19.4 deg (east-north-east),
+Y across, +Z up, z = 0 mean water (Piazza paving ~1.1 m).  Total height 98.6 m.

From the photos:
  * Istrian stone plinth; red-orange brick shaft (12.3 m) to ~49 m, every face divided by broad brick pilasters
    (lesene) with four narrow recessed channels; one column of small round-headed windows with white stone frames per face,
    staggered face to face (the internal ramp)
  * at the top of the shaft a white band with four round arches per face holding carved shells
  * white Istrian belfry: corner piers, four arched openings per face on columns, lion masks over the columns,
    dark interior with the bells; heavy cornice and a small balustrade above it
  * brick attic (set back) framed in white stone: winged lion of St Mark on two faces, Venezia/Justice between two lions
    on the other two
  * steep green copper pyramid with white stone ribs on the edges; the gilded Archangel Gabriel on top
"""
import bpy, bmesh, math, sys, os, random
from mathutils import Vector

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
OUT = ARGS[0] if len(ARGS) > 0 else "/home/claude/venice/campanile.blend"
ATLAS = ARGS[1] if len(ARGS) > 1 else "/home/claude/venice/campanile_atlas.png"
ATLAS_RES = int(ARGS[2]) if len(ARGS) > 2 else 2048
random.seed(5)
bpy.ops.wm.read_factory_settings(use_empty=True)
ARCH_CZ = 0.0; R_ARCH = 1.0                  # unused by this asset (archivolt material lives in the shared module)
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "lmcommon.py")).read())

# ---------------------------------------------------------------- dimensions (m)
Z0 = 0.6
PL_H = 2.0          # plinth top
SHAFT_W = 12.3
SH_TOP = 50.5       # brick shaft (12 m square, ~50 m)
FRIEZE_TOP = 52.9
BEL_BASE = 54.0     # belfry floor cornice top
BEL_TOP = 60.2
BEL_W = 12.5
ATT_W = 10.7
ATT_H = 6.4         # attic: about half as tall as it is wide
SP_W = 11.0; SP_APEX = 94.0
SP_BOT = 68.45      # spire base (checked against the built stack below)
ANGEL_TOP = 98.6

# ---------------------------------------------------------------- materials
def m_brick(nt, coord):
    """Red-orange brick shaft: tone varies in large patches, lighter weathered pilasters, soot streaks, grime at the base."""
    base = N(nt, "ShaderNodeTexNoise", i_Scale=0.25, i_Detail=5.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.52, 0.31, 0.22, 1), (0.64, 0.40, 0.28, 1), base.outputs["Fac"])
    fine = N(nt, "ShaderNodeTexNoise", i_Scale=6.0, i_Detail=4.0); L(nt, coord, fine.inputs["Vector"])
    c = mix(nt, c, (0.40, 0.24, 0.24, 1), mathn(nt, "MULTIPLY", ramp_val(nt, fine.outputs["Fac"], 0.55, 0.8), 0.45))   # purple-brown headers
    c = mix(nt, c, (0.66, 0.42, 0.30, 1), mathn(nt, "MULTIPLY", ramp_val(nt, fine.outputs["Fac"], 0.3, 0.15), 0.25))
    # coursing: faint horizontal bands (groups of courses) so the brick reads as laid, not stucco
    wv = N(nt, "ShaderNodeTexWave", wave_type="BANDS", bands_direction="Z"); wv.inputs["Scale"].default_value = 3.0
    wv.inputs["Distortion"].default_value = 0.6; L(nt, coord, wv.inputs["Vector"])
    c = mix(nt, c, (0.42, 0.22, 0.16, 1), mathn(nt, "MULTIPLY", ramp_val(nt, wv.outputs["Fac"], 0.75, 0.95), 0.25))
    # brick-scale speckle: cream, salmon and purple-brown bricks (+-15%)
    sp = N(nt, "ShaderNodeTexNoise", i_Scale=38.0, i_Detail=1.0); L(nt, coord, sp.inputs["Vector"])
    c = mix(nt, c, (0.74, 0.55, 0.40, 1), mathn(nt, "MULTIPLY", ramp_val(nt, sp.outputs["Fac"], 0.62, 0.72), 0.35))
    c = mix(nt, c, (0.38, 0.22, 0.22, 1), mathn(nt, "MULTIPLY", ramp_val(nt, sp.outputs["Fac"], 0.38, 0.28), 0.35))
    c = mix(nt, c, (0.70, 0.70, 0.70, 1), 1.0, "MULTIPLY")    # neutral: in-game the brick read as orange stucco (r2) / crimson (r3)
    sz = sep_z(nt, coord)
    c = mix(nt, c, (0.36, 0.24, 0.19, 1), mathn(nt, "MULTIPLY", ramp_val(nt, sz.outputs["Z"], 9.0, 1.0), 0.45))
    return weather(nt, coord, c, streak=0.55, tide=False, ao_min=0.45, grime=0.12)

def m_copper(nt, coord):
    """Verdigris copper sheets: seams, streaks of brighter and darker patina running down the slope."""
    base = N(nt, "ShaderNodeTexNoise", i_Scale=0.5, i_Detail=5.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.27, 0.42, 0.36, 1), (0.36, 0.52, 0.44, 1), base.outputs["Fac"])
    bg = N(nt, "ShaderNodeTexNoise", i_Scale=0.15, i_Detail=3.0); L(nt, coord, bg.inputs["Vector"])
    c = mix(nt, c, (0.40, 0.50, 0.50, 1), mathn(nt, "MULTIPLY", ramp_val(nt, bg.outputs["Fac"], 0.5, 0.7), 0.5))
    mp = N(nt, "ShaderNodeMapping"); L(nt, coord, mp.inputs[0]); mp.inputs["Scale"].default_value = (3.0, 3.0, 0.15)
    st = N(nt, "ShaderNodeTexNoise", i_Scale=3.0, i_Detail=6.0); L(nt, mp.outputs[0], st.inputs["Vector"])
    c = mix(nt, c, (0.22, 0.32, 0.27, 1), mathn(nt, "MULTIPLY", ramp_val(nt, st.outputs["Fac"], 0.5, 0.75), 0.6))
    c = mix(nt, c, (0.55, 0.36, 0.20, 1), mathn(nt, "MULTIPLY", ramp_val(nt, st.outputs["Fac"], 0.32, 0.12), 0.55))   # corrosion runs
    sz = sep_z(nt, coord)
    wv = N(nt, "ShaderNodeTexWave", wave_type="BANDS", bands_direction="Z"); wv.inputs["Scale"].default_value = 1.0
    L(nt, coord, wv.inputs["Vector"]); wv.inputs["Scale"].default_value = 0.5
    seam = ramp_val(nt, wv.outputs["Fac"], 0.06, 0.0)
    c = mix(nt, c, (0.18, 0.26, 0.22, 1), mathn(nt, "MULTIPLY", seam, 0.25))
    zn = N(nt, "ShaderNodeTexNoise", i_Scale=0.08, i_Detail=2.0); L(nt, coord, zn.inputs["Vector"])
    c = mix(nt, c, (0.24, 0.33, 0.36, 1), mathn(nt, "MULTIPLY", ramp_val(nt, zn.outputs["Fac"], 0.45, 0.65), 0.5))
    # standing seams running down the slope (0.6 m sheets): bands in X and Y, light ridge + dark shadow line
    for d in ("X", "Y"):
        sw_ = N(nt, "ShaderNodeTexWave", wave_type="BANDS", bands_direction=d); sw_.inputs["Scale"].default_value = 1.67
        L(nt, coord, sw_.inputs["Vector"])
        c = mix(nt, c, (0.55, 0.70, 0.62, 1), mathn(nt, "MULTIPLY", ramp_val(nt, sw_.outputs["Fac"], 0.05, 0.0), 0.7))
        c = mix(nt, c, (0.13, 0.22, 0.19, 1), mathn(nt, "MULTIPLY", ramp_val(nt, sw_.outputs["Fac"], 0.10, 0.06), 0.6))
    sz2 = sep_z(nt, coord)
    c = mix(nt, c, (0.33, 0.27, 0.18, 1), mathn(nt, "MULTIPLY", ramp_val(nt, sz2.outputs["Z"], SP_BOT + 3.5, SP_BOT), 0.5))   # eaves
    return weather(nt, coord, c, streak=0.2, tide=False, ao_min=0.6, grime=0.1)

def m_gold(nt, coord):
    """Fake gilding (no metal shader in the game): dark ochre, bright where the surface faces the sky."""
    base = N(nt, "ShaderNodeTexNoise", i_Scale=4.0, i_Detail=3.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.45, 0.30, 0.10, 1), (0.58, 0.41, 0.15, 1), base.outputs["Fac"])
    g = N(nt, "ShaderNodeNewGeometry"); sn = N(nt, "ShaderNodeSeparateXYZ"); L(nt, g.outputs["Normal"], sn.inputs[0])
    c = mix(nt, c, (0.98, 0.84, 0.45, 1), ramp_val(nt, sn.outputs["Z"], 0.2, 0.9))
    return mix(nt, c, (0.30, 0.20, 0.07, 1), ramp_val(nt, sn.outputs["Z"], -0.2, -0.8))

node_mat("brick", m_brick, 11)
def m_brick_shadow(nt, coord):
    return mix(nt, m_brick(nt, coord), (0.10, 0.06, 0.05, 1), 0.3)
def m_brick_attic(nt, coord):
    return mix(nt, m_brick(nt, coord), (0.75, 0.75, 0.75, 1), 1.0, "MULTIPLY")
node_mat("brick_shadow", m_brick_shadow, 11)
node_mat("brick_attic", m_brick_attic, 11)
node_mat("stone", lambda nt, c: m_istrian_plain(nt, c, ao_min=0.55), 15)       # dentil gaps were near-black
node_mat("stone_light", lambda nt, c: m_istrian_plain(nt, c, 1.04, 0.35), 15)
node_mat("ashlar", lambda nt, c: m_istrian_ashlar(nt, c, 1.4, 0.5), 15)
node_mat("carved", lambda nt, c: mix(nt, m_istrian_plain(nt, c, 0.95, 0.7, 0.4), (0.86, 0.80, 0.68, 1), 1.0, "MULTIPLY"), 15)   # grey-ochre reliefs
node_mat("hatch", m_flat((0.12, 0.16, 0.15), 0.1, 0.6), 33)
node_mat("copper", m_copper, 31)
node_mat("rib", lambda nt, c: m_istrian_plain(nt, c, 0.97, 0.5), 15)
node_mat("gold", m_gold, 33)
node_mat("dark", m_flat((0.04, 0.035, 0.03), 0.2, 0.8), 33)
node_mat("inner", m_flat((0.20, 0.19, 0.18), 0.1, 0.4), 15)
node_mat("bronze", m_flat((0.20, 0.15, 0.09), 0.15, 0.6), 33)
node_mat("iron", m_flat((0.05, 0.05, 0.05), 0.2, 0.6), 33)
MAT_ORDER = list(MATS.keys())

X, Y, Z = Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1))

def faces4():
    """(outward normal, along-face unit) for the four faces."""
    return [(Vector((0, -1, 0)), Vector((1, 0, 0))), (Vector((1, 0, 0)), Vector((0, 1, 0))),
            (Vector((0, 1, 0)), Vector((-1, 0, 0))), (Vector((-1, 0, 0)), Vector((0, -1, 0)))]

def slab(c, nrm, along, hw, hd, z0, z1, mat):
    """Box centred on c (z ignored) with half-width hw along `along`, half-depth hd along `nrm`, from z0 to z1."""
    obox(Vector((c.x, c.y, (z0 + z1) / 2)), along, nrm, Z, hw, hd, (z1 - z0) / 2, mat)

def square_band(w, z0, z1, mat, proud=0.0):
    obox(Vector((0, 0, (z0 + z1) / 2)), X, Y, Z, w / 2 + proud, w / 2 + proud, (z1 - z0) / 2, mat)

def cornice(w, z0, layers, mat="stone"):
    """Stack of projecting square slabs: layers = [(height, overhang)] bottom to top."""
    z = z0
    for h, o in layers:
        square_band(w, z, z + h, mat, o); z += h
    return z

# ---------------------------------------------------------------- 1. plinth and shaft
cornice(12.9, Z0, [(PL_H - Z0 - 0.35, 0.0), (0.2, 0.05), (0.15, 0.0)], "ashlar")
hw = SHAFT_W / 2
REC = 0.42                                    # depth of the recessed channels
CH_W = 0.85                                   # channel width
CORNER = 1.55
inner = SHAFT_W - 2 * CORNER - 4 * CH_W
PIL = inner / 3
# core (recessed plane) and pilasters per face
square_band(SHAFT_W - 2 * REC, PL_H, SH_TOP, "brick_shadow")      # channel backs: in shade, read as bold grooves
for nrm, along in faces4():
    centre = nrm * (hw - REC)
    u = -hw
    segs = [CORNER, CH_W, PIL, CH_W, PIL, CH_W, PIL, CH_W, CORNER]
    for k, wseg in enumerate(segs):
        if k % 2 == 0:   # pilaster
            c = nrm * (hw - REC / 2) + along * (u + wseg / 2)
            hwid = wseg / 2 if 0 < k < 8 else wseg / 2 - REC / 2
            cc = c if 0 < k < 8 else c + along * (REC / 2 * (1 if k == 0 else -1))
            slab(cc, nrm, along, hwid, REC / 2, PL_H, SH_TOP, "brick")
        else:            # channel: ends in a small round arch under the frieze; stepped profile (inner fillet each side)
            for su in (u + 0.075, u + wseg - 0.075):
                slab(nrm * (hw - REC * 0.75) + along * su, nrm, along, 0.075, REC / 4, PL_H, SH_TOP - 0.9, "brick")
            cx = u + wseg / 2
            ap = [(cx + (wseg / 2) * math.cos(math.pi * i / 8), SH_TOP - 0.9 + (wseg / 2) * math.sin(math.pi * i / 8)) for i in range(9)]
            pts = [nrm * (hw - 0.001) + along * a + Z * b for a, b in ([(u + wseg, SH_TOP)] + ap + [(u, SH_TOP)])]
            face(pts if nrm.cross(along).z > 0 else pts[::-1], "brick_shadow")
        u += wseg
# windows: one column per face on the second pilaster, staggered face to face (the ramp inside)
for fi, (nrm, along) in enumerate(faces4()):
    u = -hw + CORNER + CH_W + PIL / 2
    z = 7.0 + fi * 1.1
    while z < SH_TOP - 4:
        c = nrm * (hw + 0.02) + along * u
        for (w2, h2, d2, mat) in ((1.2, 2.0, 0.06, "stone_light"), (0.8, 1.6, 0.09, "dark")):
            obox(Vector((c.x, c.y, z)) + nrm * d2, along, nrm, Z, w2 / 2, d2, h2 / 2, mat)
            blob(Vector((c.x, c.y, z + h2 / 2)) + nrm * d2, w2 / 2, d2 * 1.2, w2 / 2, mat, segs=8, rings=4)
        z += 4.4

# ---------------------------------------------------------------- 2. frieze of shell arches, belfry base
square_band(SHAFT_W, SH_TOP, SH_TOP + 0.25, "stone", 0.05)
square_band(SHAFT_W, SH_TOP + 0.25, SH_TOP + 1.45, "stone_light")
square_band(SHAFT_W, SH_TOP + 1.45, FRIEZE_TOP + 0.4, "stone", 0.04)
for nrm, along in faces4():
    for k in range(4):
        u = -hw + CORNER + CH_W / 2 + k * (CH_W + PIL) + (PIL + CH_W) / 2 - 0.4
        c = nrm * (hw + 0.05) + along * u
        for i in range(8):     # archivolt voussoir ring
            a0, a1 = math.pi * i / 8, math.pi * (i + 1) / 8
            am = (a0 + a1) / 2
            p = Vector((c.x, c.y, SH_TOP + 0.35)) + along * math.cos(am) * 1.05 + Z * math.sin(am) * 1.05
            obox(p + nrm * 0.06, (along * -math.sin(am) + Z * math.cos(am)).normalized(), nrm, (along * math.cos(am) + Z * math.sin(am)).normalized(), 0.2, 0.06, 0.12, "stone_light")
        for j in range(7):     # fluted shell in the lunette
            a = math.pi * (j + 0.5) / 7
            tube([Vector((c.x, c.y, SH_TOP + 0.35)) + nrm * 0.12, Vector((c.x, c.y, SH_TOP + 0.35)) + nrm * 0.14 + along * math.cos(a) * 0.8 + Z * math.sin(a) * 0.8], 0.07, "carved", segs=5)
        slab(c + along * 1.2, nrm, along, 0.12, 0.08, SH_TOP, SH_TOP + 1.1, "stone_light")    # little pilaster between arches
cornice(SHAFT_W, FRIEZE_TOP + 0.4, [(0.2, 0.12), (0.3, 0.3), (0.2, 0.42)], "stone")

# ---------------------------------------------------------------- 3. belfry
bw = BEL_W / 2
PIER = 2.1
OPEN_W = (BEL_W - 2 * PIER - 3 * 0.55) / 4
SPRING = BEL_BASE + 4.4
FLOOR = BEL_BASE + 0.5
square_band(5.2, BEL_BASE, BEL_TOP, "inner")                                   # stair core / back wall seen through the arches
square_band(BEL_W - 0.6, BEL_TOP - 1.0, BEL_TOP - 0.85, "inner")                 # ceiling
for sx in (-1, 1):
    for sy in (-1, 1):   # corner piers: one block per corner, with a recessed panel on both faces
        box(sx * bw - (PIER if sx > 0 else 0), sx * bw + (PIER if sx < 0 else 0), sy * bw - (PIER if sy > 0 else 0), sy * bw + (PIER if sy < 0 else 0), BEL_BASE, BEL_TOP, "stone_light")
for nrm, along in faces4():
    for e in (-1, 1):
        slab(nrm * (bw + 0.03) + along * e * (bw - PIER / 2), nrm, along, PIER / 2 - 0.25, 0.03, BEL_BASE + 0.6, BEL_TOP - 1.2, "stone")
    # parapet under the openings
    slab(nrm * (bw - 0.3), nrm, along, bw - PIER, 0.3, BEL_BASE, FLOOR, "stone")
    # arcade: columns with capitals and bases, arches with archivolts, spandrel wall above
    us = [-bw + PIER + OPEN_W + 0.275 + k * (OPEN_W + 0.55) for k in range(3)]
    for u in us:
        c = nrm * (bw - 0.4) + along * u
        lathe(c.x, c.y, FLOOR, [(0.32, 0.0), (0.32, 0.12), (0.24, 0.2), (0.2, 0.3), (0.17, SPRING - FLOOR - 0.6), (0.2, SPRING - FLOOR - 0.45), (0.32, SPRING - FLOOR - 0.15), (0.34, SPRING - FLOOR)], "stone_light", segs=10)
        blob(Vector((c.x, c.y, SPRING + OPEN_W / 2 + 0.55)) + nrm * 0.5, 0.26, 0.2, 0.28, "carved", segs=10, rings=6)    # lion mask
        blob(Vector((c.x, c.y, SPRING + OPEN_W / 2 + 0.78)) + nrm * 0.48, 0.34, 0.14, 0.22, "carved", segs=8, rings=5)  # mane
    for k in range(4):
        u0 = -bw + PIER + k * (OPEN_W + 0.55); uc = u0 + OPEN_W / 2; r = OPEN_W / 2
        ring = [(uc + r * math.cos(math.pi * i / 10), SPRING + r * math.sin(math.pi * i / 10)) for i in range(11)]
        outer = [(uc + (r + 0.3) * math.cos(math.pi * i / 10), SPRING + (r + 0.3) * math.sin(math.pi * i / 10)) for i in range(11)]
        for (a, b), (c2, d2) in zip(zip(ring, ring[1:]), zip(outer, outer[1:])):
            P = [nrm * (bw + 0.06) + along * p[0] + Z * p[1] for p in (a, b, d2, c2)]
            face(P if nrm.cross(along).z < 0 else P[::-1], "stone_light")
        # spandrel infill between arches up to the entablature
        ztop = BEL_TOP - 0.9
        left = [(u0 - 0.275, SPRING)] + ring[::-1] + [(u0 + OPEN_W + 0.275, SPRING), (u0 + OPEN_W + 0.275, ztop), (u0 - 0.275, ztop)]
        P = [nrm * (bw - 0.02) + along * a + Z * b for a, b in left]
        face(P if nrm.cross(along).z > 0 else P[::-1], "stone")
    # entablature band
    ent_hw = bw + 0.04 if abs(nrm.y) > 0.5 else bw - 0.56
    slab(nrm * (bw - 0.26), nrm, along, ent_hw, 0.3, BEL_TOP - 0.9, BEL_TOP, "stone")
# bells inside
for (bx, by, br) in ((0, -3.9, 1.1), (3.9, 0, 0.95), (0, 3.9, 0.85), (-3.9, 0, 0.8), (-3.6, -3.4, 0.6)):
    lathe(bx, by, SPRING - 1.6, [(br, 0.0), (br * 0.95, 0.15), (br * 0.62, br * 0.9), (br * 0.55, br * 1.4), (br * 0.3, br * 1.55), (0.0, br * 1.6)], "bronze", segs=12)
top = cornice(BEL_W, BEL_TOP, [(0.25, 0.15), (0.3, 0.35), (0.25, 0.55), (0.25, 0.6)], "stone")

# ---------------------------------------------------------------- 4. balustrade, attic with reliefs, cornice
BAL = [(0.09, 0.0), (0.09, 0.05), (0.07, 0.07), (0.11, 0.2), (0.12, 0.27), (0.08, 0.38), (0.06, 0.44), (0.09, 0.48), (0.09, 0.52)]
for nrm, along in faces4():
    edge = BEL_W / 2 + 0.25
    peds = [-edge + 0.25, -edge / 3, edge / 3, edge - 0.25]
    for p0_, p1_ in zip(peds, peds[1:]):
        for t in frange(p0_ + 0.45, p1_ - 0.45, 0.36):
            c = nrm * (edge - 0.25) + along * t
            lathe(c.x, c.y, top, [(r, dz * 1.6) for r, dz in BAL], "stone", segs=7)
    for t in peds[1:-1]:
        slab(nrm * (edge - 0.25) + along * t, nrm, along, 0.28, 0.28, top, top + 1.05, "stone")
    slab(nrm * (edge - 0.25), nrm, along, edge, 0.26, top + 0.83, top + 1.05, "stone")
    for e in (-1, 1):
        slab(nrm * (edge - 0.25) + along * e * (edge - 0.25), nrm, along, 0.25, 0.25, top, top + 1.05, "stone")
aw = ATT_W / 2
ATT_TOP = top + ATT_H
square_band(ATT_W, top, ATT_TOP, "brick_attic")
square_band(ATT_W + 0.05, top, top + 0.5, "stone"); square_band(ATT_W + 0.05, ATT_TOP - 0.3, ATT_TOP, "stone")
for sx in (-1, 1):
    for sy in (-1, 1):   # one white corner pilaster per corner
        box(sx * (aw + 0.05) - (0.8 if sx > 0 else 0), sx * (aw + 0.05) + (0.8 if sx < 0 else 0), sy * (aw + 0.05) - (0.8 if sy > 0 else 0), sy * (aw + 0.05) + (0.8 if sy < 0 else 0), top, ATT_TOP, "stone")
for fi, (nrm, along) in enumerate(faces4()):
    # broad panel moulding (0.35 m) with an inner fillet
    for (u0, u1, z0, z1) in ((-aw + 0.9, aw - 0.9, top + 0.5, top + 0.8), (-aw + 0.9, aw - 0.9, ATT_TOP - 0.6, ATT_TOP - 0.3),
                             (-aw + 0.9, -aw + 1.2, top + 0.5, ATT_TOP - 0.3), (aw - 1.2, aw - 0.9, top + 0.5, ATT_TOP - 0.3)):
        slab(nrm * (aw + 0.06) + along * ((u0 + u1) / 2), nrm, along, (u1 - u0) / 2, 0.08, z0, z1, "stone_light")
    slab(nrm * (aw - 0.15), nrm, along, aw - 1.2, 0.02, top + 0.8, ATT_TOP - 0.6, "brick_shadow")   # recessed field
    for (u0_, u1_, z0_, z1_) in ((-aw + 1.35, aw - 1.35, top + 0.95, top + 1.0), (-aw + 1.35, aw - 1.35, ATT_TOP - 0.8, ATT_TOP - 0.75),
                                 (-aw + 1.35, -aw + 1.4, top + 0.95, ATT_TOP - 0.75), (aw - 1.4, aw - 1.35, top + 0.95, ATT_TOP - 0.75)):
        slab(nrm * (aw - 0.11) + along * ((u0_ + u1_) / 2), nrm, along, (u1_ - u0_) / 2, 0.02, z0_, z1_, "stone_light")
    zc = (top + 0.8 + ATT_TOP - 0.6) / 2 - 0.4
    c = nrm * aw + along * 0.0
    yface = 0.0
    # relief in local face coordinates (u along, v up): build with the shared relief() on a rotated frame
    def P3(u, v, d): return nrm * (aw + d) + along * u + Z * (zc + v)
    def rel(outline, ox, oy, depth, mat, bevel=0.5, d0=0.02, sc=1.0):
        bm.verts.ensure_lookup_table(); n0 = len(bm.verts)
        relief(outline, ox, oy, sc, 0.0, 0.0, -1, depth, mat, bevel)
        bm.verts.ensure_lookup_table()
        for v in bm.verts[n0:]:
            u, d, w = v.co.x, -v.co.y, v.co.z
            v.co = P3(u, w, d + d0)
    if fi % 2 == 0:   # winged lion of St Mark (south / north faces): walking, big sickle wing, paw on the open gospel
        body = [(-1.9, -1.2), (-1.6, -1.2), (-1.5, -0.55), (-0.6, -0.5), (-0.5, -1.2), (-0.2, -1.2), (-0.1, -0.45), (0.8, -0.4),
                (0.9, -1.2), (1.2, -1.2), (1.3, -0.35), (1.7, -0.05), (1.95, 0.25), (1.9, 0.75), (1.35, 0.85), (1.0, 0.45),
                (0.3, 0.4), (-0.6, 0.45), (-1.4, 0.4), (-1.75, 0.2), (-2.1, 0.35), (-2.4, 0.75), (-2.55, 0.55), (-2.15, -0.05)]
        rel(body, 0.0, 0.0, 0.35, "carved")
        # the sickle wing sweeping up and back over the body (upper-left quadrant, ~1.8 m)
        wing = [(0.5, 0.35), (0.7, 0.6), (0.4, 0.95), (-0.2, 1.25), (-1.0, 1.45), (-1.9, 1.5), (-1.5, 1.3), (-2.0, 1.15),
                (-1.5, 1.0), (-1.9, 0.85), (-1.3, 0.75), (-1.5, 0.55), (-0.8, 0.5)]
        rel(wing, 0.0, 0.0, 0.3, "carved", 0.4, 0.22)
        for k in range(4):   # feather grooves
            tube([P3(0.2 - 0.35 * k, 0.6 + 0.05 * k, 0.55), P3(-0.6 - 0.35 * k, 1.05 + 0.08 * k, 0.55)], 0.04, "stone", segs=4)
        blob(P3(1.55, 0.75, 0.42), 0.45, 0.32, 0.45, "carved", segs=12, rings=7)            # maned head, ~0.9 m across
        blob(P3(1.95, 0.62, 0.55), 0.2, 0.15, 0.16, "carved", segs=8, rings=5)              # muzzle
        obox(P3(2.45, 0.35, 0.55), along, nrm, Z, 0.36, 0.06, 0.45, "stone_light")          # open gospel held up by the front paw
        obox(P3(2.45, 0.35, 0.62), along, nrm, Z, 0.025, 0.02, 0.41, "dark")                # gutter
        tube([P3(1.3, -0.3, 0.42), P3(1.9, 0.0, 0.45), P3(2.15, 0.2, 0.5)], 0.1, "carved")    # raised paw
        for lx in (-1.2, -0.6, 0.9, 1.4): tube([P3(lx, -0.3, 0.38), P3(lx + 0.05, -0.95, 0.38)], 0.11, "carved")
        tube([P3(-2.0, 0.1, 0.4), P3(-2.35, 0.5, 0.4), P3(-2.2, 0.9, 0.4)], 0.07, "carved")   # tail
    else:             # Venezia enthroned as Justice, sword raised, between two seated lions
        for e in (-1, 1): obox(P3(e * 0.75, 0.2, 0.18), along, nrm, Z, 0.15, 0.16, 1.6, "stone")   # throne posts
        fig = [(-0.85, -1.6), (0.85, -1.6), (0.85, -1.0), (0.72, -0.35), (0.58, 0.05), (0.6, 0.5), (0.42, 0.85), (0.14, 0.98),
               (0.12, 1.12), (-0.12, 1.12), (-0.14, 0.98), (-0.42, 0.85), (-0.6, 0.5), (-0.58, 0.05), (-0.72, -0.35), (-0.85, -1.0)]
        rel(fig, 0.0, 0.0, 0.42, "carved")
        blob(P3(0.0, 1.38, 0.5), 0.21, 0.19, 0.26, "carved", segs=10, rings=6)               # head on its neck
        tube([P3(0.45, 0.75, 0.5), P3(0.62, 1.25, 0.55), P3(0.55, 1.7, 0.55)], 0.07, "carved")   # raised arm
        tube([P3(0.55, 1.7, 0.6), P3(0.5, 2.45, 0.6)], 0.045, "stone_light")                  # sword above the head
        obox(P3(0.53, 1.85, 0.6), along, nrm, Z, 0.16, 0.03, 0.03, "stone_light")             # crossguard
        tube([P3(-0.45, 0.6, 0.5), P3(-0.85, 0.85, 0.52)], 0.06, "carved")
        obox(P3(-0.9, 0.85, 0.55), along, nrm, Z, 0.3, 0.03, 0.025, "carved")                 # scales beam
        for e in (-1, 1):
            obox(P3(-0.9 + e * 0.28, 0.6, 0.55), along, nrm, Z, 0.1, 0.03, 0.02, "carved")    # pans
        for e in (-1, 1):   # seated lions, upright, flanking her at chest height, facing outwards
            lion = [(-0.9, -1.6), (0.75, -1.6), (0.95, -1.3), (1.05, -0.85), (0.95, -0.45), (0.7, -0.35), (0.5, -0.7), (-0.5, -0.8),
                    (-0.85, -1.0), (-1.1, -0.85), (-1.15, -1.0)]
            if e < 0: lion = [(-u, v) for u, v in lion]
            rel(lion, e * 1.75, 0.0, 0.36, "carved")
            blob(P3(e * 2.65, -0.6, 0.45), 0.36, 0.26, 0.34, "carved", segs=10, rings=6)      # mane ruff
            blob(P3(e * 3.0, -0.72, 0.5), 0.2, 0.16, 0.15, "carved", segs=8, rings=5)        # projecting muzzle
        for e in (-1, 1): blob(P3(e * 0.32, -0.55, 0.62), 0.3, 0.22, 0.2, "carved", segs=8, rings=5)   # knees / lap
_sp = cornice(ATT_W, ATT_TOP, [(0.2, 0.15), (0.25, 0.35), (0.2, 0.45)], "stone") + 0.15
assert abs(_sp - SP_BOT) < 0.05, _sp
assert abs(SH_TOP - 50.5) < 0.6 and abs(SHAFT_W - 12.0) < 0.5, "shaft must stay ~50 m x 12 m"
print("attic h/w", round((ATT_TOP - top) / ATT_W, 2), "spire", SP_BOT, "->", SP_APEX)

# ---------------------------------------------------------------- 5. spire with stone ribs, the angel
sw = SP_W / 2
corners = [Vector((sx * sw, sy * sw, SP_BOT)) for sx, sy in ((-1, -1), (1, -1), (1, 1), (-1, 1))]
apex = Vector((0, 0, SP_APEX))
for i in range(4):
    a, b = corners[i], corners[(i + 1) % 4]
    inset = 0.42
    a2 = a + (b - a).normalized() * inset; b2 = b + (a - b).normalized() * inset
    face([a2, b2, apex + (b2 - apex) * 0.03, apex + (a2 - apex) * 0.03], "copper")
    # white stone rib up the hip
    d = (apex - a); L_ = d.length; d.normalize()
    side = (b - a).normalized()
    out = d.cross(side).normalized()
    if out.z < 0: out = -out
    hip = (a - apex); hip.z = 0
    obox(a + d * L_ / 2 + out * 0.08, d, hip.normalized().cross(d).normalized(), out, L_ / 2, 0.5, 0.14, "rib")
    obox(a + d * L_ / 2 + out * 0.2, d, hip.normalized().cross(d).normalized(), out, L_ / 2, 0.28, 0.08, "rib")
# small dormer-like openings low on the spire (one per face)
SPH = SP_APEX - SP_BOT
def on_face(nrm, along, u, z, off=0.0):
    """point on the copper face plane at height z, offset `off` along its outward normal; plus (up-slope, out) axes"""
    hwz = sw * (1.0 - (z - SP_BOT) / SPH)
    out = (nrm * SPH + Z * sw).normalized()
    up = (Z * SPH - nrm * sw).normalized()
    return nrm * hwz + along * u + Z * z + out * off, up, out
for nrm, along in faces4():
    for (u, z) in ((-2.2, SP_BOT + 6.5), (2.2, SP_BOT + 6.5), (-1.5, SP_BOT + 10.0), (1.5, SP_BOT + 10.0), (0.0, SP_BOT + 13.5)):
        c2, up2, out2 = on_face(nrm, along, u, z, 0.03)
        obox(c2, along, up2, out2, 0.155, 0.155, 0.03, "hatch")                  # small square hatches
cornice(1.2, SP_APEX - 0.3, [(0.25, 0.1), (0.35, 0.0), (0.2, 0.15)], "stone")
lathe(0, 0, SP_APEX + 0.5, [(0.12, 0.0), (0.12, 0.4)], "gold", segs=8)
# Archangel Gabriel on a drum: ~3.6 m with the wings swept up and back, right arm raised
cornice(1.6, SP_APEX + 0.2, [(0.5, 0.0), (0.15, 0.12)], "stone")
AZ = SP_APEX + 0.85
G = 1.5
# robe: flared hem, narrow waist, chest, shoulders, neck
lathe(0, 0, AZ, [(0.40 * G, 0.0), (0.36 * G, 0.15 * G), (0.24 * G, 0.6 * G), (0.17 * G, 0.95 * G), (0.21 * G, 1.2 * G),
                 (0.26 * G, 1.4 * G), (0.22 * G, 1.5 * G), (0.08 * G, 1.56 * G), (0.08 * G, 1.62 * G), (0.0, 1.63 * G)], "gold", segs=12)
blob(Vector((0, 0.03, AZ + 1.76 * G)), 0.12 * G, 0.13 * G, 0.15 * G, "gold", segs=10, rings=6)            # head
for e in (-1, 1):   # broad flat wing panels rising behind the shoulders in a V (~2 m each)
    ang = math.radians(32)
    ax_ = Vector((e * math.sin(ang), -0.25, math.cos(ang))).normalized()
    side = Vector((e * math.cos(ang), 0.0, -math.sin(ang))).normalized()
    nrm_ = ax_.cross(side).normalized()
    c_ = Vector((e * 0.22 * G, -0.22 * G, AZ + 1.35 * G)) + ax_ * 0.95
    obox(c_, ax_, side, nrm_, 1.0, 0.32, 0.035, "gold")
    obox(c_ + ax_ * 0.2 + side * 0.25, ax_, side, nrm_, 0.75, 0.12, 0.03, "gold")          # primary feathers
tube([Vector((0.2 * G, 0.06, AZ + 1.42 * G)), Vector((0.32 * G, 0.25, AZ + 1.75 * G)), Vector((0.36 * G, 0.35, AZ + 2.1 * G))], 0.06 * G, "gold")   # right arm raised forward
tube([Vector((-0.2 * G, 0.06, AZ + 1.38 * G)), Vector((-0.28 * G, 0.18, AZ + 1.05 * G))], 0.06 * G, "gold")

# ---------------------------------------------------------------- finish: object, UVs, bake
me = bpy.data.meshes.new("campanile"); bm.normal_update(); bm.to_mesh(me); bm.free()
ob = bpy.data.objects.new("Campanile", me); bpy.context.scene.collection.objects.link(ob)
for m in MAT_ORDER: me.materials.append(MATS[m][0])
bpy.context.view_layer.objects.active = ob; ob.select_set(True)
bpy.ops.object.mode_set(mode="EDIT"); bpy.ops.mesh.select_all(action="SELECT")
bpy.ops.mesh.remove_doubles(threshold=0.002)
bpy.ops.uv.smart_project(angle_limit=math.radians(60), island_margin=0.0, area_weight=0.0, scale_to_bounds=False)
bpy.context.scene.tool_settings.use_uv_select_sync = True
bpy.ops.mesh.select_all(action="SELECT")
bpy.ops.uv.average_islands_scale()
bpy.ops.uv.pack_islands(rotate=True, scale=True, margin_method="FRACTION", margin=0.0004, shape_method="CONVEX")
bpy.ops.object.mode_set(mode="OBJECT")
tris = sum(len(p.vertices) - 2 for p in ob.data.polygons)
img = bpy.data.images.new("atlas", ATLAS_RES, ATLAS_RES, alpha=False)
for name, (m, layer) in MATS.items():
    n = m.node_tree.nodes.new("ShaderNodeTexImage"); n.image = img; m.node_tree.nodes.active = n
sc = bpy.context.scene
sc.render.engine = "CYCLES"; sc.cycles.device = "CPU"; sc.cycles.samples = int(os.environ.get("BAKE_SAMPLES", "4"))
bpy.ops.object.bake(type="EMIT", margin=6)
img.filepath_raw = ATLAS; img.file_format = "PNG"; img.save()
ob["layers"] = [MATS[n][1] for n in MAT_ORDER]
bpy.ops.wm.save_as_mainfile(filepath=OUT)
print("Campanile saved", OUT, ATLAS, "triangles", tris)

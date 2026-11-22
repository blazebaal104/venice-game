"""ACTV vaporetto stop (imbarcadero) - floating pontoon with waiting cabin, from photo references (Commons: S. Tomà,
S. Silvestro, Giardini, Salute).  Local frame: +X along the pontoon, +Y towards land (gangway side), z = 0 mean water.
Built in three lengths (12 / 16 / 22 m) so stops are never stretched; the game picks the closest one.

From the photos:
  * dark grey steel hull standing ~0.9 m out of the water, black rubber fender blocks, green slime at the waterline
  * grey deck; bold black/yellow chevrons over the open boarding area, a yellow nosing along the boat edge
  * cabin: off-white lower panels, a glazed band (aluminium mullions, transom) between two ACTV-yellow bands; the stop's
    name sits at both ends of each band, its letter on a separate white plaque; the glazing and bands wrap round the ends
  * flat roof with a deep grey fascia, also covering the open boarding area; slim posts, tube railings, a boarding gate
  * H-shaped twin mooring bitts on the boat edge; a railed gangway ramp up to the fondamenta
Name plates use a second UV set ("signuv") mapped into the stop's slot of the shared sign sheet: u 0..0.8 = name,
u 0.86..0.99 = letter square.
"""
import bpy, bmesh, math, sys, os
from mathutils import Vector

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
OUT = ARGS[0] if len(ARGS) > 0 else "/home/claude/venice/actv.blend"
ATLAS = ARGS[1] if len(ARGS) > 1 else "/home/claude/venice/actv_atlas.png"
ATLAS_RES = int(ARGS[2]) if len(ARGS) > 2 else 1024
LENGTH = float(ARGS[3]) if len(ARGS) > 3 else 16.0
bpy.ops.wm.read_factory_settings(use_empty=True)
ARCH_CZ = 0.0; R_ARCH = 1.0
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "lmcommon.py")).read())

HL = LENGTH / 2; HW = 2.4; DECK = 0.9; KEEL = -0.5
OPEN = min(5.0, LENGTH * 0.3)                         # open boarding area at the +X end
CAB_X0, CAB_X1 = -HL + 0.6, HL - OPEN
CAB_Y0, CAB_Y1 = -1.25, 1.95
CAB_TOP = DECK + 2.55
ROOF0 = CAB_TOP; ROOF1 = CAB_TOP + 0.12
YEL = (242 / 255, 194 / 255, 0.0)      # ACTV yellow, same value as the sign sheet background

def m_hull(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=1.0, i_Detail=5.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.22, 0.23, 0.23, 1), (0.29, 0.30, 0.29, 1), base.outputs["Fac"])
    mp = N(nt, "ShaderNodeMapping"); L(nt, coord, mp.inputs[0]); mp.inputs["Scale"].default_value = (5.0, 5.0, 0.3)
    st = N(nt, "ShaderNodeTexNoise", i_Scale=3.0, i_Detail=5.0); L(nt, mp.outputs[0], st.inputs["Vector"])
    c = mix(nt, c, (0.42, 0.25, 0.14, 1), mathn(nt, "MULTIPLY", ramp_val(nt, st.outputs["Fac"], 0.6, 0.8), 0.55))   # rust runs
    sz = sep_z(nt, coord)
    c = mix(nt, c, (0.10, 0.11, 0.09, 1), ramp_val(nt, sz.outputs["Z"], 0.0, -0.08))                       # wet, below the line
    edge = N(nt, "ShaderNodeTexNoise", i_Scale=3.0, i_Detail=3.0); L(nt, coord, edge.inputs["Vector"])
    mtop = mathn(nt, "ADD", 0.1, mathn(nt, "MULTIPLY", edge.outputs["Fac"], 0.12))           # ragged top edge 0.1-0.22 m
    moss = mathn(nt, "MULTIPLY", ramp_val(nt, sz.outputs["Z"], -0.06, 0.0), mathn(nt, "LESS_THAN", sz.outputs["Z"], mtop))
    mn = N(nt, "ShaderNodeTexNoise", i_Scale=4.0, i_Detail=4.0); L(nt, coord, mn.inputs["Vector"])
    c = mix(nt, c, mix(nt, (0.30, 0.44, 0.10, 1), (0.44, 0.58, 0.14, 1), mn.outputs["Fac"]), moss)
    return weather(nt, coord, c, streak=0.35, tide=False, ao_min=0.5, grime=0.25)

def m_paint(color, var=0.06, grime=0.15, streak=0.3, dirt=0.45, ao_min=0.5):
    def f(nt, coord):
        base = N(nt, "ShaderNodeTexNoise", i_Scale=3.0, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
        c = mix(nt, (color[0] * (1 - var), color[1] * (1 - var), color[2] * (1 - var), 1), (*color, 1), base.outputs["Fac"])
        sz = sep_z(nt, coord)        # dirt rising from the deck onto the lower panels
        c = mix(nt, c, (0.45, 0.43, 0.40, 1), mathn(nt, "MULTIPLY", ramp_val(nt, sz.outputs["Z"], DECK + 0.5, DECK), dirt))
        return weather(nt, coord, c, streak=streak, tide=False, ao_min=ao_min, grime=grime)
    return f

GATE_X = HL - OPEN + 1.3      # boarding axis (centre of the gate)
def m_chevrons(nt, coord):
    sz = sep_z(nt, coord)
    u = mathn(nt, "ADD", mathn(nt, "ABSOLUTE", mathn(nt, "SUBTRACT", sz.outputs["X"], GATE_X)), sz.outputs["Y"])
    cv = N(nt, "ShaderNodeCombineXYZ"); L(nt, u, cv.inputs["X"])
    wv = N(nt, "ShaderNodeTexWave", wave_type="BANDS", bands_direction="X", i_Distortion=0.0); wv.inputs["Scale"].default_value = 0.85
    L(nt, cv.outputs[0], wv.inputs["Vector"])
    c = mix(nt, (0.32, 0.32, 0.31, 1), (*YEL, 1), mathn(nt, "GREATER_THAN", wv.outputs["Fac"], 0.5))
    wear = N(nt, "ShaderNodeTexNoise", i_Scale=2.0, i_Detail=5.0); L(nt, coord, wear.inputs["Vector"])
    c = mix(nt, c, (0.40, 0.40, 0.38, 1), mathn(nt, "MULTIPLY", ramp_val(nt, wear.outputs["Fac"], 0.55, 0.75), 0.2))
    return weather(nt, coord, c, streak=0.0, tide=False, ao_min=0.7, grime=0.1)

def m_deck(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=2.0, i_Detail=5.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.34, 0.35, 0.34, 1), (0.45, 0.45, 0.43, 1), base.outputs["Fac"])
    return weather(nt, coord, c, streak=0.0, tide=False, ao_min=0.55, grime=0.3)

def m_glass(nt, coord):
    """Glazing as seen from the canal: sky/facade reflection, light grey-blue at the top fading to a mid tone."""
    sz = sep_z(nt, coord)
    base = N(nt, "ShaderNodeTexNoise", i_Scale=0.5, i_Detail=2.0); L(nt, coord, base.inputs["Vector"])
    lo = mix(nt, (0.30, 0.34, 0.37, 1), (0.36, 0.40, 0.43, 1), base.outputs["Fac"])
    return mix(nt, lo, (0.60, 0.66, 0.72, 1), ramp_val(nt, sz.outputs["Z"], DECK + 1.2, DECK + 2.1))

node_mat("hull", m_hull, 33)
node_mat("rubber", m_flat((0.04, 0.04, 0.04), 0.1, 0.8), 33)
node_mat("deck", m_deck, 25)
node_mat("chevrons", m_chevrons, 25)
node_mat("white", m_paint((0.91, 0.91, 0.89), 0.03, 0.05, 0.1, 0.2, 0.7), 0)
node_mat("roof", m_paint((0.86, 0.87, 0.87), 0.01, 0.03, 0.05, 0.0, 0.8), 0)
node_mat("fascia", m_paint((0.86, 0.87, 0.87), 0.01, 0.03, 0.08, 0.0, 0.8), 0)
node_mat("yellow", m_paint(YEL, 0.0, 0.0, 0.0, 0.0, 0.95), 0)
node_mat("black", m_paint((0.06, 0.06, 0.06), 0.1, 0.1, 0.0), 33)
node_mat("glass", m_glass, 34)                     # layer 34 = glossy glazing in the game
node_mat("alu", m_paint((0.66, 0.68, 0.69), 0.05, 0.15, 0.2), 33)
node_mat("planks", m_flat((0.40, 0.33, 0.25), 0.15, 0.55), 26)
node_mat("ring_o", m_paint((0.95, 0.38, 0.08), 0.05, 0.1, 0.0), 0)
node_mat("ring_w", m_paint((0.92, 0.92, 0.90), 0.05, 0.1, 0.0), 0)
IMGF = []
def m_image(path):
    def f(nt, coord):
        im = bpy.data.images.load(path); im.colorspace_settings.name = "Non-Color"
        uv = N(nt, "ShaderNodeUVMap", uv_map="puv"); t = N(nt, "ShaderNodeTexImage"); t.image = im
        L(nt, uv.outputs[0], t.inputs["Vector"])
        return t.outputs["Color"]
    return f
G_ = "/home/claude/venice/gen/"
node_mat("img_plaque", m_image(G_ + "actv_plaque.png"), 0)
node_mat("img_timetable", m_image(G_ + "actv_timetable.png"), 0)
node_mat("img_linemap", m_image(G_ + "actv_linemap.png"), 0)
def m_hazard(nt, coord):
    sz = sep_z(nt, coord)
    cv = N(nt, "ShaderNodeCombineXYZ"); L(nt, mathn(nt, "ADD", sz.outputs["Y"], sz.outputs["Z"]), cv.inputs["X"])
    wv = N(nt, "ShaderNodeTexWave", wave_type="BANDS", bands_direction="X"); wv.inputs["Scale"].default_value = 1.3
    L(nt, cv.outputs[0], wv.inputs["Vector"])
    return mix(nt, (0.04, 0.04, 0.04, 1), (*YEL, 1), mathn(nt, "GREATER_THAN", wv.outputs["Fac"], 0.5))
node_mat("hazard", m_hazard, 0)
node_mat("sign", m_paint(YEL, 0.0, 0.0, 0.0, 0.0, 0.95), 0)      # game swaps in the stop's own name / letter
node_mat("grey", m_paint((0.50, 0.52, 0.53), 0.05, 0.1, 0.1, 0.0, 0.6), 33)
MAT_ORDER = list(MATS.keys())
X, Y, Z = Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1))
SIGNS = []   # (face, dir, a, b[, "letter"])
def img_panel(c, along, nrm, hw, hh, mat, back="white"):
    """thin backed panel; its front face shows an image through the 'puv' UV set"""
    obox(c - nrm * 0.02, along, nrm, Z, hw + 0.02, 0.015, hh + 0.02, back)
    P = [c - along * hw - Z * hh, c + along * hw - Z * hh, c + along * hw + Z * hh, c - along * hw + Z * hh]
    if (P[1] - P[0]).cross(P[3] - P[0]).dot(nrm) < 0: P = [P[1], P[0], P[3], P[2]]
    f = face(P, mat)
    if f: IMGF.append((f, c, along, hw, hh))

def bx(x0, x1, y0, y1, z0, z1, mat, bottom=False): box(min(x0, x1), max(x0, x1), min(y0, y1), max(y0, y1), z0, z1, mat, skip_bottom=not bottom)

def vplane(p0, p1, z0, z1, mat, normal):
    """Vertical quad(s) from p0 to p1 (xy) facing `normal` (xy), split every ~1.2 m. Returns the list of faces."""
    a = Vector((p0[0], p0[1], 0)); b = Vector((p1[0], p1[1], 0))
    k = max(1, int(round((b - a).length / 1.2)))
    out = []
    for i in range(k):
        q0 = a.lerp(b, i / k); q1 = a.lerp(b, (i + 1) / k)
        P = [Vector((q0.x, q0.y, z0)), Vector((q1.x, q1.y, z0)), Vector((q1.x, q1.y, z1)), Vector((q0.x, q0.y, z1))]
        n = (P[1] - P[0]).cross(P[3] - P[0])
        if n.x * normal[0] + n.y * normal[1] < 0: P = [P[1], P[0], P[3], P[2]]
        out.append(face(P, mat))
    return out

# ---- hull with bevelled top edge, fender blocks, deck, chevrons, nosing
bx(-HL, HL, -HW, HW, KEEL, DECK - 0.08, "hull", True)
bx(-HL + 0.08, HL - 0.08, -HW + 0.08, HW - 0.08, DECK - 0.08, DECK, "hull")
for x in [-HL + 0.4 + i * (2 * HL - 0.8) / 5 for i in range(6)]:
    bx(x - 0.22, x + 0.22, -HW - 0.32, -HW, -0.2, DECK - 0.05, "rubber")
for (x0, x1) in ((-HL - 0.3, -HL), (HL, HL + 0.3)):
    for y in (-HW + 0.5, HW - 0.5):
        bx(x0, x1, y - 0.22, y + 0.22, 0.0, DECK - 0.1, "rubber")
bx(-HL + 0.1, HL - 0.1, -HW + 0.1, HW - 0.1, DECK, DECK + 0.03, "deck")
bx(GATE_X - 1.25, GATE_X + 1.25, -HW + 0.3, CAB_Y1, DECK + 0.03, DECK + 0.04, "chevrons")
bx(-HL + 0.1, HL - 0.1, -HW + 0.1, -HW + 0.28, DECK + 0.03, DECK + 0.045, "yellow")
# H-shaped twin bitts on the boat edge
for x in (-HL + 0.7, (CAB_X0 + CAB_X1) / 2, HL - 0.7):
    bx(x - 0.3, x + 0.3, -HW + 0.3, -HW + 0.9, DECK, DECK + 0.5, "black")                     # steel plinth
    lathe(x, -HW + 0.6, DECK + 0.5, [(0.09, 0.0), (0.09, 0.25), (0.0, 0.26)], "black", segs=8)
    obox(Vector((x, -HW + 0.6, DECK + 0.68)), X, Y, Z, 0.28, 0.05, 0.05, "black")              # cross bitt
    obox(Vector((x, -HW + 0.6, DECK + 0.68)), Y, X, Z, 0.2, 0.05, 0.05, "black")

# ---- cabin: lower panels, glazing with mullions + transom, yellow bands with fixed-size name plates, glazed ends
Z_LOW = DECK + 0.62; Z_B1 = Z_LOW + 0.28; Z_GT = CAB_TOP - 0.36; Z_B2 = CAB_TOP - 0.04
BAND_NAME = lambda h: 6.4 * h            # name plate length for a band of height h (aspect of the name part of a slot)
def band(p0, p1, z0, z1, normal, inset):
    """Yellow band from p0 to p1 with the stop's name at both ends (one plate if the band is short)."""
    d = Vector((p1[0] - p0[0], p1[1] - p0[1], 0)); Ltot = d.length; d.normalize()
    off = Vector((normal[0], normal[1], 0)) * inset
    q0 = Vector((p0[0], p0[1], 0)) + off; q1 = Vector((p1[0], p1[1], 0)) + off
    ln = BAND_NAME(z1 - z0)
    if Ltot < ln * 2.2:
        a = q0 + d * max(0.0, (Ltot - ln) / 2); b = a + d * min(ln, Ltot)
        SIGNS.extend((f, d, a, b) for f in vplane(a, b, z0, z1, "sign", normal))
        if Ltot > ln: vplane(q0, a, z0, z1, "yellow", normal); vplane(b, q1, z0, z1, "yellow", normal)
    else:
        a0, a1 = q0 + d * 0.25, q0 + d * (0.25 + ln); b1, b0 = q1 - d * 0.25, q1 - d * (0.25 + ln)
        for (a, b) in ((a0, a1), (b0, b1)):
            SIGNS.extend((f, d, a, b) for f in vplane(a, b, z0, z1, "sign", normal))
        vplane(q0, a0, z0, z1, "yellow", normal); vplane(a1, b0, z0, z1, "yellow", normal); vplane(b1, q1, z0, z1, "yellow", normal)

def glazed_wall(p0, p1, normal):
    vplane(p0, p1, DECK, Z_LOW, "white", normal)
    band(p0, p1, Z_LOW, Z_B1, normal, 0.03)
    off = Vector((normal[0], normal[1], 0)) * -0.04
    g0 = Vector((p0[0], p0[1], 0)) + off; g1 = Vector((p1[0], p1[1], 0)) + off
    vplane(g0, g1, Z_B1, Z_GT, "glass", normal)
    band(p0, p1, Z_GT, Z_B2, normal, 0.03)
    vplane(p0, p1, Z_B2, CAB_TOP, "white", normal)
    d = Vector((p1[0] - p0[0], p1[1] - p0[1], 0)); Ltot = d.length; d.normalize()
    nv = Vector((normal[0], normal[1], 0))
    k = max(2, int(Ltot / 1.3))
    for i in range(k + 1):
        c = Vector((p0[0], p0[1], 0)) + d * (Ltot * i / k)
        obox(Vector((c.x, c.y, (Z_B1 + Z_GT) / 2)), d, nv, Z, 0.045, 0.05, (Z_GT - Z_B1) / 2, "alu")
    m = Vector(((p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2, 0))
    obox(Vector((m.x, m.y, Z_B1 + 0.02)) + nv * 0.04, d, nv, Z, Ltot / 2, 0.06, 0.03, "alu")    # sill

glazed_wall((CAB_X0, CAB_Y0), (CAB_X1, CAB_Y0), (0, -1))       # boat side
glazed_wall((CAB_X1, CAB_Y1), (CAB_X0, CAB_Y1), (0, 1))        # land side
glazed_wall((CAB_X0, CAB_Y1), (CAB_X0, CAB_Y0), (-1, 0))       # closed end
# open end: door in a glazed screen
vplane((CAB_X1, CAB_Y0), (CAB_X1, CAB_Y1), DECK, CAB_TOP, "glass", (1, 0))
for yy in (-0.55, 0.65):
    obox(Vector((CAB_X1 + 0.03, yy, (DECK + CAB_TOP) / 2)), Y, X, Z, 0.05, 0.04, (CAB_TOP - DECK) / 2, "alu")
band((CAB_X1, CAB_Y0), (CAB_X1, CAB_Y1), Z_GT, Z_B2, (1, 0), 0.05)
obox(Vector((CAB_X1 + 0.07, 0.05, DECK + 2.12)), Y, X, Z, 0.68, 0.05, 0.08, "hazard")         # header over the door
# letter plaque (white square with the stop letter) at the boarding corner, facing the boat, on its own post
lp = Vector((CAB_X1 + 0.25, -HW + 0.25, 0))
pa, pb = lp + Vector((-0.27, 0, 0)), lp + Vector((0.27, 0, 0))
SIGNS.extend((f, X, pa, pb, "letter") for f in vplane(pa, pb, CAB_TOP - 1.05, CAB_TOP - 0.51, "sign", (0, -1)))
lathe(lp.x, lp.y + 0.05, DECK, [(0.05, 0.0), (0.05, ROOF0 - DECK)], "alu", segs=8)

# ---- roof with deep fascia
bx(CAB_X0 - 0.45, HL - 0.15, -HW - 0.15, HW + 0.1, ROOF0, ROOF1, "roof", True)
for (y0, y1) in ((-HW - 0.18, -HW - 0.15), (HW + 0.1, HW + 0.13)):
    bx(CAB_X0 - 0.45, HL - 0.15, y0, y1, ROOF0 - 0.06, ROOF1 + 0.03, "fascia", True)
for x0, x1 in ((CAB_X0 - 0.48, CAB_X0 - 0.45), (HL - 0.15, HL - 0.12)):
    bx(x0, x1, -HW - 0.18, HW + 0.13, ROOF0 - 0.06, ROOF1 + 0.03, "fascia", True)
# posts, railings round the open end and the shore side, boarding gate on the boat side
for (x, y) in ((HL - 0.35, -HW + 0.25), (HL - 0.35, HW - 0.25)):
    lathe(x, y, DECK, [(0.06, 0.0), (0.06, ROOF0 - DECK)], "alu", segs=8)
def rail(p0, p1):
    for zr in (DECK + 0.5, DECK + 1.05):
        tube([Vector((p0[0], p0[1], zr)), Vector((p1[0], p1[1], zr))], 0.025, "alu", segs=6)
    n_ = max(2, int((Vector(p1) - Vector(p0)).length / 1.2))
    for i in range(n_ + 1):
        t = i / n_
        x = p0[0] + (p1[0] - p0[0]) * t; y = p0[1] + (p1[1] - p0[1]) * t
        tube([Vector((x, y, DECK)), Vector((x, y, DECK + 1.07))], 0.022, "alu", segs=6)
rail((CAB_X1 + 2.6, HW - 0.25), (HL - 0.4, HW - 0.25))
rail((HL - 0.35, -HW + 1.6), (HL - 0.35, HW - 0.3))
rail((CAB_X0 + 0.1, CAB_Y1 + 0.15), (CAB_X1 - 0.1, CAB_Y1 + 0.15))
for gx in (CAB_X1 + 0.6, CAB_X1 + 2.0):                         # sliding gate frame
    tube([Vector((gx, -HW + 0.3, DECK)), Vector((gx, -HW + 0.3, DECK + 1.1))], 0.03, "alu")
tube([Vector((CAB_X1 + 0.6, -HW + 0.3, DECK + 1.1)), Vector((CAB_X1 + 2.0, -HW + 0.3, DECK + 1.1))], 0.035, "yellow")
tube([Vector((CAB_X1 + 0.6, -HW + 0.3, DECK + 0.55)), Vector((CAB_X1 + 2.0, -HW + 0.3, DECK + 0.55))], 0.02, "alu")
# lifebuoy on the closed end, orange with white bands (a torus standing off the wall)
lb = Vector((CAB_X0 + 0.9, CAB_Y0 - 0.16, DECK + 1.25))         # on the boat face, by the closed end
R_, r_ = 0.32, 0.07
for i in range(16):
    a0, a1 = 2 * math.pi * i / 16, 2 * math.pi * (i + 1) / 16
    p0_ = lb + Vector((math.cos(a0) * R_, 0, math.sin(a0) * R_)); p1_ = lb + Vector((math.cos(a1) * R_, 0, math.sin(a1) * R_))
    tube([p0_, p1_], r_, "ring_w" if i % 4 == 0 else "ring_o", segs=6)
obox(lb + Vector((0, 0.08, R_ + 0.05)), X, Y, Z, 0.08, 0.04, 0.06, "grey")           # hook
# white ACTV plaque mid lower band (boat face), lamp head on the boarding corner, info boards + validator
img_panel(Vector(((CAB_X0 + CAB_X1) / 2, CAB_Y0 - 0.08, (Z_LOW + Z_B1) / 2)), X, -Y, 0.34, 0.11, "img_plaque")
lathe(HL - 0.35, -HW + 0.25, ROOF1 + 0.1, [(0.03, 0.0), (0.03, 0.35)], "grey", segs=6)
blob(Vector((HL - 0.35, -HW + 0.25, ROOF1 + 0.55)), 0.16, 0.16, 0.12, "white", segs=8, rings=4)
for bxo in (CAB_X1 + 2.7, CAB_X1 + 3.5):
    if bxo < HL - 0.6:
        img_panel(Vector((bxo, HW - 0.42, DECK + 1.35)), X, -Y, 0.3, 0.45, "img_timetable" if bxo < CAB_X1 + 3.0 else "img_linemap", "grey")
        lathe(bxo, HW - 0.4, DECK, [(0.03, 0.0), (0.03, 0.9)], "grey", segs=6)
lathe(CAB_X1 + 2.3, 0.6, DECK, [(0.08, 0.0), (0.08, 1.05), (0.1, 1.1), (0.1, 1.25), (0.0, 1.26)], "grey", segs=8)   # validator

# ---- gangway to the fondamenta
GX0, GX1 = CAB_X1 + 0.4, CAB_X1 + 2.4; GL = 5.5
ramp = lambda t: DECK + 0.05 + (1.25 - DECK - 0.05) * t
for i in range(6):
    t0, t1 = i / 6, (i + 1) / 6
    y0, y1 = HW + t0 * GL, HW + t1 * GL
    face([Vector((GX0, y0, ramp(t0))), Vector((GX1, y0, ramp(t0))), Vector((GX1, y1, ramp(t1))), Vector((GX0, y1, ramp(t1)))], "planks")
    for x in (GX0, GX1):
        face([Vector((x, y0, ramp(t0) - 0.25)), Vector((x, y1, ramp(t1) - 0.25)), Vector((x, y1, ramp(t1) + 0.05)), Vector((x, y0, ramp(t0) + 0.05))], "alu")
for x in (GX0, GX1):
    tube([Vector((x, HW + 0.1, ramp(0.0) + 1.0)), Vector((x, HW + GL - 0.1, ramp(1.0) + 1.0))], 0.03, "alu")
    tube([Vector((x, HW + 0.1, ramp(0.0) + 0.5)), Vector((x, HW + GL - 0.1, ramp(1.0) + 0.5))], 0.02, "alu")
    for t in (0.02, 0.5, 0.98):
        tube([Vector((x, HW + t * GL, ramp(t))), Vector((x, HW + t * GL, ramp(t) + 1.0))], 0.025, "alu")

# ---------------------------------------------------------------- finish
me = bpy.data.meshes.new("actv"); bm.normal_update()
suv = bm.loops.layers.uv.new("signuv")
for item in SIGNS:
    f, d, a, b = item[:4]
    if f is None: continue
    letter = len(item) > 4
    zs = [v.co.z for v in f.verts]
    L_ = (b - a).length
    for lp_ in f.loops:
        co = lp_.vert.co
        t = (Vector((co.x, co.y, 0)) - Vector((a.x, a.y, 0))).dot(d) / L_
        t = min(1.0, max(0.0, t))
        u = (0.86 + 0.13 * t) if letter else 0.8 * t
        v = (co.z - min(zs)) / (max(zs) - min(zs))
        lp_[suv].uv = (u, v)
puv = bm.loops.layers.uv.new("puv")
for f, c, al, hw, hh in IMGF:
    for lp_ in f.loops:
        d_ = lp_.vert.co - c
        lp_[puv].uv = (0.5 + d_.dot(al) / (2 * hw), 0.5 + d_.z / (2 * hh))
bm.to_mesh(me); bm.free()
ob = bpy.data.objects.new("ACTV", me); bpy.context.scene.collection.objects.link(ob)
for m in MAT_ORDER: me.materials.append(MATS[m][0])
uvm = me.uv_layers.new(name="atlas"); me.uv_layers.active = uvm
bpy.context.view_layer.objects.active = ob; ob.select_set(True)
bpy.ops.object.mode_set(mode="EDIT"); bpy.ops.mesh.select_all(action="SELECT")
bpy.ops.uv.smart_project(angle_limit=math.radians(60), island_margin=0.0, area_weight=0.0, scale_to_bounds=False)
bpy.context.scene.tool_settings.use_uv_select_sync = True
bpy.ops.mesh.select_all(action="SELECT")
bpy.ops.uv.average_islands_scale()
bpy.ops.uv.pack_islands(rotate=True, scale=True, margin_method="FRACTION", margin=0.001, shape_method="CONVEX")
bpy.ops.object.mode_set(mode="OBJECT")
tris = sum(len(p.vertices) - 2 for p in ob.data.polygons)
img = bpy.data.images.new("atlas", ATLAS_RES, ATLAS_RES, alpha=False)
for name, (m, layer) in MATS.items():
    n = m.node_tree.nodes.new("ShaderNodeTexImage"); n.image = img; m.node_tree.nodes.active = n
sc = bpy.context.scene
sc.render.engine = "CYCLES"; sc.cycles.device = "CPU"; sc.cycles.samples = int(os.environ.get("BAKE_SAMPLES", "4"))
bpy.ops.object.bake(type="EMIT", margin=6, uv_layer="atlas")
img.filepath_raw = ATLAS; img.file_format = "PNG"; img.save()
ob["layers"] = [MATS[n][1] for n in MAT_ORDER]
bpy.ops.wm.save_as_mainfile(filepath=OUT)
print("ACTV pontoon", LENGTH, "m saved", OUT, ATLAS, "triangles", tris)

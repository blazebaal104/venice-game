"""Venetian bridge kit - small/medium types, long types and three special bridges, one shared baked atlas.

Local frame of every bridge: +X along the walkway (centred on 0, total length L between the two quay edges), +Y across
(width W), +Z up, mean water z = 0, quay / paving level G = 1.1.  Every type is parametric (L, W, R...) so the game can
stretch it: scale X for length (the deck profile and the stair treads are generated for the nominal L, the preview
renders show each type at 0.8x / nominal / 1.4x).

Types (see TYPES at the bottom):
  small / medium (masonry arches with stairs): brick, balustrade, stucco, noparapet (Ponte Chiodo style), irongate, castiron
  long: timber (pile pontile), liberta (road viaduct), rail (1846 railway viaduct), girder (concrete beam), bowstring
        (steel tied arch), ribbon (cycle path on slender piers)
  special: guglie (Ponte delle Guglie), scalzi (Ponte degli Scalzi), costituzione (Calatrava bridge, Piazzale Roma)
usage: python build_bridges.py -- out.blend atlas.png [res]      env BR_ONLY=brick,scalzi limits the build"""
import bpy, bmesh, math, sys, os, random
from mathutils import Vector, Matrix

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
OUT = ARGS[0] if len(ARGS) > 0 else "/home/claude/venice/bridges.blend"
ATLAS = ARGS[1] if len(ARGS) > 1 else "/home/claude/venice/bridges_atlas.png"
ATLAS_RES = int(ARGS[2]) if len(ARGS) > 2 else 2048
ONLY = [s for s in os.environ.get("BR_ONLY", "").split(",") if s]
random.seed(5)
bpy.ops.wm.read_factory_settings(use_empty=True)
ARCH_CZ = 0.0; R_ARCH = 1.0
HERE = os.path.dirname(os.path.abspath(__file__))
exec(open(os.path.join(HERE, "lmcommon.py")).read())
exec(open(os.path.join(HERE, "lmmulti.py")).read())

G = 1.1
PI = math.pi

# ---------------------------------------------------------------- materials
def m_brick(tint=(1.0, 1.0, 1.0), bw=0.26, bh=0.075):
    def f(nt, coord):
        base = N(nt, "ShaderNodeTexNoise", i_Scale=1.6, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
        c = mix(nt, (0.64 * tint[0], 0.33 * tint[1], 0.24 * tint[2], 1), (0.80 * tint[0], 0.46 * tint[1], 0.34 * tint[2], 1), base.outputs["Fac"])
        c = joints(nt, c, uv_face(nt, coord), bw, bh, 0.5, mortar=0.012, jcol=(0.62, 0.58, 0.50), var=0.22)
        return weather(nt, coord, c, streak=0.55, tide=True, ao_min=0.55, grime=0.12)
    return f

def m_stucco(col, patch=0.35):
    """rendered wall with the render fallen away in places, showing the brick"""
    def f(nt, coord):
        n1 = N(nt, "ShaderNodeTexNoise", i_Scale=1.3, i_Detail=5.0); L(nt, coord, n1.inputs["Vector"])
        c = mix(nt, (col[0] * 0.92, col[1] * 0.92, col[2] * 0.92, 1), (col[0] * 1.12, col[1] * 1.12, col[2] * 1.12, 1), n1.outputs["Fac"])
        brick = joints(nt, mix(nt, (0.50, 0.27, 0.20, 1), (0.64, 0.36, 0.26, 1), n1.outputs["Fac"]), uv_face(nt, coord), 0.26, 0.075, 0.5, mortar=0.012, jcol=(0.60, 0.56, 0.48), var=0.22)
        n2 = N(nt, "ShaderNodeTexNoise", i_Scale=2.1, i_Detail=6.0, i_Roughness=0.65); L(nt, coord, n2.inputs["Vector"])
        sz = sep_z(nt, coord)
        low = mathn(nt, "MULTIPLY", ramp_val(nt, sz.outputs["Z"], 2.2, 0.6), 0.35)       # more damage towards the waterline
        mask = ramp_val(nt, mathn(nt, "ADD", n2.outputs["Fac"], low), 0.66, 0.70)
        c = mix(nt, c, brick, mask)
        return weather(nt, coord, c, streak=0.5, tide=True, ao_min=0.55, grime=0.10)
    return f

def m_planks(nt, coord):
    """deck planks running along X: horizontal faces map (x, y)"""
    base = N(nt, "ShaderNodeTexNoise", i_Scale=5.0, i_Detail=5.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.30, 0.22, 0.15, 1), (0.47, 0.37, 0.27, 1), base.outputs["Fac"])
    sz = sep_z(nt, coord); cv = N(nt, "ShaderNodeCombineXYZ"); L(nt, sz.outputs["X"], cv.inputs["X"]); L(nt, sz.outputs["Y"], cv.inputs["Y"])
    c = joints(nt, c, cv.outputs[0], 2.4, 0.16, 0.37, mortar=0.012, jcol=(0.06, 0.05, 0.04), var=0.3)
    g = N(nt, "ShaderNodeTexNoise", i_Scale=1.0, i_Detail=4.0); L(nt, coord, g.inputs["Vector"])
    c = mix(nt, c, (0.56, 0.54, 0.50, 1), mathn(nt, "MULTIPLY", ramp_val(nt, g.outputs["Fac"], 0.5, 0.75), 0.4))   # grey weathered patches
    return weather(nt, coord, c, streak=0.0, tide=False, ao_min=0.5, grime=0.1)

def m_wood(nt, coord):
    """weathered posts / piles / beams: vertical grain"""
    mp = N(nt, "ShaderNodeMapping"); L(nt, coord, mp.inputs[0]); mp.inputs["Scale"].default_value = (14.0, 14.0, 1.4)
    gr = N(nt, "ShaderNodeTexNoise", i_Scale=3.0, i_Detail=6.0); L(nt, mp.outputs[0], gr.inputs["Vector"])
    c = mix(nt, (0.18, 0.14, 0.10, 1), (0.40, 0.33, 0.26, 1), gr.outputs["Fac"])
    sz = sep_z(nt, coord)
    wet = ramp_val(nt, sz.outputs["Z"], 1.4, 0.2)
    c = mix(nt, c, (0.10, 0.13, 0.08, 1), mathn(nt, "MULTIPLY", wet, 0.8))                       # green slime on the piles
    return weather(nt, coord, c, streak=0.0, tide=False, ao_min=0.45, grime=0.1)

def m_concrete(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=1.5, i_Detail=5.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.46, 0.45, 0.43, 1), (0.60, 0.59, 0.56, 1), base.outputs["Fac"])
    c = joints(nt, c, uv_face(nt, coord), 2.6, 1.3, 0.0, mortar=0.006, jcol=(0.25, 0.25, 0.24), var=0.05)   # shuttering joints
    return weather(nt, coord, c, streak=0.8, tide=True, ao_min=0.4, grime=0.5)

def m_steel(col, rough=0.18):
    def f(nt, coord):
        base = N(nt, "ShaderNodeTexNoise", i_Scale=3.0, i_Detail=5.0); L(nt, coord, base.inputs["Vector"])
        c = mix(nt, (col[0] * (1 - rough), col[1] * (1 - rough), col[2] * (1 - rough), 1), (*col, 1), base.outputs["Fac"])
        return weather(nt, coord, c, streak=0.3, tide=False, ao_min=0.5, grime=0.12)
    return f

def m_glassp(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=1.2, i_Detail=2.0); L(nt, coord, base.inputs["Vector"])
    return mix(nt, (0.30, 0.42, 0.42, 1), (0.52, 0.64, 0.62, 1), base.outputs["Fac"])

def m_ballast(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=60.0, i_Detail=2.0); L(nt, coord, base.inputs["Vector"])
    return mix(nt, (0.20, 0.19, 0.18, 1), (0.42, 0.40, 0.37, 1), base.outputs["Fac"])

def m_asphalt(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=40.0, i_Detail=2.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.10, 0.10, 0.10, 1), (0.20, 0.20, 0.20, 1), base.outputs["Fac"])
    sz = sep_z(nt, coord)       # white lane line along the middle
    line = mathn(nt, "MULTIPLY", ramp_val(nt, mathn(nt, "ABSOLUTE", sz.outputs["Y"]), 0.14, 0.08), 0.0)
    return c

node_mat("brick", m_brick(), 18)
node_mat("brick2", m_brick((1.08, 0.95, 0.85)), 18)
node_mat("stucco_ochre", m_stucco((0.82, 0.66, 0.45)), 18)
node_mat("stucco_pink", m_stucco((0.80, 0.56, 0.46)), 18)
node_mat("stone", lambda nt, c: m_istrian_plain(nt, c, 1.05, 0.55, 0.40), 15)
node_mat("ashlar", lambda nt, c: weather(nt, c, joints(nt, stone_base(nt, c), uv_face(nt, c), 1.1, 0.42, 0.5, mortar=0.010), streak=0.30, ao_min=0.62), 17)
node_mat("slab", lambda nt, c: m_istrian_plain(nt, c, 1.02, 0.3, 0.6), 15)
node_mat("ring", lambda nt, c: m_archivolt(nt, c), 15)
node_mat("vault", m_vault, 11)
node_mat("tread", m_trachyte, 25)
node_mat("nosing", m_nosing, 15)
node_mat("planks", m_planks, 25)
node_mat("wood", m_wood, 11)
node_mat("concrete", m_concrete, 14)
node_mat("iron", m_flat((0.045, 0.05, 0.045), 0.25, 0.5, 0.0), 33)
node_mat("castiron", m_steel((0.10, 0.14, 0.12), 0.35), 33)
node_mat("steel_green", m_steel((0.28, 0.40, 0.36), 0.25), 33)
node_mat("steel_grey", m_steel((0.46, 0.47, 0.48), 0.2), 33)
node_mat("steel_brown", m_steel((0.20, 0.15, 0.12), 0.25), 33)
node_mat("bronze", m_flat((0.30, 0.20, 0.10), 0.2, 0.6), 33)
node_mat("glass", m_glassp, 33)
node_mat("ballast", m_ballast, 25)
node_mat("asphalt", m_asphalt, 25)
node_mat("rail", m_flat((0.30, 0.24, 0.20), 0.3, 0.6), 33)
node_mat("dark", m_flat((0.035, 0.03, 0.028), 0.2, 0.8), 33)
MAT_ORDER = list(MATS.keys())

# ---------------------------------------------------------------- geometry helpers
def V(*a): return Vector(a)

def qd(a, b, c, d, mat, out):
    """quad whose normal is made to point along `out`"""
    return oface([Vector(a), Vector(b), Vector(c), Vector(d)], Vector(out), mat)

def tri(a, b, c, mat, out):
    return oface([Vector(a), Vector(b), Vector(c)], Vector(out), mat)

def bx(x0, x1, y0, y1, z0, z1, mat, bottom=False):
    """axis aligned box, all faces oriented outward"""
    quad_ = lambda *p: None
    qd(V(x0, y0, z1), V(x1, y0, z1), V(x1, y1, z1), V(x0, y1, z1), mat, (0, 0, 1))
    if bottom: qd(V(x0, y0, z0), V(x1, y0, z0), V(x1, y1, z0), V(x0, y1, z0), mat, (0, 0, -1))
    qd(V(x0, y0, z0), V(x1, y0, z0), V(x1, y0, z1), V(x0, y0, z1), mat, (0, -1, 0))
    qd(V(x0, y1, z0), V(x1, y1, z0), V(x1, y1, z1), V(x0, y1, z1), mat, (0, 1, 0))
    qd(V(x0, y0, z0), V(x0, y1, z0), V(x0, y1, z1), V(x0, y0, z1), mat, (-1, 0, 0))
    qd(V(x1, y0, z0), V(x1, y1, z0), V(x1, y1, z1), V(x1, y0, z1), mat, (1, 0, 0))

def tbox(x0, x1, y0, y1, za0, za1, zb0, zb1, mat):
    """box whose bottom/top follow a slope: bottom z goes za0 -> za1, top zb0 -> zb1 along x"""
    qd(V(x0, y0, zb0), V(x1, y0, zb1), V(x1, y1, zb1), V(x0, y1, zb0), mat, (0, 0, 1))
    qd(V(x0, y0, za0), V(x1, y0, za1), V(x1, y1, za1), V(x0, y1, za0), mat, (0, 0, -1))
    qd(V(x0, y0, za0), V(x1, y0, za1), V(x1, y0, zb1), V(x0, y0, zb0), mat, (0, -1, 0))
    qd(V(x0, y1, za0), V(x1, y1, za1), V(x1, y1, zb1), V(x0, y1, zb0), mat, (0, 1, 0))

def pyramid(cx, cy, z0, hx, hy, h, mat):
    c = [V(cx - hx, cy - hy, z0), V(cx + hx, cy - hy, z0), V(cx + hx, cy + hy, z0), V(cx - hx, cy + hy, z0)]
    ap = V(cx, cy, z0 + h)
    for i in range(4):
        a, b = c[i], c[(i + 1) % 4]
        tri(a, b, ap, mat, ((a + b) / 2 - V(cx, cy, z0)) + V(0, 0, 0.3))

def post(x, y, z0, z1, r, mat):
    bx(x - r, x + r, y - r, y + r, z0, z1, mat)

def xs_dense(a, b, step, extra=()):
    n = max(1, int(math.ceil((b - a) / step)))
    xs = [a + (b - a) * i / n for i in range(n + 1)] + list(extra)
    xs = sorted(set(round(x, 5) for x in xs))
    return xs

# ---------------------------------------------------------------- masonry arch bridge (stairs both sides)
class Arch:
    """deck profile, intrados and common surfaces of a single-arch stepped bridge"""
    def __init__(s, L, W, R, abut=0.75, t=0.55, springz=0.40, plateau=0.16, expo=1.4, tread=0.38, riser=0.16, wall=-1.0, ph=0.0):
        s.L, s.W, s.R = L, W, R
        s.hl = L / 2; s.S = s.hl - abut; s.c = plateau * s.hl
        s.zc = G + R - t; h = s.zc - springz
        s.r = (s.S ** 2 + h * h) / (2 * h); s.cz = s.zc - s.r
        s.springz = springz; s.tread = tread; s.riser = riser; s.wall = wall

    def raw(s, x):
        u = min(max((abs(x) - s.c) / (s.hl - s.c), 0.0), 1.0)
        return G + s.R * (1 - u ** 1.4)

    def intr(s, x):
        ax = abs(x)
        if ax >= s.S: return s.springz
        return s.cz + math.sqrt(max(s.r ** 2 - ax * ax, 0))

    def zq(s, x):
        return G + round((s.raw(x) - G) / s.riser) * s.riser

    def xs_side(s, step=0.22):
        return xs_dense(-s.hl, s.hl, step, extra=(-s.S, s.S))

    def spandrel(s, side, mat, top_off=0.0):
        """outer face from the waterline / arch up to the deck line (+top_off)"""
        y = side * s.W / 2
        xs = s.xs_side()
        for a, b in zip(xs[:-1], xs[1:]):
            m = (a + b) / 2
            if abs(m) < s.S: za, zb = s.intr(a), s.intr(b)
            else: za = zb = s.wall
            qd(V(a, y, za), V(b, y, zb), V(b, y, s.raw(b) + top_off), V(a, y, s.raw(a) + top_off), mat, (0, side, 0))

    def intrados(s, mat):
        xs = xs_dense(-s.S, s.S, 0.25)
        for a, b in zip(xs[:-1], xs[1:]):
            qd(V(a, -s.W / 2, s.intr(a)), V(b, -s.W / 2, s.intr(b)), V(b, s.W / 2, s.intr(b)), V(a, s.W / 2, s.intr(a)), mat, (0, 0, -1))
        for sx in (-1, 1):   # abutment faces inside the opening
            qd(V(sx * s.S, -s.W / 2, s.wall), V(sx * s.S, s.W / 2, s.wall), V(sx * s.S, s.W / 2, s.springz), V(sx * s.S, -s.W / 2, s.springz), mat, (-sx, 0, 0))

    def ring(s, side, mat, w=0.36, proud=0.05, keystone=True):
        y = side * (s.W / 2 + proud)
        xs = xs_dense(-s.S, s.S, 0.2)
        for a, b in zip(xs[:-1], xs[1:]):
            qd(V(a, y, s.intr(a)), V(b, y, s.intr(b)), V(b, y, s.intr(b) + w), V(a, y, s.intr(a) + w), mat, (0, side, 0))
            qd(V(a, y, s.intr(a) + w), V(b, y, s.intr(b) + w), V(b, y - side * proud, s.intr(b) + w), V(a, y - side * proud, s.intr(a) + w), mat, (0, 0, 1))
        if keystone:
            kw = 0.32; kz = s.intr(0)
            bx(-kw, kw, side * s.W / 2 - (proud + 0.04 if side < 0 else 0), side * s.W / 2 + (proud + 0.04 if side > 0 else 0), kz - 0.03, kz + w + 0.20, mat)

    def deck(s, tread_mat="tread", riser_mat="nosing", y0=None, y1=None):
        """stepped walking surface between y0..y1 (default full width)"""
        y0 = -s.W / 2 if y0 is None else y0; y1 = s.W / 2 if y1 is None else y1
        n = int(math.ceil(s.L / s.tread)); xs = [-s.hl + s.L * i / n for i in range(n + 1)]
        zs = [s.zq((a + b) / 2) for a, b in zip(xs[:-1], xs[1:])]
        for k, (a, b) in enumerate(zip(xs[:-1], xs[1:])):
            qd(V(a, y0, zs[k]), V(b, y0, zs[k]), V(b, y1, zs[k]), V(a, y1, zs[k]), tread_mat, (0, 0, 1))
            if k + 1 < len(zs) and abs(zs[k + 1] - zs[k]) > 0.01:
                lo, hi = (zs[k], zs[k + 1]) if zs[k] < zs[k + 1] else (zs[k + 1], zs[k])
                sgn = 1 if zs[k] > zs[k + 1] else -1
                qd(V(b, y0, lo), V(b, y1, lo), V(b, y1, hi), V(b, y0, hi), riser_mat, (sgn, 0, 0))
        return xs, zs

    def ends(s, mat, top_off=0.0):
        """close the two ends (x = +-L/2) of the solid between the faces"""
        for sx in (-1, 1):
            qd(V(sx * s.hl, -s.W / 2, s.wall), V(sx * s.hl, s.W / 2, s.wall), V(sx * s.hl, s.W / 2, G + top_off), V(sx * s.hl, -s.W / 2, G + top_off), mat, (sx, 0, 0))

# ---------------------------------------------------------------- parapets (follow the smooth deck line)
def parapet_wall(A, mat_out, mat_in, mat_top, ph=0.78, pt=0.30, pier=True, pier_mat="stone"):
    """solid wall with a coping; the outer face is built here (starting at the deck line), spandrel carries it below"""
    xs = xs_dense(-A.hl, A.hl, 0.4)
    for side in (-1, 1):
        yo = side * A.W / 2; yi = side * (A.W / 2 - pt)
        for a, b in zip(xs[:-1], xs[1:]):
            za, zb = A.raw(a) + ph, A.raw(b) + ph
            qd(V(a, yo, A.raw(a)), V(b, yo, A.raw(b)), V(b, yo, zb), V(a, yo, za), mat_out, (0, side, 0))
            m = (a + b) / 2; zt = A.zq(m)
            qd(V(a, yi, zt), V(b, yi, zt), V(b, yi, zb), V(a, yi, za), mat_in, (0, -side, 0))
            qd(V(a, yo + 0.04 * side, za + 0.05), V(b, yo + 0.04 * side, zb + 0.05), V(b, yi - 0.04 * side, zb + 0.05), V(a, yi - 0.04 * side, za + 0.05), mat_top, (0, 0, 1))
            qd(V(a, yo + 0.04 * side, za), V(b, yo + 0.04 * side, zb), V(b, yo + 0.04 * side, zb + 0.05), V(a, yo + 0.04 * side, za + 0.05), mat_top, (0, side, 0))
            qd(V(a, yi - 0.04 * side, za), V(b, yi - 0.04 * side, zb), V(b, yi - 0.04 * side, zb + 0.05), V(a, yi - 0.04 * side, za + 0.05), mat_top, (0, -side, 0))
        if pier:
            for sx in (-1, 1):
                px = sx * (A.hl - 0.22)
                bx(px - 0.26, px + 0.26, side * (A.W / 2 - 0.30) - 0.26 + 0.0, side * (A.W / 2 - 0.30) + 0.26, G - 0.2, G + ph + 0.30, pier_mat)
                pyramid(px, side * (A.W / 2 - 0.30), G + ph + 0.30, 0.22, 0.22, 0.18, pier_mat)

def balustrade(A, ph=0.95, pillar_every=2.2, mat="stone", rail="stone"):
    """stone balustrade: plinth, turned (square-section) balusters, rail; pillars at the ends and every ~2 m"""
    for side in (-1, 1):
        y = side * (A.W / 2 - 0.16)
        xs = xs_dense(-A.hl, A.hl, 0.18)
        for a, b in zip(xs[:-1], xs[1:]):       # plinth + rail strips (follow the curve)
            tbox(a, b, y - 0.16, y + 0.16, A.raw(a), A.raw(b), A.raw(a) + 0.20, A.raw(b) + 0.20, mat)
            tbox(a, b, y - 0.17, y + 0.17, A.raw(a) + ph - 0.16, A.raw(b) + ph - 0.16, A.raw(a) + ph, A.raw(b) + ph, rail)
        n = int(A.L / 0.24)
        npil = max(2, int(round(A.L / pillar_every)))
        pil = [-A.hl + 0.25 + (A.L - 0.5) * i / npil for i in range(npil + 1)]
        for k in range(n + 1):
            x = -A.hl + 0.25 + (A.L - 0.5) * k / n
            if any(abs(x - p) < 0.2 for p in pil): continue
            z0 = A.raw(x) + 0.18
            # baluster: vase = wide belly low, thin neck
            bx(x - 0.045, x + 0.045, y - 0.045, y + 0.045, z0, z0 + ph - 0.30, mat)
            bx(x - 0.065, x + 0.065, y - 0.065, y + 0.065, z0 + 0.08, z0 + 0.28, mat)
        for p in pil:
            z0 = A.raw(p)
            bx(p - 0.19, p + 0.19, y - 0.19, y + 0.19, z0, z0 + ph + 0.18, mat)
            bx(p - 0.23, p + 0.23, y - 0.23, y + 0.23, z0 + ph + 0.18, z0 + ph + 0.26, rail)

def iron_rail(A, ph=1.0, kerb=0.26, bars=0.13, mat="iron", kerb_mat="slab", posts_every=2.0, scroll=False):
    for side in (-1, 1):
        y = side * (A.W / 2 - 0.12)
        xs = xs_dense(-A.hl, A.hl, 0.3)
        for a, b in zip(xs[:-1], xs[1:]):
            tbox(a, b, y - 0.12, y + 0.12, A.raw(a), A.raw(b), A.raw(a) + kerb, A.raw(b) + kerb, kerb_mat)
            for z_off in (kerb + 0.07, ph - 0.03):
                tbox(a, b, y - 0.02, y + 0.02, A.raw(a) + z_off - 0.02, A.raw(b) + z_off - 0.02, A.raw(a) + z_off + 0.02, A.raw(b) + z_off + 0.02, mat)
        n = int(A.L / bars)
        for k in range(n + 1):
            x = -A.hl + 0.15 + (A.L - 0.3) * k / n
            z0 = A.raw(x) + kerb
            bx(x - 0.011, x + 0.011, y - 0.011, y + 0.011, z0, z0 + ph - kerb, mat)
            if k % 2 == 0: pyramid(x, y, z0 + ph - kerb, 0.02, 0.02, 0.07, mat)           # spear points on alternate bars
        np_ = max(2, int(round(A.L / posts_every)))
        for i in range(np_ + 1):
            x = -A.hl + 0.15 + (A.L - 0.3) * i / np_
            z0 = A.raw(x) + kerb
            bx(x - 0.045, x + 0.045, y - 0.045, y + 0.045, z0, z0 + ph + 0.05, mat)
            pyramid(x, y, z0 + ph + 0.05, 0.05, 0.05, 0.10, mat)

def kerb_only(A, w=0.30, h=0.22, mat="slab"):
    """no parapet at all (Ponte Chiodo / Ponte dei Pugni): just a stone kerb along each edge, following the steps"""
    n = int(math.ceil(A.L / A.tread)); xs = [-A.hl + A.L * i / n for i in range(n + 1)]
    for side in (-1, 1):
        y0 = side * (A.W / 2 - w); y1 = side * A.W / 2
        for a, b in zip(xs[:-1], xs[1:]):
            z = A.zq((a + b) / 2)
            bx(a, b, min(y0, y1), max(y0, y1), z - 0.02, z + h, mat)

# ---------------------------------------------------------------- small types
def build_masonry(kind):
    spec = dict(
        brick=dict(L=7.0, W=2.8, R=1.55),
        balustrade=dict(L=9.0, W=3.3, R=1.95),
        stucco=dict(L=6.0, W=2.5, R=1.35),
        noparapet=dict(L=8.0, W=3.0, R=1.80),
        irongate=dict(L=12.0, W=3.7, R=2.45),
    )[kind]
    A = Arch(**spec, t={"brick": 0.5, "balustrade": 0.6, "stucco": 0.5, "noparapet": 0.55, "irongate": 0.65}[kind])
    new_part()
    sp = {"brick": "brick", "balustrade": "ashlar", "stucco": "stucco_ochre", "noparapet": "brick2", "irongate": "stucco_pink"}[kind]
    for side in (-1, 1):
        if kind in ("brick", "stucco", "noparapet", "irongate"):
            A.spandrel(side, sp, 0.0)
        else:
            A.spandrel(side, sp, 0.0)
        A.ring(side, "ring" if kind != "noparapet" else "stone", w=0.34 if kind != "balustrade" else 0.42)
    A.intrados("vault"); A.ends(sp)
    A.deck("tread", "nosing")
    if kind == "brick":
        parapet_wall(A, "brick", "brick", "slab", ph=0.80, pt=0.32)
    elif kind == "stucco":
        parapet_wall(A, "stucco_ochre", "stucco_ochre", "stone", ph=0.88, pt=0.36)
    elif kind == "balustrade":
        balustrade(A)
        # string course under the balustrade
        for side in (-1, 1):
            xs = xs_dense(-A.hl, A.hl, 0.4)
            for a, b in zip(xs[:-1], xs[1:]):
                tbox(a, b, side * A.W / 2 - (0.0 if side < 0 else 0.10), side * A.W / 2 + (0.10 if side > 0 else 0.0), A.raw(a) - 0.22, A.raw(b) - 0.22, A.raw(a) + 0.02, A.raw(b) + 0.02, "stone")
    elif kind == "noparapet":
        kerb_only(A)
    elif kind == "irongate":
        iron_rail(A, ph=1.05)
        for side in (-1, 1):   # string course
            xs = xs_dense(-A.hl, A.hl, 0.4)
            for a, b in zip(xs[:-1], xs[1:]):
                tbox(a, b, side * A.W / 2 - (0.0 if side < 0 else 0.07), side * A.W / 2 + (0.07 if side > 0 else 0.0), A.raw(a) - 0.12, A.raw(b) - 0.12, A.raw(a) + 0.0, A.raw(b) + 0.0, "stone")
    return end_part("b_" + kind, 1.0), spec

def build_castiron():
    """19th-century cast-iron bridge: shallow arch of iron ribs, pierced spandrels, plank steps, iron railing"""
    L, W, R = 14.0, 3.4, 2.2
    A = Arch(L, W, R, abut=1.0, t=0.45, springz=0.5, plateau=0.12, tread=0.36)
    new_part()
    # stone abutments (outer faces for |x| > S)
    xs = xs_dense(-A.hl, A.hl, 0.3, extra=(-A.S, A.S))
    for side in (-1, 1):
        y = side * W / 2
        for a, b in zip(xs[:-1], xs[1:]):
            if abs((a + b) / 2) > A.S:
                qd(V(a, y, -1.0), V(b, y, -1.0), V(b, y, A.raw(b)), V(a, y, A.raw(a)), "ashlar", (0, side, 0))
    A.ends("ashlar")
    for sx in (-1, 1):
        qd(V(sx * A.S, -W / 2, -1.0), V(sx * A.S, W / 2, -1.0), V(sx * A.S, W / 2, A.springz), V(sx * A.S, -W / 2, A.springz), "ashlar", (-sx, 0, 0))
        # abutment tops under the iron
        bx(sx * A.S - (0 if sx > 0 else 0), sx * A.S + sx * 0.02, -W / 2, W / 2, A.springz - 0.02, A.springz, "ashlar")
    # iron: three ribs (two fascia + one central), each a curved box girder; vertical struts and rings in the fascia spandrel
    xr = xs_dense(-A.S, A.S, 0.35)
    for y, w in ((-W / 2 + 0.07, 0.14), (0.0, 0.12), (W / 2 - 0.07, 0.14)):
        for a, b in zip(xr[:-1], xr[1:]):
            tbox(a, b, y - w / 2, y + w / 2, A.intr(a), A.intr(b), A.intr(a) + 0.34, A.intr(b) + 0.34, "castiron")
    # deck beam under the walkway (follows the stepped deck, smooth)
    for side in (-1, 1):
        y = side * (W / 2 - 0.07)
        for a, b in zip(xr[:-1], xr[1:]):
            tbox(a, b, y - 0.06, y + 0.06, A.raw(a) - 0.30, A.raw(b) - 0.30, A.raw(a) - 0.04, A.raw(b) - 0.04, "castiron")
        # struts + rings between rib and deck beam
        for x in [v for v in xs_dense(-A.S + 0.4, A.S - 0.4, 0.9)]:
            z0 = A.intr(x) + 0.34; z1 = A.raw(x) - 0.30
            if z1 - z0 > 0.1:
                bx(x - 0.045, x + 0.045, y - 0.05, y + 0.05, z0, z1, "castiron")
                if z1 - z0 > 0.35:                 # ring in the panel
                    zc = (z0 + z1) / 2; rr = min(0.22, (z1 - z0) / 2 - 0.05); xo = x + 0.45
                    if xo < A.S - 0.2:
                        for i in range(10):
                            t0, t1 = i * 2 * PI / 10, (i + 1) * 2 * PI / 10
                            for yy, sg in ((y - 0.045, -1), (y + 0.045, 1)):
                                qd(V(xo + rr * math.cos(t0), yy, zc + rr * math.sin(t0)), V(xo + rr * math.cos(t1), yy, zc + rr * math.sin(t1)),
                                   V(xo + (rr - 0.045) * math.cos(t1), yy, zc + (rr - 0.045) * math.sin(t1)), V(xo + (rr - 0.045) * math.cos(t0), yy, zc + (rr - 0.045) * math.sin(t0)), "castiron", (0, sg, 0))
                        bx(xo - rr, xo + rr, y - 0.04, y + 0.04, zc - 0.025, zc + 0.025, "castiron")
    # cross girders + planks
    for x in xs_dense(-A.S, A.S, 1.2):
        bx(x - 0.05, x + 0.05, -W / 2 + 0.1, W / 2 - 0.1, A.raw(x) - 0.28, A.raw(x) - 0.10, "castiron")
    A.deck("planks", "wood")
    iron_rail(A, ph=1.0, kerb=0.0, bars=0.14, mat="castiron", kerb_mat="castiron", posts_every=1.75)
    for side in (-1, 1):    # fascia plate along the deck edge
        xs2 = xs_dense(-A.hl, A.hl, 0.4)
        for a, b in zip(xs2[:-1], xs2[1:]):
            tbox(a, b, side * W / 2 - (0 if side < 0 else 0.06), side * W / 2 + (0.06 if side > 0 else 0), A.raw(a) - 0.26, A.raw(b) - 0.26, A.raw(a) + 0.02, A.raw(b) + 0.02, "castiron")
    return end_part("b_castiron", 1.0), dict(L=L, W=W, R=R)

# ---------------------------------------------------------------- registry + layout
BUILT = []      # (name, object, spec)
def want(n): return not ONLY or n in ONLY
def reg(name, ob, spec): BUILT.append((name, ob, spec))

if want("brick"): reg("brick", *build_masonry("brick"))
if want("balustrade"): reg("balustrade", *build_masonry("balustrade"))
if want("stucco"): reg("stucco", *build_masonry("stucco"))
if want("noparapet"): reg("noparapet", *build_masonry("noparapet"))
if want("irongate"): reg("irongate", *build_masonry("irongate"))
if want("castiron"): reg("castiron", *build_castiron())

exec(open(os.path.join(HERE, "build_bridges_long.py")).read()) if os.path.exists(os.path.join(HERE, "build_bridges_long.py")) else None
exec(open(os.path.join(HERE, "build_bridges_special.py")).read()) if os.path.exists(os.path.join(HERE, "build_bridges_special.py")) else None

for i, (name, ob, spec) in enumerate(BUILT):       # row layout for inspection (procedural materials use object coordinates: keep origin at 0)
    ob["bridge"] = name; ob["spec"] = str(spec)
finish(OUT, ATLAS, ATLAS_RES)

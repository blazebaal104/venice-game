"""Ponte di Rialto v2 - rebuilt from photo references (Wikimedia Commons: north/south faces, portico, balustrade,
stairs, underside, aerial).  Local frame: +X along the bridge, +Y across (+Y = north face), +Z up, mean water z=0.

What the photos show and this script models:
  * single segmental arch (span 28.8 m, rise 6.4 m) with a moulded archivolt and a carved keystone
  * spandrels clad in large Istrian slabs laid on the diagonal, grey patina, dark rain streaks, green-black tide band
  * reliefs on the spandrels (Annunciation on the south face, St Mark / St Theodore on the north face),
    framed inscription plaques towards the ends
  * a continuous row of square corbels (modiglioni) under the cornice that carries the balustrade
  * heavy balustrade: pear-shaped balusters on a plinth, thick rail, pedestals at every bay, following the deck curve
  * three stepped walkways (trachyte treads with Istrian nosings): two outer along the balustrades, one central
  * two rows of shops, 6 bays per ramp: Istrian pilasters, round arches, dark green louvred lunettes and
    panelled doors on the outer face; shop fronts / roll-down shutters, iron lunette grilles and canvas awnings inside
  * a straight sloping cornice over each row and a continuous lead roof with standing rolls
  * central portico: rusticated piers and voussoirs, cross passage, grotesque keystone heads, pediments on all
    four faces, iron tie rods and hanging lanterns
Every surface gets a procedural weathered material that is baked (with ambient occlusion) into one atlas.
"""
import bpy, bmesh, math, sys, os, random
from mathutils import Vector, Matrix

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
OUT = ARGS[0] if len(ARGS) > 0 else "/home/claude/venice/rialto2.blend"
ATLAS = ARGS[1] if len(ARGS) > 1 else "/home/claude/venice/rialto_atlas.png"
ATLAS_RES = int(ARGS[2]) if len(ARGS) > 2 else 4096
random.seed(11)
bpy.ops.wm.read_factory_settings(use_empty=True)

# ---------------------------------------------------------------- dimensions
HALF_L = 22.8; HALF_W = 11.45
SPAN_HALF = 14.4; RISE = 6.4; SPRING_Z = 0.92
CROWN = SPRING_Z + RISE
R_ARCH = ((SPAN_HALF * 2) ** 2 / 4 + RISE ** 2) / (2 * RISE)
ARCH_CZ = CROWN - R_ARCH
CREST_Z = 9.0; END_Z = 1.10; CREST_HALF = 3.6
ROW_IN, ROW_OUT = 3.0, 7.6
BAL_IN = HALF_W - 0.5
BAY_L = 2.9; BAYS = 6
ROW_END = CREST_HALF + BAY_L * BAYS           # 21.6
STEP_H = 0.15
BASE_Z = -1.6

def ramp(x):
    """Deck line (cornice / balustrade): convex like the real bridge - nearly flat over the crown, steepening towards the
    banks, which leaves deep spandrels between the arch and the cornice for the reliefs and plaques."""
    ax = abs(x)
    if ax <= CREST_HALF: return CREST_Z
    f = min(1.0, (ax - CREST_HALF) / (HALF_L - CREST_HALF))
    return CREST_Z - (CREST_Z - END_Z) * f ** 1.6

def walk(x):
    raw = ramp(x)
    return END_Z + math.floor((raw - END_Z) / STEP_H + 0.5) * STEP_H

def intrados(x):
    if abs(x) >= SPAN_HALF: return SPRING_Z
    return ARCH_CZ + math.sqrt(max(R_ARCH ** 2 - x * x, 0))

def row_top(x):
    """Top of the shop rows' entablature: follows the deck so the arcade keeps its height on every bay."""
    ax = min(abs(x), ROW_END)
    return ramp(ax) + 6.3

# ---------------------------------------------------------------- materials: procedural, baked to one atlas
MATS = {}
def node_mat(name, build, layer):
    """build(nt, coord) -> color socket.  `layer` = game texture layer used for fine normal detail."""
    m = bpy.data.materials.new(name); m.use_nodes = True
    nt = m.node_tree; nt.nodes.clear()
    out = nt.nodes.new("ShaderNodeOutputMaterial")
    em = nt.nodes.new("ShaderNodeEmission")
    tc = nt.nodes.new("ShaderNodeTexCoord")
    col = build(nt, tc.outputs["Object"])
    # colours in this script are written as sRGB-like values: convert to linear before the emission bake
    g = nt.nodes.new("ShaderNodeGamma"); g.inputs["Gamma"].default_value = 2.2
    nt.links.new(col, g.inputs["Color"]); col = g.outputs[0]
    nt.links.new(col, em.inputs["Color"]); nt.links.new(em.outputs[0], out.inputs[0])
    MATS[name] = (m, layer)
    return m

def N(nt, kind, **kw):
    n = nt.nodes.new(kind)
    for k, v in kw.items():
        if k.startswith("i_"): n.inputs[k[2:].replace("_", " ")].default_value = v
        else: setattr(n, k, v)
    return n

def L(nt, a, b): nt.links.new(a, b)

def mix(nt, a, b, fac, blend="MIX"):
    m = N(nt, "ShaderNodeMix", data_type="RGBA", blend_type=blend)
    for s, v in ((6, a), (7, b), (0, fac)):
        if isinstance(v, (tuple, list)): m.inputs[s].default_value = v if len(v) == 4 else (*v, 1.0)
        elif isinstance(v, float): m.inputs[s].default_value = v
        else: L(nt, v, m.inputs[s])
    return m.outputs[2]

def mathn(nt, op, a, b=None, clamp=False):
    m = N(nt, "ShaderNodeMath", operation=op, use_clamp=clamp)
    for i, v in enumerate((a, b)):
        if v is None: continue
        if isinstance(v, (int, float)): m.inputs[i].default_value = v
        else: L(nt, v, m.inputs[i])
    return m.outputs[0]

def sep_z(nt, coord):
    s = N(nt, "ShaderNodeSeparateXYZ"); L(nt, coord, s.inputs[0]); return s

def ramp_val(nt, v, a, b):
    """smoothstep-ish clamp((v-a)/(b-a))"""
    m = N(nt, "ShaderNodeMapRange", interpolation_type="SMOOTHSTEP")
    L(nt, v, m.inputs["Value"]); m.inputs["From Min"].default_value = a; m.inputs["From Max"].default_value = b
    return m.outputs[0]

def ao(nt, dist=1.2):
    a = N(nt, "ShaderNodeAmbientOcclusion", samples=int(os.environ.get("AO_SAMPLES", "8")), inside=False, only_local=False)
    a.inputs["Distance"].default_value = dist
    return a.outputs["AO"]

def weather(nt, coord, col, streak=0.5, tide=True, ao_min=0.30, grime=0.30):
    """Venetian weathering: soot/rain streaks (stronger where AO says something overhangs), patina, AO, tide band."""
    sz = sep_z(nt, coord)
    occ_raw = ao(nt)
    # vertical streaks: noise stretched along Z, gated so they come in runs
    mp = N(nt, "ShaderNodeMapping"); L(nt, coord, mp.inputs[0]); mp.inputs["Scale"].default_value = (6.0, 6.0, 0.22)
    st = N(nt, "ShaderNodeTexNoise", i_Scale=3.0, i_Detail=7.0, i_Roughness=0.6); L(nt, mp.outputs[0], st.inputs["Vector"])
    st_f = ramp_val(nt, st.outputs["Fac"], 0.46, 0.70)
    gate = N(nt, "ShaderNodeTexNoise", i_Scale=0.7, i_Detail=2.0); L(nt, coord, gate.inputs["Vector"])
    st_f = mathn(nt, "MULTIPLY", st_f, ramp_val(nt, gate.outputs["Fac"], 0.35, 0.6))
    # drips are strongest just under projections (low AO above): boost by (1 - AO)
    under = mathn(nt, "ADD", mathn(nt, "MULTIPLY", mathn(nt, "SUBTRACT", 1.0, occ_raw), 1.6), 0.55)
    c = mix(nt, col, (0.30, 0.29, 0.27, 1), mathn(nt, "MINIMUM", mathn(nt, "MULTIPLY", mathn(nt, "MULTIPLY", st_f, streak), under), 0.85))
    # patina blotches (larger, grey-brown)
    bl = N(nt, "ShaderNodeTexNoise", i_Scale=0.8, i_Detail=4.0); L(nt, coord, bl.inputs["Vector"])
    c = mix(nt, c, (0.55, 0.53, 0.49, 1), mathn(nt, "MULTIPLY", ramp_val(nt, bl.outputs["Fac"], 0.45, 0.72), grime))
    # pitting / fine mottling
    fn = N(nt, "ShaderNodeTexNoise", i_Scale=28.0, i_Detail=3.0); L(nt, coord, fn.inputs["Vector"])
    c = mix(nt, c, fn.outputs["Color"], 0.10, "OVERLAY")
    # darker grey patina over the lower part of the structure
    low = ramp_val(nt, sz.outputs["Z"], 4.0, 1.0)
    c = mix(nt, c, (0.44, 0.43, 0.40, 1), mathn(nt, "MULTIPLY", low, 0.5 * grime / 0.3))
    if tide:
        # irregular top edge + drips: the band height wanders and stretched noise pulls stains up
        en = N(nt, "ShaderNodeTexNoise", i_Scale=1.3, i_Detail=4.0); L(nt, coord, en.inputs["Vector"])
        dm = N(nt, "ShaderNodeMapping"); L(nt, coord, dm.inputs[0]); dm.inputs["Scale"].default_value = (9.0, 9.0, 0.5)
        dr = N(nt, "ShaderNodeTexNoise", i_Scale=2.0, i_Detail=3.0); L(nt, dm.outputs[0], dr.inputs["Vector"])
        zoff = mathn(nt, "ADD", mathn(nt, "MULTIPLY", en.outputs["Fac"], 0.5), mathn(nt, "MULTIPLY", ramp_val(nt, dr.outputs["Fac"], 0.55, 0.8), 0.45))
        zz = mathn(nt, "SUBTRACT", sz.outputs["Z"], zoff)
        wet = ramp_val(nt, zz, 0.9, 0.2)                                  # brown-grey damp zone
        c = mix(nt, c, (0.36, 0.34, 0.29, 1), mathn(nt, "MULTIPLY", wet, 0.75))
        alg = ramp_val(nt, zz, 0.25, -0.05)                               # green-black at the waterline
        c = mix(nt, c, (0.10, 0.13, 0.07, 1), alg)
        deep = ramp_val(nt, sz.outputs["Z"], 0.2, -0.2)
        c = mix(nt, c, (0.05, 0.07, 0.04, 1), deep)
    occ = mix(nt, (ao_min, ao_min, ao_min, 1), (1, 1, 1, 1), occ_raw)
    return mix(nt, c, occ, 1.0, "MULTIPLY")

CREAM0 = (0.71, 0.69, 0.64); CREAM1 = (0.81, 0.78, 0.73)

def stone_base(nt, coord, k=1.0):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=1.8, i_Detail=5.0); L(nt, coord, base.inputs["Vector"])
    return mix(nt, (CREAM0[0] * k, CREAM0[1] * k, CREAM0[2] * k, 1), (CREAM1[0] * k, CREAM1[1] * k, CREAM1[2] * k, 1), base.outputs["Fac"])

def joints(nt, c, vec, bw, bh, offset, mortar=0.012, jcol=(0.36, 0.35, 0.32), var=0.12):
    """Block pattern on `vec`: per-block tone variation + dark joints."""
    br = N(nt, "ShaderNodeTexBrick", offset=offset, i_Scale=1.0, i_Mortar_Size=mortar, i_Mortar_Smooth=0.3, i_Brick_Width=bw, i_Row_Height=bh, i_Bias=0.0)
    br.inputs["Color1"].default_value = (1, 1, 1, 1); br.inputs["Color2"].default_value = (1 - var, 1 - var * 1.05, 1 - var * 1.15, 1)
    br.inputs["Mortar"].default_value = (*jcol, 1)
    L(nt, vec, br.inputs["Vector"])
    # chipped joint edges: noise eats into the blocks along the mortar
    ch = N(nt, "ShaderNodeTexNoise", i_Scale=9.0, i_Detail=3.0); L(nt, vec, ch.inputs["Vector"])
    chip = mathn(nt, "MULTIPLY", ramp_val(nt, br.outputs["Fac"], 0.0, 1.0), 1.0)
    c = mix(nt, c, br.outputs["Color"], 1.0, "MULTIPLY")
    return c

def uv_face(nt, coord, rot45=False):
    """(u, v) on vertical faces: u = x + y (works for faces along X or along Y), v = z; optionally rotated 45 degrees."""
    sz = sep_z(nt, coord)
    u = mathn(nt, "ADD", sz.outputs["X"], sz.outputs["Y"]); v = sz.outputs["Z"]
    cv = N(nt, "ShaderNodeCombineXYZ")
    if rot45:
        L(nt, mathn(nt, "MULTIPLY", mathn(nt, "ADD", u, v), 0.7071), cv.inputs["X"])
        L(nt, mathn(nt, "MULTIPLY", mathn(nt, "SUBTRACT", u, v), 0.7071), cv.inputs["Y"])
    else:
        L(nt, u, cv.inputs["X"]); L(nt, v, cv.inputs["Y"])
    return cv.outputs[0]

def m_istrian_slabs(nt, coord):
    """Spandrel facing: big Istrian slabs laid on the diagonal in a lozenge grid (~1.6 m), dark joints, tone per slab."""
    c = stone_base(nt, coord)
    c = joints(nt, c, uv_face(nt, coord, True), 1.25, 1.25, 0.0, mortar=0.032, jcol=(0.22, 0.21, 0.19), var=0.30)
    cr = N(nt, "ShaderNodeTexNoise", i_Scale=5.0, i_Detail=8.0, i_Roughness=0.7); L(nt, coord, cr.inputs["Vector"])
    crack = ramp_val(nt, cr.outputs["Fac"], 0.505, 0.5)
    c = mix(nt, c, (0.35, 0.34, 0.31, 1), mathn(nt, "MULTIPLY", crack, 0.5))
    return weather(nt, coord, c, streak=0.95, grime=0.4)

def m_istrian_ashlar(nt, coord, bw=1.2, bh=0.45):
    c = stone_base(nt, coord)
    c = joints(nt, c, uv_face(nt, coord), bw, bh, 0.5, mortar=0.010)
    return weather(nt, coord, c, streak=0.55)

def m_rustic(nt, coord):
    """Portico: banded ashlar 0.6 x 0.3 m with chamfered, shadowed joints, same cream stone, lightly soiled."""
    c = stone_base(nt, coord, 1.02)
    c = joints(nt, c, uv_face(nt, coord), 1.2, 0.45, 0.5, mortar=0.012, jcol=(0.45, 0.43, 0.40), var=0.10)
    return weather(nt, coord, c, streak=0.35, grime=0.18, tide=False)

def m_istrian_plain(nt, coord, k=1.0, streak=0.5):
    return weather(nt, coord, stone_base(nt, coord, k), streak=streak)

def m_archivolt(nt, coord):
    """Archivolt ring: voussoir joints radiating from the arch centre every ~0.9 m of arc."""
    c = stone_base(nt, coord, 1.02)
    sz = sep_z(nt, coord)
    ang = mathn(nt, "ARCTAN2", sz.outputs["X"], mathn(nt, "SUBTRACT", sz.outputs["Z"], ARCH_CZ))
    s_arc = mathn(nt, "MULTIPLY", ang, R_ARCH / 0.9)
    fr = mathn(nt, "FRACT", s_arc)
    edge = mathn(nt, "MINIMUM", fr, mathn(nt, "SUBTRACT", 1.0, fr))
    jl = ramp_val(nt, edge, 0.03, 0.0)
    c = mix(nt, c, (0.36, 0.35, 0.32, 1), mathn(nt, "MULTIPLY", jl, 0.85))
    return weather(nt, coord, c, streak=0.5)

def m_vault(nt, coord):
    """Arch soffit: coursed blocks running along the barrel, darker grey-brown, damp, efflorescence, black at the springings."""
    base = N(nt, "ShaderNodeTexNoise", i_Scale=1.0, i_Detail=6.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.27, 0.26, 0.24, 1), (0.37, 0.35, 0.32, 1), base.outputs["Fac"])
    sz = sep_z(nt, coord)
    ang = mathn(nt, "ARCTAN2", sz.outputs["X"], mathn(nt, "SUBTRACT", sz.outputs["Z"], ARCH_CZ))
    cv = N(nt, "ShaderNodeCombineXYZ"); L(nt, sz.outputs["Y"], cv.inputs["X"]); L(nt, mathn(nt, "MULTIPLY", ang, R_ARCH), cv.inputs["Y"])
    c = joints(nt, c, cv.outputs[0], 1.9, 0.75, 0.37, mortar=0.02, jcol=(0.14, 0.13, 0.12), var=0.25)
    ef = N(nt, "ShaderNodeTexNoise", i_Scale=1.6, i_Detail=5.0); L(nt, coord, ef.inputs["Vector"])
    c = mix(nt, c, (0.78, 0.77, 0.72, 1), mathn(nt, "MULTIPLY", ramp_val(nt, ef.outputs["Fac"], 0.62, 0.78), 0.55))
    low = ramp_val(nt, sz.outputs["Z"], 2.6, 0.9)
    c = mix(nt, c, (0.11, 0.14, 0.08, 1), mathn(nt, "MULTIPLY", low, 0.85))
    return weather(nt, coord, c, streak=0.0, ao_min=0.55)

def m_plaster_vault(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=1.2, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.80, 0.79, 0.75, 1), (0.88, 0.87, 0.83, 1), base.outputs["Fac"])
    return weather(nt, coord, c, streak=0.15, tide=False, ao_min=0.5, grime=0.15)

def m_trachyte(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=4.0, i_Detail=6.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.27, 0.27, 0.27, 1), (0.40, 0.39, 0.38, 1), base.outputs["Fac"])
    sz = sep_z(nt, coord)
    cv = N(nt, "ShaderNodeCombineXYZ"); L(nt, sz.outputs["X"], cv.inputs["X"]); L(nt, sz.outputs["Y"], cv.inputs["Y"])
    c = joints(nt, c, cv.outputs[0], 0.8, 0.55, 0.5, mortar=0.015, jcol=(0.15, 0.15, 0.15), var=0.15)
    wear = N(nt, "ShaderNodeTexNoise", i_Scale=0.6, i_Detail=3.0); L(nt, coord, wear.inputs["Vector"])
    c = mix(nt, c, (0.50, 0.49, 0.47, 1), mathn(nt, "MULTIPLY", ramp_val(nt, wear.outputs["Fac"], 0.5, 0.7), 0.35))
    return weather(nt, coord, c, streak=0.0, tide=False, ao_min=0.55, grime=0.1)

def m_nosing(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=6.0, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.84, 0.82, 0.77, 1), (0.93, 0.91, 0.87, 1), base.outputs["Fac"])
    return weather(nt, coord, c, streak=0.0, tide=False, ao_min=0.6, grime=0.12)

def m_lead(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=0.9, i_Detail=5.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.52, 0.54, 0.53, 1), (0.60, 0.62, 0.61, 1), base.outputs["Fac"])
    ox = N(nt, "ShaderNodeTexNoise", i_Scale=2.5, i_Detail=4.0); L(nt, coord, ox.inputs["Vector"])
    c = mix(nt, c, (0.72, 0.73, 0.72, 1), mathn(nt, "MULTIPLY", ramp_val(nt, ox.outputs["Fac"], 0.6, 0.78), 0.35))
    mp = N(nt, "ShaderNodeMapping"); L(nt, coord, mp.inputs[0]); mp.inputs["Scale"].default_value = (8.0, 0.4, 8.0)
    ro = N(nt, "ShaderNodeTexNoise", i_Scale=2.0, i_Detail=4.0); L(nt, mp.outputs[0], ro.inputs["Vector"])
    c = mix(nt, c, (0.30, 0.31, 0.32, 1), mathn(nt, "MULTIPLY", ramp_val(nt, ro.outputs["Fac"], 0.55, 0.75), 0.45))
    return weather(nt, coord, c, streak=0.0, tide=False, ao_min=0.55, grime=0.12)

def m_shutter_green(nt, coord):
    wv = N(nt, "ShaderNodeTexWave", wave_type="BANDS", bands_direction="Z"); wv.inputs["Scale"].default_value = 14.0
    L(nt, coord, wv.inputs["Vector"])
    slat = ramp_val(nt, wv.outputs["Fac"], 0.2, 0.6)
    c = mix(nt, (0.07, 0.12, 0.09, 1), (0.14, 0.22, 0.16, 1), slat)
    base = N(nt, "ShaderNodeTexNoise", i_Scale=6.0, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, c, (0.22, 0.25, 0.21, 1), mathn(nt, "MULTIPLY", ramp_val(nt, base.outputs["Fac"], 0.55, 0.8), 0.6))
    return weather(nt, coord, c, streak=0.1, tide=False, ao_min=0.5)

def m_door_green(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=5.0, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.15, 0.21, 0.17, 1), (0.19, 0.26, 0.21, 1), base.outputs["Fac"])
    sz = sep_z(nt, coord)
    u = mathn(nt, "ADD", sz.outputs["X"], sz.outputs["Y"])
    cv = N(nt, "ShaderNodeCombineXYZ"); L(nt, u, cv.inputs["X"])
    wv = N(nt, "ShaderNodeTexWave", wave_type="BANDS", bands_direction="X"); wv.inputs["Scale"].default_value = 4.0
    L(nt, cv.outputs[0], wv.inputs["Vector"])
    c = mix(nt, c, (0.04, 0.06, 0.05, 1), mathn(nt, "MULTIPLY", ramp_val(nt, wv.outputs["Fac"], 0.08, 0.0), 0.8))
    return weather(nt, coord, c, streak=0.15, tide=False, ao_min=0.5)

def m_flat(color, var=0.08, ao_min=0.5, streak=0.0):
    def f(nt, coord):
        base = N(nt, "ShaderNodeTexNoise", i_Scale=6.0, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
        c = mix(nt, (color[0] * (1 - var), color[1] * (1 - var), color[2] * (1 - var), 1), (*color, 1), base.outputs["Fac"])
        return weather(nt, coord, c, streak=streak, tide=False, ao_min=ao_min, grime=0.05)
    return f

def m_rollshutter(nt, coord):
    wv = N(nt, "ShaderNodeTexWave", wave_type="BANDS", bands_direction="Z"); wv.inputs["Scale"].default_value = 7.0
    L(nt, coord, wv.inputs["Vector"])
    c = mix(nt, (0.52, 0.53, 0.53, 1), (0.68, 0.69, 0.69, 1), wv.outputs["Fac"])
    return weather(nt, coord, c, streak=0.25, tide=False, ao_min=0.5)

def m_glass(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=1.5, i_Detail=3.0); L(nt, coord, base.inputs["Vector"])
    gd = N(nt, "ShaderNodeTexNoise", i_Scale=7.0, i_Detail=2.0); L(nt, coord, gd.inputs["Vector"])
    c = mix(nt, (0.06, 0.06, 0.06, 1), (0.30, 0.24, 0.17, 1), base.outputs["Fac"])     # lit shop interiors, goods
    c = mix(nt, c, gd.outputs["Color"], 0.25, "OVERLAY")
    return c

PLAQUES = []   # (xc, zc, half_w, half_h, face sign) - filled before materials are built
def m_inscription_k(k):
    def f(nt, coord):
        xc, zc, hw, hh, sgn = PLAQUES[k]
        c = stone_base(nt, coord, 0.95)
        sz = sep_z(nt, coord)
        u = mathn(nt, "ADD", mathn(nt, "MULTIPLY", mathn(nt, "SUBTRACT", sz.outputs["X"], xc), (1.0 if sgn < 0 else -1.0) / (2 * hw)), 0.5)
        v = mathn(nt, "ADD", mathn(nt, "MULTIPLY", mathn(nt, "SUBTRACT", sz.outputs["Z"], zc), 1.0 / (2 * hh)), 0.5)
        cv = N(nt, "ShaderNodeCombineXYZ"); L(nt, u, cv.inputs["X"]); L(nt, v, cv.inputs["Y"])
        tx = N(nt, "ShaderNodeTexImage", extension="CLIP"); tx.image = bpy.data.images.load(f"/home/claude/venice/gen/inscr_{'south' if sgn < 0 else 'north'}.png")
        tx.image.colorspace_settings.name = "Non-Color"
        L(nt, cv.outputs[0], tx.inputs["Vector"])
        letters = mathn(nt, "SUBTRACT", 1.0, tx.outputs["Color"])
        c = mix(nt, c, (0.20, 0.19, 0.18, 1), mathn(nt, "MULTIPLY", letters, 1.25))
        return weather(nt, coord, c, streak=0.55, tide=False)
    return f

def m_canvas(tone):
    def f(nt, coord):
        base = N(nt, "ShaderNodeTexNoise", i_Scale=3.0, i_Detail=3.0); L(nt, coord, base.inputs["Vector"])
        c = mix(nt, (tone[0] * 0.88, tone[1] * 0.88, tone[2] * 0.88, 1), (*tone, 1), base.outputs["Fac"])
        return weather(nt, coord, c, streak=0.15, tide=False, ao_min=0.6, grime=0.12)
    return f

# layers: 15 istrian, 16 istrian worn, 17 ashlar, 22 masegni, 25 trachyte dark, 31 lead, 32 shutter wood, 33 rust, 0 plaster
node_mat("slabs", m_istrian_slabs, 15)
node_mat("ashlar", lambda nt, c: m_istrian_ashlar(nt, c), 15)
node_mat("rustic", m_rustic, 17)
node_mat("stone", lambda nt, c: m_istrian_plain(nt, c), 15)
node_mat("stone_light", lambda nt, c: m_istrian_plain(nt, c, 1.03, 0.4), 15)
node_mat("carved", lambda nt, c: m_istrian_plain(nt, c, 0.95, 0.7), 15)
node_mat("archivolt", m_archivolt, 15)
node_mat("vault", m_vault, 15)
node_mat("plaster", m_plaster_vault, 0)
node_mat("trachyte", m_trachyte, 25)
node_mat("nosing", m_nosing, 15)
node_mat("lead", m_lead, 31)
node_mat("louvre", m_shutter_green, 32)
node_mat("door", m_door_green, 32)
node_mat("wood", m_flat((0.16, 0.20, 0.17)), 27)
node_mat("sign", m_flat((0.33, 0.20, 0.11)), 27)
node_mat("iron", m_flat((0.05, 0.05, 0.05), 0.2, 0.6), 33)
node_mat("roll", m_rollshutter, 33)
node_mat("glass", m_glass, 32)
PLAQUES += [(xc, (ramp(xc) - 1.1 + 1.2) / 2 + 0.25, 1.15, 0.78, s) for s in (-1, 1) for xc in (-16.6, 16.6)]
for k in range(4): node_mat(f"plaque{k}", m_inscription_k(k), 15)
node_mat("lampglass", m_flat((0.62, 0.58, 0.46), 0.1, 0.7), 32)
node_mat("canvas", m_canvas((0.86, 0.80, 0.68)), 0)
node_mat("canvas2", m_canvas((0.36, 0.42, 0.34)), 0)
node_mat("canvas3", m_canvas((0.74, 0.66, 0.52)), 0)
node_mat("dark", m_flat((0.03, 0.03, 0.03), 0.2, 0.8), 33)
MAT_ORDER = list(MATS.keys())

# ---------------------------------------------------------------- geometry builder
bm = bmesh.new()
def mi(name): return MAT_ORDER.index(name)

def face(pts, mat):
    vs = [bm.verts.new(p) for p in pts]
    try:
        f = bm.faces.new(vs); f.material_index = mi(mat); return f
    except ValueError:
        return None

def quad(a, b, c, d, mat): return face([a, b, c, d], mat)

def box(x0, x1, y0, y1, z0, z1, mat, skip_bottom=True):
    p = [Vector((x, y, z)) for z in (z0, z1) for y in (y0, y1) for x in (x0, x1)]
    # 0 x0y0z0 1 x1y0z0 2 x0y1z0 3 x1y1z0 4.. same at z1
    if not skip_bottom: quad(p[0], p[2], p[3], p[1], mat)
    quad(p[4], p[5], p[7], p[6], mat)
    quad(p[0], p[1], p[5], p[4], mat); quad(p[3], p[2], p[6], p[7], mat)
    quad(p[2], p[0], p[4], p[6], mat); quad(p[1], p[3], p[7], p[5], mat)

def obox(c, ax, ay, az, hx, hy, hz, mat):
    """Oriented box: centre c, unit axes ax/ay/az, half sizes."""
    if ax.cross(ay).dot(az) < 0: az = -az          # keep the frame right-handed so faces point outward
    P = lambda sx, sy, sz: c + ax * (sx * hx) + ay * (sy * hy) + az * (sz * hz)
    v = {(sx, sy, sz): P(sx, sy, sz) for sx in (-1, 1) for sy in (-1, 1) for sz in (-1, 1)}
    quad(v[-1, -1, 1], v[1, -1, 1], v[1, 1, 1], v[-1, 1, 1], mat)
    quad(v[-1, -1, -1], v[-1, 1, -1], v[1, 1, -1], v[1, -1, -1], mat)
    quad(v[-1, -1, -1], v[1, -1, -1], v[1, -1, 1], v[-1, -1, 1], mat)
    quad(v[1, 1, -1], v[-1, 1, -1], v[-1, 1, 1], v[1, 1, 1], mat)
    quad(v[-1, 1, -1], v[-1, -1, -1], v[-1, -1, 1], v[-1, 1, 1], mat)
    quad(v[1, -1, -1], v[1, 1, -1], v[1, 1, 1], v[1, -1, 1], mat)

def lathe(cx, cy, z0, prof, mat, segs=8):
    """prof: list of (radius, dz) bottom->top."""
    rings = []
    for r, dz in prof:
        rings.append([bm.verts.new((cx + r * math.cos(2 * math.pi * i / segs), cy + r * math.sin(2 * math.pi * i / segs), z0 + dz)) for i in range(segs)])
    for a, b in zip(rings, rings[1:]):
        for i in range(segs):
            j = (i + 1) % segs
            try: bm.faces.new((a[i], a[j], b[j], b[i])).material_index = mi(mat)
            except ValueError: pass
    try: bm.faces.new(rings[-1]).material_index = mi(mat)
    except ValueError: pass

def blob(c, rx, ry, rz, mat, segs=8, rings=6, rot=0.0):
    """Ellipsoid (used for sculpted relief masses)."""
    cs, sn = math.cos(rot), math.sin(rot)
    grid = []
    for j in range(rings + 1):
        th = math.pi * j / rings
        row = []
        for i in range(segs):
            ph = 2 * math.pi * i / segs
            x = rx * math.sin(th) * math.cos(ph); y = ry * math.sin(th) * math.sin(ph); z = rz * math.cos(th)
            x, z = x * cs - z * sn, x * sn + z * cs
            row.append(bm.verts.new((c.x + x, c.y + y, c.z + z)))
        grid.append(row)
    for j in range(rings):
        for i in range(segs):
            k = (i + 1) % segs
            try: bm.faces.new((grid[j][i], grid[j + 1][i], grid[j + 1][k], grid[j][k])).material_index = mi(mat)
            except ValueError: pass

def strip_along(xs, zfun_lo, zfun_hi, y, mat, normal_sign):
    """Vertical band on the plane y between two height functions, sampled at xs."""
    for a, b in zip(xs, xs[1:]):
        p = [Vector((a, y, zfun_lo(a))), Vector((b, y, zfun_lo(b))), Vector((b, y, zfun_hi(b))), Vector((a, y, zfun_hi(a)))]
        if normal_sign < 0: quad(p[0], p[1], p[2], p[3], mat)
        else: quad(p[1], p[0], p[3], p[2], mat)

def frange(a, b, step):
    n = max(1, int(round((b - a) / step)))
    return [a + (b - a) * i / n for i in range(n + 1)]

XS = frange(-HALF_L, HALF_L, 0.4)

# ================================================================ 1. bridge body: faces, vault, abutments
def face_outline():
    """Front face boundary in (x, z): deck line on top, arch + abutments below (CCW for +Y normal is handled by caller)."""
    top = [(x, ramp(x) - 0.36) for x in frange(-HALF_L, HALF_L, 0.6)]       # up to the cornice slab
    arch = [(x, intrados(x)) for x in frange(-SPAN_HALF, SPAN_HALF, 0.5)]
    return top[::-1] + [(-HALF_L, BASE_Z), (-SPAN_HALF, BASE_Z), (-SPAN_HALF, SPRING_Z)] + arch[1:-1] + \
        [(SPAN_HALF, SPRING_Z), (SPAN_HALF, BASE_Z), (HALF_L, BASE_Z)]

for s in (-1, 1):
    y = s * HALF_W
    outline = face_outline()
    pts = [Vector((x, y, z)) for x, z in outline]
    if s > 0: pts = pts[::-1]
    face(pts, "slabs")

# vault (intrados) between the faces
for a, b in zip(frange(-SPAN_HALF, SPAN_HALF, 0.5), frange(-SPAN_HALF, SPAN_HALF, 0.5)[1:]):
    quad(Vector((a, -HALF_W, intrados(a))), Vector((a, HALF_W, intrados(a))), Vector((b, HALF_W, intrados(b))), Vector((b, -HALF_W, intrados(b))), "vault")
# abutment walls facing the water under the springing
for s in (-1, 1):
    x = s * SPAN_HALF
    p = [Vector((x, -HALF_W, BASE_Z)), Vector((x, HALF_W, BASE_Z)), Vector((x, HALF_W, SPRING_Z)), Vector((x, -HALF_W, SPRING_Z))]
    quad(*(p[::-1] if s > 0 else p), "ashlar")
# ends towards the fondamenta
for s in (-1, 1):
    x = s * HALF_L
    p = [Vector((x, -HALF_W, BASE_Z)), Vector((x, HALF_W, BASE_Z)), Vector((x, HALF_W, END_Z)), Vector((x, -HALF_W, END_Z))]
    quad(*(p if s > 0 else p[::-1]), "ashlar")

# ================================================================ 2. archivolt, keystone, corbel cornice, reliefs, plaques
def arc_pt(x, r_off, y):
    """Point on a circle concentric with the intrados, offset r_off outward, at the angle of intrados point x."""
    z = intrados(x); dx = x; dz = z - ARCH_CZ; l = math.hypot(dx, dz)
    return Vector((dx / l * (R_ARCH + r_off), y, ARCH_CZ + dz / l * (R_ARCH + r_off)))

def chaikin(o, it=2):
    for _ in range(it):
        q = []
        for i in range(len(o)):
            a, b = o[i], o[(i + 1) % len(o)]
            q.append((a[0] * 0.75 + b[0] * 0.25, a[1] * 0.75 + b[1] * 0.25)); q.append((a[0] * 0.25 + b[0] * 0.75, a[1] * 0.25 + b[1] * 0.75))
        o = q
    return o

def relief(outline, x0, z0, scale, rot, yface, s, depth, mat, bevel=0.12):
    """Carved relief: smoothed silhouette, straight sides then three insets that dome the front so it reads as rounded
    stone, not a cut-out plank."""
    outline = chaikin(outline, 2)
    area = sum(outline[i][0] * outline[(i + 1) % len(outline)][1] - outline[(i + 1) % len(outline)][0] * outline[i][1] for i in range(len(outline)))
    if area < 0: outline = outline[::-1]
    cs, sn = math.cos(rot), math.sin(rot)
    pts = [Vector((x0 + (u * cs - v * sn) * scale, yface, z0 + (u * sn + v * cs) * scale)) for u, v in outline]
    f = face(pts if s < 0 else pts[::-1], mat)
    if f is None: return
    f.normal_update()
    steps = [(0.0, 0.45), (bevel * 0.10, 0.25), (bevel * 0.12, 0.18), (bevel * 0.12, 0.12)]
    cur = [f]
    for th, dp in steps:
        r = bmesh.ops.inset_region(bm, faces=cur, thickness=th, depth=depth * dp, use_even_offset=True, use_boundary=True)
        for nf in r["faces"]: nf.material_index = mi(mat)

def tube(points, r, mat, segs=6):
    rings = []
    for i, p in enumerate(points):
        d = (points[min(i + 1, len(points) - 1)] - points[max(i - 1, 0)]).normalized()
        a = d.cross(Vector((0, 0, 1))); a = a if a.length > 1e-3 else d.cross(Vector((1, 0, 0)))
        a.normalize(); b = d.cross(a)
        taper = 1.0 if 0 < i < len(points) - 1 else 0.6
        rings.append([bm.verts.new(p + (a * math.cos(2 * math.pi * k / segs) + b * math.sin(2 * math.pi * k / segs)) * r * taper) for k in range(segs)])
    for A, B in zip(rings, rings[1:]):
        for k in range(segs):
            j = (k + 1) % segs
            try: bm.faces.new((A[k], A[j], B[j], B[k])).material_index = mi(mat)
            except ValueError: pass
    for R, rev in ((rings[0], True), (rings[-1], False)):
        try: bm.faces.new(R[::-1] if rev else R).material_index = mi(mat)
        except ValueError: pass

ANGEL = [(-0.34, 0.00), (0.10, -0.02), (0.36, 0.06), (0.40, 0.22), (0.24, 0.30), (0.26, 0.48), (0.22, 0.58), (0.34, 0.66), (0.60, 0.84),
         (0.62, 0.78), (0.32, 0.58), (0.22, 0.70), (0.16, 0.78), (0.21, 0.84), (0.19, 0.95), (0.10, 1.00), (0.02, 0.97),
         (-0.02, 0.88), (-0.10, 0.84), (-0.38, 1.12), (-0.72, 1.26), (-0.66, 1.04), (-0.58, 0.86), (-0.42, 0.66), (-0.26, 0.52), (-0.30, 0.28)]
VIRGIN = [(-0.32, 0.00), (0.30, 0.00), (0.32, 0.18), (0.24, 0.34), (0.20, 0.50), (0.14, 0.62), (0.10, 0.70), (0.13, 0.80),
          (0.09, 0.90), (0.00, 0.94), (-0.08, 0.88), (-0.10, 0.78), (-0.22, 0.62), (-0.34, 0.52), (-0.30, 0.44), (-0.18, 0.48), (-0.28, 0.24)]
SAINT = [(-0.17, 0.00), (0.17, 0.00), (0.19, 0.30), (0.22, 0.52), (0.24, 0.70), (0.12, 0.78), (0.09, 0.82), (0.11, 0.92),
         (0.03, 1.00), (-0.06, 0.98), (-0.11, 0.90), (-0.09, 0.80), (-0.13, 0.77), (-0.26, 0.70), (-0.24, 0.50), (-0.20, 0.30)]
SHIELD = [(-0.16, 0.10), (0.16, 0.10), (0.16, -0.12), (0.0, -0.28), (-0.16, -0.12)]
DOVE = [(-0.55, 0.12), (-0.25, 0.06), (-0.10, 0.10), (0.00, 0.20), (0.10, 0.10), (0.25, 0.06), (0.55, 0.12), (0.30, -0.02),
        (0.10, -0.08), (0.08, -0.30), (0.0, -0.42), (-0.08, -0.30), (-0.10, -0.08), (-0.30, -0.02)]
ESCUTCHEON = [(-0.30, 0.28), (0.30, 0.28), (0.34, 0.36), (0.30, 0.0), (0.22, -0.22), (0.0, -0.36), (-0.22, -0.22), (-0.30, 0.0), (-0.34, 0.36)]

def mirror_u(o): return [(-u, v) for u, v in o][::-1]

AX = frange(-SPAN_HALF, SPAN_HALF, 0.3)
for s in (-1, 1):
    y0 = s * HALF_W
    # archivolt: three stepped fasciae and a crowning moulding, ~0.95 m deep in elevation
    for (r0, r1, d) in ((0.0, 0.26, 0.06), (0.26, 0.50, 0.11), (0.50, 0.70, 0.16), (0.70, 0.84, 0.24)):
        yf = y0 + s * d
        for a, b in zip(AX, AX[1:]):
            A0, B0, A1, B1 = arc_pt(a, r0, yf), arc_pt(b, r0, yf), arc_pt(a, r1, yf), arc_pt(b, r1, yf)
            quad(*((A0, B0, B1, A1) if s < 0 else (B0, A0, A1, B1)), "archivolt")
            A0w, B0w, A1w, B1w = arc_pt(a, r0, y0), arc_pt(b, r0, y0), arc_pt(a, r1, y0), arc_pt(b, r1, y0)
            quad(*((A0w, B0w, B0, A0) if s < 0 else (B0w, A0w, A0, B0)), "archivolt")
            quad(*((B1w, A1w, A1, B1) if s < 0 else (A1w, B1w, B1, A1)), "archivolt")
    # keystone at the crown: rises from the intrados through the archivolt to the cornice fascia
    ktop = ramp(0) - 0.85
    obox(Vector((0, y0 + s * 0.17, (CROWN + ktop) / 2)), Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1)), 0.55, 0.17, (ktop - CROWN) / 2 + 0.06, "carved")
    if s < 0:   # south face: the dove of the Annunciation, flying towards the Virgin
        relief(DOVE, 0.0, CROWN + 0.55, 0.95, -0.25, y0 + s * 0.34, s, 0.16, "carved", 0.18)
    else:       # north face: carved rosette on the keystone
        blob(Vector((0, y0 + s * 0.36, CROWN + 0.5)), 0.26, 0.1, 0.26, "carved", segs=10, rings=6)
        for k in range(8):
            a = k * math.pi / 4
            blob(Vector((math.cos(a) * 0.28, y0 + s * 0.37, CROWN + 0.5 + math.sin(a) * 0.28)), 0.1, 0.06, 0.1, "carved", segs=6, rings=4)

    # ---- cornice that carries the balustrade: fascia, dense modillions, deep moulded slab
    for a, b in zip(XS, XS[1:]):
        for (zlo, zhi, d, mat) in ((-0.85, -0.64, 0.06, "stone"),):
            yy = y0 + s * d
            P = [Vector((a, yy, ramp(a) + zlo)), Vector((b, yy, ramp(b) + zlo)), Vector((b, yy, ramp(b) + zhi)), Vector((a, yy, ramp(a) + zhi))]
            quad(*(P if s < 0 else [P[1], P[0], P[3], P[2]]), mat)
            U = [Vector((a, y0, ramp(a) + zlo)), Vector((b, y0, ramp(b) + zlo)), Vector((b, yy, ramp(b) + zlo)), Vector((a, yy, ramp(a) + zlo))]
            quad(*(U if s < 0 else U[::-1]), mat)
        # soffit band between the modillions (recessed, gets dark AO)
        P = [Vector((a, y0 + s * 0.06, ramp(a) - 0.64)), Vector((b, y0 + s * 0.06, ramp(b) - 0.64)), Vector((b, y0 + s * 0.06, ramp(b) - 0.36)), Vector((a, y0 + s * 0.06, ramp(a) - 0.36))]
        quad(*(P if s < 0 else [P[1], P[0], P[3], P[2]]), "stone")
        # the slab: underside, cyma, fascia, top
        yo1, yo2 = y0 + s * 0.50, y0 + s * 0.62
        za, zb = ramp(a) - 0.36, ramp(b) - 0.36
        U = [Vector((a, y0 + s * 0.06, za)), Vector((b, y0 + s * 0.06, zb)), Vector((b, yo1, zb)), Vector((a, yo1, za))]
        quad(*(U if s < 0 else U[::-1]), "stone")
        F = [Vector((a, yo1, za)), Vector((b, yo1, zb)), Vector((b, yo1, zb + 0.14)), Vector((a, yo1, za + 0.14))]
        quad(*(F if s < 0 else [F[1], F[0], F[3], F[2]]), "stone")
        C = [Vector((a, yo1, za + 0.14)), Vector((b, yo1, zb + 0.14)), Vector((b, yo2, zb + 0.24)), Vector((a, yo2, za + 0.24))]
        quad(*(C if s < 0 else [C[1], C[0], C[3], C[2]]), "stone")
        F2 = [Vector((a, yo2, za + 0.24)), Vector((b, yo2, zb + 0.24)), Vector((b, yo2, zb + 0.36)), Vector((a, yo2, za + 0.36))]
        quad(*(F2 if s < 0 else [F2[1], F2[0], F2[3], F2[2]]), "stone")
        T = [Vector((a, yo2, za + 0.36)), Vector((b, yo2, zb + 0.36)), Vector((b, y0 - s * 0.5, zb + 0.36)), Vector((a, y0 - s * 0.5, za + 0.36))]
        quad(*(T if s < 0 else T[::-1]), "stone")
    for x in frange(-HALF_L + 0.3, HALF_L - 0.3, 0.30):
        zt = ramp(x) - 0.36
        obox(Vector((x, y0 + s * 0.27, zt - 0.14)), Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1)), 0.10, 0.22, 0.14, "stone")

    # ---- spandrel reliefs, sitting on the archivolt's outer edge, ~2.2 m tall, leaning with the arch
    if s < 0:
        figs = [(ANGEL, -12.7, 0.30), (mirror_u(VIRGIN), 12.7, -0.30)]
    else:
        figs = [(SAINT, -12.7, 0.30), (mirror_u(SAINT), 12.7, -0.30)]
    for i, (outl, xc, rot) in enumerate(figs):
        zb = arc_pt(xc, 0.98, y0).z - 0.05
        H = 2.6; flip = -1 if xc > 0 else 1
        cs, sn = math.cos(rot), math.sin(rot)
        def F(u, v, dy):
            u *= flip
            return Vector((xc + (u * cs - v * sn) * H, y0 + s * dy, zb + (u * sn + v * cs) * H))
        relief(outl, xc, zb, H, rot, y0, s, 0.30, "carved", 0.5)
        kind = "angel" if outl is ANGEL else ("virgin" if s < 0 else "saint")
        # head in the round with hair, halo disc behind
        head = F(0.08 if kind == "angel" else 0.02, 0.92, 0.42)
        blob(head, 0.15, 0.14, 0.18, "carved", segs=10, rings=7)
        blob(head + Vector((0, -s * 0.03, 0.06)), 0.17, 0.12, 0.13, "carved", segs=10, rings=6)
        blob(F(0.0, 0.93, 0.26), 0.30, 0.03, 0.30, "carved", segs=14, rings=4)
        # drapery: long folds falling from the waist, a belt, the hem
        for fu, ph in ((-0.10, 0.0), (0.12, 0.06)):
            tube([F(fu * 0.7, 0.52, 0.40), F(fu * 0.9 + ph, 0.33, 0.43), F(fu * 1.3 - ph, 0.12, 0.41), F(fu * 1.5, 0.03, 0.37)], 0.05, "carved")
        tube([F(-0.18, 0.56, 0.40), F(0.0, 0.53, 0.43), F(0.2, 0.56, 0.40)], 0.035, "carved")
        if kind == "angel":
            tube([F(0.14, 0.72, 0.40), F(0.30, 0.76, 0.43), F(0.46, 0.80, 0.42), F(0.53, 0.79, 0.40)], 0.05, "carved")      # pointing arm
            tube([F(-0.04, 0.72, 0.42), F(0.04, 0.64, 0.45), F(0.12, 0.62, 0.43)], 0.045, "carved")                            # arm on chest
            tube([F(0.12, 0.62, 0.45), F(0.10, 0.80, 0.47), F(0.06, 1.02, 0.45)], 0.015, "carved")                             # lily stem
            blob(F(0.06, 1.04, 0.46), 0.07, 0.05, 0.09, "carved", segs=6, rings=4)
            for k, (wu, wv) in enumerate(((-0.52, 1.06), (-0.48, 0.94), (-0.44, 0.82), (-0.38, 0.72))):                    # wing feathers
                tube([F(-0.06, 0.80 - k * 0.04, 0.36), F((wu - 0.06) / 2, (0.80 + wv) / 2 + 0.03, 0.40), F(wu, wv, 0.36)], 0.04 - k * 0.004, "carved")
            for cu in (-0.25, -0.05, 0.15):                                                                                     # cloud
                blob(F(cu, 0.0, 0.32), 0.25, 0.12, 0.14, "carved", segs=8, rings=5)
        elif kind == "virgin":
            tube([F(-0.12, 0.64, 0.42), F(0.0, 0.58, 0.46), F(0.12, 0.64, 0.42)], 0.045, "carved")                             # arms crossed
            tube([F(-0.10, 0.70, 0.40), F(-0.14, 0.86, 0.44), F(-0.04, 1.00, 0.44), F(0.10, 0.92, 0.42), F(0.10, 0.70, 0.40)], 0.04, "carved")  # veil
            obox(F(-0.36, 0.22, 0.25), Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1)), 0.22, 0.18, 0.5, "carved")    # prie-dieu
            blob(F(-0.36, 0.52, 0.3), 0.2, 0.12, 0.06, "carved", segs=6, rings=4)                                              # open book
        else:
            tube([F(-0.22, 0.68, 0.42), F(-0.28, 0.52, 0.45), F(-0.22, 0.40, 0.44)], 0.05, "carved")
            tube([F(0.24, 0.68, 0.42), F(0.32, 0.55, 0.46)], 0.05, "carved")
            if i == 1:   # St Theodore: armour band, spear and shield
                tube([F(0.34, -0.02, 0.48), F(0.34, 0.6, 0.48), F(0.34, 1.18, 0.48)], 0.025, "carved")
                relief(SHIELD, F(0.26, 0.42, 0).x, F(0.26, 0.42, 0).z, 1.4, rot, y0 + s * 0.36, s, 0.14, "carved", 0.4)
                tube([F(-0.2, 0.70, 0.43), F(0.0, 0.74, 0.46), F(0.2, 0.70, 0.43)], 0.04, "carved")
            else:        # St Mark with his gospel
                obox(F(0.30, 0.55, 0.5), Vector((cs, 0, sn)), Vector((0, 1, 0)), Vector((-sn, 0, cs)), 0.16, 0.06, 0.2, "carved")
        # cartouche with scrolled ends under the cornice
        ex = xc + 1.15 * flip; ez = ramp(ex) - 1.6
        relief([(-0.42, 0.42), (0.42, 0.42), (0.50, 0.0), (0.42, -0.42), (-0.42, -0.42), (-0.50, 0.0)], ex, ez, 1.0, 0.0, y0, s, 0.10, "carved", 0.3)
        relief(SHIELD, ex, ez + 0.08, 1.6, 0.0, y0 + s * 0.1, s, 0.10, "stone_light", 0.4)
        for e in (-1, 1):
            tube([Vector((ex + e * 0.36, y0 + s * 0.18, ez + 0.32)), Vector((ex + e * 0.48, y0 + s * 0.2, ez + 0.2)), Vector((ex + e * 0.42, y0 + s * 0.2, ez + 0.06)), Vector((ex + e * 0.32, y0 + s * 0.18, ez + 0.1))], 0.05, "carved")
    # ---- inscription tablets near the abutments: moulded eared frame, side brackets, recessed lettered field
    for xc in (-16.6, 16.6):
        k = [i for i, p in enumerate(PLAQUES) if p[0] == xc and p[4] == s][0]
        _, zc, hw, hh, _ = PLAQUES[k]
        X, Y, Z = Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1))
        # two-step moulded frame with "ears" at the top corners and a tablet foot
        obox(Vector((xc, y0 + s * 0.04, zc)), X, Y, Z, hw + 0.24, 0.04, hh + 0.24, "stone")
        obox(Vector((xc, y0 + s * 0.10, zc)), X, Y, Z, hw + 0.12, 0.04, hh + 0.12, "stone")
        for ex in (-1, 1):
            obox(Vector((xc + ex * (hw + 0.30), y0 + s * 0.09, zc + hh - 0.02)), X, Y, Z, 0.10, 0.09, 0.26, "stone")
            tube([Vector((xc + ex * (hw + 0.30), y0 + s * 0.2, zc - hh + 0.2 + 0.25 * t)) for t in (0, 1, 2)], 0.07, "carved")
            blob(Vector((xc + ex * (hw + 0.30), y0 + s * 0.2, zc - hh + 0.12)), 0.11, 0.08, 0.11, "carved", segs=8, rings=5)  # scroll volute
        obox(Vector((xc, y0 + s * 0.08, zc - hh - 0.32)), X, Y, Z, 0.5, 0.08, 0.1, "stone")
        # recessed lettered field (its own material carries the incised inscription)
        P = [Vector((xc - hw, y0 + s * 0.145, zc - hh)), Vector((xc + hw, y0 + s * 0.145, zc - hh)), Vector((xc + hw, y0 + s * 0.145, zc + hh)), Vector((xc - hw, y0 + s * 0.145, zc + hh))]
        face(P if s < 0 else P[::-1], f"plaque{k}")
        for (bx0, bx1, bz0, bz1) in ((-hw - 0.06, hw + 0.06, hh, hh + 0.06), (-hw - 0.06, hw + 0.06, -hh - 0.06, -hh), (-hw - 0.06, -hw, -hh, hh), (hw, hw + 0.06, -hh, hh)):
            box(xc + bx0, xc + bx1, min(y0 + s * 0.14, y0 + s * 0.18), max(y0 + s * 0.14, y0 + s * 0.18), zc + bz0, zc + bz1, "stone")
    # small carved crests over the abutment ends
    for xc in (-20.2, 20.2):
        relief(ESCUTCHEON, xc, ramp(xc) - 1.7, 0.8, 0.0, y0, s, 0.12, "carved", 0.18)

# ================================================================ 3. walkways: stairs with Istrian nosings following the deck curve
def ramp_inv(z):
    """|x| where ramp(|x|) = z (on the descending part)."""
    if z >= CREST_Z: return CREST_HALF
    f = ((CREST_Z - z) / (CREST_Z - END_Z)) ** (1 / 1.6)
    return CREST_HALF + f * (HALF_L - CREST_HALF)

def stairs(y0, y1):
    # landing at the crest: trachyte with white Istrian bands across it
    quad(Vector((-CREST_HALF, y0, CREST_Z)), Vector((CREST_HALF, y0, CREST_Z)), Vector((CREST_HALF, y1, CREST_Z)), Vector((-CREST_HALF, y1, CREST_Z)), "trachyte")
    for bx in frange(-CREST_HALF + 0.6, CREST_HALF - 0.6, 1.2):
        quad(Vector((bx - 0.09, y0, CREST_Z + 0.004)), Vector((bx + 0.09, y0, CREST_Z + 0.004)), Vector((bx + 0.09, y1, CREST_Z + 0.004)), Vector((bx - 0.09, y1, CREST_Z + 0.004)), "nosing")
    for d in (-1, 1):
        x = CREST_HALF; z = CREST_Z
        while z > END_Z + 1e-6:
            z2 = z - STEP_H
            x2 = min(ramp_inv(z2 + STEP_H * 0.5), HALF_L)
            x2 = max(x2, x + 0.28)                           # never steeper than a 28 cm tread
            xa, xb = d * x, d * x2
            r = [Vector((xa, y0, z2)), Vector((xa, y1, z2)), Vector((xa, y1, z)), Vector((xa, y0, z))]
            quad(*(r if d > 0 else r[::-1]), "nosing")
            nose = d * 0.15
            t = [Vector((xa, y0, z2)), Vector((xa + nose, y0, z2)), Vector((xa + nose, y1, z2)), Vector((xa, y1, z2))]
            quad(*(t if d > 0 else t[::-1]), "nosing")
            t = [Vector((xa + nose, y0, z2)), Vector((xb, y0, z2)), Vector((xb, y1, z2)), Vector((xa + nose, y1, z2))]
            quad(*(t if d > 0 else t[::-1]), "trachyte")
            x = x2; z = z2
            if x >= HALF_L: break
stairs(-ROW_IN, ROW_IN)
stairs(-BAL_IN, -ROW_OUT); stairs(ROW_OUT, BAL_IN)

# ================================================================ 4. balustrades: dado plinth, square-based vase balusters, massive rail, pedestals
BALUSTER = [(0.10, 0.00), (0.10, 0.05), (0.08, 0.07), (0.10, 0.13), (0.165, 0.25), (0.18, 0.33), (0.155, 0.42),
            (0.085, 0.51), (0.065, 0.56), (0.10, 0.60), (0.10, 0.64)]
PL_T = 0.15; RAIL0 = 0.98; RAIL1 = 1.24
for s in (-1, 1):
    yo, yi = s * (HALF_W + 0.02), s * BAL_IN
    ym = (yo + yi) / 2
    for a, b in zip(XS, XS[1:]):
        # dado plinth: outer face continues the cornice top, inner face rises from the stairs
        for yy, sg in ((yo, s), (yi, -s)):
            P = [Vector((a, yy, ramp(a) - 0.08)), Vector((b, yy, ramp(b) - 0.08)), Vector((b, yy, ramp(b) + PL_T)), Vector((a, yy, ramp(a) + PL_T))]
            quad(*(P if sg < 0 else [P[1], P[0], P[3], P[2]]), "stone")
        T = [Vector((a, yi, ramp(a) + PL_T)), Vector((b, yi, ramp(b) + PL_T)), Vector((b, yo, ramp(b) + PL_T)), Vector((a, yo, ramp(a) + PL_T))]
        quad(*(T if s > 0 else T[::-1]), "stone")
        # massive rail with a rounded (two-slope) top and a drip moulding underneath
        za, zb = ramp(a) + RAIL0, ramp(b) + RAIL0
        y_out, y_in = yo + s * 0.06, yi - s * 0.06
        h = RAIL1 - RAIL0
        for yy, sg in ((y_out, s), (y_in, -s)):
            P = [Vector((a, yy, za)), Vector((b, yy, zb)), Vector((b, yy, zb + h * 0.7)), Vector((a, yy, za + h * 0.7))]
            quad(*(P if sg < 0 else [P[1], P[0], P[3], P[2]]), "stone")
            Q = [Vector((a, yy, za + h * 0.7)), Vector((b, yy, zb + h * 0.7)), Vector((b, ym, zb + h)), Vector((a, ym, za + h))]
            quad(*(Q if sg < 0 else [Q[1], Q[0], Q[3], Q[2]]), "stone")
        B = [Vector((a, y_in, za)), Vector((b, y_in, zb)), Vector((b, y_out, zb)), Vector((a, y_out, za))]
        quad(*(B[::-1] if s > 0 else B), "stone")
    peds = sorted(set([round(v, 2) for v in [-HALF_L + 0.35, HALF_L - 0.35] + [d * (CREST_HALF + k * BAY_L) for d in (-1, 1) for k in range(BAYS + 1)]]))
    for x in peds:
        box(x - 0.30, x + 0.30, min(yi, yo) - 0.04, max(yi, yo) + 0.04, ramp(x) - 0.08, ramp(x) + RAIL1 + 0.06, "stone")
        box(x - 0.36, x + 0.36, min(yi, yo) - 0.09, max(yi, yo) + 0.09, ramp(x) + RAIL1 + 0.06, ramp(x) + RAIL1 + 0.18, "stone")
    for a, b in zip(peds, peds[1:]):
        n = max(1, int((b - a - 0.62) / 0.40))
        for i in range(n):
            x = a + 0.31 + (i + 0.5) * (b - a - 0.62) / n
            z0 = ramp(x) + PL_T; h = RAIL0 - PL_T
            box(x - 0.15, x + 0.15, ym - 0.15, ym + 0.15, z0, z0 + 0.08, "stone")
            box(x - 0.14, x + 0.14, ym - 0.14, ym + 0.14, z0 + h - 0.07, z0 + h, "stone")
            prof = [(r, 0.08 + dz * (h - 0.15) / 0.64) for r, dz in BALUSTER]
            lathe(x, ym, z0, prof, "stone", segs=10)

# ================================================================ 5. shop rows
def arch_pts(cx, spring, r, n=10):
    return [(cx + r * math.cos(math.pi * i / n), spring + r * math.sin(math.pi * i / n)) for i in range(n + 1)]

def bay_face(x0, x1, y, s, inner, k, d):
    """One bay of a shop row face on the plane y (normal = s*Y). x0<x1."""
    xm = (x0 + x1) / 2
    zfloor = walk(xm) if True else 0
    ztop = row_top(xm)
    pil = 0.20
    r = (x1 - x0) / 2 - pil - 0.03
    spring = zfloor + 4.25 - r
    rec = 0.32
    # wall around the arch (one polygon with the arch cut as its lower edge)
    ap = arch_pts(xm, spring, r)
    lo0, lo1 = walk(x0) - 0.05, walk(x1) - 0.05
    outline = [(x0 + pil, lo0), (xm - r, lo0)] + [(px, pz) for px, pz in ap[::-1]] + [(xm + r, lo1), (x1 - pil, lo1), (x1 - pil, row_top(x1 - pil) - 0.55), (x0 + pil, row_top(x0 + pil) - 0.55)]
    # the arch cut makes the outline self-overlapping at the base: split into left jamb, right jamb, spandrel strip
    def P(px, pz, yy): return Vector((px, yy, pz))
    def addpoly(pp, mat, yy):
        V = [P(px, pz, yy) for px, pz in pp]
        if s > 0: V = V[::-1]          # outlines are CCW in (x, z), which faces -Y
        face(V, mat)
    addpoly([(x0 + pil, lo0), (xm - r, lo0), (xm - r, spring), (x0 + pil, spring)], "ashlar", y)
    addpoly([(xm + r, lo1), (x1 - pil, lo1), (x1 - pil, spring), (xm + r, spring)], "ashlar", y)
    top_l, top_r = row_top(x0 + pil) - 0.55, row_top(x1 - pil) - 0.55
    span = [(x0 + pil, spring)] + ap[::-1][0:0] + [(xm - r, spring)] + [p for p in ap[::-1][1:-1]][::-1][::-1]
    # spandrel above the arch: left half and right half to keep convex-ish polygons
    half = len(ap) // 2
    left = [(x0 + pil, spring), (xm - r, spring)] + ap[::-1][1:half + 1] + [(xm, top_l + (top_r - top_l) * 0.5), (x0 + pil, top_l)]
    right = [ap[half], ap[half - 1]] if False else None
    left_poly = [(x0 + pil, spring)] + [p for p in ap[::-1][:half + 1]] + [(xm, (top_l + top_r) / 2), (x0 + pil, top_l)]
    right_poly = [p for p in ap[::-1][half:]] + [(x1 - pil, spring), (x1 - pil, top_r), (xm, (top_l + top_r) / 2)]
    addpoly(left_poly, "ashlar", y); addpoly(right_poly, "ashlar", y)
    # archivolt ring (proud) and keystone
    yr = y + s * 0.07
    for (a0, a1) in zip(ap, ap[1:]):
        for (rr0, rr1) in ((0.0, 0.2),):
            def rp(pt, rr):
                vx, vz = pt[0] - xm, pt[1] - spring; l = math.hypot(vx, vz)
                return (xm + vx / l * (r + rr), spring + vz / l * (r + rr))
            A, B, C, D = rp(a0, rr0), rp(a1, rr0), rp(a1, rr1), rp(a0, rr1)
            V = [P(*A, yr), P(*B, yr), P(*C, yr), P(*D, yr)]
            face(V if s > 0 else V[::-1], "stone_light")
            V2 = [P(*A, y), P(*B, y), P(*B, yr), P(*A, yr)]
            face(V2 if s > 0 else V2[::-1], "stone_light")
    obox(Vector((xm, y + s * 0.13, spring + r + 0.2)), Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1)), 0.2, 0.13, 0.32, "carved")
    # impost blocks
    for ex in (xm - r - 0.08, xm + r + 0.08):
        obox(Vector((ex, y + s * 0.05, spring - 0.06)), Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1)), 0.14, 0.06, 0.08, "stone_light")
    # reveal (soffit of the opening down to the recessed infill)
    yb = y - s * rec
    for (a0, a1) in zip(ap, ap[1:]):
        V = [P(*a0, y), P(*a1, y), P(*a1, yb), P(*a0, yb)]
        face(V if s < 0 else V[::-1], "stone")
    for ex, sgn in ((xm - r, 1), (xm + r, -1)):
        lo = lo0 if sgn > 0 else lo1
        V = [P(ex, lo, y), P(ex, spring, y), P(ex, spring, yb), P(ex, lo, yb)]
        face(V if (s * sgn) > 0 else V[::-1], "stone")
    # infill on the recessed plane
    lun = [(xm - r, spring)] + ap[::-1][1:-1] + [(xm + r, spring)]
    if not inner:
        addpoly(lun[::-1] if False else [(xm + r, spring)] + ap[1:-1] + [(xm - r, spring)], "louvre", yb)
        box(xm - r, xm + r, min(yb, yb + s * 0.06), max(yb, yb + s * 0.06), spring - 0.04, spring + 0.04, "iron")
        for i in range(1, 6):
            ang = math.pi * i / 6
            ex, ez = xm + math.cos(ang) * r * 0.97, spring + math.sin(ang) * r * 0.97
            c = Vector(((xm + ex) / 2, yb + s * 0.04, (spring + ez) / 2))
            a = Vector((ex - xm, 0, ez - spring)).normalized()
            obox(c, a, Vector((0, 1, 0)), a.cross(Vector((0, 1, 0))).normalized(), r * 0.49, 0.012, 0.012, "iron")
        # wooden beam with a small sloping canopy under the lunette
        box(xm - r, xm + r, min(yb, yb + s * 0.12), max(yb, yb + s * 0.12), spring - 0.28, spring, "wood")
        cz = spring - 0.3
        V = [Vector((xm - r, yb, cz + 0.02)), Vector((xm + r, yb, cz + 0.02)), Vector((xm + r, yb + s * 0.55, cz - 0.18)), Vector((xm - r, yb + s * 0.55, cz - 0.18))]
        face(V if s > 0 else V[::-1], "wood")
        addpoly([(xm - r, min(lo0, lo1)), (xm + r, min(lo0, lo1)), (xm + r, spring - 0.28), (xm - r, spring - 0.28)], "door", yb)
        # door panels: thin proud frames
        for px in (xm - r * 0.5, xm + r * 0.5):
            obox(Vector((px, yb + s * 0.03, (zfloor + spring - 0.28) / 2)), Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1)), r * 0.38, 0.02, (spring - 0.28 - zfloor) * 0.38, "door")
    else:
        addpoly([(xm + r, spring)] + ap[1:-1] + [(xm - r, spring)], "glass", yb)
        # iron grille: radial bars + a ring
        for i in range(1, 6):
            ang = math.pi * i / 6
            ex, ez = xm + math.cos(ang) * r * 0.97, spring + math.sin(ang) * r * 0.97
            c = Vector(((xm + ex) / 2, yb + s * 0.04, (spring + ez) / 2))
            a = Vector((ex - xm, 0, ez - spring)).normalized()
            obox(c, a, Vector((0, 1, 0)), a.cross(Vector((0, 1, 0))).normalized(), r * 0.49, 0.015, 0.015, "iron")
        box(xm - r, xm + r, min(yb, yb + s * 0.05), max(yb, yb + s * 0.05), spring - 0.08, spring, "iron")
        shop = random.random()
        lo = min(lo0, lo1)
        top = spring - 0.08
        if shop < 0.3:      # grey roll-down shutter
            addpoly([(xm - r, lo), (xm + r, lo), (xm + r, top), (xm - r, top)], "roll", yb)
            box(xm - r, xm + r, min(yb, yb + s * 0.18), max(yb, yb + s * 0.18), top - 0.3, top, "roll")   # shutter box
        elif shop < 0.55:   # old green wooden doors
            addpoly([(xm - r, lo), (xm + r, lo), (xm + r, top), (xm - r, top)], "door", yb)
            for px in (xm - r * 0.5, xm + r * 0.5):
                obox(Vector((px, yb + s * 0.03, (lo + top) / 2)), Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1)), r * 0.4, 0.02, (top - lo) * 0.4, "door")
        else:               # open shop: display glazing in a wooden frame
            addpoly([(xm - r, lo), (xm + r, lo), (xm + r, top), (xm - r, top)], "glass", yb + s * 0.02)
            for px in (xm - r + 0.05, xm, xm + r - 0.05):
                box(px - 0.04, px + 0.04, min(yb, yb + s * 0.06), max(yb, yb + s * 0.06), lo, top - 0.5, "wood")
        # wooden sign board across the opening under the lunette
        box(xm - r + 0.05, xm + r - 0.05, min(yb, yb + s * 0.1), max(yb, yb + s * 0.1), top - 0.5, top - 0.1, "sign")
        # thin canvas awning: sags in three facets, iron arms, hanging valance
        if random.random() < 0.7:
            mat = ["canvas", "canvas", "canvas3", "canvas2"][random.randrange(4)]
            az = spring - 0.25; out = 1.1; x_a, x_b = xm - r - 0.05, xm + r + 0.05
            prof = [(0.0, 0.0), (0.4, -0.20), (0.75, -0.36), (out, -0.48)]
            for (u0, w0), (u1, w1) in zip(prof, prof[1:]):
                V = [Vector((x_a, y + s * u0, az + w0)), Vector((x_b, y + s * u0, az + w0)), Vector((x_b, y + s * u1, az + w1)), Vector((x_a, y + s * u1, az + w1))]
                face(V if s > 0 else V[::-1], mat); face(V[::-1] if s > 0 else V, mat)
            V2 = [Vector((x_a, y + s * out, az - 0.48)), Vector((x_b, y + s * out, az - 0.48)), Vector((x_b, y + s * out, az - 0.73)), Vector((x_a, y + s * out, az - 0.73))]
            face(V2 if s > 0 else V2[::-1], mat); face(V2[::-1] if s > 0 else V2, mat)
            for ex in (x_a + 0.05, x_b - 0.05):
                c = Vector((ex, y + s * out / 2, az - 0.27)); a = Vector((0, s * out, -0.5)).normalized()
                obox(c, a, Vector((1, 0, 0)), a.cross(Vector((1, 0, 0))).normalized(), 0.6, 0.012, 0.012, "iron")
        # wrought-iron lantern on a scroll bracket at every other pier
        if k % 2 == 0:
            lx = x0 + 0.02; lz = spring + 0.55
            obox(Vector((lx, y + s * 0.3, lz + 0.35)), Vector((0, 1, 0)), Vector((1, 0, 0)), Vector((0, 0, 1)), 0.3, 0.015, 0.015, "iron")
            blob(Vector((lx, y + s * 0.18, lz + 0.22)), 0.02, 0.12, 0.12, "iron", segs=6, rings=4)
            lathe(lx, y + s * 0.6, lz - 0.2, [(0.0, 0.0), (0.06, 0.02), (0.11, 0.14), (0.13, 0.36), (0.07, 0.40), (0.15, 0.44), (0.03, 0.58), (0.0, 0.6)], "iron", segs=6)
            lathe(lx, y + s * 0.6, lz - 0.16, [(0.10, 0.0), (0.135, 0.32)], "lampglass", segs=6)

for side in (-1, 1):
    for d in (-1, 1):
        for k in range(BAYS):
            xa, xb = d * (CREST_HALF + k * BAY_L), d * (CREST_HALF + (k + 1) * BAY_L)
            x0, x1 = min(xa, xb), max(xa, xb)
            # outer face (toward the balustrade walkway) and inner face (toward the central walkway)
            bay_face(x0, x1, side * ROW_OUT, side, False, k, d)
            bay_face(x0, x1, side * ROW_IN, -side, True, k, d)
            # pilasters at the bay edge (both faces)
            for yy, sg in ((side * ROW_OUT, side), (side * ROW_IN, -side)):
                for ex in (x0, x1):
                    zb = walk(ex) - 0.05; zt = row_top(ex) - 0.98
                    box(ex - 0.20, ex + 0.20, min(yy, yy + sg * 0.12), max(yy, yy + sg * 0.12), zb, zt, "ashlar")
                    box(ex - 0.26, ex + 0.26, min(yy, yy + sg * 0.18), max(yy, yy + sg * 0.18), zt - 0.22, zt, "stone_light")  # capital
    # row cornice (entablature) on both faces: frieze + projecting moulding, straight sloping line
    xs_row = [d * v for d in (-1, 1) for v in frange(CREST_HALF, ROW_END, 0.5)]
    for d in (-1, 1):
        xr = [d * v for v in frange(CREST_HALF, ROW_END, 0.5)]
        for yy, sg in ((side * ROW_OUT, side), (side * ROW_IN, -side)):
            for a, b in zip(xr, xr[1:]):
                a, b = min(a, b), max(a, b)
                za, zb = row_top(a), row_top(b)
                P = [Vector((a, yy, za - 0.55)), Vector((b, yy, zb - 0.55)), Vector((b, yy, zb - 0.25)), Vector((a, yy, za - 0.25))]
                face(P if sg < 0 else [P[1], P[0], P[3], P[2]], "stone")
                yf = yy + sg * 0.14   # frieze proud of the wall, piers stop under it
                Fz = [Vector((a, yf, za - 1.0)), Vector((b, yf, zb - 1.0)), Vector((b, yf, za * 0 + zb - 0.55)), Vector((a, yf, za - 0.55))]
                face(Fz if sg < 0 else [Fz[1], Fz[0], Fz[3], Fz[2]], "stone_light")
                Fu = [Vector((a, yy, za - 1.0)), Vector((b, yy, zb - 1.0)), Vector((b, yf, zb - 1.0)), Vector((a, yf, za - 1.0))]
                face(Fu if sg < 0 else Fu[::-1], "stone_light")
                Ft = [Vector((a, yf, za - 0.55)), Vector((b, yf, zb - 0.55)), Vector((b, yy, zb - 0.55)), Vector((a, yy, za - 0.55))]
                face(Ft if sg < 0 else Ft[::-1], "stone_light")
                yo = yy + sg * 0.35
                for mx in frange(a, b, 0.25)[:-1]:
                    mz = row_top(mx) - 0.25
                    box(mx - 0.05, mx + 0.05, min(yy, yy + sg * 0.3), max(yy, yy + sg * 0.3), mz - 0.12, mz - 0.02, "stone")
                Q = [Vector((a, yy, za - 0.25)), Vector((b, yy, zb - 0.25)), Vector((b, yo, zb - 0.05)), Vector((a, yo, za - 0.05))]
                face(Q if sg < 0 else [Q[1], Q[0], Q[3], Q[2]], "stone")
                R = [Vector((a, yo, za - 0.05)), Vector((b, yo, zb - 0.05)), Vector((b, yo, zb + 0.12)), Vector((a, yo, za + 0.12))]
                face(R if sg < 0 else [R[1], R[0], R[3], R[2]], "stone")
        # end wall of the row with a blind arch
        xe = d * ROW_END
        ye0, ye1 = sorted((side * ROW_IN, side * ROW_OUT))
        zb = walk(xe) - 0.05; zt = row_top(xe)
        P = [Vector((xe, ye0, zb)), Vector((xe, ye1, zb)), Vector((xe, ye1, zt)), Vector((xe, ye0, zt))]
        face(P if d > 0 else P[::-1], "ashlar")
        ym = (ye0 + ye1) / 2
        for (a0, a1) in zip(arch_pts(0, 0, 1.3), arch_pts(0, 0, 1.3)[1:]):
            V = [Vector((xe + d * 0.08, ym + a0[0], zb + 2.4 + a0[1])), Vector((xe + d * 0.08, ym + a1[0], zb + 2.4 + a1[1])),
                 Vector((xe + d * 0.08, ym + a1[0] * 1.15, zb + 2.4 + a1[1] * 1.15)), Vector((xe + d * 0.08, ym + a0[0] * 1.15, zb + 2.4 + a0[1] * 1.15))]
            face(V[::-1] if d > 0 else V, "stone_light")
        # gable of the roof at the end
        zr = zt + 0.12
        G = [Vector((xe, ye0 - 0.35, zr)), Vector((xe, ye1 + 0.35, zr)), Vector((xe, ym, zr + 1.35))]
        face(G if d > 0 else G[::-1], "stone")
    # lead roof: two slopes per row, ridge along x, standing rolls every 0.75 m
    ye0, ye1 = sorted((side * ROW_IN, side * ROW_OUT)); ym = (ye0 + ye1) / 2
    for d in (-1, 1):
        xr = sorted([d * v for v in frange(CREST_HALF - 0.3, ROW_END + 0.3, 0.75)])
        for a, b in zip(xr, xr[1:]):
            za, zb = row_top(a) + 0.12, row_top(b) + 0.12
            for (yE, sg) in ((ye0 - 0.4, -1), (ye1 + 0.4, 1)):
                P = [Vector((a, yE, za)), Vector((b, yE, zb)), Vector((b, ym, zb + 1.35)), Vector((a, ym, za + 1.35))]
                face(P if sg < 0 else [P[1], P[0], P[3], P[2]], "lead")
                # soffit of the eave
                S = [Vector((a, yE, za)), Vector((b, yE, zb)), Vector((b, yE - sg * 0.4, zb)), Vector((a, yE - sg * 0.4, za))]
                face(S if sg > 0 else [S[1], S[0], S[3], S[2]], "lead")
            # standing roll from ridge to both eaves
            for (yE, sg) in ((ye0 - 0.4, -1), (ye1 + 0.4, 1)):
                c = Vector((a, (yE + ym) / 2, (za + za + 1.35) / 2 + 0.04))
                ax = Vector((0, ym - yE, 1.35)).normalized()
                tube([Vector((a, yE, za + 0.03)), Vector((a, ym, za + 1.35 + 0.03))], 0.045, "lead", segs=6)
                blob(Vector((a, yE - sg * 0.02, za - 0.02)), 0.11, 0.09, 0.11, "lead", segs=8, rings=5)   # rolled end cap -> scalloped eave
        # ridge capping
        obox(Vector(((CREST_HALF + ROW_END) / 2 * d, ym, (row_top(CREST_HALF) + row_top(ROW_END)) / 2 + 1.5)),
             Vector((d * (ROW_END - CREST_HALF), 0, row_top(ROW_END) - row_top(CREST_HALF))).normalized(), Vector((0, 1, 0)), Vector((0, 0, 1)),
             ((ROW_END - CREST_HALF) ** 2 + (row_top(ROW_END) - row_top(CREST_HALF)) ** 2) ** 0.5 / 2, 0.09, 0.06, "lead")

# ================================================================ 6. central portico (boolean: block minus two barrel passages)
pbm = bmesh.new()
PZ0 = CREST_Z - 0.05; PZ1 = CREST_Z + 8.6 - 0.2; PHX = 3.6; PHY = ROW_OUT + 0.25
bmesh.ops.create_cube(pbm, size=1.0)
for v in pbm.verts: v.co = Vector((v.co.x * 2 * PHX, v.co.y * 2 * PHY, PZ0 + (v.co.z + 0.5) * (PZ1 - PZ0)))
for f in pbm.faces: f.material_index = mi("rustic")
pme = bpy.data.meshes.new("portico"); pbm.to_mesh(pme); pbm.free()
por = bpy.data.objects.new("Portico", pme); bpy.context.scene.collection.objects.link(por)
for m in MAT_ORDER: pme.materials.append(MATS[m][0])

def tunnel(name, along_x, half_w, crown_h, length):
    tb = bmesh.new(); prof = []
    spring = crown_h - half_w
    prof = [(-half_w, -1.0), (half_w, -1.0)] + [(half_w * math.cos(math.pi * i / 32), spring + half_w * math.sin(math.pi * i / 32)) for i in range(33)]
    vs0 = [tb.verts.new((-length, u, PZ0 + z) if along_x else (u, -length, PZ0 + z)) for u, z in prof]
    vs1 = [tb.verts.new((length, u, PZ0 + z) if along_x else (u, length, PZ0 + z)) for u, z in prof]
    n = len(prof)
    for i in range(n):
        j = (i + 1) % n
        tb.faces.new((vs0[i], vs0[j], vs1[j], vs1[i]))
    tb.faces.new(vs0[::-1]); tb.faces.new(vs1)
    bmesh.ops.recalc_face_normals(tb, faces=tb.faces[:])
    me = bpy.data.meshes.new(name); tb.to_mesh(me); tb.free()
    ob = bpy.data.objects.new(name, me); bpy.context.scene.collection.objects.link(ob)
    me.materials.append(MATS["plaster"][0])
    return ob

t1 = tunnel("t1", True, 2.6, 6.2, 20)      # the central walkway passage
t2 = tunnel("t2", False, 2.05, 7.45, 20)    # the cross passage over the outer walkways
for t in (t1, t2):
    mod = por.modifiers.new("b", "BOOLEAN"); mod.operation = "DIFFERENCE"; mod.object = t; mod.solver = "EXACT"
    mod.material_mode = "TRANSFER"
bpy.context.view_layer.objects.active = por
for m in list(por.modifiers): bpy.ops.object.modifier_apply(modifier=m.name)
bpy.data.objects.remove(t1); bpy.data.objects.remove(t2)

# portico ornaments: pilaster capitals, voussoirs, keystone heads, entablature, pediments, tie rods, lanterns
def pediment(face_axis, sgn, base_z, half_w, depth):
    """Triangular pediment with a raking cornice on the face perpendicular to face_axis ('x' or 'y')."""
    h = half_w * 0.42
    if face_axis == "y":
        y = sgn * (PHY + depth)
        T = [Vector((-half_w, y, base_z)), Vector((half_w, y, base_z)), Vector((0, y, base_z + h))]
        face(T[::-1] if sgn > 0 else T, "stone")
        for a, b in ((T[0], T[2]), (T[2], T[1])):
            c = (a + b) / 2 + Vector((0, sgn * 0.15, 0.12)); ax = (b - a).normalized()
            obox(c, ax, Vector((0, 1, 0)), ax.cross(Vector((0, 1, 0))).normalized() * -1, (b - a).length / 2 + 0.3, 0.5, 0.15, "stone")
        # back of the pediment / roof gable to the portico roof
        T2 = [Vector((-half_w, sgn * PHY, base_z)), Vector((half_w, sgn * PHY, base_z)), Vector((0, sgn * PHY, base_z + h))]
        for a, b in ((0, 2), (2, 1)):
            P = [T[a], T[b], T2[b], T2[a]]; face(P if sgn < 0 else P[::-1], "stone")
    else:
        x = sgn * (PHX + depth)
        T = [Vector((x, -half_w, base_z)), Vector((x, half_w, base_z)), Vector((x, 0, base_z + h))]
        face(T if sgn > 0 else T[::-1], "stone")
        for a, b in ((T[0], T[2]), (T[2], T[1])):
            c = (a + b) / 2 + Vector((sgn * 0.15, 0, 0.12)); ax = (b - a).normalized()
            obox(c, ax, Vector((1, 0, 0)), ax.cross(Vector((1, 0, 0))).normalized() * -1, (b - a).length / 2 + 0.3, 0.5, 0.15, "stone")
        T2 = [Vector((sgn * PHX, -half_w, base_z)), Vector((sgn * PHX, half_w, base_z)), Vector((sgn * PHX, 0, base_z + h))]
        for a, b in ((0, 2), (2, 1)):
            P = [T[a], T[b], T2[b], T2[a]]; face(P[::-1] if sgn < 0 else P, "stone")
    return h

ENT = PZ1 - 1.0
def mask(kc, fa, sgn):
    """Bearded male mask on the portico keystone: projecting block, face, brow, nose, moustache, lobed beard, hair."""
    if fa == "y": out = Vector((0, sgn, 0)); side = Vector((1, 0, 0))
    else: out = Vector((sgn, 0, 0)); side = Vector((0, 1, 0))
    up = Vector((0, 0, 1))
    base = kc - out * 0.25
    k = 1.7
    obox(base + out * 0.12 + up * 0.1, side, out, up, 0.42, 0.14, 0.62, "carved")               # keystone block
    P = lambda a, b, c: base + side * a * k + out * b * k + up * c * k
    blob(P(0, 0.30, 0.25), 0.36 * k, 0.12 * k, 0.30 * k, "carved", segs=10, rings=6)                         # hair mass
    blob(P(0, 0.40, 0.05), 0.24 * k, 0.16 * k, 0.30 * k, "carved", segs=10, rings=7)                         # face
    blob(P(0, 0.52, 0.18), 0.22 * k, 0.06 * k, 0.05 * k, "carved", segs=8, rings=4)                          # brow
    blob(P(0, 0.56, 0.04), 0.05 * k, 0.08 * k, 0.12 * k, "carved", segs=6, rings=5)                          # nose
    for e in (-1, 1):
        blob(P(e * 0.10, 0.50, 0.10), 0.05 * k, 0.03 * k, 0.03 * k, "dark", segs=6, rings=4)                 # eye sockets
        blob(P(e * 0.11, 0.53, -0.08), 0.12 * k, 0.05 * k, 0.04 * k, "carved", segs=6, rings=4, rot=e * 0.4)  # moustache
        blob(P(e * 0.13, 0.44, -0.32), 0.12 * k, 0.10 * k, 0.22 * k, "carved", segs=8, rings=5)              # beard lobes
    blob(P(0, 0.47, -0.36), 0.13 * k, 0.11 * k, 0.26 * k, "carved", segs=8, rings=5)
for sgn in (-1, 1):
    # entablature bands on all four faces
    for (fa, hw, hx) in (("y", PHX, PHY), ("x", PHY, PHX)):
        if fa == "y":
            # broken entablature: ressauts over the pilasters only, the arch breaks through the middle
            for e in (-1, 1):
                x0_, x1_ = sorted((e * (PHX + 0.2), e * 3.05))
                box(x0_, x1_, sgn * PHY - 0.2 if sgn < 0 else sgn * PHY - 0.05, sgn * PHY + 0.05 if sgn < 0 else sgn * PHY + 0.2, ENT, ENT + 0.55, "stone")
                x0_, x1_ = sorted((e * (PHX + 0.4), e * 2.95))
                box(x0_, x1_, sgn * PHY - 0.4 if sgn < 0 else sgn * PHY - 0.05, sgn * PHY + 0.05 if sgn < 0 else sgn * PHY + 0.4, ENT + 0.55, ENT + 0.85, "stone")
                # smooth panelled pilaster on the canal face
                xc_ = e * (PHX - 0.28)
                box(xc_ - 0.42, xc_ + 0.42, min(sgn * PHY, sgn * (PHY + 0.12)), max(sgn * PHY, sgn * (PHY + 0.12)), PZ0 + 0.05, ENT, "stone_light")
                box(xc_ - 0.28, xc_ + 0.28, min(sgn * PHY, sgn * (PHY + 0.16)), max(sgn * PHY, sgn * (PHY + 0.16)), PZ0 + 0.5, ENT - 0.4, "stone")
        else:
            box(sgn * PHX - 0.2 if sgn < 0 else sgn * PHX - 0.05, sgn * PHX + 0.05 if sgn < 0 else sgn * PHX + 0.2, -PHY - 0.2, PHY + 0.2, ENT, ENT + 0.55, "stone")
            box(sgn * PHX - 0.4 if sgn < 0 else sgn * PHX - 0.05, sgn * PHX + 0.05 if sgn < 0 else sgn * PHX + 0.4, -PHY - 0.4, PHY + 0.4, ENT + 0.55, ENT + 0.85, "stone")
    pediment("y", sgn, ENT + 0.85, PHX + 0.35, 0.05)
    pediment("x", sgn, ENT + 0.85, 2.9, 0.05)
    # dentils under the entablature cornice on the four faces
    for t in frange(-PHY - 0.1, PHY + 0.1, 0.24):
        box(min(sgn * PHX, sgn * (PHX + 0.3)), max(sgn * PHX, sgn * (PHX + 0.3)), t - 0.06, t + 0.06, ENT + 0.42, ENT + 0.55, "stone")
    # banded rustication: proud courses (0.45 m) with deep horizontal joints, stopped around the arch + voussoirs
    for (fa, half_w, crown_h, half_face) in (("x", 2.6, 6.2, PHY),):
        spring = PZ0 + crown_h - half_w; R2 = half_w + 1.0
        z = PZ0 + 0.05
        while z + 0.45 <= ENT:
            zc = z + 0.2
            cut = half_w if zc < spring else (math.sqrt(max(R2 * R2 - (zc - spring) ** 2, 0)) if zc - spring < R2 else 0.0)
            for e in (-1, 1):
                u0, u1 = sorted((e * max(cut, 0.0), e * half_face))
                if u1 - u0 < 0.2: continue
                if fa == "y":
                    box(u0, u1, min(sgn * PHY, sgn * (PHY + 0.09)), max(sgn * PHY, sgn * (PHY + 0.09)), z, z + 0.37, "rustic")
                else:
                    box(min(sgn * PHX, sgn * (PHX + 0.09)), max(sgn * PHX, sgn * (PHX + 0.09)), u0, u1, z, z + 0.37, "rustic")
            z += 0.45
    # stepped pilaster capitals at the arch imposts and voussoir ring + grotesque keystone heads
    for (fa, half_w, crown_h) in (("y", 2.05, 7.45), ("x", 2.6, 6.2)):
        spring = PZ0 + crown_h - half_w
        for e in (-1, 1):
            for k, (w, hh, dd) in enumerate(((0.75, 0.22, 0.24), (0.66, 0.18, 0.16), (0.58, 0.26, 0.10))):
                zc = spring - 0.1 - k * 0.2
                if fa == "y": c = Vector((e * (half_w + 0.45), sgn * (PHY + dd), zc))
                else: c = Vector((sgn * (PHX + dd), e * (half_w + 0.45), zc))
                obox(c, Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1)), w if fa == "y" else dd + 0.05, dd + 0.05 if fa == "y" else w, hh / 2, "stone_light")
        # rusticated voussoirs: alternating long/short wedges proud of the face
        nv = 15
        for i in range(nv):
            a0 = math.pi * i / nv; a1 = math.pi * (i + 1) / nv; am = (a0 + a1) / 2
            ro = half_w + (0.95 if i % 2 == 0 else 0.7)
            ri = half_w
            pts = [(math.cos(a0) * ri, math.sin(a0) * ri), (math.cos(a0) * ro, math.sin(a0) * ro), (math.cos(a1) * ro, math.sin(a1) * ro), (math.cos(a1) * ri, math.sin(a1) * ri)]
            pts = [(u * 0.985 + math.cos(am) * 0.0, z) for u, z in pts]
            if fa == "y":
                yf = sgn * (PHY + 0.1)
                V = [Vector((u, yf, spring + z)) for u, z in pts]
                face(V if sgn < 0 else V[::-1], "rustic")
                for a, b in zip(range(4), [1, 2, 3, 0]):
                    W = [V[a], V[b], Vector((V[b].x, sgn * PHY, V[b].z)), Vector((V[a].x, sgn * PHY, V[a].z))]
                    face(W if sgn > 0 else W[::-1], "rustic")
            else:
                xf = sgn * (PHX + 0.1)
                V = [Vector((xf, u, spring + z)) for u, z in pts]
                face(V[::-1] if sgn < 0 else V, "rustic")
                for a, b in zip(range(4), [1, 2, 3, 0]):
                    W = [V[a], V[b], Vector((sgn * PHX, V[b].y, V[b].z)), Vector((sgn * PHX, V[a].y, V[a].z))]
                    face(W[::-1] if sgn > 0 else W, "rustic")
        # grotesque head keystone
        if fa == "y": kc = Vector((0, sgn * (PHY + 0.3), spring + half_w + 0.45))
        else: kc = Vector((sgn * (PHX + 0.3), 0, spring + half_w + 0.45))
        mask(kc, fa, sgn)
        # iron tie rod across the arch at the impost
        if fa == "y": obox(Vector((0, sgn * (PHY - 0.4), spring + 0.2)), Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1)), half_w + 0.1, 0.03, 0.03, "iron")
        else: obox(Vector((sgn * (PHX - 0.4), 0, spring + 0.2)), Vector((0, 1, 0)), Vector((1, 0, 0)), Vector((0, 0, 1)), half_w + 0.1, 0.03, 0.03, "iron")
    # lanterns on brackets at the portico corners of the central passage
    for e in (-1, 1):
        lx, ly, lz = sgn * (PHX + 0.55), e * 3.25, CREST_Z + 4.3
        obox(Vector((sgn * (PHX + 0.3), ly, lz + 0.75)), Vector((1, 0, 0)), Vector((0, 1, 0)), Vector((0, 0, 1)), 0.3, 0.02, 0.02, "iron")
        lathe(lx, ly, lz, [(0.0, 0.0), (0.09, 0.02), (0.16, 0.18), (0.18, 0.45), (0.1, 0.5), (0.2, 0.55), (0.04, 0.72), (0.0, 0.74)], "iron", segs=6)
        lathe(lx, ly, lz + 0.04, [(0.13, 0.0), (0.17, 0.4)], "lampglass", segs=6)
# portico roof: cross-gabled lead
PR = PZ1 + 0.02
for (a, b, c, d) in (((-PHX - 0.4, -PHY - 0.4), (PHX + 0.4, -PHY - 0.4), (PHX + 0.4, PHY + 0.4), (-PHX - 0.4, PHY + 0.4)),):
    pass
hy = (PHX + 0.35) * 0.42; hx = 2.9 * 0.42
ridge = PR + max(hx, hy)
for sgn in (-1, 1):
    # cross-gabled lead roof: a ridge along y (over the canal pediments) and one along x (over the walkway pediments)
    P = [Vector((sgn * (PHX + 0.4), -PHY - 0.3, PR)), Vector((sgn * (PHX + 0.4), PHY + 0.3, PR)), Vector((0, PHY + 0.3, PR + hy)), Vector((0, -PHY - 0.3, PR + hy))]
    face(P if sgn > 0 else P[::-1], "lead")
    Q = [Vector((-PHX - 0.4, sgn * (PHY + 0.4), PR)), Vector((PHX + 0.4, sgn * (PHY + 0.4), PR)), Vector((PHX + 0.4, 0, PR + hy * 0.75)), Vector((-PHX - 0.4, 0, PR + hy * 0.75))]
    face(Q[::-1] if sgn > 0 else Q, "lead")

# ================================================================ 7. finish: object, cleanup, UVs, bake
me = bpy.data.meshes.new("rialto_main"); bm.normal_update(); bm.to_mesh(me); bm.free()
main = bpy.data.objects.new("Rialto", me); bpy.context.scene.collection.objects.link(main)
for m in MAT_ORDER: me.materials.append(MATS[m][0])
# join portico into main
bpy.ops.object.select_all(action="DESELECT")
main.select_set(True); por.select_set(True); bpy.context.view_layer.objects.active = main
bpy.ops.object.join()
ob = bpy.context.view_layer.objects.active
# consistent normals pointing outward where possible
bpy.ops.object.mode_set(mode="EDIT"); bpy.ops.mesh.select_all(action="SELECT")
bpy.ops.mesh.remove_doubles(threshold=0.002)
bpy.ops.uv.smart_project(angle_limit=math.radians(60), island_margin=0.0, area_weight=0.0, scale_to_bounds=False)
bpy.context.scene.tool_settings.use_uv_select_sync = True
bpy.ops.mesh.select_all(action="SELECT")
bpy.ops.uv.average_islands_scale()                 # uniform texel density across the whole bridge
r = bpy.ops.uv.pack_islands(rotate=True, scale=True, margin_method="FRACTION", margin=0.0003, shape_method="CONVEX"); print("pack", r)
bpy.ops.mesh.select_all(action="DESELECT")
bpy.ops.object.mode_set(mode="OBJECT")
tris = sum(len(p.vertices) - 2 for p in ob.data.polygons)
print("triangles", tris)

img = bpy.data.images.new("atlas", ATLAS_RES, ATLAS_RES, alpha=False)
for name, (m, layer) in MATS.items():
    n = m.node_tree.nodes.new("ShaderNodeTexImage"); n.image = img
    m.node_tree.nodes.active = n
sc = bpy.context.scene
sc.render.engine = "CYCLES"; sc.cycles.device = "CPU"; sc.cycles.samples = int(os.environ.get("BAKE_SAMPLES", "12"))
sc.render.bake.margin = 6
ob.select_set(True); bpy.context.view_layer.objects.active = ob
bpy.ops.object.bake(type="EMIT", margin=6)
img.filepath_raw = ATLAS; img.file_format = "PNG"; img.save()
# record which game layer each material uses for fine normal detail
ob["layers"] = [MATS[n][1] for n in MAT_ORDER]
bpy.ops.wm.save_as_mainfile(filepath=OUT)
print("Rialto v2 saved", OUT, ATLAS, "triangles", tris)

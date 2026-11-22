"""Palazzo Ducale + Ponte della Paglia + Ponte dei Sospiri + Prigioni Nuove (one landmark).

Local frame (landmarks.txt rot 16.0): origin = south-west corner of the palace (Molo / Piazzetta), +X east along the
Molo facade, +Y north along the Piazzetta facade, z = 0 mean water, paving at 1.1 m.  Footprint fitted to the Regione
Veneto building polygons: south wing 77 x 27.5 m, west wing 19 m deep to y = 77.5, east wing (Rio di Palazzo) 23.5 m
deep, courtyard x 19..53.5, Rio di Palazzo x 77..84.5, Prigioni Nuove x 84.5..120.

What makes it read as the Doge's Palace (from photos of the Molo and Piazzetta facades):
  * 18 + 18 stout pointed arches on squat columns with big capitals (no bases: the paving was raised)
  * the upper loggia: twice as many slender columns, ogee arches with cusps and a continuous row of pierced
    quatrefoil roundels above them
  * a massive flush upper wall in a white / pink lozenge pattern with few, large pointed windows (two on the Molo
    side set lower), the central balcony window with pinnacles and Justice on top
  * white crenellation of leaf-shaped merli with small spikes between, corner pinnacles, grey lead roofs
  * the white Renaissance facade on the rio, the enclosed Ponte dei Sospiri and the arched Ponte della Paglia"""
import bpy, bmesh, math, sys, os, random
from mathutils import Vector, Matrix

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
OUT = ARGS[0] if len(ARGS) > 0 else "/home/claude/venice/ducale.blend"
ATLAS = ARGS[1] if len(ARGS) > 1 else "/home/claude/venice/ducale_atlas.png"
ATLAS_RES = int(ARGS[2]) if len(ARGS) > 2 else 2048
random.seed(7)
bpy.ops.wm.read_factory_settings(use_empty=True)
ARCH_CZ = 0.0; R_ARCH = 1.0
HERE = os.path.dirname(os.path.abspath(__file__))
exec(open(os.path.join(HERE, "lmcommon.py")).read())
exec(open(os.path.join(HERE, "lmmulti.py")).read())

G = 1.1                      # paving
P_S = 77.0 / 17              # arcade pitch, Molo (17 ground / 34 loggia arches)
P_W = 77.0 / 18              # Piazzetta (18 / 36)
P = P_W
LEN_S = 77.0; LEN_W = 77.0
# heights
CAP0 = G + 2.95; ABA = G + 3.70            # portico shaft top / abacus top (arch springing)
WALL1 = G + 7.0; FLOOR2 = G + 7.38         # spandrel top / loggia floor
LCAP0 = G + 10.25; LABA = G + 10.67        # loggia shaft top / abacus top
TRAC1 = G + 13.65; CORN1 = G + 14.2        # tracery top / loggia cornice top
TOP = G + 24.4; TOPC = G + 24.85           # upper wall top / main cornice top

# ---------------------------------------------------------------- materials
def m_lozenge(nt, coord):
    """Upper wall: pale pink Verona marble lozenges framed by a white Istrian net, a small rose diamond in each cell."""
    base = N(nt, "ShaderNodeTexNoise", i_Scale=0.6, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.88, 0.65, 0.57, 1), (0.93, 0.72, 0.64, 1), base.outputs["Fac"])
    vec = uv_face(nt, coord, True)
    br = N(nt, "ShaderNodeTexBrick", offset=0.0, i_Scale=1.0, i_Mortar_Size=0.135, i_Mortar_Smooth=0.15, i_Brick_Width=1.3,
           i_Row_Height=1.3, i_Bias=0.0)
    br.inputs["Color1"].default_value = (1, 1, 1, 1); br.inputs["Color2"].default_value = (0.95, 0.93, 0.95, 1)
    br.inputs["Mortar"].default_value = (1, 1, 1, 1)
    L(nt, vec, br.inputs["Vector"])
    c = mix(nt, c, br.outputs["Color"], 1.0, "MULTIPLY")
    c = mix(nt, c, (0.93, 0.89, 0.84, 1), mathn(nt, "MULTIPLY", br.outputs["Fac"], 0.78))          # white net (Fac = mortar)
    br2 = N(nt, "ShaderNodeTexBrick", offset=0.0, i_Scale=1.0, i_Mortar_Size=0.78, i_Mortar_Smooth=0.1, i_Brick_Width=1.3, i_Row_Height=1.3, i_Bias=0.0)
    for k in ("Color1", "Color2", "Mortar"): br2.inputs[k].default_value = (1, 1, 1, 1)
    L(nt, vec, br2.inputs["Vector"])
    c = mix(nt, c, (0.93, 0.86, 0.80, 1), mathn(nt, "MULTIPLY", mathn(nt, "SUBTRACT", 1.0, br2.outputs["Fac"]), 0.45))
    return weather(nt, coord, c, streak=0.55, tide=False, ao_min=0.5, grime=0.15)

def m_glass(nt, coord):
    """Venetian leaded glazing: round bullseye panes (rui) in dark lead, a soft sky sheen toward the top"""
    vec = uv_face(nt, coord)
    vo = N(nt, "ShaderNodeTexVoronoi", feature="F1"); vo.inputs["Scale"].default_value = 7.0; L(nt, vec, vo.inputs["Vector"])
    pane = ramp_val(nt, vo.outputs["Distance"], 0.42, 0.30)
    base = N(nt, "ShaderNodeTexNoise", i_Scale=2.0, i_Detail=3.0); L(nt, coord, base.inputs["Vector"])
    gl = mix(nt, (0.22, 0.26, 0.27, 1), (0.30, 0.34, 0.35, 1), base.outputs["Fac"])
    sz = sep_z(nt, coord)
    wv = N(nt, "ShaderNodeTexWave", wave_type="BANDS", bands_direction="Z"); wv.inputs["Scale"].default_value = 0.3; L(nt, coord, wv.inputs["Vector"])
    gl = mix(nt, gl, (0.55, 0.62, 0.68, 1), mathn(nt, "MULTIPLY", mathn(nt, "ADD", ramp_val(nt, sz.outputs["Z"], G + 14.0, G + 24.0), ramp_val(nt, wv.outputs["Fac"], 0.55, 0.95)), 0.3))
    return mix(nt, (0.15, 0.15, 0.15, 1), gl, mathn(nt, "ADD", mathn(nt, "MULTIPLY", pane, 0.6), 0.4))

def m_ceiling(nt, coord):
    sz = sep_z(nt, coord)
    cv = N(nt, "ShaderNodeCombineXYZ"); L(nt, mathn(nt, "ADD", sz.outputs["X"], sz.outputs["Y"]), cv.inputs["X"])
    wv = N(nt, "ShaderNodeTexWave", wave_type="BANDS", bands_direction="X"); wv.inputs["Scale"].default_value = 1.1
    L(nt, cv.outputs[0], wv.inputs["Vector"])
    c = mix(nt, (0.15, 0.10, 0.07, 1), (0.27, 0.19, 0.12, 1), ramp_val(nt, wv.outputs["Fac"], 0.25, 0.55))
    return weather(nt, coord, c, streak=0.0, tide=False, ao_min=0.45, grime=0.05)

def m_back(nt, coord):
    """deep arcade interiors: worn plaster and stone in permanent shade"""
    base = N(nt, "ShaderNodeTexNoise", i_Scale=1.5, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.33, 0.31, 0.28, 1), (0.37, 0.35, 0.31, 1), base.outputs["Fac"])
    sz = sep_z(nt, coord)
    en = N(nt, "ShaderNodeTexNoise", i_Scale=2.0, i_Detail=3.0); L(nt, coord, en.inputs["Vector"])
    damp = ramp_val(nt, mathn(nt, "SUBTRACT", sz.outputs["Z"], mathn(nt, "MULTIPLY", en.outputs["Fac"], 0.4)), G + 1.2, G + 0.7)
    c = mix(nt, c, (0.20, 0.19, 0.16, 1), mathn(nt, "MULTIPLY", damp, 0.8))
    return weather(nt, coord, c, streak=0.7, tide=False, ao_min=0.45, grime=0.04)

def m_red(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=3.0, i_Detail=6.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.55, 0.27, 0.22, 1), (0.70, 0.40, 0.33, 1), base.outputs["Fac"])
    return weather(nt, coord, c, streak=0.1, tide=False, ao_min=0.55, grime=0.08)

def m_tile(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=0.8, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.56, 0.33, 0.24, 1), (0.68, 0.43, 0.31, 1), base.outputs["Fac"])
    wv = N(nt, "ShaderNodeTexWave", wave_type="BANDS", bands_direction="X"); wv.inputs["Scale"].default_value = 3.0
    L(nt, coord, wv.inputs["Vector"])
    c = mix(nt, c, (0.36, 0.22, 0.17, 1), mathn(nt, "MULTIPLY", ramp_val(nt, wv.outputs["Fac"], 0.7, 1.0), 0.5))
    lic = N(nt, "ShaderNodeTexNoise", i_Scale=2.5, i_Detail=5.0); L(nt, coord, lic.inputs["Vector"])
    c = mix(nt, c, (0.45, 0.42, 0.36, 1), mathn(nt, "MULTIPLY", ramp_val(nt, lic.outputs["Fac"], 0.6, 0.75), 0.45))
    return weather(nt, coord, c, streak=0.0, tide=False, ao_min=0.55, grime=0.1)

def m_court(nt, coord):
    """courtyard: brick upper storeys, white stone lower storeys"""
    base = N(nt, "ShaderNodeTexNoise", i_Scale=0.4, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
    brick = mix(nt, (0.58, 0.36, 0.28, 1), (0.66, 0.43, 0.33, 1), base.outputs["Fac"])
    sz = sep_z(nt, coord)
    c = mix(nt, brick, (0.82, 0.79, 0.73, 1), ramp_val(nt, sz.outputs["Z"], G + 14.6, G + 14.2))
    return weather(nt, coord, c, streak=0.3, tide=False, ao_min=0.45, grime=0.2)

def m_gold(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=4.0, i_Detail=3.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.45, 0.30, 0.10, 1), (0.58, 0.41, 0.15, 1), base.outputs["Fac"])
    g = N(nt, "ShaderNodeNewGeometry"); sn = N(nt, "ShaderNodeSeparateXYZ"); L(nt, g.outputs["Normal"], sn.inputs[0])
    return mix(nt, c, (0.98, 0.84, 0.45, 1), ramp_val(nt, sn.outputs["Z"], 0.2, 0.9))

node_mat("stone", lambda nt, c: m_istrian_plain(nt, c, 1.05, 0.45, 0.42), 15)
node_mat("column", lambda nt, c: weather(nt, c, stone_base(nt, c, 0.97), streak=0.3, tide=False, ao_min=0.45, grime=0.5), 15)
node_mat("lozenge", m_lozenge, 15)
node_mat("ashlar", lambda nt, c: weather(nt, c, mix(nt, joints(nt, stone_base(nt, c, 1.14), uv_face(nt, c), 1.1, 0.42, 0.5, mortar=0.008, jcol=(0.6, 0.58, 0.55), var=0.06), (1.0, 0.93, 0.83, 1), 1.0, "MULTIPLY"), streak=0.3, tide=True, ao_min=0.5, grime=0.12), 17)
node_mat("rustic", m_rustic, 17)
node_mat("glass", m_glass, 33)
node_mat("dark", m_flat((0.035, 0.03, 0.028), 0.2, 0.8), 33)
node_mat("ceiling", m_ceiling, 26)
node_mat("back", m_back, 0)
node_mat("floor", m_trachyte, 25)
node_mat("red", m_red, 15)
node_mat("sculpt", lambda nt, c: mix(nt, m_istrian_plain(nt, c, 0.95, 0.7, 0.4), (0.90, 0.85, 0.75, 1), 1.0, "MULTIPLY"), 15)
node_mat("lead", m_lead, 31)
node_mat("tile", m_tile, 18)
node_mat("court", m_court, 11)
node_mat("iron", m_flat((0.05, 0.05, 0.05), 0.2, 0.6), 33)
node_mat("bronze", m_flat((0.22, 0.17, 0.10), 0.15, 0.6), 33)
node_mat("gold", m_gold, 33)
node_mat("steps", m_trachyte, 25)
MAT_ORDER = list(MATS.keys())

def slot_matrix(F):
    return Matrix.Translation(F.o) @ Matrix.Rotation(math.atan2(F.a.y, F.a.x), 4, "Z")

# ---------------------------------------------------------------- arcade bay module
WALL_T = 0.75
def bay(F, P):
    """one arcade bay of the two lower orders, u in [0, P]: portico column at u = 0, loggia columns at 0 and P/2"""
    # portico column, capital, abacus
    column(F, 0.0, WALL_T / 2, G - 0.3, CAP0, 0.57, "column", cap=(ABA - CAP0 - 0.18, 0.88, 0.76, 0.18), segs=10)
    for k in range(6):                                     # leafy capital: crockets curling out under the abacus
        a = 2 * math.pi * (k + 0.5) / 6; cc = F.P(0.0, WALL_T / 2, 0)
        blob(Vector((cc.x + 0.74 * math.cos(a), cc.y + 0.74 * math.sin(a), ABA - 0.38)), 0.19, 0.19, 0.16, "column", 5, 3)
    # spandrel wall with the pointed arch cut out
    w = P / 2 - 0.66; h = 2.95
    head = pointed(P / 2, w, ABA + 0.02, h, 10)
    carve(lambda: fbox(F, 0.0, P, 0.0, WALL_T, ABA - 0.02, WALL1, "stone"),
          lambda: prism(F, opening(head, ABA - 1.0), -1.0, WALL_T + 1.0, "stone"))
    band(F, head, 0.26, 0.0, "stone", proud=0.07)                                              # archivolt
    band(F, [(x, z + 0.30) for x, z in head], 0.10, 0.0, "stone", proud=0.04)                  # hood line
    fbox(F, 0.0, P, -0.14, WALL_T, WALL1, FLOOR2, "stone")                                     # string course
    fbox(F, 0.0, P, -0.20, -0.02, WALL1 + 0.12, WALL1 + 0.24, "stone")
    # loggia parapet: plinth, colonnettes, rail
    fbox(F, 0.0, P, 0.05, 0.45, FLOOR2, FLOOR2 + 0.14, "stone")
    for s in (0, 1):
        u0 = s * P / 2 + 0.22; u1 = (s + 1) * P / 2 - 0.22
        for k in range(5):
            uu = u0 + (u1 - u0) * (k + 0.5) / 5
            fbox(F, uu - 0.06, uu + 0.06, 0.19, 0.31, FLOOR2 + 0.14, FLOOR2 + 0.94, "stone")
    fbox(F, 0.0, P, -0.02, 0.50, FLOOR2 + 0.94, FLOOR2 + 1.08, "stone")
    # loggia columns
    for uc in (0.0, P / 2):
        column(F, uc, 0.25, FLOOR2, LCAP0, 0.17, "column", cap=(LABA - LCAP0 - 0.1, 0.29, 0.27, 0.1), segs=8)
    # tracery panel: two cusped ogee arches, pierced quatrefoils above the columns
    heads = [ogee(uc, P / 4 - 0.22, LABA, 1.45, 8, cusp=0.09) for uc in (P / 4, 3 * P / 4)]
    def cutters():
        for hd in heads: prism(F, opening(hd, LABA - 0.8), -1.0, 1.5, "stone")
        for uc in (0.0, P / 2, P): prism(F, foil(uc, G + 12.85, 0.6, 4, 28, 0.34, math.pi / 4), -1.0, 1.5, "stone")
    carve(lambda: fbox(F, 0.0, P, 0.05, 0.45, LABA - 0.02, TRAC1, "stone"), cutters)
    for hd in heads: band(F, hd, 0.12, 0.05, "stone", proud=0.05)
    for uc in (0.0, P / 2):
        ring = circle(uc, G + 12.85, 0.66, 20); ring = ring + [ring[0]]
        band(F, ring, 0.11, 0.05, "stone", proud=0.04)
    # loggia cornice
    fbox(F, 0.0, P, -0.28, 0.45, TRAC1, TRAC1 + 0.30, "stone")
    fbox(F, 0.0, P, -0.16, 0.45, TRAC1 + 0.30, CORN1, "stone")
    for k in range(6):                                                                             # dentils
        uu = (k + 0.5) * P / 6
        fbox(F, uu - 0.1, uu + 0.1, -0.36, -0.28, TRAC1 + 0.02, TRAC1 + 0.2, "stone")

S_SLOTS = [Frame((i * P_S, 0, 0), (1, 0, 0), (0, 1, 0)) for i in range(17)]
W_SLOTS = [Frame((0, LEN_W - i * P_W, 0), (0, -1, 0), (1, 0, 0)) for i in range(18)]
MASTERS = [(S_SLOTS[4], P_S), (S_SLOTS[11], P_S), (W_SLOTS[5], P_W), (W_SLOTS[13], P_W)]
masters = []
for k, (F, pp) in enumerate(MASTERS):
    new_part(); bay(F, pp); masters.append(end_part("bay%d" % k, weight=5.5))
for i, F in enumerate(S_SLOTS + W_SLOTS):
    if any(F is m for m, _ in MASTERS): continue
    k = (i % 2) if i < 17 else 2 + (i % 2)
    M = slot_matrix(F) @ slot_matrix(MASTERS[k][0]).inverted()
    o2 = bpy.data.objects.new("bay_i%d" % i, masters[k].data); bpy.context.scene.collection.objects.link(o2)
    o2.matrix_world = M; o2["layers"] = masters[k]["layers"]; INSTANCES.append(o2)

# ---------------------------------------------------------------- main body (unique)
new_part()
FS = Frame((0, 0, 0), (1, 0, 0), (0, 1, 0))
FW = Frame((0, LEN_W, 0), (0, -1, 0), (1, 0, 0))
FE = Frame((LEN_S, 0, 0), (0, 1, 0), (-1, 0, 0))
# corner and end columns (both orders), corner sculpture groups
def figure(c, h, mat="stone", k=1.0):
    lathe(c.x, c.y, c.z, [(0.15 * h * k, 0.0), (0.13 * h * k, 0.25 * h), (0.115 * h * k, 0.5 * h), (0.14 * h * k, 0.72 * h), (0.06 * h * k, 0.82 * h),
                           (0.075 * h, 0.9 * h), (0.0, h)], mat, 7)
for (cx, cy) in ((WALL_T / 2, WALL_T / 2), (LEN_S - WALL_T / 2, WALL_T / 2)):
    lathe(cx, cy, G - 0.3, [(0.58, 0.0), (0.56, 0.15), (0.54, CAP0 - G + 0.3)], "column", 12)
    lathe(cx, cy, CAP0, [(0.54, 0.0), (0.74, 0.3), (0.86, ABA - CAP0 - 0.2), (0.86, ABA - CAP0)], "sculpt", 12)
    lathe(cx, cy, FLOOR2, [(0.24, 0.0), (0.23, LCAP0 - FLOOR2)], "column", 10)
    lathe(cx, cy, LCAP0, [(0.23, 0.0), (0.36, LABA - LCAP0)], "sculpt", 10)
    sx = -1 if cx < 10 else 1
    # corner group on the capital: two figures and a tree between them (Adam and Eve / Drunkenness of Noah)
    o = Vector((sx * 0.55, -0.55, 0))
    ex = Vector((cx + sx * WALL_T / 2, cy - WALL_T / 2, ABA))                   # the outer corner edge at the capital top
    figure(ex + Vector((-sx * 0.05, -0.62, 0)), 1.95, k=1.5)                   # one figure on each face, leaning into the corner
    figure(ex + Vector((sx * 0.62, 0.05, 0)), 1.9, k=1.5)
    tube([ex + Vector((sx * 0.12, -0.12, 0)), ex + Vector((sx * 0.16, -0.16, 1.5)), ex + Vector((sx * 0.1, -0.1, 2.3))], 0.15, "stone", 6)
    for (du, dz, rr) in ((0.0, 2.45, 0.75), (0.75, 2.2, 0.55), (1.35, 2.05, 0.42), (0.45, 2.75, 0.5)):   # foliage relief on both faces
        blob(ex + Vector((sx * 0.06, -du - 0.06, dz)), 0.18, rr, rr * 0.7, "stone", 7, 5)
        blob(ex + Vector((sx * du + sx * 0.06, -0.06, dz)), rr, 0.18, rr * 0.7, "stone", 7, 5)
    # archangel at the loggia corner: figure + two wings
    oc = Vector((cx, cy, LABA)) + o * 0.6
    figure(oc, 2.1)
    for e in (-1, 1):
        wd = Vector((e * 0.7071, e * 0.7071 * (-sx), 0)) if False else Vector((0.0, 0.0, 0.0))
    wax = Vector((sx * 0.7071, 0.7071, 0))                                 # wing plane runs along the corner diagonal
    for e in (-1, 1):
        base_ = oc + Vector((0, 0, 1.45)) - o * 0.3
        Fw = Frame(base_, wax * e, Vector((-(wax * e).y, (wax * e).x, 0)))
        prism(Fw, [(0.1, -0.3), (0.5, -0.1), (0.95, 0.35), (1.15, 0.95), (0.9, 0.8), (0.55, 0.55), (0.2, 0.25)], -0.04, 0.04, "stone")
    # twisted rope column up the corner of the upper wall
    zz = CORN1; k = 0
    while zz < TOP - 0.05:
        r = 0.25 if k % 2 == 0 else 0.19
        lathe(cx + sx * (WALL_T / 2 - 0.08), cy - WALL_T / 2 + 0.08, zz, [(r, 0.0), (r, 0.33)], "stone", 8); zz += 0.33; k += 1
column(FW, 0.0, WALL_T / 2, G - 0.3, CAP0, 0.57, "column", cap=(ABA - CAP0 - 0.18, 0.88, 0.76, 0.18))
column(FW, 0.0, 0.25, FLOOR2, LCAP0, 0.17, "column", cap=(LABA - LCAP0 - 0.1, 0.29, 0.27, 0.1))
# the two red marble columns of the Piazzetta loggia (9th and 10th from the Molo)
for k in (9, 10):
    u = LEN_W - k * P / 2
    column(FW, u, 0.25, FLOOR2 - 0.01, LCAP0 + 0.01, 0.19, "red", segs=8)

# portico and loggia interiors: ceilings, back walls with doors, loggia floors
for F, L0, L1 in ((FS, 0.0, LEN_S), (FW, 0.0, LEN_W - 5.2)):
    PD = 5.2; LD = 4.6
    oface([F.P(L0, WALL_T, WALL1 - 0.25), F.P(L1, WALL_T, WALL1 - 0.25), F.P(L1, PD, WALL1 - 0.25), F.P(L0, PD, WALL1 - 0.25)], Vector((0, 0, -1)), "ceiling")
    flat(F, [(L0, G - 0.3), (L1, G - 0.3), (L1, WALL1 - 0.25), (L0, WALL1 - 0.25)], PD, "back")
    oface([F.P(L0, 0.45, FLOOR2), F.P(L1, 0.45, FLOOR2), F.P(L1, LD, FLOOR2), F.P(L0, LD, FLOOR2)], Vector((0, 0, 1)), "floor")
    oface([F.P(L0, 0.45, TRAC1 - 0.2), F.P(L1, 0.45, TRAC1 - 0.2), F.P(L1, LD, TRAC1 - 0.2), F.P(L0, LD, TRAC1 - 0.2)], Vector((0, 0, -1)), "ceiling")
    flat(F, [(L0, FLOOR2), (L1, FLOOR2), (L1, TRAC1 - 0.2), (L0, TRAC1 - 0.2)], LD, "back")
    bu = L0 + 0.5
    while bu < L1:
        oface([F.P(bu - 0.1, WALL_T, WALL1 - 0.45), F.P(bu + 0.1, WALL_T, WALL1 - 0.45), F.P(bu + 0.1, PD, WALL1 - 0.45), F.P(bu - 0.1, PD, WALL1 - 0.45)], Vector((0, 0, -1)), "ceiling")
        for e in (-1, 1):
            oface([F.P(bu + e * 0.1, WALL_T, WALL1 - 0.45), F.P(bu + e * 0.1, PD, WALL1 - 0.45), F.P(bu + e * 0.1, PD, WALL1 - 0.25), F.P(bu + e * 0.1, WALL_T, WALL1 - 0.25)], F.a * e, "ceiling")
        bu += 1.1
    u = L0 + 5.0
    while u < L1 - 2.0:
        flat(F, [(u - 0.8, G), (u + 0.8, G), (u + 0.8, G + 3.4), (u - 0.8, G + 3.4)], PD - 0.02, "dark")
        flat(F, [(u - 0.6, FLOOR2), (u + 0.6, FLOOR2), (u + 0.6, FLOOR2 + 3.0), (u - 0.6, FLOOR2 + 3.0)], LD - 0.02, "dark")
        u += 3 * P + 1.3
# end walls of the arcades
fbox(FS, LEN_S - 0.6, LEN_S, 0.0, 5.2, G - 0.3, CORN1, "stone")
fbox(FW, 0.0, 0.6, 0.0, 5.2, G - 0.3, CORN1, "stone")

# ---- upper wall with the big windows (Molo and Piazzetta)
def window(F, uc, sill, spring, rise, w=1.45, balc=True):
    head = pointed(uc, w, spring, rise, 10)
    band(F, head + [], 0.30, 0.0, "stone", proud=0.09)
    fbox(F, uc - w - 0.30, uc - w, -0.09, 0.0, sill, spring, "stone"); fbox(F, uc + w, uc + w + 0.30, -0.09, 0.0, sill, spring, "stone")
    soffit(F, opening(head, sill), 0.0, 0.62, "stone")
    fbox(F, uc - 0.1, uc + 0.1, 0.30, 0.72, sill, spring + rise * 0.45, "stone")                  # central colonnette
    fbox(F, uc - w - 0.45, uc + w + 0.45, -0.42 if balc else -0.15, 0.1, sill - 0.22, sill, "stone")   # sill / little balcony
    if balc:
        for k in range(7):
            uu = uc - w - 0.2 + (2 * w + 0.4) * (k + 0.5) / 7
            fbox(F, uu - 0.05, uu + 0.05, -0.36, -0.26, sill, sill + 0.75, "stone")
        fbox(F, uc - w - 0.45, uc + w + 0.45, -0.42, -0.2, sill + 0.75, sill + 0.86, "stone")
    return opening(head, sill)

def balcony(F, uc):
    """the central balcony window (Molo: Dalle Masegne 1404 / Piazzetta 1536): it rises from the loggia cornice past
    the crenellation: projecting balcony on corbels, a tall carved frame between pinnacled piers with statues in three
    tiers of niches, a crocketed canopy and Justice enthroned on top above the roofline"""
    z0 = CORN1 + 0.4
    fbox(F, uc - 3.3, uc + 3.3, -1.6, 0.0, z0, z0 + 0.4, "stone")
    for k in range(6):
        uu = uc - 2.9 + 5.8 * k / 5
        prism(F, [(uu - 0.18, z0 - 1.2), (uu + 0.18, z0 - 1.2), (uu + 0.18, z0), (uu - 0.18, z0)], -1.3, 0.0, "stone")
    for k in range(16):
        uu = uc - 3.1 + 6.2 * (k + 0.5) / 16
        fbox(F, uu - 0.06, uu + 0.06, -1.52, -1.4, z0 + 0.4, z0 + 1.3, "stone")
    for sd in (-1, 1):
        for k in range(4):
            dd = -1.5 + 1.3 * (k + 0.5) / 4
            fbox(F, uc + sd * 3.2 - 0.06, uc + sd * 3.2 + 0.06, dd - 0.06, dd + 0.06, z0 + 0.4, z0 + 1.3, "stone")
    fbox(F, uc - 3.3, uc + 3.3, -1.6, -1.35, z0 + 1.3, z0 + 1.45, "stone")
    head = pointed(uc, 1.75, G + 20.2, 2.8, 12)
    band(F, head, 0.4, 0.0, "stone", proud=0.22)
    soffit(F, opening(head, z0 + 0.4), 0.0, 0.72, "stone")
    for sd in (-1, 1):
        fbox(F, uc + sd * 1.75 - 0.2, uc + sd * 1.75 + 0.2, -0.22, 0.0, z0 + 0.4, G + 20.2, "stone")
    for sd in (-1, 1):                                                                               # pinnacled piers
        pu = uc + sd * 2.75
        fbox(F, pu - 0.55, pu + 0.55, -0.95, 0.0, z0 + 0.4, TOPC + 2.4, "stone")
        for zz in (z0 + 1.6, G + 19.8, TOP - 1.4):
            flat(F, [(pu - 0.32, zz), (pu + 0.32, zz), (pu + 0.32, zz + 2.3), (pu, zz + 2.7), (pu - 0.32, zz + 2.3)], -0.96, "dark")
            figure(F.P(pu, -1.12, zz), 2.0)
        c = F.P(pu, -0.47, 0)
        lathe(c.x, c.y, TOPC + 2.4, [(0.55, 0.0), (0.38, 0.4), (0.06, 3.4)], "stone", 6)
        for k in range(4): blob(F.P(pu, -0.6, TOPC + 2.9 + k * 0.65), 0.18, 0.18, 0.12, "stone", 5, 3)
    hd2 = ogee(uc, 2.25, G + 22.6, 3.2, 10)                                                         # crocketed canopy
    prism(F, opening(hd2, G + 22.4), -0.55, 0.0, "stone")
    band(F, hd2, 0.2, -0.55, "stone", proud=0.12)
    for k in range(1, 10, 2):
        for idx in (k, len(hd2) - 1 - k):
            x, z = hd2[idx]; blob(F.P(x, -0.4, z + 0.22), 0.17, 0.17, 0.2, "stone", 5, 3)
    flat(F, [(uc - 0.45, G + 22.9), (uc + 0.45, G + 22.9), (uc + 0.45, G + 24.6), (uc, G + 25.0), (uc - 0.45, G + 24.6)], -0.56, "dark")
    figure(F.P(uc, -0.75, G + 22.9), 1.8)
    # Justice enthroned on a pedestal above the roofline
    c = F.P(uc, -0.2, 0)
    lathe(c.x, c.y, G + 25.8, [(0.9, 0.0), (0.9, TOPC - G - 25.8 + 1.6), (0.7, TOPC - G - 25.8 + 1.75)], "stone", 8)
    zj = TOPC + 1.75
    lathe(c.x, c.y, zj, [(0.6, 0.0), (0.55, 0.8), (0.34, 1.45), (0.4, 1.75), (0.14, 1.9), (0.17, 2.15), (0.0, 2.3)], "sculpt", 8)
    tube([F.P(uc + 0.35, -0.3, zj + 1.7), F.P(uc + 0.6, -0.5, zj + 2.6), F.P(uc + 0.62, -0.55, zj + 3.3)], 0.06, "sculpt", 5)
    blob(F.P(uc - 0.55, -0.4, zj + 1.9), 0.28, 0.12, 0.2, "sculpt", 5, 3)
    return opening(head, z0 + 0.4)

S_WIN = [(8.5, G + 15.8, G + 19.6, 2.1, True), (20.0, G + 15.8, G + 19.6, 2.1, True), (52.5, G + 15.8, G + 19.6, 2.1, True),
         (61.5, G + 15.8, G + 19.6, 2.1, True), (67.6, G + 14.85, G + 17.9, 1.9, False), (73.2, G + 14.85, G + 17.9, 1.9, False)]
W_WIN = [(9.0, G + 15.8, G + 19.6, 2.1, True), (20.0, G + 15.8, G + 19.6, 2.1, True), (29.5, G + 15.8, G + 19.6, 2.1, True),
         (48.5, G + 15.8, G + 19.6, 2.1, True), (58.0, G + 15.8, G + 19.6, 2.1, True), (67.5, G + 15.8, G + 19.6, 2.1, True)]
for F, wins, L1, bal_u, oculi in ((FS, S_WIN, LEN_S, 38.5, (67.6, 73.2)), (FW, W_WIN, LEN_W, 38.5, ())):
    outlines = [window(F, wi[0], wi[1], wi[2], wi[3], balc=wi[4]) for wi in wins]
    outlines.append(balcony(F, bal_u))
    for ou in oculi:
        band(F, circle(ou, G + 21.9, 0.62, 16) + [circle(ou, G + 21.9, 0.62, 16)[0]], 0.22, 0.0, "stone", proud=0.08)
        outlines.append(circle(ou, G + 21.9, 0.6, 16))
    def cut(F=F, outlines=outlines):
        for o in outlines: prism(F, o, -1.0, 0.75, "glass")
    carve(lambda F=F, L1=L1: fbox(F, 0.0, L1, 0.0, 0.9, CORN1, TOP, "lozenge"), cut)
    # main cornice, crenellation
    fbox(F, -0.1, L1 + 0.1, -0.30, 0.7, TOP, TOP + 0.22, "stone")
    fbox(F, -0.1, L1 + 0.1, -0.42, 0.7, TOP + 0.22, TOPC, "stone")
    for k in range(int(L1 / 0.75)):
        uu = (k + 0.5) * L1 / int(L1 / 0.75)
        fbox(F, uu - 0.12, uu + 0.12, -0.36, -0.30, TOP - 0.28, TOP, "stone")          # little corbels under the cornice
    n = int(L1 / 1.55)
    for k in range(n):
        uu = (k + 0.5) * L1 / n
        if abs(uu - bal_u) < 3.5: continue
        prism(F, [(uu - 0.42, TOPC), (uu + 0.42, TOPC), (uu + 0.42, TOPC + 0.72), (uu + 0.26, TOPC + 0.78), (uu + 0.26, TOPC + 1.1),
                  (uu + 0.12, TOPC + 1.14), (uu, TOPC + 1.62), (uu - 0.12, TOPC + 1.14), (uu - 0.26, TOPC + 1.1), (uu - 0.26, TOPC + 0.78),
                  (uu - 0.42, TOPC + 0.72)], 0.02, 0.30, "stone")
        if k < n - 1:
            um = (k + 1) * L1 / n
            prism(F, [(um - 0.09, TOPC), (um + 0.09, TOPC), (um, TOPC + 0.95)], 0.08, 0.24, "stone")
# corner pinnacles on the roofline
for (cx, cy) in ((0.35, 0.35), (LEN_S - 0.35, 0.35)):
    lathe(cx, cy, TOPC, [(0.55, 0.0), (0.55, 0.6), (0.42, 0.7), (0.42, 2.0), (0.5, 2.15), (0.08, 4.2)], "stone", 8)
    blob(Vector((cx, cy, TOPC + 2.45)), 0.22, 0.22, 0.45, "sculpt", 6, 4)

# ---- east facade on the Rio di Palazzo (Renaissance, white ashlar)
RIO_L = 80.0
def east_cut():
    for zs, ze, w, top in ((G + 0.9, G + 2.5, 0.55, "flat"), (G + 5.6, G + 8.3, 0.62, "round"), (G + 10.8, G + 13.6, 0.62, "round"),
                           (G + 16.3, G + 19.4, 0.62, "round"), (G + 21.3, G + 22.9, 0.45, "flat")):
        u = 2.4
        while u < RIO_L - 1.5:
            if not (zs > G + 4 and zs < G + 9 and abs(u - 12.0) < 2.8):
                o = [(u - w, zs), (u + w, zs), (u + w, ze), (u - w, ze)] if top == "flat" else opening(rounded(u, w, ze - w, 8), zs)
                prism(FE, o, -1.0, 0.55, "glass")
            u += 3.55
carve(lambda: fbox(FE, 0.0, RIO_L, 0.0, 0.8, -0.6, TOP, "ashlar"), east_cut)
for zb in (G + 4.6, G + 9.8, G + 15.3, G + 20.5):
    fbox(FE, 0.0, RIO_L, -0.2, 0.8, zb, zb + 0.3, "stone")
fbox(FE, 0.0, RIO_L, -0.5, 0.8, TOP, TOP + 0.5, "stone")
u = 2.4
while u < RIO_L - 1.5:                                                    # framed windows, pediments, pilasters between bays
    for zs, ze, w in ((G + 5.6, G + 8.3, 0.62), (G + 10.8, G + 13.6, 0.62), (G + 16.3, G + 19.4, 0.62)):
        if not (zs < G + 9 and abs(u - 12.0) < 2.8):
            band(FE, opening(rounded(u, w, ze - w, 8), zs)[1:] + [(u - w, zs)], 0.2, 0.0, "stone", proud=0.1)
            fbox(FE, u - 0.95, u + 0.95, -0.3, 0.0, ze + 0.08, ze + 0.32, "stone")
            prism(FE, [(u - 0.9, ze + 0.32), (u + 0.9, ze + 0.32), (u, ze + 0.85)], -0.24, 0.0, "stone")
            fbox(FE, u - 0.8, u + 0.8, -0.25, 0.0, zs - 0.18, zs, "stone")
    if u + 1.78 < RIO_L - 1:
        for z0_, z1_ in ((G + 4.9, G + 9.8), (G + 10.1, G + 15.3), (G + 15.6, G + 20.5)):
            fbox(FE, u + 1.78 - 0.28, u + 1.78 + 0.28, -0.16, 0.0, z0_, z1_, "stone")
    u += 3.55
# north ends and courtyard walls
flat(Frame((LEN_S, RIO_L, 0), (-1, 0, 0), (0, -1, 0)), [(0, -0.5), (LEN_S - 53.5, -0.5), (LEN_S - 53.5, TOP), (0, TOP)], 0.0, "court")
flat(Frame((19.0, LEN_W + 1.3, 0), (-1, 0, 0), (0, -1, 0)), [(0, G - 0.3), (19.0, G - 0.3), (19.0, TOP), (0, TOP)], 0.0, "court")
COURT = [Frame((53.5, 27.5, 0), (-1, 0, 0), (0, -1, 0)), Frame((53.5, 76.0, 0), (0, -1, 0), (1, 0, 0)), Frame((19.0, 27.5, 0), (0, 1, 0), (-1, 0, 0))]
COURT_L = [34.5, 52.5, 48.5]
for F, Lc in zip(COURT, COURT_L):
    flat(F, [(0, G - 0.3), (Lc, G - 0.3), (Lc, TOP), (0, TOP)], 0.0, "court")
    k = 0; u = 2.0
    while u < Lc - 1.5:
        flat(F, opening(rounded(u, 1.4, G + 4.2), G), -0.02, "dark")                                  # courtyard portico
        flat(F, opening(pointed(u, 0.75, G + 9.8, 0.9, 6), G + 7.6), -0.02, "dark")
        for zz in (G + 15.5, G + 20.0):
            flat(F, [(u - 0.55, zz), (u + 0.55, zz), (u + 0.55, zz + 2.4), (u - 0.55, zz + 2.4)], -0.02, "dark")
        u += 4.2
    fbox(F, 0, Lc, -0.3, 0.0, TOP, TOP + 0.4, "stone")
flat(Frame((19.0, 76.0, 0), (1, 0, 0), (0, 1, 0)), [(0, G - 0.3), (34.5, G - 0.3), (34.5, G + 14.0), (0, G + 14.0)], 0.0, "court")
# Scala dei Giganti and the two bronze wellheads
box(50.5, 53.5, 66.9, 72.1, G, G + 7.6, "stone")
prism(Frame((45.0, 66.9, 0), (1, 0, 0), (0, 1, 0)), [(0.0, G), (5.5, G), (5.5, G + 7.6)], 0.0, 5.2, "steps")
for yy in (67.4, 71.6):
    lathe(52.6, yy, G + 7.6, [(0.4, 0.0), (0.4, 0.6)], "stone", 8)
    blob(Vector((52.6, yy, G + 9.4)), 0.32, 0.32, 1.25, "sculpt", 8, 6)
for (wx, wy) in ((31.0, 50.0), (43.0, 50.0)):
    lathe(wx, wy, G, [(1.0, 0.0), (1.05, 0.3), (0.9, 0.6), (1.0, 1.05), (1.08, 1.15)], "bronze", 12)

# ---- roofs (lead) behind the crenellation
R0 = TOPC - 0.05
hip_roof(0.45, LEN_S - 0.45, 0.45, 27.5, R0, 24, "lead", 0.0)
hip_roof(0.45, 19.0, 27.5, LEN_W + 1.3, R0, 24, "lead", 0.0)
hip_roof(53.5, LEN_S - 0.4, 27.5, RIO_L, R0, 24, "lead", 0.0)
for (cx, cy) in ((30, 14), (47, 14), (9, 45), (66, 46)):
    fbox(FS, cx - 0.5, cx + 0.5, cy - 0.5, cy + 0.5, R0 + 2.0, R0 + 5.5, "back")
body = end_part("body", weight=1.2)

# ---------------------------------------------------------------- bridges + Prigioni
new_part()
# Ponte dei Sospiri: enclosed white Istrian bridge across the rio (y 5.9 .. 9.7), clearly above the water
BY0 = 5.9; BD = 3.8
BS = Frame((LEN_S, BY0, 0), (1, 0, 0), (0, 1, 0)); BN = Frame((84.5, BY0 + BD, 0), (-1, 0, 0), (0, -1, 0))
SPAN = 84.5 - LEN_S
ZS, ZC, ZT, ZA = 3.4, 5.6, 10.1, 12.0          # springing, soffit crown, top of the body, crown of the top arc
under = [(SPAN / 2 + (SPAN / 2) * math.cos(math.pi * i / 12), ZS + (ZC - ZS) * math.sin(math.pi * i / 12)) for i in range(13)]
topa = [(SPAN / 2 + (SPAN / 2 - 0.9) * math.cos(math.pi * i / 12), ZT + (ZA - ZT) * math.sin(math.pi * i / 12)) for i in range(13)]
prof = [(-0.3, ZS - 0.3)] + under[::-1] + [(SPAN + 0.3, ZS - 0.3), (SPAN + 0.3, ZT)] + topa + [(-0.3, ZT)]
WINS = (0.95, SPAN - 2.75)
def bs_cut():
    for u0 in WINS:
        r = [(u0, 6.9), (u0 + 1.8, 6.9), (u0 + 1.8, 8.7), (u0, 8.7)]
        prism(BS, r, -1.0, 0.4, "dark"); prism(BS, r, BD - 0.4, BD + 1.0, "dark")
carve(lambda: prism(BS, prof, 0.0, BD, "stone"), bs_cut)
for F in (BS, BN):
    for uc in (0.35, SPAN - 0.35):                                           # rusticated end pilasters
        for k in range(8):
            z0_ = ZS - 0.9 + k * (ZT - ZS + 0.9) / 8
            fbox(F, uc - 0.4, uc + 0.4, -0.22 - 0.06 * (k % 2), 0.0, z0_ + 0.04, z0_ + (ZT - ZS + 0.9) / 8 - 0.04, "stone")
    band(F, under, 0.7, 0.0, "rustic", proud=0.1)                           # rusticated arch: alternating voussoirs
    for k in range(12):
        if k % 2 == 0: band(F, under[k:k + 2], 0.7, -0.1, "rustic", proud=0.08)
    band(F, [(x, z + 0.7) for x, z in under], 0.14, 0.0, "stone", proud=0.2)
    for k in range(11):                                                         # eleven carved heads on the arch
        x, z = under[1 + k]
        blob(F.P(x, -0.32, z + 0.36), 0.24, 0.18, 0.28, "stone", 7, 5)
    for sd in (-1, 1):                                                          # reclining figures in the spandrels
        blob(F.P(SPAN / 2 + sd * 2.4, -0.12, ZC + 0.55), 0.75, 0.15, 0.24, "sculpt", 8, 4)
    fbox(F, 0.0, SPAN, -0.12, 0.0, ZC + 1.05, ZC + 1.3, "stone")
    for u0 in (0.95, SPAN - 2.75):                                              # two windows with stone openwork grilles
        for (fu, fz) in ((0.45, 7.35), (1.35, 7.35), (0.45, 8.25), (1.35, 8.25)):      # four eight-leaf stone flowers
            fl = foil(u0 + fu, fz, 0.42, 8, 32, 0.3); band(F, fl + [fl[0]], 0.07, 0.2, "stone", proud=0.08)
            blob(F.P(u0 + fu, 0.12, fz), 0.08, 0.06, 0.08, "stone", 5, 3)
        fbox(F, u0 + 0.86, u0 + 0.94, 0.12, 0.2, 6.9, 8.7, "stone"); fbox(F, u0, u0 + 1.8, 0.12, 0.2, 7.76, 7.84, "stone")
        band(F, [(u0, 6.9), (u0 + 1.8, 6.9), (u0 + 1.8, 8.7), (u0, 8.7), (u0, 6.9)], 0.22, -0.01, "stone", proud=0.14)
    blob(F.P(SPAN / 2, -0.16, 7.7), 0.42, 0.14, 0.55, "sculpt", 8, 5)        # Grimani arms
    figure(F.P(SPAN / 2, -0.35, 8.4), 1.15)                                    # Justice
    fbox(F, -0.1, SPAN + 0.1, -0.25, 0.0, ZT - 0.45, ZT, "stone")             # cornice
    band(F, topa[::-1], 0.2, 0.0, "stone", proud=0.16)
    for sd in (-1, 1):                                                          # S-scrolls lying along the curved top
        pts = []
        for k in range(16):
            t = k / 15
            if t < 0.6:                                                         # follow the arc down from near the crown
                a = math.pi / 2 - (math.pi / 2 - 0.25) * (t / 0.6) * 0.9
                pts.append(F.P(SPAN / 2 + sd * (SPAN / 2 - 0.9) * math.cos(a), -0.24, ZT + (ZA - ZT) * math.sin(a) + 0.18))
            else:                                                               # then curl outward into a volute
                q = (t - 0.6) / 0.4; a = math.pi * 1.6 * q; r = 0.55 * (1 - 0.55 * q)
                x0 = SPAN / 2 + sd * (SPAN / 2 - 0.55); z0_ = ZT + 0.5
                pts.append(F.P(x0 + sd * r * math.sin(a), -0.24, z0_ + r * math.cos(a)))
        tube(pts, 0.14, "stone", 6)
    blob(F.P(SPAN / 2, -0.2, ZA - 0.25), 0.3, 0.14, 0.3, "sculpt", 6, 4)        # head at the crown
    for uc in (0.2, SPAN - 0.2):
        cc = F.P(uc, -0.05, 0); lathe(cc.x, cc.y, ZT, [(0.22, 0.0), (0.15, 0.5), (0.02, 1.0)], "stone", 6)

# Ponte della Paglia: humped Istrian bridge carrying the Riva across the rio mouth
PP = Frame((71.5, -22.5, 0), (1, 0, 0), (0, 1, 0))
PL = 16.0; PW = 11.5
def deck(u): return G + 1.9 * math.sin(math.pi * u / PL) ** 0.8
top = [(PL - PL * i / 16, deck(PL - PL * i / 16)) for i in range(17)]
soff = [(PL / 2 + 5.8 * math.cos(math.pi * i / 12), -0.4 + 2.75 * math.sin(math.pi * i / 12)) for i in range(13)]
prism(PP, [(0.0, -0.6), (PL / 2 - 5.8, -0.6)] + soff[::-1][0:0] + [(x, z) for x, z in soff[::-1]] + [(PL / 2 + 5.8, -0.6), (PL, -0.6)] + top, 0.0, PW, "stone")
for d0 in (0.0, PW - 0.42):
    for k in range(46):
        u = 0.5 + (PL - 1.0) * k / 45
        cc = PP.P(u, d0 + 0.21, 0)
        if k % 9 == 0: fbox(PP, u - 0.2, u + 0.2, d0 + 0.01, d0 + 0.41, deck(u) + 0.1, deck(u) + 0.98, "stone")
        else: lathe(cc.x, cc.y, deck(u) + 0.1, [(0.07, 0.0), (0.06, 0.15), (0.095, 0.4), (0.055, 0.72), (0.08, 0.88)], "stone", 12)
    for k in range(16):
        u0 = 0.5 + (PL - 1.0) * k / 16; u1 = 0.5 + (PL - 1.0) * (k + 1) / 16
        prism(Frame(PP.P(u0, d0, 0), (1, 0, 0), (0, 1, 0)), [(0, deck(u0) + 0.95), (u1 - u0, deck(u1) + 0.95), (u1 - u0, deck(u1) + 1.08), (0, deck(u0) + 1.08)], -0.1, 0.52, "stone")
        prism(Frame(PP.P(u0, d0, 0), (1, 0, 0), (0, 1, 0)), [(0, deck(u0) + 1.08), (u1 - u0, deck(u1) + 1.08), (u1 - u0, deck(u1) + 1.18), (0, deck(u0) + 1.18)], -0.02, 0.44, "stone")
        prism(Frame(PP.P(u0, d0, 0), (1, 0, 0), (0, 1, 0)), [(0, deck(u0) - 0.05), (u1 - u0, deck(u1) - 0.05), (u1 - u0, deck(u1) + 0.18), (0, deck(u0) + 0.18)], 0.0, 0.42, "stone")
for k in range(16):                                                                                 # deck steps
    u0 = PL * k / 16; u1 = PL * (k + 1) / 16
    oface([PP.P(u0, 0.42, deck(u0) + 0.01), PP.P(u1, 0.42, deck(u1) + 0.01), PP.P(u1, PW - 0.42, deck(u1) + 0.01), PP.P(u0, PW - 0.42, deck(u0) + 0.01)], Vector((0, 0, 1)), "steps")
band(PP, soff, 0.45, 0.0, "stone", proud=0.08)
band(Frame(PP.P(PL, PW, 0), (-1, 0, 0), (0, -1, 0)), soff, 0.45, 0.0, "stone", proud=0.08)

# Prigioni Nuove: Doric arcade on the Riva, piano nobile, prison windows with grates on the rio
ang = math.radians(-3.35)
FP = Frame((84.5, -0.9, 0), (math.cos(ang), math.sin(ang), 0), (-math.sin(ang), math.cos(ang), 0))
PRL = 35.1; PRD = 41.5; PTOP = G + 15.4
def pr_cut():
    for k in range(7):
        uc = 2.5 + k * 5.0
        prism(FP, opening(rounded(uc, 1.75, G + 3.7, 10), G - 1.0), -1.0, 1.9, "rustic")
        prism(FP, [(uc - 0.8, G + 7.9), (uc + 0.8, G + 7.9), (uc + 0.8, G + 11.2), (uc - 0.8, G + 11.2)], -1.0, 0.65, "glass")
carve(lambda: fbox(FP, 0.0, PRL, 0.0, 0.9, -0.4, PTOP, "rustic"), pr_cut)
oface([FP.P(0, 0.9, G + 5.6), FP.P(PRL, 0.9, G + 5.6), FP.P(PRL, 4.5, G + 5.6), FP.P(0, 4.5, G + 5.6)], Vector((0, 0, -1)), "ceiling")
flat(FP, [(0, G - 0.3), (PRL, G - 0.3), (PRL, G + 5.6), (0, G + 5.6)], 4.5, "back")
for k in range(8):
    uc = k * 5.0
    for zz0, zz1 in ((G - 0.3, G + 6.0), (G + 7.0, G + 13.9)):
        for du in ((-0.35, 0.35),) if k in (0, 7) else ((-0.62, -0.18), (0.18, 0.62)):
            fbox(FP, uc + du[0], uc + du[1], -0.28, 0.0, zz0, zz1, "stone")
fbox(FP, -0.2, PRL + 0.2, -0.32, 0.9, G + 6.0, G + 7.0, "stone")
fbox(FP, -0.2, PRL + 0.2, -0.35, 0.9, G + 13.9, G + 14.7, "stone")
fbox(FP, -0.3, PRL + 0.3, -0.6, 0.9, G + 14.7, PTOP, "stone")
for k in range(7):
    uc = 2.5 + k * 5.0
    fbox(FP, uc - 1.15, uc + 1.15, -0.4, 0.0, G + 7.7, G + 7.9, "stone")
    for j in range(6): fbox(FP, uc - 0.95 + j * 0.38, uc - 0.85 + j * 0.38, -0.32, -0.22, G + 7.9, G + 8.6, "stone")
    band(FP, [(uc + 0.8, G + 7.9), (uc + 0.8, G + 11.2), (uc - 0.8, G + 11.2), (uc - 0.8, G + 7.9)], 0.22, 0.0, "stone", proud=0.1)
    fbox(FP, uc - 1.15, uc + 1.15, -0.3, 0.0, G + 11.45, G + 11.7, "stone")
FPW = Frame((84.5, 40.6, 0), (0, -1, 0), (1, 0, 0))
def prw_cut():
    for zz in (G + 2.0, G + 8.3, G + 11.8):
        u = 2.2
        while u < PRD - 2.5:
            prism(FPW, [(u - 0.5, zz), (u + 0.5, zz), (u + 0.5, zz + 1.4), (u - 0.5, zz + 1.4)], -1.0, 0.5, "dark")
            u += 3.4
carve(lambda: fbox(FPW, 0.0, PRD, 0.0, 0.8, -0.6, PTOP, "rustic"), prw_cut)
for zz in (G + 2.0, G + 8.3, G + 11.8):
    u = 2.2
    while u < PRD - 2.5:
        for j in range(4): fbox(FPW, u - 0.42 + j * 0.28, u - 0.36 + j * 0.28, 0.1, 0.16, zz, zz + 1.4, "iron")
        for j in range(3): fbox(FPW, u - 0.5, u + 0.5, 0.1, 0.16, zz + 0.35 * (j + 1) - 0.03, zz + 0.35 * (j + 1) + 0.03, "iron")
        u += 3.4
fbox(FPW, -0.2, PRD + 0.2, -0.5, 0.8, PTOP - 0.7, PTOP, "stone")
FPE = Frame(FP.P(PRL, 0, 0), (-math.sin(ang), math.cos(ang), 0), (-math.cos(ang), -math.sin(ang), 0))
flat(FPE, [(0, G - 0.3), (PRD, G - 0.3), (PRD, PTOP), (0, PTOP)], 0.0, "back")
FPN = Frame(FP.P(PRL, PRD, 0), (-math.cos(ang), -math.sin(ang), 0), (math.sin(ang), -math.cos(ang), 0))
flat(FPN, [(0, G - 0.3), (PRL, G - 0.3), (PRL, PTOP), (0, PTOP)], 0.0, "back")
# tiled hip roof over the (slightly rotated) block
def hip_F(F, u0, u1, d0, d1, z0, slope, mat):
    t = math.tan(math.radians(slope)); h = (d1 - d0) / 2 * t
    A, B, C, D = F.P(u0, d0, z0), F.P(u1, d0, z0), F.P(u1, d1, z0), F.P(u0, d1, z0)
    if (u1 - u0) > (d1 - d0):
        r0 = F.P(u0 + (d1 - d0) / 2, (d0 + d1) / 2, z0 + h); r1 = F.P(u1 - (d1 - d0) / 2, (d0 + d1) / 2, z0 + h)
    else:
        h = (u1 - u0) / 2 * t
        r0 = F.P((u0 + u1) / 2, d0 + (u1 - u0) / 2, z0 + h); r1 = F.P((u0 + u1) / 2, d1 - (u1 - u0) / 2, z0 + h)
    up = Vector((0, 0, 1))
    oface([A, B, r1, r0] if (u1 - u0) > (d1 - d0) else [A, B, r0], -F.i + up, mat)
    oface([C, D, r0, r1] if (u1 - u0) > (d1 - d0) else [C, D, r1], F.i + up, mat)
    oface([B, C, r1] if (u1 - u0) > (d1 - d0) else [B, C, r1, r0], F.a + up, mat)
    oface([D, A, r0] if (u1 - u0) > (d1 - d0) else [D, A, r0, r1], -F.a + up, mat)
hip_F(FP, -0.4, PRL + 0.4, -0.5, PRD + 0.3, PTOP, 22, "tile")
aux = end_part("bridges", weight=1.4)

finish(OUT, ATLAS, ATLAS_RES)

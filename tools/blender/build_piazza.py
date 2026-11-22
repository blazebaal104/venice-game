"""Piazza San Marco surroundings (one landmark, local frame = world frame, landmarks.txt rot 0):
Procuratie Vecchie (north), Procuratie Nuove + Ala Napoleonica (south / west), Libreria Marciana + its north end,
Zecca (on the Molo), Torre dell'Orologio, the two columns of San Marco and San Todaro, the three flagpoles.

Facade lines are fitted to the Regione Veneto polygons (world metres).  Each facade is a row of instanced bay modules
(two baked masters per facade -> high texel density); the rest of each polygon is extruded as a plain mass set back
behind the arcades.

Recognisable traits (photo references):
  * Procuratie Vecchie: long white front, ground arcade of round arches, two upper storeys of close-set arched windows
    (twice the ground rhythm) between slender columns, a row of small merlons on the cornice
  * Procuratie Nuove / Ala Napoleonica: Scamozzi's three orders - Doric arcade, Ionic arched windows with balustrades,
    Corinthian top floor with alternating pediments; the Ala crowned by an attic with statues
  * Libreria (Sansovino): Doric arcade with half-columns, Ionic upper storey with arched windows and small columns, a deep
    frieze with oval windows and garlands, balustrade with statues on every pier and obelisks on the corners
  * Zecca: heavy rusticated Doric front on the water
  * Torre dell'Orologio: arched passage, the blue-and-gold clock face, the Madonna niche, the gold lion on a blue starry
    field, the bell between two bronze Moors on the terrace; four-storey wings with a terrace each side
  * the granite columns with the winged lion and St Theodore on the crocodile"""
import bpy, bmesh, math, sys, os, random
from mathutils import Vector, Matrix

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
OUT = ARGS[0] if len(ARGS) > 0 else "/home/claude/venice/piazza.blend"
ATLAS = ARGS[1] if len(ARGS) > 1 else "/home/claude/venice/piazza_atlas.png"
ATLAS_RES = int(ARGS[2]) if len(ARGS) > 2 else 2048
random.seed(13)
bpy.ops.wm.read_factory_settings(use_empty=True)
ARCH_CZ = 0.0; R_ARCH = 1.0
HERE = os.path.dirname(os.path.abspath(__file__))
exec(open(os.path.join(HERE, "lmcommon.py")).read())
exec(open(os.path.join(HERE, "lmmulti.py")).read())
G = 1.1

# ---------------------------------------------------------------- materials
def m_mass(nt, coord):
    """plain backs of the blocks: light plaster with a grid of dark windows"""
    base = N(nt, "ShaderNodeTexNoise", i_Scale=0.5, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.74, 0.66, 0.56, 1), (0.82, 0.75, 0.65, 1), base.outputs["Fac"])
    br = N(nt, "ShaderNodeTexBrick", offset=0.0, i_Scale=1.0, i_Mortar_Size=1.7, i_Mortar_Smooth=0.0, i_Brick_Width=3.2, i_Row_Height=3.6, i_Bias=0.0)
    for k in ("Color1", "Color2"): br.inputs[k].default_value = (1, 1, 1, 1)
    br.inputs["Mortar"].default_value = (1, 1, 1, 1)
    L(nt, uv_face(nt, coord), br.inputs["Vector"])
    win = mathn(nt, "SUBTRACT", 1.0, br.outputs["Fac"])
    c = mix(nt, c, (0.10, 0.10, 0.11, 1), mathn(nt, "MULTIPLY", win, 0.85))
    return weather(nt, coord, c, streak=0.4, tide=False, ao_min=0.5, grime=0.25)

def m_gold(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=4.0, i_Detail=3.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.50, 0.35, 0.12, 1), (0.66, 0.48, 0.18, 1), base.outputs["Fac"])
    g = N(nt, "ShaderNodeNewGeometry"); sn = N(nt, "ShaderNodeSeparateXYZ"); L(nt, g.outputs["Normal"], sn.inputs[0])
    return mix(nt, c, (0.95, 0.80, 0.42, 1), ramp_val(nt, sn.outputs["Z"], 0.1, 0.9))

def m_blue(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=8.0, i_Detail=2.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.07, 0.12, 0.32, 1), (0.10, 0.17, 0.40, 1), base.outputs["Fac"])
    st = N(nt, "ShaderNodeTexVoronoi", feature="F1"); st.inputs["Scale"].default_value = 2.4; L(nt, coord, st.inputs["Vector"])
    return mix(nt, c, (0.92, 0.76, 0.36, 1), ramp_val(nt, st.outputs["Distance"], 0.09, 0.05))

def m_granite(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=14.0, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.42, 0.38, 0.38, 1), (0.60, 0.55, 0.54, 1), base.outputs["Fac"])
    return weather(nt, coord, c, streak=0.25, tide=False, ao_min=0.55, grime=0.2)

def m_tiles(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=0.8, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.52, 0.31, 0.23, 1), (0.64, 0.40, 0.29, 1), base.outputs["Fac"])
    lic = N(nt, "ShaderNodeTexNoise", i_Scale=2.5, i_Detail=5.0); L(nt, coord, lic.inputs["Vector"])
    c = mix(nt, c, (0.44, 0.40, 0.34, 1), mathn(nt, "MULTIPLY", ramp_val(nt, lic.outputs["Fac"], 0.6, 0.75), 0.4))
    return weather(nt, coord, c, streak=0.0, tide=False, ao_min=0.55, grime=0.1)

def m_shade(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=1.5, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.24, 0.22, 0.20, 1), (0.29, 0.27, 0.24, 1), base.outputs["Fac"])
    return weather(nt, coord, c, streak=0.4, tide=False, ao_min=0.45, grime=0.1)

node_mat("stone", lambda nt, c: m_istrian_plain(nt, c, 1.03, 0.5, 0.42), 15)
node_mat("ashlar", lambda nt, c: m_istrian_ashlar(nt, c, 1.0, 0.4), 17)
node_mat("rustic", m_rustic, 17)
node_mat("sculpt", lambda nt, c: m_istrian_plain(nt, c, 0.97, 0.6, 0.4), 15)
node_mat("shade", m_shade, 0)
node_mat("ceiling", m_flat((0.30, 0.26, 0.21), 0.15, 0.45), 26)
node_mat("glass", m_flat((0.10, 0.11, 0.12), 0.25, 0.7), 33)
node_mat("dark", m_flat((0.04, 0.035, 0.03), 0.2, 0.8), 33)
node_mat("mass", m_mass, 0)
node_mat("tiles", m_tiles, 18)
node_mat("lead", m_lead, 31)
node_mat("gold", m_gold, 33)
node_mat("blue", m_blue, 33)
node_mat("bronze", m_flat((0.16, 0.12, 0.08), 0.2, 0.55), 33)
node_mat("granite", m_granite, 15)
node_mat("red", m_flat((0.40, 0.15, 0.11), 0.15, 0.5), 33)
MAT_ORDER = list(MATS.keys())

def slot_matrix(F):
    return Matrix.Translation(F.o) @ Matrix.Rotation(math.atan2(F.a.y, F.a.x), 4, "Z")

def line_frame(p0, p1, outward_sign=1):
    """facade frame from p0 to p1 with the building on the given side (+1: left of p0->p1)"""
    a = (Vector((p1[0], p1[1], 0)) - Vector((p0[0], p0[1], 0))).normalized()
    inward = Vector((-a.y, a.x, 0)) * outward_sign
    if a.cross(inward).z < 0:                                     # walk the other way so the frame stays right-handed
        return line_frame(p1, p0, -outward_sign)
    return Frame((p0[0], p0[1], 0), a, inward), (Vector(p1) - Vector(p0)).length

def statue(c, h, mat="sculpt"):
    lathe(c.x, c.y, c.z, [(0.15 * h, 0.0), (0.13 * h, 0.25 * h), (0.11 * h, 0.5 * h), (0.14 * h, 0.72 * h), (0.06 * h, 0.82 * h),
                           (0.075 * h, 0.9 * h), (0.0, h)], mat, 7)

def halfcol(F, u, d, z0, z1, r, mat="stone", cap=0.0):
    c = F.P(u, d, 0)
    lathe(c.x, c.y, z0, [(r * 1.3, 0.0), (r * 1.3, 0.2), (r, 0.32), (r * 0.9, z1 - z0 - 0.3), (r * 1.3, z1 - z0 - 0.08), (r * 1.4, z1 - z0)], mat, 8)

def place(master_F, master_ob, F):
    M = slot_matrix(F) @ slot_matrix(master_F).inverted()
    o2 = bpy.data.objects.new(master_ob.name + "_i%d" % len(INSTANCES), master_ob.data); bpy.context.scene.collection.objects.link(o2)
    o2.matrix_world = M; o2["layers"] = master_ob["layers"]; INSTANCES.append(o2)

def facade_row(F, L, n, bay_fn, name, weight=4.5, masters=2):
    P = L / n
    slots = [Frame(F.P(i * P, 0, 0), F.a, F.i) for i in range(n)]
    ms = []
    for k in range(masters):
        idx = int((k + 0.5) * n / masters)
        new_part(); bay_fn(slots[idx], P, idx); ms.append((idx, end_part("%s%d" % (name, k), weight)))
    for i in range(n):
        if any(i == idx for idx, _ in ms): continue
        idx, ob = ms[i % masters]
        place(slots[idx], ob, slots[i])
    return P

# ---------------------------------------------------------------- Procuratie Vecchie bay (pitch ~3.1)
PV_TOP = G + 16.3
def bay_pv(F, P, idx):
    D = 5.6
    w = P / 2 - 0.45
    carve(lambda: fbox(F, 0, P, 0, 0.7, G - 0.3, G + 5.6, "stone"),
          lambda: prism(F, opening(rounded(P / 2, w, G + 3.5, 10), G - 1), -1, 2, "stone"))
    band(F, rounded(P / 2, w, G + 3.5, 10), 0.22, 0.0, "stone", proud=0.06)
    fbox(F, -0.05, 0.05, -0.12, 0.0, G - 0.3, G + 3.5, "stone")
    oface([F.P(0, 0.7, G + 4.9), F.P(P, 0.7, G + 4.9), F.P(P, D, G + 4.9), F.P(0, D, G + 4.9)], Vector((0, 0, -1)), "ceiling")
    flat(F, [(0, G - 0.3), (P, G - 0.3), (P, G + 4.9), (0, G + 4.9)], D, "shade")
    if idx % 3 == 0: flat(F, [(P / 2 - 0.8, G), (P / 2 + 0.8, G), (P / 2 + 0.8, G + 2.8), (P / 2 - 0.8, G + 2.8)], D - 0.02, "dark")
    fbox(F, 0, P, -0.2, 0.7, G + 5.6, G + 6.0, "stone")
    for z0 in (G + 6.0, G + 11.1):
        hw = P / 4 - 0.2
        def cuts(z0=z0):
            for uc in (P / 4, 3 * P / 4): prism(F, opening(rounded(uc, hw, z0 + 3.3, 8), z0 + 0.6), -1, 0.5, "glass")
        carve(lambda z0=z0: fbox(F, 0, P, 0, 0.7, z0, z0 + 5.1, "stone"), cuts)
        for uc in (0.0, P / 2):
            halfcol(F, uc, -0.05, z0 + 0.6, z0 + 3.3, 0.13)
        for uc in (P / 4, 3 * P / 4):
            band(F, rounded(uc, hw, z0 + 3.3, 8), 0.12, 0.0, "stone", proud=0.05)
        fbox(F, 0, P, -0.25, 0.7, z0 + 0.45, z0 + 0.6, "stone")                      # sill band
        fbox(F, 0, P, -0.18, 0.7, z0 + 4.8, z0 + 5.1, "stone")
    fbox(F, 0, P, -0.4, 0.7, PV_TOP - 0.2, PV_TOP + 0.1, "stone")
    for uc in (P / 4, 3 * P / 4):                                                    # merlons
        prism(F, [(uc - 0.22, PV_TOP + 0.1), (uc + 0.22, PV_TOP + 0.1), (uc + 0.22, PV_TOP + 0.55), (uc, PV_TOP + 0.85), (uc - 0.22, PV_TOP + 0.55)], -0.05, 0.25, "stone")
    oface([F.P(0, 0.7, PV_TOP + 0.1), F.P(P, 0.7, PV_TOP + 0.1), F.P(P, D, PV_TOP + 0.1), F.P(0, D, PV_TOP + 0.1)], Vector((0, 0, 1)), "tiles")

# ---------------------------------------------------------------- Procuratie Nuove / Ala / Libreria bay (Sansovino-Scamozzi)
def bay_classic(F, P, idx, top="pn"):
    """top: 'pn' Corinthian third floor (Procuratie Nuove), 'ala' attic with statues, 'lib' Libreria balustrade + statues"""
    D = 6.0
    w = P / 2 - 0.85
    carve(lambda: fbox(F, 0, P, 0, 0.8, G - 0.3, G + 6.4, "stone"),
          lambda: prism(F, opening(rounded(P / 2, w, G + 4.1, 10), G - 1), -1, 2, "stone"))
    band(F, rounded(P / 2, w, G + 4.1, 10), 0.25, 0.0, "stone", proud=0.07)
    halfcol(F, 0.0, -0.1, G - 0.2, G + 5.9, 0.38)                                    # Doric half-column on the pier
    blob(F.P(P / 2, -0.1, G + 5.3), 0.28, 0.12, 0.35, "sculpt", 6, 4)                # keystone head
    oface([F.P(0, 0.8, G + 5.4), F.P(P, 0.8, G + 5.4), F.P(P, D, G + 5.4), F.P(0, D, G + 5.4)], Vector((0, 0, -1)), "ceiling")
    flat(F, [(0, G - 0.3), (P, G - 0.3), (P, G + 5.4), (0, G + 5.4)], D, "shade")
    if idx % 2 == 0: flat(F, [(P / 2 - 0.9, G), (P / 2 + 0.9, G), (P / 2 + 0.9, G + 3.2), (P / 2 - 0.9, G + 3.2)], D - 0.02, "dark")
    # Doric entablature with triglyphs
    fbox(F, 0, P, -0.35, 0.8, G + 6.4, G + 7.5, "stone")
    for k in range(3):
        uu = (k + 0.25) * P / 3
        fbox(F, uu - 0.18, uu + 0.18, -0.42, -0.35, G + 6.6, G + 7.3, "stone")
    fbox(F, 0, P, -0.55, 0.8, G + 7.5, G + 7.8, "stone")
    # Ionic floor: arched window with balustrade between half-columns
    z1 = G + 7.8
    carve(lambda: fbox(F, 0, P, 0, 0.8, z1, z1 + 5.0, "stone"),
          lambda: prism(F, opening(rounded(P / 2, 0.75, z1 + 3.3, 8), z1 + 0.9), -1, 0.6, "glass"))
    band(F, rounded(P / 2, 0.75, z1 + 3.3, 8), 0.18, 0.0, "stone", proud=0.06)
    halfcol(F, 0.0, -0.08, z1 + 0.2, z1 + 4.6, 0.3)
    fbox(F, P / 2 - 1.1, P / 2 + 1.1, -0.4, 0.0, z1 + 0.2, z1 + 0.35, "stone")
    for k in range(6):
        uu = P / 2 - 0.95 + k * 0.38
        fbox(F, uu - 0.06, uu + 0.06, -0.3, -0.18, z1 + 0.35, z1 + 0.95, "stone")
    fbox(F, P / 2 - 1.1, P / 2 + 1.1, -0.4, 0.0, z1 + 0.95, z1 + 1.05, "stone")
    for e in (-1, 1): blob(F.P(P / 2 + e * 1.15, -0.06, z1 + 4.25), 0.38, 0.08, 0.22, "sculpt", 6, 3)   # spandrel figures
    # frieze
    fbox(F, 0, P, -0.3, 0.8, z1 + 5.0, z1 + 6.2, "stone")
    if top == "lib":
        flat(F, [(P / 2 + 0.55 * math.cos(2 * math.pi * k / 14), z1 + 5.6 + 0.35 * math.sin(2 * math.pi * k / 14)) for k in range(14)], -0.31, "dark")
        for e in (-1, 1): blob(F.P(P / 2 + e * 1.3, -0.35, z1 + 5.6), 0.5, 0.1, 0.2, "sculpt", 6, 3)    # garlands / putti
    fbox(F, 0, P, -0.6, 0.8, z1 + 6.2, z1 + 6.6, "stone")
    z2 = z1 + 6.6
    if top == "lib":
        fbox(F, 0, P, -0.15, 0.8, z2, z2 + 0.2, "stone")
        for k in range(int(P / 0.36)):
            uu = 0.5 + k * 0.36
            if uu > P - 0.4: break
            fbox(F, uu - 0.07, uu + 0.07, 0.0, 0.14, z2 + 0.2, z2 + 0.95, "stone")
        fbox(F, 0, P, -0.1, 0.8, z2 + 0.95, z2 + 1.12, "stone")
        fbox(F, -0.3, 0.3, -0.25, 0.4, z2, z2 + 1.12, "stone")
        statue(F.P(0.0, 0.07, z2 + 1.12), 2.1)
        oface([F.P(0, 0.8, z2 + 0.2), F.P(P, 0.8, z2 + 0.2), F.P(P, D, z2 + 0.2), F.P(0, D, z2 + 0.2)], Vector((0, 0, 1)), "lead")
        return
    # Corinthian floor (Procuratie Nuove / Ala): rectangular window with alternating pediment
    carve(lambda: fbox(F, 0, P, 0, 0.8, z2, z2 + 5.0, "stone"),
          lambda: prism(F, [(P / 2 - 0.7, z2 + 0.9), (P / 2 + 0.7, z2 + 0.9), (P / 2 + 0.7, z2 + 3.3), (P / 2 - 0.7, z2 + 3.3)], -1, 0.6, "glass"))
    band(F, [(P / 2 + 0.7, z2 + 0.9), (P / 2 + 0.7, z2 + 3.3), (P / 2 - 0.7, z2 + 3.3), (P / 2 - 0.7, z2 + 0.9)], 0.16, 0.0, "stone", proud=0.06)
    halfcol(F, 0.0, -0.08, z2 + 0.1, z2 + 4.5, 0.26)
    if idx % 2 == 0: prism(F, [(P / 2 - 1.0, z2 + 3.45), (P / 2 + 1.0, z2 + 3.45), (P / 2, z2 + 4.05)], -0.22, 0.0, "stone")
    else: band(F, rounded(P / 2, 0.85, z2 + 3.45, 8), 0.14, 0.0, "stone", proud=0.2)
    fbox(F, P / 2 - 0.9, P / 2 + 0.9, -0.25, 0.0, z2 + 0.75, z2 + 0.9, "stone")
    fbox(F, 0, P, -0.3, 0.8, z2 + 4.6, z2 + 5.3, "stone")
    fbox(F, 0, P, -0.7, 0.8, z2 + 5.3, z2 + 5.8, "stone")
    z3 = z2 + 5.8
    if top == "ala":
        fbox(F, 0, P, 0.0, 0.8, z3, z3 + 2.4, "stone")
        flat(F, [(P / 2 - 0.9, z3 + 0.5), (P / 2 + 0.9, z3 + 0.5), (P / 2 + 0.9, z3 + 1.9), (P / 2 - 0.9, z3 + 1.9)], -0.01, "sculpt")
        fbox(F, 0, P, -0.25, 0.8, z3 + 2.4, z3 + 2.7, "stone")
        statue(F.P(0.0, 0.3, z3 + 2.7), 2.2)
        oface([F.P(0, 0.8, z3 + 2.4), F.P(P, 0.8, z3 + 2.4), F.P(P, D, z3 + 2.4), F.P(0, D, z3 + 2.4)], Vector((0, 0, 1)), "lead")
    else:
        oface([F.P(0, 0.8, z3), F.P(P, 0.8, z3), F.P(P, D, z3), F.P(0, D, z3)], Vector((0, 0, 1)), "tiles")

PN_TOP = G + 7.8 + 6.6 + 5.8
LIB_TOP = G + 7.8 + 6.6 + 1.12

# ---------------------------------------------------------------- facades
FAC = {}
def facade(name, p0, p1, side, n, fn, masters=2, weight=4.5):
    F, L = line_frame(p0, p1, side)
    P = facade_row(F, L, n, fn, name, weight, masters)
    FAC[name] = (F, L, P)
    return F, L

F_PV, L_PV = facade("pv", (-145.6, -5.6), (14.5, 92.7), 1, 52, bay_pv)
F_PN, L_PN = facade("pn", (-122.5, -58.1), (1.8, -9.2), -1, 32, lambda F, P, i: bay_classic(F, P, i, "pn"))
F_AL, L_AL = facade("ala", (-145.6, -5.6), (-122.5, -58.1), -1, 14, lambda F, P, i: bay_classic(F, P, i, "ala"), masters=1, weight=3.5)
F_LB, L_LB = facade("lib", (15.0, -6.0), (45.5, -82.5), -1, 21, lambda F, P, i: bay_classic(F, P, i, "lib"))
F_LN, L_LN = facade("libn", (2.1, -10.0), (15.0, -6.0), -1, 3, lambda F, P, i: bay_classic(F, P, i, "lib"), masters=1, weight=3.0)

# ---------------------------------------------------------------- unique: masses, Zecca, corners, Torre, columns
new_part()
def clip(poly, F, dmin):
    """keep the part of a world polygon that lies at depth >= dmin behind facade frame F"""
    def dep(p): return (Vector((p[0], p[1], 0)) - F.o).dot(F.i)
    out = []
    n = len(poly)
    for k in range(n):
        a, b = poly[k], poly[(k + 1) % n]
        da, db = dep(a) - dmin, dep(b) - dmin
        if da >= 0: out.append(a)
        if (da >= 0) != (db >= 0):
            t = da / (da - db); out.append((a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t))
    return out

def mass(poly, z1, clips, roof="tiles"):
    for F, dmin in clips: poly = clip(poly, F, dmin)
    if len(poly) < 3: return
    if poly_area2(poly) < 0: poly = poly[::-1]
    n = len(poly)
    for k in range(n):
        a, b = poly[k], poly[(k + 1) % n]
        if (Vector(b) - Vector(a)).length < 0.05: continue
        en = Vector((b[1] - a[1], -(b[0] - a[0]), 0))
        oface([Vector((a[0], a[1], G - 0.3)), Vector((b[0], b[1], G - 0.3)), Vector((b[0], b[1], z1)), Vector((a[0], a[1], z1))], en, "mass")
    oface([Vector((x, y, z1)) for x, y in poly], Vector((0, 0, 1)), roof)

import pickle
POLYS = pickle.load(open(os.path.join(HERE, "piazza_polys.pkl"), "rb"))
def P_(key): return [tuple(p) for p in POLYS[key][:-1]]
mass(P_((-92, 43)), PV_TOP + 0.1, [(F_PV, 5.5)])
mass(P_((-33, 76)), PV_TOP + 0.1, [(F_PV, 5.5)])
mass(P_((-57, -50)), PN_TOP, [(F_PN, 5.9), (F_AL, 5.9), (F_LN, 5.9)])
mass(P_((-139, -38)), PN_TOP + 2.4, [(F_AL, 5.9), (F_PN, 5.9)], "lead")
for k in ((11, -23), (11, -58), (27, -63)):
    mass(P_(k), LIB_TOP - 0.9, [(F_LB, 5.9), (F_LN, 5.9)], "lead")
# fill the strip between the Procuratie Vecchie front and the Torre dell'Orologio wings
mass([(-14.0, 75.0), (14.5, 92.7), (8.2, 103.0), (-24.3, 92.2)], PV_TOP + 0.1, [(F_PV, 5.5)])

# corner obelisks on the Libreria
for (u, F) in ((0.0, F_LB), (L_LB, F_LB), (0.0, F_LN)):
    c = F.P(u, 0.3, 0)
    box(c.x - 0.45, c.x + 0.45, c.y - 0.45, c.y + 0.45, LIB_TOP, LIB_TOP + 0.6, "stone")
    lathe(c.x, c.y, LIB_TOP + 0.6, [(0.32, 0.0), (0.12, 3.2)], "stone", 4)
    lathe(c.x, c.y, LIB_TOP + 3.8, [(0.15, 0.0), (0.2, 0.15), (0.0, 0.4)], "gold", 6)

# Zecca: rusticated Doric front on the Molo, 9 bays, three storeys
FZ, LZ = line_frame((-4.1, -101.6), (31.5, -88.5), 1)
ZT = G + 16.5
def z_cut():
    for k in range(9):
        uc = (k + 0.5) * LZ / 9
        prism(FZ, opening(rounded(uc, 1.2, G + 3.4, 8), G - 1), -1, 1.8, "rustic")
        for z0 in (G + 6.6, G + 11.6):
            prism(FZ, [(uc - 0.65, z0), (uc + 0.65, z0), (uc + 0.65, z0 + 2.4), (uc - 0.65, z0 + 2.4)], -1, 0.6, "glass")
carve(lambda: fbox(FZ, 0, LZ, 0, 0.9, G - 0.6, ZT, "rustic"), z_cut)
flat(FZ, [(0, G - 0.3), (LZ, G - 0.3), (LZ, G + 4.6), (0, G + 4.6)], 1.79, "shade")
for k in range(9):
    uc = (k + 0.5) * LZ / 9
    for z0 in (G + 6.6, G + 11.6):                                     # heavy blocked surrounds and lintels
        for j in range(4):
            fbox(FZ, uc - 0.95, uc - 0.65, -0.3 - 0.08 * (j % 2), 0.0, z0 + j * 0.6, z0 + j * 0.6 + 0.52, "rustic")
            fbox(FZ, uc + 0.65, uc + 0.95, -0.3 - 0.08 * (j % 2), 0.0, z0 + j * 0.6, z0 + j * 0.6 + 0.52, "rustic")
        prism(FZ, [(uc - 0.95, z0 + 2.4), (uc + 0.95, z0 + 2.4), (uc + 0.8, z0 + 3.1), (uc - 0.8, z0 + 3.1)], -0.35, 0.0, "rustic")
    for z0, z1 in ((G + 5.6, G + 10.4), (G + 10.9, G + 15.3)):
        halfcol(FZ, k * LZ / 9, -0.15, z0, z1, 0.32, "rustic")
for zb in (G + 5.0, G + 10.4, G + 15.3):
    fbox(FZ, -0.2, LZ + 0.2, -0.5, 0.9, zb, zb + 0.55, "stone")
fbox(FZ, -0.3, LZ + 0.3, -0.8, 0.9, ZT - 0.6, ZT, "stone")
mass(P_((4, -90)), ZT, [(FZ, 0.85), (F_LB, 5.9)], "lead")

# Torre dell'Orologio + wings (front from (20.0, 99.1) to (39.2, 109.8))
FT, LT = line_frame((20.0, 99.1), (39.2, 109.8), 1)
TU0 = LT / 2 - 3.8; TU1 = LT / 2 + 3.8; TC = LT / 2
TTOP = G + 20.4
def t_cut():
    prism(FT, opening(rounded(TC, 1.3, G + 4.6, 10), G - 1), -1, 7.0, "dark")
carve(lambda: fbox(FT, TU0, TU1, 0, 7.0, G - 0.3, TTOP, "stone"), t_cut)
band(FT, rounded(TC, 1.3, G + 4.6, 10), 0.3, 0.0, "stone", proud=0.12)
for u in (TU0 + 0.35, TU1 - 0.35):
    fbox(FT, u - 0.35, u + 0.35, -0.25, 0.0, G - 0.3, TTOP, "stone")
fbox(FT, TC - 3.3, TC + 3.3, -0.15, 0.0, G + 6.6, G + 12.8, "stone")             # clock: stone square, gold ring, blue face
ring = circle(TC, G + 9.7, 2.7, 32); band(FT, ring + [ring[0]], 0.32, -0.15, "gold", proud=0.1)
flat(FT, circle(TC, G + 9.7, 2.7, 32), -0.16, "blue")
band(FT, circle(TC, G + 9.7, 1.6, 24) + [circle(TC, G + 9.7, 1.6, 24)[0]], 0.1, -0.17, "gold", proud=0.04)
for k in range(24):
    a = 2 * math.pi * k / 24
    fbox(FT, TC + 2.25 * math.cos(a) - 0.07, TC + 2.25 * math.cos(a) + 0.07, -0.22, -0.16, G + 9.7 + 2.25 * math.sin(a) - 0.15, G + 9.7 + 2.25 * math.sin(a) + 0.15, "gold")
flat(FT, circle(TC, G + 9.7, 0.5, 16), -0.19, "gold")
flat(FT, [(TC - 0.06, G + 9.7), (TC + 0.06, G + 9.7), (TC + 0.6, G + 11.8), (TC + 0.48, G + 11.85)], -0.2, "gold")
for e in (-1, 1):                                                                  # corner figures on the clock square
    blob(FT.P(TC + e * 2.9, -0.2, G + 7.1), 0.25, 0.1, 0.25, "gold", 5, 3)
fbox(FT, TC - 3.4, TC + 3.4, -0.4, 0.0, G + 12.8, G + 13.2, "stone")
fbox(FT, TC - 1.4, TC + 1.4, -0.9, 0.0, G + 13.2, G + 13.45, "stone")            # Madonna balcony
flat(FT, opening(rounded(TC, 0.8, G + 15.4, 8), G + 13.45), -0.01, "blue")
statue(FT.P(TC, -0.4, G + 13.45), 1.7, "gold")
for e in (-1, 1):
    flat(FT, [(TC + e * 1.6 - 0.55, G + 13.7), (TC + e * 1.6 + 0.55, G + 13.7), (TC + e * 1.6 + 0.55, G + 15.6), (TC + e * 1.6 - 0.55, G + 15.6)], -0.01, "blue")
fbox(FT, TC - 3.4, TC + 3.4, -0.3, 0.0, G + 16.3, G + 16.6, "stone")
flat(FT, [(TC - 2.7, G + 16.8), (TC + 2.7, G + 16.8), (TC + 2.7, G + 19.8), (TC - 2.7, G + 19.8)], -0.01, "blue")
lion = [(-1.0, -0.35), (-0.85, -0.35), (-0.8, -0.05), (0.45, -0.05), (0.5, -0.35), (0.65, -0.35), (0.7, 0.05), (0.95, 0.25), (1.05, 0.6),
        (0.9, 0.78), (0.62, 0.62), (0.5, 0.35), (-0.6, 0.3), (-0.85, 0.5), (-1.15, 0.25)]
flat(FT, [(TC + 1.5 * x, G + 18.0 + 1.5 * z) for x, z in lion], -0.03, "gold")
flat(FT, [(TC + 1.5 * x, G + 18.0 + 1.5 * z) for x, z in [(-0.3, 0.3), (0.35, 0.33), (0.2, 1.0), (-0.1, 1.15), (-0.55, 0.95)]], -0.04, "gold")
fbox(FT, TU0 - 0.2, TU1 + 0.2, -0.5, 7.2, TTOP, TTOP + 0.4, "stone")           # terrace: balustrade, bell, Moors
for k in range(18):
    uu = TU0 + 0.2 + k * (TU1 - TU0 - 0.4) / 17
    fbox(FT, uu - 0.07, uu + 0.07, -0.35, -0.21, TTOP + 0.4, TTOP + 1.1, "stone")
fbox(FT, TU0 - 0.2, TU1 + 0.2, -0.45, -0.1, TTOP + 1.1, TTOP + 1.22, "stone")
cb = FT.P(TC, 3.5, 0)
for e in (-1.6, 1.6):
    pp = FT.P(TC + e, 3.5, 0); box(pp.x - 0.12, pp.x + 0.12, pp.y - 0.12, pp.y + 0.12, TTOP + 0.4, TTOP + 4.0, "bronze")
lathe(cb.x, cb.y, TTOP + 2.0, [(1.25, 0.0), (1.15, 0.3), (0.95, 0.9), (0.85, 1.5), (0.45, 1.85), (0.0, 1.95)], "bronze", 14)
for e in (-1, 1):
    pp = FT.P(TC + e * 2.1, 3.5, 0)
    lathe(pp.x, pp.y, TTOP + 0.4, [(0.32, 0.0), (0.28, 0.9), (0.3, 1.5), (0.36, 2.0), (0.14, 2.25), (0.17, 2.5), (0.0, 2.7)], "bronze", 8)
    tube([pp + Vector((0, 0, TTOP + 2.1)), pp + Vector((0, 0, TTOP + 2.6)) + (cb - pp) * 0.35, pp + Vector((0, 0, TTOP + 3.0)) + (cb - pp) * 0.5], 0.06, "bronze", 5)
for (u0, u1) in ((0.0, TU0), (TU1, LT)):                                          # the two wings
    def w_cut(u0=u0, u1=u1):
        n = max(1, int((u1 - u0) / 3.3))
        for k in range(n):
            uc = u0 + (k + 0.5) * (u1 - u0) / n
            prism(FT, opening(rounded(uc, 1.05, G + 3.6, 8), G - 1), -1, 1.5, "dark")
            for z0 in (G + 6.0, G + 9.4, G + 12.6):
                prism(FT, opening(rounded(uc, 0.55, z0 + 1.9, 6), z0), -1, 0.5, "glass")
    carve(lambda u0=u0, u1=u1: fbox(FT, u0, u1, 0, 6.0, G - 0.3, G + 15.6, "stone"), w_cut)
    for zb in (G + 5.4, G + 8.9, G + 12.1):
        fbox(FT, u0, u1, -0.2, 0.0, zb, zb + 0.25, "stone")
    fbox(FT, u0 - 0.1, u1 + 0.1, -0.35, 6.0, G + 15.6, G + 16.0, "stone")
    n = int((u1 - u0) / 0.4)
    for k in range(n):
        uu = u0 + 0.2 + k * (u1 - u0 - 0.4) / max(n - 1, 1)
        fbox(FT, uu - 0.06, uu + 0.06, -0.25, -0.13, G + 16.0, G + 16.7, "stone")
    fbox(FT, u0, u1, -0.3, -0.08, G + 16.7, G + 16.82, "stone")

# the columns of San Marco (lion) and San Todaro on the Molo
for (cx, cy, who) in ((51.3, -76.0, "todaro"), (75.3, -69.1, "lion")):
    for k, (r, h) in enumerate(((2.3, 0.35), (1.9, 0.35), (1.5, 0.35))):
        box(cx - r, cx + r, cy - r, cy + r, G + k * 0.35, G + (k + 1) * 0.35, "stone")
    lathe(cx, cy, G + 1.05, [(1.05, 0.0), (1.0, 0.5), (0.85, 0.7), (0.82, 11.6), (0.72, 11.9)], "granite", 16)
    lathe(cx, cy, G + 12.95, [(0.75, 0.0), (1.05, 0.7), (1.25, 1.2)], "stone", 12)
    box(cx - 1.3, cx + 1.3, cy - 1.3, cy + 1.3, G + 14.15, G + 14.6, "stone")
    zt = G + 14.6
    if who == "lion":                                                                # winged bronze lion facing the lagoon
        d = Vector((0.276, -0.961, 0)); s_ = Vector((0.961, 0.276, 0))
        c0 = Vector((cx, cy, zt))
        blob(c0 + Vector((0, 0, 0.95)), 1.3, 0.5, 0.5, "bronze", 8, 6, rot=0.0)
        blob(c0 + d * 1.25 + Vector((0, 0, 1.45)), 0.55, 0.5, 0.6, "bronze", 8, 6)
        for e in (-1, 1):
            obox(c0 + s_ * e * 0.45 + Vector((0, 0, 1.9)) - d * 0.2, -d, Vector((0, 0, 1)), s_ * e, 0.9, 0.55, 0.05, "bronze")
            for f_ in (-0.9, 0.9):
                tube([c0 + d * f_ + s_ * e * 0.3 + Vector((0, 0, 0.7)), c0 + d * f_ + s_ * e * 0.3 + Vector((0, 0, 0.05))], 0.14, "bronze", 5)
        tube([c0 - d * 1.2 + Vector((0, 0, 1.0)), c0 - d * 1.7 + Vector((0, 0, 0.6))], 0.08, "bronze", 5)
    else:                                                                            # St Theodore standing on the crocodile
        blob(Vector((cx, cy, zt + 0.3)), 1.1, 0.35, 0.28, "sculpt", 8, 5)
        statue(Vector((cx, cy, zt + 0.45)), 2.9, "sculpt")
        tube([Vector((cx + 0.35, cy, zt + 1.8)), Vector((cx + 0.45, cy, zt + 3.6))], 0.05, "sculpt", 5)

# three flagpoles in front of the Basilica (bronze pedestals, red masts)
for v in (14.0, 27.5, 41.0):
    p = Vector((32.4, 25.6, 0)) + Vector((0.961, 0.276, 0)) * (-26.0) + Vector((-0.276, 0.961, 0)) * v
    lathe(p.x, p.y, G, [(1.0, 0.0), (0.95, 0.5), (0.7, 1.4), (0.45, 2.4), (0.3, 2.6)], "bronze", 12)
    lathe(p.x, p.y, G + 2.6, [(0.2, 0.0), (0.09, 19.0)], "red", 8)
    lathe(p.x, p.y, G + 21.6, [(0.2, 0.0), (0.25, 0.25), (0.0, 0.6)], "gold", 6)
uniq = end_part("unique", weight=1.0)

finish(OUT, ATLAS, ATLAS_RES)

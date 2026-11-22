"""Basilica di San Marco + Porta della Carta + Pilastri Acritani (one landmark).

Local frame (landmarks.txt rot 16.0): origin = south-west corner of the west front, +X east along the nave, +Y north
along the front, z = 0 mean water, paving 1.1 m.  Footprint from the Regione Veneto polygon: west front 52 m (y 1.5 ..
53.5), nave axis y = 27.5, apse to x ~ 78.  The Palazzo Ducale's Piazzetta facade is the plane x = 22.3 (its NW corner
at y = -9.9); the Porta della Carta closes the gap x = 22.3..23, y = -9.9..-3.6.

Recognisable features (photo references of the west front and the Piazzetta side):
  * lower order: five deep round-arched portals (central one wider and taller) between piers faced with two tiers of
    small marble columns, gold-ground mosaic lunettes at the back of each portal, bronze doors
  * the terrace (Loggia dei Cavalli) with a balustrade and the four gilded bronze horses over the central portal
  * upper order: five round arches (gold mosaics; the central one a big glazed lunette) crowned by ogee crests with gilt
    crockets and statues, St Mark and the winged lion on a blue starry field on the central one, pinnacle tabernacles
    between them
  * five lead domes on tall drums with small lanterns, gilt balls and crosses (the central one highest, ~43 m)"""
import bpy, bmesh, math, sys, os, random
from mathutils import Vector, Matrix

ARGS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
OUT = ARGS[0] if len(ARGS) > 0 else "/home/claude/venice/basilica.blend"
ATLAS = ARGS[1] if len(ARGS) > 1 else "/home/claude/venice/basilica_atlas.png"
ATLAS_RES = int(ARGS[2]) if len(ARGS) > 2 else 2048
random.seed(11)
bpy.ops.wm.read_factory_settings(use_empty=True)
ARCH_CZ = 0.0; R_ARCH = 1.0
HERE = os.path.dirname(os.path.abspath(__file__))
exec(open(os.path.join(HERE, "lmcommon.py")).read())
exec(open(os.path.join(HERE, "lmmulti.py")).read())

G = 1.1
TER = G + 16.0                 # terrace floor
AXIS = 27.5

# ---------------------------------------------------------------- materials
def m_marble(nt, coord):
    """facade veneer: big slabs of grey-white veined marble with joints, panel tones varying"""
    base = N(nt, "ShaderNodeTexNoise", i_Scale=0.9, i_Detail=5.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.71, 0.67, 0.59, 1), (0.81, 0.77, 0.69, 1), base.outputs["Fac"])
    mp = N(nt, "ShaderNodeMapping"); L(nt, coord, mp.inputs[0]); mp.inputs["Scale"].default_value = (1.0, 1.0, 3.0)
    vn = N(nt, "ShaderNodeTexWave", wave_type="BANDS", bands_direction="DIAGONAL"); vn.inputs["Scale"].default_value = 1.6
    vn.inputs["Distortion"].default_value = 9.0; vn.inputs["Detail"].default_value = 4.0; L(nt, mp.outputs[0], vn.inputs["Vector"])
    c = mix(nt, c, (0.58, 0.58, 0.58, 1), mathn(nt, "MULTIPLY", ramp_val(nt, vn.outputs["Fac"], 0.9, 1.0), 0.45))
    c = joints(nt, c, uv_face(nt, coord), 1.4, 2.1, 0.0, mortar=0.02, jcol=(0.5, 0.48, 0.45), var=0.14)
    return weather(nt, coord, c, streak=0.5, tide=False, ao_min=0.4, grime=0.38)

def m_colmarble(tone):
    def f(nt, coord):
        base = N(nt, "ShaderNodeTexNoise", i_Scale=4.0, i_Detail=6.0, i_Distortion=2.0); L(nt, coord, base.inputs["Vector"])
        c = mix(nt, (tone[0] * 0.75, tone[1] * 0.75, tone[2] * 0.75, 1), (*tone, 1), base.outputs["Fac"])
        return weather(nt, coord, c, streak=0.1, tide=False, ao_min=0.55, grime=0.08)
    return f

def m_mosaic(nt, coord):
    """gold-ground mosaic scenes: warm deep gold, rows of standing robed figures (blue, red, white, brown), a dark
    red-brown frame line, tesserae shimmer"""
    base = N(nt, "ShaderNodeTexNoise", i_Scale=45.0, i_Detail=1.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.40, 0.30, 0.11, 1), (0.52, 0.40, 0.16, 1), base.outputs["Fac"])
    vec = uv_face(nt, coord)
    mp = N(nt, "ShaderNodeMapping"); L(nt, vec, mp.inputs[0]); mp.inputs["Scale"].default_value = (1.3, 0.38, 1.0)
    fg = N(nt, "ShaderNodeTexVoronoi", feature="F1"); fg.inputs["Scale"].default_value = 1.4; L(nt, mp.outputs[0], fg.inputs["Vector"])
    fig = ramp_val(nt, fg.outputs["Distance"], 0.50, 0.38)
    hue = fg.outputs["Color"]
    sep = N(nt, "ShaderNodeSeparateColor"); L(nt, hue, sep.inputs[0])
    robe = mix(nt, (0.16, 0.19, 0.30, 1), (0.40, 0.16, 0.13, 1), ramp_val(nt, sep.outputs[0], 0.35, 0.45))
    robe = mix(nt, robe, (0.62, 0.58, 0.50, 1), ramp_val(nt, sep.outputs[1], 0.72, 0.8))
    robe = mix(nt, robe, (0.30, 0.20, 0.13, 1), ramp_val(nt, sep.outputs[2], 0.78, 0.86))
    c = mix(nt, c, robe, mathn(nt, "MULTIPLY", fig, 0.0))
    halo = ramp_val(nt, fg.outputs["Distance"], 0.5, 0.42)
    c = mix(nt, c, (0.95, 0.80, 0.40, 1), mathn(nt, "MULTIPLY", mathn(nt, "SUBTRACT", halo, fig), 0.0))
    return weather(nt, coord, c, streak=0.0, tide=False, ao_min=0.35, grime=0.08)

def m_blue(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=8.0, i_Detail=2.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.08, 0.14, 0.38, 1), (0.12, 0.20, 0.48, 1), base.outputs["Fac"])
    st = N(nt, "ShaderNodeTexVoronoi", feature="F1"); st.inputs["Scale"].default_value = 2.2; L(nt, coord, st.inputs["Vector"])
    return mix(nt, c, (0.95, 0.80, 0.40, 1), ramp_val(nt, st.outputs["Distance"], 0.09, 0.05))

def m_gold(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=4.0, i_Detail=3.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.50, 0.34, 0.11, 1), (0.66, 0.47, 0.17, 1), base.outputs["Fac"])
    g = N(nt, "ShaderNodeNewGeometry"); sn = N(nt, "ShaderNodeSeparateXYZ"); L(nt, g.outputs["Normal"], sn.inputs[0])
    c = mix(nt, c, (0.98, 0.84, 0.45, 1), ramp_val(nt, sn.outputs["Z"], 0.1, 0.9))
    return mix(nt, c, (0.30, 0.20, 0.07, 1), ramp_val(nt, sn.outputs["Z"], -0.2, -0.8))

def m_horse(nt, coord):
    """gilded bronze, patinated green-brown in the hollows"""
    base = N(nt, "ShaderNodeTexNoise", i_Scale=3.0, i_Detail=4.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.36, 0.30, 0.16, 1), (0.62, 0.48, 0.22, 1), base.outputs["Fac"])
    g = N(nt, "ShaderNodeNewGeometry"); sn = N(nt, "ShaderNodeSeparateXYZ"); L(nt, g.outputs["Normal"], sn.inputs[0])
    c = mix(nt, c, (0.92, 0.74, 0.36, 1), mathn(nt, "MULTIPLY", ramp_val(nt, sn.outputs["Z"], -0.3, 0.7), 0.85))
    return weather(nt, coord, c, streak=0.0, tide=False, ao_min=0.35, grime=0.05)

def m_brick(nt, coord):
    base = N(nt, "ShaderNodeTexNoise", i_Scale=0.35, i_Detail=5.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.50, 0.31, 0.24, 1), (0.60, 0.39, 0.29, 1), base.outputs["Fac"])
    sp = N(nt, "ShaderNodeTexNoise", i_Scale=30.0, i_Detail=1.0); L(nt, coord, sp.inputs["Vector"])
    c = mix(nt, c, (0.68, 0.50, 0.38, 1), mathn(nt, "MULTIPLY", ramp_val(nt, sp.outputs["Fac"], 0.6, 0.72), 0.35))
    return weather(nt, coord, c, streak=0.4, tide=False, ao_min=0.45, grime=0.2)

def m_domelead(nt, coord):
    """lead sheets on the domes: vertical seams, light oxide bloom, dark rain tracks"""
    base = N(nt, "ShaderNodeTexNoise", i_Scale=0.7, i_Detail=5.0); L(nt, coord, base.inputs["Vector"])
    c = mix(nt, (0.28, 0.30, 0.33, 1), (0.35, 0.37, 0.40, 1), base.outputs["Fac"])
    sz = sep_z(nt, coord)
    ang = mathn(nt, "ARCTAN2", sz.outputs["Y"], sz.outputs["X"])
    wv = N(nt, "ShaderNodeTexWave", wave_type="BANDS", bands_direction="X"); wv.inputs["Scale"].default_value = 1.0
    cv = N(nt, "ShaderNodeCombineXYZ"); L(nt, mathn(nt, "MULTIPLY", ang, 6.0), cv.inputs["X"]); L(nt, cv.outputs[0], wv.inputs["Vector"])
    c = mix(nt, c, (0.20, 0.21, 0.22, 1), mathn(nt, "MULTIPLY", ramp_val(nt, wv.outputs["Fac"], 0.1, 0.0), 0.8))
    mp = N(nt, "ShaderNodeMapping"); L(nt, coord, mp.inputs[0]); mp.inputs["Scale"].default_value = (5.0, 5.0, 0.3)
    st = N(nt, "ShaderNodeTexNoise", i_Scale=2.0, i_Detail=4.0); L(nt, mp.outputs[0], st.inputs["Vector"])
    c = mix(nt, c, (0.33, 0.33, 0.34, 1), mathn(nt, "MULTIPLY", ramp_val(nt, st.outputs["Fac"], 0.55, 0.75), 0.45))
    return weather(nt, coord, c, streak=0.0, tide=False, ao_min=0.6, grime=0.08)

node_mat("marble", m_marble, 15)
node_mat("stone", lambda nt, c: m_istrian_plain(nt, c, 1.04, 0.45, 0.45), 15)
node_mat("sculpt", lambda nt, c: mix(nt, m_istrian_plain(nt, c, 0.96, 0.6, 0.4), (0.92, 0.88, 0.80, 1), 1.0, "MULTIPLY"), 15)
node_mat("colA", m_colmarble((0.70, 0.68, 0.64)), 15)        # grey cipollino
node_mat("colB", m_colmarble((0.13, 0.17, 0.15)), 15)        # verde antico
node_mat("colC", m_colmarble((0.36, 0.20, 0.20)), 15)        # porphyry
node_mat("colD", m_colmarble((0.86, 0.84, 0.79)), 15)        # white marble
node_mat("mosaic", m_mosaic, 33)
node_mat("robeB", m_flat((0.10, 0.12, 0.22), 0.15, 0.5), 33)
node_mat("robeR", m_flat((0.30, 0.11, 0.09), 0.15, 0.5), 33)
node_mat("robeW", m_flat((0.56, 0.53, 0.46), 0.1, 0.5), 33)
node_mat("robeN", m_flat((0.27, 0.19, 0.13), 0.1, 0.5), 33)
node_mat("archi", m_flat((0.36, 0.30, 0.20), 0.15, 0.5), 33)
node_mat("skin", m_flat((0.70, 0.55, 0.42), 0.1, 0.5), 33)
node_mat("halo", m_flat((0.62, 0.48, 0.20), 0.1, 0.5), 33)
node_mat("frame", m_flat((0.38, 0.16, 0.12), 0.15, 0.5), 33)
node_mat("blue", m_blue, 33)
node_mat("gold", m_gold, 33)
node_mat("horse", m_horse, 33)
node_mat("glass", m_flat((0.20, 0.24, 0.30), 0.25, 0.7), 33)
node_mat("dark", m_flat((0.04, 0.035, 0.03), 0.2, 0.8), 33)
def m_door(nt, coord):
    """bronze doors: square panels with raised frames and rows of studs, green-brown patina, worn highlights"""
    vec = uv_face(nt, coord)
    br = N(nt, "ShaderNodeTexBrick", offset=0.0, i_Scale=1.0, i_Mortar_Size=0.07, i_Mortar_Smooth=0.2, i_Brick_Width=0.55, i_Row_Height=0.62, i_Bias=0.0)
    br.inputs["Color1"].default_value = (0.20, 0.19, 0.13, 1); br.inputs["Color2"].default_value = (0.17, 0.17, 0.12, 1)
    br.inputs["Mortar"].default_value = (0.26, 0.22, 0.14, 1); L(nt, vec, br.inputs["Vector"])
    vo = N(nt, "ShaderNodeTexVoronoi", feature="F1"); vo.inputs["Scale"].default_value = 9.0; L(nt, vec, vo.inputs["Vector"])
    c = mix(nt, br.outputs["Color"], (0.36, 0.30, 0.18, 1), ramp_val(nt, vo.outputs["Distance"], 0.12, 0.07))
    return weather(nt, coord, c, streak=0.2, tide=False, ao_min=0.5, grime=0.1)
node_mat("bronze", m_door, 33)
node_mat("panelwall", lambda nt, c: mix(nt, joints(nt, m_marble(nt, c), uv_face(nt, c), 1.25, 1.7, 0.0, mortar=0.06, jcol=(0.35, 0.33, 0.30), var=0.25), (0.75, 0.73, 0.70, 1), 1.0, "MULTIPLY"), 15)
node_mat("gilt", m_flat((0.62, 0.48, 0.20), 0.15, 0.5), 33)
node_mat("recess", lambda nt, c: mix(nt, m_marble(nt, c), (0.5, 0.48, 0.45, 1), 1.0, "MULTIPLY"), 15)
node_mat("brick", m_brick, 11)
node_mat("lead", m_lead, 31)
node_mat("domelead", m_domelead, 31)
MAT_ORDER = list(MATS.keys())
COLS = ["colA", "colD", "colB", "colA", "colC", "colD"]

def statue(c, h, mat="sculpt", seated=False):
    lathe(c.x, c.y, c.z, [(0.16 * h, 0.0), (0.14 * h, 0.2 * h), (0.11 * h, 0.45 * h), (0.13 * h, 0.72 * h), (0.05 * h, 0.82 * h),
                           (0.065 * h, 0.9 * h), (0.0, h)], mat, 7)

def col(F, s, d, z0, z1, r, mat):
    c = F.P(s, d, 0)
    lathe(c.x, c.y, z0 + 0.3, [(r, 0.0), (r * 0.92, z1 - z0 - 0.75)], mat, 8)
    lathe(c.x, c.y, z0, [(r * 1.45, 0.0), (r * 1.45, 0.16), (r * 1.1, 0.3)], "stone", 8)                    # white base
    lathe(c.x, c.y, z1 - 0.45, [(r * 0.95, 0.0), (r * 1.35, 0.3), (r * 1.6, 0.45)], "stone", 8)            # white capital

# ---------------------------------------------------------------- bays
def mosaic_figs(F, c, R, zs, d):
    """figurative lunette: framed gold field dominating, a city / hill silhouette behind, a few dark haloed figures of
    varying height and stance, the central one larger"""
    band(F, rounded(c, R, zs, 14)[::-1], 0.2, d - 0.01, "frame")
    rnd = random.Random(int(c * 10 + zs * 7))
    # background architecture: low walls, an arcade and a small dome on either side
    for sd in (-1, 1):
        x0 = c + sd * R * 0.35; x1 = c + sd * R * 0.8; hh = min(1.6, R * 0.32)
        poly = [(min(x0, x1), zs + 0.05), (max(x0, x1), zs + 0.05), (max(x0, x1), zs + hh), (min(x0, x1), zs + hh)]
        flat(F, poly, d - 0.005, "archi")
        dc = (x0 + x1) / 2
        flat(F, [(dc + 0.5 * R * 0.18 * math.cos(math.pi * i / 8), zs + hh + 0.5 * R * 0.18 * math.sin(math.pi * i / 8)) for i in range(9)], d - 0.005, "archi")
    fig_x = [0.0]; k = 1
    while True:
        added = False
        for sd in (-1, 1):
            x = sd * k * 1.45
            if math.sqrt(max(R * R - x * x, 0)) - 0.5 >= 1.2: fig_x.append(x); added = True
        if not added: break
        k += 1
    robes = ["robeB", "robeR", "robeN", "robeW"]
    for n, x in enumerate(fig_x):
        avail = math.sqrt(max(R * R - x * x, 0)) - 0.45
        h = min(2.8 if x == 0 else 1.9 * (0.82 + 0.18 * rnd.random()), avail * 0.9); sc = h / 1.9; xc = c + x; zb = zs + 0.08
        lean = (rnd.random() - 0.5) * 0.25 * sc
        flat(F, circle(xc + lean, zb + 1.62 * sc, 0.28 * sc, 12), d - 0.01, "halo")
        flat(F, [(xc - 0.36 * sc, zb), (xc + 0.36 * sc, zb), (xc + 0.22 * sc + lean, zb + 1.45 * sc), (xc - 0.26 * sc + lean, zb + 1.45 * sc)], d - 0.02, robes[(n + (1 if x == 0 else 0)) % 4] if x != 0 else "robeB")
        if rnd.random() < 0.6:                                                  # a raised arm / staff
            flat(F, [(xc + 0.2 * sc, zb + 1.2 * sc), (xc + 0.5 * sc, zb + 1.55 * sc), (xc + 0.42 * sc, zb + 1.62 * sc), (xc + 0.12 * sc, zb + 1.3 * sc)], d - 0.02, "robeW" if n % 2 else "robeN")
        flat(F, circle(xc + lean, zb + 1.62 * sc, 0.14 * sc, 10), d - 0.02, "skin")

def bay_lower(F, c, W, zs, deep=2.25, door=True):
    """deep round-arched portal between piers: four receding orders, columns in two tiers on the jambs,
    a figurative gold mosaic lunette, below it a marble wall with small arched windows and the bronze door"""
    w = W / 2 - 0.8
    carve(lambda: fbox(F, c - W / 2 + 0.8, c + W / 2 - 0.8, -0.8, 0.0, G - 0.3, TER - 0.4, "marble"),
          lambda: prism(F, opening(rounded(c, w, zs, 12), G - 1.0), -1.5, 1.0, "marble"))
    band(F, rounded(c, w, zs, 12), 0.3, -0.81, "stone", proud=0.06)
    for k, (dd, rr) in enumerate(((0.01, w - 0.4), (0.76, w - 0.8), (1.51, w - 1.2))):
        path = opening(rounded(c, rr, zs, 12), G - 0.3)
        band(F, path, 0.4, dd, "stone" if k != 1 else "marble")
        soffit(F, path, dd, dd + 0.75, "recess")
    inner = w - 1.2
    flat(F, opening(rounded(c, inner, zs, 12), G - 0.3), deep, "recess")
    flat(F, [(c - inner + 0.05, G - 0.2), (c + inner - 0.05, G - 0.2), (c + inner - 0.05, zs - 2.1), (c - inner + 0.05, zs - 2.1)], deep - 0.01, "panelwall")
    flat(F, rounded(c, inner - 0.05, zs + 0.02, 12), deep - 0.02, "mosaic")
    mosaic_figs(F, c, inner - 0.05, zs + 0.02, deep - 0.02)
    fbox(F, c - inner, c + inner, deep - 0.3, deep, zs - 0.4, zs, "stone")
    nwin = max(2, int(inner * 2 / 1.0))
    for k in range(nwin):
        xx = c - inner + (2 * inner) * (k + 0.5) / nwin
        flat(F, opening(rounded(xx, 0.24, zs - 0.95, 6), zs - 2.0), deep - 0.02, "dark")
    if door:
        flat(F, [(c - 1.6, G), (c + 1.6, G), (c + 1.6, G + 4.6), (c - 1.6, G + 4.6)], deep - 0.02, "bronze")
        band(F, [(c + 1.6, G), (c + 1.6, G + 4.6), (c - 1.6, G + 4.6), (c - 1.6, G)], 0.42, deep - 0.03, "sculpt", proud=0.14)
        for e in (-1, 1):
            for j in range(2):
                col(F, c + e * (2.35 + 0.45 * j), deep - 0.35, G - 0.1, G + 4.9, 0.12, COLS[(j + (e > 0)) % 6])
    for sd in (-1, 1):
        for t, (zz0, zz1) in enumerate(((G - 0.1, G + 4.8), (G + 5.1, zs - 0.35))):
            for j, dd in enumerate((-0.55, 0.2, 0.95, 1.7)):
                col(F, c + sd * (w - 0.22 - 0.4 * j), dd, zz0, zz1, 0.16, COLS[(j + t + (1 if sd > 0 else 0)) % 6])

def pier(F, s, wdt, front=-1.8):
    fbox(F, s - wdt / 2, s + wdt / 2, front, 0.0, G - 0.3, TER - 0.4, "marble")
    for t, (zz0, zz1) in enumerate(((G - 0.1, G + 4.8), (G + 5.1, G + 10.6))):
        for j, ds in enumerate((-wdt / 2 + 0.25, 0.0, wdt / 2 - 0.25)):
            col(F, s + ds, front - 0.22, zz0, zz1, 0.19, COLS[(j + 2 * t) % 6])
        for j, ds in enumerate((-wdt / 2 + 0.45, wdt / 2 - 0.45)):
            col(F, s + ds, front + 0.25, zz0, zz1, 0.17, COLS[(j + t + 1) % 6])
    fbox(F, s - wdt / 2 - 0.1, s + wdt / 2 + 0.1, front - 0.45, 0.0, G + 4.8, G + 5.1, "stone")
    fbox(F, s - wdt / 2 - 0.1, s + wdt / 2 + 0.1, front - 0.45, 0.0, G + 10.6, G + 10.9, "stone")
    for (z0_, z1_) in ((G + 11.3, G + 12.9), (G + 13.2, TER - 1.1)):           # carved relief plaques
        fbox(F, s - wdt / 2 + 0.2, s + wdt / 2 - 0.2, front - 0.08, front, z0_, z1_, "sculpt")
        blob(F.P(s, front - 0.1, (z0_ + z1_) / 2), wdt * 0.25, 0.06, (z1_ - z0_) * 0.33, "sculpt", 6, 4)

def bay_upper(F, c, W, zs, kind):
    """upper order: round arch with mosaic / glazed lunette, ogee crest with gilt crockets and a statue"""
    w = W / 2 - 1.0
    crest_top = zs + w + (6.6 if kind == "centre" else 6.0)
    edge = W / 2 - 0.8
    crest = ogee(c, edge, zs + 0.6, crest_top - zs - 0.6, 12)
    prism(F, opening(crest, TER), 0.4, 1.4, "marble")
    hd = rounded(c, w, zs, 14)
    band(F, hd, 0.55, 0.38, "stone", proud=0.12)
    band(F, [(x, z) for x, z in crest], 0.3, 0.38, "stone", proud=0.12)
    if kind == "centre":
        flat(F, hd[::-1], 0.36, "glass")
        for k in range(1, 12):
            a = math.pi * k / 12
            band(F, [(c + 0.4 * math.cos(a), zs + 0.4 * math.sin(a)), (c + w * math.cos(a), zs + w * math.sin(a))], 0.06, 0.36, "stone", proud=0.05)
        for rr in (w * 0.4, w * 0.72): band(F, rounded(c, rr, zs, 12), 0.07, 0.36, "stone", proud=0.05)
        # winged lion of St Mark (gilt) on a blue starry field in the crest, St Mark with two angels on top
        # blue starry field filling the crest between the arch extrados and the ogee
        topc = ogee(c, edge - 0.45, zs + 0.6, crest_top - zs - 1.3, 24)[:25]           # right half: springing -> apex
        Rx = w + 0.62
        field = []
        for (x, z) in topc:
            dx_ = x - c
            zb_ = zs + math.sqrt(max(Rx * Rx - dx_ * dx_, 0.0)) + 0.1
            if z > zb_ + 0.15: field.append((dx_, z, zb_))
        right = [(c + dx_, z) for dx_, z, _ in field]
        bottom = [(c + dx_, zb_) for dx_, _, zb_ in field]
        poly = right + [(2 * c - x, z) for x, z in reversed(right)][1:] + [(2 * c - x, z) for x, z in bottom] [::1][:0]
        lower = [(c - dx_, zb_) for dx_, _, zb_ in field] [::-1]
        poly = right + [(2 * c - x, z) for x, z in right[::-1][1:]] + [(c - dx_, zb_) for dx_, _, zb_ in field][1:] + [(c + dx_, zb_) for dx_, _, zb_ in field][::-1][:-1]
        flat(F, poly, 0.37, "blue")
        L0 = zs + Rx + 0.2
        sc = 1.35
        lion = [(-1.0, -0.35), (-0.85, -0.35), (-0.8, -0.05), (0.45, -0.05), (0.5, -0.35), (0.65, -0.35), (0.7, 0.05), (0.95, 0.25), (1.05, 0.6),
                (0.9, 0.78), (0.62, 0.62), (0.5, 0.35), (-0.6, 0.3), (-0.85, 0.5), (-1.15, 0.25)]
        flat(F, [(c + x * sc, L0 + 1.2 + z * sc) for x, z in lion], 0.35, "gold")
        wing = [(-0.3, 0.3), (0.35, 0.33), (0.2, 1.0), (-0.1, 1.25), (-0.55, 1.05), (-0.75, 0.7)]
        flat(F, [(c + x * sc, L0 + 1.2 + z * sc) for x, z in wing], 0.34, "gold")
        statue(F.P(c, 0.7, crest_top - 0.1), 2.8, "gold")
        for sd in (-1, 1): statue(F.P(c + sd * 1.6, 0.7, crest_top - 2.2), 1.6, "sculpt")
    else:
        flat(F, hd[::-1], 0.36, "mosaic")
        mosaic_figs(F, c, w - 0.05, zs, 0.36)
        statue(F.P(c, 0.7, crest_top - 0.1), 2.3, "sculpt")
    for k in range(1, 12, 2):                                                 # chunky marble leaf crockets
        for side in (0, 1):
            i = k if side == 0 else len(crest) - 1 - k
            if 0 < i < len(crest) - 1:
                x, z = crest[i]
                blob(F.P(x, 0.45, z + 0.38), 0.36, 0.3, 0.42, "stone", 6, 4)
    for sd in (-1, 1):
        statue(F.P(c + sd * (edge - 0.3), 0.7, zs + 0.6), 1.5, "sculpt")

def tabernacle(F, s, top=G + 34.0, k=1.0):
    hw = 0.8 * k
    fbox(F, s - hw, s + hw, -0.5, 1.4, TER, G + 28.4, "marble")
    flat(F, [(s - 0.5 * k, G + 21.0), (s + 0.5 * k, G + 21.0), (s + 0.5 * k, G + 26.0), (s, G + 26.8), (s - 0.5 * k, G + 26.0)], -0.51, "dark")
    statue(F.P(s, -0.85, G + 21.0), 2.6 * k, "sculpt")
    for e in (-1, 1):
        col(F, s + e * (hw - 0.18), -0.62, G + 20.8, G + 27.0, 0.11, "colA")
    c = F.P(s, 0.45, 0)
    fbox(F, s - hw - 0.15, s + hw + 0.15, -0.65, 1.55, G + 28.4, G + 28.8, "stone")
    lathe(c.x, c.y, G + 28.8, [(0.85 * k + 0.1, 0.0), (0.75 * k + 0.1, 0.9), (0.55 * k, 1.1), (0.1, top - G - 28.8)], "marble", 8)
    lathe(c.x, c.y, top - 0.1, [(0.2, 0.0), (0.25, 0.25), (0.0, 0.55)], "gold", 6)

def balustrade(F, s0, s1, d, z):
    fbox(F, s0, s1, d, d + 0.4, z, z + 0.15, "stone")
    n = int((s1 - s0) / 0.42)
    for k in range(n):
        ss = s0 + (s1 - s0) * (k + 0.5) / n
        fbox(F, ss - 0.1, ss + 0.1, d + 0.1, d + 0.3, z + 0.15, z + 0.85, "stone")
    fbox(F, s0, s1, d - 0.05, d + 0.45, z + 0.85, z + 1.0, "stone")

def horse(F, s, d, z, step):
    """one of the four horses, ~2.3 m to the ears, facing out of the facade, one foreleg raised"""
    o = F.out(); a = F.a
    K = 1.25
    P = lambda along, side, up: F.P(s + side * K, d, 0) + o * along * K + Vector((0, 0, z + up * K))
    blob(P(0.0, 0, 1.35), 0.42 * K, 0.42 * K, 0.42 * K, "horse", 8, 6)
    blob(P(0.55, 0, 1.4), 0.5 * K, 0.38 * K, 0.42 * K, "horse", 8, 6)                      # body: chest + barrel
    blob(P(-0.55, 0, 1.38), 0.45 * K, 0.38 * K, 0.4 * K, "horse", 8, 6)
    tube([P(0.85, 0, 1.55), P(1.05, 0, 1.95), P(1.18, 0, 2.2)], 0.22 * K, "horse", 7)   # neck
    blob(P(1.35, 0, 2.15), 0.33 * K, 0.16 * K, 0.18 * K, "horse", 7, 5)                       # head
    for e in (-0.09, 0.09): tube([P(1.18, e, 2.3), P(1.2, e, 2.45)], 0.04 * K, "horse", 4)
    for sd in (-0.2, 0.2):
        tube([P(-0.75, sd, 1.15), P(-0.8, sd, 0.6), P(-0.7, sd, 0.05)], 0.09 * K, "horse", 5)
        if sd > 0 and step:
            tube([P(0.75, sd, 1.15), P(1.05, sd, 0.85), P(0.95, sd, 0.55)], 0.085 * K, "horse", 5)
        else:
            tube([P(0.75, sd, 1.15), P(0.78, sd, 0.6), P(0.8, sd, 0.05)], 0.085 * K, "horse", 5)
    tube([P(-1.0, 0, 1.5), P(-1.2, 0, 1.0), P(-1.15, 0, 0.6)], 0.08 * K, "horse", 5)

# ---------------------------------------------------------------- the west front
new_part()
FF = Frame((0, 55, 0), (0, -1, 0), (1, 0, 0))
BW = [9.6, 9.6, 13.6, 9.6, 9.6]
bounds = [1.5]
for w_ in BW: bounds.append(bounds[-1] + w_)
centres = [(bounds[i] + bounds[i + 1]) / 2 for i in range(5)]
for i, c in enumerate(centres):
    zs = G + 9.6 if i != 2 else G + 9.0
    bay_lower(F=FF, c=c, W=BW[i], zs=zs)
for i, s in enumerate(bounds):
    pier(FF, s, 2.0 if i in (0, 5) else 1.6)
# terrace slab with a frieze, balustrade
fbox(FF, 0.5, 54.5, -2.1, 4.0, TER - 0.4, TER, "stone")
fbox(FF, 0.5, 54.5, -2.0, -1.8, TER - 1.0, TER - 0.4, "marble")
balustrade(FF, 0.6, 54.4, -2.05, TER)
for k, ds in enumerate((-3.75, -1.25, 1.25, 3.75)):
    fbox(FF, AXIS + ds - 0.75, AXIS + ds + 0.75, -1.4, 0.6, TER, TER + 0.8, "marble")
    horse(FF, AXIS + ds, -0.4, TER + 0.8, k in (0, 2))
# upper order
for i, c in enumerate(centres):
    bay_upper(FF, c, BW[i], G + 19.6, "centre" if i == 2 else "side")
for i, s in enumerate(bounds): tabernacle(FF, s, G + 35.0 if i in (0, 5) else G + 34.0, 1.3 if i in (0, 5) else 1.0)
front = end_part("front", weight=2.6)

# ---------------------------------------------------------------- sides, Porta della Carta, body, domes
new_part()
FSo = Frame((0, 0, 0), (1, 0, 0), (0, 1, 0))
FNo = Frame((24, 55, 0), (-1, 0, 0), (0, -1, 0))
for F in (FSo, FNo):
    for k, (c, W) in enumerate(((6.4, 9.6), (16.0, 9.6))):
        bay_lower(F, c, W, G + 9.6, deep=1.8, door=(F is FNo and k == 0))
        bay_upper(F, c, W, G + 19.6, "side")
    for s in (1.6, 11.2, 20.8):
        pier(F, s, 1.6)
        tabernacle(F, s)
    fbox(F, 0.8, 22.4, -2.1, 3.0, TER - 0.4, TER, "stone")
    balustrade(F, 0.8, 22.4, -2.05, TER)
# Porta della Carta (Gothic gate between the Basilica and the Doge's Palace)
PC = Frame((22.35, -3.6, 0), (0, -1, 0), (1, 0, 0))
PCW = 6.3
def pc_cut():
    prism(PC, opening(pointed(PCW / 2, 1.5, G + 5.6, 2.6, 10), G - 1.0), -1.0, 0.8, "dark")
    prism(PC, opening(pointed(PCW / 2, 1.55, G + 13.4, 2.3, 10), G + 9.4), -1.0, 0.45, "glass")
carve(lambda: fbox(PC, 0.0, PCW, 0.0, 1.2, G - 0.3, G + 16.6, "stone"), pc_cut)
for k in range(1, 4):
    fbox(PC, PCW / 2 - 1.55 + k * 0.775 - 0.06, PCW / 2 - 1.55 + k * 0.775 + 0.06, 0.25, 0.45, G + 9.4, G + 14.2, "stone")
for (fu, fz) in ((PCW / 2 - 0.78, G + 14.6), (PCW / 2 + 0.78, G + 14.6), (PCW / 2, G + 15.2)):
    fl = foil(fu, fz, 0.4, 4, 24, 0.3); band(PC, fl + [fl[0]], 0.07, 0.25, "stone", proud=0.06)
band(PC, pointed(PCW / 2, 1.5, G + 5.6, 2.6, 10), 0.3, 0.0, "stone", proud=0.15)
band(PC, pointed(PCW / 2, 1.55, G + 13.4, 2.3, 10), 0.25, 0.0, "stone", proud=0.12)
figure_c = PC.P(PCW / 2 - 0.7, -0.35, G + 8.4)
lathe(figure_c.x, figure_c.y, figure_c.z, [(0.42, 0.0), (0.38, 0.75), (0.27, 1.25), (0.12, 1.4), (0.15, 1.6), (0.0, 1.78)], "gilt", 8)   # Doge Foscari kneeling
lion = [(-0.9, 0.0), (-0.75, 0.0), (-0.7, 0.3), (0.35, 0.3), (0.4, 0.0), (0.55, 0.0), (0.6, 0.4), (0.85, 0.55), (0.9, 0.85), (0.72, 1.0), (0.5, 0.85), (0.4, 0.62), (-0.55, 0.6), (-0.8, 0.8), (-1.0, 0.5)]
flat(PC, [(PCW / 2 + 0.7 - 1.5 * x, G + 8.4 + 1.5 * z) for x, z in lion], -0.03, "gilt")
blob(PC.P(PCW / 2 + 0.7, -0.15, G + 9.0), 0.95, 0.2, 0.45, "gilt", 8, 5)
blob(PC.P(PCW / 2 + 1.75, -0.2, G + 9.55), 0.38, 0.22, 0.4, "gilt", 6, 4)
fbox(PC, PCW / 2 - 1.8, PCW / 2 + 1.8, -0.3, 0.0, G + 8.2, G + 8.4, "stone")
for s in (0.65, PCW - 0.65):
    fbox(PC, s - 0.65, s + 0.65, -0.5, 0.6, G - 0.3, G + 18.4, "stone")
    for zz in (G + 4.0, G + 8.8, G + 13.4):
        flat(PC, [(s - 0.4, zz), (s + 0.4, zz), (s + 0.4, zz + 2.6), (s, zz + 3.0), (s - 0.4, zz + 2.6)], -0.51, "dark")
        statue(PC.P(s, -0.75, zz), 2.3)
    c3 = PC.P(s, 0.05, 0)
    lathe(c3.x, c3.y, G + 18.4, [(0.6, 0.0), (0.45, 0.4), (0.08, 4.6)], "stone", 8)
    for k in range(4): blob(PC.P(s, -0.3, G + 19.2 + k * 0.85), 0.16, 0.16, 0.12, "stone", 5, 3)
crest = ogee(PCW / 2, 2.6, G + 16.6, 4.2, 12)
prism(PC, opening(crest, G + 16.0), 0.1, 0.9, "stone")
band(PC, crest, 0.2, 0.1, "stone", proud=0.1)
for k in range(1, 12, 2):
    for idx in (k, len(crest) - 1 - k):
        x, z = crest[idx]; blob(PC.P(x, 0.25, z + 0.25), 0.22, 0.2, 0.26, "stone", 5, 3)
ring = circle(PCW / 2, G + 18.4, 0.95, 18); band(PC, ring + [ring[0]], 0.14, 0.05, "stone", proud=0.1)
flat(PC, circle(PCW / 2, G + 18.4, 0.95, 18), 0.04, "blue")
blob(PC.P(PCW / 2, 0.0, G + 18.4), 0.5, 0.15, 0.6, "gold", 6, 4)
statue(PC.P(PCW / 2, 0.5, G + 20.7), 3.2, "sculpt")
# the Tetrarchs: two embracing porphyry pairs on the treasury corner by the gate
for (tx, ty) in ((22.0, -3.25), (22.0, -2.15)):
    box(tx - 0.42, tx + 0.4, ty - 0.55, ty + 0.55, G + 2.3, G + 2.6, "stone")
    for e in (-0.22, 0.22):
        lathe(tx, ty + e, G + 2.6, [(0.24, 0.0), (0.21, 0.6), (0.24, 0.95), (0.1, 1.05), (0.13, 1.2), (0.0, 1.33)], "colC", 8)
# Pilastri Acritani, Pietra del Bando
for (px, py) in ((12.5, -7.0), (18.5, -7.4)):
    box(px - 0.6, px + 0.6, py - 0.6, py + 0.6, G, G + 0.35, "stone")
    box(px - 0.5, px + 0.5, py - 0.5, py + 0.5, G + 0.35, G + 4.3, "sculpt"); box(px - 0.66, px + 0.66, py - 0.66, py + 0.66, G + 4.3, G + 4.75, "sculpt")
    box(px - 0.78, px + 0.78, py - 0.78, py + 0.78, G + 4.75, G + 5.0, "sculpt")
    for (nx, ny) in ((1, 0), (-1, 0), (0, 1), (0, -1)):                  # framed panels with a continuous vine scroll
        t_ = Vector((-ny, nx, 0)); nrm = Vector((nx, ny, 0)); f0 = Vector((px, py, 0)) + nrm * 0.5
        for e in (-1, 1): obox(f0 + t_ * e * 0.42 + Vector((0, 0, G + 2.3)), t_, Vector((0, 0, 1)), nrm, 0.06, 1.95, 0.05, "sculpt")
        pts = [f0 + nrm * 0.05 + t_ * 0.22 * math.sin(k * 0.9) + Vector((0, 0, G + 0.6 + k * 0.22)) for k in range(17)]
        tube(pts, 0.045, "sculpt", 5)
        for k in range(1, 16, 2):
            q = pts[k] + t_ * (0.12 if math.sin(k * 0.9) < 0 else -0.12)
            blob(q, 0.1 if nx else 0.05, 0.05 if nx else 0.1, 0.09, "sculpt", 5, 3)
lathe(1.2, -1.4, G, [(0.55, 0.0), (0.5, 0.9), (0.55, 1.0)], "colC", 10)

body = end_part("sides", weight=1.6)
new_part()
# body: brick masses behind the decorated fronts, roofs, apse, treasury
box(2.4, 76.0, 3.0, 52.0, G - 0.3, G + 18.0, "brick")
box(29.5, 61.0, -9.9, 3.0, G - 0.3, G + 14.0, "marble")
box(22.4, 29.5, -3.6, 3.0, G - 0.3, G + 14.0, "marble")
box(62.0, 90.0, 29.5, 43.0, G - 0.3, G + 14.0, "brick")
lathe(76.0, AXIS, G - 0.3, [(6.5, 0.0), (6.5, 18.3)], "brick", 16)
lathe(76.0, AXIS, G + 18.0, [(6.6, 0.0), (0.1, 2.4)], "lead", 16)
hip_roof(2.4, 76.0, 3.0, 52.0, G + 18.0, 8, "lead", 0.2)
hip_roof(29.5, 61.0, -9.9, 3.0, G + 14.0, 10, "lead", 0.2)
# vault roofs on the arms of the Greek cross (lead barrels) and the drums + domes
def barrel(x0, x1, y0, y1, z0, along_x):
    n = 10
    if along_x:
        rc = (y1 - y0) / 2; yc = (y0 + y1) / 2
        prof = [(yc + rc * math.cos(math.pi * i / n), z0 + 0.55 * rc * math.sin(math.pi * i / n)) for i in range(n + 1)]
        F = Frame((x1, 0, 0), (0, 1, 0), (-1, 0, 0))
        prism(F, prof + [(yc - rc, z0 - 0.5), (yc + rc, z0 - 0.5)], 0.0, x1 - x0, "lead")
    else:
        rc = (x1 - x0) / 2; xc = (x0 + x1) / 2
        prof = [(xc + rc * math.cos(math.pi * i / n), z0 + 0.55 * rc * math.sin(math.pi * i / n)) for i in range(n + 1)]
        F = Frame((0, y0, 0), (1, 0, 0), (0, 1, 0))
        prism(F, prof + [(xc - rc, z0 - 0.5), (xc + rc, z0 - 0.5)], 0.0, y1 - y0, "lead")
barrel(4.0, 70.0, 20.5, 34.5, G + 20.5, True)
barrel(29.0, 43.0, 3.0, 52.0, G + 20.5, False)
mass = end_part("mass", weight=0.3)
new_part()
DOMES = [(19.0, AXIS, 6.0, 0.0), (36.0, AXIS, 6.8, 1.4), (54.0, AXIS, 6.0, 0.0), (36.0, 8.5, 5.6, -0.6), (36.0, 46.5, 5.6, -0.6)]
for (dx, dy, r, extra) in DOMES:
    z0 = G + 22.5
    lathe(dx, dy, z0, [(r, 0.0), (r, 6.4 + extra * 0.5)], "brick", 24)
    zd = z0 + 6.4 + extra * 0.5
    for k in range(12):                                                       # drum windows
        a = 2 * math.pi * (k + 0.5) / 12
        Fd = Frame((dx + (r + 0.02) * math.cos(a), dy + (r + 0.02) * math.sin(a), 0), (-math.sin(a), math.cos(a), 0), (-math.cos(a), -math.sin(a), 0))
        flat(Fd, opening(rounded(0, 0.5, zd - 2.3, 6), zd - 4.6), 0.0, "dark")
    lathe(dx, dy, zd, [(r + 0.3, 0.0), (r + 0.3, 0.35)], "stone", 24)
    hb = 11.8 + extra
    prof = [(r * fr, hb * fh) for fr, fh in ((1.03, 0.0), (1.08, 0.12), (1.09, 0.25), (1.05, 0.40), (0.95, 0.55), (0.80, 0.68),
                                             (0.60, 0.80), (0.40, 0.89), (0.22, 0.96), (0.15, 1.0))]
    lathe(dx, dy, zd + 0.35, prof, "domelead", 24)
    zt = zd + 0.35 + hb
    rt = prof[-1][0]
    lathe(dx, dy, zt - 0.2, [(1.05, 0.0), (1.05, 1.7)], "domelead", 10)
    for k in range(8):
        a = 2 * math.pi * k / 8
        Fd = Frame((dx + 1.07 * math.cos(a), dy + 1.07 * math.sin(a), 0), (-math.sin(a), math.cos(a), 0), (-math.cos(a), -math.sin(a), 0))
        flat(Fd, opening(rounded(0, 0.22, zt + 0.95, 5), zt + 0.2), 0.0, "dark")
    lathe(dx, dy, zt + 1.5, [(1.25, 0.0), (1.1, 0.4), (0.6, 1.2), (0.12, 1.6)], "domelead", 10)
    lathe(dx, dy, zt + 3.05, [(0.08, 0.0), (0.6, 0.15), (0.72, 0.65), (0.6, 1.15), (0.08, 1.3)], "gold", 10)
    box(dx - 0.11, dx + 0.11, dy - 0.11, dy + 0.11, zt + 4.3, zt + 6.8, "gold")
    box(dx - 0.11, dx + 0.11, dy - 0.8, dy + 0.8, zt + 5.6, zt + 5.82, "gold")
    box(dx - 0.11, dx + 0.11, dy - 0.55, dy + 0.55, zt + 6.2, zt + 6.38, "gold")
body = end_part("domes", weight=1.2)

finish(OUT, ATLAS, ATLAS_RES)

"""Shared helpers for baked-atlas landmarks (exec()d by the build scripts): node-material helpers, weathering,
geometry primitives, relief/tube carving.  Expects the caller to define ARCH_CZ / R_ARCH (may be dummies)."""
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

CREAM0 = (0.74, 0.71, 0.65); CREAM1 = (0.84, 0.81, 0.75)

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
    c = joints(nt, c, uv_face(nt, coord, True), 1.25, 1.25, 0.0, mortar=0.016, jcol=(0.24, 0.23, 0.21), var=0.30)
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

def m_istrian_plain(nt, coord, k=1.0, streak=0.5, ao_min=0.30):
    return weather(nt, coord, stone_base(nt, coord, k), streak=streak, ao_min=ao_min)

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
    c = mix(nt, (0.60, 0.61, 0.60, 1), (0.69, 0.70, 0.68, 1), base.outputs["Fac"])
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
    c = mix(nt, (0.10, 0.13, 0.11, 1), (0.14, 0.17, 0.15, 1), base.outputs["Fac"])
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

"""Red 4.5 m centre-console runabout with a 40 hp outboard.
Built entirely by script. Coordinates: +X forward (transom at x=0, bow at x=L), +Y port, +Z up, waterline z=0.
Run: python build_boat.py  -> boat.blend
"""
import bpy, bmesh, math, sys, os
from mathutils import Vector, Matrix, Euler

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "boat.blend")
L = 4.44  # hull length (LOA 4.54 with engine overhang)

# ---------------------------------------------------------------- reset
bpy.ops.wm.read_factory_settings(use_empty=True)
scene = bpy.context.scene
col = bpy.data.collections.new("Speedboat")
scene.collection.children.link(col)


def link(ob):
    col.objects.link(ob)
    return ob


# ---------------------------------------------------------------- materials
def principled(name, color, rough=0.4, metal=0.0, coat=0.0, coat_rough=0.03, alpha=1.0,
               transmission=0.0, ior=1.45, emission=None, estr=0.0):
    m = bpy.data.materials.new(name)
    m.use_nodes = True
    nt = m.node_tree
    p = nt.nodes["Principled BSDF"]
    p.inputs["Base Color"].default_value = (*color, 1)
    p.inputs["Roughness"].default_value = rough
    p.inputs["Metallic"].default_value = metal
    p.inputs["Coat Weight"].default_value = coat
    p.inputs["Coat Roughness"].default_value = coat_rough
    p.inputs["Transmission Weight"].default_value = transmission
    p.inputs["IOR"].default_value = ior
    if emission:
        p.inputs["Emission Color"].default_value = (*emission, 1)
        p.inputs["Emission Strength"].default_value = estr
    return m


def add_bump(m, scale=60.0, strength=0.15, detail=4.0, dist=0.002, voronoi=False):
    nt = m.node_tree
    p = nt.nodes["Principled BSDF"]
    tc = nt.nodes.new("ShaderNodeTexCoord")
    if voronoi:
        tex = nt.nodes.new("ShaderNodeTexVoronoi")
        tex.inputs["Scale"].default_value = scale
        out = tex.outputs["Distance"]
    else:
        tex = nt.nodes.new("ShaderNodeTexNoise")
        tex.inputs["Scale"].default_value = scale
        tex.inputs["Detail"].default_value = detail
        out = tex.outputs["Fac"]
    nt.links.new(tc.outputs["Object"], tex.inputs["Vector"])
    b = nt.nodes.new("ShaderNodeBump")
    b.inputs["Strength"].default_value = strength
    b.inputs["Distance"].default_value = dist
    nt.links.new(out, b.inputs["Height"])
    nt.links.new(b.outputs["Normal"], p.inputs["Normal"])
    if "Coat Normal" in p.inputs and m.name.startswith("Gel"):
        pass
    return m


RED = (0.42, 0.012, 0.016)
M = {}
# hull topsides: deep red gelcoat with a white accent stripe driven by the 'sheer_d' attribute
gel_red = principled("GelRed", RED, rough=0.22, coat=1.0, coat_rough=0.02)
nt = gel_red.node_tree
p = nt.nodes["Principled BSDF"]
attr = nt.nodes.new("ShaderNodeAttribute"); attr.attribute_name = "sheer_d"
# stripe between 0.075 and 0.093 m below the sheer, thin pinstripe 0.105-0.111
def band(lo, hi):
    a = nt.nodes.new("ShaderNodeMath"); a.operation = "GREATER_THAN"; a.inputs[1].default_value = lo
    b = nt.nodes.new("ShaderNodeMath"); b.operation = "LESS_THAN"; b.inputs[1].default_value = hi
    nt.links.new(attr.outputs["Fac"], a.inputs[0]); nt.links.new(attr.outputs["Fac"], b.inputs[0])
    c = nt.nodes.new("ShaderNodeMath"); c.operation = "MULTIPLY"
    nt.links.new(a.outputs[0], c.inputs[0]); nt.links.new(b.outputs[0], c.inputs[1])
    return c
b1 = band(0.070, 0.092); b2 = band(0.103, 0.109)
mx = nt.nodes.new("ShaderNodeMath"); mx.operation = "MAXIMUM"
nt.links.new(b1.outputs[0], mx.inputs[0]); nt.links.new(b2.outputs[0], mx.inputs[1])
mix = nt.nodes.new("ShaderNodeMix"); mix.data_type = "RGBA"
mix.inputs["A"].default_value = (*RED, 1); mix.inputs["B"].default_value = (0.86, 0.86, 0.84, 1)
nt.links.new(mx.outputs[0], mix.inputs["Factor"])
nt.links.new(mix.outputs["Result"], p.inputs["Base Color"])
# very slight orange-peel waviness in the gelcoat
add_bump(gel_red, scale=9.0, strength=0.025, detail=2.0, dist=0.01)
M["red"] = gel_red
M["white_gel"] = add_bump(principled("GelWhite", (0.80, 0.80, 0.78), rough=0.18, coat=0.8), scale=9, strength=0.02, dist=0.01)
M["bottom"] = add_bump(principled("GelBottom", (0.74, 0.75, 0.74), rough=0.3, coat=0.4), scale=6, strength=0.04, dist=0.01)
nonskid = principled("NonSkid", (0.70, 0.71, 0.70), rough=0.62)
add_bump(nonskid, scale=900.0, strength=0.55, detail=1.0, dist=0.0015, voronoi=True)
M["nonskid"] = nonskid
M["vinyl"] = add_bump(principled("Vinyl", (0.72, 0.70, 0.66), rough=0.45, coat=0.2, coat_rough=0.3), scale=380, strength=0.25, detail=6, dist=0.0008)
M["vinyl_dark"] = add_bump(principled("VinylGrey", (0.10, 0.10, 0.11), rough=0.5), scale=380, strength=0.25, detail=6, dist=0.0008)
M["steel"] = principled("Stainless", (0.80, 0.80, 0.80), rough=0.13, metal=1.0)
M["alu"] = principled("Aluminium", (0.55, 0.56, 0.57), rough=0.32, metal=1.0)
M["rubber"] = principled("Rubber", (0.025, 0.025, 0.025), rough=0.7)
M["black_gloss"] = principled("BlackGloss", (0.012, 0.012, 0.013), rough=0.12, coat=1.0, coat_rough=0.02)
M["black_satin"] = principled("BlackSatin", (0.02, 0.02, 0.022), rough=0.35)
M["grey_cowl"] = principled("CowlGrey", (0.05, 0.052, 0.056), rough=0.3, coat=0.5)
M["lower_unit"] = principled("LowerUnit", (0.03, 0.03, 0.032), rough=0.35, coat=0.3)
M["acrylic"] = principled("Acrylic", (0.30, 0.32, 0.33), rough=0.02, transmission=1.0, ior=1.49)
M["white_dec"] = principled("DecalWhite", (0.85, 0.85, 0.85), rough=0.3)
M["gauge_face"] = principled("GaugeFace", (0.01, 0.01, 0.012), rough=0.25)
M["gauge_glass"] = principled("GaugeGlass", (0.8, 0.8, 0.8), rough=0.0, transmission=1.0, ior=1.5)
M["red_lens"] = principled("NavRed", (0.6, 0.0, 0.0), rough=0.1, emission=(1, 0.02, 0.02), estr=0.6)
M["green_lens"] = principled("NavGreen", (0.0, 0.5, 0.1), rough=0.1, emission=(0.02, 1, 0.2), estr=0.6)
M["white_lens"] = principled("NavWhite", (0.9, 0.9, 0.9), rough=0.1, emission=(1, 1, 0.95), estr=0.3)
M["needle"] = principled("Needle", (0.9, 0.15, 0.02), rough=0.3, emission=(1, 0.2, 0.0), estr=0.5)
M["prop"] = principled("Prop", (0.62, 0.63, 0.64), rough=0.25, metal=1.0)


# ---------------------------------------------------------------- generic helpers
def mesh_from_bm(name, bm, mats, smooth_angle=None):
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    for m in mats:
        me.materials.append(m)
    if smooth_angle is not None:
        me.shade_smooth()
        me.set_sharp_from_angle(angle=math.radians(smooth_angle))
    ob = link(bpy.data.objects.new(name, me))
    return ob


def loft_bm(sections, closed=False, cap_start=False, cap_end=False, bm=None):
    bm = bm or bmesh.new()
    rows = [[bm.verts.new(Vector(p)) for p in sec] for sec in sections]
    n = len(sections[0])
    faces = []
    for i in range(len(rows) - 1):
        a, b = rows[i], rows[i + 1]
        for j in (range(n) if closed else range(n - 1)):
            j2 = (j + 1) % n
            try:
                faces.append((i, j, bm.faces.new((a[j], a[j2], b[j2], b[j]))))
            except ValueError:
                pass
    if cap_start:
        try: bm.faces.new(list(reversed(rows[0])))
        except ValueError: pass
    if cap_end:
        try: bm.faces.new(rows[-1])
        except ValueError: pass
    return bm, rows, faces


def finish_bm(bm, weld=1e-5):
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=weld)
    bmesh.ops.dissolve_degenerate(bm, dist=1e-7, edges=bm.edges)
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)


def box(name, x0, x1, y0, y1, z0, z1, mat, bevel=0.0, segs=3, smooth=True):
    bm = bmesh.new()
    bmesh.ops.create_cube(bm, size=1.0)
    for v in bm.verts:
        v.co.x = x0 if v.co.x < 0 else x1
        v.co.y = y0 if v.co.y < 0 else y1
        v.co.z = z0 if v.co.z < 0 else z1
    ob = mesh_from_bm(name, bm, [mat], 40 if smooth else None)
    if bevel > 0:
        md = ob.modifiers.new("Bevel", "BEVEL")
        md.width = bevel; md.segments = segs; md.limit_method = "ANGLE"
        md.harden_normals = False
    return ob


def cylinder(name, p0, p1, r, mat, segs=24, cap=True):
    p0, p1 = Vector(p0), Vector(p1)
    d = p1 - p0
    bm = bmesh.new()
    bmesh.ops.create_cone(bm, cap_ends=cap, segments=segs, radius1=r, radius2=r, depth=d.length)
    rot = d.to_track_quat("Z", "Y").to_matrix().to_4x4()
    bmesh.ops.transform(bm, matrix=Matrix.Translation((p0 + p1) / 2) @ rot, verts=bm.verts)
    return mesh_from_bm(name, bm, [mat], 50)


def tube(name, pts, r, mat, caps=True, res=12):
    cu = bpy.data.curves.new(name, "CURVE")
    cu.dimensions = "3D"
    cu.bevel_depth = r
    cu.bevel_resolution = 4
    cu.use_fill_caps = caps
    sp = cu.splines.new("POLY")
    sp.points.add(len(pts) - 1)
    for i, pt in enumerate(pts):
        sp.points[i].co = (*pt, 1)
    ob = link(bpy.data.objects.new(name, cu))
    cu.materials.append(mat)
    return ob


def bezier_tube(name, pts, r, mat):
    cu = bpy.data.curves.new(name, "CURVE")
    cu.dimensions = "3D"; cu.bevel_depth = r; cu.bevel_resolution = 4; cu.use_fill_caps = True
    cu.resolution_u = 24
    sp = cu.splines.new("BEZIER")
    sp.bezier_points.add(len(pts) - 1)
    for i, pt in enumerate(pts):
        bp = sp.bezier_points[i]
        bp.co = pt; bp.handle_left_type = bp.handle_right_type = "AUTO"
    cu.materials.append(mat)
    return link(bpy.data.objects.new(name, cu))


def smooth_path(ctrl, n=80):
    """Catmull-Rom through control points -> dense list."""
    P = [Vector(c) for c in ctrl]
    out = []
    for i in range(len(P) - 1):
        p0 = P[max(i - 1, 0)]; p1 = P[i]; p2 = P[i + 1]; p3 = P[min(i + 2, len(P) - 1)]
        steps = max(2, n // (len(P) - 1))
        for k in range(steps):
            t = k / steps
            t2, t3 = t * t, t * t * t
            out.append(tuple(0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)))
    out.append(tuple(P[-1]))
    return out


# ---------------------------------------------------------------- hull lines
def bs(t):  # half beam at sheer
    if t < 0.4:
        return 0.855 + 0.045 * math.sin(t / 0.4 * math.pi / 2)
    u = (t - 0.4) / 0.6
    return 0.90 * max(0.0, 1 - u ** 2.1) ** 0.78


def zs(t):  # sheer height
    return 0.52 + 0.10 * t ** 1.6 + 0.11 * max(0.0, (t - 0.72) / 0.28) ** 2


def zk(t):  # keel height
    if t < 0.55:
        return -0.30 + 0.02 * t
    u = (t - 0.55) / 0.45
    z0 = -0.30 + 0.02 * 0.55
    return z0 + (zs(1.0) - 0.015 - z0) * u ** 2.05


def zc(t):
    return zk(t) + (zs(t) - zk(t)) * (0.27 + 0.16 * t * t)


def half_section(t):
    """Returns half profile keel->sheer as list of (y,z) and index where topsides begin."""
    w = bs(t) / 0.90
    k, s, b = zk(t), zs(t), bs(t)
    c = zc(t)
    bc = 0.855 * b
    A = Vector((0.016 * w, k + 0.002 * w))
    C = Vector((bc, c))
    step = Vector((0.019 * w, -0.007 * w))
    pts = [Vector((0.0, k)), A.copy()]
    off = Vector((0, 0))
    for u in (0.12, 0.24, 0.36, "S", 0.48, 0.60, 0.70, "S", 0.80, 0.90, 1.0):
        if u == "S":
            off = off + step
            pts.append(pts[-1] + step)
            continue
        base = A + (C - A) * u
        # slight convexity of the bottom panels (warped vee)
        bulge = 0.010 * w * math.sin(math.pi * u)
        pts.append(base + off + Vector((0, -bulge)))
    chine = pts[-1]
    lip = chine + Vector((0.042 * w, -0.006 * w))
    pts.append(lip)
    top_start = len(pts)
    T0 = lip + Vector((0.003 * w, 0.014 * w))
    n = 9
    for i in range(n):
        f = i / (n - 1)
        y = T0.x + (b - T0.x) * (f ** 0.85) + 0.012 * w * math.sin(math.pi * f)
        z = T0.y + (s - T0.y) * f
        pts.append(Vector((y, z)))
    return [(p.x, p.y) for p in pts], top_start


def stations(n=110):
    ts = []
    for i in range(n):
        sv = i / (n - 1)
        ts.append(1 - (1 - sv) ** 1.3)
    return ts


NOTCH_HW = 0.27   # half width of the outboard notch in the transom
NOTCH_Z = 0.385   # notch bottom


def build_hull():
    bm = bmesh.new()
    lay = bm.verts.layers.float.new("sheer_d")
    ts = stations()
    secs = []
    top_idx = None
    for t in ts:
        hp, top_idx = half_section(t)
        full = [(-y, z) for (y, z) in reversed(hp[1:])] + hp
        secs.append([(t * L, y, z) for (y, z) in full])
    nh = len(hp)
    bm, rows, faces = loft_bm(secs, bm=bm)
    for i, row in enumerate(rows):
        t = ts[i]
        for v in row:
            v[lay] = zs(t) - v.co.z
    # material by profile segment: 0 = topsides red, 1 = bottom
    mid = nh - 1  # index of keel in full section
    for (i, j, f) in faces:
        dist = abs(j - mid) if j >= mid else abs(j + 1 - mid)
        f.material_index = 1 if dist < top_idx else 0
    # transom face with motor notch
    r0 = rows[0]
    s0 = zs(0.0)
    notch = [(0.0, NOTCH_HW + 0.012, s0), (0.0, NOTCH_HW, NOTCH_Z), (0.0, -NOTCH_HW, NOTCH_Z), (0.0, -NOTCH_HW - 0.012, s0)]
    nv = [bm.verts.new(Vector(p)) for p in notch]
    for v in nv:
        v[lay] = s0 - v.co.z
    tf = bm.faces.new(r0 + nv)
    tf.material_index = 0
    finish_bm(bm, weld=2e-4)
    ob = mesh_from_bm("Hull", bm, [M["red"], M["bottom"]], 28)
    return ob


FLOOR_T0, FLOOR_T1 = 0.655, 0.668


def floorz(t):
    if t <= FLOOR_T0:
        return 0.050 + 0.035 * t
    if t >= FLOOR_T1:
        return zs(t) - 0.135
    f = (t - FLOOR_T0) / (FLOOR_T1 - FLOOR_T0)
    return (1 - f) * floorz(FLOOR_T0) + f * floorz(FLOOR_T1)


def deck_half(t):
    b, z = bs(t), zs(t)
    cw = min(0.125, b * 0.42)
    fz = floorz(t)
    inner = b - cw
    pts = [(b - 0.003, z - 0.014), (b - 0.006, z + 0.007), (b - 0.022, z + 0.017),
           (inner + 0.016, z + 0.016), (inner + 0.002, z + 0.004), (inner - 0.006, z - 0.028),  # gunwale cap (0..4)
           (inner - 0.024, fz + 0.06), (inner - 0.042, fz + 0.012), (inner - 0.062, fz),           # liner (5..7)
           (max(0.0, (inner - 0.062)) * 0.5, fz), (0.0, fz)]                                       # floor (8..9)
    out = []
    lasty = 9
    for (y, zz) in pts:
        y = max(0.0, y)
        out.append((y, zz))
    return out


def build_deck():
    x_start = 0.065
    t_start = x_start / L
    ts = [t_start + (FLOOR_T0 - t_start) * i / 45 for i in range(46)]
    ts += [FLOOR_T1 + (1 - FLOOR_T1) * (1 - (1 - i / 45) ** 1.3) for i in range(46)]
    secs = []
    for t in ts:
        hp = deck_half(t)
        full = [(-y, z) for (y, z) in hp] + [(y, z) for (y, z) in reversed(hp[:-1])]
        secs.append([(t * L, y, z) for (y, z) in full])
    n = len(secs[0])
    bm, rows, faces = loft_bm(secs)
    nhalf = len(deck_half(0.3))
    for (i, j, f) in faces:
        jj = j if j < nhalf - 1 else (n - 2 - j)
        if jj <= 4:
            f.material_index = 0       # cap
        elif jj <= 7:
            f.material_index = 0       # liner gloss
        else:
            f.material_index = 1       # non-skid
    # mark the step face between cockpit sole and bow deck as gloss
    # end face at transom with notch
    s0 = zs(t_start)
    notch = [(x_start, NOTCH_HW + 0.012, s0 + 0.016), (x_start, NOTCH_HW, NOTCH_Z), (x_start, -NOTCH_HW, NOTCH_Z), (x_start, -NOTCH_HW - 0.012, s0 + 0.016)]
    nv = [bm.verts.new(Vector(p)) for p in notch]
    try:
        f = bm.faces.new(rows[0] + nv); f.material_index = 0
    except ValueError:
        pass
    finish_bm(bm, weld=2e-4)
    return mesh_from_bm("DeckMould", bm, [M["white_gel"], M["nonskid"]], 32)


def build_transom_cap():
    b0, s0 = bs(0.0), zs(0.0)
    path = [(-b0 - 0.004, s0 + 0.02), (-NOTCH_HW - 0.012, s0 + 0.02), (-NOTCH_HW, NOTCH_Z), (NOTCH_HW, NOTCH_Z), (NOTCH_HW + 0.012, s0 + 0.02), (b0 + 0.004, s0 + 0.02)]
    # densify path
    pts = []
    for i in range(len(path) - 1):
        a, b = Vector(path[i]), Vector(path[i + 1])
        for k in range(4):
            pts.append(a + (b - a) * k / 4)
    pts.append(Vector(path[-1]))
    secs = [[(x, p.x, p.y) for p in pts] for x in (-0.012, 0.072)]
    bm, rows, faces = loft_bm(secs)
    finish_bm(bm)
    ob = mesh_from_bm("TransomCap", bm, [M["white_gel"]], 30)
    sd = ob.modifiers.new("Solid", "SOLIDIFY"); sd.thickness = 0.025; sd.offset = -1
    bv = ob.modifiers.new("Bevel", "BEVEL"); bv.width = 0.006; bv.segments = 2; bv.limit_method = "ANGLE"
    return ob


def build_rubrail():
    ts = [i / 160 for i in range(161)]
    port = [(t * L, bs(t) + 0.010, zs(t) - 0.010) for t in ts]
    stbd = [(t * L, -(bs(t) + 0.010), zs(t) - 0.010) for t in reversed(ts)]
    pts = port[:-1] + [(L + 0.008, 0, zs(1) - 0.010)] + stbd[1:]
    r = tube("Rubrail", pts, 0.019, M["rubber"])
    r.data.bevel_resolution = 5
    ins_p = [(x, y + (0.017 if y > 0 else -0.017 if y < 0 else 0), z) for (x, y, z) in pts]
    ins_p = [(x + (0.016 if abs(y) < 0.02 else 0), y, z) for (x, y, z) in ins_p]
    tube("RubrailInsert", ins_p, 0.0055, M["steel"])


# ---------------------------------------------------------------- deck furniture
def cushion(name, x0, x1, y0, y1, z0, z1, mat=None):
    ob = box(name, x0, x1, y0, y1, z0, z1, mat or M["vinyl"], bevel=min(0.03, (z1 - z0) * 0.45), segs=5)
    return ob


def build_furniture():
    fz = floorz(0.1)
    # aft bench with splash well
    box("AftBenchSideP", 0.065, 0.62, NOTCH_HW, 0.725, fz - 0.02, 0.405, M["white_gel"], bevel=0.012)
    box("AftBenchSideS", 0.065, 0.62, -0.725, -NOTCH_HW, fz - 0.02, 0.405, M["white_gel"], bevel=0.012)
    box("AftBenchFront", 0.305, 0.62, -NOTCH_HW - 0.01, NOTCH_HW + 0.01, fz - 0.02, 0.405, M["white_gel"], bevel=0.012)
    box("SplashWell", 0.06, 0.31, -NOTCH_HW - 0.01, NOTCH_HW + 0.01, fz - 0.02, 0.335, M["white_gel"], bevel=0.008)
    cushion("AftCushion", 0.315, 0.615, -0.70, 0.70, 0.405, 0.485)
    cushion("AftBackrestP", 0.07, 0.15, 0.32, 0.70, 0.43, 0.66)
    cushion("AftBackrestS", 0.07, 0.15, -0.70, -0.32, 0.43, 0.66)
    # side hatches on bench (inspection hatch rings)
    for sgn in (1, -1):
        cylinder("HatchRing" + str(sgn), (0.19, sgn * 0.52, 0.405), (0.19, sgn * 0.52, 0.413), 0.07, M["black_satin"], 32)
        cylinder("HatchLid" + str(sgn), (0.19, sgn * 0.52, 0.413), (0.19, sgn * 0.52, 0.418), 0.058, M["white_gel"], 32)

    # helm seat (leaning post style bench)
    fz2 = floorz(0.29)
    box("HelmSeatBase", 1.10, 1.46, -0.36, 0.36, fz2 - 0.02, 0.40, M["white_gel"], bevel=0.02)
    cushion("HelmCushion", 1.08, 1.48, -0.38, 0.38, 0.40, 0.49)
    cushion("HelmBack", 1.04, 1.12, -0.36, 0.36, 0.53, 0.80)
    for sgn in (1, -1):
        tube("HelmBackFrame" + str(sgn), [(1.10, sgn * 0.30, 0.47), (1.08, sgn * 0.30, 0.60), (1.07, sgn * 0.30, 0.86), (1.07, 0, 0.88) if False else (1.07, sgn * 0.30, 0.86)], 0.012, M["steel"])
    tube("HelmGrab", [(1.07, 0.30, 0.86), (1.065, 0.15, 0.875), (1.065, -0.15, 0.875), (1.07, -0.30, 0.86)], 0.012, M["steel"])

    # front seat ahead of console
    fz3 = floorz(0.55)
    box("FrontSeatBase", 2.39, 2.72, -0.30, 0.30, fz3 - 0.02, 0.37, M["white_gel"], bevel=0.02)
    cushion("FrontSeatCushion", 2.38, 2.74, -0.31, 0.31, 0.37, 0.45)

    # bow sun pad following the hull
    ts = [0.685 + (0.905 - 0.685) * i / 24 for i in range(25)]
    ring = []
    for t in ts:
        b = bs(t); cw = min(0.125, b * 0.42)
        ring.append((t * L, b - cw - 0.075))
    port = [(x, y) for x, y in ring]
    stbd = [(x, -y) for x, y in reversed(ring)]
    outline = port + stbd
    bm = bmesh.new()
    zb = floorz(0.685)
    vb = [bm.verts.new((x, y, zb + 0.005)) for x, y in outline]
    f = bm.faces.new(vb)
    ext = bmesh.ops.extrude_face_region(bm, geom=[f])
    vt = [e for e in ext["geom"] if isinstance(e, bmesh.types.BMVert)]
    for v in vt:
        t = v.co.x / L
        v.co.z = floorz(t) + 0.095
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    ob = mesh_from_bm("BowCushion", bm, [M["vinyl"]], 40)
    bv = ob.modifiers.new("Bevel", "BEVEL"); bv.width = 0.035; bv.segments = 5; bv.limit_method = "ANGLE"
    # seam groove splitting the pad in two (dark piping)
    tube("BowCushionSeam", [(0.80 * L, -0.45, floorz(0.8) + 0.101), (0.80 * L, 0.45, floorz(0.8) + 0.101)], 0.004, M["vinyl_dark"])
    # anchor locker hatch at the bow
    tb = 0.935
    x0 = 0.915 * L; x1 = 0.965 * L
    box("AnchorHatch", x0, x1, -0.12, 0.12, floorz(tb) - 0.01, floorz(tb) + 0.012, M["white_gel"], bevel=0.008)
    cylinder("AnchorHatchHandle", (x1 - 0.06, -0.04, floorz(tb) + 0.014), (x1 - 0.06, 0.04, floorz(tb) + 0.014), 0.007, M["steel"], 12)


def build_console():
    fz = floorz(0.43)
    # side profile polygon (x,z)
    prof = [(1.70, fz - 0.02), (2.37, fz - 0.02), (2.23, 1.00), (2.06, 1.005), (1.70, 0.78)]
    bm = bmesh.new()
    hw = 0.30
    vs = [bm.verts.new((x, -hw, z)) for (x, z) in prof]
    f = bm.faces.new(vs)
    ext = bmesh.ops.extrude_face_region(bm, geom=[f])
    for e in ext["geom"]:
        if isinstance(e, bmesh.types.BMVert):
            e.co.y = hw
    bmesh.ops.recalc_face_normals(bm, faces=bm.faces)
    ob = mesh_from_bm("Console", bm, [M["white_gel"]], 40)
    bv = ob.modifiers.new("Bevel", "BEVEL"); bv.width = 0.035; bv.segments = 5; bv.limit_method = "ANGLE"

    # dash geometry
    P1 = Vector((1.70, 0, 0.78)); P2 = Vector((2.06, 0, 1.005))
    d = (P2 - P1).normalized()
    n = Vector((-d.z, 0, d.x))  # aft-up normal
    def dash_pt(s, y, off=0.0):
        return P1 + d * s + Vector((0, y, 0)) + n * off
    dl = (P2 - P1).length
    # black dash panel
    bm = bmesh.new()
    corners = [dash_pt(0.05, -0.255, 0.003), dash_pt(dl - 0.05, -0.255, 0.003), dash_pt(dl - 0.05, 0.255, 0.003), dash_pt(0.05, 0.255, 0.003)]
    vv = [bm.verts.new(c) for c in corners]
    f = bm.faces.new(vv)
    ob = mesh_from_bm("DashPanel", bm, [M["black_satin"]])
    sd = ob.modifiers.new("Solid", "SOLIDIFY"); sd.thickness = 0.006
    bv = ob.modifiers.new("Bevel", "BEVEL"); bv.width = 0.004; bv.segments = 2

    # gauges
    for i, y in enumerate((0.06, 0.18)):
        c = dash_pt(dl * 0.62, y, 0.009)
        cylinder(f"GaugeBody{i}", c - n * 0.01, c + n * 0.012, 0.047, M["steel"], 40)
        cylinder(f"GaugeFace{i}", c + n * 0.012, c + n * 0.0135, 0.041, M["gauge_face"], 40)
        cylinder(f"GaugeGlass{i}", c + n * 0.0145, c + n * 0.016, 0.041, M["gauge_glass"], 40)
        # tick ring (white)
        bm = bmesh.new()
        rot = n.to_track_quat("Z", "Y").to_matrix()
        right = rot @ Vector((1, 0, 0)); up = rot @ Vector((0, 1, 0))
        for k in range(11):
            a = math.radians(-135 + 27 * k)
            dirv = right * math.sin(a) + up * math.cos(a)
            a0 = c + n * 0.0138 + dirv * 0.030; a1 = c + n * 0.0138 + dirv * 0.038
            side = (dirv.cross(n)).normalized() * 0.0016
            vv = [bm.verts.new(p) for p in (a0 - side, a1 - side, a1 + side, a0 + side)]
            bm.faces.new(vv)
        mesh_from_bm(f"GaugeTicks{i}", bm, [M["white_dec"]])
        # needle
        a = math.radians(-60 + 70 * i)
        dirv = right * math.sin(a) + up * math.cos(a)
        side = (dirv.cross(n)).normalized() * 0.0018
        bm = bmesh.new()
        vv = [bm.verts.new(p) for p in (c + n * 0.0141 - side, c + n * 0.0141 + dirv * 0.034, c + n * 0.0141 + side)]
        bm.faces.new(vv)
        mesh_from_bm(f"GaugeNeedle{i}", bm, [M["needle"]])
    # switch panel
    for k in range(4):
        c = dash_pt(dl * 0.30, 0.03 + k * 0.05, 0.008)
        cylinder(f"Switch{k}", c, c + n * 0.025, 0.006, M["black_gloss"], 12)
        cylinder(f"SwitchLed{k}", dash_pt(dl * 0.38, 0.03 + k * 0.05, 0.008), dash_pt(dl * 0.38, 0.03 + k * 0.05, 0.011), 0.004, M["green_lens"], 12)

    # steering wheel
    wc_base = dash_pt(dl * 0.45, -0.13, 0.0)
    wc = wc_base + n * 0.115
    cylinder("HelmShaft", wc_base, wc - n * 0.01, 0.022, M["black_satin"], 24)
    cylinder("HelmBezel", wc_base, wc_base + n * 0.012, 0.055, M["black_satin"], 32)
    rot = n.to_track_quat("Z", "Y").to_matrix().to_4x4()
    bm = bmesh.new()
    bmesh.ops.create_circle(bm, segments=8, radius=0.017)
    # build torus manually
    bm.free()
    bm = bmesh.new()
    R, rr = 0.170, 0.0175
    segU, segV = 72, 16
    grid = []
    for i in range(segU):
        u = 2 * math.pi * i / segU
        ring = []
        for j in range(segV):
            v = 2 * math.pi * j / segV
            # slightly oval grip section
            p = Vector(((R + rr * math.cos(v)) * math.cos(u), (R + rr * math.cos(v)) * math.sin(u), 1.15 * rr * math.sin(v)))
            ring.append(bm.verts.new(p))
        grid.append(ring)
    for i in range(segU):
        for j in range(segV):
            a = grid[i][j]; b = grid[(i + 1) % segU][j]; c = grid[(i + 1) % segU][(j + 1) % segV]; d2 = grid[i][(j + 1) % segV]
            bm.faces.new((a, b, c, d2))
    bmesh.ops.transform(bm, matrix=Matrix.Translation(wc) @ rot, verts=bm.verts)
    ob = mesh_from_bm("WheelRim", bm, [M["black_satin"]], 60)
    add = ob
    # hub & spokes
    hub_a = wc - n * 0.03; hub_b = wc + n * 0.012
    cylinder("WheelHub", hub_a, hub_b, 0.036, M["steel"], 32)
    cylinder("WheelCap", hub_b, hub_b + n * 0.006, 0.028, M["black_gloss"], 32)
    for k in range(3):
        a = math.radians(90 + 120 * k)
        dirv = (rot.to_3x3() @ Vector((math.cos(a), math.sin(a), 0)))
        cylinder(f"Spoke{k}", wc - n * 0.022 + dirv * 0.03, wc + dirv * (R - 0.012), 0.0085, M["steel"], 16)
    # spinner knob
    sp_base = wc + (rot.to_3x3() @ Vector((math.cos(math.radians(-30)), math.sin(math.radians(-30)), 0))) * R
    cylinder("SpinnerKnob", sp_base + n * 0.015, sp_base + n * 0.075, 0.014, M["black_gloss"], 20)

    # throttle binnacle on starboard side
    box("ThrottleBox", 1.82, 1.98, -0.37, -0.295, 0.60, 0.76, M["black_satin"], bevel=0.02)
    piv = Vector((1.90, -0.385, 0.70))
    tip = piv + Vector((0.07, -0.01, 0.12))
    cylinder("ThrottlePivot", piv + Vector((0, 0.01, 0)), piv + Vector((0, -0.02, 0)), 0.03, M["black_gloss"], 24)
    cylinder("ThrottleArm", piv + Vector((0, -0.012, 0)), tip, 0.011, M["steel"], 16)
    cylinder("ThrottleGrip", tip - Vector((0, 0.06, 0)), tip + Vector((0, 0.045, 0)), 0.016, M["black_gloss"], 20)
    box("KillSwitch", 1.84, 1.87, -0.375, -0.37, 0.615, 0.635, M["red_lens"], bevel=0.003)

    # compass
    cc = Vector((2.145, 0, 1.005))
    cylinder("CompassBase", cc, cc + Vector((0, 0, 0.02)), 0.055, M["black_satin"], 32)
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=32, v_segments=16, radius=0.048)
    bmesh.ops.delete(bm, geom=[v for v in bm.verts if v.co.z < -1e-4], context="VERTS")
    bmesh.ops.translate(bm, vec=cc + Vector((0, 0, 0.02)), verts=bm.verts)
    mesh_from_bm("CompassDome", bm, [M["gauge_glass"]], 60)
    cylinder("CompassCard", cc + Vector((0, 0, 0.02)), cc + Vector((0, 0, 0.032)), 0.04, M["black_gloss"], 32)

    # wrap-around windshield
    def zdash(x):
        if x <= 2.06:
            return 0.78 + (x - 1.70) * (1.005 - 0.78) / 0.36
        if x <= 2.23:
            return 1.005
        return 1.005 - (x - 2.23) * (1.005 - 0.0) / (2.37 - 2.23)
    base, top = [], []
    N = 40
    for i in range(N + 1):
        a = -1 + 2 * i / N
        y = 0.315 * a
        x = 1.93 + 0.255 * (1 - abs(a) ** 2.4)
        zb = zdash(x) + 0.004
        base.append((x, y, zb))
        top.append((x - 0.15 - 0.02 * (1 - a * a), y * 0.97, 1.31 - 0.07 * a * a))
    bm, rows, faces = loft_bm([base, top])
    finish_bm(bm)
    ob = mesh_from_bm("Windshield", bm, [M["acrylic"]], 60)
    sd = ob.modifiers.new("Solid", "SOLIDIFY"); sd.thickness = 0.006
    tube("WindshieldTopTrim", top, 0.0065, M["black_satin"])
    tube("WindshieldBaseTrim", base, 0.009, M["black_satin"])
    # windshield grab rail
    gr = [(x + 0.02, y * 1.01, z + 0.03) for (x, y, z) in top[4:-4]]
    # side grab rails on console
    for sgn in (1, -1):
        tube("ConsoleGrab" + str(sgn), [(1.96, sgn * 0.30, 0.56), (1.97, sgn * 0.345, 0.58), (2.18, sgn * 0.345, 0.58), (2.20, sgn * 0.30, 0.56)], 0.011, M["steel"])


def build_fittings():
    # bow rail (pulpit)
    def rail_pt(t, side):
        b = bs(t); cw = min(0.125, b * 0.42)
        h = 0.30 - 0.06 * max(0, (t - 0.7) / 0.3)
        return (t * L, side * (b - cw * 0.45), zs(t) + h)
    ts = [0.665 + (0.985 - 0.665) * i / 40 for i in range(41)]
    port = [rail_pt(t, 1) for t in ts]
    stbd = [rail_pt(t, -1) for t in reversed(ts)]
    tipx = L - 0.02
    path = port[:-2] + [(tipx - 0.03, 0.035, zs(1) + 0.245), (tipx, 0.0, zs(1) + 0.24), (tipx - 0.03, -0.035, zs(1) + 0.245)] + stbd[2:]
    a0 = port[0]; a1 = stbd[-1]
    full = [(a0[0] - 0.05, a0[1], zs(0.655) + 0.02), (a0[0] - 0.01, a0[1], a0[2] - 0.05)] + path + [(a1[0] - 0.01, a1[1], a1[2] - 0.05), (a1[0] - 0.05, a1[1], zs(0.655) + 0.02)]
    tube("BowRail", smooth_path(full, 140), 0.0125, M["steel"])
    for t in (0.77, 0.88):
        for sgn in (1, -1):
            p = rail_pt(t, sgn)
            cylinder(f"Stanchion{t}{sgn}", (p[0], p[1], zs(t) + 0.012), p, 0.011, M["steel"], 16)
            cylinder(f"StanchionBase{t}{sgn}", (p[0], p[1], zs(t) + 0.010), (p[0], p[1], zs(t) + 0.03), 0.022, M["steel"], 20)
    # bow nav light
    nl = Vector((L - 0.05, 0, zs(1) + 0.018))
    bm = bmesh.new()
    bmesh.ops.create_uvsphere(bm, u_segments=24, v_segments=12, radius=0.035)
    for v in bm.verts:
        v.co.x *= 1.3
    bmesh.ops.delete(bm, geom=[v for v in bm.verts if v.co.z < -1e-4], context="VERTS")
    for f in bm.faces:
        f.material_index = 0 if f.calc_center_median().y > 0 else 1
    bmesh.ops.translate(bm, vec=nl, verts=bm.verts)
    mesh_from_bm("BowNavLight", bm, [M["red_lens"], M["green_lens"]], 60)
    # note: red = port (+y) green = starboard (-y)  (correct convention)

    # cleats (aft and bow)
    def cleat(name, x, y, z, ang=0.0):
        bm = bmesh.new()
        # horn bar: stretched capsule
        bmesh.ops.create_uvsphere(bm, u_segments=20, v_segments=10, radius=0.5)
        for v in bm.verts:
            v.co.x *= 0.17; v.co.y *= 0.022; v.co.z *= 0.022
            v.co.z += 0.034
        bm2 = bmesh.new()
        mesh = None
        # legs
        for lx in (-0.03, 0.03):
            res = bmesh.ops.create_cone(bm, cap_ends=True, segments=12, radius1=0.014, radius2=0.010, depth=0.034)
            bmesh.ops.translate(bm, vec=(lx, 0, 0.017), verts=res["verts"])
        bm2.free()
        bmesh.ops.transform(bm, matrix=Matrix.Translation((x, y, z)) @ Matrix.Rotation(ang, 4, "Z"), verts=bm.verts)
        mesh_from_bm(name, bm, [M["steel"]], 50)
    for sgn in (1, -1):
        t = 0.06
        b = bs(t); cw = min(0.125, b * 0.42)
        cleat(f"CleatAft{sgn}", t * L + 0.08, sgn * (b - cw * 0.5), zs(t) + 0.017)
        t = 0.84
        b = bs(t); cw = min(0.125, b * 0.42)
        cleat(f"CleatBow{sgn}", t * L, sgn * (b - cw * 0.5), zs(t) + 0.017, sgn * 0.25)
    # stern all-round light pole (port aft corner)
    px, py = 0.04, 0.80
    cylinder("SternPoleBase", (px, py, zs(0) + 0.02), (px, py, zs(0) + 0.05), 0.025, M["black_satin"], 20)
    cylinder("SternPole", (px, py, zs(0) + 0.05), (px, py, zs(0) + 1.05), 0.011, M["white_dec"], 16)
    cylinder("SternLight", (px, py, zs(0) + 1.05), (px, py, zs(0) + 1.10), 0.022, M["white_lens"], 20)
    # fuel filler on starboard gunwale
    t = 0.09; b = bs(t); cw = min(0.125, b * 0.42)
    cylinder("FuelCap", (t * L, -(b - cw * 0.5), zs(t) + 0.016), (t * L, -(b - cw * 0.5), zs(t) + 0.024), 0.03, M["steel"], 24)
    # transom boarding ladder (port side of motor)
    lx = -0.035
    for k, z in enumerate((0.40, 0.20, 0.0, -0.20)):
        cylinder(f"LadderStep{k}", (lx - 0.02 * k, 0.40, z), (lx - 0.02 * k, 0.58, z), 0.011, M["steel"], 14)
    for yy in (0.40, 0.58):
        tube(f"LadderRail{yy}", [(0.0, yy, 0.50), (lx, yy, 0.47), (lx - 0.06, yy, -0.22)], 0.012, M["steel"])
    # drain plug & trim
    cylinder("DrainPlug", (0.0, 0.0, -0.22), (-0.012, 0.0, -0.22), 0.016, M["alu"], 16)
    # scuppers on transom
    for sgn in (1, -1):
        cylinder(f"Scupper{sgn}", (0.0, sgn * 0.55, 0.12), (-0.006, sgn * 0.55, 0.12), 0.022, M["black_satin"], 20)


# ---------------------------------------------------------------- outboard
def superellipse_ring(cx, a_front, a_rear, b, z, n=2.6, seg=48, y0=0.0):
    pts = []
    for i in range(seg):
        th = 2 * math.pi * i / seg
        c, s = math.cos(th), math.sin(th)
        a = a_front if c > 0 else a_rear
        x = cx + a * math.copysign(abs(c) ** (2 / n), c)
        y = y0 + b * math.copysign(abs(s) ** (2 / n), s)
        pts.append((x, y, z))
    return pts


def build_outboard():
    # cowling (lower grey, split band, upper black)
    lv = [  # z, cx, a_front, a_rear, b, n
        (0.445, -0.285, 0.15, 0.19, 0.120, 2.5),
        (0.475, -0.290, 0.21, 0.25, 0.160, 2.7),
        (0.52, -0.295, 0.235, 0.275, 0.182, 3.0),
        (0.60, -0.300, 0.245, 0.285, 0.190, 3.2),
        (0.640, -0.300, 0.247, 0.288, 0.191, 3.2),
        (0.646, -0.300, 0.240, 0.281, 0.185, 3.2),  # split groove
        (0.654, -0.300, 0.240, 0.281, 0.185, 3.2),
        (0.660, -0.300, 0.248, 0.289, 0.192, 3.2),
        (0.75, -0.302, 0.250, 0.292, 0.193, 3.2),
        (0.85, -0.306, 0.240, 0.282, 0.186, 3.0),
        (0.92, -0.312, 0.212, 0.258, 0.168, 2.7),
        (0.965, -0.320, 0.165, 0.215, 0.135, 2.4),
        (0.990, -0.328, 0.10, 0.15, 0.085, 2.2),
        (1.000, -0.332, 0.03, 0.06, 0.03, 2.0),
    ]
    secs = [superellipse_ring(cx, af, ar, b, z, n) for (z, cx, af, ar, b, n) in lv]
    bm, rows, faces = loft_bm(secs, closed=True, cap_start=True, cap_end=True)
    for (i, j, f) in faces:
        if i <= 3:
            f.material_index = 0
        elif i <= 6:
            f.material_index = 2
        else:
            f.material_index = 1
    finish_bm(bm)
    mesh_from_bm("Cowling", bm, [M["grey_cowl"], M["black_gloss"], M["black_satin"]], 35)

    # rear cowl intake vent slots
    for k in range(5):
        z = 0.80 + k * 0.025
        box(f"Vent{k}", -0.60, -0.585, -0.08, 0.08, z, z + 0.012, M["black_satin"], bevel=0.004)

    # midsection / exhaust housing (teardrop sections)
    def tear(cx, a, b, z, seg=40):
        pts = []
        for i in range(seg):
            th = 2 * math.pi * i / seg
            c, s = math.cos(th), math.sin(th)
            if c >= 0:
                x = cx + a * c; y = b * s
            else:
                x = cx + 1.9 * a * c; y = b * s * (1 - 0.55 * (-c) ** 1.5)
            pts.append((x, y, z))
        return pts
    mid = [tear(-0.215, 0.085, 0.085, 0.45), tear(-0.215, 0.085, 0.082, 0.40), tear(-0.22, 0.075, 0.07, 0.30),
           tear(-0.225, 0.07, 0.062, 0.10), tear(-0.23, 0.068, 0.058, -0.10), tear(-0.235, 0.07, 0.06, -0.24), tear(-0.24, 0.072, 0.062, -0.262)]
    bm, rows, faces = loft_bm(mid, closed=True, cap_start=True, cap_end=True)
    finish_bm(bm)
    mesh_from_bm("Midsection", bm, [M["lower_unit"]], 40)
    # anti-ventilation plate
    plate = []
    for i in range(33):
        a = -math.pi / 2 + math.pi * i / 32
        plate.append((-0.36 - 0.11 * math.cos(a) * 0.9, 0.125 * math.sin(a), -0.265))
    plate = [(-0.12, -0.10, -0.265)] + plate + [(-0.12, 0.10, -0.265)]
    bm = bmesh.new()
    vv = [bm.verts.new(p) for p in plate]
    bm.faces.new(vv)
    ob = mesh_from_bm("AVPlate", bm, [M["lower_unit"]], 40)
    sd = ob.modifiers.new("Solid", "SOLIDIFY"); sd.thickness = 0.012
    bv = ob.modifiers.new("Bevel", "BEVEL"); bv.width = 0.004; bv.segments = 2
    # anode/trim tab under plate
    box("TrimTab", -0.40, -0.33, -0.012, 0.012, -0.30, -0.27, M["alu"], bevel=0.004)
    # strut
    strut = [tear(-0.235, 0.06, 0.03, -0.262), tear(-0.235, 0.06, 0.028, -0.36), tear(-0.235, 0.06, 0.028, -0.385)]
    bm, rows, faces = loft_bm(strut, closed=True)
    finish_bm(bm)
    mesh_from_bm("Strut", bm, [M["lower_unit"]], 40)
    # torpedo gearcase (revolve)
    zc_, = (-0.405,)
    prof = [(-0.065, 0.0), (-0.07, 0.02), (-0.08, 0.035), (-0.10, 0.048), (-0.14, 0.055), (-0.22, 0.056), (-0.30, 0.053), (-0.37, 0.048), (-0.40, 0.044), (-0.405, 0.0)]
    secs = []
    for (x, r) in prof:
        ring = [(x, r * math.cos(2 * math.pi * k / 32), zc_ + r * math.sin(2 * math.pi * k / 32)) for k in range(32)]
        secs.append(ring)
    bm, rows, faces = loft_bm(secs, closed=True)
    finish_bm(bm, weld=1e-4)
    mesh_from_bm("Gearcase", bm, [M["lower_unit"]], 40)
    # skeg
    bm = bmesh.new()
    vv = [bm.verts.new(p) for p in [(-0.17, 0, -0.44), (-0.37, 0, -0.44), (-0.34, 0, -0.585), (-0.27, 0, -0.595)]]
    bm.faces.new(vv)
    ob = mesh_from_bm("Skeg", bm, [M["lower_unit"]], 40)
    sd = ob.modifiers.new("Solid", "SOLIDIFY"); sd.thickness = 0.014; sd.offset = 0
    bv = ob.modifiers.new("Bevel", "BEVEL"); bv.width = 0.006; bv.segments = 3
    # water intake grille
    for k in range(4):
        box(f"Intake{k}", -0.20 - k * 0.018, -0.192 - k * 0.018, -0.031, 0.031, -0.37, -0.33, M["black_satin"], bevel=0.002)
    # propeller hub
    hx0, hx1 = -0.405, -0.49
    cylinder("PropHub", (hx0, 0, zc_), (hx1, 0, zc_), 0.040, M["prop"], 32)
    bm = bmesh.new()
    res = bmesh.ops.create_cone(bm, cap_ends=True, segments=32, radius1=0.036, radius2=0.012, depth=0.05)
    bmesh.ops.transform(bm, matrix=Matrix.Translation((hx1 - 0.025, 0, zc_)) @ Matrix.Rotation(-math.pi / 2, 4, "Y"), verts=bm.verts)
    mesh_from_bm("PropNut", bm, [M["prop"]], 50)
    # three blades
    bm = bmesh.new()
    xc = -0.448
    pitch = 0.33
    nr, nc = 14, 12
    for k in range(3):
        phi0 = 2 * math.pi * k / 3
        grid = []
        for i in range(nr):
            r = 0.038 + (0.142 - 0.038) * i / (nr - 1)
            q = (r - 0.038) / (0.142 - 0.038)
            hw = 0.62 * math.sqrt(max(0.0, 1 - q ** 2.4)) * (0.75 + 0.5 * q * (1 - q)) + 0.012
            skew = 0.35 * q * q
            row = []
            for j in range(nc):
                f = -1 + 2 * j / (nc - 1)
                th = phi0 + skew + hw * f
                x = xc - pitch * (th - phi0) / (2 * math.pi) - 0.02 * q
                row.append(bm.verts.new((x, r * math.cos(th), zc_ + r * math.sin(th))))
            grid.append(row)
        for i in range(nr - 1):
            for j in range(nc - 1):
                bm.faces.new((grid[i][j], grid[i + 1][j], grid[i + 1][j + 1], grid[i][j + 1]))
    ob = mesh_from_bm("PropBlades", bm, [M["prop"]], 70)
    sd = ob.modifiers.new("Solid", "SOLIDIFY"); sd.thickness = 0.006; sd.offset = 0
    # transom clamp bracket + tilt tube + steering link
    box("BracketOuter", -0.105, -0.005, -0.11, 0.11, 0.20, 0.44, M["lower_unit"], bevel=0.015)
    box("BracketClampP", -0.01, 0.075, 0.075, 0.105, 0.33, 0.43, M["lower_unit"], bevel=0.006)
    box("BracketClampS", -0.01, 0.075, -0.105, -0.075, 0.33, 0.43, M["lower_unit"], bevel=0.006)
    cylinder("TiltTube", (-0.05, -0.15, 0.415), (-0.05, 0.15, 0.415), 0.016, M["alu"], 20)
    cylinder("SteerArm", (-0.12, 0.0, 0.47), (0.02, 0.0, 0.47), 0.012, M["alu"], 16)
    # swivel bracket
    box("Swivel", -0.17, -0.10, -0.06, 0.06, 0.24, 0.45, M["lower_unit"], bevel=0.012)
    # rigging hose from cowl into splash well
    bezier_tube("RiggingHose", [(-0.08, 0.05, 0.52), (0.02, 0.07, 0.47), (0.15, 0.09, 0.36), (0.25, 0.12, 0.345)], 0.024, M["rubber"])
    # power decals
    for sgn in (1, -1):
        cu = bpy.data.curves.new(f"Decal{sgn}", "FONT")
        cu.body = "40"
        cu.size = 0.085
        cu.extrude = 0.0006
        cu.align_x = "CENTER"; cu.align_y = "CENTER"
        ob = link(bpy.data.objects.new(f"Decal{sgn}", cu))
        cu.materials.append(M["white_dec"])
        ob.location = (-0.40, sgn * 0.1945, 0.76)
        ob.rotation_euler = (math.radians(90), 0, math.radians(180) if sgn > 0 else 0)
        # slim stripe under the number
        cu2 = bpy.data.curves.new(f"Decal4S{sgn}", "FONT")
        cu2.body = "FOUR STROKE"
        cu2.size = 0.022; cu2.extrude = 0.0006; cu2.align_x = "CENTER"; cu2.align_y = "CENTER"
        ob2 = link(bpy.data.objects.new(f"Decal4S{sgn}", cu2))
        cu2.materials.append(M["white_dec"])
        ob2.location = (-0.40, sgn * 0.1945, 0.705)
        ob2.rotation_euler = ob.rotation_euler


# ---------------------------------------------------------------- build all
hull = build_hull()
build_deck()
build_transom_cap()
build_rubrail()
build_furniture()
build_console()
build_fittings()
build_outboard()

# parent everything to an empty for easy placement
root = link(bpy.data.objects.new("SpeedboatRoot", None))
for ob in list(col.objects):
    if ob is not root:
        ob.parent = root

tris = 0
dg = bpy.context.evaluated_depsgraph_get()
for ob in col.objects:
    if ob.type in ("MESH", "CURVE", "FONT"):
        ev = ob.evaluated_get(dg)
        me = ev.to_mesh()
        if me:
            me.calc_loop_triangles()
            tris += len(me.loop_triangles)
            ev.to_mesh_clear()
print("objects", len(col.objects), "triangles", tris)
bpy.ops.wm.save_as_mainfile(filepath=OUT)
print("saved", OUT)

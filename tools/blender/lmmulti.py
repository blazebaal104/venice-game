"""Multi-object landmark helpers (exec()d after lmcommon.py).

* parts: every part is its own mesh object sharing the material list; a part may be instanced many times
  (linked duplicates) so repeated modules (arcade bays, windows) share one atlas region and get a much higher
  texel density than unique geometry
* frames: facade-local coordinates (u along the facade, d depth into the building, z absolute height)
* booleans: carve openings (arches, tracery, windows) out of closed panels with the exact solver
* finish(): per-part smart UV unwrap, area-weighted UV scale, joint packing, one emission bake"""
from mathutils import Matrix

PARTS = []          # [object, weight, bake]
INSTANCES = []
Z_UP = Vector((0, 0, 1))

def new_part():
    global bm
    bm = bmesh.new()

def end_part(name, weight=1.0, bake=True):
    global bm
    me = bpy.data.meshes.new(name)
    bmesh.ops.remove_doubles(bm, verts=bm.verts, dist=0.0015)
    bm.normal_update(); bm.to_mesh(me); bm.free(); bm = None
    ob = bpy.data.objects.new(name, me); bpy.context.scene.collection.objects.link(ob)
    for m in MAT_ORDER: me.materials.append(MATS[m][0])
    ob["layers"] = [MATS[n][1] for n in MAT_ORDER]
    PARTS.append([ob, weight, bake])
    return ob

def instance(ob, x, y, rot_deg, z=0.0):
    o2 = bpy.data.objects.new(ob.name + "_i%d" % len(INSTANCES), ob.data)
    bpy.context.scene.collection.objects.link(o2)
    o2.matrix_world = Matrix.Translation((x, y, z)) @ Matrix.Rotation(math.radians(rot_deg), 4, "Z")
    o2["layers"] = ob["layers"]
    INSTANCES.append(o2)
    return o2

# ------------------------------------------------------------------ frames
class Frame:
    """o: origin, a: unit vector along the facade, inward: unit vector into the building (horizontal).
    P(u, d, z) = o + a*u + inward*d + Z*z.  a x inward must be +Z (checked) so faces keep their winding."""
    def __init__(self, o, a, inward):
        self.o = Vector(o); self.a = Vector(a).normalized(); self.i = Vector(inward).normalized()
        assert self.a.cross(self.i).dot(Z_UP) > 0.5, "left-handed frame"
    def P(self, u, d, z): return self.o + self.a * u + self.i * d + Z_UP * z
    def out(self): return -self.i

F0 = Frame((0, 0, 0), (1, 0, 0), (0, 1, 0))      # module frame: along +X, inward +Y, outward -Y

def oface(pts, expect, mat):
    f = face(pts, mat)
    if f is None: return None
    f.normal_update()
    if f.normal.dot(expect) < 0: f.normal_flip()
    return f

def poly_area2(p):
    return sum(p[i][0] * p[(i + 1) % len(p)][1] - p[(i + 1) % len(p)][0] * p[i][1] for i in range(len(p))) / 2

def prism(F, poly, d0, d1, mat, mat_side=None, caps=True):
    """Extrude a (u, z) polygon from depth d0 (front, faces outward) to d1 (back)."""
    if poly_area2(poly) < 0: poly = poly[::-1]
    n = len(poly)
    fr = [F.P(u, d0, z) for u, z in poly]; bk = [F.P(u, d1, z) for u, z in poly]
    if caps:
        oface(fr, F.out(), mat); oface(bk, F.i, mat)
    ms = mat_side or mat
    for k in range(n):
        j = (k + 1) % n
        (u0, z0), (u1, z1) = poly[k], poly[j]
        en = Vector((z1 - z0, -(u1 - u0)))                          # outward edge normal in (u, z) for a CCW polygon
        exp = F.a * en.x + Z_UP * en.y
        oface([fr[k], fr[j], bk[j], bk[k]], exp, ms)

def fbox(F, u0, u1, d0, d1, z0, z1, mat, bottom=False):
    prism(F, [(u0, z0), (u1, z0), (u1, z1), (u0, z1)], d0, d1, mat)

def flat(F, poly, d, mat, facing_out=True):
    """single face lying in the plane at depth d"""
    pts = [F.P(u, d, z) for u, z in poly]
    return oface(pts, F.out() if facing_out else F.i, mat)

def band(F, curve, w, d, mat, proud=0.0, d_back=None):
    """Strip of width w following a (u, z) curve (outer side = left of travel for CCW curves), at depth d.
    proud>0 also builds the side faces so it reads as a moulding."""
    pts_in = []; pts_out = []
    n = len(curve)
    for k in range(n):
        a = Vector(curve[max(k - 1, 0)]); b = Vector(curve[min(k + 1, n - 1)])
        t = (b - a).normalized(); nn = Vector((t.y, -t.x))
        pts_in.append(Vector(curve[k])); pts_out.append(Vector(curve[k]) + nn * w)
    for k in range(n - 1):
        q = [pts_in[k], pts_in[k + 1], pts_out[k + 1], pts_out[k]]
        oface([F.P(p.x, d - proud, p.y) for p in q], F.out(), mat)
        if proud > 0:
            for A, B in ((pts_out[k], pts_out[k + 1]), (pts_in[k + 1], pts_in[k])):
                e = (B - A); en = Vector((e.y, -e.x))
                oface([F.P(A.x, d - proud, A.y), F.P(B.x, d - proud, B.y), F.P(B.x, d, B.y), F.P(A.x, d, A.y)], F.a * en.x + Z_UP * en.y, mat)

def column(F, u, d, z0, z1, r, mat, cap=None, segs=10):
    """round shaft (+ optional capital: (height, top radius, abacus half size, abacus h))"""
    c = F.P(u, d, 0)
    lathe(c.x, c.y, z0, [(r * 1.04, 0.0), (r, 0.12), (r * 0.94, z1 - z0)], mat, segs)
    if cap:
        h, rt, ah, ahh = cap
        lathe(c.x, c.y, z1, [(r * 0.97, 0.0), (r * 1.15, h * 0.35), (rt, h)], mat, segs)
        fbox(F, u - ah, u + ah, d - ah, d + ah, z1 + h, z1 + h + ahh, mat)

# ------------------------------------------------------------------ curves
def pointed(uc, w, zs, h, n=10):
    """pointed (two-centred) arch from the right springing to the left one, CCW about the opening"""
    R = (w * w + h * h) / (2 * w)
    th = math.atan2(h, R - w)
    right = [(uc + w - R + R * math.cos(th * i / n), zs + R * math.sin(th * i / n)) for i in range(n + 1)]
    left = [(2 * uc - x, z) for x, z in reversed(right)][1:]
    return right + left

def ogee(uc, w, zs, h, n=8, cusp=0.0):
    """ogee head (convex then concave to a sharp apex), right springing -> apex -> left springing"""
    def bez(t, p0, p1, p2, p3):
        s = 1 - t
        return (s ** 3 * p0[0] + 3 * s * s * t * p1[0] + 3 * s * t * t * p2[0] + t ** 3 * p3[0],
                s ** 3 * p0[1] + 3 * s * s * t * p1[1] + 3 * s * t * t * p2[1] + t ** 3 * p3[1])
    p0 = (uc + w, zs); p1 = (uc + w, zs + 0.62 * h); p2 = (uc + 0.05 * w, zs + 0.45 * h); p3 = (uc, zs + h)
    right = [bez(i / n, p0, p1, p2, p3) for i in range(n + 1)]
    if cusp > 0:                       # trefoil cusp: pull one point toward the opening axis
        k = n // 3; x, z = right[k]; right[k] = (x - cusp, z - cusp * 0.3)
    left = [(2 * uc - x, z) for x, z in reversed(right)][1:]
    return right + left

def rounded(uc, w, zs, n=10):
    return [(uc + w * math.cos(math.pi * i / n), zs + w * math.sin(math.pi * i / n)) for i in range(n + 1)]

def foil(uc, zc, R, lobes=4, n=40, k=0.36, rot=0.0):
    return [(uc + R * (1 - k + k * abs(math.cos(lobes / 2 * (2 * math.pi * i / n + rot)))) * math.cos(2 * math.pi * i / n),
             zc + R * (1 - k + k * abs(math.cos(lobes / 2 * (2 * math.pi * i / n + rot)))) * math.sin(2 * math.pi * i / n)) for i in range(n)]

def circle(uc, zc, R, n=24):
    return [(uc + R * math.cos(2 * math.pi * i / n), zc + R * math.sin(2 * math.pi * i / n)) for i in range(n)]

def opening(head, z_bottom):
    """closed outline: head curve (right springing .. left springing) dropped to z_bottom"""
    (ur, zr), (ul, zl) = head[0], head[-1]
    return [(ur, z_bottom)] + head + [(ul, z_bottom)]

# ------------------------------------------------------------------ booleans
def _bm_object(name, b):
    me = bpy.data.meshes.new(name); b.to_mesh(me); b.free()
    ob = bpy.data.objects.new(name, me); bpy.context.scene.collection.objects.link(ob)
    for m in MAT_ORDER: me.materials.append(MATS[m][0])
    return ob

def carve(panel_fn, cutter_fn):
    """panel_fn() / cutter_fn() build geometry into the current `bm`; the cutter is subtracted from the panel and
    the result is appended to the part being built.  Faces created by the cutter keep the cutter's material."""
    global bm
    host = bm
    bm = bmesh.new(); panel_fn(); bm.normal_update(); pb = bm
    bm = bmesh.new(); cutter_fn(); bm.normal_update(); cb = bm
    bm = host
    po = _bm_object("_panel", pb); co = _bm_object("_cut", cb)
    mod = po.modifiers.new("b", "BOOLEAN"); mod.operation = "DIFFERENCE"; mod.solver = "EXACT"; mod.object = co
    mod.material_mode = "INDEX"
    dg = bpy.context.evaluated_depsgraph_get()
    me = po.evaluated_get(dg).to_mesh()
    vmap = [bm.verts.new(v.co) for v in me.vertices]
    for p in me.polygons:
        try:
            f = bm.faces.new([vmap[i] for i in p.vertices]); f.material_index = p.material_index
        except ValueError: pass
    po.evaluated_get(dg).to_mesh_clear()
    for o in (po, co):
        m = o.data; bpy.data.objects.remove(o); bpy.data.meshes.remove(m)

# ------------------------------------------------------------------ roofs
def hip_roof(x0, x1, y0, y1, z0, slope_deg, mat, eave=0.4):
    """hipped roof over an axis-aligned rectangle"""
    x0 -= eave; x1 += eave; y0 -= eave; y1 += eave
    w = x1 - x0; dpt = y1 - y0
    t = math.tan(math.radians(slope_deg))
    if w >= dpt:
        h = dpt / 2 * t; r0 = Vector((x0 + dpt / 2, (y0 + y1) / 2, z0 + h)); r1 = Vector((x1 - dpt / 2, (y0 + y1) / 2, z0 + h))
        A, B, C, D = Vector((x0, y0, z0)), Vector((x1, y0, z0)), Vector((x1, y1, z0)), Vector((x0, y1, z0))
        oface([A, B, r1, r0], Vector((0, -1, 1)), mat); oface([C, D, r0, r1], Vector((0, 1, 1)), mat)
        oface([B, C, r1], Vector((1, 0, 1)), mat); oface([D, A, r0], Vector((-1, 0, 1)), mat)
    else:
        h = w / 2 * t; r0 = Vector(((x0 + x1) / 2, y0 + w / 2, z0 + h)); r1 = Vector(((x0 + x1) / 2, y1 - w / 2, z0 + h))
        A, B, C, D = Vector((x0, y0, z0)), Vector((x1, y0, z0)), Vector((x1, y1, z0)), Vector((x0, y1, z0))
        oface([B, C, r1, r0], Vector((1, 0, 1)), mat); oface([D, A, r0, r1], Vector((-1, 0, 1)), mat)
        oface([A, B, r0], Vector((0, -1, 1)), mat); oface([C, D, r1], Vector((0, 1, 1)), mat)
    oface([Vector((x0, y0, z0)), Vector((x1, y0, z0)), Vector((x1, y1, z0)), Vector((x0, y1, z0))], Vector((0, 0, -1)), mat)

# ------------------------------------------------------------------ UVs + bake
def _uv_area(me):
    uv = me.uv_layers.active.data; s = 0.0
    for p in me.polygons:
        pts = [uv[li].uv for li in p.loop_indices]
        s += abs(sum(pts[i].x * pts[(i + 1) % len(pts)].y - pts[(i + 1) % len(pts)].x * pts[i].y for i in range(len(pts)))) / 2
    return s

def finish(blend_out, atlas_path, res):
    bpy.ops.object.select_all(action="DESELECT")
    for ob, w, _ in PARTS:
        bpy.context.view_layer.objects.active = ob; ob.select_set(True)
        bpy.ops.object.mode_set(mode="EDIT"); bpy.ops.mesh.select_all(action="SELECT")
        bpy.ops.uv.smart_project(angle_limit=math.radians(60), island_margin=0.0, area_weight=0.0, scale_to_bounds=False)
        bpy.ops.object.mode_set(mode="OBJECT"); ob.select_set(False)
        a3 = sum(p.area for p in ob.data.polygons); auv = max(_uv_area(ob.data), 1e-9)
        s = math.sqrt(w * a3 / auv)
        for l in ob.data.uv_layers.active.data: l.uv = l.uv * s
    for ob, _, _ in PARTS: ob.select_set(True)
    bpy.context.view_layer.objects.active = PARTS[0][0]
    bpy.ops.object.mode_set(mode="EDIT"); bpy.ops.mesh.select_all(action="SELECT")
    bpy.context.scene.tool_settings.use_uv_select_sync = True
    bpy.ops.uv.pack_islands(rotate=True, scale=True, margin_method="FRACTION", margin=0.0005, shape_method="CONVEX")
    bpy.ops.object.mode_set(mode="OBJECT")
    tris = sum(sum(len(p.vertices) - 2 for p in ob.data.polygons) for ob, _, _ in PARTS)
    tris_all = tris + sum(sum(len(p.vertices) - 2 for p in o.data.polygons) for o in INSTANCES)
    img = bpy.data.images.new("atlas", res, res, alpha=False)
    for name, (m, layer) in MATS.items():
        n = m.node_tree.nodes.new("ShaderNodeTexImage"); n.image = img; m.node_tree.nodes.active = n
    sc = bpy.context.scene
    sc.render.engine = "CYCLES"; sc.cycles.device = "CPU"; sc.cycles.samples = int(os.environ.get("BAKE_SAMPLES", "4"))
    bpy.ops.object.select_all(action="DESELECT")
    for ob, _, b in PARTS:
        if b: ob.select_set(True)
    bpy.context.view_layer.objects.active = PARTS[0][0]
    if os.environ.get("NO_BAKE") != "1":
        bpy.ops.object.bake(type="EMIT", margin=4)
    img.filepath_raw = atlas_path; img.file_format = "PNG"; img.save()
    bpy.ops.wm.save_as_mainfile(filepath=blend_out)
    print("saved", blend_out, atlas_path, "unique triangles", tris, "exported triangles", tris_all)

def soffit(F, curve, d0, d1, mat):
    """intrados strip of an opening: the (u, z) curve swept from depth d0 to d1, faces toward the opening axis"""
    n = len(curve)
    for k in range(n - 1):
        (u0, z0), (u1, z1) = curve[k], curve[k + 1]
        t = Vector((u1 - u0, z1 - z0)).normalized(); nn = Vector((-t.y, t.x))            # left of travel = toward the opening
        oface([F.P(u0, d0, z0), F.P(u1, d0, z1), F.P(u1, d1, z1), F.P(u0, d1, z0)], F.a * nn.x + Z_UP * nn.y, mat)

def ribbed_lathe(cx, cy, z0, prof, mat, segs=16):
    lathe(cx, cy, z0, prof, mat, segs)

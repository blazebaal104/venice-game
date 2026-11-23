"""Preview renders of the bridge kit (reads the baked blend + atlas).
usage: python render_bridges.py -- bridges.blend atlas.png outdir [name,name...]
Per bridge: <name>_hero.png (3/4 view with water + quays) and <name>_stretch.png (side elevation: 0.8x / nominal / 1.4x length)."""
import bpy, math, sys, os, ast
from mathutils import Vector

src, atlas, outdir = sys.argv[sys.argv.index("--") + 1:][:3]
only = sys.argv[sys.argv.index("--") + 4].split(",") if len(sys.argv) > sys.argv.index("--") + 4 else []
os.makedirs(outdir, exist_ok=True)
bpy.ops.wm.open_mainfile(filepath=src)
sc = bpy.context.scene
sc.render.engine = "CYCLES"; sc.cycles.device = "CPU"; sc.cycles.samples = int(os.environ.get("RS", "24"))
try: sc.cycles.use_denoising = True
except Exception: pass
sc.render.resolution_percentage = 100
G = 1.1

img = bpy.data.images.load(atlas)
def mk(name, build):
    m = bpy.data.materials.new(name); m.use_nodes = True; nt = m.node_tree
    for n in list(nt.nodes): nt.nodes.remove(n)
    out = nt.nodes.new("ShaderNodeOutputMaterial"); bs = nt.nodes.new("ShaderNodeBsdfPrincipled"); nt.links.new(bs.outputs[0], out.inputs[0])
    build(nt, bs); return m
def mat_atlas(nt, bs):
    t = nt.nodes.new("ShaderNodeTexImage"); t.image = img; t.interpolation = "Smart"
    nt.links.new(t.outputs[0], bs.inputs["Base Color"]); bs.inputs["Roughness"].default_value = 0.85
M_ATLAS = mk("atlas_prev", mat_atlas)
def mat_water(nt, bs):
    bs.inputs["Base Color"].default_value = (0.05, 0.11, 0.10, 1); bs.inputs["Roughness"].default_value = 0.12
M_WATER = mk("water", mat_water)
def mat_quay(nt, bs):
    bs.inputs["Base Color"].default_value = (0.30, 0.29, 0.27, 1); bs.inputs["Roughness"].default_value = 0.9
M_QUAY = mk("quay", mat_quay)

bridges = [o for o in bpy.data.objects if o.type == "MESH" and "bridge" in o]
for o in bridges:
    o.data.materials.clear(); o.data.materials.append(M_ATLAS)
    for p in o.data.polygons: p.material_index = 0
ALL = list(bridges)
bridges = [o for o in bridges if not only or o["bridge"] in only]

def add_box(name, x0, x1, y0, y1, z0, z1, mat):
    me = bpy.data.meshes.new(name)
    vs = [(x, y, z) for z in (z0, z1) for y in (y0, y1) for x in (x0, x1)]
    fs = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
    me.from_pydata(vs, [], fs); me.update(); me.materials.append(mat)
    ob = bpy.data.objects.new(name, me); sc.collection.objects.link(ob); return ob

def setup_world():
    w = bpy.data.worlds.new("w"); w.use_nodes = True; sc.world = w
    bg = w.node_tree.nodes["Background"]; bg.inputs[0].default_value = (0.62, 0.72, 0.85, 1); bg.inputs[1].default_value = 1.1
def sun():
    s = bpy.data.lights.new("sun", "SUN"); s.energy = 3.4; s.angle = 0.05
    o = bpy.data.objects.new("sun", s); sc.collection.objects.link(o); o.rotation_euler = (math.radians(50), math.radians(8), math.radians(-35)); return o
setup_world(); SUN = sun()

def cam_at(loc, target, lens=35, ortho=None):
    cd = bpy.data.cameras.new("c")
    if ortho: cd.type = "ORTHO"; cd.ortho_scale = ortho
    else: cd.lens = lens
    co = bpy.data.objects.new("c", cd); sc.collection.objects.link(co); co.location = loc
    d = Vector(target) - Vector(loc); co.rotation_euler = d.to_track_quat("-Z", "Y").to_euler(); sc.camera = co; return co

def clear_extras(keep):
    for o in list(bpy.data.objects):
        if o.name.startswith("x_"): bpy.data.objects.remove(o, do_unlink=True)

def render(path, w, h):
    sc.render.resolution_x = w; sc.render.resolution_y = h; sc.render.filepath = path
    bpy.ops.render.render(write_still=True)

for o in bridges:
    name = o["bridge"]; spec = ast.literal_eval(o["spec"])
    L = spec["L"]; W = spec["W"]; extra = spec.get("extra", 0)
    for b in ALL: b.hide_render = (b is not o)
    o.hide_render = False; o.location = (0, 0, 0); o.scale = (1, 1, 1)
    # ---- hero
    water = add_box("x_water", -L - 40, L + 40, -60, 60, -3.0, 0.0, M_WATER)
    qx = 14.0
    long_ = spec.get("long", False)
    q = []
    if not long_:
        for sx in (-1, 1):
            q.append(add_box("x_q%d" % sx, sx * (L / 2 + 0.01) if sx > 0 else -L / 2 - qx, (L / 2 + qx) if sx > 0 else -L / 2 - 0.01, -W / 2 - 7, W / 2 + 7, -3.0, G, M_QUAY))
    d = max(L * 0.72, W * 2.4) + 4.0
    if long_: d = max(L * 0.9, W * 2.4, spec.get("height", 8) * 3.6, 36)
    h = spec.get("hero_h", 0.38)
    cam = cam_at((-d * 0.62, -d * 0.92, d * h + 2.2), (0, 0, spec.get("look_z", 1.8)), lens=spec.get("lens", 35))
    SUN.rotation_euler = (math.radians(48), math.radians(6), math.radians(-40))
    render(os.path.join(outdir, name + "_hero.png"), 1100, 620)
    for x in [water] + q: bpy.data.objects.remove(x, do_unlink=True)
    bpy.data.objects.remove(cam, do_unlink=True)
    if long_:
        continue
    # ---- low view from the canal (specials / underside)
    if spec.get("low"):
        for x in [add_box("x_water", -L - 40, L + 40, -60, 60, -3.0, 0.0, M_WATER)]: pass
        for sx in (-1, 1):
            add_box("x_q%d" % sx, sx * (L / 2 + 0.01) if sx > 0 else -L / 2 - qx, (L / 2 + qx) if sx > 0 else -L / 2 - 0.01, -W / 2 - 7, W / 2 + 7, -3.0, G, M_QUAY)
        cam = cam_at((-L * 0.30, -L * 0.62, 1.4), (0, 0, spec.get("look_z", 2.0) + 1.0), lens=24)
        render(os.path.join(outdir, name + "_low.png"), 1100, 620)
        clear_extras(None); bpy.data.objects.remove(cam, do_unlink=True)
    # ---- stretch strip: elevation with three length variants (the mesh stretched along X)
    vs = []
    scales = [0.8, 1.0, 1.4]; gap = 2.5
    total = sum(L * s for s in scales) + gap * 2; x = -total / 2
    for s in scales:
        ob = o.copy(); ob.data = o.data; sc.collection.objects.link(ob); ob.name = "x_v%s" % s
        ob.scale = (s, 1, 1); ob.location = (x + L * s / 2, 0, 0); ob.hide_render = False; x += L * s + gap; vs.append(ob)
    o.hide_render = True
    gl = add_box("x_gr", -total, total, -4, 4, -0.5, 0.0, M_WATER)
    span_z = spec.get("height", 8.0)
    ow = total * 1.06
    cam = cam_at((0, -120, span_z / 2 - 0.4), (0, 0, span_z / 2 - 0.4), ortho=ow)
    render(os.path.join(outdir, name + "_stretch.png"), 1400, max(180, int(1400 * (span_z + 2.0) / ow) // 2 * 2))
    for x in vs + [gl]: bpy.data.objects.remove(x, do_unlink=True)
    bpy.data.objects.remove(cam, do_unlink=True)
    o.hide_render = False
print("rendered", [b["bridge"] for b in bridges])

"""Export every bridge of bridges.blend to game meshes (same format as export_atlas_mesh.py).
Non-modular bridge  -> bridge_<name>.mesh
Modular long bridge -> bridge_<name>_a.mesh (x <= -c), _m.mesh (-c..c, repeated), _z.mesh (x >= c)   (c = spec["slice"])
Also writes bridge_types.txt:  name  L  W  [slice]
usage: python export_bridges.py -- in.blend outdir"""
import bpy, bmesh, struct, zlib, sys, os, ast

src, outdir = sys.argv[sys.argv.index("--") + 1:][:2]
os.makedirs(outdir, exist_ok=True)
bpy.ops.wm.open_mainfile(filepath=src)
dg = bpy.context.evaluated_depsgraph_get()

def tris_of(ob):
    layers = list(ob["layers"])
    ev = ob.evaluated_get(dg); me = ev.to_mesh(); me.transform(ob.matrix_world)
    bm = bmesh.new(); bm.from_mesh(me); bmesh.ops.triangulate(bm, faces=bm.faces[:]); bm.to_mesh(me); bm.free()
    me.calc_loop_triangles()
    uv = (me.uv_layers.get("atlas") or me.uv_layers.active).data
    out = []
    for t in me.loop_triangles:
        layer = layers[min(t.material_index, len(layers) - 1)]
        n = tuple(t.normal); vs = []
        for li in t.loops:
            v = me.vertices[me.loops[li].vertex_index].co; u = uv[li].uv
            vs.append((v.x, v.y, v.z, n[0], n[1], n[2], u.x, 1.0 - u.y, 200.0 + layer, 1.0, 1.0, 1.0))
        out.append(vs)
    ev.to_mesh_clear()
    return out

def lerp(a, b, t): return tuple(x + (y - x) * t for x, y in zip(a, b))

def clip(tris, c, keep_ge):
    """keep the part of each triangle with x >= c (keep_ge) or x <= c"""
    res = []
    sgn = 1.0 if keep_ge else -1.0
    for tri in tris:
        d = [(v[0] - c) * sgn for v in tri]
        poly = []
        for i in range(3):
            a, b = tri[i], tri[(i + 1) % 3]; da, db = d[i], d[(i + 1) % 3]
            if da >= 0: poly.append(a)
            if (da >= 0) != (db >= 0):
                poly.append(lerp(a, b, da / (da - db)))
        for k in range(1, len(poly) - 1):
            res.append([poly[0], poly[k], poly[k + 1]])
    return res

def write(path, tris):
    buf = bytearray()
    for tri in tris:
        for v in tri: buf += struct.pack(">12f", *v)
    data = struct.pack(">i", len(tris) * 3) + bytes(buf)
    open(path, "wb").write(zlib.compress(data, 9))
    return len(tris)

lines = []
for ob in sorted([o for o in bpy.data.objects if o.type == "MESH" and "bridge" in o], key=lambda o: o["bridge"]):
    name = ob["bridge"]; spec = ast.literal_eval(ob["spec"])
    tris = tris_of(ob)
    c = spec.get("slice")
    if c:
        a = clip(tris, -c, False); m = clip(clip(tris, -c, True), c, False); z = clip(tris, c, True)
        na = write(os.path.join(outdir, "bridge_%s_a.mesh" % name), a); nm = write(os.path.join(outdir, "bridge_%s_m.mesh" % name), m); nz = write(os.path.join(outdir, "bridge_%s_z.mesh" % name), z)
        lines.append("%s %.2f %.2f %.2f" % (name, spec["L"], spec["W"], c)); print(name, "slices", na, nm, nz)
    else:
        n = write(os.path.join(outdir, "bridge_%s.mesh" % name), tris)
        lines.append("%s %.2f %.2f" % (name, spec["L"], spec["W"])); print(name, n)
open(os.path.join(outdir, "bridge_types.txt"), "w").write("# name  L(nominal)  W  [slice half-length c: _a = x<=-c, _m = -c..c repeated every 2c, _z = x>=c]\n" + "\n".join(lines) + "\n")

"""Export a baked-atlas landmark (.blend from build_*_v2.py) to the game's mesh format.
Per vertex: position, normal, atlas UV, attr = (200 + detail layer, 1, 1, 1).  The detail layer per material comes from
the object's "layers" property written by the build script.
usage: python export_atlas_mesh.py -- in.blend out.mesh"""
import bpy, bmesh, struct, zlib, sys

src, out = sys.argv[sys.argv.index("--") + 1:][:2]
bpy.ops.wm.open_mainfile(filepath=src)
dg = bpy.context.evaluated_depsgraph_get()
buf = bytearray(); count = 0
for ob in bpy.data.objects:
    if ob.type != "MESH" or "layers" not in ob: continue
    layers = list(ob["layers"])
    ev = ob.evaluated_get(dg); me = ev.to_mesh(); me.transform(ob.matrix_world)
    bm = bmesh.new(); bm.from_mesh(me); bmesh.ops.triangulate(bm, faces=bm.faces[:]); bm.to_mesh(me); bm.free()
    me.calc_loop_triangles()
    uvl = me.uv_layers.get("atlas") or me.uv_layers.active
    uv = uvl.data
    suv = me.uv_layers["signuv"].data if "signuv" in me.uv_layers else None
    mats = [m.name if m else "" for m in me.materials]
    for t in me.loop_triangles:
        layer = layers[min(t.material_index, len(layers) - 1)]
        sign = suv is not None and mats[t.material_index].startswith("sign")
        attr = ((300.0 if sign else 200.0) + layer, 1.0, 1.0, 1.0)
        n = t.normal
        for li in t.loops:
            v = me.vertices[me.loops[li].vertex_index].co
            u = (suv if sign else uv)[li].uv
            buf += struct.pack(">12f", v.x, v.y, v.z, n.x, n.y, n.z, u.x, 1.0 - u.y, *attr)
            count += 1
    ev.to_mesh_clear()
data = struct.pack(">i", count) + bytes(buf)
open(out, "wb").write(zlib.compress(data, 9))
print("exported", count // 3, "triangles", len(data) // 1024, "KB raw")

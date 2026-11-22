"""Export boat.blend to the game's mesh format: zlib-deflated, big-endian:
int vertexCount, then per vertex 12 floats: pos xyz, normal xyz, uv(0,0), m r g b (generic shader:
m >= 0 texture layer tinted by rgb, m < 0 plain colour with gloss = -m-1).
Materials listed in LAYER_MAP become textured; everything else keeps its colour."""
import bpy, bmesh, struct, zlib, sys

src, out = sys.argv[sys.argv.index("--") + 1:][:2]
# material name -> (texture layer, tint)   (layers: see tools/textures/pack_textures.py)
LAYER_MAP = {
    "istrian": (15, (1.0, 0.98, 0.94)), "istrian_weathered": (16, (0.82, 0.80, 0.76)), "istrian_carved": (15, (0.86, 0.84, 0.80)),
    "trachyte": (22, (1.0, 1.0, 1.0)), "lead_roof": (31, (0.75, 0.77, 0.8)), "shutter_wood": (32, (0.075, 0.080, 0.075)),
}
bpy.ops.wm.open_mainfile(filepath=src)

def mat_props(m):
    if m is None or not m.use_nodes:
        return (0.7, 0.7, 0.7, 0.1)
    p = m.node_tree.nodes.get("Principled BSDF")
    if p is None:
        return (0.7, 0.7, 0.7, 0.1)
    c = list(p.inputs["Base Color"].default_value)[:3]
    if m.name == "GelRed":
        c = [0.42, 0.012, 0.016]
    rough = p.inputs["Roughness"].default_value
    metal = p.inputs["Metallic"].default_value
    coat = p.inputs["Coat Weight"].default_value
    trans = p.inputs["Transmission Weight"].default_value
    gloss = max(0.0, 1.0 - rough) * (0.6 + 0.4 * metal)
    gloss = max(gloss, coat * 0.85)
    if trans > 0.5:   # acrylic / gauge glass -> dark tinted, very glossy
        c = [0.10, 0.12, 0.13]; gloss = 0.95
    if metal > 0.5:   # metals read darker in albedo; spec carries them
        c = [x * 0.75 for x in c]
    # linear -> the game's shading works in roughly linear space already; brighten very dark gelcoat a bit
    return (c[0], c[1], c[2], min(gloss, 0.95))

dg = bpy.context.evaluated_depsgraph_get()
buf = bytearray()
count = 0
for ob in bpy.data.objects:
    if ob.type not in ("MESH", "CURVE", "FONT"):
        continue
    ev = ob.evaluated_get(dg)
    me = ev.to_mesh()
    if me is None:
        continue
    me.transform(ob.matrix_world)
    bm = bmesh.new(); bm.from_mesh(me)
    bmesh.ops.triangulate(bm, faces=bm.faces[:])
    bm.to_mesh(me); bm.free()
    me.calc_loop_triangles()
    cn = me.corner_normals
    props = []
    for sl in ev.material_slots:
        nm = sl.material.name if sl.material else ""
        if nm in LAYER_MAP:
            layer, tint = LAYER_MAP[nm]; props.append(("L", layer, tint))
        else:
            props.append(("C",) + tuple(mat_props(sl.material)))
    props = props or [("C", 0.7, 0.7, 0.7, 0.1)]
    smooth = [p.use_smooth for p in me.polygons]
    for t in me.loop_triangles:
        pr = props[min(t.material_index, len(props) - 1)]
        if pr[0] == "L": attr = (float(pr[1]), pr[2][0], pr[2][1], pr[2][2])
        else: attr = (-1.0 - pr[4], pr[1], pr[2], pr[3])
        # bottom of the hull is white gelcoat (material slot 1 on the hull already), nothing special needed
        for li in t.loops:
            v = me.vertices[me.loops[li].vertex_index].co
            n = cn[li].vector if smooth[t.polygon_index] else t.normal
            buf += struct.pack(">12f", v.x, v.y, v.z, n.x, n.y, n.z, 0.0, 0.0, *attr)
            count += 1
    ev.to_mesh_clear()

data = struct.pack(">i", count) + bytes(buf)
open(out, "wb").write(zlib.compress(data, 9))
print("exported", count, "vertices", count // 3, "triangles", len(data) // 1024, "KB raw")

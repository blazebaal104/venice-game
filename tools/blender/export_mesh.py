"""Export boat.blend to the game's mesh format: zlib-deflated, big-endian:
int vertexCount, then per vertex 12 floats: pos xyz, normal xyz, uv(0,0), r g b gloss (generic shader)."""
import bpy, bmesh, struct, zlib, sys

src, out = sys.argv[sys.argv.index("--") + 1:][:2]
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
    props = [mat_props(s.material) for s in ev.material_slots] or [(0.7, 0.7, 0.7, 0.1)]
    smooth = [p.use_smooth for p in me.polygons]
    for t in me.loop_triangles:
        col = props[min(t.material_index, len(props) - 1)]
        # bottom of the hull is white gelcoat (material slot 1 on the hull already), nothing special needed
        for li in t.loops:
            v = me.vertices[me.loops[li].vertex_index].co
            n = cn[li].vector if smooth[t.polygon_index] else t.normal
            buf += struct.pack(">12f", v.x, v.y, v.z, n.x, n.y, n.z, 0.0, 0.0, col[0], col[1], col[2], col[3])
            count += 1
    ev.to_mesh_clear()

data = struct.pack(">i", count) + bytes(buf)
open(out, "wb").write(zlib.compress(data, 9))
print("exported", count, "vertices", count // 3, "triangles", len(data) // 1024, "KB raw")

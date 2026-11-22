"""Ponte di Rialto, built by script from published measurements and street-level photo references.
Local frame: +X along the bridge (towards the San Polo side), +Y across, +Z up, mean water at z=0.
Facts used: length 48 m, width 22.9 m, single arch, span 28.8 m, rise 6.4 m, clearance ~7.3 m,
two covered rows of 24 shops in total (6 bays per row per ramp), central portico with arches,
pilasters and triangular pediments, three walkways (two outer along balustrades, one central between the shops),
Istrian stone throughout, rusticated abutments with stairs, archivolt with relief sculptures on the spandrels.
Materials are tagged per face with names that the game maps onto its texture layers.
"""
import bpy, bmesh, math, sys, os
from mathutils import Vector, Matrix

OUT = sys.argv[sys.argv.index("--") + 1] if "--" in sys.argv else "/home/claude/venice/rialto.blend"
bpy.ops.wm.read_factory_settings(use_empty=True)
col = bpy.data.collections.new("Rialto"); bpy.context.scene.collection.children.link(col)

# ---------------------------------------------------------------- dimensions
HALF_L = 24.0            # half length (stairs included)
HALF_W = 11.45           # half width
SPAN_HALF = 14.4         # half of the 28.8 m span
RISE = 6.4
SPRING_Z = 0.92          # springing line just above mean water -> crown at 7.32
CROWN = SPRING_Z + RISE
R_ARCH = ((SPAN_HALF * 2) ** 2 / 4 + RISE ** 2) / (2 * RISE)  # 19.4 m
ARCH_CZ = CROWN - R_ARCH
CREST_Z = 8.55           # walkway at the top
END_Z = 1.10             # fondamenta level
CREST_HALF = 3.2         # flat landing under the central portico
ROW_IN, ROW_OUT = 3.0, 7.6     # shop rows between |y| = 3.0 and 7.6
BAL_IN = 11.0                  # balustrade inner face
BAYS = 6; BAY_L = 3.2          # shop bays per ramp
STEP_H = 0.15

def intrados(x):
    if abs(x) >= SPAN_HALF: return SPRING_Z
    return ARCH_CZ + math.sqrt(max(R_ARCH ** 2 - x * x, 0))

def walk(x):
    """Stepped walkway height at x."""
    ax = abs(x)
    if ax <= CREST_HALF: return CREST_Z
    f = min(1.0, (ax - CREST_HALF) / (HALF_L - CREST_HALF))
    raw = CREST_Z - (CREST_Z - END_Z) * f
    return END_Z + math.floor((raw - END_Z) / STEP_H + 0.5) * STEP_H

# ---------------------------------------------------------------- materials (preview look; game maps names to layers)
def mat(name, color, rough=0.6):
    m = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    m.use_nodes = True
    p = m.node_tree.nodes["Principled BSDF"]
    p.inputs["Base Color"].default_value = (*color, 1); p.inputs["Roughness"].default_value = rough
    return m
M = {
    "stone": mat("istrian", (0.80, 0.78, 0.72), 0.55),
    "stone_dark": mat("istrian_weathered", (0.55, 0.54, 0.50), 0.7),
    "algae": mat("algae", (0.12, 0.17, 0.10), 0.4),
    "shutter": mat("shutter_wood", (0.52, 0.53, 0.50), 0.6),
    "grille": mat("iron_dark", (0.06, 0.06, 0.06), 0.4),
    "lead": mat("lead_roof", (0.38, 0.39, 0.40), 0.5),
    "paving": mat("trachyte", (0.40, 0.40, 0.39), 0.75),
    "relief": mat("istrian_carved", (0.70, 0.68, 0.62), 0.6),
}

def new_obj(name, bm, smooth=False):
    me = bpy.data.meshes.new(name); bm.to_mesh(me); bm.free()
    for m in M.values(): me.materials.append(m)
    if smooth: me.shade_smooth(); me.set_sharp_from_angle(angle=math.radians(35))
    ob = bpy.data.objects.new(name, me); col.objects.link(ob); return ob

MID = {k: i for i, k in enumerate(M.keys())}

def quad(bm, a, b, c, d, m):
    f = bm.faces.new([bm.verts.new(a), bm.verts.new(b), bm.verts.new(c), bm.verts.new(d)]); f.material_index = MID[m]; return f

def box(bm, x0, x1, y0, y1, z0, z1, m, mside=None):
    ms = mside or m
    P = lambda x, y, z: Vector((x, y, z))
    quad(bm, P(x0, y0, z1), P(x1, y0, z1), P(x1, y1, z1), P(x0, y1, z1), m)
    quad(bm, P(x0, y0, z0), P(x0, y1, z0), P(x1, y1, z0), P(x1, y0, z0), m)
    quad(bm, P(x0, y0, z0), P(x1, y0, z0), P(x1, y0, z1), P(x0, y0, z1), ms)
    quad(bm, P(x1, y1, z0), P(x0, y1, z0), P(x0, y1, z1), P(x1, y1, z1), ms)
    quad(bm, P(x0, y1, z0), P(x0, y0, z0), P(x0, y0, z1), P(x0, y1, z1), ms)
    quad(bm, P(x1, y0, z0), P(x1, y1, z0), P(x1, y1, z1), P(x1, y0, z1), ms)

def arch_outline(cx, w, spring, n=16, pointed=False):
    """Points of a round arch head (left spring -> right spring)."""
    r = w / 2
    return [(cx - r * math.cos(math.pi * i / n), spring + r * math.sin(math.pi * i / n)) for i in range(n + 1)]

# ================================================================ 1. arch barrel, spandrels, deck structure
bm = bmesh.new()
N = 64
xs = [-HALF_L + 2 * HALF_L * i / N for i in range(N + 1)]
# underside (intrados) across the full width
for i in range(N):
    x0, x1 = xs[i], xs[i + 1]
    if abs(x0) > SPAN_HALF + 0.01 and abs(x1) > SPAN_HALF + 0.01: continue
    x0 = max(-SPAN_HALF, min(SPAN_HALF, x0)); x1 = max(-SPAN_HALF, min(SPAN_HALF, x1))
    z0, z1 = intrados(x0), intrados(x1)
    quad(bm, Vector((x1, -HALF_W, z1)), Vector((x0, -HALF_W, z0)), Vector((x0, HALF_W, z0)), Vector((x1, HALF_W, z1)), "stone")
# side faces (spandrels + abutments) from the intrados / water up to the deck edge, both sides
def deck_edge(x): return walk(x) - 0.35
for side in (-1, 1):
    y = side * HALF_W
    for i in range(N):
        x0, x1 = xs[i], xs[i + 1]
        b0 = intrados(x0) if abs(x0) < SPAN_HALF else -1.5
        b1 = intrados(x1) if abs(x1) < SPAN_HALF else -1.5
        t0, t1 = deck_edge(x0), deck_edge(x1)
        if side > 0: quad(bm, Vector((x0, y, b0)), Vector((x1, y, b1)), Vector((x1, y, t1)), Vector((x0, y, t0)), "stone")
        else: quad(bm, Vector((x1, y, b1)), Vector((x0, y, b0)), Vector((x0, y, t0)), Vector((x1, y, t1)), "stone")
# abutment end walls (towards the fondamenta) and the stepped deck surface + risers for 3 walkways
for i in range(N):
    x0, x1 = xs[i], xs[i + 1]
    z = walk((x0 + x1) / 2)
    quad(bm, Vector((x0, -HALF_W, z)), Vector((x1, -HALF_W, z)), Vector((x1, HALF_W, z)), Vector((x0, HALF_W, z)), "paving")
# fine steps along x (0.42 m treads)
steps = []
x = -HALF_L
while x < HALF_L:
    nx = x + 0.42
    steps.append((x, nx)); x = nx
for (x0, x1) in steps:
    z = walk((x0 + x1) / 2)
    pass
ob_struct = new_obj("RialtoStructure", bm)

# true staircase treads/risers (over the coarse deck) for all three walkways
bm = bmesh.new()
x = -HALF_L
prevz = walk(x)
while x < HALF_L - 1e-6:
    nx = min(HALF_L, x + 0.40)
    z = walk((x + nx) / 2)
    quad(bm, Vector((x, -BAL_IN, z + 0.02)), Vector((nx, -BAL_IN, z + 0.02)), Vector((nx, BAL_IN, z + 0.02)), Vector((x, BAL_IN, z + 0.02)), "paving")
    if abs(z - prevz) > 0.01:   # riser with an Istrian stone nose
        lo, hi = min(z, prevz), max(z, prevz)
        quad(bm, Vector((x, -BAL_IN, lo + 0.02)), Vector((x, BAL_IN, lo + 0.02)), Vector((x, BAL_IN, hi + 0.02)), Vector((x, -BAL_IN, hi + 0.02)), "stone")
    prevz = z; x = nx
new_obj("RialtoSteps", bm)

# ================================================================ 2. archivolt, keystone, string course, reliefs (both faces)
bm = bmesh.new()
for side in (-1, 1):
    y0 = side * HALF_W; y1 = side * (HALF_W + 0.18)
    n = 40
    for i in range(n):
        a0 = math.pi * i / n; a1 = math.pi * (i + 1) / n
        # archivolt band follows the intrados, 0.95 m deep in the face
        def P(a, off, yy):
            x = -SPAN_HALF + (2 * SPAN_HALF) * (1 - math.cos(a)) / 2
            zi = intrados(x)
            # normal of the arch circle in the x-z plane
            nx = x / R_ARCH; nz = (zi - ARCH_CZ) / R_ARCH
            return Vector((x + nx * off, yy, zi + nz * off))
        quad(bm, P(a0, 0, y1), P(a1, 0, y1), P(a1, 0.95, y1), P(a0, 0.95, y1), "stone") if side > 0 else quad(bm, P(a1, 0, y1), P(a0, 0, y1), P(a0, 0.95, y1), P(a1, 0.95, y1), "stone")
        quad(bm, P(a0, 0, y0), P(a1, 0, y0), P(a1, 0, y1), P(a0, 0, y1), "stone")
        quad(bm, P(a1, 0.95, y0), P(a0, 0.95, y0), P(a0, 0.95, y1), P(a1, 0.95, y1), "stone")
    # keystone with a carved mask (the "mascherone")
    box(bm, -0.7, 0.7, min(y0, y1 + side * 0.1), max(y0, y1 + side * 0.1), CROWN - 0.1, CROWN + 1.5, "relief")
    # relief panels on the spandrels: Annunciation (angel / Virgin), Saints Mark and Theodore near the abutments
    for cx, h in ((-7.6, 1.7), (7.6, 1.7), (-12.2, 1.4), (12.2, 1.4)):
        zb = intrados(cx) + 1.4
        box(bm, cx - 0.55, cx + 0.55, min(y0, y1), max(y0, y1), zb, zb + h, "relief")
        box(bm, cx - 0.75, cx + 0.75, min(y0, y1), max(y0, y1), zb - 0.18, zb, "stone")   # corbel
    # sloping string course (cornice) just under the balustrade, following the deck line
    for i in range(N):
        x0, x1 = xs[i], xs[i + 1]
        za, zb2 = deck_edge(x0), deck_edge(x1)
        yo = side * (HALF_W + 0.28)
        quad(bm, Vector((x0, y0, za - 0.05)), Vector((x1, y0, zb2 - 0.05)), Vector((x1, yo, zb2 - 0.05)), Vector((x0, yo, za - 0.05)), "stone")
        quad(bm, Vector((x0, yo, za - 0.05)), Vector((x1, yo, zb2 - 0.05)), Vector((x1, yo, zb2 + 0.32)), Vector((x0, yo, za + 0.32)), "stone") if side > 0 else \
            quad(bm, Vector((x1, yo, zb2 - 0.05)), Vector((x0, yo, za - 0.05)), Vector((x0, yo, za + 0.32)), Vector((x1, yo, zb2 + 0.32)), "stone")
        quad(bm, Vector((x0, yo, za + 0.32)), Vector((x1, yo, zb2 + 0.32)), Vector((x1, y0, zb2 + 0.32)), Vector((x0, y0, za + 0.32)), "stone")
    # weathered band + algae at the waterline along the abutments and arch springing
    for i in range(N):
        x0, x1 = xs[i], xs[i + 1]
        if max(abs(x0), abs(x1)) <= SPAN_HALF + 0.01 and min(abs(x0), abs(x1)) < SPAN_HALF - 0.01:
            continue  # open water under the arch: the band only belongs on the abutments
        yy = side * (HALF_W + 0.01)
        quad(bm, Vector((x0, yy, -0.4)), Vector((x1, yy, -0.4)), Vector((x1, yy, 0.35)), Vector((x0, yy, 0.35)), "algae") if side > 0 else \
            quad(bm, Vector((x1, yy, -0.4)), Vector((x0, yy, -0.4)), Vector((x0, yy, 0.35)), Vector((x1, yy, 0.35)), "algae")
new_obj("RialtoArchivolt", bm)

# ================================================================ 3. balustrades (outer walkways), stepped
bm = bmesh.new()
for side in (-1, 1):
    yb0 = side * BAL_IN; yb1 = side * HALF_W
    yc = (yb0 + yb1) / 2
    x = -HALF_L + 1.0
    while x < HALF_L - 1.0:
        z = walk(x)
        # baluster: base block, vase (turned), cap
        box(bm, x - 0.09, x + 0.09, min(yb0, yb1) + 0.07, max(yb0, yb1) - 0.07, z, z + 0.08, "stone")
        vase = bmesh.ops.create_cone(bm, cap_ends=True, segments=8, radius1=0.08, radius2=0.12, depth=0.28)
        bmesh.ops.translate(bm, vec=(x, yc, z + 0.22), verts=vase["verts"])
        vase2 = bmesh.ops.create_cone(bm, cap_ends=True, segments=8, radius1=0.12, radius2=0.06, depth=0.24)
        bmesh.ops.translate(bm, vec=(x, yc, z + 0.48), verts=vase2["verts"])
        for f in bm.faces:
            if f.material_index == 0 and not f.select: pass
        x += 0.34
    # posts every ~3 m and the continuous top rail + plinth following the steps
    x = -HALF_L + 1.0
    while x < HALF_L - 0.5:
        z = walk(x)
        box(bm, x - 0.22, x + 0.22, min(yb0, yb1), max(yb0, yb1), z, z + 0.92, "stone")
        x += 3.1
    for i in range(N):
        x0, x1 = xs[i], xs[i + 1]
        z = walk((x0 + x1) / 2)
        box(bm, x0, x1, min(yb0, yb1) - 0.03, max(yb0, yb1) + 0.03, z + 0.62, z + 0.82, "stone")
        box(bm, x0, x1, min(yb0, yb1), max(yb0, yb1), z - 0.02, z + 0.08, "stone")
ob = new_obj("RialtoBalustrade", bm)
for f in ob.data.polygons:
    if f.material_index != MID["stone"]: f.material_index = MID["stone"]

# ================================================================ 4. shop rows: 6 bays per row per ramp, arched openings both faces
bm = bmesh.new()
def bay(x0, x1, y0, y1, floor):
    """One shop bay: stone piers, round-arched opening with shutters and a louvred lunette on both long faces."""
    h_cornice = floor + 4.55
    top = floor + 5.0
    # solid core (back of the shop) slightly recessed so openings read as deep
    box(bm, x0 + 0.25, x1 - 0.25, y0 + 0.35, y1 - 0.35, floor, top - 0.2, "shutter")
    for face_y, inward in ((y0, 1), (y1, -1)):
        # piers at both ends of the bay
        for px0, px1 in ((x0, x0 + 0.5), (x1 - 0.5, x1)):
            box(bm, px0, px1, min(face_y, face_y + inward * 0.4), max(face_y, face_y + inward * 0.4), floor, h_cornice, "stone")
        # arch head: spring at +2.55, width = bay - piers
        w = (x1 - x0) - 1.0
        cx = (x0 + x1) / 2
        spring = floor + 2.55
        pts = arch_outline(cx, w, spring, 14)
        yo = face_y; yi = face_y + inward * 0.4
        # spandrel wall above the arch up to the cornice (fan of quads)
        for k in range(len(pts) - 1):
            (ax, az), (bx, bz) = pts[k], pts[k + 1]
            if inward > 0: quad(bm, Vector((ax, yo, az)), Vector((bx, yo, bz)), Vector((bx, yo, h_cornice)), Vector((ax, yo, h_cornice)), "stone")
            else: quad(bm, Vector((bx, yo, bz)), Vector((ax, yo, az)), Vector((ax, yo, h_cornice)), Vector((bx, yo, h_cornice)), "stone")
            # soffit of the arch (depth of the opening)
            quad(bm, Vector((ax, yo, az)), Vector((bx, yo, bz)), Vector((bx, yi, bz)), Vector((ax, yi, az)), "stone")
        # opening: closed grey wooden shutters (lower part) and a louvred lunette under the arch
        sh_y = face_y + inward * 0.38
        box(bm, cx - w / 2, cx + w / 2, min(sh_y, sh_y + inward * 0.04), max(sh_y, sh_y + inward * 0.04), floor, spring - 0.05, "shutter")
        lun = arch_outline(cx, w, spring, 10)
        for k in range(len(lun) - 1):
            (ax, az), (bx, bz) = lun[k], lun[k + 1]
            if inward > 0: quad(bm, Vector((ax, sh_y, az)), Vector((bx, sh_y, bz)), Vector((bx, sh_y, spring)), Vector((ax, sh_y, spring)), "grille")
            else: quad(bm, Vector((bx, sh_y, bz)), Vector((ax, sh_y, az)), Vector((ax, sh_y, spring)), Vector((bx, sh_y, spring)), "grille")
        box(bm, cx - w / 2, cx + w / 2, min(sh_y, sh_y + inward * 0.06), max(sh_y, sh_y + inward * 0.06), spring - 0.08, spring + 0.04, "stone")  # transom
        # keystone
        box(bm, cx - 0.16, cx + 0.16, min(face_y, face_y - inward * 0.06), max(face_y, face_y - inward * 0.06), spring + w / 2 - 0.15, spring + w / 2 + 0.35, "stone")
    # cornice band projecting on both faces + lead roof (ridge along x)
    box(bm, x0 - 0.02, x1 + 0.02, y0 - 0.22, y1 + 0.22, h_cornice, top - 0.2, "stone")
    ym = (y0 + y1) / 2
    rz = top + 0.9
    for (ya, yb) in ((y0 - 0.25, ym), (y1 + 0.25, ym)):
        if ya < yb: quad(bm, Vector((x0, ya, top - 0.2)), Vector((x1, ya, top - 0.2)), Vector((x1, yb, rz)), Vector((x0, yb, rz)), "lead")
        else: quad(bm, Vector((x1, ya, top - 0.2)), Vector((x0, ya, top - 0.2)), Vector((x0, yb, rz)), Vector((x1, yb, rz)), "lead")
    # gable ends (stepping walls) of the roof
    for xe in (x0, x1):
        bm.faces.new([bm.verts.new((xe, y0 - 0.25, top - 0.2)), bm.verts.new((xe, y1 + 0.25, top - 0.2)), bm.verts.new((xe, ym, rz))]).material_index = MID["lead"]

for side in (-1, 1):
    for row in (-1, 1):
        y0, y1 = (ROW_IN, ROW_OUT) if row > 0 else (-ROW_OUT, -ROW_IN)
        for b in range(BAYS):
            xa = CREST_HALF + b * BAY_L; xb = xa + BAY_L
            if side < 0: xa, xb = -xb, -xa
            floor = walk((xa + xb) / 2)
            bay(xa, xb, y0, y1, floor)
new_obj("RialtoShops", bm)

# ================================================================ 5. central portico with arches, pilasters, pediments
bm = bmesh.new()
pz = CREST_Z
ph = 7.6                       # portico height to the cornice
PX = CREST_HALF
PY = ROW_OUT + 0.1
# four corner masses
for sx in (-1, 1):
    for sy in (-1, 1):
        x0, x1 = sorted((sx * PX, sx * (PX - 1.3)))
        y0, y1 = sorted((sy * PY, sy * (PY - 2.0)))
        box(bm, x0, x1, y0, y1, pz, pz + ph, "stone")
        # paired pilasters on the canal faces
        for dx in (0.25, 0.95):
            xx = sx * (PX - dx)
            box(bm, xx - 0.22, xx + 0.22, min(sy * PY, sy * (PY + 0.12)), max(sy * PY, sy * (PY + 0.12)), pz, pz + ph - 0.6, "stone")
# side walls between the masses, pierced by the great arches (towards the canal, +-Y)
for sy in (-1, 1):
    y0, y1 = sorted((sy * PY, sy * (PY - 0.9)))
    w = 2 * (PX - 1.3)
    spring = pz + 3.9
    pts = arch_outline(0, w, spring, 18)
    yo = sy * PY
    for k in range(len(pts) - 1):
        (ax, az), (bx, bz) = pts[k], pts[k + 1]
        if sy > 0: quad(bm, Vector((bx, yo, bz)), Vector((ax, yo, az)), Vector((ax, yo, pz + ph)), Vector((bx, yo, pz + ph)), "stone")
        else: quad(bm, Vector((ax, yo, az)), Vector((bx, yo, bz)), Vector((bx, yo, pz + ph)), Vector((ax, yo, pz + ph)), "stone")
        quad(bm, Vector((ax, yo, az)), Vector((bx, yo, bz)), Vector((bx, sy * (PY - 0.9), bz)), Vector((ax, sy * (PY - 0.9), az)), "stone")
    box(bm, -0.25, 0.25, min(yo, yo + sy * 0.1), max(yo, yo + sy * 0.1), spring + w / 2 - 0.2, spring + w / 2 + 0.5, "relief")  # keystone
# transverse barrel vault over the crossing
for k in range(18):
    a0 = math.pi * k / 18; a1 = math.pi * (k + 1) / 18
    r = PX - 1.3
    za = pz + 4.6 + r * math.sin(a0); zb = pz + 4.6 + r * math.sin(a1)
    xa = -r * math.cos(a0); xb = -r * math.cos(a1)
    quad(bm, Vector((xa, -PY, za)), Vector((xa, PY, za)), Vector((xb, PY, zb)), Vector((xb, -PY, zb)), "stone_dark")
# entablature + attic
box(bm, -PX - 0.25, PX + 0.25, -PY - 0.3, PY + 0.3, pz + ph - 0.6, pz + ph, "stone")
box(bm, -PX, PX, -PY, PY, pz + ph, pz + ph + 0.8, "stone")
# triangular pediments on both canal faces + lead roof
pk = pz + ph + 0.8 + 2.0
for sy in (-1, 1):
    yy = sy * (PY + 0.05)
    tri = [bm.verts.new((-PX - 0.2, yy, pz + ph + 0.8)), bm.verts.new((PX + 0.2, yy, pz + ph + 0.8)), bm.verts.new((0, yy, pk))]
    if sy < 0: tri.reverse()
    bm.faces.new(tri).material_index = MID["stone"]
    # raking cornices
    for (xa, za, xb, zb) in ((-PX - 0.35, pz + ph + 0.75, 0, pk + 0.15), (0, pk + 0.15, PX + 0.35, pz + ph + 0.75)):
        quad(bm, Vector((xa, yy, za)), Vector((xb, yy, zb)), Vector((xb, yy + sy * 0.3, zb)), Vector((xa, yy + sy * 0.3, za)), "stone")
    # a small oculus / tympanum relief
    box(bm, -0.5, 0.5, min(yy, yy + sy * 0.08), max(yy, yy + sy * 0.08), pz + ph + 1.2, pz + ph + 2.0, "relief")
for sx in (-1, 1):
    a = [Vector((sx * (PX + 0.2), -PY - 0.05, pz + ph + 0.8)), Vector((sx * (PX + 0.2), PY + 0.05, pz + ph + 0.8)), Vector((0, PY + 0.05, pk)), Vector((0, -PY - 0.05, pk))]
    if sx < 0: a = [a[1], a[0], a[3], a[2]]
    quad(bm, *a, "lead")
new_obj("RialtoPortico", bm)

# ================================================================ 6. abutments: rusticated walls, stairs flanks, shrine
bm = bmesh.new()
for sx in (-1, 1):
    x0, x1 = sorted((sx * SPAN_HALF, sx * (HALF_L + 1.5)))
    for sy in (-1, 1):
        y0, y1 = sorted((sy * HALF_W, sy * (HALF_W + 0.6)))
        # horizontal rustication courses
        z = -1.2
        k = 0
        while z < END_Z - 0.05:
            h = 0.42
            inset = 0.0 if k % 2 == 0 else 0.03
            yy0 = y0 + (inset if sy > 0 else 0); yy1 = y1 - (0 if sy > 0 else inset)
            box(bm, x0, x1, yy0, yy1, z + 0.02, z + h - 0.02, "stone_dark" if z < 0.4 else "stone")
            z += h; k += 1
    # small aedicule (votive shrine) on the abutment face, seen in the photos
    for sy in (-1, 1):
        cx = sx * 18.5
        yf = sy * (HALF_W + 0.6)
        box(bm, cx - 0.6, cx + 0.6, min(yf, yf + sy * 0.25), max(yf, yf + sy * 0.25), END_Z + 0.4, END_Z + 2.2, "stone")
        tri = [bm.verts.new((cx - 0.75, yf + sy * 0.26, END_Z + 2.2)), bm.verts.new((cx + 0.75, yf + sy * 0.26, END_Z + 2.2)), bm.verts.new((cx, yf + sy * 0.26, END_Z + 2.8))]
        if sy < 0: tri.reverse()
        bm.faces.new(tri).material_index = MID["stone"]
        box(bm, cx - 0.35, cx + 0.35, min(yf + sy * 0.25, yf + sy * 0.27), max(yf + sy * 0.25, yf + sy * 0.27), END_Z + 0.7, END_Z + 1.9, "relief")
new_obj("RialtoAbutments", bm)

bpy.ops.wm.save_as_mainfile(filepath=OUT)
tris = 0
for ob in col.objects:
    ob.data.calc_loop_triangles(); tris += len(ob.data.loop_triangles)
print("Rialto saved", OUT, "triangles", tris)

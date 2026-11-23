"""Place the Blender bridge kit on the map.
Reads the OSM tiles, merges the bridge ways that touch each other into physical bridges (the Scalzi is mapped as 34 stair
fragments), picks a bridge type per bridge from its length / road class / location, and writes the placement list.

usage: python place_bridges.py data_dir bridge_types.txt landmarks.txt out_bridges.txt

out line:  mesh  x  y  rotDeg  sx  sy  sz       (model = T(x,y) * Rz(rot) * S(sx,sy,sz about the quay level z = 1.1))"""
import sys, glob, math, collections
import xml.etree.ElementTree as ET

data_dir, types_f, lm_f, out_f = sys.argv[1:5]
LAT0, LON0 = 45.43397, 12.33901
phi = math.radians(LAT0)
mlat = 111132.954 - 559.822 * math.cos(2 * phi) + 1.175 * math.cos(4 * phi)
mlon = 111412.84 * math.cos(phi) - 93.5 * math.cos(3 * phi) + 0.118 * math.cos(5 * phi)
X = lambda lon: (lon - LON0) * mlon
Y = lambda lat: (lat - LAT0) * mlat
MINX, MAXX, MINY, MAXY = X(12.285), X(12.430), Y(45.360), Y(45.475)
G = 1.1

nodes = {}; ways = {}
for f in sorted(glob.glob(data_dir + "/venice_osm_tile*.osm")):
    for ev, el in ET.iterparse(f):
        if el.tag == "node": nodes[el.get("id")] = (X(float(el.get("lon"))), Y(float(el.get("lat"))))
        elif el.tag == "way": ways[el.get("id")] = ([n.get("ref") for n in el.findall("nd")], {t.get("k"): t.get("v") for t in el.findall("tag")})
        if el.tag in ("node", "way", "relation"): el.clear()

# ---- kit
types = {}
for ln in open(types_f):
    if ln.startswith("#") or not ln.strip(): continue
    p = ln.split(); types[p[0]] = dict(L=float(p[1]), W=float(p[2]), c=float(p[3]) if len(p) > 3 else None)

# ---- landmark zones (bridges inside are the hand-built ones)
zones = []
for ln in open(lm_f):
    p = ln.split()
    if not p or p[0].startswith("#") or len(p) < 6: continue
    zones.append(tuple(float(v) for v in p[1:6]))
def in_zone(x, y):
    for cx, cy, rot, hl, hw in zones:
        r = math.radians(rot); dx, dy = x - cx, y - cy
        u = dx * math.cos(r) + dy * math.sin(r); v = -dx * math.sin(r) + dy * math.cos(r)
        if abs(u) <= hl and abs(v) <= hw: return True
    return False

# ---- merge touching bridge ways
SKIP = ("Ponte di Rialto", "Ponte della Paglia", "Ponte dei Sospiri")
br = {i: (nd, t) for i, (nd, t) in ways.items() if t.get("bridge") not in (None, "no") and t.get("highway") and t.get("name") not in SKIP}
par = {}
def find(a):
    while par.setdefault(a, a) != a: par[a] = par[par[a]]; a = par[a]
    return a
for i, (nd, t) in br.items():
    for n in nd[1:]: par[find(n)] = find(nd[0])
comp = collections.defaultdict(list)
for i, (nd, t) in br.items(): comp[find(nd[0])].append(i)

def order(ids):
    """ordered node list if the component is a simple path, else None"""
    adj = collections.defaultdict(set)
    for i in ids:
        nd = [n for n in br[i][0] if n in nodes]
        for a, b in zip(nd[:-1], nd[1:]):
            if a != b: adj[a].add(b); adj[b].add(a)
    if any(len(v) > 2 for v in adj.values()): return None
    ends = [n for n, v in adj.items() if len(v) == 1]
    if len(ends) != 2: return None
    path = [ends[0]]; prev = None
    while True:
        nxt = [n for n in adj[path[-1]] if n != prev]
        if not nxt: break
        prev = path[-1]; path.append(nxt[0])
        if len(path) > len(adj) + 1: return None
    return path

def dp(pts, tol):
    if len(pts) < 3: return pts
    a, b = pts[0], pts[-1]; L = math.dist(a, b) or 1e-9
    d = [abs((p[0] - a[0]) * (b[1] - a[1]) - (p[1] - a[1]) * (b[0] - a[0])) / L for p in pts[1:-1]]
    k = max(range(len(d)), key=d.__getitem__)
    if d[k] <= tol: return [a, b]
    return dp(pts[:k + 2], tol)[:-1] + dp(pts[k + 1:], tol)

def hsh(x, y):
    return ((int(round(x * 7)) * 73856093) ^ (int(round(y * 7)) * 19349663)) & 0xffff

MASONRY = [(n, types[n]["L"]) for n in ("stucco_s", "stucco", "brick", "noparapet", "balustrade", "stucco_m", "brick_m", "irongate", "brick_l", "balustrade_l", "irongate_l", "castiron") if n in types]
out = []; stats = collections.Counter()

def emit(mesh, x, y, rot, sx, sy, sz):
    out.append("%s %.2f %.2f %.2f %.4f %.4f %.4f" % (mesh, x, y, rot, sx, sy, sz)); stats[mesh] += 1

def place_masonry(a, b, width):
    L = math.dist(a, b); mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
    rot = math.degrees(math.atan2(b[1] - a[1], b[0] - a[0]))
    cand = [(n, nl) for n, nl in MASONRY if 0.78 <= L / nl <= 1.42]
    if not cand: cand = [min(MASONRY, key=lambda c: abs(math.log(max(L, 1.5) / c[1])))]
    n, nl = cand[hsh(mx, my) % len(cand)]
    s = max(0.5, L / nl); sz = min(1.25, max(0.8, s ** 0.6))
    sy = 1.0 if not width else min(1.5, max(0.7, width / types[n]["W"]))
    emit("bridge_" + n, mx, my, rot, s, sy, sz)

def place_modular(name, a, b, sy):
    t = types[name]; c = t["c"]; hl = t["L"] / 2; la = hl - c; P = 2 * c
    L = math.dist(a, b); ux, uy = (b[0] - a[0]) / L, (b[1] - a[1]) / L
    rot = math.degrees(math.atan2(uy, ux))
    k = max(0, round((L - 2 * la) / P)); tot = 2 * la + k * P; s = L / tot
    def put(suffix, along, off=0.0):          # along = distance of the piece's local x=0 from the start
        d = (along) * s
        emit("bridge_%s_%s" % (name, suffix), a[0] + ux * d, a[1] + uy * d, rot, s, sy, 1.0)
    put("a", hl)                              # start piece: its local x=-hl sits on the start point
    for j in range(k): put("m", la + (j + 0.5) * P)
    put("z", la + k * P - c)                  # end piece covers local x in [c, hl]

def place_special(name, a, b):
    L = math.dist(a, b); mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
    emit("bridge_" + name, mx, my, math.degrees(math.atan2(b[1] - a[1], b[0] - a[0])), L / types[name]["L"], 1.0, 1.0)

def classify(ids):
    hw = collections.Counter(br[i][1]["highway"] for i in ids)
    top = hw.most_common(1)[0][0]
    if any(h in ("trunk", "primary") for h in hw): return "bigroad"
    if top == "cycleway" or (hw.get("cycleway", 0) >= len(ids) / 2): return "cycle"
    if any(h in ("unclassified", "residential", "service", "road", "tertiary", "secondary") for h in hw): return "road"
    return "foot"

def width_of(ids):
    ws = []
    for i in ids:
        try: ws.append(float(br[i][1]["width"]))
        except (KeyError, ValueError): pass
    return max(ws) if ws else None

SPECIAL = {"Ponte degli Scalzi": "scalzi", "Ponte delle Guglie": "guglie", "Ponte della Costituzione": "costituzione"}
dropped = collections.Counter()
for c, ids in comp.items():
    names = set(br[i][1].get("name", "") for i in ids)
    pts_all = [nodes[n] for i in ids for n in br[i][0] if n in nodes]
    if not pts_all: continue
    path = order(ids)
    if path:
        pts = [nodes[n] for n in path]
    else:
        a = max(pts_all, key=lambda p: math.dist(p, pts_all[0])); b = max(pts_all, key=lambda p: math.dist(p, a)); pts = [a, b]
    mid = pts[len(pts) // 2]
    if not (MINX <= pts[0][0] < MAXX and MINY <= pts[0][1] < MAXY): dropped["outside"] += 1; continue
    if any("Accademia" in n for n in names):
        # the mapped stretch includes the approach stairs on land: the bridge itself is the longest footway part
        spans = [[nodes[n] for n in br[i][0] if n in nodes] for i in ids if br[i][1].get("highway") == "footway"]
        a, b = max(((s_[0], s_[-1]) for s_ in spans), key=lambda ab: math.dist(*ab))
        if "accademia" in types: place_special("accademia", a, b)
        else: dropped["accademia (no model)"] += 1
        continue
    if in_zone(*mid) or in_zone((pts[0][0] + pts[-1][0]) / 2, (pts[0][1] + pts[-1][1]) / 2): dropped["landmark zone"] += 1; continue
    a, b = pts[0], pts[-1]; chord = math.dist(a, b)
    if chord < 1.2: dropped["tiny"] += 1; continue
    sp = next((SPECIAL[n] for n in names if n in SPECIAL and SPECIAL[n] in types), None)
    if sp: place_special(sp, a, b); continue
    cls = classify(ids); width = width_of(ids)
    plen = sum(math.dist(p, q) for p, q in zip(pts[:-1], pts[1:]))
    # long, curved bridges: straight pieces
    segs = [(a, b)]
    if plen > 80 and plen / chord > 1.02 and len(pts) > 2:
        s2 = dp(pts, 6.0); segs = list(zip(s2[:-1], s2[1:]))
    for (p, q) in segs:
        if not (MINX <= (p[0] + q[0]) / 2 < MAXX and MINY <= (p[1] + q[1]) / 2 < MAXY): dropped["outside"] += 1; continue
        L = math.dist(p, q)
        if L < 33.5 and cls not in ("bigroad",) or L < 20:
            place_masonry(p, q, width if cls != "bigroad" else None); continue
        if cls == "bigroad" and "liberta" in types: place_modular("liberta", p, q, min(1.0, max(0.3, (width or 8.0) / types["liberta"]["W"])))
        elif cls == "cycle" and "ribbon" in types: place_modular("ribbon", p, q, min(1.4, max(0.6, (width or 2.5) / types["ribbon"]["W"])))
        elif cls == "road" and "girder" in types: place_modular("girder", p, q, min(1.4, max(0.5, (width or 7.0) / types["girder"]["W"])))
        else:
            west = (p[0] + q[0]) / 2 < -1700
            if west and 45 <= L <= 95 and "bowstring" in types:
                emit("bridge_bowstring", (p[0] + q[0]) / 2, (p[1] + q[1]) / 2, math.degrees(math.atan2(q[1] - p[1], q[0] - p[0])), L / types["bowstring"]["L"], min(1.4, max(0.7, (width or 4.0) / types["bowstring"]["W"])), 1.0)
            elif west and "ribbon" in types: place_modular("ribbon", p, q, min(1.4, max(0.6, (width or 2.5) / types["ribbon"]["W"])))
            else: place_modular("timber", p, q, min(1.3, max(0.7, (width or 2.4) / types["timber"]["W"])))

open(out_f, "w").write("# mesh  x  y  rotDeg  sx  sy  sz   (generated by tools/place_bridges.py)\n" + "\n".join(out) + "\n")
print("bridges placed:", len(out), "instances;", len(comp), "physical bridges")
for k, v in sorted(stats.items(), key=lambda kv: -kv[1]): print("  ", k, v)
print("not placed:", dict(dropped))

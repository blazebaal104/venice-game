"""Moored boats of Venice, generated from code: 10 hull types with smooth lofted hulls and a kit of parts
(outboards from 2.5 to 200 hp with their power on the cowling, biminis, covers, consoles, lights, antennas, fenders,
mooring lines, registration plates), combined into a catalogue of variants the game assembles at run time.

Output (app/src/main/assets/boats/):
  parts/*.mesh   hulls (and their low-detail versions) and parts, game mesh format (see meshlib.py) with UVs
  decals.png     outboard power labels and registration plates (sampled through the shader's sign path)
  catalog.txt    one variant per line (see write_catalog)

Hull frame: +X bow, +Y port (the quay side when moored), +Z up, waterline z = 0, origin amidships.
Paint slots: plain-colour vertices with these exact colours are repainted per variant at run time:
  A (1,0,1) topsides   B (0,1,1) sheer stripe / accent   C (1,1,0) interior, deck   D (0,0,1) bottom
  E (1,0,0) canvas / tubes / covers   F (0,1,0) cushions
Run: python3 boats.py [out_dir]
"""
import math, os, random, struct, sys, zlib

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), '../../app/src/main/assets/boats')
PLANKS = 26

def col(r, g, b, gloss=0.0): return (-1.0 - gloss, r, g, b)
def wood(r, g, b): return (float(PLANKS), r, g, b)
SA = lambda g=0.6: col(1, 0, 1, g)
SB = lambda g=0.6: col(0, 1, 1, g)
SC = lambda g=0.2: col(1, 1, 0, g)
SD = lambda g=0.3: col(0, 0, 1, g)
SE = lambda g=0.1: col(1, 0, 0, g)
SF = lambda g=0.15: col(0, 1, 0, g)
STEEL = col(0.62, 0.64, 0.66, 0.85); CHROME = col(0.8, 0.82, 0.85, 0.95); BLACK = col(0.03, 0.03, 0.035, 0.5)
RUBBER = col(0.05, 0.05, 0.05, 0.15); GLASS = col(0.04, 0.06, 0.07, 0.97); BRASS = col(0.70, 0.52, 0.20, 0.9)
ROPE = col(0.78, 0.74, 0.62, 0.05); TEAK = wood(0.62, 0.44, 0.28); WHITE = col(0.88, 0.89, 0.88, 0.6)

def sub(a, b): return (a[0]-b[0], a[1]-b[1], a[2]-b[2])
def add(a, b): return (a[0]+b[0], a[1]+b[1], a[2]+b[2])
def mul(a, s): return (a[0]*s, a[1]*s, a[2]*s)
def cross(a, b): return (a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0])
def dot(a, b): return a[0]*b[0]+a[1]*b[1]+a[2]*b[2]
def norm(a):
    l = math.sqrt(dot(a, a)) or 1.0
    return (a[0]/l, a[1]/l, a[2]/l)
def lerp(a, b, t): return a + (b - a) * t
def sstep(a, b, x):
    t = min(max((x - a) / (b - a), 0.0), 1.0); return t * t * (3 - 2 * t)

class Mesh:
    def __init__(self): self.v = []
    def vert(self, p, n, uv, a): self.v.append((p[0], p[1], p[2], n[0], n[1], n[2], uv[0], uv[1]) + tuple(a))
    def tri(self, a, b, c, at, uvs=((0, 0), (0, 0), (0, 0))):
        n = cross(sub(b, a), sub(c, a))
        if dot(n, n) < 1e-14: return
        n = norm(n)
        for p, uv in zip((a, b, c), uvs): self.vert(p, n, uv, at)
    def quad(self, a, b, c, d, at, uvs=None):
        if uvs: self.tri(a, b, c, at, (uvs[0], uvs[1], uvs[2])); self.tri(a, c, d, at, (uvs[0], uvs[2], uvs[3]))
        else: self.tri(a, b, c, at); self.tri(a, c, d, at)
    def squad(self, ps, ns, at):
        """smooth quad: per-corner normals; wound so the front face looks the way the normals point (the shader
        flips the normal on back faces)"""
        fn = cross(sub(ps[1], ps[0]), sub(ps[2], ps[0]))
        if dot(fn, fn) < 1e-14: fn = cross(sub(ps[2], ps[0]), sub(ps[3], ps[0]))
        avg = add(add(ns[0], ns[1]), add(ns[2], ns[3]))
        order = (0, 1, 2, 0, 2, 3) if dot(fn, avg) >= 0 else (0, 2, 1, 0, 3, 2)
        for i in order: self.vert(ps[i], ns[i], (0, 0), at)
    def box(self, c, h, at, yaw=0.0, pitch=0.0):
        cy, sy, cp, sp = math.cos(yaw), math.sin(yaw), math.cos(pitch), math.sin(pitch)
        def P(x, y, z):
            x, y, z = x*h[0], y*h[1], z*h[2]
            x, z = x*cp + z*sp, -x*sp + z*cp
            return (c[0] + x*cy - y*sy, c[1] + x*sy + y*cy, c[2] + z)
        for f in (((-1,-1,-1),(-1,1,-1),(1,1,-1),(1,-1,-1)), ((-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)),
                  ((-1,-1,-1),(1,-1,-1),(1,-1,1),(-1,-1,1)), ((1,1,-1),(-1,1,-1),(-1,1,1),(1,1,1)),
                  ((-1,1,-1),(-1,-1,-1),(-1,-1,1),(-1,1,1)), ((1,-1,-1),(1,1,-1),(1,1,1),(1,-1,1))):
            self.quad(*[P(*q) for q in f], at)
    def tube(self, pts, r, at, segs=8, caps=True):
        rr = r if isinstance(r, (list, tuple)) else [r] * len(pts)
        rings = []
        for i, p in enumerate(pts):
            a = pts[max(i-1, 0)]; b = pts[min(i+1, len(pts)-1)]
            t = norm(sub(b, a)); up = (0, 0, 1) if abs(t[2]) < 0.9 else (1, 0, 0)
            u = norm(cross(t, up)); w = cross(t, u)
            rings.append([(add(p, add(mul(u, rr[i]*math.cos(2*math.pi*k/segs)), mul(w, rr[i]*math.sin(2*math.pi*k/segs)))),
                           add(mul(u, math.cos(2*math.pi*k/segs)), mul(w, math.sin(2*math.pi*k/segs)))) for k in range(segs)])
        for i in range(len(rings) - 1):
            for k in range(segs):
                k2 = (k + 1) % segs
                self.squad((rings[i][k][0], rings[i][k2][0], rings[i+1][k2][0], rings[i+1][k][0]),
                           (rings[i][k][1], rings[i][k2][1], rings[i+1][k2][1], rings[i+1][k][1]), at)
        if caps:
            for ring, p, s in ((rings[0], pts[0], -1), (rings[-1], pts[-1], 1)):
                for k in range(segs):
                    if s > 0: self.tri(p, ring[k][0], ring[(k+1) % segs][0], at)
                    else: self.tri(p, ring[(k+1) % segs][0], ring[k][0], at)
    def cyl(self, a, b, r, at, segs=10, caps=True): self.tube([a, b], r, at, segs, caps)
    def plate(self, poly, z0, z1, at, y=0.0, axis='y'):
        """extrude a 2D outline (x, z) through y0..y1 (a flat cut-out like the gondola's ferro)"""
        y0, y1 = y - (z1 - z0) / 2, y + (z1 - z0) / 2
        n = len(poly)
        cx = sum(p[0] for p in poly) / n; cz = sum(p[1] for p in poly) / n
        for k in range(n):
            a, b = poly[k], poly[(k+1) % n]
            self.quad((a[0], y0, a[1]), (b[0], y0, b[1]), (b[0], y1, b[1]), (a[0], y1, a[1]), at)
            self.tri((cx, y1, cz), (a[0], y1, a[1]), (b[0], y1, b[1]), at)
            self.tri((cx, y0, cz), (b[0], y0, b[1]), (a[0], y0, a[1]), at)
    def extend(self, other): self.v.extend(other.v)
    def write(self, path):
        data = bytearray(struct.pack('>i', len(self.v)))
        for v in self.v: data += struct.pack('>12f', *v)
        open(path, 'wb').write(zlib.compress(bytes(data), 9))
        return len(self.v) // 3

# ====================================================================== hull loft
class Hull:
    """L length, B beam, D sheer height amidships, T draft; sec: 'round' | 'v' | 'flat'; ends: (bow, stern) each
    'point' or 'transom'; rise: (bow, stern) extra sheer height at the ends; sw: transom width share of the beam"""
    def __init__(self, L, B, D, T, sec='round', ends=('point', 'transom'), rise=(0.25, 0.08), sw=0.8, fine=2.0,
                 deadrise=0.18, curve=0.0, flare=0.12):
        self.L, self.B, self.D, self.T, self.sec, self.ends, self.rise, self.sw, self.fine = L, B, D, T, sec, ends, rise, sw, fine
        self.deadrise, self.curve, self.flare = deadrise, curve, flare
    def hb(self, f):
        a = abs(f)
        if f >= 0:
            return self.B / 2 * max(0.0, 1 - a ** self.fine) ** 0.55 if self.ends[0] == 'point' else self.B / 2 * (1 - 0.35 * a ** 3)
        if self.ends[1] == 'point': return self.B / 2 * max(0.0, 1 - a ** self.fine) ** 0.55
        return self.B / 2 * (1 - (1 - self.sw) * a ** 2.5)
    def zs(self, f):
        e = 2.0 if self.rise[0] > 0.7 else 2.6           # the gondola's ends sweep up in a long curve
        return self.D + self.rise[0] * max(f, 0) ** e + self.rise[1] * max(-f, 0) ** e
    def zk(self, f):
        z = -self.T * (1 - 0.25 * abs(f) ** 2)
        if self.ends[0] == 'point': z = lerp(z, self.zs(f) * 0.55, sstep(0.55, 1.0, f) ** 1.4)
        else: z = lerp(z, self.D * 0.2, sstep(0.6, 1.0, f))
        if self.ends[1] == 'point': z = lerp(z, self.zs(f) * 0.55, sstep(0.55, 1.0, -f) ** 1.4)
        return z
    def yoff(self, f): return self.curve * (1 - f * f)
    def section(self, f, t):
        """outer surface point at station f, girth parameter t (0 keel .. 1 sheer), starboard side (y >= 0 here,
        mirrored by the caller)"""
        hb, zs, zk = self.hb(f), self.zs(f), self.zk(f)
        if self.sec == 'round':
            y = hb * math.sin(t * math.pi / 2) ** 0.75
            z = zk + (zs - zk) * (1 - math.cos(t * math.pi / 2) ** 1.25)
        elif self.sec == 'v':
            tc = 0.42; zc = zk + (zs - zk) * 0.32 + self.deadrise * 0.2
            if t < tc: u = t / tc; y = hb * 0.82 * u; z = zk + (zc - zk) * u ** 1.1
            else: u = (t - tc) / (1 - tc); y = hb * (0.82 + 0.18 * math.sin(u * math.pi / 2)); z = zc + (zs - zc) * u
        else:   # flat bottom, flared sides
            tc = 0.45
            if t < tc: u = t / tc; y = hb * (1 - self.flare) * u; z = zk + 0.04 * u * u
            else: u = (t - tc) / (1 - tc); y = hb * (1 - self.flare + self.flare * u); z = zk + 0.04 + (zs - zk - 0.04) * u
        return (self.L / 2 * f, y, z)

def stations(n):
    return [-math.cos(math.pi * i / (n - 1)) for i in range(n)]       # dense at the ends

def loft(m, h, ns=28, nt=9, inner=True, paint=True, stripe=(-0.14, -0.05), lod=False, interior=None, inset=0.05):
    """outer hull (smooth), inner hull, gunwale cap and transom"""
    fs = stations(ns); ts = [i / (nt - 1) for i in range(nt)]
    def P(f, t, side, k=1.0, dz=0.0):
        x, y, z = h.section(f, t)
        return (x, side * y * k + h.yoff(f), z + dz)
    def N(f, t, side):
        e = 1e-3
        pf = h.section(min(f + e, 1), t); pb = h.section(max(f - e, -1), t)
        pt = h.section(f, min(t + e, 1)); pk = h.section(f, max(t - e, 0))
        df = sub(pf, pb); dt = sub(pt, pk)
        n = cross(dt, df)                      # outward (starboard), from the keel up and the stern forward
        if dot(n, n) < 1e-12: n = (1.0 if f > 0 else -1.0, 0.3, 0.0)
        n = norm(n)
        return (n[0], side * n[1], n[2])
    for side in (1, -1):
        for i in range(ns - 1):
            for j in range(nt - 1):
                f0, f1, t0, t1 = fs[i], fs[i+1], ts[j], ts[j+1]
                ps = [P(f0, t0, side), P(f1, t0, side), P(f1, t1, side), P(f0, t1, side)]
                if side < 0: ps = [ps[0], ps[3], ps[2], ps[1]]
                ns_ = [N(f, t, side) for f, t in ((f0, t0), (f1, t0), (f1, t1), (f0, t1))]
                if side < 0: ns_ = [ns_[0], ns_[3], ns_[2], ns_[1]]
                zm = sum(p[2] for p in ps) / 4; zr = zm - (h.zs(f0) + h.zs(f1)) / 2
                if not paint: at = SA()
                elif zm < 0.0: at = SD()
                elif stripe and stripe[0] < zr < stripe[1]: at = SB()
                else: at = SA()
                m.squad(ps, ns_, at)
        # inner skin (seen from above) and the gunwale cap
        if inner and not lod:
            for i in range(ns - 1):
                f0, f1 = fs[i], fs[i+1]
                for j in range(2, nt - 1):
                    t0, t1 = ts[j], ts[j+1]
                    def Q(f, t):
                        x, y, z = h.section(f, t); return (x, side * max(y - inset, 0) + h.yoff(f), z + inset * 0.6)
                    ps = [Q(f0, t0), Q(f0, t1), Q(f1, t1), Q(f1, t0)]
                    if side < 0: ps = [ps[0], ps[3], ps[2], ps[1]]
                    m.quad(*ps, interior or SC())
                a = P(f0, 1, side); b = P(f1, 1, side)
                ai = (a[0], side * max(abs(a[1] - h.yoff(f0)) - inset, 0) + h.yoff(f0), a[2] + inset * 0.6)
                bi = (b[0], side * max(abs(b[1] - h.yoff(f1)) - inset, 0) + h.yoff(f1), b[2] + inset * 0.6)
                q = [a, b, bi, ai] if side > 0 else [a, ai, bi, b]
                m.quad(*q, interior or SC(0.4))
    # transom: flat panel closing the stern
    if h.ends[1] == 'transom':
        f = -1.0
        ring = [P(f, t, 1) for t in ts] + [P(f, t, -1) for t in reversed(ts)]
        c = (ring[0][0], h.yoff(f), sum(p[2] for p in ring) / len(ring))
        for k in range(len(ring) - 1):
            zm = (ring[k][2] + ring[k+1][2]) / 2
            m.tri(c, ring[k+1], ring[k], SD() if zm < 0 else SA())
    return fs

def floor(m, h, z, f0, f1, at, n=14, inset=0.06):
    """flat floorboards / sole at height z between stations f0..f1, clipped to the inside of the hull"""
    def width(f):
        best = 0.0
        prev = h.section(f, 0)
        for k in range(1, 40):
            p = h.section(f, k / 39)
            if (prev[2] - z) * (p[2] - z) <= 0 and p[2] != prev[2]:
                u = (z - prev[2]) / (p[2] - prev[2]); best = prev[1] + (p[1] - prev[1]) * u; break
            prev = p
        return max(best - inset, 0.0)
    fs = [f0 + (f1 - f0) * i / n for i in range(n + 1)]
    for i in range(n):
        a, b = fs[i], fs[i+1]; wa, wb = width(a), width(b)
        if wa <= 0 and wb <= 0: continue
        xa, xb = h.L / 2 * a, h.L / 2 * b
        m.quad((xa, -wa + h.yoff(a), z), (xb, -wb + h.yoff(b), z), (xb, wb + h.yoff(b), z), (xa, wa + h.yoff(a), z), at)

def foredeck(m, h, f0, at, camber=0.05, n=10):
    fs = [f0 + (1 - f0) * i / n for i in range(n + 1)]
    for i in range(n):
        a, b = fs[i], fs[i+1]
        pa, pb = h.section(a, 1), h.section(b, 1)
        for s in (1, -1):
            q = [(pa[0], s * pa[1] + h.yoff(a), pa[2]), (pb[0], s * pb[1] + h.yoff(b), pb[2]),
                 (pb[0], h.yoff(b), pb[2] + camber * (pb[1] / (h.B / 2) + 0.2)), (pa[0], h.yoff(a), pa[2] + camber * (pa[1] / (h.B / 2) + 0.2))]
            if s < 0: q = [q[0], q[3], q[2], q[1]]
            m.quad(*q, at)

def rubrail(m, h, at, dz=-0.03, r=0.035, f0=-0.98, f1=0.98, n=24):
    for s in (1, -1):
        pts = []
        for i in range(n + 1):
            f = f0 + (f1 - f0) * i / n
            x, y, z = h.section(f, 1)
            pts.append((x, s * (y + r * 0.6) + h.yoff(f), z + dz))
        m.tube(pts, r, at, segs=6)

def thwart(m, h, f, z, at, w=0.22, t=0.03):
    """a bench seat across the hull at station f"""
    x = h.L / 2 * f
    best = 0.0
    for k in range(40):
        p = h.section(f, k / 39)
        if p[2] >= z: best = p[1]; break
    hw = max(best - 0.05, 0.1)
    m.box((x, h.yoff(f), z), (w / 2, hw, t), at)

# ====================================================================== parts
def outboard(hp, cowl, cell, tiller):
    """outboard motor, origin at the transom clamp (x = 0 transom face, z = 0 transom top). Size from power."""
    m = Mesh()
    s = 0.55 + 0.45 * min(1.0, math.log(hp / 2.5 + 1) / math.log(81))     # 2.5 hp -> 0.55, 200 hp -> 1.0
    shaft = 0.45 + 0.35 * s
    ch, cl, cw = 0.42 * s, 0.62 * s, 0.38 * s          # cowling height, length, width
    cx, cz = -0.22 * s - cl / 2 + 0.05, 0.12 + ch / 2
    m.box((-0.05, 0, -0.02), (0.07, 0.09 * s, 0.12), BLACK)                       # clamp bracket
    # cowling: rounded box (lofted rings)
    rings = []
    for i in range(9):
        u = i / 8
        x = cx - cl / 2 + cl * u
        k = 0.75 + 0.25 * math.sin(u * math.pi) ** 0.5
        rings.append((x, k))
    for i in range(8):
        (xa, ka), (xb, kb) = rings[i], rings[i+1]
        for j in range(12):
            a0, a1 = 2 * math.pi * j / 12, 2 * math.pi * (j + 1) / 12
            def R(x, k, a): return (x, math.cos(a) * cw / 2 * k, cz + math.sin(a) * ch / 2 * k * (1.0 if math.sin(a) > 0 else 1.15))
            def Nn(a): return (0, math.cos(a), math.sin(a))
            m.squad((R(xa, ka, a0), R(xb, kb, a0), R(xb, kb, a1), R(xa, ka, a1)), (Nn(a0), Nn(a0), Nn(a1), Nn(a1)), cowl)
    for (x, k), sgn in ((rings[0], -1), (rings[-1], 1)):
        for j in range(12):
            a0, a1 = 2 * math.pi * j / 12, 2 * math.pi * (j + 1) / 12
            p0 = (x, math.cos(a0) * cw / 2 * k, cz + math.sin(a0) * ch / 2 * k); p1 = (x, math.cos(a1) * cw / 2 * k, cz + math.sin(a1) * ch / 2 * k)
            if sgn > 0: m.tri((x, 0, cz), p0, p1, cowl)
            else: m.tri((x, 0, cz), p1, p0, cowl)
    # power label on both sides of the cowling (decal cell from the atlas)
    u0, v0, u1, v1 = cell
    lw, lh = cl * 0.62, ch * 0.34
    for s_ in (1, -1):
        y = s_ * (cw / 2 * 0.99 + 0.006)
        a = (cx + lw / 2 * s_, y, cz - lh / 2 + 0.02); b = (cx - lw / 2 * s_, y, cz - lh / 2 + 0.02)
        c = (cx - lw / 2 * s_, y, cz + lh / 2 + 0.02); d = (cx + lw / 2 * s_, y, cz + lh / 2 + 0.02)
        m.quad(a, b, c, d, (300.0, 1, 1, 1), ((u0, v1), (u1, v1), (u1, v0), (u0, v0)))   # reads left to right from outside
    # midsection, shaft, anti-cavitation plate, gearcase, propeller
    m.box((cx + 0.05, 0, 0.06), (0.16 * s, 0.11 * s, 0.07), BLACK)
    m.box((cx + 0.08, 0, -shaft / 2), (0.07 * s, 0.05 * s, shaft / 2), cowl)
    m.box((cx + 0.05, 0, -shaft + 0.02), (0.2 * s, 0.13 * s, 0.008), cowl)
    m.cyl((cx + 0.28 * s, 0, -shaft - 0.07 * s), (cx - 0.12 * s, 0, -shaft - 0.07 * s), 0.065 * s, cowl, segs=10)
    m.box((cx + 0.08, 0, -shaft - 0.15 * s), (0.08 * s, 0.015, 0.06 * s), cowl)
    for k in range(3):
        a = 2 * math.pi * k / 3
        m.box((cx - 0.15 * s, math.cos(a) * 0.09 * s, -shaft - 0.07 * s + math.sin(a) * 0.09 * s), (0.02, 0.07 * s, 0.03 * s), STEEL, yaw=0, pitch=0)
    if tiller:
        m.tube([(cx + cl / 2 - 0.05, 0, cz - 0.05), (cx + cl / 2 + 0.35 * s, 0.05, cz - 0.02), (cx + cl / 2 + 0.6 * s, 0.08, cz + 0.02)], 0.025, BLACK, segs=6)
    return m

def bimini(L, W, H, foldDown=False):
    m = Mesh()
    hw = W / 2
    if foldDown:
        for x in (0, 0.08, 0.16):
            m.tube([(x - L * 0.3, -hw, 0.0), (x - L * 0.3, -hw, 0.25), (x - L * 0.3, hw, 0.25), (x - L * 0.3, hw, 0.0)], 0.012, CHROME, segs=5, caps=False)
        m.box((-L * 0.25, 0, 0.27), (0.18, hw, 0.05), SE())
        return m
    bows = [(-L / 2, H * 0.92), (0.0, H), (L / 2, H * 0.97)]
    for (x, h) in bows:
        m.tube([(x, -hw, 0.0), (x, -hw, h - 0.06), (x, -hw * 0.8, h), (x, hw * 0.8, h), (x, hw, h - 0.06), (x, hw, 0.0)], 0.013, CHROME, segs=5, caps=False)
    n = 8
    for i in range(n):
        x0 = -L / 2 + L * i / n; x1 = -L / 2 + L * (i + 1) / n
        def hh(x): return H * (0.92 + 0.08 * math.sin((x + L / 2) / L * math.pi)) + 0.01
        for (y0, y1, z0a, z1a) in ((-hw, -hw * 0.8, -0.06, 0.0), (-hw * 0.8, hw * 0.8, 0.0, 0.0), (hw * 0.8, hw, 0.0, -0.06)):
            m.quad((x0, y0, hh(x0) + z0a), (x1, y0, hh(x1) + z0a), (x1, y1, hh(x1) + z1a), (x0, y1, hh(x0) + z1a), SE())
            m.quad((x0, y1, hh(x0) + z1a - 0.004), (x1, y1, hh(x1) + z1a - 0.004), (x1, y0, hh(x1) + z0a - 0.004), (x0, y0, hh(x0) + z0a - 0.004), SE())
    return m

def cover(h, lift=0.12):
    """fitted cover over the open boat (ties down to the rubrail)"""
    m = Mesh()
    fs = stations(22)
    for i in range(len(fs) - 1):
        f0, f1 = fs[i], fs[i+1]
        def ring(f):
            x, y, z = h.section(f, 1); return x, y + 0.02, z
        xa, ya, za = ring(f0); xb, yb, zb = ring(f1)
        ca = za + lift * (1 - abs(f0) ** 3) + 0.02; cb = zb + lift * (1 - abs(f1) ** 3) + 0.02
        for s in (1, -1):
            q = [(xa, s * ya + h.yoff(f0), za - 0.04), (xb, s * yb + h.yoff(f1), zb - 0.04), (xb, s * yb * 0.55 + h.yoff(f1), cb - 0.02), (xa, s * ya * 0.55 + h.yoff(f0), ca - 0.02)]
            r = [(xa, s * ya * 0.55 + h.yoff(f0), ca - 0.02), (xb, s * yb * 0.55 + h.yoff(f1), cb - 0.02), (xb, h.yoff(f1), cb), (xa, h.yoff(f0), ca)]
            if s < 0: q = [q[0], q[3], q[2], q[1]]; r = [r[0], r[3], r[2], r[1]]
            m.quad(*q, SE()); m.quad(*r, SE())
    return m

def navlight(h=1.1):
    m = Mesh()
    m.cyl((0, 0, 0), (0, 0, h), 0.018, CHROME, segs=6)
    m.cyl((0, 0, h), (0, 0, h + 0.09), 0.035, col(0.9, 0.9, 0.85, 0.9), segs=8)
    m.cyl((0, 0, h + 0.09), (0, 0, h + 0.11), 0.04, BLACK, segs=8)
    return m

def bowlights():
    m = Mesh()
    m.box((0, 0, 0.03), (0.06, 0.08, 0.03), CHROME)
    m.box((0.01, 0.045, 0.035), (0.04, 0.03, 0.02), col(0.9, 0.05, 0.05, 0.9))
    m.box((0.01, -0.045, 0.035), (0.04, 0.03, 0.02), col(0.05, 0.75, 0.15, 0.9))
    return m

def antenna(h=2.4):
    m = Mesh()
    m.box((0, 0, 0.04), (0.03, 0.03, 0.04), CHROME)
    m.tube([(0, 0, 0.08), (0.02, 0, h * 0.5), (0.07, 0, h)], [0.012, 0.009, 0.004], WHITE, segs=5)
    return m

def fender(c):
    m = Mesh()
    m.tube([(0, 0, -0.32), (0, 0, -0.3), (0, 0, -0.08), (0, 0, -0.06)], [0.02, 0.09, 0.09, 0.02], c, segs=10)
    m.tube([(0, 0, -0.06), (0, -0.02, 0.15), (0, -0.05, 0.25)], 0.006, ROPE, segs=4, caps=False)
    return m

def mooring_line(length=1.4, rise=0.9):
    """from the cleat (origin) to a ring on the quay (+Y), sagging"""
    m = Mesh()
    pts = []
    for i in range(9):
        u = i / 8
        pts.append((0.15 * math.sin(u * math.pi), length * u, rise * u - 0.22 * math.sin(u * math.pi)))
    m.tube(pts, 0.012, ROPE, segs=5)
    m.box((0, 0, 0.02), (0.07, 0.02, 0.02), CHROME)
    return m

def regplate(cell):
    """registration plate (VE + number), faces +Y; origin at its centre"""
    m = Mesh()
    u0, v0, u1, v1 = cell
    w, hh = 0.42, 0.1
    m.quad((w / 2, 0, -hh / 2), (-w / 2, 0, -hh / 2), (-w / 2, 0, hh / 2), (w / 2, 0, hh / 2), (300.0, 1, 1, 1), ((u0, v1), (u1, v1), (u1, v0), (u0, v0)))
    return m

def console(w=0.62, h=0.95):
    m = Mesh()
    m.box((0, 0, h / 2), (0.32, w / 2, h / 2), SA(0.7))
    m.box((0.05, 0, h + 0.02), (0.28, w / 2 - 0.02, 0.02), SB(0.6))
    # windscreen
    for s in (1, -1):
        m.quad((0.32, s * w / 2, h), (0.32, 0, h), (0.18, 0, h + 0.38), (0.18, s * w / 2 * 0.9, h + 0.33), col(0.35, 0.42, 0.45, 0.97))
    m.cyl((0.05, 0, h - 0.1), (-0.12, 0, h + 0.18), 0.014, BLACK, segs=5)
    m.tube([(-0.12 + 0.17 * math.cos(a) * 0.2, 0.17 * math.sin(a), h + 0.2 + 0.17 * math.cos(a) * 0.15) for a in [2 * math.pi * k / 12 for k in range(13)]], 0.012, BLACK, segs=4, caps=False)
    m.box((0.18, 0, h - 0.05), (0.08, w / 2 - 0.08, 0.04), col(0.05, 0.05, 0.06, 0.9))
    # leaning post / seat behind it
    m.box((-0.75, 0, 0.42), (0.22, w / 2 + 0.05, 0.08), SF())
    m.box((-0.75, 0, 0.2), (0.15, w / 2 - 0.05, 0.2), SA(0.6))
    return m

def crates(n, seed):
    m = Mesh()
    r = random.Random(seed)
    cols = [col(0.75, 0.18, 0.12, 0.3), col(0.15, 0.35, 0.65, 0.3), col(0.9, 0.75, 0.2, 0.3), wood(0.6, 0.48, 0.3), col(0.85, 0.85, 0.82, 0.2)]
    for i in range(n):
        x = (i % 3) * 0.62 - 0.6; y = (i // 3 % 2) * 0.7 - 0.35; z = (i // 6) * 0.34
        c = r.choice(cols)
        m.box((x + r.uniform(-0.05, 0.05), y + r.uniform(-0.05, 0.05), z + 0.16), (0.28, 0.32, 0.16), c, yaw=r.uniform(-0.1, 0.1))
    return m

def oars(L):
    m = Mesh()
    for s in (1, -1):
        m.cyl((-L / 2, s * 0.22, 0), (L / 2 - 0.35, s * 0.2, 0.02), 0.022, wood(0.7, 0.55, 0.35), segs=6)
        m.box((L / 2 - 0.2, s * 0.2, 0.02), (0.22, 0.07, 0.008), wood(0.7, 0.55, 0.35))
    return m

# ====================================================================== the hull types
class Base:
    def __init__(self, name, hull, material, prop, hp, mesh, lod, extras):
        self.name, self.hull, self.material, self.prop, self.hp, self.mesh, self.lod = name, hull, material, prop, hp, mesh, lod
        self.extras = extras            # attach points: dict

def gondola(lod=False):
    h = Hull(10.85, 1.42, 0.38, 0.12, 'flat', ('point', 'point'), (0.95, 0.75), fine=1.6, curve=0.06, flare=0.18)
    m = Mesh()
    loft(m, h, 34 if not lod else 12, 9 if not lod else 4, inner=not lod, stripe=None, lod=lod)
    if lod: return m, h, {}
    floor(m, h, -0.02, -0.72, 0.72, wood(0.25, 0.17, 0.11))
    # ferro (steel bow blade with six teeth) and the stern curl
    fx = h.L / 2
    ferro = [(fx - 0.15, 0.3), (fx + 0.02, 0.35), (fx + 0.1, 0.55), (fx + 0.05, 0.75), (fx + 0.12, 0.95), (fx + 0.05, 1.1), (fx + 0.16, 1.32), (fx + 0.02, 1.42), (fx - 0.12, 1.28), (fx - 0.05, 1.0), (fx - 0.12, 0.62)]
    for k in range(6):
        z = 0.58 + k * 0.11
        m.box((fx + 0.13, h.yoff(1), z), (0.09, 0.008, 0.018), CHROME)
    m.plate(ferro, -0.012, 0.012, CHROME, y=h.yoff(1))
    sx = -h.L / 2
    m.plate([(sx + 0.15, 0.6), (sx - 0.05, 0.75), (sx - 0.1, 0.95), (sx + 0.0, 1.05), (sx + 0.08, 0.9), (sx + 0.22, 0.7)], -0.012, 0.012, BRASS, y=h.yoff(-1))
    # forcola (walnut oarlock) at the stern on the right, the oar laid along the boat
    fo = (-h.L * 0.33, -0.42, 0.42)
    m.tube([(fo[0], fo[1], 0.2), (fo[0] + 0.05, fo[1], 0.55), (fo[0] - 0.04, fo[1], 0.78), (fo[0] + 0.06, fo[1], 0.92)], [0.06, 0.05, 0.045, 0.035], wood(0.35, 0.22, 0.12), segs=7)
    m.cyl((-h.L * 0.42, -0.25, 0.42), (h.L * 0.28, 0.05, 0.36), 0.028, wood(0.72, 0.56, 0.36), segs=6)
    # seats: the passengers' bench with cushions, two chairs, the little stools; brass sea-horses on the sides
    m.box((-0.85, h.yoff(-0.15), 0.2), (0.32, 0.48, 0.12), SF())
    m.box((-1.2, h.yoff(-0.22), 0.45), (0.06, 0.48, 0.26), SF())
    for x in (0.25, 0.95):
        m.box((x, h.yoff(x / (h.L / 2)), 0.15), (0.24, 0.3, 0.1), SF())
    for s in (1, -1):
        m.tube([(-0.6, s * 0.66 + h.yoff(-0.1), 0.42), (-0.5, s * 0.66 + h.yoff(-0.1), 0.6), (-0.38, s * 0.66 + h.yoff(-0.1), 0.62), (-0.3, s * 0.66 + h.yoff(-0.1), 0.52)], 0.025, BRASS, segs=5)
    return m, h, {'reg': (0.32, 0.25), 'cleats': (0.62, -0.62)}

def sandolo(lod=False):
    h = Hull(6.9, 1.32, 0.34, 0.1, 'flat', ('point', 'point'), (0.32, 0.22), fine=1.7, flare=0.15)
    m = Mesh(); loft(m, h, 28 if not lod else 10, 8 if not lod else 4, inner=not lod, lod=lod)
    if lod: return m, h, {}
    floor(m, h, -0.03, -0.75, 0.75, wood(0.55, 0.42, 0.28))
    thwart(m, h, 0.25, 0.22, SC(0.3)); thwart(m, h, -0.35, 0.22, SC(0.3))
    m.tube([(-h.L * 0.32, -0.4, 0.2), (-h.L * 0.32 + 0.04, -0.4, 0.5), (-h.L * 0.32, -0.4, 0.65)], [0.05, 0.04, 0.03], wood(0.35, 0.22, 0.12), segs=6)
    m.extend(oars(h.L * 0.75))
    return m, h, {'motor': (-h.L / 2 + 0.25, 0.32), 'reg': (0.45, 0.2), 'cleats': (0.75, -0.7)}

def sciopon(lod=False):
    h = Hull(5.4, 1.2, 0.26, 0.08, 'flat', ('point', 'point'), (0.16, 0.1), fine=1.5, flare=0.1)
    m = Mesh(); loft(m, h, 24 if not lod else 10, 7 if not lod else 4, inner=not lod, lod=lod)
    if lod: return m, h, {}
    floor(m, h, -0.02, -0.7, 0.7, SC(0.1))
    foredeck(m, h, 0.62, SA(0.3));
    m.box((-1.75, 0, 0.12), (0.4, 0.4, 0.1), SC(0.3))
    m.extend(oars(h.L * 0.6))
    return m, h, {'motor': (-h.L / 2 + 0.2, 0.24), 'reg': (0.4, 0.15), 'cleats': (0.7, -0.7)}

def dinghy(lod=False):
    h = Hull(3.3, 1.38, 0.42, 0.14, 'round', ('transom', 'transom'), (0.1, 0.02), sw=0.82, fine=2.2)
    m = Mesh(); loft(m, h, 20 if not lod else 8, 8 if not lod else 4, inner=not lod, lod=lod)
    # pram bow: close the bow transom too
    if not lod:
        floor(m, h, -0.04, -0.85, 0.85, wood(0.6, 0.48, 0.32))
        thwart(m, h, 0.0, 0.25, TEAK); thwart(m, h, -0.65, 0.25, TEAK); thwart(m, h, 0.6, 0.25, TEAK)
        m.extend(oars(2.2))
    f = 1.0
    ts = [i / 7 for i in range(8)]
    ring = [h.section(f, t) for t in ts]
    ring = ring + [(p[0], -p[1], p[2]) for p in reversed(ring)]
    c = (ring[0][0], 0, sum(p[2] for p in ring) / len(ring))
    for k in range(len(ring) - 1): m.tri(c, ring[k], ring[k+1], SA())
    if lod: return m, h, {}
    return m, h, {'motor': (-h.L / 2 + 0.03, 0.42), 'reg': (0.55, 0.15), 'cleats': (0.85, -0.8)}

def barchino(lod=False):
    h = Hull(5.3, 1.95, 0.62, 0.22, 'v', ('point', 'transom'), (0.22, 0.04), sw=0.82, fine=2.2)
    m = Mesh(); loft(m, h, 28 if not lod else 10, 9 if not lod else 4, inner=not lod, lod=lod)
    if lod: return m, h, {}
    floor(m, h, 0.12, -0.92, 0.55, SC(0.15))
    foredeck(m, h, 0.5, SA(0.6))
    rubrail(m, h, SB(0.5), r=0.03)
    thwart(m, h, -0.55, 0.42, SC(0.3), w=0.45)
    m.box((-0.55 * h.L / 2, 0, 0.49), (0.2, 0.55, 0.05), SF())
    return m, h, {'motor': (-h.L / 2, 0.62), 'reg': (0.55, 0.42), 'console': (0.05, 0.12), 'nav': (-0.86, 0.62), 'bow': (0.97, 0.75), 'bimini': (-0.25, 0.62), 'cleats': (0.8, -0.85)}

def topa(lod=False):
    h = Hull(7.6, 2.35, 0.7, 0.35, 'round', ('point', 'transom'), (0.35, 0.08), sw=0.7, fine=1.9)
    m = Mesh(); loft(m, h, 30 if not lod else 10, 9 if not lod else 4, inner=not lod, lod=lod)
    if lod: return m, h, {}
    floor(m, h, 0.05, -0.9, 0.6, wood(0.62, 0.48, 0.3))
    foredeck(m, h, 0.62, wood(0.6, 0.45, 0.28))
    rubrail(m, h, wood(0.5, 0.33, 0.18), r=0.035)
    # engine box (inboard) amidships-aft, and a helm
    return m, h, {'motor': (-h.L / 2, 0.7), 'reg': (0.55, 0.5), 'enginebox': (-0.45, 0.05), 'nav': (-0.9, 0.7), 'cargo': (0.15, 0.05), 'bimini': (-0.45, 0.7), 'cleats': (0.82, -0.88)}

def motoscafo(lod=False):
    h = Hull(9.4, 2.45, 0.95, 0.5, 'v', ('point', 'transom'), (0.3, 0.05), sw=0.85, fine=2.3)
    m = Mesh(); loft(m, h, 32 if not lod else 10, 9 if not lod else 4, inner=not lod, stripe=(-0.2, -0.06), lod=lod)
    foredeck(m, h, 0.35, SA(0.95) if lod else wood(0.42, 0.18, 0.08))
    # cabin with windows and a cream roof, aft cockpit
    cx0, cx1 = -1.6, 1.4; cw = 0.98; z0 = 0.95; z1 = 1.95
    m.box(((cx0 + cx1) / 2, 0, (z0 + z1) / 2 - 0.05), ((cx1 - cx0) / 2, cw, (z1 - z0) / 2), wood(0.42, 0.18, 0.08) if not lod else SA())
    m.box(((cx0 + cx1) / 2, 0, z1 + 0.03), ((cx1 - cx0) / 2 + 0.08, cw + 0.06, 0.05), SC(0.6))
    if lod: return m, h, {}
    for s in (1, -1):
        for k in range(4):
            x = cx0 + 0.35 + k * 0.72
            m.quad((x - 0.25, s * (cw + 0.004), z0 + 0.4), (x + 0.25, s * (cw + 0.004), z0 + 0.4), (x + 0.25, s * (cw + 0.004), z1 - 0.18), (x - 0.25, s * (cw + 0.004), z1 - 0.18), GLASS) if s > 0 else \
                m.quad((x + 0.25, s * (cw + 0.004), z0 + 0.4), (x - 0.25, s * (cw + 0.004), z0 + 0.4), (x - 0.25, s * (cw + 0.004), z1 - 0.18), (x + 0.25, s * (cw + 0.004), z1 - 0.18), GLASS)
    m.quad((cx1 + 0.004, -cw * 0.85, z0 + 0.35), (cx1 + 0.004, cw * 0.85, z0 + 0.35), (cx1 + 0.004, cw * 0.85, z1 - 0.15), (cx1 + 0.004, -cw * 0.85, z1 - 0.15), GLASS)
    floor(m, h, 0.45, -0.95, -0.36, TEAK)
    m.box((-3.1, 0, 0.62), (0.5, 0.95, 0.15), SF())
    for s in (1, -1):
        m.tube([(cx0, s * (cw + 0.02), z1 + 0.12), (cx1, s * (cw + 0.02), z1 + 0.12)], 0.015, CHROME, segs=5)
    m.cyl((-h.L / 2 + 0.2, 0, 1.0), (-h.L / 2 + 0.05, 0, 2.1), 0.015, CHROME, segs=5)
    return m, h, {'reg': (0.55, 0.65), 'nav': (-0.05, 2.0), 'bow': (0.97, 1.2), 'cleats': (0.75, -0.9)}

def rib(lod=False):
    h = Hull(5.8, 2.0, 0.42, 0.25, 'v', ('point', 'transom'), (0.18, 0.0), sw=0.85, fine=2.4)
    m = Mesh(); loft(m, h, 26 if not lod else 10, 8 if not lod else 4, inner=not lod, stripe=None, lod=lod)
    # the inflatable tube all round, open at the transom
    pts = []; rr = []
    n = 26
    for i in range(n + 1):
        u = i / n
        t = 2 * u - 1        # -1 port aft .. bow .. 1 starboard aft, round the plan outline
        fstation = max(-0.98, min(0.985, 1 - 1.98 * abs(t)))
        side = 1 if t < 0 else -1
        x, y, z = h.section(fstation, 1)
        pts.append((x + (0.1 if abs(t) < 0.05 else 0), side * (y + 0.05) if abs(t) > 0.02 else 0.0, z + 0.12 + 0.08 * max(0, fstation) ** 2))
        rr.append(0.23 if fstation > -0.9 else 0.2)
    m.tube(pts, rr, SE(0.2), segs=10 if not lod else 5)
    if lod: return m, h, {}
    floor(m, h, 0.12, -0.92, 0.6, SC(0.15))
    m.box((h.L / 2 - 0.9, 0, 0.42), (0.45, 0.5, 0.06), SF())
    return m, h, {'motor': (-h.L / 2, 0.42), 'reg': (0.3, 0.55), 'console': (0.05, 0.12), 'nav': (-0.88, 0.45), 'cleats': (0.75, -0.8)}

def cruiser(lod=False):
    h = Hull(7.4, 2.5, 0.95, 0.45, 'v', ('point', 'transom'), (0.32, 0.05), sw=0.88, fine=2.3)
    m = Mesh(); loft(m, h, 30 if not lod else 10, 9 if not lod else 4, inner=not lod, stripe=(-0.32, -0.12), lod=lod)
    foredeck(m, h, 0.05, SA(0.7), camber=0.08)
    # cabin trunk with dark ports, windscreen, cockpit
    m.box((0.95, 0, 1.13), (1.35, 0.95, 0.2), SA(0.7))
    m.quad((-0.4, -0.95, 1.33), (-0.4, 0.95, 1.33), (-0.15, 0.85, 1.75), (-0.15, -0.85, 1.75), GLASS)
    if lod: return m, h, {}
    for s in (1, -1):
        for x in (0.4, 1.0, 1.6):
            q = [(x - 0.22, s * 0.955, 1.08), (x + 0.22, s * 0.955, 1.08), (x + 0.22, s * 0.955, 1.2), (x - 0.22, s * 0.955, 1.2)]
            if s < 0: q = [q[1], q[0], q[3], q[2]]
            m.quad(*q, GLASS)
    floor(m, h, 0.35, -0.95, -0.12, TEAK)
    m.box((-1.4, 0, 0.62), (0.55, 1.05, 0.18), SF())
    for s in (1, -1):
        m.tube([(-0.3, s * 1.12, 1.25), (1.5, s * 1.08, 1.33), (h.L / 2 - 0.3, s * 0.3, 1.45), (h.L / 2 - 0.05, 0, 1.45)], 0.014, CHROME, segs=5, caps=False)
    return m, h, {'motor': (-h.L / 2, 0.95), 'reg': (0.55, 0.65), 'nav': (-0.25, 1.75), 'bimini': (-0.6, 0.95), 'cleats': (0.75, -0.9)}

def barge(lod=False):
    h = Hull(10.5, 3.0, 0.85, 0.5, 'flat', ('transom', 'transom'), (0.25, 0.1), sw=0.92, fine=3.0, flare=0.04)
    m = Mesh(); loft(m, h, 26 if not lod else 10, 8 if not lod else 4, inner=not lod, stripe=(-0.18, -0.06), lod=lod)
    # wheelhouse aft, exhaust
    m.box((-3.9, 0, 1.55), (0.6, 0.75, 0.75), SC(0.4))
    m.box((-3.9, 0, 2.33), (0.7, 0.85, 0.04), SB(0.4))
    if lod: return m, h, {}
    for s in (1, -1):
        q = [(-4.25, s * 0.755, 1.6), (-3.55, s * 0.755, 1.6), (-3.55, s * 0.755, 2.1), (-4.25, s * 0.755, 2.1)]
        if s < 0: q = [q[1], q[0], q[3], q[2]]
        m.quad(*q, GLASS)
    m.quad((-3.295, -0.6, 1.6), (-3.295, 0.6, 1.6), (-3.295, 0.6, 2.1), (-3.295, -0.6, 2.1), GLASS)
    m.cyl((-4.6, 0.5, 0.9), (-4.6, 0.5, 2.8), 0.07, BLACK, segs=8)
    floor(m, h, 0.35, -0.65, 0.85, col(0.32, 0.30, 0.27, 0.1))
    m.box((-4.7, 0, 0.9), (0.2, 1.3, 0.05), SB(0.3))
    return m, h, {'reg': (0.65, 0.6), 'cargo': (0.2, 0.35), 'nav': (-0.75, 2.35), 'cleats': (0.85, -0.9)}

BASES = [('gondola', gondola, 'wood', 'row', []), ('sandolo', sandolo, 'wood', 'row', [0, 0, 0, 2.5, 4, 6]),
         ('sciopon', sciopon, 'wood', 'row', [0, 0, 2.5, 4]), ('dinghy', dinghy, 'plastic', 'row', [0, 0, 2.5, 4]),
         ('barchino', barchino, 'plastic', 'outboard', [9.9, 15, 20, 25, 40]), ('topa', topa, 'wood', 'outboard', [0, 0, 25, 40, 60]),
         ('motoscafo', motoscafo, 'wood', 'inboard', []), ('rib', rib, 'rubber', 'outboard', [40, 60, 90, 115]),
         ('cruiser', cruiser, 'plastic', 'outboard', [115, 150, 200]), ('barge', barge, 'steel', 'inboard', [])]
HP_LIST = [2.5, 4, 6, 9.9, 15, 20, 25, 40, 60, 90, 115, 150, 200]
COWLS = [('black', (0.04, 0.04, 0.045), (1, 1, 1)), ('grey', (0.55, 0.57, 0.6), (0.05, 0.05, 0.05)), ('white', (0.86, 0.87, 0.86), (0.08, 0.1, 0.2))]

# ====================================================================== decals
def decals(path, regs):
    from PIL import Image, ImageDraw, ImageFont
    W, H, CW, CH = 2048, 1024, 256, 64
    im = Image.new('RGB', (W, H), (128, 128, 128)); d = ImageDraw.Draw(im)
    bold = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
    cond = '/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed-Bold.ttf'
    cells = {}
    slot = [0]
    def next_cell():
        i = slot[0]; slot[0] += 1
        cx, cy = i % 8, 1 + i // 8          # rows 1..14 (the shader clamps the outer rows)
        return cx * CW, cy * CH
    for hp in HP_LIST:
        for name, cowl, txt in COWLS:
            x, y = next_cell()
            bg = tuple(int(c ** (1 / 2.2) * 255) for c in cowl); fg = tuple(int(c ** (1 / 2.2) * 255) for c in txt)
            d.rectangle([x, y, x + CW - 1, y + CH - 1], fill=bg)
            label = ('%g' % hp)
            f1 = ImageFont.truetype(bold, 46); f2 = ImageFont.truetype(cond, 15)
            d.text((x + 14, y + 6), label, font=f1, fill=fg)
            tw = d.textlength(label, font=f1)
            d.text((x + 22 + tw, y + 12), 'FOUR', font=f2, fill=fg); d.text((x + 22 + tw, y + 30), 'STROKE', font=f2, fill=fg)
            d.line([x + 10, y + CH - 9, x + CW - 10, y + CH - 9], fill=fg, width=2)
            cells[('hp', hp, name)] = (x / W, y / H, (x + CW) / W, (y + CH) / H)
    for r in regs:
        x, y = next_cell()
        d.rectangle([x, y, x + CW - 1, y + CH - 1], fill=(236, 236, 230)); d.rectangle([x + 3, y + 3, x + CW - 4, y + CH - 4], outline=(20, 20, 20), width=3)
        f = ImageFont.truetype(bold, 36)
        tw = d.textlength(r, font=f)
        d.text((x + (CW - tw) / 2, y + 10), r, font=f, fill=(15, 15, 15))
        cells[('reg', r)] = (x / W, y / H, (x + CW) / W, (y + CH) / H)
    im.save(path)
    return cells

# ====================================================================== the catalogue
PALETTES = {
    'gondola': [((0.018, 0.018, 0.022), 0.92)],
    'sandolo': [((0.03, 0.03, 0.035), 0.5), ((0.08, 0.17, 0.12), 0.4), ((0.42, 0.28, 0.16), -1), ((0.62, 0.72, 0.78), 0.4), ((0.82, 0.8, 0.74), 0.4), ((0.12, 0.2, 0.35), 0.4)],
    'sciopon': [((0.25, 0.3, 0.26), 0.2), ((0.36, 0.36, 0.33), 0.2), ((0.42, 0.28, 0.16), -1), ((0.15, 0.22, 0.3), 0.3)],
    'dinghy': [((0.88, 0.89, 0.88), 0.6), ((0.7, 0.12, 0.1), 0.6), ((0.15, 0.3, 0.55), 0.6), ((0.9, 0.82, 0.55), 0.5)],
    'barchino': [((0.9, 0.91, 0.9), 0.7), ((0.68, 0.8, 0.88), 0.7), ((0.12, 0.25, 0.48), 0.7), ((0.75, 0.15, 0.1), 0.7), ((0.2, 0.4, 0.28), 0.7), ((0.85, 0.85, 0.8), 0.7)],
    'topa': [((0.5, 0.3, 0.14), -1), ((0.86, 0.85, 0.8), 0.5), ((0.12, 0.3, 0.22), 0.5), ((0.15, 0.25, 0.45), 0.5), ((0.62, 0.15, 0.1), 0.5)],
    'motoscafo': [((0.42, 0.18, 0.08), -1)],
    'rib': [((0.88, 0.89, 0.88), 0.6), ((0.4, 0.42, 0.44), 0.5), ((0.1, 0.1, 0.11), 0.5)],
    'cruiser': [((0.92, 0.92, 0.9), 0.75), ((0.88, 0.88, 0.85), 0.75)],
    'barge': [((0.28, 0.3, 0.32), 0.3), ((0.2, 0.28, 0.42), 0.3), ((0.38, 0.22, 0.14), 0.25), ((0.55, 0.12, 0.1), 0.3)],
}
STRIPES = [(0.7, 0.1, 0.08), (0.1, 0.2, 0.45), (0.15, 0.4, 0.25), (0.05, 0.05, 0.05), (0.85, 0.65, 0.15), (0.55, 0.75, 0.85)]
BOTTOMS = [(0.45, 0.1, 0.08), (0.08, 0.1, 0.2), (0.05, 0.05, 0.05), (0.35, 0.38, 0.4)]
CANVAS = [(0.1, 0.2, 0.45), (0.12, 0.3, 0.22), (0.45, 0.46, 0.47), (0.08, 0.1, 0.12), (0.78, 0.76, 0.7), (0.55, 0.12, 0.1)]
CUSHIONS = [(0.42, 0.05, 0.06), (0.08, 0.08, 0.1), (0.8, 0.78, 0.72), (0.1, 0.2, 0.4)]
GONDOLA_CUSHIONS = [(0.38, 0.03, 0.05), (0.5, 0.06, 0.06), (0.06, 0.05, 0.06), (0.45, 0.32, 0.08)]
COUNTS = {'gondola': 14, 'sandolo': 14, 'sciopon': 10, 'dinghy': 10, 'barchino': 18, 'topa': 16, 'motoscafo': 8, 'rib': 12, 'cruiser': 10, 'barge': 10}

def fmt(a): return '%g,%g,%g,%g' % a

def build_catalog(out):
    os.makedirs(os.path.join(out, 'parts'), exist_ok=True)
    rnd = random.Random(1977)
    regs = ['VE %d%s' % (rnd.randint(1000, 39999), rnd.choice(['', '', ' A', ' B', ' N'])) for _ in range(40)]
    cells = decals(os.path.join(out, 'decals.png'), regs)
    tris = {}
    def save(name, m):
        tris[name] = m.write(os.path.join(out, 'parts', name + '.mesh'))
    # ---- parts
    for hp in HP_LIST:
        for cname, cowl, _ in COWLS:
            save('ob_%g_%s' % (hp, cname), outboard(hp, col(*cowl, 0.75), cells[('hp', hp, cname)], hp <= 20))
    for r in regs: save('reg_' + r.replace(' ', '_'), regplate(cells[('reg', r)]))
    save('navlight', navlight()); save('navlight_short', navlight(0.75)); save('bowlights', bowlights()); save('antenna', antenna())
    save('fender_w', fender(col(0.9, 0.9, 0.88, 0.4))); save('fender_b', fender(col(0.1, 0.2, 0.5, 0.4)))
    for ln in (0.8, 1.2, 1.6):
        for rs in (0.3, 0.55, 0.8, 1.05): save('line_%g_%g' % (ln, rs), mooring_line(ln, rs))
    save('console', console())
    for i in range(3): save('crates_%d' % i, crates(6 + 3 * i, 40 + i))
    eb = Mesh(); eb.box((0, 0, 0.28), (0.55, 0.45, 0.28), SC(0.4)); eb.box((0, 0, 0.57), (0.57, 0.47, 0.02), wood(0.5, 0.35, 0.2)); save('enginebox', eb)
    for bl in (2.0, 2.4): save('bimini_%g' % bl, bimini(bl, 1.7, 1.25)); save('bimini_fold_%g' % bl, bimini(bl, 1.7, 1.25, True))
    hulls = {}
    for name, fn, mat, prop, hps in BASES:
        m, h, ex = fn(); save('hull_' + name, m)
        lm, _, _ = fn(lod=True); save('hull_' + name + '_lod', lm)
        save('cover_' + name, cover(h, 0.14 if name in ('gondola', 'sandolo', 'sciopon') else 0.2))
        hulls[name] = (h, ex)
    # ---- variants
    lines = ['# id base length beam material(plastic|wood|rubber|steel) propulsion hp | paints A..F (m,r,g,b) | parts: name@x,y,z,yaw,pitch']
    vid = 0
    for name, fn, mat, prop, hps in BASES:
        h, ex = hulls[name]
        for k in range(COUNTS[name]):
            r = random.Random('%s-%d' % (name, k))
            pal = r.choice(PALETTES[name])
            if pal[1] < 0: A = (float(PLANKS),) + pal[0]
            else: A = (-1.0 - pal[1],) + pal[0]
            stripe = r.random() < 0.6 and name not in ('gondola', 'motoscafo')
            B = (-1.6,) + (r.choice(STRIPES) if stripe else pal[0]) if pal[1] >= 0 else (float(PLANKS),) + tuple(c * 0.8 for c in pal[0])
            if name == 'motoscafo': B = (-1.95, 0.85, 0.83, 0.76)
            C = (-1.2,) + r.choice([(0.82, 0.82, 0.78), (0.75, 0.74, 0.7), (0.55, 0.42, 0.28), (0.4, 0.42, 0.44)])
            if name in ('gondola',): C = (-1.3, 0.03, 0.03, 0.035)
            if name in ('sandolo', 'sciopon') and pal[1] >= 0: C = (-1.2,) + tuple(min(1.0, c * 1.25 + 0.05) for c in pal[0])
            if name == 'barge': C = (-1.3,) + r.choice([(0.85, 0.85, 0.82), (0.75, 0.6, 0.2), (0.3, 0.4, 0.5)])
            Dp = (-1.3,) + (r.choice(BOTTOMS) if name not in ('gondola',) else (0.02, 0.02, 0.02))
            E = (-1.1,) + r.choice(CANVAS)
            if name == 'rib': E = (-1.2,) + r.choice([(0.35, 0.37, 0.4), (0.12, 0.12, 0.13), (0.8, 0.35, 0.1), (0.2, 0.22, 0.28)])
            F = (-1.15,) + r.choice(GONDOLA_CUSHIONS if name == 'gondola' else CUSHIONS)
            parts = []
            L2 = h.L / 2
            hp = 0
            covered = r.random() < (0.35 if name in ('gondola', 'sandolo', 'barchino', 'topa', 'dinghy', 'sciopon') else 0.1)
            # power: realistic for the hull (a gondola is rowed; a sandolo might carry a 6 hp at most)
            if prop == 'outboard' or (prop == 'row' and hps):
                choice = r.choice(hps) if hps else 0
                if choice and 'motor' in ex:
                    hp = choice
                    mx, mz = ex['motor']
                    cname = r.choice(COWLS)[0]
                    tilt = 1.15 if r.random() < 0.45 else 0.0          # many moored boats keep the leg up
                    parts.append('ob_%g_%s@%.3f,%.3f,%.3f,0,%.3f' % (hp, cname, mx, 0, mz, tilt))
            if prop == 'inboard': hp = {'motoscafo': r.choice([220, 260, 300]), 'barge': r.choice([90, 120, 150])}[name]
            if name == 'topa' and hp == 0:
                hp = r.choice([30, 40, 50]); bx, bz = ex['enginebox']; parts.append('enginebox@%.3f,0,%.3f,0,0' % (bx * L2, bz))
            if 'console' in ex and not covered and (name == 'rib' or r.random() < 0.55):
                cx, cz = ex['console']; parts.append('console@%.3f,0,%.3f,0,0' % (cx * L2, cz))
            if 'nav' in ex and r.random() < (0.8 if name in ('motoscafo', 'cruiser', 'barge') else 0.45) and not covered:
                nx, nz = ex['nav']; parts.append('%s@%.3f,0,%.3f,0,0' % ('navlight_short' if name in ('barchino', 'topa', 'rib') else 'navlight', nx * L2, nz))
            if 'bow' in ex and r.random() < 0.6:
                bx, bz = ex['bow']; parts.append('bowlights@%.3f,0,%.3f,0,0' % (bx * L2, bz))
            if name in ('cruiser', 'motoscafo', 'barge', 'rib') and r.random() < 0.5:
                parts.append('antenna@%.3f,%.3f,%.3f,0,0' % (-L2 * 0.6, -h.B * 0.3, ex.get('nav', (0, h.D))[1] if name != 'barge' else 2.35))
            if 'bimini' in ex and not covered and r.random() < (0.45 if name != 'cruiser' else 0.6):
                bx, bz = ex['bimini']; parts.append('%s@%.3f,0,%.3f,0,0' % (('bimini_fold_' if r.random() < 0.4 else 'bimini_') + ('2' if name in ('barchino', 'rib') else '2.4'), bx * L2, bz))
            if 'cargo' in ex and r.random() < 0.6 and not covered:
                cx_, cz = ex['cargo']; parts.append('crates_%d@%.3f,0,%.3f,0,0' % (r.randint(0, 2), cx_ * L2, cz))
            if covered: parts.append('cover_%s@0,0,0,0,0' % name)
            # registration plate on both bows (gondolas carry none), fenders and lines on the quay side (+Y)
            if name != 'gondola':
                fr, rz = ex['reg']
                reg = 'reg_' + r.choice(regs).replace(' ', '_')
                # follow the hull's waterline curve: turned to the side's run, set out to its widest point
                da = h.section(fr + 0.04, 0.85); db = h.section(fr - 0.04, 0.85)
                run = math.atan2(da[1] - db[1], da[0] - db[0])
                y = max(h.section(fr + k * 0.04, t)[1] for k in (-1, 0, 1) for t in (0.75, 0.85, 0.95)) + 0.015
                for s in (1, -1):
                    parts.append('%s@%.3f,%.3f,%.3f,%.4f,0' % (reg, fr * L2, s * y, rz, run if s > 0 else math.pi - run))
            if name not in ('gondola',) and r.random() < 0.7:
                fc = r.choice(['fender_w', 'fender_b'])
                for fx in ((-0.4, 0.3) if h.L < 6.5 else (-0.5, 0.0, 0.45)):
                    parts.append('%s@%.3f,%.3f,%.3f,0,0' % (fc, fx * L2, h.section(fx, 1)[1] + 0.1, h.zs(fx)))
            c1, c2 = ex['cleats']
            for fx in (c1, c2):
                y0 = h.section(fx, 1)[1] * 0.85 + h.yoff(fx); z0 = h.zs(fx)
                need_l = h.B / 2 + 0.35 - y0 + 0.45; need_r = 1.15 - z0
                ln = min((0.8, 1.2, 1.6), key=lambda q: abs(q - need_l)); rs = min((0.3, 0.55, 0.8, 1.05), key=lambda q: abs(q - need_r))
                parts.append('line_%g_%g@%.3f,%.3f,%.3f,0,0' % (ln, rs, fx * L2, y0, z0))
            lines.append('%d %s %.2f %.2f %s %s %g | %s | %s' % (vid, name, h.L, h.B, mat, prop, hp,
                         ' '.join(fmt(p) for p in (A, B, C, Dp, E, F)), ' '.join(parts)))
            vid += 1
    open(os.path.join(out, 'catalog.txt'), 'w').write('\n'.join(lines) + '\n')
    print('variants', vid, 'parts', len(tris), 'hull tris', {k: v for k, v in tris.items() if k.startswith('hull_')})

if __name__ == '__main__':
    build_catalog(OUT)

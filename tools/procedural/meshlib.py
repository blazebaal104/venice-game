"""Tiny mesh writer for the game's .mesh format (zlib-compressed, big-endian: int vertexCount, then 12 floats per
vertex: position xyz, normal xyz, uv, attr). attr = (m, r, g, b): m >= 0 -> texture layer m (triplanar) tinted by rgb,
m < 0 -> plain colour rgb with gloss = -m - 1. Used for models generated straight from code (no Blender needed)."""
import math, struct, zlib

# texture layers (app/src/main/java/.../core/Layers.kt)
PLASTER0, BRICK0, ISTRIAN, ISTRIAN_WORN, ASHLAR = 0, 11, 15, 16, 17
MASEGNI, TRACHYTE_DARK, PLANKS, LEAD, RUST = 22, 25, 26, 31, 33

class Mesh:
    def __init__(self):
        self.v = []
        self.attr = (-1.0, 1, 1, 1)
    def color(self, r, g, b, gloss=0.0): self.attr = (-1.0 - gloss, r, g, b)
    def mat(self, layer, r=1, g=1, b=1): self.attr = (float(layer), r, g, b)
    def tri(self, a, b, c):
        ux, uy, uz = b[0]-a[0], b[1]-a[1], b[2]-a[2]; wx, wy, wz = c[0]-a[0], c[1]-a[1], c[2]-a[2]
        nx, ny, nz = uy*wz-uz*wy, uz*wx-ux*wz, ux*wy-uy*wx
        l = math.sqrt(nx*nx+ny*ny+nz*nz)
        if l < 1e-12: return
        n = (nx/l, ny/l, nz/l)
        for p in (a, b, c): self.v.append((p[0], p[1], p[2], n[0], n[1], n[2], 0.0, 0.0) + self.attr)
    def quad(self, a, b, c, d): self.tri(a, b, c); self.tri(a, c, d)
    def box(self, cx, cy, cz, hx, hy, hz):
        P = lambda sx, sy, sz: (cx+sx*hx, cy+sy*hy, cz+sz*hz)
        for (a, b, c, d) in [((-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1)), ((-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)),
                             ((-1,-1,-1),(1,-1,-1),(1,-1,1),(-1,-1,1)), ((-1,1,-1),(1,1,-1),(1,1,1),(-1,1,1)),
                             ((-1,-1,-1),(-1,1,-1),(-1,1,1),(-1,-1,1)), ((1,-1,-1),(1,1,-1),(1,1,1),(1,-1,1))]:
            self.quad(P(*a), P(*b), P(*c), P(*d))
    def tube(self, pts, r, segs=10, caps=True):
        """Sweep a circle of radius r (number or list per point) along a polyline."""
        rr = r if isinstance(r, (list, tuple)) else [r]*len(pts)
        keep = [0] + [i for i in range(1, len(pts)) if sum((pts[i][k]-pts[i-1][k])**2 for k in range(3)) > 1e-8]
        pts = [pts[i] for i in keep]; rr = [rr[i] for i in keep]
        if len(pts) < 2: return
        rings = []
        for i, p in enumerate(pts):
            a = pts[max(i-1, 0)]; b = pts[min(i+1, len(pts)-1)]
            t = [b[k]-a[k] for k in range(3)]; tl = math.sqrt(sum(x*x for x in t)) or 1; t = [x/tl for x in t]
            up = (0, 0, 1) if abs(t[2]) < 0.9 else (1, 0, 0)
            u = [t[1]*up[2]-t[2]*up[1], t[2]*up[0]-t[0]*up[2], t[0]*up[1]-t[1]*up[0]]; ul = math.sqrt(sum(x*x for x in u)); u = [x/ul for x in u]
            w = [t[1]*u[2]-t[2]*u[1], t[2]*u[0]-t[0]*u[2], t[0]*u[1]-t[1]*u[0]]
            rings.append([(p[0]+rr[i]*(math.cos(2*math.pi*k/segs)*u[0]+math.sin(2*math.pi*k/segs)*w[0]),
                           p[1]+rr[i]*(math.cos(2*math.pi*k/segs)*u[1]+math.sin(2*math.pi*k/segs)*w[1]),
                           p[2]+rr[i]*(math.cos(2*math.pi*k/segs)*u[2]+math.sin(2*math.pi*k/segs)*w[2])) for k in range(segs)])
        for i in range(len(rings)-1):
            for k in range(segs):
                k2 = (k+1) % segs
                self.quad(rings[i][k], rings[i][k2], rings[i+1][k2], rings[i+1][k])
        if caps:
            for ring, p in ((rings[0], pts[0]), (rings[-1], pts[-1])):
                for k in range(segs): self.tri(tuple(p), ring[k], ring[(k+1) % segs])
    def write(self, path):
        data = bytearray(struct.pack('>i', len(self.v)))
        for v in self.v: data += struct.pack('>12f', *v)
        open(path, 'wb').write(zlib.compress(bytes(data), 9))
        return len(self.v)

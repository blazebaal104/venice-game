"""Builds the title-screen flyover city from venice.map: every building of historic Venice extruded to its real height
(outlines simplified to ~1.2 m, party walls between touching buildings dropped), plaster walls in Venetian colours
darkening towards the ground, terracotta / lead roofs; plus a ground texture (canals, fondamenta, campi, gardens).
Compact format for assets/menu/city.bin (zlib): int vertexCount, then per vertex 3 float32 position + 4 bytes RGBA.
Run from the repo root: python3 tools/menu/build_menu_city.py"""
import os, sys, math, struct, zlib, random, collections
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
sys.path.insert(0, os.path.dirname(__file__))
import vmap
ROOT = os.path.join(os.path.dirname(__file__), '../..')
m = vmap.load(os.path.join(ROOT, 'app/src/main/assets/venice.map'))
X0, X1, Y0, Y1 = -2950, 2350, -1550, 1450
GX = 1.5   # ground texture metres per pixel

def dp(pts, eps):
    if len(pts) < 3: return pts
    a = np.array(pts[0]); b = np.array(pts[-1]); d = b - a; L = math.hypot(*d)
    best, bi = 0.0, 0
    for i in range(1, len(pts) - 1):
        p = np.array(pts[i])
        dist = abs(d[0] * (a[1] - p[1]) - d[1] * (a[0] - p[0])) / L if L > 1e-6 else math.hypot(*(p - a))
        if dist > best: best, bi = dist, i
    if best > eps: return dp(pts[:bi + 1], eps)[:-1] + dp(pts[bi:], eps)
    return [pts[0], pts[-1]]

def simplify_ring(pts, eps=1.2):
    if len(pts) < 5: return pts
    # split at the farthest point from the first to keep it closed
    far = max(range(len(pts)), key=lambda i: (pts[i][0] - pts[0][0]) ** 2 + (pts[i][1] - pts[0][1]) ** 2)
    a = dp(pts[:far + 1], eps); b = dp(pts[far:] + [pts[0]], eps)
    r = a[:-1] + b[:-1]
    return r if len(r) >= 3 else pts

def earcut(poly):
    """ear clipping for a simple polygon (list of (x, y)); returns index triples"""
    n = len(poly); idx = list(range(n))
    area = sum(poly[i][0] * poly[(i + 1) % n][1] - poly[(i + 1) % n][0] * poly[i][1] for i in range(n))
    if area < 0: idx.reverse()
    def cross(o, a, b): return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    out = []; guard = 0
    while len(idx) > 3 and guard < 5000:
        guard += 1; found = False
        for k in range(len(idx)):
            i0, i1, i2 = idx[k - 1], idx[k], idx[(k + 1) % len(idx)]
            A, B, C = poly[i0], poly[i1], poly[i2]
            if cross(A, B, C) <= 1e-9: continue
            ok = True
            for j in idx:
                if j in (i0, i1, i2): continue
                P = poly[j]
                if cross(A, B, P) > 0 and cross(B, C, P) > 0 and cross(C, A, P) > 0: ok = False; break
            if ok: out.append((i0, i1, i2)); idx.pop(k); found = True; break
        if not found: break
    if len(idx) == 3: out.append(tuple(idx))
    return out

WALLS = [(206, 160, 120), (190, 110, 80), (214, 190, 150), (170, 80, 60), (222, 205, 175), (200, 140, 100), (184, 150, 120), (226, 214, 190), (160, 100, 80)]
ROOFS = [(168, 84, 58), (180, 98, 66), (156, 76, 56), (190, 116, 82)]
LEAD = (120, 118, 114)
buf = bytearray(); count = 0
def V(x, y, z, c):
    global buf, count
    buf += struct.pack('<fffBBBB', x, y, z, c[0], c[1], c[2], 255); count += 1
blds = []
for k in m['dir']:
    ox = m['minX'] + k[0] * 256; oy = m['minY'] + k[1] * 256
    if not (X0 - 256 < ox < X1 and Y0 - 256 < oy < Y1): continue
    t = vmap.tile(m, *k)
    for (kind, h, seed, sizes, xy, tris) in t['blds']:
        if not sizes or sizes[0] < 3: continue
        pts = [(ox + xy[2 * i], oy + xy[2 * i + 1]) for i in range(sizes[0])]
        cx = sum(p[0] for p in pts) / len(pts); cy = sum(p[1] for p in pts) / len(pts)
        if not (X0 < cx < X1 and Y0 < cy < Y1): continue
        blds.append((simplify_ring(pts), max(3.0, h), seed))
# shared wall segments (rounded) -> skip when both sides are about the same height
segs = collections.defaultdict(list)
for bi, (pts, h, seed) in enumerate(blds):
    for i in range(len(pts)):
        a = (round(pts[i][0], 0), round(pts[i][1], 0)); b = (round(pts[(i + 1) % len(pts)][0], 0), round(pts[(i + 1) % len(pts)][1], 0))
        segs[(min(a, b), max(a, b))].append(h)
G = 1.1
for (pts, h, seed) in blds:
    rnd = random.Random(seed)
    wc = WALLS[rnd.randrange(len(WALLS))]; j = rnd.uniform(0.92, 1.06); wc = tuple(min(255, int(v * j)) for v in wc)
    rc = LEAD if (h > 22 and rnd.random() < 0.5) else ROOFS[rnd.randrange(len(ROOFS))]
    top = G + h
    n = len(pts)
    for i in range(n):
        a = pts[i]; b = pts[(i + 1) % n]
        ka = (round(a[0], 0), round(a[1], 0)); kb = (round(b[0], 0), round(b[1], 0))
        hs = segs[(min(ka, kb), max(ka, kb))]
        z0 = G
        if len(hs) > 1:
            others = sorted(hs)
            lower = [x for x in others if x < h - 0.5]
            if not lower and len([x for x in others if abs(x - h) <= 0.5]) > 1: continue      # fully hidden party wall
            if lower: z0 = G + max(lower)
        dark = tuple(int(v * 0.55) for v in wc)
        mid = tuple(int(v * (0.55 + 0.45 * min(1, (z0 - G) / max(h, 1)))) for v in wc)
        bottom = dark if z0 == G else mid
        V(a[0], a[1], z0, bottom); V(b[0], b[1], z0, bottom); V(b[0], b[1], top, wc)
        V(a[0], a[1], z0, bottom); V(b[0], b[1], top, wc); V(a[0], a[1], top, wc)
    for (i0, i1, i2) in earcut(pts):
        for q in (i0, i1, i2):
            # a touch of variation across the roof so it isn't a flat colour
            s = 0.93 + 0.07 * ((q * 7) % 3) / 2
            V(pts[q][0], pts[q][1], top + 0.2, tuple(int(v * s) for v in rc))
out_dir = os.path.join(ROOT, 'app/src/main/assets/menu'); os.makedirs(out_dir, exist_ok=True)
open(os.path.join(out_dir, 'city.bin'), 'wb').write(zlib.compress(struct.pack('<i', count) + bytes(buf), 9))
print('buildings', len(blds), 'vertices', count, 'MB raw', round(len(buf) / 1e6, 1))

# ---------------------------------------------------------------- ground texture
W = int((X1 - X0) / GX); H = int((Y1 - Y0) / GX)
land = Image.new('L', (W, H), 0); marsh = Image.new('L', (W, H), 0); cls = Image.new('L', (W, H), 0)
for k in m['dir']:
    ox = m['minX'] + k[0] * 256; oy = m['minY'] + k[1] * 256
    if not (X0 - 256 < ox < X1 and Y0 - 256 < oy < Y1): continue
    t = vmap.tile(m, *k)
    if not t['hard']: continue
    n = t['mn']
    for src, dst in ((t['hard'], land), (t['marsh'], marsh), (t['cls'], cls)):
        a = Image.fromarray(np.frombuffer(src, np.uint8).reshape(n, n)[::-1].copy())
        a = a.resize((max(1, int(n / GX)), max(1, int(n / GX))), Image.BILINEAR)
        px = int((ox - 0.5 - X0) / GX); py = int((Y1 - (oy + n - 0.5)) / GX)
        cur = dst.crop((px, py, px + a.width, py + a.height))
        dst.paste(Image.fromarray(np.maximum(np.array(cur), np.array(a))), (px, py))
L = np.array(land, np.float32) / 255; M = np.array(marsh, np.float32) / 255; C = np.array(cls)
rng = np.random.default_rng(5)
noise = np.array(Image.fromarray((rng.random((H // 48 + 2, W // 48 + 2)) * 255).astype(np.uint8)).resize((W, H), Image.BICUBIC), np.float32) / 255
fine = rng.random((H, W)).astype(np.float32)
near = np.array(land.filter(ImageFilter.GaussianBlur(10)), np.float32) / 255
water = np.stack([30 + 10 * noise + 22 * near, 86 + 14 * noise + 22 * near, 92 + 10 * noise + 14 * near], -1)
stone = np.stack([178 + 14 * fine, 170 + 12 * fine, 152 + 10 * fine], -1)
grass = np.stack([92 + 20 * noise, 112 + 16 * noise, 66 + 10 * noise], -1)
img = water * (1 - L[..., None]) + stone * L[..., None]
gmask = ((C == 2) | (C == 3)).astype(np.float32) * L
img = img * (1 - gmask[..., None]) + grass * gmask[..., None]
img = img * (1 - (M * (1 - L))[..., None]) + np.array([104, 118, 74], np.float32) * (M * (1 - L))[..., None]
edge = np.clip(np.array(land.filter(ImageFilter.FIND_EDGES), np.float32) / 255 * 1.4, 0, 1)
img *= (1 - 0.35 * edge)[..., None]
Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).save(os.path.join(out_dir, 'ground.jpg'), quality=82)
print('ground', W, H)

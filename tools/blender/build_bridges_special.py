# exec()d by build_bridges.py: the three special bridges, modelled from photographs / published dimensions

def spire(cx, cy, z0, h=2.7, w=0.30):
    """Ponte delle Guglie obelisk: moulded pedestal, stepped neck, tall square pyramid, ball finial"""
    bx(cx - w * 1.35, cx + w * 1.35, cy - w * 1.35, cy + w * 1.35, z0, z0 + 0.35, "stone")
    bx(cx - w * 1.05, cx + w * 1.05, cy - w * 1.05, cy + w * 1.05, z0 + 0.35, z0 + 1.05, "ashlar")
    bx(cx - w * 1.2, cx + w * 1.2, cy - w * 1.2, cy + w * 1.2, z0 + 1.05, z0 + 1.20, "stone")
    pyramid(cx, cy, z0 + 1.20, w * 0.95, w * 0.95, h - 1.35, "stone")
    blob(V(cx, cy, z0 + h - 0.05), 0.09, 0.09, 0.09, "stone", 6, 4)

def mascaron(x, y, z, sg, s=1.0):
    """carved head on the keystone: block with brow, nose and chin"""
    bx(x - 0.22 * s, x + 0.22 * s, y if sg > 0 else y - 0.16 * s, y + 0.16 * s if sg > 0 else y, z - 0.30 * s, z + 0.30 * s, "slab")
    bx(x - 0.05 * s, x + 0.05 * s, y + (0.16 * s if sg > 0 else -0.24 * s), y + (0.24 * s if sg > 0 else -0.16 * s), z - 0.10 * s, z + 0.12 * s, "slab")

def build_guglie():
    A = Arch(22.0, 6.2, 2.95, abut=1.9, t=0.72, springz=0.45, plateau=0.18, tread=0.38, riser=0.16)
    new_part()
    for sg in (-1, 1):
        A.spandrel(sg, "ashlar", 0.0)
        A.ring(sg, "ring", w=0.46, proud=0.06)
        mascaron(0.0, sg * (A.W / 2 + 0.06), A.intr(0) + 0.34, sg, 1.1)
        for x in (-A.S * 0.5, A.S * 0.5): mascaron(x, sg * (A.W / 2 + 0.02), A.intr(x) + 0.30, sg, 0.55)      # smaller gargoyle masks
        xs = xs_dense(-A.hl, A.hl, 0.4)                                                                           # cornice under the balustrade
        for a, b in zip(xs[:-1], xs[1:]):
            tbox(a, b, sg * A.W / 2 - (0 if sg < 0 else 0.12), sg * A.W / 2 + (0.12 if sg > 0 else 0), A.raw(a) - 0.24, A.raw(b) - 0.24, A.raw(a) + 0.02, A.raw(b) + 0.02, "stone")
    A.intrados("brick2"); A.ends("ashlar")
    A.deck("tread", "nosing")
    balustrade(A, ph=1.0, pillar_every=2.8)
    for sg in (-1, 1):                         # the four guglie, on the end pillars of the parapets
        for sx in (-1, 1): spire(sx * (A.hl - 0.45), sg * (A.W / 2 - 0.16), A.raw(sx * A.hl) + 0.0, h=3.4, w=0.34)
    # metal handrails along the steps (1987 restoration)
    for y in (-1.0, 1.0):
        xs = xs_dense(-A.hl + 0.6, A.hl - 0.6, 0.4)
        for a, b in zip(xs[:-1], xs[1:]):
            tbox(a, b, y - 0.025, y + 0.025, A.raw(a) + 0.9, A.raw(b) + 0.9, A.raw(a) + 0.95, A.raw(b) + 0.95, "iron")
        for x in xs_dense(-A.hl + 0.6, A.hl - 0.6, 1.6): bx(x - 0.025, x + 0.025, y - 0.025, y + 0.025, A.raw(x), A.raw(x) + 0.95, "iron")
    return end_part("b_guglie", 1.3), dict(L=22.0, W=6.2, long=False, height=6.2, low=True, look_z=1.5)

def build_scalzi():
    L, W, R = 40.0, 9.0, 5.65
    A = Arch(L, W, R, abut=2.6, t=1.05, springz=0.50, plateau=0.22, tread=0.46, riser=0.15)
    new_part()
    for sg in (-1, 1):
        A.spandrel(sg, "ashlar", 0.0)
        A.ring(sg, "ring", w=0.62, proud=0.07)
        xs = xs_dense(-A.hl, A.hl, 0.5)
        for a, b in zip(xs[:-1], xs[1:]):                  # heavy string course / cornice
            tbox(a, b, sg * W / 2 - (0 if sg < 0 else 0.2), sg * W / 2 + (0.2 if sg > 0 else 0), A.raw(a) - 0.24, A.raw(b) - 0.24, A.raw(a) + 0.02, A.raw(b) + 0.02, "stone")
        for sx in (-1, 1):                                  # rusticated abutment pilasters flanking the arch
            px = sx * (A.S + 0.1)
            bx(px - 0.6, px + 0.6, sg * W / 2 - (0.0 if sg < 0 else 0.0) - (0.14 if sg < 0 else 0), sg * W / 2 + (0.14 if sg > 0 else 0), -0.6, A.raw(px) - 0.4, "ashlar")
    A.intrados("vault"); A.ends("ashlar")
    A.deck("tread", "nosing")
    balustrade(A, ph=1.15, pillar_every=4.0)
    # large end blocks with a pyramidal cap on each parapet end (stone, like the real bridge ends)
    for sg in (-1, 1):
        for sx in (-1, 1):
            x = sx * (A.hl - 0.55); y = sg * (W / 2 - 0.25); z = A.raw(sx * A.hl)
            bx(x - 0.45, x + 0.45, y - 0.32, y + 0.32, z, z + 1.0, "ashlar")
            bx(x - 0.5, x + 0.5, y - 0.37, y + 0.37, z + 1.0, z + 1.12, "stone")
            pyramid(x, y, z + 1.12, 0.40, 0.30, 0.38, "stone")
    # central iron handrails on the long flights
    for y in (-2.25, 2.25):
        xs = xs_dense(-A.hl + 0.8, A.hl - 0.8, 0.5)
        for a, b in zip(xs[:-1], xs[1:]):
            tbox(a, b, y - 0.025, y + 0.025, A.raw(a) + 0.88, A.raw(b) + 0.88, A.raw(a) + 0.93, A.raw(b) + 0.93, "iron")
        for x in xs_dense(-A.hl + 0.8, A.hl - 0.8, 2.0): bx(x - 0.025, x + 0.025, y - 0.025, y + 0.025, A.raw(x), A.raw(x) + 0.93, "iron")
    return end_part("b_scalzi", 1.8), dict(L=L, W=W, long=False, height=8.0, lens=38, low=True, look_z=3.0)

def build_costituzione():
    """Calatrava bridge: 80 m steel arch, deck flares from 9.4 m (crown) to 17.7 m (abutments), low wide steps alternating
    Istrian stone and lit glass treads, glass parapets with a bronze handrail, abutments clad in Istrian stone"""
    L = 80.0; hl = L / 2; WMAX, WMIN = 17.7, 9.4
    A = Arch(L, WMAX, 6.4, abut=4.2, t=1.35, springz=0.6, plateau=0.20, tread=0.55, riser=0.12)
    wf = lambda x: WMIN + (WMAX - WMIN) * (abs(x) / hl) ** 2.4
    new_part()
    n = int(math.ceil(L / A.tread)); xs = [-hl + L * i / n for i in range(n + 1)]
    zs = [A.zq((a + b) / 2) for a, b in zip(xs[:-1], xs[1:])]
    for k, (a, b) in enumerate(zip(xs[:-1], xs[1:])):
        wa, wb = wf(a), wf(b); z = zs[k]
        m = "glass" if k % 2 else "tread"
        qd(V(a, -wa / 2, z), V(b, -wb / 2, z), V(b, wb / 2, z), V(a, wa / 2, z), m, (0, 0, 1))
        if k + 1 < len(zs) and abs(zs[k + 1] - z) > 0.01:
            lo, hi = sorted((z, zs[k + 1])); sgn = 1 if z > zs[k + 1] else -1
            qd(V(b, -wb / 2, lo), V(b, wb / 2, lo), V(b, wb / 2, hi), V(b, -wb / 2, hi), "steel_brown", (sgn, 0, 0))
        # soffit plate + fascia
        qd(V(a, -wa / 2, A.raw(a) - 0.55), V(b, -wb / 2, A.raw(b) - 0.55), V(b, wb / 2, A.raw(b) - 0.55), V(a, wa / 2, A.raw(a) - 0.55), "steel_brown", (0, 0, -1))
        for sg in (-1, 1):
            qd(V(a, sg * wa / 2, A.raw(a) - 0.55), V(b, sg * wb / 2, A.raw(b) - 0.55), V(b, sg * wb / 2, A.raw(b) + 0.03), V(a, sg * wa / 2, A.raw(a) + 0.03), "steel_brown", (0, sg, 0))
    # abutments: Istrian stone blocks under the first / last 4.2 m
    for sx in (-1, 1):
        for sg in (-1, 1):
            xa = [sx * hl, sx * (hl - A.hl + A.S)] if False else None
        x0, x1 = (hl - 4.2, hl) if sx > 0 else (-hl, -hl + 4.2)
        xs2 = xs_dense(x0, x1, 0.7)
        for sg in (-1, 1):
            for a, b in zip(xs2[:-1], xs2[1:]):
                qd(V(a, sg * wf(a) / 2, -1.0), V(b, sg * wf(b) / 2, -1.0), V(b, sg * wf(b) / 2, A.raw(b) - 0.55), V(a, sg * wf(a) / 2, A.raw(a) - 0.55), "ashlar", (0, sg, 0))
        qd(V(sx * hl, -WMAX / 2, -1.0), V(sx * hl, WMAX / 2, -1.0), V(sx * hl, WMAX / 2, G), V(sx * hl, -WMAX / 2, G), "ashlar", (sx, 0, 0))
        sxi = sx * (hl - 4.2)
        qd(V(sxi, -wf(sxi) / 2, -1.0), V(sxi, wf(sxi) / 2, -1.0), V(sxi, wf(sxi) / 2, A.springz), V(sxi, -wf(sxi) / 2, A.springz), "ashlar", (-sx, 0, 0))
    # steel arch ribs (box girders) below the deck: five ribs following the flare, crown below the deck by 1.1 m
    S = hl - 4.2
    h = (A.raw(0) - 1.2) - A.springz; r = (S * S + h * h) / (2 * h); cz = (A.raw(0) - 1.2) - r
    rib = lambda x: cz + math.sqrt(max(r * r - x * x, 0))
    xr = xs_dense(-S, S, 1.0)
    for f in (-0.46, -0.23, 0.0, 0.23, 0.46):
        for a, b in zip(xr[:-1], xr[1:]):
            ya, yb = f * wf(a), f * wf(b)
            tbox(a, b, min(ya, yb) - 0.30, max(ya, yb) + 0.30, rib(a) - 0.32, rib(b) - 0.32, rib(a) + 0.32, rib(b) + 0.32, "steel_brown")
    # edge arches: heavy tubular steel rib running along each deck edge, bundled with a second thinner tube
    for sg in (-1, 1):
        for off, t_ in ((0.0, 0.38), (0.55, 0.22)):
            for a, b in zip(xr[:-1], xr[1:]):
                ya, yb = sg * (wf(a) / 2 - off), sg * (wf(b) / 2 - off)
                za, zb = A.raw(a) - 0.95 - off * 0.3, A.raw(b) - 0.95 - off * 0.3
                tbox(a, b, min(ya, yb) - t_ / 2, max(ya, yb) + t_ / 2, za - t_ / 2, zb - t_ / 2, za + t_ / 2, zb + t_ / 2, "steel_brown")
    # solid steel fascia box along each deck edge: the bold dark "bow" seen from the water, widening towards the abutments
    for sg in (-1, 1):
        for a, b in zip(xr[:-1], xr[1:]):
            ya, yb = sg * wf(a) / 2, sg * wf(b) / 2
            za0 = max(rib(a) - 0.1, A.springz) if False else rib(a) + 0.05; zb0 = rib(b) + 0.05
            qd(V(a, ya, min(za0, A.raw(a) - 0.6)), V(b, yb, min(zb0, A.raw(b) - 0.6)), V(b, yb, A.raw(b) + 0.03), V(a, ya, A.raw(a) + 0.03), "steel_brown", (0, sg, 0))
            qd(V(a, ya, min(za0, A.raw(a) - 0.6)), V(b, yb, min(zb0, A.raw(b) - 0.6)), V(b, yb, A.raw(b) + 0.03), V(a, ya, A.raw(a) + 0.03), "steel_brown", (0, -sg, 0))
    # cross girders + hangers from the ribs to the deck
    for x in xs_dense(-S, S, 2.75):
        for f in (-0.46, -0.23, 0.0, 0.23, 0.46):
            y = f * wf(x)
            if A.raw(x) - 0.55 > rib(x) + 0.35: bx(x - 0.07, x + 0.07, y - 0.07, y + 0.07, rib(x) + 0.30, A.raw(x) - 0.55, "steel_brown")
    # glass parapets: bays between bronze posts, bronze handrail following the deck edge
    px = xs_dense(-hl + 0.4, hl - 0.4, 2.75)
    for sg in (-1, 1):
        for a, b in zip(px[:-1], px[1:]):
            ya, yb = sg * (wf(a) / 2 - 0.10), sg * (wf(b) / 2 - 0.10)
            qd(V(a + 0.07, ya, A.raw(a) + 0.12), V(b - 0.07, yb, A.raw(b) + 0.12), V(b - 0.07, yb, A.raw(b) + 1.0), V(a + 0.07, ya, A.raw(a) + 1.0), "glass", (0, sg, 0))
            qd(V(a + 0.07, ya, A.raw(a) + 0.12), V(b - 0.07, yb, A.raw(b) + 0.12), V(b - 0.07, yb, A.raw(b) + 1.0), V(a + 0.07, ya, A.raw(a) + 1.0), "glass", (0, -sg, 0))
        for x in px:
            y = sg * (wf(x) / 2 - 0.10); bx(x - 0.035, x + 0.035, y - 0.035, y + 0.035, A.raw(x) + 0.03, A.raw(x) + 1.08, "bronze")
        xh = xs_dense(-hl + 0.4, hl - 0.4, 1.0)
        for a, b in zip(xh[:-1], xh[1:]):
            ya, yb = sg * (wf(a) / 2 - 0.10), sg * (wf(b) / 2 - 0.10)
            tbox(a, b, min(ya, yb) - 0.05, max(ya, yb) + 0.05, A.raw(a) + 1.0, A.raw(b) + 1.0, A.raw(a) + 1.1, A.raw(b) + 1.1, "bronze")
    return end_part("b_costituzione", 2.2), dict(L=L, W=WMAX, long=False, height=10.5, lens=34, hero_h=0.30, look_z=3.5, low=True)

def build_accademia():
    """Ponte dell'Accademia (Miozzi, 1933): 48 m shallow timber arch with a steel arch hidden behind the wooden fascia,
    wide shallow plank steps, deep laminated side beams that thicken towards the abutments, timber railings."""
    L, W, R = 48.0, 9.0, 3.6
    class WA(Arch):
        def raw(s, x):
            u = min(abs(x) / s.hl, 1.0)
            return G + s.R * (1 - u ** 1.7)
    A = WA(L, W, R, abut=3.0, t=1.2, springz=0.30, plateau=0.0, tread=0.62, riser=0.13)
    hl = A.hl
    def depth(x): return 0.62 + 0.85 * (abs(x) / hl) ** 2          # fascia beam depth, thicker at the springs
    new_part()
    xs = xs_dense(-hl, hl, 0.5)
    # deck underside (planked soffit) + cross beams
    for a, b in zip(xs[:-1], xs[1:]):
        tbox(a, b, -W / 2, W / 2, A.raw(a) - 0.42, A.raw(b) - 0.42, A.raw(a) - 0.06, A.raw(b) - 0.06, "wood", bottom=True)
    for x in xs_dense(-hl + 0.4, hl - 0.4, 1.5):
        bx(x - 0.09, x + 0.09, -W / 2 + 0.05, W / 2 - 0.05, A.raw(x) - 0.62, A.raw(x) - 0.42, "wood", bottom=True)
    # side fascia beams (laminated timber), slightly proud of the deck edge
    for sg in (-1, 1):
        y0, y1 = (sg * W / 2 - 0.14, sg * W / 2 + 0.10) if sg > 0 else (sg * W / 2 - 0.10, sg * W / 2 + 0.14)
        for a, b in zip(xs[:-1], xs[1:]):
            tbox(a, b, y0, y1, A.raw(a) - depth(a) - 0.12, A.raw(b) - depth(b) - 0.12, A.raw(a) + 0.02, A.raw(b) + 0.02, "wood", bottom=True)
    # steel arches behind/under the deck (three ribs) with diagonal struts up to the deck
    for y in (-W / 2 + 1.0, 0.0, W / 2 - 1.0):
        for a, b in zip(xs[:-1], xs[1:]):
            tbox(a, b, y - 0.11, y + 0.11, A.raw(a) - depth(a) - 0.40, A.raw(b) - depth(b) - 0.40, A.raw(a) - depth(a) - 0.12, A.raw(b) - depth(b) - 0.12, "steel_grey", bottom=True)
        for x in xs_dense(-hl + 1.0, hl - 1.0, 1.5):
            z0 = A.raw(x) - depth(x) - 0.12; z1 = A.raw(x) - 0.42
            if z1 - z0 > 0.08: bx(x - 0.04, x + 0.04, y - 0.05, y + 0.05, z0, z1, "steel_grey")
    # abutments: stone blocks carrying the arch ends on the quay
    for sx in (-1, 1):
        a, b = sorted((sx * (hl - 2.4), sx * hl))
        qd(V(a, -W / 2, -0.6), V(b, -W / 2, -0.6), V(b, -W / 2, G), V(a, -W / 2, G), "stone", (0, -1, 0))
        qd(V(a, W / 2, -0.6), V(b, W / 2, -0.6), V(b, W / 2, G), V(a, W / 2, G), "stone", (0, 1, 0))
        qd(V(sx * hl, -W / 2, -0.6), V(sx * hl, W / 2, -0.6), V(sx * hl, W / 2, G), V(sx * hl, -W / 2, G), "stone", (sx, 0, 0))
    A.deck("planks", "wood")
    # timber railings: heavy posts every 1.5 m, four rails, wide top rail
    ph = 1.08
    for sg in (-1, 1):
        y = sg * (W / 2 - 0.08)
        for a, b in zip(xs[:-1], xs[1:]):
            for zo, hh in ((0.22, 0.03), (0.52, 0.03), (0.80, 0.03)):
                tbox(a, b, y - 0.025, y + 0.025, A.raw(a) + zo - hh, A.raw(b) + zo - hh, A.raw(a) + zo + hh, A.raw(b) + zo + hh, "wood")
            tbox(a, b, y - 0.085, y + 0.085, A.raw(a) + ph - 0.04, A.raw(b) + ph - 0.04, A.raw(a) + ph + 0.04, A.raw(b) + ph + 0.04, "wood")
        np_ = int(L / 1.5)
        for i in range(np_ + 1):
            x = -hl + 0.12 + (L - 0.24) * i / np_
            bx(x - 0.055, x + 0.055, y - 0.055, y + 0.055, A.raw(x) - 0.05, A.raw(x) + ph, "wood")
    return end_part("b_accademia", 1.5), dict(L=L, W=W, long=False, height=5.0, lens=44, low=True, look_z=2.4)

for nm, fn in (("guglie", build_guglie), ("scalzi", build_scalzi), ("costituzione", build_costituzione), ("accademia", build_accademia)):
    if want(nm): reg(nm, *fn())

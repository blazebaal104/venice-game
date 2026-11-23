# exec()d by build_bridges.py: the six long bridge types (assembled demo of modules: end caps + repeated bays)

def deck_slab(xs, zf, W, th, top, side, bottom=None):
    """slab with a top surface following zf(x); thickness th"""
    for a, b in zip(xs[:-1], xs[1:]):
        za, zb = zf(a), zf(b)
        qd(V(a, -W / 2, za), V(b, -W / 2, zb), V(b, W / 2, zb), V(a, W / 2, za), top, (0, 0, 1))
        for sg in (-1, 1):
            qd(V(a, sg * W / 2, za - th), V(b, sg * W / 2, zb - th), V(b, sg * W / 2, zb), V(a, sg * W / 2, za), side, (0, sg, 0))
        if bottom: qd(V(a, -W / 2, za - th), V(b, -W / 2, zb - th), V(b, W / 2, zb - th), V(a, W / 2, za - th), bottom, (0, 0, -1))

def solid_wall(xs, zf_top, zf_bot, y, side, mat):
    for a, b in zip(xs[:-1], xs[1:]):
        qd(V(a, y, zf_bot(a)), V(b, y, zf_bot(b)), V(b, y, zf_top(b)), V(a, y, zf_top(a)), mat, (0, side, 0))

def arch_bay(xc, bay, span, zs, zc, W, topfn, mat_face, mat_int, wall=-1.0, face_inset=0.0):
    """one segmental arch opening in a viaduct: faces on both sides above the opening, piers either side, intrados + pier flanks"""
    h = zc - zs; S = span / 2
    r = (S * S + h * h) / (2 * h); cz = zc - r
    intr = lambda x: cz + math.sqrt(max(r * r - (x - xc) ** 2, 0))
    xs = xs_dense(xc - S, xc + S, 0.5)
    for sg in (-1, 1):
        y = sg * W / 2
        for a, b in zip(xs[:-1], xs[1:]):
            qd(V(a, y, intr(a)), V(b, y, intr(b)), V(b, y, topfn(b)), V(a, y, topfn(a)), mat_face, (0, sg, 0))
        for x0, x1 in ((xc - bay / 2, xc - S), (xc + S, xc + bay / 2)):
            qd(V(x0, y, wall), V(x1, y, wall), V(x1, y, topfn(x1)), V(x0, y, topfn(x0)), mat_face, (0, sg, 0))
    for a, b in zip(xs[:-1], xs[1:]):
        qd(V(a, -W / 2, intr(a)), V(b, -W / 2, intr(b)), V(b, W / 2, intr(b)), V(a, W / 2, intr(a)), mat_int, (0, 0, -1))
    for sx in (-1, 1):
        qd(V(xc + sx * S, -W / 2, wall), V(xc + sx * S, W / 2, wall), V(xc + sx * S, W / 2, zs), V(xc + sx * S, -W / 2, zs), mat_int, (-sx, 0, 0))
    return intr

def wall_parapet(xs, zf, W, h, t, out, inn, top, pil_every=0, pil_mat=None, off=0.0):
    for sg in (-1, 1):
        yo = sg * (W / 2 - off); yi = sg * (W / 2 - off - t)
        for a, b in zip(xs[:-1], xs[1:]):
            za, zb = zf(a), zf(b)
            qd(V(a, yo, za), V(b, yo, zb), V(b, yo, zb + h), V(a, yo, za + h), out, (0, sg, 0))
            qd(V(a, yi, za), V(b, yi, zb), V(b, yi, zb + h), V(a, yi, za + h), inn, (0, -sg, 0))
            qd(V(a, yo, za + h), V(b, yo, zb + h), V(b, yi, zb + h), V(a, yi, za + h), top, (0, 0, 1))
        if pil_every:
            x = xs[0]
            while x <= xs[-1] + 1e-6:
                z = zf(x)
                bx(x - 0.28, x + 0.28, min(yo, yi) - (0.06 if sg < 0 else 0), max(yo, yi) + (0.06 if sg > 0 else 0), z, z + h + 0.18, pil_mat or top)
                x += pil_every

def tube_rail(xs, zf, W, h, mat, post_every=2.4, rails=(0.55, 1.1), off=0.12, bars=0.0, post=0.04):
    for sg in (-1, 1):
        y = sg * (W / 2 - off)
        for a, b in zip(xs[:-1], xs[1:]):
            za, zb = zf(a), zf(b)
            for rh in rails:
                tbox(a, b, y - 0.025, y + 0.025, za + rh - 0.025, zb + rh - 0.025, za + rh + 0.025, zb + rh + 0.025, mat)
        x = xs[0]
        while x <= xs[-1] + 1e-6:
            z = zf(x); bx(x - post, x + post, y - post, y + post, z, z + h, mat); x += post_every
        if bars:
            x = xs[0]
            while x <= xs[-1] + 1e-6:
                z = zf(x); bx(x - 0.012, x + 0.012, y - 0.012, y + 0.012, z + 0.1, z + h - 0.05, mat); x += bars

def lamp(x, y, z, h=7.5, arm=1.8, sg=1, mat="steel_grey"):
    bx(x - 0.08, x + 0.08, y - 0.08, y + 0.08, z, z + h, mat)
    tbox(x - 0.04, x + 0.04, y, y - sg * arm, z + h - 0.08, z + h - 0.08, z + h, z + h, mat) if False else None
    bx(x - 0.04, x + 0.04, min(y, y - sg * arm), max(y, y - sg * arm), z + h - 0.06, z + h + 0.02, mat)
    bx(x - 0.22, x + 0.22, min(y - sg * arm, y - sg * (arm + 0.45)), max(y - sg * arm, y - sg * (arm + 0.45)), z + h - 0.14, z + h - 0.04, "dark")

def lathe_pile(x, y, z0, z1, r, mat="wood"):
    lathe(x, y, z0, [(r, 0.0), (r, z1 - z0)], mat, 8)
    qd(V(x - r * 0.7, y - r * 0.7, z1), V(x + r * 0.7, y - r * 0.7, z1), V(x + r * 0.7, y + r * 0.7, z1), V(x - r * 0.7, y + r * 0.7, z1), mat, (0, 0, 1))

# ------------------------------------------------------------------ L1 timber pile bridge
def build_timber():
    L, W = 36.0, 3.0; hl = L / 2; top = 2.35
    zf = lambda x: min(top, G + (hl - abs(x)) * 0.24)
    new_part()
    xs = xs_dense(-hl, hl, 0.5)
    for a, b in zip(xs[:-1], xs[1:]):
        za, zb = zf(a), zf(b)
        qd(V(a, -W / 2, za), V(b, -W / 2, zb), V(b, W / 2, zb), V(a, W / 2, za), "planks", (0, 0, 1))
        for sg in (-1, 1):           # fascia beam
            tbox(a, b, sg * W / 2 - (0.0 if sg < 0 else 0.14), sg * W / 2 + (0.14 if sg > 0 else 0.0), za - 0.38, zb - 0.38, za, zb, "wood")
        for y in (-0.75, 0.0, 0.75): tbox(a, b, y - 0.07, y + 0.07, za - 0.3, zb - 0.3, za - 0.0 - 0.01, zb - 0.01, "wood") if False else None
    # bents every 3 m: three piles, cap beam, diagonal braces
    x = -hl + 1.5
    while x < hl - 1.0:
        z = zf(x)
        for y in (-1.15, 0.0, 1.15): lathe_pile(x, y, -2.6, z - 0.55, 0.17)
        bx(x - 0.15, x + 0.15, -W / 2 - 0.05, W / 2 + 0.05, z - 0.55, z - 0.38, "wood")
        if z > 1.6:
            for sg in (-1, 1):
                a0 = V(x - 0.05, sg * 1.15, -0.2); b0 = V(x + 0.05, sg * 1.15 - sg * 1.1, z - 0.9)
                tbox(x - 0.04, x + 0.04, sg * 0.6 - 0.04, sg * 0.6 + 0.04, 0.2, 0.2, z - 0.9, z - 0.9, "wood") if False else None
        x += 3.0
    # railing: posts, two rails (timber)
    for sg in (-1, 1):
        y = sg * (W / 2 - 0.08)
        for a, b in zip(xs[:-1], xs[1:]):
            za, zb = zf(a), zf(b)
            for rh in (1.0, 0.55):
                tbox(a, b, y - 0.05, y + 0.05, za + rh - 0.04, zb + rh - 0.04, za + rh + 0.04, zb + rh + 0.04, "wood")
        x = -hl
        while x <= hl + 1e-6:
            z = zf(x); bx(x - 0.06, x + 0.06, y - 0.06, y + 0.06, z, z + 1.12, "wood"); x += 1.5
    # mooring dolphins (briccole): a few tall piles beside the bridge
    for x, y in ((-6.0, -3.0), (-5.6, -3.4), (9.0, 3.2), (9.4, 3.6)):
        lathe_pile(x, y, -3.0, 2.6, 0.22)
    return end_part("b_timber", 1.0), dict(L=L, W=W, long=True, height=4.0, lens=40, look_z=0.5, hero_h=0.30)

# ------------------------------------------------------------------ L2 Ponte della Liberta (road viaduct)
def build_liberta():
    bay = 12.0; n = 3; L = bay * n + 2.0; W = 22.0; hl = L / 2; top = 3.6
    zf = lambda x: top
    new_part()
    xs = xs_dense(-hl, hl, 1.0)
    deck_slab(xs, zf, W, 0.5, "asphalt", "ashlar", "vault")
    # sidewalks / kerbs
    for sg in (-1, 1):
        bx(-hl, hl, sg * (W / 2 - 2.0) - (0.15 if sg < 0 else 0.0), sg * (W / 2 - 2.0) + (0.15 if sg > 0 else 0.0), top, top + 0.18, "slab")
        bx(-hl, hl, sg * (W / 2 - 1.85) if sg > 0 else -W / 2, sg * (W / 2) if sg > 0 else -(W / 2 - 1.85), top, top + 0.16, "slab")
    # centre barrier
    bx(-hl, hl, -0.3, 0.3, top, top + 0.8, "concrete")
    # arcade: faces in brick, cornice + parapet in Istrian stone
    for k in range(n):
        xc = -hl + 1.0 + bay * (k + 0.5)
        arch_bay(xc, bay, 9.6, 1.1, 3.0, W, lambda x: top - 0.5, "brick2", "vault")
    for sg in (-1, 1):                      # end walls + abutment faces
        for sx in (-1, 1):
            qd(V(sx * hl, -W / 2, -1.0), V(sx * hl, W / 2, -1.0), V(sx * hl, W / 2, top), V(sx * hl, -W / 2, top), "concrete", (sx, 0, 0))
    for sg in (-1, 1):                      # end bays between the first/last pier and the cap (pier face)
        for x0, x1 in ((-hl, -hl + 1.0), (hl - 1.0, hl)):
            qd(V(x0, sg * W / 2, -1.0), V(x1, sg * W / 2, -1.0), V(x1, sg * W / 2, top - 0.5), V(x0, sg * W / 2, top - 0.5), "brick2", (0, sg, 0))
    for sg in (-1, 1):                      # cornice band
        bx(-hl - 0.05, hl + 0.05, sg * W / 2 - (0 if sg < 0 else 0.0) - (0.12 if sg < 0 else 0), sg * W / 2 + (0.12 if sg > 0 else 0), top - 0.5, top, "stone")
    wall_parapet(xs, zf, W, 1.0, 0.35, "ashlar", "stone", "slab", pil_every=6.0, pil_mat="stone")
    for x in (-hl + 6, hl - 6):
        for sg in (-1, 1): lamp(x, sg * (W / 2 - 0.9), top + 0.18, 7.0, 0.0 if False else 1.6, sg)
    return end_part("b_liberta", 1.6), dict(L=L, W=W, long=True, height=6.0, lens=45, look_z=1.5, hero_h=0.30)

# ------------------------------------------------------------------ L3 railway viaduct (1846)
def build_rail():
    bay = 10.0; n = 3; L = bay * n + 2.0; W = 9.6; hl = L / 2; top = 4.6
    zf = lambda x: top
    new_part()
    xs = xs_dense(-hl, hl, 1.0)
    deck_slab(xs, zf, W, 0.7, "ballast", "ashlar", "vault")
    for k in range(n):
        xc = -hl + 1.0 + bay * (k + 0.5)
        arch_bay(xc, bay, 7.6, 1.0, 3.6, W, lambda x: top - 0.55, "ashlar", "vault")
    for sx in (-1, 1):
        qd(V(sx * hl, -W / 2, -1.0), V(sx * hl, W / 2, -1.0), V(sx * hl, W / 2, top), V(sx * hl, -W / 2, top), "ashlar", (sx, 0, 0))
    for sg in (-1, 1):
        for x0, x1 in ((-hl, -hl + 1.0), (hl - 1.0, hl)):
            qd(V(x0, sg * W / 2, -1.0), V(x1, sg * W / 2, -1.0), V(x1, sg * W / 2, top - 0.55), V(x0, sg * W / 2, top - 0.55), "ashlar", (0, sg, 0))
        bx(-hl - 0.06, hl + 0.06, sg * W / 2 - (0.14 if sg < 0 else 0), sg * W / 2 + (0.14 if sg > 0 else 0), top - 0.55, top - 0.18, "stone")      # cornice
    wall_parapet(xs, zf, W, 0.9, 0.55, "brick", "brick", "slab", pil_every=10.0, pil_mat="stone", off=0.0)
    # ballast bed + two tracks
    bx(-hl, hl, -W / 2 + 0.55, W / 2 - 0.55, top, top + 0.22, "ballast")
    for ty in (-1.6, 1.6):
        x = -hl + 0.3
        while x < hl - 0.2:
            bx(x - 0.13, x + 0.13, ty - 1.3, ty + 1.3, top + 0.22, top + 0.35, "wood"); x += 0.6
        for ry in (ty - 0.7175, ty + 0.7175):
            bx(-hl, hl, ry - 0.035, ry + 0.035, top + 0.35, top + 0.52, "rail")
    # catenary masts (steel) + wire
    for x in (-hl + 5.0, hl - 5.0):
        for sg in (-1, 1):
            bx(x - 0.12, x + 0.12, sg * (W / 2 - 0.65) - 0.12, sg * (W / 2 - 0.65) + 0.12, top + 0.22, top + 6.6, "steel_grey")
            bx(x - 0.05, x + 0.05, min(sg * (W / 2 - 0.65), sg * 1.6), max(sg * (W / 2 - 0.65), sg * 1.6), top + 5.6, top + 5.75, "steel_grey")
    for ty in (-1.6, 1.6): bx(-hl, hl, ty - 0.012, ty + 0.012, top + 5.5, top + 5.54, "dark")
    return end_part("b_rail", 1.6), dict(L=L, W=W, long=True, height=7.5, lens=42, look_z=3.0, hero_h=0.30)

# ------------------------------------------------------------------ L4 concrete girder bridge
def build_girder():
    span = 24.0; n = 2; L = span * n; W = 9.0; hl = L / 2; top = 4.4
    zf = lambda x: top - 0.0006 * (x * x - hl * hl) * -1 if False else top
    new_part()
    xs = xs_dense(-hl, hl, 1.0)
    deck_slab(xs, zf, W, 1.0, "asphalt", "concrete", "concrete")
    for sg in (-1, 1):
        bx(-hl, hl, sg * W / 2 - (0.0 if sg < 0 else 0.55) - 0.0 if False else (-W / 2 if sg < 0 else W / 2 - 0.55), (-W / 2 + 0.55 if sg < 0 else W / 2), top, top + 0.2, "slab")
        # hollow-box fascia: slightly proud lower beam
        bx(-hl, hl, (-W / 2 - 0.08 if sg < 0 else W / 2), (-W / 2 if sg < 0 else W / 2 + 0.08), top - 1.0, top - 0.3, "concrete")
    # piers: two columns + cap beam at the middle and abutments at the ends
    for x in (0.0,):
        for y in (-2.6, 2.6): bx(x - 0.7, x + 0.7, y - 0.45, y + 0.45, -1.5, top - 1.4, "concrete")
        bx(x - 1.0, x + 1.0, -W / 2 + 0.4, W / 2 - 0.4, top - 1.5, top - 1.0, "concrete")
    for sx in (-1, 1):
        bx(sx * hl - (0.9 if sx > 0 else 0), sx * hl + (0 if sx > 0 else 0.9) if False else sx * hl - (0.9 if sx > 0 else -0.9), -W / 2, W / 2, -1.5, top - 1.0, "concrete") if False else None
        qd(V(sx * hl, -W / 2, -1.5), V(sx * hl, W / 2, -1.5), V(sx * hl, W / 2, top - 1.0), V(sx * hl, -W / 2, top - 1.0), "concrete", (sx, 0, 0))
    tube_rail(xs, zf, W, 1.15, "steel_grey", post_every=2.0, rails=(0.35, 0.75, 1.1), off=0.1, bars=0.0, post=0.045)
    for x in (-hl + 8, hl - 8):
        for sg in (-1, 1): lamp(x, sg * (W / 2 - 0.35), top, 7.5, 1.7, sg)
    return end_part("b_girder", 1.2), dict(L=L, W=W, long=True, height=7.5, lens=42, look_z=1.5, hero_h=0.32)

# ------------------------------------------------------------------ L5 steel bowstring (tied arch) footbridge
def build_bowstring():
    L, W = 60.0, 6.0; hl = L / 2; top = 3.1; rise = 8.5
    zf = lambda x: top
    new_part()
    xs = xs_dense(-hl, hl, 1.0)
    deck_slab(xs, zf, W, 0.7, "planks", "steel_green", "steel_green")
    ay = W / 2 + 0.15
    arch = lambda x: top + 0.3 + rise * (1 - (x / hl) ** 2)
    xr = xs_dense(-hl + 1.0, hl - 1.0, 1.0)
    for sg in (-1, 1):
        y = sg * ay
        for a, b in zip(xr[:-1], xr[1:]):                      # arch rib (box)
            tbox(a, b, y - 0.2, y + 0.2, arch(a) - 0.3, arch(b) - 0.3, arch(a) + 0.3, arch(b) + 0.3, "steel_green")
        bx(-hl, hl, y - 0.22, y + 0.22, top - 0.55, top - 0.05, "steel_green")       # tie girder
        x = -hl + 3.0
        while x < hl - 2.0:                                     # hangers
            bx(x - 0.03, x + 0.03, y - 0.03, y + 0.03, top, arch(x) - 0.3, "steel_grey"); x += 3.0
    for x in xs_dense(-hl + 3.0, hl - 3.0, 3.0):                # cross beams / portal bracing at the crown
        if abs(x) < 3.5: bx(x - 0.08, x + 0.08, -ay, ay, arch(x) - 0.05, arch(x) + 0.25, "steel_green")
    tube_rail(xs, zf, W - 0.3, 1.1, "steel_grey", post_every=2.0, rails=(0.5, 1.05), off=0.05, post=0.035)
    for sx in (-1, 1):                                          # concrete abutments
        bx(sx * hl - (0 if sx > 0 else -0.0) - (3.0 if sx > 0 else 0), sx * hl + (3.0 if sx < 0 else 0), -W / 2 - 0.6, W / 2 + 0.6, -1.5, top, "concrete") if False else None
        x0, x1 = (hl - 3.0, hl) if sx > 0 else (-hl, -hl + 3.0)
        bx(x0, x1, -W / 2 - 0.4, W / 2 + 0.4, -1.5, top - 0.05, "concrete", bottom=False)
    return end_part("b_bowstring", 1.2), dict(L=L, W=W, long=True, height=12.5, lens=40, look_z=4.0, hero_h=0.28)

# ------------------------------------------------------------------ L6 cycle path ribbon on slender piers
def build_ribbon():
    span = 18.0; n = 3; L = span * n; W = 3.2; hl = L / 2; top = 2.7
    zf = lambda x: min(top, G + (hl - abs(x)) * 0.20)
    new_part()
    xs = xs_dense(-hl, hl, 0.75)
    deck_slab(xs, zf, W, 0.38, "asphalt", "concrete", "concrete")
    for k in range(1, n):
        x = -hl + span * k
        bx(x - 0.28, x + 0.28, -0.7, 0.7, -1.5, top - 0.65, "concrete")
        bx(x - 0.4, x + 0.4, -W / 2 + 0.2, W / 2 - 0.2, top - 0.65, top - 0.38, "concrete")
    for sx in (-1, 1):
        qd(V(sx * hl, -W / 2, zf(sx * hl) - 0.38), V(sx * hl, W / 2, zf(sx * hl) - 0.38), V(sx * hl, W / 2, zf(sx * hl)), V(sx * hl, -W / 2, zf(sx * hl)), "concrete", (sx, 0, 0))
    tube_rail(xs, zf, W, 1.2, "steel_grey", post_every=2.25, rails=(0.45, 1.2), off=0.06, bars=0.18, post=0.035)
    return end_part("b_ribbon", 1.0), dict(L=L, W=W, long=True, height=4.5, lens=45, look_z=1.2, hero_h=0.28)

for nm, fn in (("timber", build_timber), ("liberta", build_liberta), ("rail", build_rail), ("girder", build_girder), ("bowstring", build_bowstring), ("ribbon", build_ribbon)):
    if want(nm): reg(nm, *fn())

"""ACTV stop signs: one sheet (2048x2048, 4 columns x 32 rows) with every stop name as it appears on the yellow bands
(black on ACTV yellow), plus the instance list for the game with each stop's slot.
usage: make_signs.py converter_out/actv.txt assets/landmarks"""
import sys, re, os
from PIL import Image, ImageDraw, ImageFont
src, out = sys.argv[1], sys.argv[2]
rows = [l.rstrip("\n").split("\t") for l in open(src, encoding="utf-8") if l.strip()]
names = []
def clean(n):
    m = re.match(r'^(.*?)\s*"([A-Z0-9]+)"\s*$', n)
    n2, letter = (m.group(1), m.group(2)) if m else (n, "")
    n2 = n2.replace("(M.V.E.)", "").replace("F.te", "F.te").strip()
    return n2, letter
ABBR = [("Santa Maria Elisabetta", "S.M.E."), ("Santa Maria del Giglio", "S.M. del Giglio"), ("Piazzale", "P.le"),
        ("Fondamente", "F.te"), ("Fondamenta", "F.ta"), ("Santa ", "S."), ("Santo ", "S."), ("San ", "S."), ("Sant'", "S."),
        ("Giardini Biennale", "Giardini Biennale"), ("Ospedale", "Ospedale")]
def abbrev(n):
    """ACTV style: mixed case, saints and squares abbreviated so every name fits at the same size."""
    for a, b in ABBR: n = n.replace(a, b)
    n = re.sub(r"\s*\([A-Z0-9]+\)\s*$", "", n)
    return n.strip()
W, H, COLS, ROWS = 2048, 2048, 4, 32
sw, sh = W // COLS, H // ROWS
YEL = (242, 194, 0)          # ACTV yellow; the pontoon bands are baked with exactly this value
img = Image.new("RGB", (W, H), YEL)
d = ImageDraw.Draw(img)
bold = "/usr/share/fonts/truetype/liberation/LiberationSansNarrow-Bold.ttf"   # condensed grotesk, like the ACTV signs
NAME_W = int(sw * 0.8)            # u 0..0.8 of a slot = the name plate, u 0.86..0.99 = the letter square
LX0, LX1 = int(sw * 0.86), int(sw * 0.99)
slots = {}
for r in rows:
    key = r[0]
    if key in slots: continue
    k = len(slots); slots[key] = k
    if k >= COLS * ROWS: break
    name, letter = clean(key)
    name = abbrev(name)
    x0, y0 = (k % COLS) * sw, (k // COLS) * sh
    d.rectangle([x0, y0, x0 + NAME_W - 1, y0 + sh - 1], fill=YEL)
    size = 60
    while size > 14:
        f = ImageFont.truetype(bold, size)
        if d.textlength(name, font=f) < NAME_W - 24: break
        size -= 2
    tw = d.textlength(name, font=f)
    asc, desc = f.getmetrics()
    d.text((x0 + (NAME_W - tw) / 2, y0 + (sh - asc) / 2 + 2), name, font=f, fill=(18, 18, 16), stroke_width=1, stroke_fill=(18, 18, 16))
    # letter square: white plaque, black frame, the stop's letter (blank plaque if the stop has none)
    d.rectangle([x0 + LX0, y0, x0 + LX1, y0 + sh - 1], fill=(30, 30, 28))
    d.rectangle([x0 + LX0 + 3, y0 + 3, x0 + LX1 - 3, y0 + sh - 4], fill=(246, 246, 242))
    if letter:
        ls_ = 50
        while ls_ > 20 and d.textlength(letter, font=ImageFont.truetype(bold, ls_)) > LX1 - LX0 - 10: ls_ -= 2
        lf = ImageFont.truetype(bold, ls_); la, _ = lf.getmetrics()
        d.text((x0 + (LX0 + LX1) / 2 - d.textlength(letter, font=lf) / 2, y0 + (sh - la) / 2 + 2), letter, font=lf, fill=(18, 18, 16))
img.save(os.path.join(out, "actv_signs.png"), optimize=True)
with open(os.path.join(out, "actv.txt"), "w", encoding="utf-8") as fo:
    for r in rows:
        if r[0] in slots: fo.write("\t".join(r[:6] + [str(slots[r[0]])]) + "\n")
print(len(rows), "stops,", len(slots), "names")

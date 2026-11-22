"""Pack selected CC0 ambientCG materials into the game's texture layers.
Per layer writes:  c_XX.png  (RGB albedo, A = paintable mask: 1 where the surface is plaster that takes the building colour)
                   n_XX.jpg  (R,G = normal xy (OpenGL), B = roughness)
Usage: python3 pack_textures.py <ambientcg src dir> <out assets/tex dir>
"""
import sys, os, glob, json
from PIL import Image, ImageFilter, ImageStat
import numpy as np

SRC, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)
S = 512

# name, source, mode: 'tint' = whole surface paintable, 'peel' = plaster with exposed brick/stone (auto mask), 'raw' = keep colour
LAYERS = [
    ("plaster_smooth", "Plaster001", "tint"), ("plaster_rough", "Plaster003", "tint"), ("plaster_weathered", "Plaster007", "tint"),
    ("plaster_clean", "PaintedPlaster017", "tint"), ("plaster_blotchy", "Concrete036", "tint"), ("plaster_aged", "Concrete012", "tint"),
    ("peel_grey", "PaintedPlaster014", "peel"), ("peel_heavy", "PaintedPlaster016", "peel"), ("peel_green", "PaintedPlaster010", "peel"),
    ("peel_light", "PaintedPlaster006", "peel"), ("peel_fine", "PaintedPlaster015", "peel"),
    ("brick_old", "Bricks094", "raw"), ("brick_dark", "Bricks097", "raw"), ("brick_small", "Bricks085", "raw"), ("brick_red", "Bricks101", "raw"),
    ("istrian", "Marble012", "raw"), ("istrian_worn", "Concrete034", "raw"), ("ashlar", "Bricks108A", "raw"),
    ("roof_coppi", "RoofingTiles007", "raw"), ("roof_coppi_old", "RoofingTiles008", "raw"), ("roof_tile", "RoofingTiles006", "raw"), ("roof_flat", "RoofingTiles014A", "raw"),
    ("masegni", "PavingStones111", "raw"), ("masegni_herring", "PavingStones081", "raw"), ("campo_brick", "PavingStones122", "raw"), ("trachyte_dark", "PavingStones127", "raw"),
    ("planks_weathered", "Planks023A", "raw"), ("planks_old", "Planks020", "raw"), ("wood_pole", "Wood051", "raw"),
    ("grass", "Grass004", "raw"), ("ground", "Ground037", "raw"), ("lead", "Metal032", "raw"),
    ("shutter_wood", "Wood049", "tint"), ("rust", "Rust009", "raw"),
]

def load(folder, kind):
    f = glob.glob(os.path.join(SRC, folder, f"*_{kind}.jpg"))
    return Image.open(f[0]) if f else None

# raw layers whose source photo is far brighter/whiter than the Venetian material (sRGB-space gain)
GAIN = {"istrian": 0.80, "lead": 0.62, "masegni": 0.80, "masegni_herring": 0.82}

meta = []
for i, (name, folder, mode) in enumerate(LAYERS):
    col = load(folder, "Color").convert("RGB").resize((S, S), Image.LANCZOS)
    nrm = load(folder, "NormalGL"); rgh = load(folder, "Roughness"); ao = load(folder, "AmbientOcclusion"); disp = load(folder, "Displacement")
    c = np.asarray(col).astype(np.float32) / 255.0
    if ao is not None:
        a = np.asarray(ao.convert("L").resize((S, S), Image.LANCZOS)).astype(np.float32) / 255.0
        c = c * (0.55 + 0.45 * a[..., None])
    lum = c @ np.array([0.2126, 0.7152, 0.0722], np.float32)
    if mode == "tint":
        mask = np.ones((S, S), np.float32)
        # store neutral detail: luminance normalised to 0.5 average
        detail = np.clip(lum / max(lum.mean(), 1e-3) * 0.5, 0, 1)
        rgb = np.repeat(detail[..., None], 3, axis=2)
    elif mode == "peel":
        # plaster is the dominant, raised, low-saturation surface; brick/stone patches are recessed and warmer
        mx = c.max(2); mn = c.min(2); sat = (mx - mn) / np.maximum(mx, 1e-3)
        h = np.asarray(disp.convert("L").resize((S, S), Image.LANCZOS)).astype(np.float32) / 255.0 if disp is not None else 1 - sat
        score = (h - np.median(h)) * 2.0 - (sat - np.median(sat)) * 3.0
        mask = np.clip(0.5 + score * 4.0, 0, 1)
        mask = np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.2))).astype(np.float32) / 255.0
        pl = mask > 0.5
        mean_pl = lum[pl].mean() if pl.any() else lum.mean()
        detail = np.clip(lum / max(mean_pl, 1e-3) * 0.5, 0, 1)
        rgb = c * (1 - mask[..., None]) + np.repeat(detail[..., None], 3, axis=2) * mask[..., None]
    else:
        mask = np.zeros((S, S), np.float32); rgb = c * GAIN.get(name, 1.0)
    rgba = np.dstack([rgb, mask])
    Image.fromarray((np.clip(rgba, 0, 1) * 255).astype(np.uint8), "RGBA").save(os.path.join(OUT, f"c_{i:02d}.png"), optimize=True)
    n = np.asarray(nrm.convert("RGB").resize((S, S), Image.LANCZOS)) if nrm is not None else np.full((S, S, 3), (128, 128, 255), np.uint8)
    r = np.asarray(rgh.convert("L").resize((S, S), Image.LANCZOS)) if rgh is not None else np.full((S, S), 180, np.uint8)
    Image.fromarray(np.dstack([n[..., 0], n[..., 1], r]).astype(np.uint8), "RGB").save(os.path.join(OUT, f"n_{i:02d}.jpg"), quality=90)
    meta.append({"i": i, "name": name, "src": folder, "mode": mode, "mean": [float(x) for x in c.reshape(-1, 3).mean(0)]})
    print(i, name, folder, mode)
json.dump(meta, open(os.path.join(OUT, "layers.json"), "w"), indent=1)
print("packed", len(LAYERS), "layers")

"""Baked foam texture for the water shader (replaces ~9 procedural noise evaluations per foam pixel).
512x512, tileable. R: multi-octave lace (torn patches + bubble rafts), G: fine froth, B: large blotches.
Run: python3 tools/water/foam.py -> app/src/main/assets/water/foam.png"""
import numpy as np, os
from PIL import Image
N = 512
rng = np.random.default_rng(7)
def vnoise(period):
    """tileable value noise with `period` cells across the texture (smoothstep interpolation)"""
    g = rng.random((period, period))
    x = np.arange(N) * period / N
    i = np.floor(x).astype(int); f = x - i; f = f * f * (3 - 2 * f)
    i1 = (i + 1) % period
    a = g[i][:, i]; b = g[i][:, i1]; c = g[i1][:, i]; d = g[i1][:, i1]
    fy = f[:, None]; fx = f[None, :]
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
def fbm(p0, octaves):
    s = np.zeros((N, N)); a = 0.5; t = 0
    for o in range(octaves): s += a * vnoise(p0 * 2 ** o); t += a; a *= 0.5
    return s / t
lace = 0.45 * fbm(8, 4) + 0.30 * vnoise(21) + 0.15 * vnoise(56) + 0.10 * vnoise(128)
lace = (lace - lace.min()) / (lace.max() - lace.min())
froth = fbm(64, 3); froth = (froth - froth.min()) / (froth.max() - froth.min())
blot = fbm(4, 3); blot = (blot - blot.min()) / (blot.max() - blot.min())
img = np.stack([lace, froth, blot], -1)
out = os.path.join(os.path.dirname(__file__), '../../app/src/main/assets/water/foam.png')
Image.fromarray((img * 255).astype(np.uint8), 'RGB').save(out, optimize=True)
print(out, os.path.getsize(out))

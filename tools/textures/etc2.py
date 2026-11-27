"""ETC2 RGB8 texture compressor (ETC1-compatible individual/differential blocks), numpy only.
GPU-native compressed textures are 4 bits per pixel instead of 32: the APK grows a little, graphics memory drops 8x.
File format (.etc, big-endian): b"ETC2", int width, int height, int levels, then per mip level: int byte count, the
blocks (8 bytes each, row-major 4x4 blocks). Full mip chain down to 1x1 (box-filtered with PIL).
Use:  python3 tools/textures/etc2.py in.jpg out.etc        (or: --check in.jpg  -> PSNR of a round trip)"""
import sys, struct
import numpy as np
from PIL import Image

TABLES = np.array([[2, 8], [5, 17], [9, 29], [13, 42], [18, 60], [24, 80], [33, 106], [47, 183]], np.int32)
# pixel index (msb, lsb) -> modifier: 0 -> +a, 1 -> +b, 2 -> -a, 3 -> -b
MODS = np.stack([TABLES[:, 0], TABLES[:, 1], -TABLES[:, 0], -TABLES[:, 1]], 1)        # (8 tables, 4)

def _sub_masks():
    """pixel membership of subblock 0 for flip 0 (left 2 columns) and flip 1 (top 2 rows); pixels as [y][x]"""
    m = np.zeros((2, 4, 4), bool)
    m[0, :, :2] = True
    m[1, :2, :] = True
    return m
SUB0 = _sub_masks()

def encode_blocks(blk):
    """blk: (N, 4, 4, 3) uint8/int [y][x][c] -> (N,) uint64 ETC1 blocks"""
    N = blk.shape[0]
    px = blk.reshape(N, 16, 3).astype(np.int32)
    best_err = np.full(N, np.iinfo(np.int64).max, np.int64); best = np.zeros(N, np.uint64)
    for flip in (0, 1):
        m0 = SUB0[flip].reshape(16)
        avg = np.stack([px[:, m0].mean(1), px[:, ~m0].mean(1)], 1)          # (N, 2 subs, 3)
        # differential candidate: 5-bit bases, 3-bit signed delta
        q5 = np.clip(np.round(avg * 31 / 255), 0, 31).astype(np.int32)
        d = q5[:, 1] - q5[:, 0]
        diff_ok = np.all((d >= -4) & (d <= 3), 1)
        c5 = np.stack([q5[:, 0], q5[:, 0] + np.clip(d, -4, 3)], 1)
        base_d = (c5 << 3) | (c5 >> 2)
        q4 = np.clip(np.round(avg * 15 / 255), 0, 15).astype(np.int32)
        base_i = (q4 << 4) | q4
        base = np.where(diff_ok[:, None, None], base_d, base_i)               # (N, 2, 3)
        # per subblock, per table: best modifier for each pixel
        tot = np.zeros(N, np.int64); tabs = np.zeros((N, 2), np.int32); idx = np.zeros((N, 16), np.int32)
        for s in (0, 1):
            ms = m0 if s == 0 else ~m0
            p = px[:, ms]                                                     # (N, 8, 3)
            cand = np.clip(base[:, s][:, None, None, :] + MODS[None, :, :, None], 0, 255)   # (N, 8t, 4m, 3)
            err = ((p[:, None, :, None, :] - cand[:, :, None, :, :]) ** 2).sum(-1)       # (N, 8t, 8px, 4m)
            mi = err.argmin(-1); me = err.min(-1).sum(-1)                     # (N, 8t, 8px), (N, 8t)
            t = me.argmin(1); tabs[:, s] = t; tot += me[np.arange(N), t]
            idx[:, ms] = mi[np.arange(N), t]
        better = tot < best_err
        if not better.any(): continue
        # pack
        b = np.zeros(N, np.uint64)
        R, G, B = 0, 1, 2
        dd = (c5[:, 1] - c5[:, 0]) & 7
        hi_d = ((c5[:, 0, R].astype(np.uint64) << 59) | (dd[:, R].astype(np.uint64) << 56) |
                (c5[:, 0, G].astype(np.uint64) << 51) | (dd[:, G].astype(np.uint64) << 48) |
                (c5[:, 0, B].astype(np.uint64) << 43) | (dd[:, B].astype(np.uint64) << 40) | (np.uint64(1) << 33))
        hi_i = ((q4[:, 0, R].astype(np.uint64) << 60) | (q4[:, 1, R].astype(np.uint64) << 56) |
                (q4[:, 0, G].astype(np.uint64) << 52) | (q4[:, 1, G].astype(np.uint64) << 48) |
                (q4[:, 0, B].astype(np.uint64) << 44) | (q4[:, 1, B].astype(np.uint64) << 40))
        b = np.where(diff_ok, hi_d, hi_i)
        b |= (tabs[:, 0].astype(np.uint64) << 37) | (tabs[:, 1].astype(np.uint64) << 34) | np.uint64(flip << 32)
        # indices: pixel (x, y) -> bit i = x*4 + y; msb plane at 16+i, lsb plane at i
        for y in range(4):
            for x in range(4):
                v = idx[:, y * 4 + x].astype(np.uint64); i = x * 4 + y
                b |= ((v >> np.uint64(1)) & np.uint64(1)) << np.uint64(16 + i)
                b |= (v & np.uint64(1)) << np.uint64(i)
        best = np.where(better, b, best); best_err = np.where(better, tot, best_err)
    return best

def decode_blocks(blocks):
    """inverse, for checking: (N,) uint64 -> (N, 4, 4, 3) uint8"""
    N = blocks.shape[0]; out = np.zeros((N, 4, 4, 3), np.int32)
    for n in range(N):
        v = int(blocks[n]); diff = (v >> 33) & 1; flip = (v >> 32) & 1
        if diff:
            c = []
            for sh in (59, 51, 43):
                a = (v >> sh) & 31; dl = (v >> (sh - 3)) & 7; dl = dl - 8 if dl >= 4 else dl
                c.append((a, a + dl))
            bases = [[(ch[k] << 3) | (ch[k] >> 2) for ch in c] for k in (0, 1)]
        else:
            bases = [[(((v >> sh) & 15) * 17) for sh in (60, 52, 44)], [(((v >> sh) & 15) * 17) for sh in (56, 48, 40)]]
        t = [(v >> 37) & 7, (v >> 34) & 7]
        for x in range(4):
            for y in range(4):
                i = x * 4 + y; ix = (((v >> (16 + i)) & 1) << 1) | ((v >> i) & 1)
                s = 0 if (x < 2 if flip == 0 else y < 2) else 1
                out[n, y, x] = np.clip(np.array(bases[s]) + MODS[t[s], ix], 0, 255)
    return out.astype(np.uint8)

def to_blocks(img):
    """(H, W, 3) -> (nby*nbx, 4, 4, 3), padding by edge replication"""
    h, w = img.shape[:2]; H = (h + 3) // 4 * 4; W = (w + 3) // 4 * 4
    if H != h or W != w: img = np.pad(img, ((0, H - h), (0, W - w), (0, 0)), mode='edge')
    return img.reshape(H // 4, 4, W // 4, 4, 3).transpose(0, 2, 1, 3, 4).reshape(-1, 4, 4, 3)

def encode_image(img):
    blk = to_blocks(img); out = np.empty(len(blk), np.uint64)
    for i in range(0, len(blk), 4096): out[i:i + 4096] = encode_blocks(blk[i:i + 4096])
    return out.astype('>u8').tobytes()

def mips(im):
    levels = [im]
    while im.size != (1, 1):
        im = im.resize((max(1, im.size[0] // 2), max(1, im.size[1] // 2)), Image.BOX); levels.append(im)
    return levels

def write_etc(src, dst, mipmapped=True):
    im = Image.open(src).convert('RGB')
    lv = mips(im) if mipmapped else [im]
    with open(dst, 'wb') as f:
        f.write(b'ETC2' + struct.pack('>iii', im.size[0], im.size[1], len(lv)))
        for l in lv:
            data = encode_image(np.asarray(l)); f.write(struct.pack('>i', len(data))); f.write(data)

if __name__ == '__main__':
    if sys.argv[1] == '--check':
        im = np.asarray(Image.open(sys.argv[2]).convert('RGB'))[:128, :128]
        blk = to_blocks(im); enc = encode_blocks(blk); dec = decode_blocks(enc)
        a = blk.astype(float); mse = ((a - dec) ** 2).mean(); print('PSNR %.1f dB' % (10 * np.log10(255 ** 2 / mse)))
        Image.fromarray(dec.reshape(32, 32, 4, 4, 3).transpose(0, 2, 1, 3, 4).reshape(128, 128, 3)).save('/tmp/etc_check.png')
    else:
        write_etc(sys.argv[1], sys.argv[2], mipmapped='--nomips' not in sys.argv)

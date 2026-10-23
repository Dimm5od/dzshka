"""Фотореалистичный фон «космос»: туманности (фрактальный шум с искривлением), звёзды с ореолами,
Млечный путь, спиральная галактика, газовый гигант с кольцами, луна."""
import numpy as np, sys, os
from PIL import Image, ImageFilter
from scipy.ndimage import map_coordinates, gaussian_filter

W, H = 1080, 2340
rng = np.random.default_rng(int(sys.argv[2]) if len(sys.argv) > 2 else 42)

def noise(h, w, scale, seed):
    r = np.random.default_rng(seed)
    gh, gw = max(2, int(h / scale)) + 2, max(2, int(w / scale)) + 2
    g = r.random((gh, gw)).astype(np.float32)
    im = Image.fromarray(g).resize((w + int(2 * scale), h + int(2 * scale)), Image.BICUBIC)
    a = np.asarray(im)[int(scale):int(scale) + h, int(scale):int(scale) + w]
    return a

def fbm(h, w, base, octaves, seed, gain=0.5):
    out = np.zeros((h, w), np.float32); amp = 1.0; tot = 0
    for o in range(octaves):
        out += amp * noise(h, w, base / (2 ** o), seed + o * 17); tot += amp; amp *= gain
    return out / tot

def warp(f, h, w, amt, seed):
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    dx = (fbm(h, w, 420, 4, seed) - 0.5) * amt
    dy = (fbm(h, w, 420, 4, seed + 99) - 0.5) * amt
    return map_coordinates(f, [np.clip(yy + dy, 0, h - 1), np.clip(xx + dx, 0, w - 1)], order=1)

img = np.zeros((H, W, 3), np.float32)
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)

# --- фон: очень тёмный, чуть синее вверху
img += np.stack([0.010 + 0.008 * yy / H, np.full_like(yy, 0.010), 0.030 - 0.012 * yy / H], -1)

# --- Млечный путь: диагональная полоса пыли и звёздного света
band_d = ((xx - W * 0.5) * 0.55 + (yy - H * 0.42) * -0.83)
band = np.exp(-(band_d / (W * 0.33)) ** 2)
n1 = warp(fbm(H, W, 380, 7, 1), H, W, 260, 5)
n2 = warp(fbm(H, W, 220, 6, 2), H, W, 180, 6)
dust = np.clip((fbm(H, W, 160, 6, 3) - 0.42) * 3.0, 0, 1)     # тёмные пылевые прожилки
mw = band * np.clip((n1 - 0.35) * 2.2, 0, 1) * (1 - 0.75 * dust)
img += mw[..., None] * np.array([0.30, 0.26, 0.34]) * 0.55

# --- туманности: фиолетово-малиновая сверху слева, сине-бирюзовая справа
def nebula(cx, cy, rad, cols, seed, strength):
    d = np.sqrt(((xx - cx) / rad) ** 2 + ((yy - cy) / (rad * 1.25)) ** 2)
    mask = np.exp(-d ** 2 * 1.6)
    f = warp(fbm(H, W, 300, 7, seed), H, W, 320, seed + 1)
    g = warp(fbm(H, W, 140, 6, seed + 2), H, W, 140, seed + 3)
    dens = np.clip((f - 0.38) * 2.4, 0, 1) ** 1.6 * mask
    fil = np.clip((g - 0.5) * 4, 0, 1) * mask          # светящиеся нити
    c = np.zeros((H, W, 3), np.float32)
    mix = np.clip(g * 1.4 - 0.2, 0, 1)[..., None]
    c += dens[..., None] * (np.array(cols[0]) * (1 - mix) + np.array(cols[1]) * mix)
    c += fil[..., None] * np.array(cols[2]) * 0.6
    return c * strength
img += nebula(W * 0.18, H * 0.20, W * 0.75, [(0.55, 0.10, 0.55), (0.85, 0.22, 0.40), (1.0, 0.55, 0.75)], 10, 0.55)
img += nebula(W * 0.92, H * 0.55, W * 0.80, [(0.08, 0.20, 0.60), (0.10, 0.50, 0.65), (0.45, 0.80, 1.0)], 20, 0.45)
img += nebula(W * 0.30, H * 0.95, W * 0.60, [(0.40, 0.08, 0.30), (0.20, 0.10, 0.50), (0.9, 0.5, 0.8)], 30, 0.30)
img *= (1 - 0.5 * dust[..., None] * band[..., None])

# --- звёзды: тысячи мелких, плотнее в полосе Млечного пути; цвет по температуре
def star_col(t):
    # t 0..1: оранжевые → белые → голубые
    return np.stack([1.0 - 0.35 * np.clip(t - 0.55, 0, 1) * 2, 0.80 + 0.2 * np.clip(1 - abs(t - 0.5) * 2, 0, 1), 0.55 + 0.45 * t], -1)
stars = np.zeros((H, W, 3), np.float32)
Nst = 9000
sx = rng.random(Nst) * W; sy = rng.random(Nst) * H
bd = np.exp(-((((sx - W * 0.5) * 0.55 + (sy - H * 0.42) * -0.83) / (W * 0.33)) ** 2))
keep = rng.random(Nst) < (0.35 + 0.65 * bd)
sx, sy = sx[keep], sy[keep]
br = rng.pareto(2.2, sx.size) * 0.18 + 0.05
br = np.clip(br, 0, 3.0)
tc = rng.beta(2, 2, sx.size)
cols = star_col(tc)
ix = np.clip(sx.astype(int), 0, W - 1); iy = np.clip(sy.astype(int), 0, H - 1)
np.add.at(stars, (iy, ix), cols * br[:, None])
stars = gaussian_filter(stars, (0.7, 0.7, 0)) * 4.0 + gaussian_filter(stars, (2.5, 2.5, 0)) * 1.2
# самые яркие — с лучами (дифракция)
bright = np.argsort(br)[-7:]
spike = np.zeros((H, W, 3), np.float32)
for i in bright:
    x0, y0 = int(sx[i]), int(sy[i]); L = int(18 + 34 * min(br[i], 3) / 3)
    for d in range(-L, L + 1):
        a = (1 - abs(d) / L) ** 3 * br[i] * 0.35
        if 0 <= x0 + d < W: spike[y0, x0 + d] += cols[i] * a
        if 0 <= y0 + d < H: spike[y0 + d, x0] += cols[i] * a
spike = gaussian_filter(spike, (0.8, 0.8, 0))
img += stars + spike

# --- спиральная галактика (наклонённая), вверху справа
def galaxy(cx, cy, R, tilt, rot, seed):
    c, s = np.cos(rot), np.sin(rot)
    X = (xx - cx); Y = (yy - cy)
    u = (X * c + Y * s); v = (-X * s + Y * c) / tilt
    r = np.sqrt(u ** 2 + v ** 2) / R
    th = np.arctan2(v, u)
    arms = 0.5 + 0.5 * np.cos(2 * (th - np.log(r + 1e-3) * 2.6))
    knots = fbm(H, W, 22, 4, seed)
    disk = np.exp(-r * 3.2) * (0.25 + 0.75 * arms ** 3 * (0.6 + 0.8 * knots))
    bulge = np.exp(-(r / 0.13) ** 2) * 1.6
    m = (r < 1.4)
    col = disk[..., None] * np.array([0.62, 0.70, 1.0]) + bulge[..., None] * np.array([1.0, 0.88, 0.65])
    return col * m[..., None] * 0.9
img += galaxy(W * 0.80, H * 0.115, W * 0.19, 0.42, -0.45, 70)
# далёкие галактики-пятнышки
for k in range(9):
    gx, gy = rng.random() * W, rng.random() * H
    a = rng.uniform(6, 16); b = a * rng.uniform(0.3, 0.8); ang = rng.uniform(0, np.pi)
    c, s = np.cos(ang), np.sin(ang)
    u = ((xx - gx) * c + (yy - gy) * s) / a; v = (-(xx - gx) * s + (yy - gy) * c) / b
    img += (np.exp(-(u ** 2 + v ** 2) * 1.5) * 0.35)[..., None] * np.array([0.9, 0.85, 1.0])

# --- газовый гигант с кольцами (внизу справа, частично за краем)
pcx, pcy, pr = W * 0.86, H * 0.80, W * 0.30
light = np.array([-0.80, -0.42, 0.42]); light /= np.linalg.norm(light)
dx = (xx - pcx) / pr; dy = (yy - pcy) / pr
rr2 = dx ** 2 + dy ** 2
inside = rr2 < 1
dz = np.sqrt(np.clip(1 - rr2, 0, 1))
# наклон оси планеты
tiltp = -0.38
lat = (dy * np.cos(tiltp) - dx * np.sin(tiltp))
turb = warp(fbm(H, W, 60, 6, 80), H, W, 90, 81)
latw = lat + (turb - 0.5) * 0.10
bands = 0.5 + 0.5 * np.sin(latw * 34) * 0.7 + 0.5 * np.sin(latw * 11 + 1.3) * 0.3 + (fbm(H, W, 18, 4, 82) - 0.5) * 0.18
belt = np.clip(np.sin(latw * 9 + 0.6), 0, 1) ** 2
pc = np.stack([0.86 + 0.12 * bands - 0.25 * belt, 0.66 + 0.20 * bands - 0.32 * belt, 0.46 + 0.20 * bands - 0.30 * belt], -1)
# большое пятно
spot = np.exp(-(((dx - 0.25) / 0.16) ** 2 + ((lat - 0.28) / 0.07) ** 2))
pc = pc * (1 - spot[..., None] * 0.5) + spot[..., None] * np.array([0.85, 0.35, 0.18]) * 0.5
lamb = np.clip(dx * light[0] + dy * light[1] + dz * light[2], 0, 1)
limb = dz ** 0.35
shade = (0.015 + 0.985 * lamb ** 1.1) * limb
planet = pc * shade[..., None] * 1.05
# атмосферный ободок на освещённой стороне
rim = np.exp(-((np.sqrt(rr2) - 1.0) / 0.025) ** 2) * np.clip(-(dx * 0.62 + dy * 0.52) + 0.2, 0, 1)
# кольца: эллипс в плоскости экватора, видим под углом
ring_tilt = 0.22
cr, sr = np.cos(tiltp), np.sin(tiltp)
ux = dx * cr + dy * sr; uy = (-dx * sr + dy * cr) / ring_tilt
rd = np.sqrt(ux ** 2 + uy ** 2)
ring_prof = np.zeros_like(rd)
for a0, a1, dens in [(1.25, 1.45, 0.55), (1.47, 1.78, 0.95), (1.84, 1.98, 0.45), (2.0, 2.12, 0.25)]:
    ring_prof += dens * np.clip((rd - a0) / 0.02, 0, 1) * np.clip((a1 - rd) / 0.02, 0, 1)
ring_prof *= 0.8 + 0.2 * np.sin(rd * 160)
front = uy > 0
ring_col = np.array([0.85, 0.74, 0.58])
# тень планеты на кольце (сзади-справа от света)
sh_ring = np.clip(((ux * 0.62 + uy * ring_tilt * 0.52) > 0.35) & (np.abs(-ux * 0.52 + uy * ring_tilt * 0.62) < 1.0), 0, 1)
ring_l = ring_prof * (1 - 0.85 * sh_ring)
back_ring = ring_l * (~front) * (~inside)
front_ring = ring_l * front
alpha_p = np.clip((1 - np.sqrt(rr2)) / 0.004, 0, 1)
img = img * (1 - alpha_p[..., None]) + planet * alpha_p[..., None]
img += back_ring[..., None] * ring_col * 0.55 * (1 - alpha_p[..., None])
img = img * (1 - front_ring[..., None] * 0.65) + front_ring[..., None] * ring_col * 0.6
img += rim[..., None] * np.array([1.0, 0.7, 0.45]) * 0.8

# --- луна слева
mcx, mcy, mr = W * 0.10, H * 0.62, W * 0.045
mdx = (xx - mcx) / mr; mdy = (yy - mcy) / mr; m2 = mdx ** 2 + mdy ** 2
mz = np.sqrt(np.clip(1 - m2, 0, 1))
crat = fbm(H, W, 6, 4, 90)
mlamb = np.clip(mdx * light[0] + mdy * light[1] + mz * light[2], 0, 1)
moon = (0.55 + 0.35 * crat)[..., None] * np.array([0.82, 0.78, 0.74]) * (0.02 + 0.98 * mlamb)[..., None]
ma = np.clip((1 - np.sqrt(m2)) / 0.03, 0, 1)
img = img * (1 - ma[..., None]) + moon * ma[..., None]

# --- свечение и тонмаппинг
glow = gaussian_filter(np.clip(img - 0.6, 0, None), (14, 14, 0))
img += glow * 0.6
out = 1 - np.exp(-img * 1.35)
out = np.clip(out, 0, 1) ** 0.95
Image.fromarray((out * 255).astype(np.uint8)).save(sys.argv[1], "WEBP", quality=88, method=6)
print("ok", os.path.getsize(sys.argv[1]))

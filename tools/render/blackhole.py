"""
Чёрная дыра Шварцшильда: трассировка лучей света в искривлённом пространстве (как для «Интерстеллара»).
Единицы G=M=c=1, горизонт r=2. Тонкий аккреционный диск в плоскости y=0, r от R_IN до R_OUT.
Ускорение фотона: a = -1.5 h^2 r_vec / r^5 (h = |r x v|) — точное для нулевых геодезических в «декартовой» форме.
"""
import numpy as np, sys, time
from PIL import Image, ImageFilter

def render(W, H, el_deg=3.2, roll_deg=-7.0, dist=55.0, half_w=17.0, r_in=2.6, r_out=13.0,
           palette="warm", phase=0.0, steps=900, seed=3, cx=0.5, cy=0.5):
    t0 = time.time()
    el = np.radians(el_deg); roll = np.radians(roll_deg)
    cam = np.array([0.0, np.sin(el), -np.cos(el)]) * dist
    fwd = -cam / np.linalg.norm(cam)
    up0 = np.array([0.0, 1.0, 0.0])
    right = np.cross(up0, fwd); right /= np.linalg.norm(right)
    up = np.cross(fwd, right)
    # крен (наклон картинки)
    r2 = np.cos(roll) * right + np.sin(roll) * up
    u2 = -np.sin(roll) * right + np.cos(roll) * up
    right, up = r2, u2
    aspect = H / W
    half_h = half_w * aspect
    xs = (np.arange(W) + 0.5) / W
    ys = (np.arange(H) + 0.5) / H
    X, Y = np.meshgrid((xs - cx) * 2 * half_w, (cy - ys) * 2 * half_h)
    N = W * H
    # направление луча: перспектива с «экраном» на расстоянии dist
    d = fwd[None, :] * dist + X.reshape(-1, 1) * right[None, :] + Y.reshape(-1, 1) * up[None, :]
    d /= np.linalg.norm(d, axis=1, keepdims=True)
    p = np.repeat(cam[None, :], N, axis=0)
    v = d.copy()
    h2 = np.sum(np.cross(p, v) ** 2, axis=1)

    col = np.zeros((N, 3))
    trans = np.ones(N)          # сколько света ещё проходит (диск полупрозрачный)
    alive = np.ones(N, bool)
    shadow = np.zeros(N, bool)
    rng = np.random.default_rng(seed)
    # турбулентность диска: набор волн по радиусу с угловыми искажениями
    K = 28
    fr = np.exp(rng.uniform(np.log(4.0), np.log(60.0), K)); ph = rng.uniform(0, 2 * np.pi, K)
    am = 1.0 / np.sqrt(fr) * rng.uniform(0.6, 1.4, K); mm = rng.integers(1, 6, K); pw = rng.uniform(0, 2 * np.pi, K)

    def disk_color(r, phi, dvec, pos):
        # профиль яркости (тонкий диск): ярко у внутреннего края, плавно гаснет наружу
        x = r_in / r
        prof = x ** 3.0 * np.clip(1 - np.sqrt(x) * 0.5, 0, 1) * 1.6
        lr = np.log(r)
        tex = np.zeros_like(r)
        for k in range(K):
            tex += am[k] * np.sin(fr[k] * lr * 3 + ph[k] + 0.8 * np.sin(mm[k] * (phi + phase) + pw[k]))
        tex = 0.70 + 0.30 * np.tanh(tex / (0.9 * np.sqrt((am ** 2).sum())))
        # Доплер: диск крутится против часовой (вид сверху); та сторона, что летит к нам, ярче
        beta = np.sqrt(1.0 / np.maximum(r - 1.0, 0.5)) * 0.85
        vdisk = np.stack([-np.sin(phi), np.zeros_like(phi), np.cos(phi)], 1) * beta[:, None]
        e = -dvec / np.linalg.norm(dvec, axis=1, keepdims=True)
        gamma = 1 / np.sqrt(1 - beta ** 2)
        D = 1 / (gamma * (1 - np.sum(vdisk * e, 1)))
        grav = np.sqrt(np.clip(1 - 2.0 / r, 0.05, 1))
        I = prof * tex * (D * grav) ** 1.8
        # цвет по «температуре»: чем ярче, тем белее (красный → оранжевый → бело-жёлтый)
        L = np.clip(I, 0, None) * 2.4
        t = np.clip(L / 2.0, 0, 1)
        if palette == "warm":
            tint = np.stack([np.ones_like(t), 0.42 + 0.56 * t ** 0.8, 0.12 + 0.86 * t ** 1.3], 1)
        else:  # холодный — для режима Студии
            tint = np.stack([0.10 + 0.75 * t ** 1.6, 0.45 + 0.50 * t, np.ones_like(t)], 1)
        c = tint * L[:, None]
        a = np.clip(0.55 + 0.45 * tex, 0, 1) * np.clip((r_out - r) / 3.0, 0, 1) * np.clip((r - r_in) / 0.25, 0, 1)
        return c, a

    for i in range(steps):
        idx = np.nonzero(alive)[0]
        if idx.size == 0: break
        pp = p[idx]; vv = v[idx]; hh = h2[idx]
        r = np.linalg.norm(pp, axis=1)
        dt = np.clip(0.05 * (r - 1.6) ** 1.2, 0.01, 2.5)
        def acc(x):
            rr = np.linalg.norm(x, axis=1)
            return -1.5 * hh[:, None] * x / rr[:, None] ** 5
        # RK4
        k1v = acc(pp); k1x = vv
        k2v = acc(pp + 0.5 * dt[:, None] * k1x); k2x = vv + 0.5 * dt[:, None] * k1v
        k3v = acc(pp + 0.5 * dt[:, None] * k2x); k3x = vv + 0.5 * dt[:, None] * k2v
        k4v = acc(pp + dt[:, None] * k3x); k4x = vv + dt[:, None] * k3v
        np_ = pp + dt[:, None] / 6 * (k1x + 2 * k2x + 2 * k3x + k4x)
        nv = vv + dt[:, None] / 6 * (k1v + 2 * k2v + 2 * k3v + k4v)
        # пересечение плоскости диска
        cross = (pp[:, 1] * np_[:, 1]) < 0
        if cross.any():
            ci = np.nonzero(cross)[0]
            t = pp[ci, 1] / (pp[ci, 1] - np_[ci, 1])
            hit = pp[ci] + t[:, None] * (np_[ci] - pp[ci])
            rh = np.linalg.norm(hit, axis=1)
            ok = (rh > r_in) & (rh < r_out)
            if ok.any():
                cj = ci[ok]; hp = hit[ok]; rr = rh[ok]
                phi = np.arctan2(hp[:, 2], hp[:, 0])
                c, a = disk_color(rr, phi, nv[cj], hp)
                g = idx[cj]
                col[g] += trans[g, None] * c * a[:, None]
                trans[g] *= (1 - a * 0.92)
        p[idx] = np_; v[idx] = nv
        rn = np.linalg.norm(np_, axis=1)
        shadow[idx[rn < 2.02]] = True
        dead = (rn < 2.02) | ((rn > dist * 1.3) & (np.sum(np_ * nv, 1) > 0)) | (trans[idx] < 0.02)
        alive[idx[dead]] = False
    print(f"trace {W}x{H}: {time.time()-t0:.1f}s, steps={i}", file=sys.stderr)
    render.shadow = shadow.reshape(H, W)
    return col.reshape(H, W, 3)

def tonemap(img, exposure=1.0):
    x = img * exposure
    return 1 - np.exp(-x)   # мягкое насыщение — как плёнка

def bloom(rgb, strength=0.6, radii=(2, 6, 16)):
    im = Image.fromarray((np.clip(rgb, 0, 1) * 255).astype(np.uint8))
    acc = np.zeros_like(rgb)
    for rad in radii:
        b = np.asarray(im.filter(ImageFilter.GaussianBlur(rad)), dtype=np.float32) / 255
        acc += b
    return np.clip(rgb + strength * acc / len(radii), 0, 1)

if __name__ == "__main__":
    W, H = int(sys.argv[1]), int(sys.argv[2])
    img = render(W, H, steps=int(sys.argv[3]) if len(sys.argv) > 3 else 900)
    out = bloom(tonemap(img, 4.5), 1.0)
    Image.fromarray((out * 255).astype(np.uint8)).save(sys.argv[4] if len(sys.argv) > 4 else "/tmp/claude-0/-home-claude/9a07046f-daec-55c9-b2e6-53b71e017710/scratchpad/bh_test.png")

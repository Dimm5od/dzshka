import numpy as np, sys, os
from PIL import Image
import blackhole as bh
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
cache = "/home/claude/render/cache"; os.makedirs(cache, exist_ok=True)
W, H = 1000, 500
for pal in ["warm", "cold"]:
    for k in range(6):
        cf = f"{cache}/{pal}_{k}.npz"
        if os.path.exists(cf):
            z = np.load(cf); img, sh = z["img"], z["sh"]
        else:
            img = bh.render(W, H, palette=pal, phase=k * np.pi / 3, steps=2000); sh = bh.render.shadow
            np.savez_compressed(cf, img=img, sh=sh)
        rgb = bh.bloom(bh.tonemap(img, 4.5), 1.0)
        # свет поверх пустоты: альфа = яркость, цвет — «чистый» (без серой каймы на светлом фоне)
        a = np.clip(rgb.max(2), 0, 1)
        col = rgb / np.maximum(a, 1e-4)[..., None]
        a = np.clip(a * 1.15, 0, 1)
        col[sh] = rgb[sh]; a[sh] = 1.0   # перед шаром диск виден, сам шар — чёрный и непрозрачный
        rgba = np.dstack([np.clip(col, 0, 1), a])
        im = Image.fromarray((rgba * 255).astype(np.uint8), "RGBA").resize((600, 300), Image.LANCZOS)
        name = f"bh_{pal}_{k}.webp"
        im.save(os.path.join(out, name), "WEBP", quality=90, method=6)
        print(name, os.path.getsize(os.path.join(out, name)), flush=True)

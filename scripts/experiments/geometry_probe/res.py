"""Input-resolution sweep for DA V2 Small on DIODE (every 3rd frame) and the Cityscapes curbs."""
import glob, json, os, sys
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe import DaV2Small, PgDepth, diode, eval_frame, rays, gt_floor_plane

R = os.path.join(os.environ.get("GEOPROBE_ROOT", os.path.expanduser("~/geoprobe")), "models")
models = []
for tag, p in [("518x686_wi8", f"{R}/dav2s_wi8.tflite"), ("392x518", f"{R}/dav2s_392x518.tflite"),
               ("266x350", f"{R}/dav2s_266x350.tflite")]:
    m = DaV2Small(p); m.name = tag; models.append(m)
pg = PgDepth(); models.append(pg)

out = {}
for split in ("indoors", "outdoor"):
    rows = {m.name: [] for m in models}; times = {m.name: [] for m in models}
    for n, (name, rgb, K, D, M) in enumerate(diode(split)):
        if n % 3: continue
        u_, v_, r_ = rays(K, *D.shape, 8)
        Ds = D[4::8, 4::8]; vm_ = M[4::8, 4::8] & (Ds > 0.2) & (Ds < 30)
        low_ = vm_ & (v_ > 0.55 * D.shape[0])
        gp = gt_floor_plane((r_ * Ds[..., None])[low_])
        if gp is None: continue
        for m in models:
            disp, dt = m(rgb); times[m.name].append(dt)
            o = eval_frame(disp, K, D, M, stride=8, gt_plane=gp, imu_noise_deg=(0, 2))
            if o: o["name"] = name; rows[m.name].append(o)
    out[split] = {"rows": rows, "times": times}
    print(split, "done", flush=True)
json.dump(out, open(os.path.join(os.environ.get("GEOPROBE_ROOT", os.path.expanduser("~/geoprobe")), "out_res.json"), "w"), default=float)

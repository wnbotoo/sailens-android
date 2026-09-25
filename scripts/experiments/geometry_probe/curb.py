"""Curb (step) test on Cityscapes8: road plane vs sidewalk height, camera height 1.22 m known.

Road = labelId 7, sidewalk = 8, ego vehicle = 1. Gravity from the published camera pitch
(0.038 rad, both signs tried since the sign convention is the unknown here).
"""
import glob, json, os, sys
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe import PgDepth, DaV2Small, rays, ransac_lin

K = np.array([[2262.52, 0, 1096.98], [0, 2265.30, 513.14], [0, 0, 1]], np.float32)
H_TRUE = 1.22
models = [PgDepth(), DaV2Small()]
res = {m.name: [] for m in models}
for f in sorted(glob.glob(os.path.join(os.environ.get("CITYSCAPES8", os.path.expanduser("~/AIModels/datasets/cityscapes8")), "images/*/*.png"))):
    rgb = cv2.cvtColor(cv2.imread(f), cv2.COLOR_BGR2RGB)
    lab = cv2.imread(f.replace("/images/", "/masks/"), cv2.IMREAD_UNCHANGED)
    Hh, Ww = lab.shape
    u, v, r = rays(K, Hh, Ww, 8)
    L = lab[4::8, 4::8]
    for m in models:
        disp, _ = m(rgb); d = disp[4::8, 4::8]; ok = np.isfinite(d)
        road = ok & (L == 7) & (v > 0.5 * Hh); side = ok & (L == 8) & (v > 0.45 * Hh)
        row = {"img": os.path.basename(f), "road_px": int(road.sum()), "side_px": int(side.sum())}
        for sign in (+1, -1):
            p = sign * 0.038
            for h_ass in (H_TRUE, 1.3):
                g = np.array([0, np.cos(p), np.sin(p)])
                q = r @ g
                if road.sum() < 100: continue
                fit = ransac_lin(np.stack([q[road], np.ones(road.sum())], 1), d[road], 0.04)
                if fit is None: continue
                (A, t), inl = fit
                s = A * h_ass
                Z = s / np.maximum(d - t, 1e-6)
                height = h_ass - Z * q
                key = f"pitch{'+' if sign > 0 else '-'}_h{h_ass}"
                row[key + "_road_inlier"] = float(inl.mean())
                near_side = side & (Z < 15)
                if near_side.sum() > 30:
                    row[key + "_sidewalk_h_med"] = float(np.median(height[near_side]))
                row[key + "_road_h_mad"] = float(np.median(np.abs(height[road & (Z < 15)])))
                # relative-only sign: is sidewalk disparity above the road plane prediction?
                pl = A * q + t
                if near_side.sum() > 30:
                    row["sidewalk_resid_pos_frac"] = float(((d[near_side] - pl[near_side]) * np.sign(A) > 0).mean())
        res[m.name].append(row)
json.dump(res, open(os.path.join(os.environ.get("GEOPROBE_ROOT", os.path.expanduser("~/geoprobe")), "out_curb.json"), "w"), indent=1)
for k, rows in res.items():
    print(k)
    for r_ in rows: print({a: (round(b, 3) if isinstance(b, float) else b) for a, b in r_.items()})

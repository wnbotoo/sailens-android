"""Geometry ground probe: PC-side experiment, not production code.

Paths evaluated on relative disparity from two monocular depth models:
  P1  relative disparity only: ground = pixels on one plane in (u, v, disparity) space
      (a ground plane's inverse depth is linear in pixel coords, and an affine map of
      inverse depth keeps it linear, so this needs no scale).
  P2  gravity (IMU) + assumed camera height h: disparity = A * (g . K^-1 p) + t on the
      ground; A and t are fitted, h turns A into the metric scale s = A * h.
"""
import glob, json, os, sys, time
import numpy as np, cv2
from ai_edge_litert.interpreter import Interpreter

ROOT = os.environ.get("GEOPROBE_ROOT", os.path.expanduser("~/geoprobe"))  # models/, data/val/ (DIODE)
rng = np.random.default_rng(0)

# ---------------------------------------------------------------- models
class PgDepth:
    name = "pg"
    def __init__(s):
        s.it = Interpreter(model_path=f"{ROOT}/models/pg_depth.tflite", num_threads=8)
        s.it.allocate_tensors()
        s.i = s.it.get_input_details()[0]["index"]; s.o = s.it.get_output_details()[0]["index"]
    def __call__(s, rgb):
        H, W = rgb.shape[:2]; c = min(H, W); y0, x0 = (H - c) // 2, (W - c) // 2
        x = cv2.resize(rgb[y0:y0 + c, x0:x0 + c], (192, 192), interpolation=cv2.INTER_AREA)
        s.it.set_tensor(s.i, x[None].astype(np.uint8)); t = time.perf_counter(); s.it.invoke()
        dt = time.perf_counter() - t
        out = s.it.get_tensor(s.o)[0, :, :, 0]
        full = np.full((H, W), np.nan, np.float32)
        full[y0:y0 + c, x0:x0 + c] = cv2.resize(out, (c, c), interpolation=cv2.INTER_LINEAR)
        return full, dt

class DaV2Small:
    name = "dav2s"
    MEAN = np.array([0.485, 0.456, 0.406], np.float32); STD = np.array([0.229, 0.224, 0.225], np.float32)
    def __init__(s, path=f"{ROOT}/models/dav2s_wi8.tflite"):
        s.it = Interpreter(model_path=path, num_threads=8); s.it.allocate_tensors()
        s.i = s.it.get_input_details()[0]["index"]; s.o = s.it.get_output_details()[0]["index"]
        _, _, s.h, s.w = s.it.get_input_details()[0]["shape"]
    def __call__(s, rgb):
        H, W = rgb.shape[:2]; ar = s.w / s.h
        if W / H > ar: cw, ch = int(round(H * ar)), H
        else: cw, ch = W, int(round(W / ar))
        y0, x0 = (H - ch) // 2, (W - cw) // 2
        x = cv2.resize(rgb[y0:y0 + ch, x0:x0 + cw], (s.w, s.h), interpolation=cv2.INTER_CUBIC)
        x = ((x.astype(np.float32) / 255 - s.MEAN) / s.STD).transpose(2, 0, 1)[None]
        s.it.set_tensor(s.i, np.ascontiguousarray(x)); t = time.perf_counter(); s.it.invoke()
        dt = time.perf_counter() - t
        out = s.it.get_tensor(s.o)[0]
        full = np.full((H, W), np.nan, np.float32)
        full[y0:y0 + ch, x0:x0 + cw] = cv2.resize(out, (cw, ch), interpolation=cv2.INTER_LINEAR)
        return full, dt

# ---------------------------------------------------------------- geometry
def rays(K, H, W, stride):
    v, u = np.mgrid[stride // 2:H:stride, stride // 2:W:stride].astype(np.float32)
    r = np.stack([(u - K[0, 2]) / K[0, 0], (v - K[1, 2]) / K[1, 1], np.ones_like(u)], -1)
    return u, v, r

def ransac_plane3d(P, thr, iters=400, normal_hint=None, max_angle_deg=50):
    """Plane n.X = d through 3D points, n pointing along the hint (down in camera coords)."""
    best, best_n = None, 0
    n_pts = len(P)
    if n_pts < 50: return None
    cos_lim = np.cos(np.radians(max_angle_deg))
    for _ in range(iters):
        a, b, c = P[rng.choice(n_pts, 3, replace=False)]
        n = np.cross(b - a, c - a); nn = np.linalg.norm(n)
        if nn < 1e-9: continue
        n /= nn
        if normal_hint is not None:
            if n @ normal_hint < 0: n = -n
            if n @ normal_hint < cos_lim: continue
        d = n @ a
        cnt = np.count_nonzero(np.abs(P @ n - d) < thr)
        if cnt > best_n: best_n, best = cnt, (n, d)
    if best is None: return None
    n, d = best
    inl = np.abs(P @ n - d) < thr
    Q = P[inl]; c = Q.mean(0); _, _, vt = np.linalg.svd(Q - c); n2 = vt[2]
    if n2 @ n < 0: n2 = -n2
    return n2, float(n2 @ c), inl

def ransac_lin(X, y, rel_thr, iters=400, min_frac=0.0):
    """y = X @ w robust fit, inlier if |resid| < rel_thr * |pred|. X: (N, k)."""
    N, k = X.shape
    if N < 50: return None
    best, best_n = None, 0
    for _ in range(iters):
        idx = rng.choice(N, k, replace=False)
        try: w = np.linalg.solve(X[idx], y[idx])
        except np.linalg.LinAlgError: continue
        p = X @ w
        cnt = np.count_nonzero(np.abs(y - p) < rel_thr * np.abs(p))
        if cnt > best_n: best_n, best = cnt, w
    if best is None or best_n < min_frac * N: return None
    p = X @ best; inl = np.abs(y - p) < rel_thr * np.abs(p)
    w, *_ = np.linalg.lstsq(X[inl], y[inl], rcond=None)
    return w, inl

def perturb(g, deg):
    if deg <= 0: return g
    axis = np.cross(g, rng.normal(size=3)); axis /= np.linalg.norm(axis)
    th = np.radians(deg)
    return g * np.cos(th) + np.cross(axis, g) * np.sin(th) + axis * (axis @ g) * (1 - np.cos(th))

def gt_floor_plane(P, n_cand=600, thr=0.04):
    """Dominant near-horizontal plane in the lower image: among RANSAC hypotheses with normal within
    35 deg of camera-down, the one with the most support (a "lowest plane" rule picked spurious planes)."""
    if len(P) < 200: return None
    cands = []
    cos_lim = np.cos(np.radians(35))
    for _ in range(n_cand):
        a, b, c = P[rng.choice(len(P), 3, replace=False)]
        n = np.cross(b - a, c - a); nn = np.linalg.norm(n)
        if nn < 1e-9: continue
        n /= nn
        if n[1] < 0: n = -n
        if n[1] < cos_lim: continue
        d = n @ a
        cands.append((np.count_nonzero(np.abs(P @ n - d) < thr), d, n))
    if not cands: return None
    best = max(c[0] for c in cands)
    cnt, d, n = max((c for c in cands if c[0] >= best), key=lambda c: c[1])
    inl = np.abs(P @ n - d) < thr
    Q = P[inl]; ctr = Q.mean(0); _, _, vt = np.linalg.svd(Q - ctr); n2 = vt[2]
    if n2 @ n < 0: n2 = -n2
    return n2, float(n2 @ ctr)

def ground_from_gravity(d, q, region, ok, v, H, rel_thr):
    """P1g: ground = pixels whose disparity is affine in q = g.K^-1 p (2 DOF). Returns (mask, A, t)."""
    if region.sum() < 100: return None
    f = ransac_lin(np.stack([q[region], np.ones(region.sum())], 1), d[region], rel_thr, iters=300)
    if f is None: return None
    (A, t), _ = f
    pl = A * q + t
    mask = ok & (np.abs(d - pl) < rel_thr * np.abs(pl)) & (v > 0.35 * H) & (q > 0)
    return mask, A, t

def eval_frame(disp, K, depth_gt, valid_gt, stride, gt_plane, bottom=0.55,
               h_err=(-0.15, 0.0, 0.15), imu_noise_deg=(0, 2, 5), rel_thr=0.04):
    H, W = disp.shape
    u, v, r = rays(K, H, W, stride)
    sl = (slice(stride // 2, H, stride), slice(stride // 2, W, stride))
    d = disp[sl]; D = depth_gt[sl]; vm = valid_gt[sl] & np.isfinite(d) & (D > 0.2) & (D < 30)
    P = r * D[..., None]
    low = vm & (v > bottom * H)
    g_true, h_true = gt_plane
    height = h_true - P @ g_true
    gt_ground = vm & (np.abs(height) < 0.05)
    sgn = np.sign(np.nanmedian(d[low])) if low.any() else 1.0
    out = {"h_true": h_true, "gt_ground_frac_low": float(gt_ground[low].mean()) if low.any() else 0.0}
    out["floor_visible"] = out["gt_ground_frac_low"] > 0.25 and 0.4 < h_true < 3.0
    def iou(pred, key):
        inter = (pred & gt_ground).sum(); union = (pred | gt_ground).sum()
        out[key + "_iou"] = float(inter / union) if union else 0.0
        out[key + "_prec"] = float(inter / max(pred.sum(), 1))
        out[key + "_rec"] = float(inter / max(gt_ground.sum(), 1))
        out[key + "_frac_low"] = float(pred[low].mean()) if low.any() else 0.0
    # linearity of the model's disparity on the TRUE ground
    if gt_ground.sum() > 100:
        Xg = np.stack([u[gt_ground], v[gt_ground], np.ones(gt_ground.sum())], 1)
        w, *_ = np.linalg.lstsq(Xg, d[gt_ground], rcond=None)
        res = np.abs(d[gt_ground] - Xg @ w) / np.abs(Xg @ w)
        out["ground_lin_relres_med"] = float(np.median(res))
    # P1: relative disparity only, free plane in (u, v, disparity)
    fit = ransac_lin(np.stack([u[low], v[low], np.ones(low.sum())], 1), d[low], rel_thr) if low.sum() > 100 else None
    if fit is not None:
        w, _ = fit
        pl = w[0] * u + w[1] * v + w[2]
        p1 = vm & (np.abs(d - pl) < rel_thr * np.abs(pl)) & (v > 0.35 * H)
        iou(p1, "p1")
        near = vm & (D < 6) & (v > 0.35 * H)
        for nm, sel, s_ in (("above", height > 0.2, 1), ("below", height < -0.15, -1)):
            m_ = near & sel
            out[f"p1_n_{nm}"] = int(m_.sum())
            if m_.sum() > 30:   # residual sign: closer than the plane (above) / farther (below)
                out[f"p1_sign_{nm}"] = float((s_ * sgn * (d[m_] - pl[m_]) > rel_thr * np.abs(pl[m_])).mean())
    # P1g + P2: gravity from IMU (true gravity, perturbed), scale from assumed camera height
    for nz in imu_noise_deg:
        g = perturb(g_true, nz)
        q = r @ g
        gr = ground_from_gravity(d, q, low & (q > 0), vm, v, H, rel_thr)
        if gr is None: continue
        mask, A, t = gr
        if nz == 0 or nz == 2: iou(mask, f"p1g_n{nz}")
        if mask.sum() < 50: continue
        f2 = ransac_lin(np.stack([q[mask], np.ones(mask.sum())], 1), d[mask], rel_thr, iters=100)
        if f2 is not None: (A, t), _ = f2
        for he in h_err:
            hh = h_true * (1 + he)
            Z = A * hh / (d - t)
            Z = np.where(Z > 0, Z, np.inf)
            key = f"p2_n{nz}_e{he:+.2f}"
            gm = gt_ground & (D < 10) & np.isfinite(Z)
            if gm.sum() > 30: out[key + "_absrel_ground"] = float(np.median(np.abs(Z[gm] - D[gm]) / D[gm]))
            hp = hh - Z * q
            zone = vm & (D < 6) & (v > 0.3 * H)
            gt_obs = zone & (height > 0.25)
            om = gt_obs & np.isfinite(Z)
            if om.sum() > 30: out[key + "_absrel_obs"] = float(np.median(np.abs(Z[om] - D[om]) / D[om]))
            pr_obs = zone & np.isfinite(Z) & (hp > 0.25)
            tp = (gt_obs & pr_obs).sum()
            if pr_obs.sum() > 30: out[key + "_obs_prec"] = float(tp / pr_obs.sum())
            if gt_obs.sum() > 30: out[key + "_obs_rec"] = float(tp / gt_obs.sum())
            # nearest obstacle distance in a 1 m wide walking corridor (the quantity guidance needs)
            X = P[..., 0]
            corr_gt = zone & (np.abs(X) < 0.5) & (height > 0.25)
            Xp = r[..., 0] * np.where(np.isfinite(Z), Z, 1e9)
            corr_pr = vm & (v > 0.3 * H) & np.isfinite(Z) & (Z < 6) & (np.abs(Xp) < 0.5) & (hp > 0.25)
            if corr_gt.sum() > 10:
                out[key + "_corr_gt"] = float(np.percentile(D[corr_gt], 5))
                out[key + "_corr_pr"] = float(np.percentile(Z[corr_pr], 5)) if corr_pr.sum() > 10 else np.inf
    return out

# ---------------------------------------------------------------- datasets
def diode(split):
    K = np.array([[886.81, 0, 512], [0, 886.81, 384], [0, 0, 1]], np.float32)
    for f in sorted(glob.glob(f"{ROOT}/data/val/{split}/*/*/*.png")):
        rgb = cv2.cvtColor(cv2.imread(f), cv2.COLOR_BGR2RGB)
        D = np.load(f[:-4] + "_depth.npy")[..., 0]
        M = np.load(f[:-4] + "_depth_mask.npy") > 0
        yield os.path.basename(f), rgb, K, D, M

def summarize(rows, keys):
    res = {}
    for k in keys:
        vals = np.array([r[k] for r in rows if k in r and r[k] is not None and np.isfinite(r[k])], float)
        if len(vals): res[k] = (float(np.median(vals)), float(np.mean(vals)), len(vals))
    return res

if __name__ == "__main__":
    split = sys.argv[1]; limit = int(sys.argv[2]) if len(sys.argv) > 2 else 10**9
    models = [PgDepth(), DaV2Small()]
    rows = {m.name: [] for m in models}; times = {m.name: [] for m in models}
    for n, (name, rgb, K, D, M) in enumerate(diode(split)):
        if n >= limit: break
        u_, v_, r_ = rays(K, *D.shape, 8)
        sl = (slice(4, D.shape[0], 8), slice(4, D.shape[1], 8))
        Ds = D[sl]; vm_ = M[sl] & (Ds > 0.2) & (Ds < 30)
        low_ = vm_ & (v_ > 0.55 * D.shape[0])
        gp = gt_floor_plane((r_ * Ds[..., None])[low_])
        if gp is None: continue
        for m in models:
            disp, dt = m(rgb); times[m.name].append(dt)
            o = eval_frame(disp, K, D, M, stride=8, gt_plane=gp)
            if o is None: continue
            o["name"] = name; rows[m.name].append(o)
        if n % 25 == 0: print(n, name, flush=True)
    json.dump({"rows": rows, "times": times}, open(f"{ROOT}/out_{split}.json", "w"), default=float)
    print("done")

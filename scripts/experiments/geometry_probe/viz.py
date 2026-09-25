"""Figure: RGB | DA V2 disparity | P1 ground (green) + obstacle-above-plane (red) | P2 height map."""
import glob, os, sys
import numpy as np, cv2
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from probe import DaV2Small, PgDepth, ransac_lin

def panel(rgb, K, g, model, h=1.3, rel=0.04):
    H, W = rgb.shape[:2]
    disp, _ = model(rgb)
    v, u = np.mgrid[0:H, 0:W].astype(np.float32)
    ok = np.isfinite(disp)
    low = ok & (v > 0.55 * H)
    idx = np.flatnonzero(low.ravel()); idx = idx[:: max(1, len(idx) // 6000)]
    X = np.stack([u.ravel()[idx], v.ravel()[idx], np.ones(len(idx))], 1)
    w, _ = ransac_lin(X, disp.ravel()[idx], rel)
    pl = w[0] * u + w[1] * v + w[2]
    sgn = np.sign(np.nanmedian(disp[low]))
    rres = (disp - pl) / np.abs(pl)
    ground = ok & (np.abs(rres) < rel) & (v > 0.35 * H)
    above = ok & (rres * sgn > 3 * rel) & (v > 0.35 * H)
    below = ok & (rres * sgn < -3 * rel) & (v > 0.35 * H)
    ov = rgb.copy()
    ov[ground] = (0.5 * ov[ground] + [0, 110, 0]).astype(np.uint8)
    ov[above] = (0.5 * ov[above] + [120, 0, 0]).astype(np.uint8)
    ov[below] = (0.5 * ov[below] + [0, 0, 140]).astype(np.uint8)
    dn = disp.copy(); dn[~ok] = np.nanmin(disp)
    dn = cv2.applyColorMap(cv2.normalize(dn, None, 0, 255, cv2.NORM_MINMAX).astype(np.uint8), cv2.COLORMAP_INFERNO)[..., ::-1]
    # P2 metric height using gravity g and assumed h
    r = np.stack([(u - K[0, 2]) / K[0, 0], (v - K[1, 2]) / K[1, 1], np.ones_like(u)], -1)
    q = r @ g
    gi = np.flatnonzero(ground.ravel()); gi = gi[:: max(1, len(gi) // 6000)]
    (A, t), _ = ransac_lin(np.stack([q.ravel()[gi], np.ones(len(gi))], 1), disp.ravel()[gi], rel, iters=200)
    Z = A * h / np.maximum(disp - t, 1e-6)
    hgt = np.clip(h - Z * q, -0.5, 1.5); hgt[~ok | (Z > 8)] = -0.5
    hm = cv2.applyColorMap(((hgt + 0.5) / 2 * 255).astype(np.uint8), cv2.COLORMAP_TURBO)[..., ::-1]
    hm[~ok | (Z > 8)] = 40
    tiles = [rgb, dn, ov, hm]
    th = 240; tiles = [cv2.resize(x, (int(W * th / H), th)) for x in tiles]
    return np.concatenate(tiles, 1)

if __name__ == "__main__":
    m = DaV2Small(); rows = []
    Kd = np.array([[886.81, 0, 512], [0, 886.81, 384], [0, 0, 1]], np.float32)
    for f in sys.argv[1:]:
        if "aachen" in f:
            K = np.array([[2262.52, 0, 1096.98], [0, 2265.30, 513.14], [0, 0, 1]], np.float32)
            g = np.array([0, np.cos(0.038), np.sin(0.038)])
            rows.append(panel(cv2.cvtColor(cv2.imread(f), cv2.COLOR_BGR2RGB), K, g, m, h=1.22))
        else:
            # "IMU" = GT floor normal (oracle gravity) for the figure
            from probe import rays, ransac_plane3d
            rgb = cv2.cvtColor(cv2.imread(f), cv2.COLOR_BGR2RGB)
            D = np.load(f[:-4] + "_depth.npy")[..., 0]; M = np.load(f[:-4] + "_depth_mask.npy") > 0
            u_, v_, r_ = rays(Kd, *D.shape, 8); Ds = D[4::8, 4::8]
            low = M[4::8, 4::8] & (Ds > 0.2) & (v_ > 0.55 * D.shape[0])
            g = ransac_plane3d((r_ * Ds[..., None])[low], 0.04, normal_hint=np.array([0, 1, 0.]))[0]
            rows.append(panel(rgb, Kd, g, m))
    wmax = max(x.shape[1] for x in rows)
    rows = [np.pad(x, ((0, 6), (0, wmax - x.shape[1]), (0, 0)), constant_values=255) for x in rows]
    cv2.imwrite(os.path.join(os.environ.get("GEOPROBE_ROOT", os.path.expanduser("~/geoprobe")), "fig.jpg"), cv2.cvtColor(np.concatenate(rows, 0), cv2.COLOR_RGB2BGR),
                [cv2.IMWRITE_JPEG_QUALITY, 85])
    print("ok")

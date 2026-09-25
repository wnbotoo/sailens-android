import json, os
import numpy as np

def vals(rows, k):
    return np.array([r[k] for r in rows if k in r and r[k] is not None and np.isfinite(r[k])], float)

def med(rows, k):
    v = vals(rows, k)
    return f"{np.median(v):.3f}(n{len(v)})" if len(v) else "-"

def corridor(rows, key):
    """Nearest obstacle in the 1 m corridor: fraction of frames where the predicted distance is
    within +-25% of truth, and fraction where it is missed (none predicted < 6 m) or too far by >25%."""
    ok = miss = far = n = 0
    for r in rows:
        if key + "_corr_gt" not in r: continue
        g, p = r[key + "_corr_gt"], r[key + "_corr_pr"]; n += 1
        if not np.isfinite(p): miss += 1
        elif abs(p - g) / g <= 0.25: ok += 1
        elif p > g: far += 1
    return f"n{n} within25%={ok/max(n,1):.2f} too-far={far/max(n,1):.2f} missed={miss/max(n,1):.2f}"

def report(rows, times, label, noise=(0, 2, 5)):
    fv = [r for r in rows if r["floor_visible"]]; nf = [r for r in rows if not r["floor_visible"]]
    print(f"--- {label}: frames {len(rows)}, floor-visible {len(fv)}, cpu {np.median(times)*1000:.0f} ms")
    print("  h_true", med(fv, "h_true"), " ground linearity relres", med(fv, "ground_lin_relres_med"))
    print("  P1  IoU", med(fv, "p1_iou"), "prec", med(fv, "p1_prec"), "rec", med(fv, "p1_rec"),
          "| no-floor frames: pred frac", med(nf, "p1_frac_low"), "gt frac", med(nf, "gt_ground_frac_low"))
    print("  P1  sign above(>0.2m) closer", med(fv + nf, "p1_sign_above"), " sign below(<-0.15m) farther",
          med(fv + nf, "p1_sign_below"), "frames w/ below", len(vals(fv + nf, "p1_sign_below")))
    for nz in (0, 2):
        print(f"  P1g imu{nz}: IoU", med(fv, f"p1g_n{nz}_iou"), "prec", med(fv, f"p1g_n{nz}_prec"), "rec",
              med(fv, f"p1g_n{nz}_rec"), "| no-floor pred frac", med(nf, f"p1g_n{nz}_frac_low"))
    for nz in noise:
        for e in ("-0.15", "+0.00", "+0.15"):
            k = f"p2_n{nz}_e{e}"
            if not vals(fv, k + "_absrel_ground").size: continue
            print(f"  P2 imu{nz} h{e}: absrel ground", med(fv, k + "_absrel_ground"), "obs", med(fv, k + "_absrel_obs"),
                  "| obs prec", med(fv, k + "_obs_prec"), "rec", med(fv, k + "_obs_rec"), "| corridor", corridor(fv, k))

if __name__ == "__main__":
    R = os.environ.get("GEOPROBE_ROOT", os.path.expanduser("~/geoprobe"))
    for split in ("indoors", "outdoor"):
        d = json.load(open(f"{R}/out_{split}.json"))
        for m in d["rows"]:
            report(d["rows"][m], d["times"][m], f"{split} {m}")
    if os.path.exists(f"{R}/out_res.json"):
        d = json.load(open(f"{R}/out_res.json"))
        for split, dd in d.items():
            for m in dd["rows"]:
                report(dd["rows"][m], dd["times"][m], f"RES {split} {m}", noise=(0, 2))

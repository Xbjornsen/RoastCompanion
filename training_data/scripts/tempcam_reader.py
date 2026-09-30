#!/usr/bin/env python3
"""
Offline 7-segment reader for the CBR-101 LED temperature display.

Proof-of-concept for the Temp Cam idea: read the red TEMP display from the
tempcam MP4s, separate the oscillating set-point from the live current temp
(motion test), and emit the current-temp time series.

Pipeline per frame:
  1. Green mask -> locate the TIME display (a reliable anchor; green only ever
     appears in that display, never in the red plastic body).
  2. Red TEMP digits sit directly above the green anchor -> derive an ROI that
     tracks the framing as it drifts.
  3. Bright-saturated-red mask inside the ROI -> the lit segments.
  4. Column projection splits the lit area into digit groups (decimal dots are
     too narrow and get dropped); each group decoded via a 7-segment table.
  5. Set-point = the most common stable reading over the clip; everything else
     is current temp.

Usage:
  python tempcam_reader.py <frames_dir> [--debug]
"""
import sys
import glob
import numpy as np
import cv2

# Italic de-slant amount (0 = off). 7-seg digits lean slightly; tuned below.
SHEAR = 0.0

# 7-segment table. Segment order: a(top) b(top-right) c(bot-right) d(bottom)
#   e(bot-left) f(top-left) g(middle)
SEG_TABLE = {
    (1, 1, 1, 1, 1, 1, 0): "0",
    (0, 1, 1, 0, 0, 0, 0): "1",
    (1, 1, 0, 1, 1, 0, 1): "2",
    (1, 1, 1, 1, 0, 0, 1): "3",
    (0, 1, 1, 0, 0, 1, 1): "4",
    (1, 0, 1, 1, 0, 1, 1): "5",
    (1, 0, 1, 1, 1, 1, 1): "6",
    (1, 1, 1, 0, 0, 0, 0): "7",
    (1, 1, 1, 1, 1, 1, 1): "8",
    (1, 1, 1, 1, 0, 1, 1): "9",
}


def green_mask(bgr):
    b, g, r = cv2.split(bgr.astype(np.int16))
    return ((g > 120) & ((g - r) > 35) & ((g - b) > 35)).astype(np.uint8)


def red_mask(bgr):
    # Strict: only the bright LED core, not the bloom halo. Keeps strokes thin
    # so digits stay separated and match the templates' thickness.
    b, g, r = cv2.split(bgr.astype(np.int16))
    return ((r > 185) & ((r - g) > 70) & ((r - b) > 70)).astype(np.uint8)


def largest_bbox(mask, min_area=150):
    """Bounding box of the largest connected component, or None."""
    n, lab, stats, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
    if n <= 1:
        return None
    # skip background (0); pick biggest by area
    idx = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    if stats[idx, cv2.CC_STAT_AREA] < min_area:
        return None
    x, y, w, h = stats[idx, :4]
    return x, y, w, h


def green_anchor(bgr):
    """Locate the green TIME digit block: cluster of green components."""
    gm = green_mask(bgr)
    gm = cv2.morphologyEx(gm, cv2.MORPH_CLOSE, np.ones((5, 15), np.uint8))
    ys, xs = np.where(gm > 0)
    if len(xs) < 200:
        return None
    # trim outliers so stray green pixels don't blow up the box
    x0, x1 = np.percentile(xs, [2, 98]).astype(int)
    y0, y1 = np.percentile(ys, [2, 98]).astype(int)
    if x1 - x0 < 40 or y1 - y0 < 15:
        return None
    return x0, y0, x1 - x0, y1 - y0


CANON_W, CANON_H = 40, 64


def _digit_template(seg, th=11):
    W, H = CANON_W, CANON_H
    t = np.zeros((H, W), np.uint8)
    a, b, c, d, e, f, g = seg
    if a: t[0:th, th:W - th] = 1
    if b: t[0:H // 2, W - th:W] = 1
    if c: t[H // 2:H, W - th:W] = 1
    if d: t[H - th:H, th:W - th] = 1
    if e: t[H // 2:H, 0:th] = 1
    if f: t[0:H // 2, 0:th] = 1
    if g: t[H // 2 - th // 2:H // 2 + (th - th // 2), th:W - th] = 1
    return t


TEMPLATES = {SEG_TABLE[s]: _digit_template(s) for s in SEG_TABLE}


def decode_digit(cell):
    """Match the cell against rendered 7-seg templates (whole-shape overlap)."""
    h, w = cell.shape
    if h < 12:
        return None
    if w / h < 0.42:            # narrow pair of bars -> "1"
        return "1"
    c = cv2.resize(cell.astype(np.uint8), (CANON_W, CANON_H), interpolation=cv2.INTER_NEAREST)
    best, best_score = None, -1.0
    for d, t in TEMPLATES.items():
        score = float(np.mean(c == t))
        if score > best_score:
            best, best_score = d, score
    return best if best_score > 0.74 else None


def split_number(m):
    """Locate digits from the TOP 60% of the mask: digit tops are always
    separated by a gap (even when the bottoms merge), and decimal points live
    only at the baseline, so the top band is decimal-free. Each column-group in
    the top band is one digit; slice the full-height cell there."""
    ys, xs = np.where(m > 0)
    if len(xs) < 30:
        return []
    x0, x1, y0, y1 = xs.min(), xs.max(), ys.min(), ys.max()
    m = m[y0:y1 + 1, x0:x1 + 1]
    H, W = m.shape
    band = m[0:int(0.60 * H), :]
    on = band.sum(axis=0) > 1
    groups, i = [], 0
    while i < W:
        if on[i]:
            j = i
            while j < W and on[j]:
                j += 1
            groups.append((i, j))
            i = j
        else:
            i += 1
    # bridge tiny gaps (a stroke break), drop specks
    merged = []
    for g in groups:
        if merged and g[0] - merged[-1][1] < 0.06 * H:
            merged[-1] = (merged[-1][0], g[1])
        else:
            merged.append(list(g))
    cells = []
    for (a, b) in merged:
        if (b - a) < 0.08 * H:          # too thin to be even a "1"
            continue
        cells.append(m[:, max(0, a - 1):min(W, b + 2)])
    return cells


def deslant(mask, shear=0.22):
    """Remove the italic lean so axis-aligned segment sampling lines up."""
    H, W = mask.shape
    M = np.float32([[1, shear, -shear * H], [0, 1, 0]])
    return cv2.warpAffine(mask, M, (W + int(shear * H), H), flags=cv2.INTER_NEAREST)


def clean_mask(rm):
    """Strip the full-width glare bar and keep only digit-height blobs."""
    H, W = rm.shape
    # 1. remove the glare bar: bright full-width rows, but ONLY in the top band
    #    (digit rows lower down can also be wide and must be kept).
    rm = rm.copy()
    row_frac = rm.sum(axis=1) / max(1, W)
    top = int(0.35 * H)
    rm[:top][row_frac[:top] > 0.70] = 0
    # 2. keep components that are digit-tall (drops dots, bar bits, edge noise).
    #    NO width cap — adjacent digits often merge into one wide blob.
    n, lab, stats, _ = cv2.connectedComponentsWithStats((rm * 255).astype(np.uint8), 8)
    keep = np.zeros_like(rm)
    for k in range(1, n):
        x, y, w, h, area = stats[k]
        if h >= 0.40 * H and area >= 0.006 * H * W:
            keep[lab == k] = 1
    return deslant(keep, SHEAR)


def split_and_decode(roi_mask):
    """Clean, split into digits by count+valleys, decode each L->R."""
    m = clean_mask(roi_mask)
    cells = split_number(m)
    if not cells:
        return None
    digits = []
    for cell in cells:
        rows = np.where(cell.sum(axis=1) > 0)[0]
        if len(rows) < 8:
            return None
        cell = cell[rows[0]:rows[-1] + 1, :]
        d = decode_digit(cell)
        if d is None:
            return None
        digits.append(d)
    try:
        return int("".join(digits))
    except ValueError:
        return None


def read_frame(bgr):
    ga = green_anchor(bgr)
    if ga is None:
        return None, None
    gx, gy, gw, gh = ga
    # TEMP digits sit above the green block, roughly same x span.
    rx0 = max(0, gx - int(0.10 * gw))
    rx1 = min(bgr.shape[1], gx + gw + int(0.10 * gw))
    ry1 = gy - int(0.12 * gh)
    ry0 = max(0, gy - int(1.9 * gh))
    if ry1 - ry0 < 15:
        return None, (rx0, ry0, rx1, ry1)
    roi = bgr[ry0:ry1, rx0:rx1]
    rm = red_mask(roi)
    val = split_and_decode(rm)
    return val, (rx0, ry0, rx1, ry1)


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: tempcam_reader.py <frames_dir> [--debug]")
    frames_dir = sys.argv[1]
    debug = "--debug" in sys.argv
    files = sorted(glob.glob(f"{frames_dir}/f_*.jpg"))
    fps = 4.0

    readings = []
    dbg_tiles = []
    for i, fp in enumerate(files):
        bgr = cv2.imread(fp)
        val, roi = read_frame(bgr)
        t = i / fps
        readings.append((t, val))
        if debug and i % 8 == 0 and roi is not None:
            vis = bgr.copy()
            cv2.rectangle(vis, (roi[0], roi[1]), (roi[2], roi[3]), (0, 255, 255), 3)
            cv2.putText(vis, f"{t:.1f}s {val}", (20, 60),
                        cv2.FONT_HERSHEY_SIMPLEX, 1.4, (0, 255, 255), 3)
            dbg_tiles.append(cv2.resize(vis, (bgr.shape[1] // 3, bgr.shape[0] // 3)))

    ok = [v for _, v in readings if v is not None]
    print(f"frames={len(readings)} decoded={len(ok)} ({100*len(ok)//max(1,len(readings))}%)")

    # Set-point = most common reading; current temp = the rest.
    from collections import Counter
    if ok:
        setpoint, _ = Counter(ok).most_common(1)[0]
        print(f"detected set-point = {setpoint}")
        cur = [(t, v) for (t, v) in readings if v is not None and v != setpoint]
        # plausibility: drop absurd jumps vs running median
        vals = [v for _, v in cur]
        print(f"current-temp readings: {len(cur)}")
        if vals:
            print(f"  range {min(vals)} -> {max(vals)} C")
            # print a downsampled trace
            for k in range(0, len(cur), max(1, len(cur) // 20)):
                print(f"  t={cur[k][0]:5.1f}s  {cur[k][1]} C")

    if debug and dbg_tiles:
        cols = 4
        rows = [np.hstack(dbg_tiles[r:r + cols]) for r in range(0, len(dbg_tiles), cols)]
        wmax = max(r.shape[1] for r in rows)
        rows = [np.pad(r, ((0, 0), (0, wmax - r.shape[1]), (0, 0))) for r in rows]
        cv2.imwrite(f"{frames_dir}/../frames/reader_debug.jpg", np.vstack(rows))
        print("wrote frames/reader_debug.jpg")


if __name__ == "__main__":
    main()

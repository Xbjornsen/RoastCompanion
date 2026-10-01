"""
Shared training / evaluation code for the v2 crack detector.

  load_sessions()   -> per-roast feature streams (from features/*.npz, see extract.py)
  label_stream()    -> per-frame labels (-1 skip, 0 ambient, 1 FC, 2 SC)
  replay()          -> offline replica of AudioAnalyzer's detection state machine
  (train_v2.py and harness.py are thin CLIs over this module)

Everything here works on the same 50 ms frames the phone sees, and only frames that
pass (a slightly widened version of) the app's amplitude + spectral gates are used
for training — those are the only frames the model is ever asked about on device.
"""
import json
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

import features as F
import labels as L

FRAME_MS = 50.0

# ---- App constants (mirror AudioAnalyzer / TransientDetector / UserPreferences) ----
APP = dict(
    mult=1.5,               # UserPreferences.DEFAULT_THRESHOLD_MULTIPLIER
    spec_min=0.12,          # SpectralGate.CRACK_BAND_MIN_RATIO
    warmup=60,              # TransientDetector.MIN_WARMUP_FRAMES
    amb_win=100, amb_pct=0.70,
    grace_ms=25_000,        # FC_STARTUP_GRACE_MS
    fc_win_ms=15_000, bucket_ms=5_000, min_buckets=3,
    pops_normal=5, pops_early=8, early_conf=0.50,
    min_fc_time_ms=9 * 60_000,   # DEFAULT_MIN_FC_TIME_MIN
    min_tr_fc=2, min_tr_sc=2,
    fc_quiet_ms=25_000,     # DEFAULT_FC_QUIET_PERIOD_S
    sc_win_ms=10_000, sc_amp=1.8, min_fc_to_sc_ms=75_000,
)

# ---- Training-frame selection (wider than the app gate so borderline frames are seen) ----
TRAIN_REL_MIN = np.log(1.3)     # rel_loud > ln(1.3)  (app gate ~ ln(1.5))
TRAIN_SPEC_MIN = 0.10           # app gate 0.12
HF_ONSET_NATS = 1.0             # crack frames must stand out in the 2-9 kHz band
HF_MEDIAN_FRAMES = 100          # ...vs the median of the previous 5 s


@dataclass
class Session:
    key: int                 # startTimeMs
    sid: int
    meta: dict
    streams: list            # [dict(feat, rms, gate_spec, hf_log, offset_ms)]
    gt: dict = field(default_factory=dict)   # FC_START / FC_END / SC_START (ms)
    kind: str = "unlabelled" # "gt" | "confirmed" | "negative" | "unlabelled"


def load_sessions(feat_dir):
    out = []
    for p in sorted(Path(feat_dir).glob("training_*.npz")):
        z = np.load(p, allow_pickle=False)
        meta = json.loads(str(z["meta"]))
        if int(z["feature_version"]) != F.FEATURE_VERSION:
            raise SystemExit(f"{p.name}: feature_version {int(z['feature_version'])} != {F.FEATURE_VERSION}; re-run extract.py --force")
        streams = []
        for s, off in enumerate((0, F.FRAME // 2)):
            streams.append(dict(feat=z[f"s{s}_feat"], rms=z[f"s{s}_rms"], gate_spec=z[f"s{s}_gate_spec"],
                                hf_log=z[f"s{s}_hf_log"], imp=z[f"s{s}_imp"],
                                offset_ms=off / F.SR * 1000.0))
        key = meta.get("startTimeMs")
        sess = Session(key=key, sid=meta.get("sessionId"), meta=meta, streams=streams)
        if key in L.NEGATIVE_SESSIONS:
            sess.kind = "negative"
        elif key in L.GROUND_TRUTH:
            sess.kind, sess.gt = "gt", dict(L.GROUND_TRUTH[key])
        elif meta.get("sessionId") not in L.EXCLUDE_SESSIONS:
            conf = {e["type"]: e["elapsedMs"] for e in meta.get("confirmed", [])}
            if "FC_START" in conf:
                sess.kind, sess.gt = "confirmed", conf
        if len(streams[0]["feat"]) > 200:      # skip the 7 s test clip
            out.append(sess)
    return out


def _rolling_median_prev(x, n):
    out = np.empty_like(x)
    for i in range(len(x)):
        lo = max(0, i - n)
        out[i] = np.median(x[lo:i]) if i > lo else x[i]
    return out


def label_stream(sess, st):
    """-1 skip / 0 ambient / 1 FC / 2 SC for every frame of one stream."""
    feat = st["feat"]
    n = len(feat)
    t = np.arange(n) * FRAME_MS + st["offset_ms"]
    y = np.full(n, -1, np.int8)
    impulsive = st["imp"] > IMP_TH
    cand = ((feat[:, 12] > TRAIN_REL_MIN) & (st["gate_spec"] >= TRAIN_SPEC_MIN)) | impulsive
    if sess.kind == "negative":
        y[cand] = 0
        return y, cand
    if sess.kind not in ("gt", "confirmed"):
        return y, cand
    gt = sess.gt
    fc = gt["FC_START"]
    # confirmed taps lag the real onset by reaction time; give them more slack
    pre = 3_000 if sess.kind == "gt" else 15_000
    hf_onset = (st["hf_log"] > _rolling_median_prev(st["hf_log"], HF_MEDIAN_FRAMES) + HF_ONSET_NATS) | impulsive
    pull = gt.get("SC_START") or (sess.meta.get("autoDetected") or {}).get("scStartElapsedMs")
    # --- ambient: start .. FC-30s (includes the 8-min CBR tick as hard negatives)
    if fc > 120_000:
        y[cand & (t < fc - 30_000)] = 0
    else:   # mid-roast start: popping from t=0, no clean ambient
        pass
    # --- FC zone
    fc_end = gt.get("FC_END")
    z1 = (fc_end + 20_000) if fc_end else fc + 90_000
    if pull:
        z1 = min(z1, pull - 5_000)
    z0 = max(0, fc - pre)
    inz = (t >= z0) & (t < z1)
    y[inz & cand & hf_onset] = 1
    # --- SC zone (human-verified SC only)
    sc = gt.get("SC_START")
    if sc and sess.kind == "gt":
        inz = (t >= sc - 3_000) & (t < sc + 45_000)
        y[inz & cand] = -1
        y[inz & cand & hf_onset] = 2
    # --- blank the recorded SC alarm tone (auto SC time) from ambient/SC
    alarm = (sess.meta.get("autoDetected") or {}).get("scStartElapsedMs")
    if alarm:
        a = (t >= alarm) & (t < alarm + 12_000) & (y != 1)
        y[a] = -1
    return y, cand


IMP_TH = 2.5   # impulsive frame (1 ms block peak vs median, nats)


def frame_inputs(st):
    """(n,17) per-frame model inputs: feature spec v2 + impulsiveness."""
    return np.concatenate([st["feat"], st["imp"][:, None]], axis=1)


def stacked(st):
    f = frame_inputs(st)
    n = len(f)
    idx = np.clip(np.arange(n)[:, None] + np.arange(-(F.CONTEXT_FRAMES - 1), 1)[None, :], 0, n - 1)
    return f[idx].reshape(n, -1)


def build_xy(sessions, streams=(0, 1)):
    X, Y, G = [], [], []
    for sess in sessions:
        for si in streams:
            st = sess.streams[si]
            y, _ = label_stream(sess, st)
            keep = y >= 0
            if keep.any():
                X.append(stacked(st)[keep]); Y.append(y[keep])
                G.append(np.full(int(keep.sum()), sess.key))
    return np.concatenate(X), np.concatenate(Y), np.concatenate(G)


# ------------------------------------------------------------------------------------
# Offline replica of AudioAnalyzer.processBuffer (MONITORING -> FC -> FC_COMPLETE -> SC)
# `probs_fn(i)` returns softmax [ambient, FC, SC] for frame i, or None = model missing
# (the app then falls back to "every gated frame is the phase's crack type").
# ------------------------------------------------------------------------------------
def replay(st, probs_fn, cfg=APP):
    rms = st["rms"].astype(np.float64)
    gate = st["gate_spec"]
    n = len(rms)
    win = []
    ambient = 1.0
    warm = 0
    phase = "MON"
    fc_pops = []            # (t, pfc)
    ev = {}
    fc_start = fc_last = 0.0
    tw_start, tw_count = 0.0, 0

    def upd(r):
        nonlocal ambient, warm
        warm += 1
        win.append(r)
        if len(win) > cfg["amb_win"]:
            win.pop(0)
        s = sorted(win)
        cut = max(1, int(len(s) * cfg["amb_pct"]))
        ambient = max(1.0, float(np.mean(s[:cut])))

    def classify(i, mult, fallback):
        if warm < cfg["warmup"] or not (rms[i] > ambient * mult):
            return None
        if gate[i] < cfg["spec_min"]:
            return None
        p = probs_fn(i)
        if p is None:
            return (fallback, 1.0)
        c = int(np.argmax(p))
        return ({1: "FC", 2: "SC"}.get(c), float(p[1]))

    def prune(t):
        while fc_pops and fc_pops[0][0] < t - cfg["fc_win_ms"]:
            fc_pops.pop(0)

    for i in range(n):
        t = i * FRAME_MS
        r = rms[i]
        if phase == "MON":
            if t < cfg["grace_ms"]:
                upd(r); prune(t); continue
            res = classify(i, cfg["mult"], "FC")
            if res and res[0] == "FC":
                fc_pops.append((t, res[1])); prune(t)
                early = t < cfg["min_fc_time_ms"]
                need = max(cfg["min_tr_fc"], cfg["pops_early"] if early else cfg["pops_normal"])
                if len(fc_pops) >= need:
                    first = fc_pops[0][0]
                    buckets = len({int((p[0] - first) // cfg["bucket_ms"]) for p in fc_pops})
                    ok = buckets >= cfg["min_buckets"]
                    if ok and early and np.mean([p[1] for p in fc_pops]) < cfg["early_conf"]:
                        ok = False
                    if ok:
                        fc_start = fc_pops[0][0]; fc_last = t
                        ev["FC"] = fc_start; phase = "FC"; fc_pops.clear()
                        tw_start, tw_count = t, 0
            else:
                upd(r); prune(t)
        elif phase == "FC":
            res = classify(i, cfg["mult"], "FC")
            if res and res[0] == "FC":
                fc_last = t
                if t - tw_start > cfg["fc_win_ms"]:
                    tw_start, tw_count = t, 0
                tw_count += 1
            else:
                upd(r)
                if fc_last > 0 and t - fc_last > cfg["fc_quiet_ms"]:
                    ev["FC_END"] = t; phase = "FCC"; tw_start, tw_count = t, 0
        elif phase == "FCC":
            res = classify(i, min(cfg["mult"], cfg["sc_amp"]), "SC")
            if res and res[0] == "SC":
                if t - tw_start > cfg["sc_win_ms"]:
                    tw_start, tw_count = t, 0
                tw_count += 1
                if tw_count >= cfg["min_tr_sc"]:
                    if t - fc_start >= cfg["min_fc_to_sc_ms"]:
                        ev["SC"] = t; phase = "SC"
                        break
                    # (app logs "SC blocked by FC->SC floor" and keeps counting)
            else:
                upd(r)
                if t - tw_start > cfg["sc_win_ms"]:
                    tw_start, tw_count = t, 0
    return ev


def fmt(ms):
    if ms is None:
        return "  —  "
    return f"{int(ms // 60000)}:{int(ms / 1000) % 60:02d}"

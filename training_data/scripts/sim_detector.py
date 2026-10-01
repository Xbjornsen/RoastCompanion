#!/usr/bin/env python3
"""LEGACY: replicates the removed v1 detector using TRAINING-side (librosa)
features, not what the phone computed — which is how the feature mismatch stayed
hidden. Use harness.py (rolldet.py == RollDetector.kt) instead.

Offline replica of AudioAnalyzer's FC path to see WHEN it fires FC on a
recording. Faithful to the app: 20 fps (50 ms frames), amplitude gate on a
rolling lower-70th-pct ambient, spectral gate, 3-class model, sustained-roll
detector, and the 25 s startup grace. Prints every FC fire it would produce."""
import sys, json
from collections import deque
import numpy as np
import tensorflow as tf
import train as T

# --- app constants ---
FPS = 20
FRAME_MS = 50
WARMUP = 60
AMB_WIN = 100
AMB_PCT = 0.70
MULT = 3.5              # default Crack Sensitivity
SPEC_MIN = 0.12
GRACE_MS = 25_000
WIN_MS = 15_000
BUCKET_MS = 5_000
MIN_BUCKETS = 3
POPS_NORMAL = 5
POPS_EARLY = 8
EARLY_CONF = 0.50
MIN_FC_TIME_MS = 9 * 60_000

from pathlib import Path
_ASSETS = Path(__file__).resolve().parent.parent.parent / "app" / "src" / "main" / "assets"
MODEL = str(_ASSETS / "crack_detector.tflite")
NORM = str(_ASSETS / "feature_norm.json")


def load_model():
    n = json.load(open(NORM))
    mean = np.array(n["mean"], np.float32); std = np.array(n["std"], np.float32)
    it = tf.lite.Interpreter(model_path=MODEL); it.allocate_tensors()
    ind = it.get_input_details()[0]["index"]; outd = it.get_output_details()[0]["index"]
    def predict(feat):
        x = ((feat - mean) / std).astype(np.float32)[None, :]
        it.set_tensor(ind, x); it.invoke()
        return it.get_tensor(outd)[0]
    return predict


def sim(wav, mult=MULT, report_all=False):
    audio = T.load_wav_i16(wav)
    T.HOP_MS = FRAME_MS            # 50 ms hop -> 20 fps, matches the app
    feats = T.extract_features(audio)
    log_rms = feats[:, -2]
    spec = feats[:, -1]
    rms = np.exp(log_rms) - 1.0
    predict = load_model()

    ambient = 1.0
    win = deque(maxlen=AMB_WIN)
    fcpops = deque()            # (t_ms, fc_prob)
    fires = []

    def update_ambient(r):
        nonlocal ambient
        win.append(r)
        s = sorted(win)
        cut = max(1, int(len(s) * AMB_PCT))
        ambient = max(1.0, float(np.mean(s[:cut])))

    def prune(t):
        while fcpops and fcpops[0][0] < t - WIN_MS:
            fcpops.popleft()

    def roll_ok(t, early):
        prune(t)
        if not fcpops:
            return False
        need = POPS_EARLY if early else POPS_NORMAL
        if len(fcpops) < need:
            return False
        first = fcpops[0][0]
        buckets = len({int((p[0] - first) // BUCKET_MS) for p in fcpops})
        if buckets < MIN_BUCKETS:
            return False
        if early and np.mean([p[1] for p in fcpops]) < EARLY_CONF:
            return False
        return True

    for i in range(len(feats)):
        t = i * FRAME_MS
        if i < WARMUP:
            update_ambient(rms[i]); continue
        if t < GRACE_MS:
            update_ambient(rms[i]); prune(t); continue
        is_tr = rms[i] > ambient * mult
        if is_tr and spec[i] >= SPEC_MIN:
            probs = predict(feats[i])
            cls = int(np.argmax(probs))
            if cls == 1:  # FC
                fcpops.append((t, float(probs[1])))
                early = t < MIN_FC_TIME_MS
                if roll_ok(t, early):
                    fires.append((t, len(fcpops), round(float(np.mean([p[1] for p in fcpops])), 2)))
                    if not report_all:
                        break
                    fcpops.clear()
            else:
                update_ambient(rms[i]); prune(t)
        else:
            update_ambient(rms[i]); prune(t)
    return fires


def fmt(t):
    return f"{int(t//60000)}:{int(t/1000)%60:02d}"


if __name__ == "__main__":
    wavs = [a for a in sys.argv[1:] if a.endswith(".wav")]
    for wav in wavs:
        p = T.RAW_DIR / wav
        print(f"=== {wav} ===")
        for m in (1.5, 1.8, 2.0, 2.3, 2.6, 3.0):
            fires = sim(p, mult=m, report_all=True)
            if fires:
                shown = ", ".join(f"{fmt(t)}(pops{n},c{c})" for t, n, c in fires[:6])
                print(f"  mult={m}: FIRST fire {fmt(fires[0][0])}  | all: {shown}")
            else:
                print(f"  mult={m}: never fires")

#!/usr/bin/env python3
"""
Train + evaluate the v2 crack detector (feature spec v2, see features.py).

  python extract.py           # once per new recording (pure numpy)
  python train_v2.py --eval   # leave-one-roast-out: replays the app's detector on each
                              # held-out roast and reports FC/SC timing error vs by-ear GT
  python train_v2.py          # train on everything, export model/ (tflite + feature_norm.json)

Evaluation is per ROAST, never per frame: frames of one roast are never split
between train and test (the v1 script's random frame split leaked heavily).
"""
import argparse, json, sys
from pathlib import Path

import numpy as np

import crackml as C
import features as F

HERE = Path(__file__).parent
FEAT_DIR = HERE.parent / "features"
MODEL_DIR = HERE.parent / "model"
SEED = 7


def build_model(n_in):
    import tensorflow as tf
    tf.keras.utils.set_random_seed(SEED)
    return tf.keras.Sequential([
        tf.keras.layers.Input(shape=(n_in,), name="features"),
        tf.keras.layers.Dense(64, activation="relu"),
        tf.keras.layers.Dropout(0.3),
        tf.keras.layers.Dense(32, activation="relu"),
        tf.keras.layers.Dense(3, activation="softmax", name="crack_probs"),
    ], name="crack_detector_v2")


def fit(X, Y, epochs=25, verbose=0):
    mean = X.mean(0).astype(np.float32)
    std = (X.std(0) + 1e-6).astype(np.float32)
    counts = np.bincount(Y, minlength=3)
    cw = {c: len(Y) / (3 * max(int(counts[c]), 1)) for c in range(3)}
    m = build_model(X.shape[1])
    m.compile(optimizer="adam", loss="sparse_categorical_crossentropy")
    m.fit((X - mean) / std, Y, epochs=epochs, batch_size=256, class_weight=cw, verbose=verbose)
    return m, mean, std


def predictor(model, mean, std, st):
    Xs = (C.stacked(st) - mean) / std
    P = model.predict(Xs.astype(np.float32), batch_size=4096, verbose=0)
    return lambda i: P[i]


def evaluate(sessions, epochs):
    rows = []
    test = [s for s in sessions if s.kind == "gt"]
    for k, hold in enumerate(test):
        train = [s for s in sessions if s.key != hold.key]
        X, Y, _ = C.build_xy(train)
        model, mean, std = fit(X, Y, epochs)
        ev = C.replay(hold.streams[0], predictor(model, mean, std, hold.streams[0]))
        rows.append((hold, ev))
        g = hold.gt
        efc = (ev["FC"] - g["FC_START"]) / 1000 if "FC" in ev else None
        esc = (ev["SC"] - g["SC_START"]) / 1000 if ("SC" in ev and g.get("SC_START")) else None
        print(f"[{k+1:2}/{len(test)}] sess {hold.sid:>3}  FC gt {C.fmt(g['FC_START'])} got {C.fmt(ev.get('FC'))} "
              f"{'' if efc is None else f'({efc:+.0f}s)':>8}   SC gt {C.fmt(g.get('SC_START'))} got {C.fmt(ev.get('SC'))} "
              f"{'' if esc is None else f'({esc:+.0f}s)'}", flush=True)
    summarise(rows)
    return rows


def summarise(rows):
    fc_err = [(ev["FC"] - s.gt["FC_START"]) / 1000 for s, ev in rows if "FC" in ev]
    fc_miss = sum("FC" not in ev for s, ev in rows)
    fc_early = sum(1 for e in fc_err if e < -30)
    fc_ok = sum(1 for e in fc_err if -30 <= e <= 30)
    sc_rows = [(s, ev) for s, ev in rows if s.gt.get("SC_START")]
    sc_err = [(ev["SC"] - s.gt["SC_START"]) / 1000 for s, ev in sc_rows if "SC" in ev]
    sc_ok = sum(1 for e in sc_err if -30 <= e <= 30)
    print(f"\nFC: {fc_ok}/{len(rows)} within ±30 s, {fc_early} early (false), {fc_miss} missed; "
          f"median |err| {np.median(np.abs(fc_err)) if fc_err else float('nan'):.0f}s")
    print(f"SC: {sc_ok}/{len(sc_rows)} within ±30 s, {len(sc_rows) - len(sc_err)} missed; "
          f"errors {[round(e) for e in sc_err]}")


def export(model, mean, std):
    import tensorflow as tf
    MODEL_DIR.mkdir(exist_ok=True)
    tfl = tf.lite.TFLiteConverter.from_keras_model(model).convert()
    (MODEL_DIR / "crack_detector.tflite").write_bytes(tfl)
    norm = dict(feature_version=F.FEATURE_VERSION, n_features=int(mean.shape[0]),
                feats_per_frame=F.FEATS_PER_FRAME, context_frames=F.CONTEXT_FRAMES,
                n_classes=3, classes=["ambient", "FC", "SC"],
                mean=[float(v) for v in mean], std=[float(v) for v in std])
    (MODEL_DIR / "feature_norm.json").write_text(json.dumps(norm, indent=1))
    print(f"exported {len(tfl):,} B tflite + feature_norm.json to {MODEL_DIR}")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--eval", action="store_true")
    ap.add_argument("--epochs", type=int, default=25)
    ap.add_argument("--features", default=str(FEAT_DIR))
    a = ap.parse_args()
    S = C.load_sessions(a.features)
    if a.eval:
        evaluate(S, a.epochs)
    else:
        X, Y, _ = C.build_xy(S)
        print("frames", len(Y), "ambient/FC/SC", np.bincount(Y, minlength=3))
        export(*fit(X, Y, a.epochs, verbose=2))

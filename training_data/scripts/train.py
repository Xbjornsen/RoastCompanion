#!/usr/bin/env python3
"""
RoastCompanion crack detector trainer.

Loads WAV+JSON pairs from training_data/raw/, extracts per-frame features,
trains a binary classifier (crack / ambient), and exports TFLite.

Usage:
    cd training_data/scripts
    python train.py

Outputs:
    training_data/model/crack_detector.tflite   — drop into app/src/main/assets/
    training_data/model/feature_norm.json       — drop into app/src/main/assets/
"""

import io
import json
import sys
import numpy as np

# Force UTF-8 stdout on Windows (cp1252 default chokes on box-drawing chars)
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
from pathlib import Path

try:
    import librosa
except ImportError:
    sys.exit("Missing: pip install librosa")

try:
    import tensorflow as tf
except ImportError:
    sys.exit("Missing: pip install tensorflow")

from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report

# ── Paths ──────────────────────────────────────────────────────────────────────
SCRIPT_DIR = Path(__file__).parent
RAW_DIR    = SCRIPT_DIR.parent / "raw"
MODEL_DIR  = SCRIPT_DIR.parent / "model"

# ── Audio / feature config ─────────────────────────────────────────────────────
SAMPLE_RATE = 44100
FRAME_MS    = 50           # 2205 samples per frame
HOP_MS      = 25           # 50% overlap -> 1102 samples per hop
N_MFCC      = 13
N_FFT       = 4096   # must be >= win_length (2205 samples at 44100 Hz / 50 ms)
HF_LOW_HZ   = 2000         # same band as SpectralGate in the app
HF_HIGH_HZ  = 9000
AUDIBLE_HZ  = 200          # ignore DC/sub-bass

# ── Labelling config ───────────────────────────────────────────────────────────
# Sessions where fc_start_ms < this are mid-roast restarts: FC sounds from t=0
MID_ROAST_THRESHOLD_MS = 120_000   # 2 minutes

# For normal sessions: how far before confirmed FC to pull ambient from
AMBIENT_END_BUFFER_S   = 60        # stop 60 s before FC_START for clean ambient

# How much before/after FC_START to include in the crack window
CRACK_PRE_S  = 15
CRACK_POST_S = 30   # past FC_END we often still have rolling cracks

# Within a crack window, only frames with log-RMS this many nats above the
# window median are labelled positive.  Quiet gaps between pops -> skipped.
CRACK_RMS_NATS = 0.6   # ≈ e^0.6 ≈ 1.82× the median RMS

# Sessions with no human confirmation that fell back to the auto-detector's
# own (false) output as "ground truth" — poisons the model. Exclude them.
EXCLUDE_SESSIONS = {28}

# Human-verified-by-ear event times (ms), keyed by sessionId. These override
# the JSON's tap-confirmed/auto values, which lag real onset by reaction time.
# Captured by listening to the last 3 min in clips/player.html.
GROUND_TRUTH = {
    2: {"FC_START": 622_400, "SC_START": 719_700},   # by ear: FC 10:22, SC 11:59
    3: {"FC_START": 608_200, "SC_START": 694_500},   # by ear: FC 10:08, SC 11:34
    # Sessions 10 & 11: owner verified FC start+end were accurate; no SC label
    # (beans pulled as SC starts, so no real SC roll was recorded).
    10: {"FC_START": 554_811, "FC_END": 676_772},    # FC 9:15 -> 11:17
    11: {"FC_START": 541_693, "FC_END": 672_195},    # FC 9:02 -> 11:12
}

# The SC alarm tone plays through the phone speaker at autoDetected.scStart and
# is captured by the mic. Blank this window out of the labels so the model never
# learns "my own alarm == second crack".
ALARM_GUARD_MS = 12_000


# ── Feature extraction ─────────────────────────────────────────────────────────

def _frame_index(elapsed_ms: float) -> int:
    hop = int(SAMPLE_RATE * HOP_MS / 1000)
    return int((elapsed_ms / 1000.0) * SAMPLE_RATE / hop)


def extract_features(audio_i16: np.ndarray) -> np.ndarray:
    """
    Returns ndarray of shape (n_frames, 15):
        columns 0-12  — 13 MFCCs
        column  13    — log RMS (log of raw int16 RMS)
        column  14    — spectral ratio: fraction of energy in HF_LOW..HF_HIGH Hz
    """
    frame_size = int(SAMPLE_RATE * FRAME_MS / 1000)
    hop_size   = int(SAMPLE_RATE * HOP_MS  / 1000)

    audio_f32 = audio_i16.astype(np.float32) / 32768.0

    # MFCCs — shape (N_MFCC, n_frames)
    mfccs = librosa.feature.mfcc(
        y=audio_f32, sr=SAMPLE_RATE,
        n_mfcc=N_MFCC, n_fft=N_FFT,
        hop_length=hop_size, win_length=frame_size,
    )
    n_frames = mfccs.shape[1]

    freqs       = np.fft.rfftfreq(N_FFT, 1.0 / SAMPLE_RATE)
    hf_mask     = (freqs >= HF_LOW_HZ) & (freqs <= HF_HIGH_HZ)
    audible_mask = freqs >= AUDIBLE_HZ

    log_rms = np.zeros(n_frames, dtype=np.float32)
    spec_ratio = np.zeros(n_frames, dtype=np.float32)

    for i in range(n_frames):
        s = i * hop_size
        e = s + frame_size
        frame = audio_i16[s:e] if e <= len(audio_i16) else \
                np.pad(audio_i16[s:], (0, e - len(audio_i16)))

        rms = np.sqrt(np.mean(frame.astype(np.float64) ** 2))
        log_rms[i] = float(np.log(rms + 1.0))

        fft_sq = np.abs(np.fft.rfft(frame.astype(np.float64), n=N_FFT)) ** 2
        hf_e   = np.sum(fft_sq[hf_mask])
        tot_e  = np.sum(fft_sq[audible_mask]) + 1e-10
        spec_ratio[i] = float(hf_e / tot_e)

    return np.column_stack([mfccs.T, log_rms, spec_ratio]).astype(np.float32)


# ── Load WAV (raw PCM, no librosa decode to avoid float precision loss) ─────────

def load_wav_i16(path: Path) -> np.ndarray:
    import wave
    with wave.open(str(path), 'rb') as w:
        assert w.getsampwidth() == 2, "expected 16-bit PCM"
        assert w.getnchannels() == 1, "expected mono"
        raw = w.readframes(w.getnframes())
    return np.frombuffer(raw, dtype="<i2").astype(np.int16)


# ── Label one session ──────────────────────────────────────────────────────────

def label_session(features: np.ndarray, meta: dict) -> np.ndarray:
    """
    Returns int8 label array aligned with features:
        -1  skip (ambiguous)
         0  ambient
         1  first crack
         2  second crack
    """
    n_frames = len(features)
    labels   = np.full(n_frames, -1, dtype=np.int8)

    confirmed = {e["type"]: e["elapsedMs"] for e in meta.get("confirmed", [])}
    auto      = meta.get("autoDetected", {})

    fc_start = confirmed.get("FC_START") or auto.get("fcStartElapsedMs")
    fc_end   = confirmed.get("FC_END")   # confirmed-only: auto fc_end is unreliable
    # Only human-verified SC creates an SC label zone. The auto SC time still
    # caps the FC zone (it marks the pull/alarm point) — but on quiet-pull roasts
    # (beans pulled as SC starts) the post-pull cooling noise must NOT be
    # labelled second crack, which would poison the SC class.
    sc_label = confirmed.get("SC_START")
    pull_cap = sc_label or auto.get("scStartElapsedMs")

    if fc_start is None:
        print("    no FC info — skipping session")
        return labels

    # ── Mid-roast restart?  FC sounds from t=0. ──────────────────────────────
    if fc_start < MID_ROAST_THRESHOLD_MS:
        print(f"    mid-roast restart (fc_start={fc_start/1000:.1f}s) — crack-dense from 0")
        fc_end_ms = (pull_cap - 5_000) if pull_cap else \
                    (fc_end + 30_000) if fc_end else \
                    n_frames * (HOP_MS / 1000) * SAMPLE_RATE
        _label_crack_zone(labels, features, 0,
                          min(n_frames, _frame_index(fc_end_ms)), cls=1)
        if sc_label:
            si0 = max(0, _frame_index(sc_label - 15_000))
            si1 = min(n_frames, _frame_index(sc_label + 45_000))
            _label_crack_zone(labels, features, si0, si1, cls=2)
            print(f"    SC crack zone {(sc_label-15000)/1000:.0f}->{(sc_label+45000)/1000:.0f}s")
        return labels

    # ── Normal session: long ambient run-up, then FC ─────────────────────────
    ambient_end_ms = fc_start - AMBIENT_END_BUFFER_S * 1000
    if ambient_end_ms > 30_000:   # at least 30 s of clean ambient
        ai1 = min(n_frames, _frame_index(ambient_end_ms))
        labels[0:ai1] = 0
        print(f"    ambient 0->{ambient_end_ms/1000:.0f}s ({ai1} frames)")

    # FC crack window
    crack_start_ms = max(0, fc_start - CRACK_PRE_S * 1000)
    crack_end_ms   = (fc_end + CRACK_POST_S * 1000) if fc_end else \
                     (fc_start + 90_000)   # FC rolls ~60-90s when end unknown
    if pull_cap:                           # never bleed the FC zone past the pull point
        crack_end_ms = min(crack_end_ms, pull_cap - 5_000)
    ci0 = _frame_index(crack_start_ms)
    ci1 = min(n_frames, _frame_index(crack_end_ms))
    _label_crack_zone(labels, features, ci0, ci1, cls=1)
    print(f"    FC crack zone {crack_start_ms/1000:.0f}->{crack_end_ms/1000:.0f}s")

    # SC crack window — only from human-verified SC
    if sc_label:
        si0 = max(0, _frame_index(sc_label - 15_000))
        si1 = min(n_frames, _frame_index(sc_label + 45_000))
        _label_crack_zone(labels, features, si0, si1, cls=2)
        print(f"    SC crack zone {(sc_label-15000)/1000:.0f}->{(sc_label+45000)/1000:.0f}s")

    return labels


def _label_crack_zone(labels: np.ndarray, features: np.ndarray,
                      ci0: int, ci1: int, cls: int = 1) -> None:
    """
    Within [ci0, ci1), label frames with RMS above median+threshold as [cls]
    (1=FC, 2=SC).  Frames below threshold stay -1 (skipped) to avoid polluting
    negatives with quiet between-pop silence that also occurs in ambient.
    """
    if ci1 <= ci0:
        return
    zone_log_rms = features[ci0:ci1, -2]   # log_rms column
    median_rms   = float(np.median(zone_log_rms))
    threshold    = median_rms + CRACK_RMS_NATS
    for i in range(ci0, ci1):
        if features[i, -2] >= threshold:
            labels[i] = cls


# ── Build full dataset ─────────────────────────────────────────────────────────

def build_dataset():
    X_parts, y_parts = [], []

    json_files = sorted(RAW_DIR.glob("training_*.json"))
    print(f"Found {len(json_files)} sessions in {RAW_DIR}\n")

    for jp in json_files:
        with open(jp) as f:
            meta = json.load(f)

        wav_path = RAW_DIR / meta["audio"]["filename"]
        if not wav_path.exists():
            print(f"SKIP {jp.name} — WAV not found\n")
            continue

        sid = meta.get("sessionId", jp.stem)

        if sid in EXCLUDE_SESSIONS:
            print(f"Session {sid}  — EXCLUDED (unverified auto-labels)\n")
            continue

        print(f"Session {sid}  ({wav_path.stat().st_size // 1_000_000} MB)")

        # Override JSON labels with by-ear ground truth where available.
        if sid in GROUND_TRUTH:
            gt = GROUND_TRUTH[sid]
            meta["confirmed"] = [{"type": k, "elapsedMs": v} for k, v in gt.items()]
            print(f"    using by-ear ground truth: "
                  + ", ".join(f"{k} {v/1000:.0f}s" for k, v in gt.items()))

        audio = load_wav_i16(wav_path)
        print(f"    loaded {len(audio)/SAMPLE_RATE:.1f}s of audio")

        feats  = extract_features(audio)
        labels = label_session(feats, meta)

        # Blank the recorded alarm tone (fires at auto SC) from the ambient and
        # SC classes so it can't pollute them. Never touch FC frames — on early
        # false alarms the tone overlaps the real (loud) FC burst, and we must
        # not throw that away.
        alarm_ms = meta.get("autoDetected", {}).get("scStartElapsedMs")
        if alarm_ms:
            a0 = _frame_index(alarm_ms)
            a1 = min(len(labels), _frame_index(alarm_ms + ALARM_GUARD_MS))
            seg = labels[a0:a1]
            mask = (seg == 0) | (seg == 2)
            n_blanked = int(np.sum(mask))
            seg[mask] = -1
            if n_blanked:
                print(f"    blanked {n_blanked} ambient/SC alarm frames at {alarm_ms/1000:.0f}s")

        n_fc      = int(np.sum(labels == 1))
        n_sc      = int(np.sum(labels == 2))
        n_ambient = int(np.sum(labels == 0))
        print(f"    -> {n_fc} FC frames, {n_sc} SC frames, {n_ambient} ambient frames\n")

        keep = labels >= 0
        if keep.any():
            X_parts.append(feats[keep])
            y_parts.append(labels[keep])

    if not X_parts:
        sys.exit("No labelled frames produced — check paths and JSON content.")

    X = np.concatenate(X_parts, axis=0)
    y = np.concatenate(y_parts, axis=0)
    print(f"Dataset: {len(X):,} frames — "
          f"{int(np.sum(y==0)):,} ambient, {int(np.sum(y==1)):,} FC, {int(np.sum(y==2)):,} SC\n")
    return X, y


# ── Train ──────────────────────────────────────────────────────────────────────

def train_model(X: np.ndarray, y: np.ndarray):
    X_tr, X_val, y_tr, y_val = train_test_split(
        X, y, test_size=0.2, random_state=42, stratify=y
    )

    mean = X_tr.mean(axis=0).astype(np.float32)
    std  = (X_tr.std(axis=0) + 1e-8).astype(np.float32)

    X_tr_n  = (X_tr  - mean) / std
    X_val_n = (X_val - mean) / std

    # Inverse-frequency class weights (ambient hugely dominant; SC rarest).
    counts = {c: int(np.sum(y_tr == c)) for c in (0, 1, 2)}
    total  = sum(counts.values())
    class_weight = {c: total / (3 * max(n, 1)) for c, n in counts.items()}
    print(f"Class counts {counts}")
    print(f"Class weights ambient={class_weight[0]:.2f} "
          f"FC={class_weight[1]:.2f} SC={class_weight[2]:.2f}\n")

    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(X.shape[1],), name="features"),
        tf.keras.layers.Dense(64, activation="relu"),
        tf.keras.layers.Dropout(0.3),
        tf.keras.layers.Dense(32, activation="relu"),
        tf.keras.layers.Dense(3, activation="softmax", name="crack_probs"),
    ], name="crack_detector")

    model.compile(
        optimizer="adam",
        loss="sparse_categorical_crossentropy",
        metrics=["accuracy"],
    )
    model.summary()

    model.fit(
        X_tr_n, y_tr,
        validation_data=(X_val_n, y_val),
        epochs=60,
        batch_size=256,
        class_weight=class_weight,
        callbacks=[
            tf.keras.callbacks.EarlyStopping(
                monitor="val_loss", mode="min",
                patience=10, restore_best_weights=True,
            )
        ],
        verbose=1,
    )

    y_pred = np.argmax(model.predict(X_val_n, verbose=0), axis=1)
    print("\nValidation report:")
    print(classification_report(y_val, y_pred,
          labels=[0, 1, 2], target_names=["ambient", "FC", "SC"], zero_division=0))

    return model, mean, std


# ── Export ─────────────────────────────────────────────────────────────────────

def export(model, mean: np.ndarray, std: np.ndarray):
    MODEL_DIR.mkdir(exist_ok=True)

    # Float32 TFLite — no calibration data needed
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite_bytes = converter.convert()

    tflite_path = MODEL_DIR / "crack_detector.tflite"
    tflite_path.write_bytes(tflite_bytes)
    print(f"\nTFLite saved: {tflite_path}  ({len(tflite_bytes):,} bytes)")

    # Normalization params — Android reads these at runtime
    norm = {
        "mean": mean.tolist(),
        "std":  std.tolist(),
        "n_features": int(mean.shape[0]),
        "n_classes":  3,
        "classes":    ["ambient", "FC", "SC"],
        "n_mfcc":     N_MFCC,
        "frame_ms":   FRAME_MS,
        "hop_ms":     HOP_MS,
        "sample_rate": SAMPLE_RATE,
        "hf_low_hz":   HF_LOW_HZ,
        "hf_high_hz":  HF_HIGH_HZ,
        "audible_hz":  AUDIBLE_HZ,
    }
    norm_path = MODEL_DIR / "feature_norm.json"
    norm_path.write_text(json.dumps(norm, indent=2))
    print(f"Norm params:  {norm_path}")

    print("\nNext steps:")
    print("  copy training_data/model/crack_detector.tflite  -> app/src/main/assets/")
    print("  copy training_data/model/feature_norm.json      -> app/src/main/assets/")


# ── Main ───────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("=== RoastCompanion crack detector trainer ===\n")
    X, y = build_dataset()
    model, mean, std = train_model(X, y)
    export(model, mean, std)
    print("\nDone.")

#!/usr/bin/env python3
"""Extract feature-spec-v2 arrays for every raw/training_*.wav into features/*.npz.
Pure numpy (runs anywhere). Two streams per roast: offset 0 (exactly what the app
sees) and offset FRAME//2 (a second, equally valid framing for more training data).
Usage: python extract.py [--force]"""
import json, sys, wave
from pathlib import Path
import numpy as np
import features as F

RAW = Path(__file__).parent.parent / "raw"
OUT = Path(__file__).parent.parent / "features"
OUT.mkdir(exist_ok=True)

def load_wav_i16(p):
    with wave.open(str(p), "rb") as w:
        assert w.getsampwidth() == 2 and w.getnchannels() == 1 and w.getframerate() == F.SR
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2")

force = "--force" in sys.argv
for jp in sorted(RAW.glob("training_*.json")):
    meta = json.loads(jp.read_text())
    wav = RAW / meta["audio"]["filename"]
    out = OUT / f"{jp.stem}.npz"
    if not wav.exists():
        print("skip (no wav)", jp.name); continue
    if out.exists() and not force:
        print("cached", out.name); continue
    audio = load_wav_i16(wav)
    arrays = {}
    for s, off in enumerate((0, F.FRAME // 2)):
        o = F.stream_features(F.frames_of(audio, off))
        for k, v in o.items():
            arrays[f"s{s}_{k}"] = v
    np.savez_compressed(out, meta=json.dumps(meta), feature_version=F.FEATURE_VERSION, **arrays)
    print(f"{out.name}: {len(audio)/F.SR:.0f}s, {len(arrays['s0_feat'])} frames")

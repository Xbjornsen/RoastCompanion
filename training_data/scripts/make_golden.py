#!/usr/bin/env python3
"""Write app/src/test/resources/feature_golden_v2.txt for FeatureExtractorTest.
The input is a deterministic signal (integer LCG noise + tone + decaying bursts)
that FeatureExtractorTest regenerates bit-for-bit in Kotlin."""
import math, sys
from pathlib import Path
import numpy as np
import features as F

N_FRAMES = 30

def test_signal(n_frames=N_FRAMES):
    x = 12345
    out = np.zeros(n_frames * F.FRAME, np.int16)
    period = F.FRAME * 7
    for i in range(len(out)):
        x = (1103515245 * x + 12345) % 2147483648
        u = x / 2147483648.0 - 0.5
        v = u * 600.0 + 400.0 * math.sin(2.0 * math.pi * 150.0 * i / F.SR)
        k = i % period
        if 700 <= k < 1400:
            v += u * 20000.0 * math.exp(-(k - 700) / 80.0)
        v = int(v)                       # truncate toward zero (== Kotlin toInt())
        out[i] = max(-32768, min(32767, v))
    return out

if __name__ == "__main__":
    dest = Path(sys.argv[1]) if len(sys.argv) > 1 else \
        Path(__file__).resolve().parents[2] / "app/src/test/resources/feature_golden_v2.txt"
    sig = test_signal()
    o = F.stream_features(F.frames_of(sig))
    ctx = F.stack_context(o["feat"])[-1]
    lines = [f"# feature spec v{F.FEATURE_VERSION} golden: {N_FRAMES} frames x ({F.FEATS_PER_FRAME} features + impulsiveness), then the last context vector"]
    lines += [" ".join(f"{v:.9g}" for v in list(row) + [imp]) for row, imp in zip(o["feat"], o["imp"])]
    lines.append(" ".join(f"{v:.9g}" for v in ctx))
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_text("\n".join(lines) + "\n")
    print("wrote", dest)

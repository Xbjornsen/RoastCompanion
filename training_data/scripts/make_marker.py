#!/usr/bin/env python3
"""Cut a slice of a roast WAV and build a self-contained Crack Marker page for it.

  python make_marker.py ../raw/training_1791333543787.wav 9:20 9:55 [note]
  -> ../clips/crack_marker_<stamp>_9m20.html  (open in Chrome/Edge, mark cracks, Copy marks)

Pasted marks are absolute roast seconds; keep them in ../marks/<roast>.tsv.
"""
import base64, io, sys, wave
from pathlib import Path

HERE = Path(__file__).parent


def secs(s):
    m, _, r = s.partition(":")
    return int(m) * 60 + float(r) if r else float(m)


def main():
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    src, a, b = Path(sys.argv[1]), secs(sys.argv[2]), secs(sys.argv[3])
    note = sys.argv[4] if len(sys.argv) > 4 else ""
    w = wave.open(str(src)); sr = w.getframerate()
    w.setpos(int(a * sr)); data = w.readframes(int((b - a) * sr))
    buf = io.BytesIO(); o = wave.open(buf, "wb")
    o.setnchannels(1); o.setsampwidth(2); o.setframerate(sr); o.writeframes(data); o.close()
    t = (HERE / "crack_marker_template.html").read_text(encoding="utf-8")
    t = (t.replace("__NAME__", f"{src.stem} {sys.argv[2]}-{sys.argv[3]}")
          .replace("__OFFSET__", str(a)).replace("__NOTE__", note)
          .replace("__B64__", base64.b64encode(buf.getvalue()).decode())
          .replace("setView(9, 14);   // FC tap is 9 s into this clip", "setView(0, Math.min(dur, 5));"))
    out = HERE.parent / "clips" / f"crack_marker_{src.stem.split('_')[-1]}_{int(a)//60}m{int(a)%60:02d}.html"
    out.write_text(t, encoding="utf-8")
    print(out)


if __name__ == "__main__":
    main()

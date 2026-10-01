#!/usr/bin/env python3
"""
Replay the app's crack detector (rolldet.py == audio/RollDetector.kt) over every
extracted roast and score it against the by-ear labels in labels.py.

  python extract.py        # after adding recordings to raw/ (pure numpy)
  python harness.py        # per-roast table + summary

Scoring: FC ok = fired within -30 s .. +60 s of the by-ear onset (early = false
alarm); SC ok = within -20 s .. +30 s. SC is scored twice: after the app's own FC,
and after a manual FC tap (by-ear FC + 3 s) — the owner's usual flow.
Change detector constants in BOTH rolldet.py and RollDetector.kt; RollDetectorTest
(app/src/test) replays real traces to keep them identical.
"""
import argparse
import numpy as np
import crackml as C
import rolldet as RD


def cls(e, early, late):
    return "miss" if e is None else ("early" if e < early else "ok" if e <= late else "late")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--features", default=str(C.Path(__file__).parent.parent / "features"))
    ap.add_argument("--earliest-fc-min", type=float, default=9.0,
                    help="app setting 'Earliest First Crack' (auto FC floor = this - 60 s)")
    a = ap.parse_args()
    floor = max(0, int(a.earliest_fc_min * 60) - 60)
    S = C.load_sessions(a.features)
    fc_rows, sc_auto, sc_tap = [], [], []
    print(f"{'sess':>4} {'GT FC':>6} {'app FC':>7} {'err':>6} | {'GT SC':>6} {'app SC':>7} {'err':>6} | {'SC after tap':>12}")
    for s in S:
        if s.kind == "negative":
            ev = RD.run(s.streams[0]["imp"], min_fc_s=0)
            print(f"empty-roaster run {s.sid}: {'no events (good)' if not ev else ev}")
            continue
        if s.kind != "gt":
            continue
        g = s.gt
        ev = RD.run(s.streams[0]["imp"], min_fc_s=floor)
        efc = None if "FC" not in ev else (ev["FC"] - g["FC_START"]) / 1000
        fc_rows.append(cls(efc, -30, 60))
        sc = g.get("SC_START")
        esc = etap = None
        if sc:
            esc = None if "SC" not in ev else (ev["SC"] - sc) / 1000
            tap = RD.run(s.streams[0]["imp"], min_fc_s=10**9, force_fc_ms=g["FC_START"] + 3000)
            etap = None if "SC" not in tap else (tap["SC"] - sc) / 1000
            sc_auto.append(cls(esc, -20, 30)); sc_tap.append(cls(etap, -20, 30))
        f = lambda e: "" if e is None else f"{e:+.0f}s"
        print(f"{s.sid:>4} {C.fmt(g['FC_START']):>6} {C.fmt(ev.get('FC')):>7} {f(efc):>6} | "
              f"{C.fmt(sc):>6} {C.fmt(ev.get('SC')):>7} {f(esc):>6} | {f(etap):>12}")
    def summ(name, r):
        print(f"{name}: ok {r.count('ok')}/{len(r)}  early {r.count('early')}  late {r.count('late')}  missed {r.count('miss')}")
    print()
    summ("FC (auto)            ", fc_rows)
    summ("SC after auto FC     ", sc_auto)
    summ("SC after manual FC   ", sc_tap)


if __name__ == "__main__":
    main()

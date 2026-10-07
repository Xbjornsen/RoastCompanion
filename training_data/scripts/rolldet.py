"""Reference implementation of RollDetector (audio/RollDetector.kt is a line-for-line port).
Input: per-frame impulsiveness (features.py 'imp'), 20 frames/s. Output: event times (ms of fed audio)."""
import numpy as np
FPS = 20
P = dict(
    pop_th=3.5,                               # frame is a "pop" if impulsiveness > this (nats)
    fc_win_s=20, fc_tmin_s=240, fc_base_span_s=120, fc_base_gap_s=20,
    fc_ratio=1.7, fc_abs_min=20, fc_hold_s=5, fc_min_base_s=60,
    sc_win_s=10, sc_after_s=90, sc_base_span_s=60, sc_base_gap_s=10,
    sc_ratio=1.3, sc_abs_min=3, sc_hold_s=3,
    fc_end_quiet_s=25,
    # Adaptive pop threshold: from adapt_a..adapt_b s (before any FC is possible) collect
    # impulsiveness; at adapt_b set pop_th = clip(percentile(adapt_q), adapt_lo, pop_th).
    # Quiet phones/placements (2026-10-07: cracks at imp ~2-3) otherwise never register pops.
    adapt_q=99.25, adapt_a=120, adapt_b=360, adapt_lo=2.0,
)
# Settings → "Crack Sensitivity" 1..5 (3 = the validated defaults above). k scales how far
# the pop rate must rise above baseline: ratio' = 1 + (ratio - 1) * k, abs_min' = abs_min * k.
SENS_K = {1: 1.6, 2: 1.3, 3: 1.0, 4: 0.8, 5: 0.6}
def params(sensitivity=3):
    k = SENS_K[sensitivity]; p = dict(P)
    for side in ("fc", "sc"):
        p[f"{side}_ratio"] = 1 + (P[f"{side}_ratio"] - 1) * k
        p[f"{side}_abs_min"] = P[f"{side}_abs_min"] * k
    return p
class RollDetector:
    def __init__(self, p=P, min_fc_s=0):
        self.p = p; self.min_fc_s = min_fc_s
        self.n = 0; self.pops = []           # frame indices of pops (pruned to the longest window)
        self.r_fc = []; self.r_sc = []        # per-second rates
        self.phase = "MON"; self.streak = 0; self.fc_s = None; self.base_fc = 0.0; self.quiet = 0; self.armed = False
        self.events = {}
        self.pop_th = p["pop_th"]; self._cal = []
    def _count(self, win_s):
        lo = self.n - win_s * FPS              # pops in frames (n - win*FPS, n]
        return sum(1 for f in self.pops if f > lo)
    def push(self, imp):
        p = self.p
        if p.get("adapt_q") is not None:
            if p["adapt_a"] * FPS <= self.n < p["adapt_b"] * FPS: self._cal.append(imp)
            elif self.n == p["adapt_b"] * FPS and self._cal:
                self.pop_th = float(min(p["pop_th"], max(p["adapt_lo"], np.percentile(self._cal, p["adapt_q"]))))
                self._cal = []
        if imp > self.pop_th: self.pops.append(self.n)
        keep = self.n - max(p["fc_win_s"], p["sc_win_s"]) * FPS
        while self.pops and self.pops[0] <= keep: self.pops.pop(0)
        if self.n % FPS == FPS - 1:
            s = self.n // FPS
            self.r_fc.append(float(self._count(p["fc_win_s"]))); self.r_sc.append(float(self._count(p["sc_win_s"])))
            self._second(s)
        self.n += 1
    def force_fc(self):
        s = max(0, self.n // FPS); self._enter_fc(s)
    def _baseline(self, R, lo, hi):
        return float(np.median(R[lo:hi]))
    def _enter_fc(self, s):
        p = self.p; self.phase = "FC"; self.fc_s = s; self.streak = 0; self.quiet = 0; self.armed = False
        lo = max(0, s - p["fc_base_span_s"]); hi = s - p["fc_base_gap_s"]
        self.base_fc = self._baseline(self.r_fc, lo, hi) if hi > lo else (self.r_fc[-1] if self.r_fc else 0.0)
    def _second(self, s):
        p = self.p; R = self.r_fc
        if self.phase == "MON":
            if s < max(p["fc_tmin_s"], self.min_fc_s): return
            lo = max(0, s - p["fc_base_span_s"]); hi = s - p["fc_base_gap_s"]
            if hi - lo < p["fc_min_base_s"]: return
            B = self._baseline(R, lo, hi)
            if R[s] >= max(p["fc_ratio"] * B, B + p["fc_abs_min"]):
                self.streak += 1
                if self.streak >= p["fc_hold_s"]:
                    self.events["FC"] = s * 1000.0; self._enter_fc(s)
            else:
                self.streak = 0
            return
        # FC active / complete: FC-end (informational) and SC
        if self.phase == "FC":
            # FC end = the roll died down. Only count quiet once the roll was actually heard
            # above baseline: after a manual tap on a soft recording the rate may never rise,
            # and "25 s of quiet" then fired FC end ~25 s after the tap (2026-10-07 roast).
            if R[s] >= max(1.2 * self.base_fc, self.base_fc + 5): self.armed = True; self.quiet = 0
            elif self.armed: self.quiet += 1
            if self.quiet >= p["fc_end_quiet_s"]:
                self.events["FC_END"] = s * 1000.0; self.phase = "FCC"
        if s < self.fc_s + p["sc_after_s"]: return
        Rs = self.r_sc
        lo = max(self.fc_s, s - p["sc_base_span_s"]); hi = s - p["sc_base_gap_s"]
        if hi <= lo: return
        B = self._baseline(Rs, lo, hi)
        if Rs[s] >= max(p["sc_ratio"] * B, B + p["sc_abs_min"]):
            self.streak += 1
            if self.streak >= p["sc_hold_s"]:
                self.events["SC"] = s * 1000.0; self.phase = "SC"
        else:
            self.streak = 0
    def done(self): return self.phase == "SC"

def run(imp, min_fc_s=0, force_fc_ms=None, p=P):
    d = RollDetector(p, min_fc_s)
    for i, v in enumerate(imp):
        if force_fc_ms is not None and d.phase == "MON" and i * 50 >= force_fc_ms: d.force_fc()
        d.push(float(v))
        if d.done(): break
    return d.events

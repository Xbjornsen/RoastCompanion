package com.roastcompanion.audio

/**
 * First/second-crack detector — line-for-line port of
 * `training_data/scripts/rolldet.py` (validated offline by `harness.py` on the
 * by-ear-labelled roasts; `RollDetectorTest` checks this port against that
 * reference on real recordings' impulsiveness traces).
 *
 * Idea: a crack pop is a 1-5 ms click that shows up as ONE very energetic
 * millisecond inside the 50 ms frame ("impulsiveness", see FeatureExtractor).
 * Beans rattle and the machine clicks too, and how loud all of it is varies ~25x
 * between roasts (phone placement, gain). So instead of absolute thresholds we
 * count pops per second-window and fire when that rate rises well above the
 * roast's OWN recent baseline, and stays up:
 *   FC: pops in the last 20 s >= max(1.7 x baseline, baseline + 20) for 5 s,
 *       baseline = median rate over the 2 min ending 20 s ago, not before 4 min.
 *   SC: from 90 s after FC, pops in the last 10 s >= max(1.3 x baseline, baseline + 3)
 *       for 3 s, baseline = median over the last 60 s (since FC) ending 10 s ago.
 * Crack Sensitivity (1..5) scales the 1.7/20 and 1.3/3 margins; 3 = as above.
 * FC end is informational: the rate falls back near the pre-FC baseline for 25 s.
 *
 * Pure Kotlin, time = frames fed (20/s), so it is deterministic and testable.
 */
class RollDetector(
    /** Auto-FC can't fire before this many seconds of monitored audio (user prior). */
    private val minFcSeconds: Int = 0,
    private val fcEndQuietS: Int = FC_END_QUIET_S,
    /** Settings "Crack Sensitivity" 1..5; 3 = the validated defaults (rolldet.params). */
    sensitivity: Int = DEFAULT_SENSITIVITY,
) {
    enum class Event { FIRST_CRACK, FIRST_CRACK_END, SECOND_CRACK }

    companion object {
        const val FPS = 20
        const val POP_TH = 3.5f
        const val FC_WIN_S = 20
        const val FC_TMIN_S = 240
        const val FC_BASE_SPAN_S = 120
        const val FC_BASE_GAP_S = 20
        const val FC_RATIO = 1.7
        const val FC_ABS_MIN = 20.0
        const val FC_HOLD_S = 5
        const val FC_MIN_BASE_S = 60
        const val SC_WIN_S = 10
        const val SC_AFTER_S = 90
        const val SC_BASE_SPAN_S = 60
        const val SC_BASE_GAP_S = 10
        const val SC_RATIO = 1.3
        const val SC_ABS_MIN = 3.0
        const val SC_HOLD_S = 3
        const val FC_END_QUIET_S = 25
        const val DEFAULT_SENSITIVITY = 3
        /** k scales how far the rate must rise: ratio' = 1 + (ratio - 1) * k, absMin' = absMin * k. */
        private val SENS_K = doubleArrayOf(1.6, 1.3, 1.0, 0.8, 0.6)
        private val MAX_WIN_FRAMES = maxOf(FC_WIN_S, SC_WIN_S) * FPS
    }

    private val k = SENS_K[sensitivity.coerceIn(1, 5) - 1]
    private val fcRatio = 1 + (FC_RATIO - 1) * k
    private val fcAbsMin = FC_ABS_MIN * k
    private val scRatio = 1 + (SC_RATIO - 1) * k
    private val scAbsMin = SC_ABS_MIN * k

    private enum class Phase { MONITORING, FIRST_CRACK, FIRST_CRACK_DONE, SECOND_CRACK }

    private var n = 0L                           // frames fed
    private val pops = ArrayDeque<Long>()        // frame indices of recent pops
    private val rFc = ArrayList<Double>(1024)    // per-second pop counts, 20 s window
    private val rSc = ArrayList<Double>(1024)    // per-second pop counts, 10 s window
    private var phase = Phase.MONITORING
    private var streak = 0
    private var fcSecond = -1
    private var baseFc = 0.0
    private var quiet = 0

    /** Total pops seen since the roast started (for the live counter). */
    var totalPops = 0L
        private set

    val fedMs: Long get() = n * 1000L / FPS

    /** Feed one 50 ms frame's impulsiveness. Returns an event if one fires on this frame. */
    fun push(impulsiveness: Float): Event? {
        if (impulsiveness > POP_TH) { pops.addLast(n); totalPops++ }
        val keep = n - MAX_WIN_FRAMES
        while (pops.isNotEmpty() && pops.first() <= keep) pops.removeFirst()
        var ev: Event? = null
        if (n % FPS == (FPS - 1).toLong()) {
            val s = (n / FPS).toInt()
            rFc.add(countSince(FC_WIN_S).toDouble())
            rSc.add(countSince(SC_WIN_S).toDouble())
            ev = second(s)
        }
        n++
        return ev
    }

    /** Owner tapped "FC start": re-anchor FC (and the SC timing) to now. */
    fun forceFirstCrack() = enterFc((n / FPS).toInt())

    private fun countSince(winS: Int): Int {
        val lo = n - winS * FPS
        var c = 0
        for (f in pops) if (f > lo) c++
        return c
    }

    private fun median(r: List<Double>, lo: Int, hi: Int): Double {
        val a = r.subList(lo, hi).sorted()
        val m = a.size / 2
        return if (a.size % 2 == 1) a[m] else (a[m - 1] + a[m]) / 2.0
    }

    private fun enterFc(s: Int) {
        phase = Phase.FIRST_CRACK; fcSecond = s; streak = 0; quiet = 0
        val lo = maxOf(0, s - FC_BASE_SPAN_S); val hi = s - FC_BASE_GAP_S
        baseFc = if (hi > lo && hi <= rFc.size) median(rFc, lo, hi) else rFc.lastOrNull() ?: 0.0
    }

    private fun second(s: Int): Event? {
        if (phase == Phase.MONITORING) {
            if (s < maxOf(FC_TMIN_S, minFcSeconds)) return null
            val lo = maxOf(0, s - FC_BASE_SPAN_S); val hi = s - FC_BASE_GAP_S
            if (hi - lo < FC_MIN_BASE_S) return null
            val b = median(rFc, lo, hi)
            return if (rFc[s] >= maxOf(fcRatio * b, b + fcAbsMin)) {
                streak++
                if (streak >= FC_HOLD_S) { enterFc(s); Event.FIRST_CRACK } else null
            } else { streak = 0; null }
        }
        if (phase == Phase.SECOND_CRACK) return null
        var ev: Event? = null
        if (phase == Phase.FIRST_CRACK) {
            if (rFc[s] < maxOf(1.2 * baseFc, baseFc + 5)) quiet++ else quiet = 0
            if (quiet >= fcEndQuietS) { phase = Phase.FIRST_CRACK_DONE; ev = Event.FIRST_CRACK_END }
        }
        if (s < fcSecond + SC_AFTER_S) return ev
        val lo = maxOf(fcSecond, s - SC_BASE_SPAN_S); val hi = s - SC_BASE_GAP_S
        if (hi <= lo) return ev
        val b = median(rSc, lo, hi)
        if (rSc[s] >= maxOf(scRatio * b, b + scAbsMin)) {
            streak++
            if (streak >= SC_HOLD_S) { phase = Phase.SECOND_CRACK; return Event.SECOND_CRACK }
        } else streak = 0
        return ev
    }
}

package com.roastcompanion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RollDetector must behave exactly like training_data/scripts/rolldet.py, the
 * reference that harness.py validated against by-ear labels. The two real
 * traces (impulsiveness per frame, from roasts 5 and 23) and their expected
 * events were produced by rolldet.py — if this fails, the phone no longer runs
 * the detector that was measured.
 */
class RollDetectorTest {

    private fun trace(name: String): FloatArray =
        javaClass.classLoader!!.getResource(name)!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.trim().toFloat() }.toFloatArray()

    /** Feeds a trace; returns first-crack / second-crack times in ms of fed audio. */
    private fun run(imp: FloatArray, minFcSeconds: Int, forceFcAtMs: Long? = null, sensitivity: Int = 3): Map<String, Long> {
        val d = RollDetector(minFcSeconds, sensitivity = sensitivity)
        val ev = HashMap<String, Long>()
        var forced = false
        for ((i, v) in imp.withIndex()) {
            if (forceFcAtMs != null && !forced && i * 50L >= forceFcAtMs) { d.forceFirstCrack(); forced = true }
            when (d.push(v)) {
                RollDetector.Event.FIRST_CRACK -> ev["FC"] = (i / RollDetector.FPS) * 1000L
                RollDetector.Event.SECOND_CRACK -> { ev["SC"] = (i / RollDetector.FPS) * 1000L; break }
                else -> Unit
            }
        }
        return ev
    }

    @Test
    fun roast5_matchesReference() {
        val ev = run(trace("roll_trace_s5.txt"), minFcSeconds = 480)
        assertEquals(573_000L, ev["FC"])   // by-ear FC 9:31
        assertEquals(724_000L, ev["SC"])   // by-ear SC 12:13
    }

    @Test
    fun roast23_matchesReference() {
        val ev = run(trace("roll_trace_s23.txt"), minFcSeconds = 480)
        assertEquals(619_000L, ev["FC"])   // by-ear FC 10:15
        assertEquals(734_000L, ev["SC"])   // by-ear SC 12:19
    }

    @Test
    fun sensitivityLevelsMatchReference() {
        // rolldet.run(trace, min_fc_s=480, p=rolldet.params(level)) for levels 1..5
        val s5 = trace("roll_trace_s5.txt")
        val s23 = trace("roll_trace_s23.txt")
        val exp5 = listOf(mapOf("FC" to 585_000L), mapOf("FC" to 578_000L, "SC" to 724_000L),
            mapOf("FC" to 573_000L, "SC" to 724_000L), mapOf("FC" to 572_000L, "SC" to 724_000L),
            mapOf("FC" to 560_000L, "SC" to 683_000L))
        val exp23 = listOf(mapOf("FC" to 753_000L), mapOf("FC" to 624_000L, "SC" to 734_000L),
            mapOf("FC" to 619_000L, "SC" to 734_000L), mapOf("FC" to 611_000L, "SC" to 731_000L),
            mapOf("FC" to 599_000L, "SC" to 729_000L))
        for (level in 1..5) {
            assertEquals("s5 level $level", exp5[level - 1], run(s5, 480, sensitivity = level))
            assertEquals("s23 level $level", exp23[level - 1], run(s23, 480, sensitivity = level))
        }
    }

    @Test
    fun steadyNoiseNeverFires() {
        // A constant sprinkling of pops (bean rattle) at any rate is the baseline, not a crack.
        val imp = FloatArray(20 * 60 * 15) { if (it % 7 == 0) 5f else 1f }
        assertEquals(emptyMap<String, Long>(), run(imp, minFcSeconds = 0))
    }

    @Test
    fun sustainedRiseFiresFirstCrackButNotBeforeTheFloor() {
        // 6 min quiet-ish, then a strong sustained roll.
        val imp = FloatArray(20 * 60 * 9) { i -> if (i < 20 * 360) (if (i % 40 == 0) 5f else 1f) else (if (i % 4 == 0) 5f else 1f) }
        val free = run(imp, minFcSeconds = 0)["FC"]!!
        assertTrue("fired at $free", free in 360_000L..380_000L)
        assertNull(run(imp, minFcSeconds = 600)["FC"])   // floor after the end of the trace
    }
}

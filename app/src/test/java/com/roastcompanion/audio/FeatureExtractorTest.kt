package com.roastcompanion.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Pins FeatureExtractor (on-device) to training_data/scripts/features.py (training).
 * Golden file: app/src/test/resources/feature_golden_v2.txt, written by
 * training_data/scripts/make_golden.py. If this fails, the phone and the model
 * disagree about what the audio looks like — do not ship.
 */
class FeatureExtractorTest {

    private fun testSignal(nFrames: Int): ShortArray {
        var x = 12345L
        val period = FeatureExtractor.FRAME * 7
        return ShortArray(nFrames * FeatureExtractor.FRAME) { i ->
            x = (1103515245L * x + 12345L) % 2147483648L
            val u = x / 2147483648.0 - 0.5
            var v = u * 600.0 + 400.0 * sin(2.0 * PI * 150.0 * i / FeatureExtractor.SR)
            val k = i % period
            if (k in 700 until 1400) v += u * 20000.0 * exp(-(k - 700) / 80.0)
            v.toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun golden(): List<FloatArray> {
        val text = javaClass.classLoader!!.getResource("feature_golden_v2.txt")!!.readText()
        return text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
            .map { l -> l.trim().split(" ").map { it.toFloat() }.toFloatArray() }
    }

    private fun assertClose(msg: String, exp: Float, act: Float) {
        val tol = 1e-3f + 1e-4f * abs(exp)
        if (abs(exp - act) > tol) assertEquals(msg, exp, act, tol)
    }

    @Test
    fun matchesTrainingFeaturesFrameByFrame() {
        val g = golden()
        val nFrames = g.size - 1
        val sig = testSignal(nFrames)
        val fx = FeatureExtractor()
        val frame = ShortArray(FeatureExtractor.FRAME)
        for (f in 0 until nFrames) {
            System.arraycopy(sig, f * FeatureExtractor.FRAME, frame, 0, FeatureExtractor.FRAME)
            val got = fx.push(frame)
            for (j in got.indices) assertClose("frame $f feature $j", g[f][j], got[j])
            assertClose("frame $f impulsiveness", g[f][FeatureExtractor.FEATS_PER_FRAME],
                FeatureExtractor.impulsiveness(frame))
        }
        val ctx = fx.contextVector()
        val gctx = g.last()
        assertEquals(FeatureExtractor.N_INPUT, gctx.size)
        for (j in ctx.indices) assertClose("context $j", gctx[j], ctx[j])
    }

    @Test
    fun contextBeforeFiveFramesRepeatsTheFirstFrame() {
        val fx = FeatureExtractor()
        val sig = testSignal(2)
        val a = fx.push(sig.copyOfRange(0, FeatureExtractor.FRAME))
        val b = fx.push(sig.copyOfRange(FeatureExtractor.FRAME, 2 * FeatureExtractor.FRAME))
        val ctx = fx.contextVector()
        val n = FeatureExtractor.FEATS_PER_FRAME
        for (slot in 0 until 4) for (j in 0 until n) {
            assertEquals(a[j], ctx[slot * n + j], 0f)   // slots t-4..t-1 = first frame
        }
        for (j in 0 until n) assertEquals(b[j], ctx[4 * n + j], 0f)
    }
}

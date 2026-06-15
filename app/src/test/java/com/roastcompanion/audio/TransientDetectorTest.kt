package com.roastcompanion.audio

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class TransientDetectorTest {

    private lateinit var detector: TransientDetector

    @Before
    fun setup() {
        detector = TransientDetector()
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** Generates a sine-wave ShortArray at a given amplitude (0..32767). */
    private fun sineWave(ampShort: Int, freqHz: Int = 440, frames: Int = TransientDetector.AMBIENT_WINDOW_FRAMES): ShortArray {
        val samples = ShortArray(frames * AudioAnalyzerConsts.SAMPLES_PER_WINDOW)
        for (i in samples.indices) {
            val t = i.toDouble() / AudioAnalyzerConsts.SAMPLE_RATE
            samples[i] = (ampShort * sin(2 * PI * freqHz * t)).toInt().toShort()
        }
        return samples
    }

    /** Feed N windows of silence to build ambient baseline. */
    private fun warmUp(frames: Int = TransientDetector.MIN_WARMUP_FRAMES, quietRms: Float = 100f) {
        val quietSamples = ShortArray(AudioAnalyzerConsts.SAMPLES_PER_WINDOW) { (quietRms).toInt().toShort() }
        repeat(frames) { detector.updateAmbient(quietRms) }
    }

    // ── warmup gate ──────────────────────────────────────────────────────────

    @Test fun `not warmed up initially`() {
        assertFalse(detector.isWarmedUp)
    }

    @Test fun `warmed up after MIN_WARMUP_FRAMES updateAmbient calls`() {
        repeat(TransientDetector.MIN_WARMUP_FRAMES) { detector.updateAmbient(100f) }
        assertTrue(detector.isWarmedUp)
    }

    @Test fun `isTransient returns false before warmup regardless of rms`() {
        // No updateAmbient calls yet
        assertFalse(detector.isTransient(99999f, multiplier = 1.0f))
    }

    // ── amplitude gate ───────────────────────────────────────────────────────

    @Test fun `isTransient false when rms equals ambient`() {
        val ambientRms = 200f
        warmUp(quietRms = ambientRms)
        assertFalse(detector.isTransient(ambientRms, multiplier = 2.0f))
    }

    @Test fun `isTransient true when rms exceeds ambient × multiplier`() {
        val ambientRms = 200f
        warmUp(quietRms = ambientRms)
        val crackRms = ambientRms * 2.1f
        assertTrue(detector.isTransient(crackRms, multiplier = 2.0f))
    }

    @Test fun `isTransient false when rms is below threshold`() {
        val ambientRms = 200f
        warmUp(quietRms = ambientRms)
        val belowThreshold = ambientRms * 1.9f
        assertFalse(detector.isTransient(belowThreshold, multiplier = 2.0f))
    }

    // ── ambient estimation ───────────────────────────────────────────────────

    @Test fun `ambient tracks quiet level over time`() {
        val quietRms = 300f
        warmUp(quietRms = quietRms)
        // Ambient should be close to the quiet level (within 5%)
        assertEquals(quietRms, detector.ambientRms, quietRms * 0.05f)
    }

    @Test fun `ambient ignores top 30% of frames (spike resistance)`() {
        val quietRms = 200f
        // Feed mostly quiet frames, then a few loud ones
        repeat(70) { detector.updateAmbient(quietRms) }
        repeat(30) { detector.updateAmbient(quietRms * 10f) }   // spikes

        // Ambient should still reflect the quiet level, not the spikes
        // (uses bottom 70% percentile)
        assertTrue(
            "Ambient ${detector.ambientRms} should be close to quiet $quietRms",
            detector.ambientRms < quietRms * 2f
        )
    }

    // ── reset ────────────────────────────────────────────────────────────────

    @Test fun `reset clears warmup and ambient`() {
        warmUp()
        assertTrue(detector.isWarmedUp)
        detector.reset()
        assertFalse(detector.isWarmedUp)
        assertEquals(1f, detector.ambientRms, 0.01f)
    }
}

/** Constants duplicated here to avoid importing android.media.AudioFormat in unit tests. */
private object AudioAnalyzerConsts {
    const val SAMPLE_RATE = 44100
    const val SAMPLES_PER_WINDOW = SAMPLE_RATE * 50 / 1000  // 50ms @ 44.1kHz = 2205
}

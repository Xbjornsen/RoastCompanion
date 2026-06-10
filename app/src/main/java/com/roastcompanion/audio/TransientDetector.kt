package com.roastcompanion.audio

import kotlin.math.sqrt

class TransientDetector {

    companion object {
        // Keep 5 seconds of history at 20 fps
        const val AMBIENT_WINDOW_FRAMES = 100
        // Use the quieter 70% of frames to estimate noise floor,
        // so crack events don't inflate the ambient estimate.
        const val AMBIENT_PERCENTILE = 0.70
        // Do NOT detect transients until we have a proper ambient baseline.
        // 60 frames = 3 seconds at 20fps. Prevents false triggers on session start
        // (ambient initialises at 1f, so any real audio would fire immediately).
        const val MIN_WARMUP_FRAMES = 60
    }

    private val ambientWindow = ArrayDeque<Float>(AMBIENT_WINDOW_FRAMES)
    private var _ambientRms = 1f
    val ambientRms: Float get() = _ambientRms

    // Counts frames fed to updateAmbient() since last reset.
    // isTransient() returns false until warmup is complete.
    private var warmUpFrameCount = 0
    val isWarmedUp: Boolean get() = warmUpFrameCount >= MIN_WARMUP_FRAMES

    fun computeRms(samples: ShortArray, count: Int = samples.size): Float {
        if (count == 0) return 0f
        var sumOfSquares = 0.0
        for (i in 0 until count) {
            val s = samples[i].toDouble()
            sumOfSquares += s * s
        }
        return sqrt(sumOfSquares / count).toFloat()
    }

    /** Update ambient noise floor. Call only on non-spike frames. */
    fun updateAmbient(rms: Float) {
        warmUpFrameCount++
        if (ambientWindow.size >= AMBIENT_WINDOW_FRAMES) ambientWindow.removeFirst()
        ambientWindow.addLast(rms)

        val sorted = ambientWindow.sorted()
        val cutoff = (sorted.size * AMBIENT_PERCENTILE).toInt().coerceAtLeast(1)
        _ambientRms = sorted.take(cutoff).average().toFloat().coerceAtLeast(1f)
    }

    /**
     * Returns true only if:
     *  1. Warmup is complete (3 seconds of ambient accumulation), AND
     *  2. rms exceeds ambientRms × multiplier
     */
    fun isTransient(rms: Float, multiplier: Float): Boolean {
        if (warmUpFrameCount < MIN_WARMUP_FRAMES) return false
        return rms > _ambientRms * multiplier
    }

    fun reset() {
        ambientWindow.clear()
        _ambientRms = 1f
        warmUpFrameCount = 0
    }
}

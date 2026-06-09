package com.roastcompanion.audio

import kotlin.math.sqrt

class TransientDetector {

    companion object {
        // Keep 5 seconds of history at 20 fps
        const val AMBIENT_WINDOW_FRAMES = 100
        // Use the quieter 70% of frames to estimate noise floor,
        // so crack events don't inflate the ambient estimate.
        const val AMBIENT_PERCENTILE = 0.70
    }

    private val ambientWindow = ArrayDeque<Float>(AMBIENT_WINDOW_FRAMES)
    private var _ambientRms = 1f
    val ambientRms: Float get() = _ambientRms

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
        if (ambientWindow.size >= AMBIENT_WINDOW_FRAMES) ambientWindow.removeFirst()
        ambientWindow.addLast(rms)

        val sorted = ambientWindow.sorted()
        val cutoff = (sorted.size * AMBIENT_PERCENTILE).toInt().coerceAtLeast(1)
        _ambientRms = sorted.take(cutoff).average().toFloat().coerceAtLeast(1f)
    }

    fun isTransient(rms: Float, multiplier: Float): Boolean =
        rms > _ambientRms * multiplier

    fun reset() {
        ambientWindow.clear()
        _ambientRms = 1f
    }
}

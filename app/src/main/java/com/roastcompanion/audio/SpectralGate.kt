package com.roastcompanion.audio

import kotlin.math.cos
import kotlin.math.PI

/**
 * FFT-based spectral check that distinguishes crack-like sounds from
 * other loud transients.
 *
 * Coffee cracks are short broadband pops with elevated energy in the
 * 2–9 kHz band. However, in a drum-roaster environment (Gene Cafe CBR-101)
 * the motor/drum/fan noise keeps low-frequency energy high continuously,
 * and crack sounds travel through drum walls and bean mass which attenuates
 * highs — so the crack-band fraction of a genuine crack in this environment
 * is 0.20–0.35, not the 0.50+ you'd see in a quiet room.
 * The threshold is set accordingly. Pure low-frequency events (thuds, bangs
 * on the table) still score near 0.0 and are correctly rejected.
 */
class SpectralGate {

    companion object {
        // 2048-point FFT over the 2205-sample window (50ms @ 44.1kHz).
        // Bin width = 44100 / 2048 ≈ 21.5 Hz.
        const val FFT_SIZE = 2048
        const val SAMPLE_RATE = 44100

        // Crack energy band
        const val BAND_LOW_HZ = 2_000
        const val BAND_HIGH_HZ = 9_000

        // Ignore everything below this when summing total energy — DC
        // offset and sub-audible rumble shouldn't influence the ratio.
        const val FLOOR_HZ = 150

        /**
         * Minimum fraction of (150Hz..Nyquist) energy that must fall in
         * the 2–9 kHz band for a frame to count as crack-like.
         * 0.20 = 20%: passes drum-roaster cracks (0.20–0.35 in practice),
         * rejects pure low-frequency thuds/bangs (near 0.0).
         */
        const val CRACK_BAND_MIN_RATIO = 0.20f
    }

    private val real = DoubleArray(FFT_SIZE)
    private val imag = DoubleArray(FFT_SIZE)
    private val window = DoubleArray(FFT_SIZE) { i ->
        // Hann window — reduces spectral leakage
        0.5 * (1 - cos(2.0 * PI * i / (FFT_SIZE - 1)))
    }

    private val binLow = BAND_LOW_HZ * FFT_SIZE / SAMPLE_RATE      // ≈ 93
    private val binHigh = BAND_HIGH_HZ * FFT_SIZE / SAMPLE_RATE    // ≈ 417
    private val binFloor = (FLOOR_HZ * FFT_SIZE / SAMPLE_RATE).coerceAtLeast(1) // ≈ 7

    /**
     * Returns the fraction of audible energy concentrated in the crack
     * band (0..1). Call only on frames that already passed the amplitude
     * threshold — the FFT costs ~0.1ms but there's no reason to run it
     * on quiet frames.
     */
    fun crackBandRatio(samples: ShortArray, count: Int): Float {
        val n = minOf(count, FFT_SIZE)
        for (i in 0 until n) {
            real[i] = samples[i] * window[i]
            imag[i] = 0.0
        }
        for (i in n until FFT_SIZE) {
            real[i] = 0.0
            imag[i] = 0.0
        }

        fft(real, imag)

        var bandEnergy = 0.0
        var totalEnergy = 0.0
        for (bin in binFloor until FFT_SIZE / 2) {
            val mag = real[bin] * real[bin] + imag[bin] * imag[bin]
            totalEnergy += mag
            if (bin in binLow..binHigh) bandEnergy += mag
        }

        if (totalEnergy <= 0.0) return 0f
        return (bandEnergy / totalEnergy).toFloat()
    }

    /** True if the frame's spectrum looks like a coffee crack. */
    fun isCrackLike(samples: ShortArray, count: Int): Boolean =
        crackBandRatio(samples, count) >= CRACK_BAND_MIN_RATIO

    /**
     * In-place iterative radix-2 Cooley–Tukey FFT.
     */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size

        // Bit-reversal permutation
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var tmp = re[i]; re[i] = re[j]; re[j] = tmp
                tmp = im[i]; im[i] = im[j]; im[j] = tmp
            }
            var m = n shr 1
            while (m in 1..j) {
                j -= m
                m = m shr 1
            }
            j += m
        }

        // Butterfly passes
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang)
            val wIm = kotlin.math.sin(ang)
            var i = 0
            while (i < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val aRe = re[i + k]
                    val aIm = im[i + k]
                    val bRe = re[i + k + len / 2] * curRe - im[i + k + len / 2] * curIm
                    val bIm = re[i + k + len / 2] * curIm + im[i + k + len / 2] * curRe
                    re[i + k] = aRe + bRe
                    im[i + k] = aIm + bIm
                    re[i + k + len / 2] = aRe - bRe
                    im[i + k + len / 2] = aIm - bIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                i += len
            }
            len = len shl 1
        }
    }
}

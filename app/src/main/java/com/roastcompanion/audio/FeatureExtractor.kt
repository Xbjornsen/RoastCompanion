package com.roastcompanion.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Crack-detector feature spec v2 — line-for-line port of
 * `training_data/scripts/features.py` (the single source of truth).
 * `FeatureExtractorTest` pins this to golden vectors from `make_golden.py`;
 * change both together, bump [FEATURE_VERSION], regenerate the golden file, retrain.
 *
 * Call [push] for EVERY 50 ms frame (not just loud ones): flux, the ambient
 * floor and the 5-frame context all depend on the frames before. Then
 * [contextVector] is the (unnormalised) model input for the latest frame.
 *
 * Pure Kotlin (no Android APIs) so it runs in plain JVM unit tests.
 */
class FeatureExtractor {

    companion object {
        const val FEATURE_VERSION = 2
        const val SR = 44100
        const val FRAME = 2205
        const val N_FFT = 4096
        const val N_MELS = 40
        const val N_MFCC_KEEP = 12
        const val AMBIENT_FRAMES = 100
        const val AMBIENT_PCT = 0.70
        const val CONTEXT_FRAMES = 5
        const val FEATS_PER_FRAME = 16
        const val N_INPUT = CONTEXT_FRAMES * FEATS_PER_FRAME
        const val IMP_BLOCK = 44          // 1 ms
        const val IMP_BLOCKS = 50         // samples i = 2..2201
        private const val N_BINS = N_FFT / 2 + 1
        private val SPEC_FLOOR = (200.0 / SR * N_FFT).toInt()    // 18
        private val SPEC_LOW = (2000.0 / SR * N_FFT).toInt()     // 185
        private val SPEC_HIGH = (9000.0 / SR * N_FFT).toInt()    // 835

        private val HANN = DoubleArray(FRAME) { 0.5 * (1.0 - cos(2.0 * PI * it / (FRAME - 1))) }

        private fun hzToMel(hz: Double): Double {
            val fSp = 200.0 / 3.0; val minLogHz = 1000.0; val minLogMel = minLogHz / fSp
            val logStep = ln(6.4) / 27.0
            return if (hz >= minLogHz) minLogMel + ln(hz / minLogHz) / logStep else hz / fSp
        }

        private fun melToHz(mel: Double): Double {
            val fSp = 200.0 / 3.0; val minLogHz = 1000.0; val minLogMel = minLogHz / fSp
            val logStep = ln(6.4) / 27.0
            return if (mel >= minLogMel) minLogHz * exp(logStep * (mel - minLogMel)) else fSp * mel
        }

        /** Slaney-normalised filterbank == librosa.filters.mel(44100, 4096, n_mels=40). Dense [mel][bin]. */
        private val MEL_FB: Array<DoubleArray> = run {
            val fftFreqs = DoubleArray(N_BINS) { it * (SR / 2.0) / (N_BINS - 1) }
            val mMin = hzToMel(0.0); val mMax = hzToMel(SR / 2.0)
            val melF = DoubleArray(N_MELS + 2) { melToHz(mMin + it * (mMax - mMin) / (N_MELS + 1)) }
            Array(N_MELS) { i ->
                val fd0 = melF[i + 1] - melF[i]
                val fd1 = melF[i + 2] - melF[i + 1]
                val enorm = 2.0 / (melF[i + 2] - melF[i])
                DoubleArray(N_BINS) { b ->
                    val lower = -(melF[i] - fftFreqs[b]) / fd0
                    val upper = (melF[i + 2] - fftFreqs[b]) / fd1
                    max(0.0, min(lower, upper)) * enorm
                }
            }
        }
        // First/last non-zero bin per mel band (skip zeros in the hot loop)
        private val MEL_LO = IntArray(N_MELS) { m -> MEL_FB[m].indexOfFirst { it > 0.0 }.coerceAtLeast(0) }
        private val MEL_HI = IntArray(N_MELS) { m -> MEL_FB[m].indexOfLast { it > 0.0 }.coerceAtLeast(0) }

        /**
         * Impulsiveness of one 50 ms frame (features.py `imp`): energy of the 2nd
         * difference x[i]-2x[i-1]+x[i-2] in 50 blocks of 1 ms; ln((max+1e-9)/(median+1e-9)).
         * A crack pop is one or two blocks far above the rest; fan/drum noise is flat
         * at this timescale. This is the only per-frame input RollDetector needs.
         * Samples beyond [count] (a short read) are treated as silence.
         */
        fun impulsiveness(samples: ShortArray, count: Int = samples.size): Float {
            val e = DoubleArray(IMP_BLOCKS)
            fun at(i: Int): Double = if (i < count) samples[i].toDouble() else 0.0
            for (b in 0 until IMP_BLOCKS) {
                var sum = 0.0
                for (j in 0 until IMP_BLOCK) {
                    val i = 2 + b * IMP_BLOCK + j
                    val d = at(i) - 2.0 * at(i - 1) + at(i - 2)
                    sum += d * d
                }
                e[b] = sum / IMP_BLOCK
            }
            var mx = 0.0
            for (v in e) if (v > mx) mx = v
            e.sort()
            val med = (e[IMP_BLOCKS / 2 - 1] + e[IMP_BLOCKS / 2]) / 2.0
            return ln((mx + 1e-9) / (med + 1e-9)).toFloat()
        }

        /** DCT-II ortho rows c1..c12 over the 40 log-mel bands. */
        private val DCT: Array<DoubleArray> = Array(N_MFCC_KEEP) { r ->
            val c = r + 1
            DoubleArray(N_MELS) { k -> cos(PI * c * (2 * k + 1) / (2.0 * N_MELS)) * sqrt(2.0 / N_MELS) }
        }
    }

    private val re = DoubleArray(N_FFT)
    private val im = DoubleArray(N_FFT)
    private val logMel = DoubleArray(N_MELS)
    private val prevLogMel = DoubleArray(N_MELS)
    private var hasPrev = false
    private val rmsHist = ArrayDeque<Double>(AMBIENT_FRAMES + 1)
    private val context = Array(CONTEXT_FRAMES) { FloatArray(FEATS_PER_FRAME) }
    private var firstFrame: FloatArray? = null
    private var framesSeen = 0

    fun reset() {
        hasPrev = false
        rmsHist.clear()
        firstFrame = null
        framesSeen = 0
    }

    /** Computes and stores the 16 per-frame features. Returns them (a copy). */
    fun push(samples: ShortArray, count: Int = samples.size): FloatArray {
        val n = min(count, FRAME)   // frames shorter than 50 ms are zero-padded
        val f = FloatArray(FEATS_PER_FRAME)

        var sumSq = 0.0; var peak = 0.0
        for (i in 0 until n) {
            val s = samples[i].toDouble()
            sumSq += s * s
            val a = abs(s); if (a > peak) peak = a
        }
        val rms = sqrt(sumSq / FRAME)
        f[15] = ln((peak + 1.0) / (rms + 1.0)).toFloat()

        for (i in 0 until N_FFT) { re[i] = 0.0; im[i] = 0.0 }
        for (i in 0 until n) re[i] = samples[i] / 32768.0 * HANN[i]
        fft(re, im)
        // power spectrum into re[0..N_BINS)
        for (b in 0 until N_BINS) re[b] = re[b] * re[b] + im[b] * im[b]

        var tot = 1e-10; var band = 0.0
        for (b in SPEC_FLOOR until N_BINS) tot += re[b]
        for (b in SPEC_LOW..SPEC_HIGH) band += re[b]
        f[13] = (band / tot).toFloat()

        for (m in 0 until N_MELS) {
            var e = 0.0
            val w = MEL_FB[m]
            for (b in MEL_LO[m]..MEL_HI[m]) e += re[b] * w[b]
            logMel[m] = 10.0 * log10(max(e, 1e-10))
        }
        for (r in 0 until N_MFCC_KEEP) {
            var s = 0.0
            val d = DCT[r]
            for (k in 0 until N_MELS) s += logMel[k] * d[k]
            f[r] = s.toFloat()
        }
        if (hasPrev) {
            var fl = 0.0
            for (k in 0 until N_MELS) fl += max(0.0, logMel[k] - prevLogMel[k])
            f[14] = (fl / N_MELS).toFloat()
        }
        System.arraycopy(logMel, 0, prevLogMel, 0, N_MELS)
        hasPrev = true

        // relative loudness vs the quietest 70% of the previous <=100 frames
        val amb = if (rmsHist.isEmpty()) rms else {
            val sorted = rmsHist.sorted()
            val cut = max(1, (sorted.size * AMBIENT_PCT).toInt())
            var s = 0.0
            for (i in 0 until cut) s += sorted[i]
            s / cut
        }
        f[12] = ln((rms + 1.0) / (amb + 1.0)).toFloat()
        rmsHist.addLast(rms)
        if (rmsHist.size > AMBIENT_FRAMES) rmsHist.removeFirst()

        // context ring: shift left, newest last
        for (i in 0 until CONTEXT_FRAMES - 1) System.arraycopy(context[i + 1], 0, context[i], 0, FEATS_PER_FRAME)
        System.arraycopy(f, 0, context[CONTEXT_FRAMES - 1], 0, FEATS_PER_FRAME)
        if (firstFrame == null) firstFrame = f.copyOf()
        framesSeen++
        return f.copyOf()
    }

    /** Model input for the latest frame: frames t-4..t oldest first (80 values). Before
     *  5 frames exist, missing slots repeat the very first frame (matches stack_context). */
    fun contextVector(out: FloatArray = FloatArray(N_INPUT)): FloatArray {
        val have = min(framesSeen, CONTEXT_FRAMES)
        val missing = CONTEXT_FRAMES - have
        val first = firstFrame ?: return out
        for (slot in 0 until CONTEXT_FRAMES) {
            val src = if (slot < missing) first else context[slot]
            System.arraycopy(src, 0, out, slot * FEATS_PER_FRAME, FEATS_PER_FRAME)
        }
        return out
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
            var m = n shr 1
            while (m in 1..j) { j -= m; m = m shr 1 }
            j += m
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang); val wIm = sin(ang)
            var i = 0
            while (i < n) {
                var cRe = 1.0; var cIm = 0.0
                val half = len / 2
                for (k in 0 until half) {
                    val xr = re[i + k + half] * cRe - im[i + k + half] * cIm
                    val xi = re[i + k + half] * cIm + im[i + k + half] * cRe
                    re[i + k + half] = re[i + k] - xr; im[i + k + half] = im[i + k] - xi
                    re[i + k] += xr; im[i + k] += xi
                    val nr = cRe * wRe - cIm * wIm; cIm = cRe * wIm + cIm * wRe; cRe = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

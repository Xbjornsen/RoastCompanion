package com.roastcompanion.audio

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import kotlin.math.*

/**
 * Wraps the TFLite crack detector model.
 *
 * Input:  one 2205-sample (50ms @ 44100 Hz) ShortArray frame
 * Output: probability in [0, 1] that the frame contains a crack sound
 *
 * Feature vector (15 values, same order as training script):
 *   [0-12]  13 MFCCs   (Hann window → 4096-pt FFT → 128-band Slaney mel → log → DCT-II ortho)
 *   [13]    log RMS    = ln(sqrt(mean(samples_i16^2)) + 1.0)
 *   [14]    spec ratio = energy(2-9 kHz) / energy(200 Hz+)   (4096-pt FFT)
 * Each value is z-scored using mean/std from feature_norm.json.
 */
class CrackClassifier(private val context: Context) {

    companion object {
        private const val TAG = "CrackClassifier"
        private const val MODEL_FILE = "crack_detector.tflite"
        private const val NORM_FILE = "feature_norm.json"

        private const val N_FFT = 4096          // matches training N_FFT
        private const val N_MELS = 128
        private const val N_MFCC = 13
        private const val SR = 44100
        private const val FRAME_SAMPLES = 2205  // 50 ms @ 44100 Hz

        private const val HF_LOW = 2000.0
        private const val HF_HIGH = 9000.0
        private const val AUDIBLE = 200.0

        // Output class indices (softmax order from feature_norm.json "classes")
        const val CLASS_AMBIENT = 0
        const val CLASS_FC = 1
        const val CLASS_SC = 2
        const val N_CLASSES = 3
    }

    private var interpreter: Interpreter? = null
    private val mean = FloatArray(N_MFCC + 2)
    private val std = FloatArray(N_MFCC + 2)

    // Pre-computed Hann window coefficients for the frame length
    private val hannWindow = FloatArray(FRAME_SAMPLES) { i ->
        (0.5 * (1.0 - cos(2.0 * PI * i / (FRAME_SAMPLES - 1)))).toFloat()
    }

    // Mel filterbank stored sparsely: for each mel band, the first FFT bin
    // and the triangle weights from that bin onward.
    private val melStart = IntArray(N_MELS)
    private val melWeights = Array(N_MELS) { FloatArray(0) }

    // FFT bin ranges for spectral ratio (mirroring training's audible/HF masks)
    private val nBins = N_FFT / 2 + 1
    private val specFloor = (AUDIBLE / SR * N_FFT).toInt()
    private val specLow   = (HF_LOW  / SR * N_FFT).toInt()
    private val specHigh  = (HF_HIGH / SR * N_FFT).toInt()

    // Per-call working arrays (not thread-safe, but called only from recording thread)
    private val fftRe = DoubleArray(N_FFT)
    private val fftIm = DoubleArray(N_FFT)
    private val logMel = DoubleArray(N_MELS)
    private val inputBuf = Array(1) { FloatArray(N_MFCC + 2) }
    private val outputBuf = Array(1) { FloatArray(N_CLASSES) }

    val isLoaded get() = interpreter != null

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    fun load() {
        try {
            val bytes = context.assets.open(MODEL_FILE).readBytes()
            interpreter = Interpreter(ByteBuffer.wrap(bytes))

            val json = JSONObject(context.assets.open(NORM_FILE).bufferedReader().readText())
            val mArr = json.getJSONArray("mean")
            val sArr = json.getJSONArray("std")
            for (i in 0 until N_MFCC + 2) {
                mean[i] = mArr.getDouble(i).toFloat()
                std[i]  = sArr.getDouble(i).toFloat()
            }

            buildMelFilterbank()
            Log.d(TAG, "loaded: model=${bytes.size} bytes, ${N_MELS} mel filters")
        } catch (e: Exception) {
            Log.e(TAG, "load failed", e)
        }
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }

    // ── Inference ──────────────────────────────────────────────────────────────

    /**
     * Returns softmax probabilities [ambient, FC, SC] for [samples], or null if
     * the model isn't loaded. Only called on frames that already passed the
     * amplitude + spectral gates.
     */
    fun classify(samples: ShortArray, count: Int = samples.size): FloatArray? {
        val interp = interpreter ?: return null
        val n = minOf(count, FRAME_SAMPLES)

        // 1. Log RMS on raw int16 (no windowing — matches training)
        var sumSq = 0.0
        for (i in 0 until n) { val s = samples[i].toDouble(); sumSq += s * s }
        val logRms = ln(sqrt(sumSq / n) + 1.0)

        // 2. Windowed + zero-padded FFT
        for (i in 0 until n)       { fftRe[i] = samples[i].toDouble() * hannWindow[i]; fftIm[i] = 0.0 }
        for (i in n until N_FFT)   { fftRe[i] = 0.0;                         fftIm[i] = 0.0 }
        fft(fftRe, fftIm)

        // 3. Spectral ratio: energy(HF_LOW..HF_HIGH) / energy(AUDIBLE..)
        var hfE = 0.0; var totE = 1e-10
        for (i in specFloor until nBins) {
            val p = fftRe[i] * fftRe[i] + fftIm[i] * fftIm[i]
            totE += p
            if (i in specLow..specHigh) hfE += p
        }
        val specRatio = (hfE / totE).toFloat()

        // 4. Mel filterbank → log energy
        for (m in 0 until N_MELS) {
            val start = melStart[m]
            val w = melWeights[m]
            var e = 0.0
            for (k in w.indices) {
                val bin = start + k
                if (bin < nBins) {
                    val p = fftRe[bin] * fftRe[bin] + fftIm[bin] * fftIm[bin]
                    e += p * w[k]
                }
            }
            logMel[m] = 10.0 * log10(max(e, 1e-10))
        }

        // 5. DCT-II ortho → first N_MFCC coefficients
        val sqrtInv  = sqrt(1.0 / N_MELS)
        val sqrt2Inv = sqrt(2.0 / N_MELS)
        val inp = inputBuf[0]
        for (k in 0 until N_MFCC) {
            var sum = 0.0
            val coeff = PI * k / (2.0 * N_MELS)
            for (i in 0 until N_MELS) sum += logMel[i] * cos(coeff * (2 * i + 1))
            inp[k] = (sum * if (k == 0) sqrtInv else sqrt2Inv).toFloat()
        }
        inp[N_MFCC]     = logRms.toFloat()
        inp[N_MFCC + 1] = specRatio

        // 6. Z-score normalize
        for (i in inp.indices) inp[i] = (inp[i] - mean[i]) / std[i]

        // 7. Run model
        interp.run(inputBuf, outputBuf)
        return outputBuf[0].copyOf()
    }

    // ── Mel filterbank (Slaney/O'Shaughnessy scale, htk=False — matches librosa default) ──

    private fun buildMelFilterbank() {
        val melMin = hzToMel(0.0)
        val melMax = hzToMel(SR / 2.0)

        // N_MELS + 2 equally-spaced mel points → Hz → FFT bin
        val bins = IntArray(N_MELS + 2) { i ->
            val mel = melMin + i.toDouble() * (melMax - melMin) / (N_MELS + 1)
            (melToHz(mel) * (N_FFT + 1) / SR).toInt().coerceIn(0, nBins - 1)
        }

        for (m in 0 until N_MELS) {
            val bL = bins[m]; val bC = bins[m + 1]; val bR = bins[m + 2]
            melStart[m] = bL
            val len = (minOf(bR, nBins - 1) - bL + 1).coerceAtLeast(0)
            val w = FloatArray(len)
            for (k in 0 until len) {
                val bin = bL + k
                w[k] = when {
                    bin < bC && bC > bL  -> (bin - bL).toFloat() / (bC - bL)
                    bin >= bC && bR > bC -> (bR - bin).toFloat() / (bR - bC)
                    else -> 0f
                }
            }
            melWeights[m] = w
        }
    }

    private fun hzToMel(hz: Double): Double {
        val fSp = 200.0 / 3.0; val minLogHz = 1000.0
        val minLogMel = minLogHz / fSp
        val logStep = ln(6.4) / 27.0
        return if (hz < minLogHz) hz / fSp else minLogMel + ln(hz / minLogHz) / logStep
    }

    private fun melToHz(mel: Double): Double {
        val fSp = 200.0 / 3.0; val minLogHz = 1000.0
        val minLogMel = minLogHz / fSp
        val logStep = ln(6.4) / 27.0
        return if (mel < minLogMel) mel * fSp else minLogHz * exp(logStep * (mel - minLogMel))
    }

    // ── In-place radix-2 Cooley–Tukey FFT (same algorithm as SpectralGate) ──────

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
                for (k in 0 until len / 2) {
                    val aRe = re[i+k]; val aIm = im[i+k]
                    val bRe = re[i+k+len/2]*cRe - im[i+k+len/2]*cIm
                    val bIm = re[i+k+len/2]*cIm + im[i+k+len/2]*cRe
                    re[i+k] = aRe+bRe;           im[i+k] = aIm+bIm
                    re[i+k+len/2] = aRe-bRe;     im[i+k+len/2] = aIm-bIm
                    val nr = cRe*wRe - cIm*wIm;  cIm = cRe*wIm + cIm*wRe;  cRe = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

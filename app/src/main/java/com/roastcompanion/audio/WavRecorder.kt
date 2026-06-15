package com.roastcompanion.audio

import java.io.File
import java.io.RandomAccessFile

/**
 * Writes PCM audio to a WAV file. WAV header is written on open() with placeholder
 * sizes; close() seeks back and patches the real lengths.
 */
class WavRecorder(private val outputFile: File, private val sampleRate: Int) {

    private var raf: RandomAccessFile? = null
    private var dataBytes = 0L

    fun open() {
        val r = RandomAccessFile(outputFile, "rw")
        r.setLength(0)
        raf = r
        writeWavHeader(r)
    }

    fun write(samples: ShortArray, count: Int) {
        val r = raf ?: return
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples[i].toInt()
            bytes[i * 2]     = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
        }
        r.write(bytes)
        dataBytes += bytes.size
    }

    /** Finalises RIFF/data size fields and closes. Returns written byte count. */
    fun close(): Long {
        val r = raf ?: return 0L
        r.seek(4)
        writeLe32(r, (36L + dataBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        r.seek(40)
        writeLe32(r, dataBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        r.close()
        raf = null
        return dataBytes
    }

    private fun writeWavHeader(r: RandomAccessFile) {
        r.write("RIFF".toByteArray(Charsets.US_ASCII))
        writeLe32(r, 0)                   // placeholder: file size - 8
        r.write("WAVE".toByteArray(Charsets.US_ASCII))
        r.write("fmt ".toByteArray(Charsets.US_ASCII))
        writeLe32(r, 16)                  // PCM fmt chunk is always 16 bytes
        writeLe16(r, 1)                   // PCM format
        writeLe16(r, 1)                   // mono
        writeLe32(r, sampleRate)
        writeLe32(r, sampleRate * 2)      // byte rate = sampleRate * channels * (bits/8)
        writeLe16(r, 2)                   // block align = channels * (bits/8)
        writeLe16(r, 16)                  // bits per sample
        r.write("data".toByteArray(Charsets.US_ASCII))
        writeLe32(r, 0)                   // placeholder: data size
    }

    private fun writeLe32(r: RandomAccessFile, value: Int) {
        r.write(byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        ))
    }

    private fun writeLe16(r: RandomAccessFile, value: Int) {
        r.write(byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte()
        ))
    }
}

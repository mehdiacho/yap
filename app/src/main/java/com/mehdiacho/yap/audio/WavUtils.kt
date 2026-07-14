package com.mehdiacho.yap.audio

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

/**
 * Minimal WAV helpers for append-to-note. All Yap recordings share one format
 * (16 kHz mono 16-bit PCM, 44-byte canonical header), so appending is: copy the
 * clip's data chunk onto the original and rewrite the sizes in the header.
 */
object WavUtils {
    private const val HEADER = 44L

    /** True when the file plausibly is one of our canonical WAVs. */
    fun isYapWav(file: File): Boolean =
        file.exists() && file.length() > HEADER && file.name.endsWith(".wav")

    /**
     * Appends [clip]'s audio data onto [base] in place and fixes the RIFF/data sizes.
     * Both must be canonical Yap WAVs. Returns the new total data length in bytes.
     */
    fun append(base: File, clip: File): Long {
        val clipData = clip.length() - HEADER
        RandomAccessFile(base, "rw").use { raf ->
            raf.seek(raf.length())
            FileInputStream(clip).use { input ->
                input.skip(HEADER)
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    raf.write(buf, 0, n)
                }
            }
            val dataLen = raf.length() - HEADER
            // RIFF chunk size @4, data chunk size @40 (little-endian ints).
            raf.seek(4); raf.write(intLE((36 + dataLen).toInt()))
            raf.seek(40); raf.write(intLE(dataLen.toInt()))
            return dataLen.also { require(clipData >= 0) }
        }
    }

    private fun intLE(v: Int) = byteArrayOf(
        (v and 0xff).toByte(),
        (v shr 8 and 0xff).toByte(),
        (v shr 16 and 0xff).toByte(),
        (v shr 24 and 0xff).toByte(),
    )
}

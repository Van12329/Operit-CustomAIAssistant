package com.ai.assistance.operit.voice.unified

/**
 * Fixed-capacity PCM preroll buffer.
 *
 * Single-producer friendly: [append] is intended to run on the audio capture thread.
 * [snapshotLast] may be called from another thread when wake/session logic needs preroll.
 */
class PcmRingBuffer(capacitySamples: Int) {
    private val samples = FloatArray(capacitySamples.coerceAtLeast(1))
    private var writeIndex = 0
    private var size = 0

    @Synchronized
    fun append(frame: FloatArray) {
        for (sample in frame) {
            samples[writeIndex] = sample
            writeIndex = (writeIndex + 1) % samples.size
            if (size < samples.size) size++
        }
    }

    @Synchronized
    fun snapshotLast(maxSamples: Int = size): FloatArray {
        val count = maxSamples.coerceAtLeast(0).coerceAtMost(size)
        val out = FloatArray(count)
        var source = (writeIndex - count + samples.size) % samples.size
        for (i in 0 until count) {
            out[i] = samples[source]
            source = (source + 1) % samples.size
        }
        return out
    }

    @Synchronized
    fun clear() {
        writeIndex = 0
        size = 0
    }

    @Synchronized
    fun sizeSamples(): Int = size

    val capacitySamples: Int
        get() = samples.size
}

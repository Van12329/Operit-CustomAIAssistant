package com.ai.assistance.operit.voice.unified

import android.content.Context
import com.ai.assistance.operit.api.speech.OnnxSileroVad

/**
 * Android model adapter only. It consumes frames supplied by the unified runtime and owns no AudioRecord.
 */
class OperitSileroSpeechGate(
    context: Context,
    private val frameSize: Int = 512,
) : SpeechGate, AutoCloseable {
    private val vad = OnnxSileroVad(
        context = context.applicationContext,
        sampleRate = 16_000,
        frameSize = frameSize,
        mode = OnnxSileroVad.Mode.NORMAL,
        speechDurationMs = 60,
        silenceDurationMs = 300,
    )

    override fun isSpeech(pcm: FloatArray): Boolean {
        require(pcm.size == frameSize) { "Silero gate expects $frameSize samples, got ${pcm.size}" }
        val shorts = ShortArray(frameSize)
        for (i in pcm.indices) {
            shorts[i] = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        return vad.isSpeech(shorts)
    }

    override fun close() {
        vad.close()
    }
}

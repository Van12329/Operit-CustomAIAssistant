package com.ai.assistance.operit.voice.unified

/** Speech-to-text consumer for the unified PCM stream. Implementations must never open a microphone. */
interface StreamingSpeechRecognizer : AutoCloseable {
    fun startSession(preroll: FloatArray = floatArrayOf()): Boolean
    fun acceptPcm(pcm: FloatArray)
    fun finishSession()
    fun cancelSession()
}

data class SpeechRecognitionResult(
    val text: String,
    val isFinal: Boolean,
    val languageTag: String? = null,
)

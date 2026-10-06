package com.ai.assistance.operit.voice.unified

/** Voice-activity decision over one PCM frame. Implementations may use Silero or another VAD, but own no microphone. */
fun interface SpeechGate {
    fun isSpeech(pcm: FloatArray): Boolean
}

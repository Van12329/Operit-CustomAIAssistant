package com.ai.assistance.operit.voice.unified

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Process-local bridge from the unified voice host into Operit's existing voice UI. */
object UnifiedVoiceUiBridge {
    const val enabled: Boolean = true

    private val _results = MutableSharedFlow<SpeechRecognitionResult>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    val results = _results.asSharedFlow()

    fun publish(result: SpeechRecognitionResult) {
        _results.tryEmit(result)
    }
}

package com.ai.assistance.operit.voice.unified

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Process-local control/result seam. It never owns microphone or Android service lifecycle. */
object UnifiedVoiceUiBridge {
    const val enabled: Boolean = true

    sealed interface Command {
        data object ResponseStarted : Command
        data object ResponseFinished : Command
        data object BargeIn : Command
        data object SessionEnded : Command
    }

    private val _results = MutableSharedFlow<SpeechRecognitionResult>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    val results = _results.asSharedFlow()

    private val _commands = MutableSharedFlow<Command>(
        replay = 0,
        extraBufferCapacity = 16,
    )
    val commands = _commands.asSharedFlow()

    fun publish(result: SpeechRecognitionResult) {
        _results.tryEmit(result)
    }

    fun responseStarted() { _commands.tryEmit(Command.ResponseStarted) }
    fun responseFinished() { _commands.tryEmit(Command.ResponseFinished) }
    fun bargeIn() { _commands.tryEmit(Command.BargeIn) }
    fun sessionEnded() { _commands.tryEmit(Command.SessionEnded) }
}

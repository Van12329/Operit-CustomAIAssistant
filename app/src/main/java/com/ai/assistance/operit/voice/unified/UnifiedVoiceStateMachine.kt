package com.ai.assistance.operit.voice.unified

/**
 * Minimal voice lifecycle for the transplant Gate.
 *
 * State changes only select PCM consumers. They must never start/stop capture.
 */
enum class UnifiedVoiceState {
    HOTWORD_ARMED,
    SESSION_LISTENING,
    SPEAKING,
    SUSPENDED_BY_CALL,
}

class UnifiedVoiceStateMachine(
    initial: UnifiedVoiceState = UnifiedVoiceState.HOTWORD_ARMED,
) {
    @Volatile
    var state: UnifiedVoiceState = initial
        private set

    @Synchronized
    fun onWakeDetected() {
        require(state == UnifiedVoiceState.HOTWORD_ARMED) { "wake is only valid while armed: $state" }
        state = UnifiedVoiceState.SESSION_LISTENING
    }

    @Synchronized
    fun onResponseStarted() {
        require(state == UnifiedVoiceState.SESSION_LISTENING) { "response requires listening: $state" }
        state = UnifiedVoiceState.SPEAKING
    }

    @Synchronized
    fun onBargeIn() {
        require(state == UnifiedVoiceState.SPEAKING) { "barge-in requires speaking: $state" }
        state = UnifiedVoiceState.SESSION_LISTENING
    }

    @Synchronized
    fun onResponseFinished() {
        require(state == UnifiedVoiceState.SPEAKING) { "response finish requires speaking: $state" }
        state = UnifiedVoiceState.SESSION_LISTENING
    }

    @Synchronized
    fun onSessionEnded() {
        require(state == UnifiedVoiceState.SESSION_LISTENING || state == UnifiedVoiceState.SPEAKING) {
            "session end requires an active session: $state"
        }
        state = UnifiedVoiceState.HOTWORD_ARMED
    }

    @Synchronized
    fun onCallSuspended() {
        state = UnifiedVoiceState.SUSPENDED_BY_CALL
    }

    @Synchronized
    fun onCallEnded() {
        require(state == UnifiedVoiceState.SUSPENDED_BY_CALL) { "call end requires suspension: $state" }
        state = UnifiedVoiceState.HOTWORD_ARMED
    }
}

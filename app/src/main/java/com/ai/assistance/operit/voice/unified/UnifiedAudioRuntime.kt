package com.ai.assistance.operit.voice.unified

/**
 * Pure PCM harness for the first unified-audio Gate.
 *
 * Capture is deliberately outside this class. The sole microphone owner calls [onFrame].
 * Wake/session/barge-in consumers are selected by state without reopening capture.
 */
class UnifiedAudioRuntime(
    sampleRate: Int,
    prerollSeconds: Int = 3,
) {
    val bus = AudioFrameBus()
    val stateMachine = UnifiedVoiceStateMachine()
    val preroll = PcmRingBuffer(sampleRate * prerollSeconds)

    private val prerollSubscription = bus.subscribe(preroll::append)

    fun onFrame(frame: FloatArray) {
        bus.publish(frame)
    }

    fun close() {
        prerollSubscription.close()
        preroll.clear()
    }
}

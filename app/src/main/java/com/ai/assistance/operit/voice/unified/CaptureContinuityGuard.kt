package com.ai.assistance.operit.voice.unified

/**
 * Identity supplied by the one Android capture owner.
 * creationId increments only when a new AudioRecord object is constructed.
 */
data class CaptureIdentity(
    val creationId: Long,
    val audioSessionId: Int,
)

data class CaptureContinuitySnapshot(
    val expected: CaptureIdentity,
    val current: CaptureIdentity,
) {
    val sameAudioRecord: Boolean get() = expected.creationId == current.creationId
    val sameAudioSession: Boolean get() = expected.audioSessionId == current.audioSessionId
    val isContinuous: Boolean get() = sameAudioRecord && sameAudioSession
}

class CaptureContinuityGuard(initial: CaptureIdentity) {
    private val expected = initial

    fun check(current: CaptureIdentity): CaptureContinuitySnapshot =
        CaptureContinuitySnapshot(expected = expected, current = current)
}

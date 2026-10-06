package com.ai.assistance.operit.voice.unified

data class WakeProfile(
    val version: Int,
    val backendId: String,
    val sampleRate: Int,
    val profileData: List<FloatArray>,
    val threshold: Float,
    val createdAt: Long,
)

data class WakeResult(
    val detected: Boolean,
    val score: Float = 0f,
    val wakeStartSample: Long? = null,
    val wakeEndSample: Long? = null,
)

interface WakeDetector {
    fun loadProfile(profile: WakeProfile)
    fun process(pcm: FloatArray): WakeResult
    fun reset()
}

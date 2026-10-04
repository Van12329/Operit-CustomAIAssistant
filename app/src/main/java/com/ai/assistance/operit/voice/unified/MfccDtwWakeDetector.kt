package com.ai.assistance.operit.voice.unified

/**
 * Converts fixed PCM windows into Backend-A features.
 * The feature encoder is injected so the detector remains independent from Android lifecycle.
 */
class MfccDtwWakeDetector(
    private val featureEncoder: (FloatArray) -> FloatArray,
    private val matcher: MfccDtwWakeMatcher = MfccDtwWakeMatcher(),
    private val windowSamples: Int = 16_000,
) : WakeDetector {
    private var profile: WakeProfile? = null
    private val window = PcmRingBuffer(windowSamples)

    override fun loadProfile(profile: WakeProfile) {
        require(profile.backendId == BACKEND_ID) { "unsupported backend: ${profile.backendId}" }
        this.profile = profile
        window.clear()
    }

    override fun process(pcm: FloatArray): WakeResult {
        val p = profile ?: return WakeResult(false)
        window.append(pcm)
        if (window.sizeSamples() < windowSamples) return WakeResult(false)

        val features = featureEncoder(window.snapshotLast())
        val match = matcher.match(
            features = features,
            templates = p.profileData,
            threshold = p.threshold,
        )
        return WakeResult(detected = match.detected, score = match.best)
    }

    override fun reset() {
        window.clear()
    }

    companion object {
        const val BACKEND_ID = "mfcc_dtw_v1"
    }
}

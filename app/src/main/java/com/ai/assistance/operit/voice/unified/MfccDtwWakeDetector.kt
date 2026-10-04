package com.ai.assistance.operit.voice.unified

/**
 * Backend A: historical Operit MFCC/DTW matching with PCM/VAD segmentation separated
 * from capture ownership. The detector never opens a microphone or owns Android lifecycle.
 */
class MfccDtwWakeDetector(
    private val speechGate: SpeechGate,
    private val featureEncoder: (FloatArray) -> FloatArray,
    private val matcher: MfccDtwWakeMatcher = MfccDtwWakeMatcher(),
    private val sampleRate: Int = 16_000,
    private val minSegmentMs: Int = 250,
    private val maxSegmentMs: Int = 1_600,
    private val endSilenceMs: Int = 350,
) : WakeDetector {
    private var profile: WakeProfile? = null
    private val speech = ArrayList<Float>()
    private var seenSpeech = false
    private var silenceSamples = 0
    private var totalSamples = 0

    override fun loadProfile(profile: WakeProfile) {
        require(profile.backendId == BACKEND_ID) { "unsupported backend: ${profile.backendId}" }
        require(profile.sampleRate == sampleRate) { "sample-rate mismatch: ${profile.sampleRate} != $sampleRate" }
        this.profile = profile
        resetSegment()
    }

    override fun process(pcm: FloatArray): WakeResult {
        val p = profile ?: return WakeResult(false)
        if (pcm.isEmpty()) return WakeResult(false)

        val isSpeech = speechGate.isSpeech(pcm)
        totalSamples += pcm.size

        if (isSpeech) {
            seenSpeech = true
            silenceSamples = 0
            for (sample in pcm) speech.add(sample)
        } else if (seenSpeech) {
            silenceSamples += pcm.size
        }

        val maxReached = totalSamples >= msToSamples(maxSegmentMs)
        val speechEnded = seenSpeech && silenceSamples >= msToSamples(endSilenceMs)
        if (!maxReached && !speechEnded) return WakeResult(false)

        val speechSamples = speech.size
        val longEnough = speechSamples >= msToSamples(minSegmentMs)
        val result = if (longEnough) {
            val segment = FloatArray(speechSamples)
            for (i in segment.indices) segment[i] = speech[i]
            val features = featureEncoder(segment)
            val match = matcher.match(
                features = features,
                templates = p.profileData,
                threshold = p.threshold,
            )
            WakeResult(detected = match.detected, score = match.best)
        } else {
            WakeResult(false)
        }
        resetSegment()
        return result
    }

    override fun reset() = resetSegment()

    private fun resetSegment() {
        speech.clear()
        seenSpeech = false
        silenceSamples = 0
        totalSamples = 0
    }

    private fun msToSamples(ms: Int): Int = (sampleRate * ms) / 1000

    companion object {
        const val BACKEND_ID = "mfcc_dtw_v1"
    }
}

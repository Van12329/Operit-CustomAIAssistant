package com.ai.assistance.operit.voice.unified

import android.content.Context
import com.ai.assistance.operit.util.AppLogger

/**
 * Production host seam for the clone's unified voice tract.
 *
 * Owns one microphone capture for Wake + session STT. Operit services/UI may drive state, but they
 * must not create a parallel recorder while this host is running.
 */
class OperitUnifiedVoiceHost(
    context: Context,
    private val onWake: () -> Unit,
    private val onSpeechResult: (SpeechRecognitionResult) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val speechGate = OperitSileroSpeechGate(appContext)
    private val wakeDetector = MfccDtwWakeDetector(
        speechGate = speechGate,
        featureEncoder = OperitMfccFeatureEncoder::encode,
    )
    private val controller = UnifiedVoicePrototypeController(
        input = UnifiedMicrophoneCapture(),
        diagnostics = { AppLogger.d(TAG, it) },
    )
    private val speechRecognizer = AndroidOnDeviceSpeechRecognizer(
        context = appContext,
        onResult = onSpeechResult,
        diagnostics = { AppLogger.d(TAG, it) },
    )
    private val router = UnifiedVoiceFrameRouter(
        runtime = controller.runtime,
        wakeDetector = wakeDetector,
        onWakeDetected = {
            controller.onWakeDetected()
            onWake()
        },
        speechRecognizer = speechRecognizer,
    )

    @Volatile
    private var profileLoaded = false
    private var profileFingerprint: Int? = null

    fun loadPersonalTemplates(templates: List<FloatArray>, threshold: Float = DEFAULT_WAKE_THRESHOLD): Boolean {
        val valid = templates.filter { it.isNotEmpty() && it.size % FEATURE_DIM == 0 }
        if (valid.isEmpty()) {
            profileLoaded = false
            profileFingerprint = null
            wakeDetector.reset()
            AppLogger.w(TAG, "UNIFIED_WAKE_PROFILE unavailable")
            return false
        }
        val fingerprint = valid.fold(1) { acc, item -> 31 * acc + item.contentHashCode() }
        if (profileLoaded && profileFingerprint == fingerprint) return true
        wakeDetector.loadProfile(
            WakeProfile(
                version = 1,
                backendId = MfccDtwWakeDetector.BACKEND_ID,
                sampleRate = SAMPLE_RATE,
                profileData = valid.map { it.copyOf() },
                threshold = threshold,
                createdAt = System.currentTimeMillis(),
            )
        )
        profileLoaded = true
        profileFingerprint = fingerprint
        AppLogger.d(TAG, "UNIFIED_WAKE_PROFILE loaded count=" + valid.size)
        return true
    }

    fun start(): Boolean {
        if (!profileLoaded) {
            AppLogger.w(TAG, "UNIFIED_START rejected reason=no_wake_profile")
            return false
        }
        val started = controller.start()
        AppLogger.d(TAG, "UNIFIED_START result=" + started)
        return started
    }

    fun onResponseStarted() {
        router.finishSpeechRecognition()
        controller.onResponseStarted()
    }

    fun onBargeIn() {
        controller.onBargeIn()
        router.startSpeechRecognition()
    }

    fun onResponseFinished() {
        controller.onResponseFinished()
        router.startSpeechRecognition()
    }

    fun onSessionEnded() {
        router.finishSpeechRecognition()
        controller.onSessionEnded()
    }

    fun continuity(): CaptureContinuitySnapshot? = controller.continuity()

    override fun close() {
        router.close()
        controller.stop()
        speechGate.close()
    }

    private companion object {
        const val TAG = "OperitUnifiedVoice"
        const val SAMPLE_RATE = 16_000
        const val FEATURE_DIM = 39
        const val DEFAULT_WAKE_THRESHOLD = 0.865f
    }
}

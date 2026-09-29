package com.ai.assistance.operit.api.speech

import android.content.Context
import android.media.MediaRecorder
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitPaths
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService as VoskAndroidSpeechService

/**
 * Offline Russian STT provider backed by Vosk.
 *
 * The model ZIP is downloaded and SHA-256 verified by the existing Gradle
 * syncSttModelAssets task, then packaged into the APK. At runtime we only
 * extract that trusted asset into app-private storage; no network is needed.
 */
class VoskRussianSpeechProvider(
    private val context: Context,
) : SpeechService {

    companion object {
        private const val TAG = "VoskRussianSpeech"
        private const val MODEL_NAME = "vosk-model-small-ru-0.22"
        private const val MODEL_ASSET_ZIP = "models/$MODEL_NAME.zip"
        private const val SAMPLE_RATE = 16_000.0f
    }

    private val initializeMutex = Mutex()
    private val recognitionMutex = Mutex()

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var voskService: VoskAndroidSpeechService? = null

    private val _recognitionState =
        MutableStateFlow(SpeechService.RecognitionState.UNINITIALIZED)
    override val currentState: SpeechService.RecognitionState
        get() = _recognitionState.value
    override val recognitionStateFlow: StateFlow<SpeechService.RecognitionState> =
        _recognitionState.asStateFlow()

    private val _recognitionResult =
        MutableStateFlow(SpeechService.RecognitionResult(""))
    override val recognitionResultFlow: StateFlow<SpeechService.RecognitionResult> =
        _recognitionResult.asStateFlow()

    private val _recognitionError =
        MutableStateFlow(SpeechService.RecognitionError(0, ""))
    override val recognitionErrorFlow: StateFlow<SpeechService.RecognitionError> =
        _recognitionError.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // Vosk Android owns AudioRecord internally. Volume metering can be added later
    // without changing recognition semantics; zero is safe for the first qualification.
    private val _volumeLevelFlow = MutableStateFlow(0f)
    override val volumeLevelFlow: StateFlow<Float> = _volumeLevelFlow.asStateFlow()

    override val isRecognizing: Boolean
        get() = _recognitionState.value == SpeechService.RecognitionState.RECOGNIZING

    override suspend fun initialize(): Boolean {
        if (_isInitialized.value) return true

        return initializeMutex.withLock {
            if (_isInitialized.value) return@withLock true

            _recognitionState.value = SpeechService.RecognitionState.PREPARING
            _recognitionError.value = SpeechService.RecognitionError(0, "")

            try {
                val modelDir = withContext(Dispatchers.IO) { ensureModelExtracted() }
                model = withContext(Dispatchers.IO) { Model(modelDir.absolutePath) }
                _isInitialized.value = true
                _recognitionState.value = SpeechService.RecognitionState.IDLE
                AppLogger.d(TAG, "Vosk Russian model initialized: ${modelDir.absolutePath}")
                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to initialize Vosk Russian STT", e)
                _recognitionState.value = SpeechService.RecognitionState.ERROR
                _recognitionError.value =
                    SpeechService.RecognitionError(-1, e.message ?: "Vosk initialization failed")
                false
            }
        }
    }

    override suspend fun startRecognition(
        languageCode: String,
        continuousMode: Boolean,
        partialResults: Boolean,
        audioSource: Int,
    ): Boolean {
        return recognitionMutex.withLock {
            if (!_isInitialized.value && !initialize()) {
                return@withLock false
            }
            if (isRecognizing) {
                return@withLock false
            }

            cleanupSession(cancel = true)
            _recognitionResult.value = SpeechService.RecognitionResult("")
            _recognitionError.value = SpeechService.RecognitionError(0, "")
            _recognitionState.value = SpeechService.RecognitionState.PREPARING

            if (!languageCode.startsWith("ru", ignoreCase = true)) {
                AppLogger.w(TAG, "Vosk Russian provider received languageCode=$languageCode; using Russian model")
            }
            if (audioSource != MediaRecorder.AudioSource.VOICE_RECOGNITION) {
                AppLogger.d(
                    TAG,
                    "vosk-android 0.3.75 owns AudioRecord and uses VOICE_RECOGNITION; requested source=$audioSource"
                )
            }

            try {
                val activeModel = checkNotNull(model) { "Vosk model is not initialized" }
                val newRecognizer = Recognizer(activeModel, SAMPLE_RATE)
                val newService = VoskAndroidSpeechService(newRecognizer, SAMPLE_RATE)

                recognizer = newRecognizer
                voskService = newService

                val listener =
                    object : RecognitionListener {
                        override fun onPartialResult(hypothesis: String) {
                            if (!partialResults || !isRecognizing) return
                            val text = parseField(hypothesis, "partial")
                            if (text.isNotBlank()) {
                                _recognitionResult.value =
                                    SpeechService.RecognitionResult(text = text, isFinal = false)
                            }
                        }

                        override fun onResult(hypothesis: String) {
                            if (!isRecognizing) return
                            val text = parseField(hypothesis, "text")
                            if (text.isNotBlank()) {
                                _recognitionResult.value =
                                    SpeechService.RecognitionResult(text = text, isFinal = true)
                                if (!continuousMode) {
                                    // The normal WakeUpWord voice path uses continuousMode=true.
                                    // For one-shot callers, stop after the first completed utterance.
                                    Thread {
                                        runBlocking {
                                            try {
                                                stopRecognition()
                                            } catch (_: Exception) {
                                            }
                                        }
                                    }.start()
                                }
                            }
                        }

                        override fun onFinalResult(hypothesis: String) {
                            val text = parseField(hypothesis, "text")
                            if (text.isNotBlank()) {
                                _recognitionResult.value =
                                    SpeechService.RecognitionResult(text = text, isFinal = true)
                            }
                            if (_recognitionState.value != SpeechService.RecognitionState.ERROR) {
                                _recognitionState.value = SpeechService.RecognitionState.IDLE
                            }
                        }

                        override fun onError(exception: Exception) {
                            AppLogger.e(TAG, "Vosk recognition failed", exception)
                            _recognitionError.value =
                                SpeechService.RecognitionError(
                                    -2,
                                    exception.message ?: "Vosk recognition failed",
                                )
                            _recognitionState.value = SpeechService.RecognitionState.ERROR
                        }

                        override fun onTimeout() {
                            if (_recognitionState.value != SpeechService.RecognitionState.ERROR) {
                                _recognitionState.value = SpeechService.RecognitionState.IDLE
                            }
                        }
                    }

                val started = newService.startListening(listener)
                if (!started) {
                    cleanupSession(cancel = true)
                    _recognitionState.value = SpeechService.RecognitionState.ERROR
                    _recognitionError.value =
                        SpeechService.RecognitionError(-3, "Vosk voice capture is already active")
                    return@withLock false
                }

                _recognitionState.value = SpeechService.RecognitionState.RECOGNIZING
                AppLogger.d(TAG, "Started Vosk Russian recognition")
                true
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to start Vosk Russian recognition", e)
                cleanupSession(cancel = true)
                _recognitionState.value = SpeechService.RecognitionState.ERROR
                _recognitionError.value =
                    SpeechService.RecognitionError(-4, e.message ?: "Failed to start Vosk")
                false
            }
        }
    }

    override suspend fun stopRecognition(): Boolean {
        return recognitionMutex.withLock {
            val activeService = voskService ?: return@withLock false
            _recognitionState.value = SpeechService.RecognitionState.PROCESSING
            return@withLock try {
                withContext(Dispatchers.IO) {
                    activeService.stop()
                }
                cleanupSession(cancel = false)
                _recognitionState.value = SpeechService.RecognitionState.IDLE
                true
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to stop Vosk recognition cleanly", e)
                cleanupSession(cancel = true)
                _recognitionState.value = SpeechService.RecognitionState.ERROR
                _recognitionError.value =
                    SpeechService.RecognitionError(-5, e.message ?: "Failed to stop Vosk")
                false
            }
        }
    }

    override suspend fun cancelRecognition() {
        recognitionMutex.withLock {
            cleanupSession(cancel = true)
            _recognitionState.value =
                if (_isInitialized.value) {
                    SpeechService.RecognitionState.IDLE
                } else {
                    SpeechService.RecognitionState.UNINITIALIZED
                }
            _recognitionResult.value = SpeechService.RecognitionResult("")
            _volumeLevelFlow.value = 0f
        }
    }

    override fun shutdown() {
        runBlocking {
            recognitionMutex.withLock {
                cleanupSession(cancel = true)
            }
            withContext(Dispatchers.IO) {
                try {
                    model?.close()
                } catch (_: Exception) {
                }
                model = null
            }
        }
        _isInitialized.value = false
        _recognitionState.value = SpeechService.RecognitionState.UNINITIALIZED
        _recognitionResult.value = SpeechService.RecognitionResult("")
        _volumeLevelFlow.value = 0f
    }

    override suspend fun getSupportedLanguages(): List<String> =
        listOf("ru", "ru-RU")

    override suspend fun recognize(audioData: FloatArray) {
        _recognitionError.value =
            SpeechService.RecognitionError(
                -10,
                "Batch recognition is not implemented for the Vosk Russian provider",
            )
        _recognitionState.value = SpeechService.RecognitionState.ERROR
    }

    private fun cleanupSession(cancel: Boolean) {
        val service = voskService
        voskService = null
        if (service != null) {
            try {
                if (cancel) service.cancel() else service.stop()
            } catch (_: Exception) {
            }
            try {
                service.shutdown()
            } catch (_: Exception) {
            }
        }

        val activeRecognizer = recognizer
        recognizer = null
        try {
            activeRecognizer?.close()
        } catch (_: Exception) {
        }
        _volumeLevelFlow.value = 0f
    }

    private fun parseField(json: String, field: String): String {
        return try {
            JSONObject(json).optString(field).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun ensureModelExtracted(): File {
        val root = OperitPaths.voskModelsDir(context)
        val target = File(root, MODEL_NAME)
        if (isModelReady(target)) return target

        if (target.exists()) {
            target.deleteRecursively()
        }

        val staging = File(root, ".$MODEL_NAME.extracting")
        if (staging.exists()) {
            staging.deleteRecursively()
        }
        check(staging.mkdirs()) { "Unable to create Vosk extraction directory" }

        try {
            val stagingCanonical = staging.canonicalFile
            ZipInputStream(
                BufferedInputStream(context.assets.open(MODEL_ASSET_ZIP))
            ).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val output = File(staging, entry.name).canonicalFile
                    val allowedPrefix = stagingCanonical.path + File.separator
                    check(
                        output.path == stagingCanonical.path ||
                            output.path.startsWith(allowedPrefix)
                    ) {
                        "Unsafe path in Vosk model archive: ${entry.name}"
                    }

                    if (entry.isDirectory) {
                        check(output.exists() || output.mkdirs()) {
                            "Unable to create Vosk model directory: ${output.path}"
                        }
                    } else {
                        output.parentFile?.let { parent ->
                            check(parent.exists() || parent.mkdirs()) {
                                "Unable to create Vosk model parent directory: ${parent.path}"
                            }
                        }
                        BufferedOutputStream(FileOutputStream(output)).use { out ->
                            zip.copyTo(out)
                        }
                    }
                    zip.closeEntry()
                }
            }

            val extracted = File(staging, MODEL_NAME)
            check(isModelReady(extracted)) {
                "Packaged Vosk Russian model is incomplete"
            }

            if (!extracted.renameTo(target)) {
                check(extracted.copyRecursively(target, overwrite = true)) {
                    "Unable to install Vosk Russian model"
                }
            }
            check(isModelReady(target)) {
                "Installed Vosk Russian model failed validation"
            }
            return target
        } finally {
            if (staging.exists()) {
                staging.deleteRecursively()
            }
        }
    }

    private fun isModelReady(dir: File): Boolean {
        return dir.isDirectory &&
            File(dir, "am/final.mdl").isFile &&
            File(dir, "conf/model.conf").isFile &&
            File(dir, "graph/HCLr.fst").isFile &&
            File(dir, "graph/Gr.fst").isFile
    }
}

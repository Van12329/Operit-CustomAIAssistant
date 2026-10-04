package com.ai.assistance.operit.voice.unified

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device Android STT fed only by injected PCM.
 * This class deliberately contains no AudioRecord: UnifiedMicrophoneCapture remains the sole mic owner.
 */
class AndroidOnDeviceSpeechRecognizer(
    context: Context,
    private val sampleRate: Int = 16_000,
    private val onResult: (SpeechRecognitionResult) -> Unit,
    private val onError: (Int) -> Unit = {},
    private val diagnostics: (String) -> Unit = {},
) : StreamingSpeechRecognizer {
    private val appContext = context.applicationContext
    private val recognizer = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
    ) SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext) else null
    private val writerExecutor = Executors.newSingleThreadExecutor { Thread(it, "CAA-UnifiedSttPipe") }
    private val active = AtomicBoolean(false)
    private var readPipe: ParcelFileDescriptor? = null
    private var writePipe: ParcelFileDescriptor? = null
    private var output: FileOutputStream? = null

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { diagnostics("STT_READY source=injected_pcm") }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onError(error: Int) {
                diagnostics("STT_ERROR code=" + error)
                active.set(false)
                closePipe()
                onError(error)
            }
            override fun onResults(results: Bundle?) = emitResults(results, true)
            override fun onPartialResults(partialResults: Bundle?) = emitResults(partialResults, false)
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onSegmentResults(segmentResults: Bundle) = emitResults(segmentResults, true)
            override fun onEndOfSegmentedSession() {
                diagnostics("STT_SEGMENTED_SESSION_END")
                active.set(false)
                closePipe()
            }
            override fun onLanguageDetection(results: Bundle) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    results.getString(SpeechRecognizer.DETECTED_LANGUAGE)?.let {
                        diagnostics("STT_LANGUAGE language=" + it)
                    }
                }
            }
        })
    }

    override fun startSession(preroll: FloatArray): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || recognizer == null) {
            diagnostics("STT_UNAVAILABLE reason=on_device_or_api")
            return false
        }
        if (!active.compareAndSet(false, true)) return false
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            readPipe = pipe[0]
            writePipe = pipe[1]
            output = FileOutputStream(pipe[1].fileDescriptor)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readPipe)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                    putStringArrayListExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                        arrayListOf("ru-RU", "es-ES", "es-US"),
                    )
                }
            }
            recognizer.startListening(intent)
            diagnostics("STT_START source=injected_pcm sampleRate=" + sampleRate)
            if (preroll.isNotEmpty()) acceptPcm(preroll)
            true
        } catch (failure: Throwable) {
            diagnostics("STT_START_FAIL type=" + failure.javaClass.simpleName)
            active.set(false)
            closePipe()
            false
        }
    }

    override fun acceptPcm(pcm: FloatArray) {
        if (!active.get() || pcm.isEmpty()) return
        val bytes = ByteArray(pcm.size * 2)
        var j = 0
        for (sample in pcm) {
            val value = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort().toInt()
            bytes[j++] = (value and 0xff).toByte()
            bytes[j++] = ((value ushr 8) and 0xff).toByte()
        }
        writerExecutor.execute {
            try {
                if (active.get()) output?.write(bytes)
            } catch (failure: Throwable) {
                diagnostics("STT_PIPE_WRITE_FAIL type=" + failure.javaClass.simpleName)
                cancelSession()
            }
        }
    }

    override fun finishSession() {
        if (!active.getAndSet(false)) return
        diagnostics("STT_FINISH close_audio_source=true")
        closePipe()
    }

    override fun cancelSession() {
        if (!active.getAndSet(false)) return
        recognizer?.cancel()
        diagnostics("STT_CANCEL")
        closePipe()
    }

    override fun close() {
        cancelSession()
        recognizer?.destroy()
        writerExecutor.shutdownNow()
    }

    private fun emitResults(bundle: Bundle?, final: Boolean) {
        val text = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
        val language = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            bundle.getString(SpeechRecognizer.DETECTED_LANGUAGE)
        } else null
        onResult(SpeechRecognitionResult(text, final, language))
    }

    @Synchronized
    private fun closePipe() {
        try { output?.close() } catch (_: Throwable) {}
        try { writePipe?.close() } catch (_: Throwable) {}
        try { readPipe?.close() } catch (_: Throwable) {}
        output = null
        writePipe = null
        readPipe = null
    }
}

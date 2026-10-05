package com.ai.assistance.operit.api.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import com.ai.assistance.operit.util.AppLogger
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PersonalWakeEnrollment {

    @SuppressLint("MissingPermission")
    suspend fun recordOneTemplate(
        context: Context,
        maxRecordMs: Long = 6000L,
        minSpeechMs: Long = 250L,
        endSilenceMs: Long = 350L,
        onTrimmedPcm: ((ShortArray) -> Unit)? = null,
    ): FloatArray? = withContext(Dispatchers.Default) {
        val sampleRate = 16000
        val frameSize = 512

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            (minBufferSize.coerceAtLeast(frameSize) * 2)
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            AppLogger.e("PersonalWakeEnrollment", "Enrollment AudioRecord failed to initialize")
            audioRecord.release()
            return@withContext null
        }

        val vad = OnnxSileroVad(
            context = context.applicationContext,
            sampleRate = sampleRate,
            frameSize = frameSize,
            mode = OnnxSileroVad.Mode.NORMAL,
            speechDurationMs = 60,
            silenceDurationMs = 300,
        )

        try {
            val buffer = ShortArray(frameSize)
            val speech = ArrayList<Short>()

            var seenSpeech = false
            var silenceMsAfterSpeech = 0L
            var speechMs = 0L

            val startedAt = System.currentTimeMillis()
            try {
                audioRecord.startRecording()
            } catch (e: IllegalStateException) {
                AppLogger.e("PersonalWakeEnrollment", "Enrollment AudioRecord failed to start", e)
                return@withContext null
            }
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                AppLogger.e("PersonalWakeEnrollment", "Enrollment AudioRecord did not enter recording state")
                return@withContext null
            }

            while (true) {
                val now = System.currentTimeMillis()
                if (now - startedAt > maxRecordMs) break

                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                val isSpeech = if (read == frameSize) vad.isSpeech(buffer) else false

                if (isSpeech) {
                    seenSpeech = true
                    silenceMsAfterSpeech = 0L
                    speechMs += (read * 1000L) / sampleRate
                    for (i in 0 until read) {
                        speech.add(buffer[i])
                    }
                } else {
                    if (seenSpeech) {
                        silenceMsAfterSpeech += (read * 1000L) / sampleRate
                        if (silenceMsAfterSpeech >= endSilenceMs) break
                    }
                }
            }

            if (!seenSpeech) return@withContext null
            if (speechMs < minSpeechMs) return@withContext null

            val pcm = ShortArray(speech.size)
            for (i in pcm.indices) {
                pcm[i] = speech[i]
            }

            // Stable enrollment seam for pluggable wake backends.
            // The existing UI/storage path still receives MFCC below; new encoders may consume
            // the same VAD-trimmed PCM without creating another recorder.
            onTrimmedPcm?.invoke(pcm.copyOf())
            PersonalWakeFeatureExtractor.extractFeatures(pcm, pcm.size)
        } finally {
            try {
                audioRecord.stop()
            } catch (_: Exception) {
            }
            try {
                audioRecord.release()
            } catch (_: Exception) {
            }
            try {
                vad.close()
            } catch (_: Exception) {
            }
        }
    }
}

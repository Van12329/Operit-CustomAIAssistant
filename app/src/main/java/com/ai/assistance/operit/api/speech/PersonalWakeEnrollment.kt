package com.ai.assistance.operit.api.speech

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Process
import android.util.Log
import android.media.AudioFormat
import android.media.AudioRecord
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
    ): FloatArray? = withContext(Dispatchers.Default) {
        val sampleRate = 16000
        val frameSize = 512

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val bufferSize = minBufferSize.coerceAtLeast(frameSize) * 2
        val hasRecordPermission =
            context.checkPermission(
                android.Manifest.permission.RECORD_AUDIO,
                Process.myPid(),
                Process.myUid()
            ) == PackageManager.PERMISSION_GRANTED
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val activeRecordings = audioManager.activeRecordingConfigurations.joinToString(
            prefix = "[",
            postfix = "]"
        ) { config ->
            "{session=${config.clientAudioSessionId},source=${config.clientAudioSource},silenced=${config.isClientSilenced}}"
        }

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            val diagnostic =
                "Personal wake microphone unavailable: " +
                    "permission=$hasRecordPermission, " +
                    "minBufferSize=$minBufferSize, bufferSize=$bufferSize, " +
                    "audioRecordState=${audioRecord.state}, " +
                    "recordingState=${audioRecord.recordingState}, " +
                    "audioSessionId=${audioRecord.audioSessionId}, " +
                    "audioSource=${audioRecord.audioSource}, " +
                    "activeRecordings=$activeRecordings"
            Log.e(TAG, diagnostic)
            try {
                audioRecord.release()
            } catch (_: Exception) {
            }
            throw PersonalWakeEnrollmentException(diagnostic)
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
            } catch (error: Exception) {
                val diagnostic =
                    "Personal wake microphone failed to start: " +
                        "permission=$hasRecordPermission, " +
                        "minBufferSize=$minBufferSize, bufferSize=$bufferSize, " +
                        "audioRecordState=${audioRecord.state}, " +
                        "recordingState=${audioRecord.recordingState}, " +
                        "audioSessionId=${audioRecord.audioSessionId}, " +
                        "audioSource=${audioRecord.audioSource}, " +
                        "activeRecordings=$activeRecordings, " +
                        "cause=${error.javaClass.simpleName}: ${error.message}"
                Log.e(TAG, diagnostic, error)
                throw PersonalWakeEnrollmentException(diagnostic, error)
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
    private const val TAG = "PersonalWakeEnrollment"
}

class PersonalWakeEnrollmentException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

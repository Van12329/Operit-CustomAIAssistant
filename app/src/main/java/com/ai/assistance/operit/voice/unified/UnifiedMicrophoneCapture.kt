package com.ai.assistance.operit.voice.unified

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Prototype single-owner microphone capture.
 *
 * This is a clean-room adapter shaped after Android's AudioRecord contract, not copied from
 * the GPL S2S donor. Exactly one instance owns exactly one AudioRecord until [stop].
 */
class UnifiedMicrophoneCapture(
    override val sampleRate: Int = 16_000,
    override val frameSize: Int = 512,
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
) : UnifiedAudioInput {
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var identity: CaptureIdentity? = null

    override fun captureIdentity(): CaptureIdentity? = identity

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start(onFrame: (FloatArray) -> Unit): Boolean {
        if (running) return true

        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuffer")
            return false
        }

        val rec = try {
            AudioRecord(
                audioSource,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer * 2, frameSize * 4),
            )
        } catch (error: Exception) {
            Log.e(TAG, "AudioRecord construction failed", error)
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord is not initialized")
            rec.release()
            return false
        }

        val captureIdentity = CaptureIdentity(
            creationId = NEXT_CREATION_ID.incrementAndGet(),
            audioSessionId = rec.audioSessionId,
        )
        identity = captureIdentity
        record = rec
        running = true

        try {
            rec.startRecording()
        } catch (error: Exception) {
            running = false
            record = null
            identity = null
            rec.release()
            Log.e(TAG, "startRecording failed", error)
            return false
        }

        Log.i(TAG, "CAPTURE_OPEN creationId=${captureIdentity.creationId} session=${captureIdentity.audioSessionId}")

        worker = thread(start = true, name = "CAA-UnifiedMic") {
            val shorts = ShortArray(frameSize)
            val floats = FloatArray(frameSize)
            var filled = 0

            while (running) {
                val read = rec.read(shorts, filled, frameSize - filled)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        Log.e(TAG, "CAPTURE_READ_ERROR code=$read identity=$captureIdentity")
                        break
                    }
                    continue
                }
                filled += read
                if (filled < frameSize) continue

                for (i in 0 until frameSize) floats[i] = shorts[i] / 32768.0f
                filled = 0
                try {
                    onFrame(floats)
                } catch (error: Throwable) {
                    Log.e(TAG, "frame consumer failed", error)
                }
            }
        }
        return true
    }

    override fun stop() {
        if (!running && record == null) return
        running = false
        worker?.join()
        worker = null
        record?.let { rec ->
            runCatching {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) rec.stop()
            }
            rec.release()
        }
        Log.i(TAG, "CAPTURE_CLOSE identity=$identity")
        record = null
        identity = null
    }

    private companion object {
        const val TAG = "CAA-UnifiedMic"
        val NEXT_CREATION_ID = AtomicLong(0)
    }
}

interface UnifiedAudioInput {
    val sampleRate: Int
    val frameSize: Int
    fun start(onFrame: (FloatArray) -> Unit): Boolean
    fun stop()
    fun captureIdentity(): CaptureIdentity?
}

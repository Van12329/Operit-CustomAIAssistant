package com.ai.assistance.operit.api.speech

import android.content.Context
import com.ai.assistance.operit.api.chat.AIForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Personal wake enrollment is a consumer of the production unified PCM stream.
 *
 * It deliberately owns no AudioRecord. This preserves the single-microphone invariant across
 * normal wake listening and enrollment and avoids recorder handoff races on device audio HALs.
 */
object PersonalWakeEnrollment {
    suspend fun recordOneTemplate(
        context: Context,
        maxRecordMs: Long = 6000L,
        minSpeechMs: Long = 250L,
        endSilenceMs: Long = 350L,
        onTrimmedPcm: ((ShortArray) -> Unit)? = null,
    ): FloatArray? = withContext(Dispatchers.Default) {
        val pcm = AIForegroundService.collectUnifiedEnrollmentPcm(
            context = context,
            maxRecordMs = maxRecordMs,
            minSpeechMs = minSpeechMs,
            endSilenceMs = endSilenceMs,
        ) ?: return@withContext null

        onTrimmedPcm?.invoke(pcm.copyOf())
        PersonalWakeFeatureExtractor.extractFeatures(pcm, pcm.size)
    }
}

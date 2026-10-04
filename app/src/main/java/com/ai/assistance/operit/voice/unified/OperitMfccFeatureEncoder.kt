package com.ai.assistance.operit.voice.unified

import com.ai.assistance.operit.api.speech.PersonalWakeFeatureExtractor

/**
 * Bridge from the unified Float PCM stream to Operit's existing pure MFCC feature extractor.
 * No capture ownership is introduced here.
 */
object OperitMfccFeatureEncoder {
    fun encode(pcm: FloatArray): FloatArray {
        val shorts = ShortArray(pcm.size)
        for (i in pcm.indices) {
            shorts[i] = (pcm[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        return PersonalWakeFeatureExtractor.extractFeatures(shorts, shorts.size)
    }
}

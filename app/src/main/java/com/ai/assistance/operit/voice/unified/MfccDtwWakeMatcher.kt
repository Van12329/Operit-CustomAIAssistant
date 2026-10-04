package com.ai.assistance.operit.voice.unified

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Backend-A matching core extracted from the historical personal-wake algorithm.
 * Pure math only: no Android, microphone, service, audio focus or lifecycle dependencies.
 *
 * It intentionally preserves the historical quality guards that matter to recognition:
 * duration compatibility, enrollment-consistency-derived threshold, and ambiguous-best protection.
 */
class MfccDtwWakeMatcher(
    private val featureDim: Int = 39,
    private val dtwBand: Int = 4,
    private val dynamicThresholdMargin: Float = 0.02f,
    private val minDynamicThresholdFloor: Float = 0.84f,
    private val maxBestSecondGap: Float = 0.04f,
    private val minDurationRatio: Float = 0.75f,
    private val maxDurationRatio: Float = 1.25f,
) {
    data class Match(
        val best: Float,
        val secondBest: Float,
        val hits: Int,
        val threshold: Float,
        val detected: Boolean,
    )

    fun match(
        features: FloatArray,
        templates: List<FloatArray>,
        threshold: Float,
        requiredMatches: Int = 1,
    ): Match {
        require(featureDim > 0)
        val candidate = reshapeAndNormalize(features)
        val valid = templates
            .filter { it.isNotEmpty() && it.size % featureDim == 0 }
            .map(::reshapeAndNormalize)
            .filter { it.isNotEmpty() }
        if (candidate.isEmpty() || valid.isEmpty()) {
            return Match(0f, 0f, 0, threshold, false)
        }

        val meanTemplateLength = valid.map { it.size }.average().toFloat().coerceAtLeast(1f)
        val durationRatio = candidate.size.toFloat() / meanTemplateLength
        if (durationRatio < minDurationRatio || durationRatio > maxDurationRatio) {
            return Match(0f, 0f, 0, threshold, false)
        }

        val intraSimilarities = ArrayList<Float>()
        for (i in valid.indices) {
            for (j in i + 1 until valid.size) {
                intraSimilarities.add(dtwSimilarity(valid[i], valid[j]))
            }
        }
        val intraMin = intraSimilarities.minOrNull() ?: 1f
        val effectiveThreshold = max(
            minDynamicThresholdFloor,
            min(threshold, (intraMin - dynamicThresholdMargin).coerceIn(0f, 1f)),
        )

        var best = -1f
        var second = -1f
        var hits = 0
        for (template in valid) {
            val sim = dtwSimilarity(candidate, template)
            if (sim > best) {
                second = best
                best = sim
            } else if (sim > second) {
                second = sim
            }
            if (sim >= effectiveThreshold) hits++
        }

        val required = min(requiredMatches.coerceAtLeast(1), valid.size)
        val bestSecondGap = if (second >= 0f) best - second else 0f
        val gapOk = valid.size < 2 || hits >= 2 || bestSecondGap <= maxBestSecondGap
        return Match(
            best = best.coerceAtLeast(0f),
            secondBest = second.coerceAtLeast(0f),
            hits = hits,
            threshold = effectiveThreshold,
            detected = hits >= required && gapOk,
        )
    }

    private fun reshapeAndNormalize(flat: FloatArray): Array<FloatArray> {
        if (flat.isEmpty() || flat.size % featureDim != 0) return emptyArray()
        val out = Array(flat.size / featureDim) { FloatArray(featureDim) }
        var p = 0
        for (frame in out) {
            for (i in frame.indices) frame[i] = flat[p++]
            var norm = 0f
            for (v in frame) norm += v * v
            norm = sqrt(max(1e-10f, norm))
            for (i in frame.indices) frame[i] /= norm
        }
        return out
    }

    private fun dtwSimilarity(a: Array<FloatArray>, b: Array<FloatArray>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val n = a.size
        val m = b.size
        val band = max(dtwBand, abs(n - m))
        val dp = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }
        dp[0][0] = 0f
        for (i in 1..n) {
            for (j in max(1, i - band)..min(m, i + band)) {
                val cost = cosineDistance(a[i - 1], b[j - 1])
                dp[i][j] = cost + min(dp[i - 1][j], min(dp[i][j - 1], dp[i - 1][j - 1]))
            }
        }
        val avgCost = dp[n][m] / max(1f, (n + m).toFloat())
        return (1f - avgCost / 2f).coerceIn(0f, 1f)
    }

    private fun cosineDistance(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in 0 until min(a.size, b.size)) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val denom = sqrt(max(1e-10f, na)) * sqrt(max(1e-10f, nb))
        return 1f - (dot / denom).coerceIn(-1f, 1f)
    }
}

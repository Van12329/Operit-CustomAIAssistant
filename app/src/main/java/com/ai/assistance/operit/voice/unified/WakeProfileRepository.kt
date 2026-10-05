package com.ai.assistance.operit.voice.unified

import kotlinx.coroutines.flow.Flow

/** Persistence boundary owned by the unified voice subsystem. */
interface WakeProfileRepository {
    val templates: Flow<List<FloatArray>>
    suspend fun saveTemplates(templates: List<FloatArray>)
}

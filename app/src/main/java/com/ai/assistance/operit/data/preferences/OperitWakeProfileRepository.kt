package com.ai.assistance.operit.data.preferences

import com.ai.assistance.operit.voice.unified.WakeProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Operit storage adapter for the unified voice subsystem's Wake profile port. */
class OperitWakeProfileRepository(
    private val preferences: WakeWordPreferences,
) : WakeProfileRepository {
    override val templates: Flow<List<FloatArray>> =
        preferences.personalWakeTemplatesFlow.map { stored ->
            stored.mapNotNull { item ->
                item.features.takeIf { it.isNotEmpty() }?.toFloatArray()
            }
        }

    override suspend fun saveTemplates(templates: List<FloatArray>) {
        preferences.savePersonalWakeTemplates(
            templates.filter { it.isNotEmpty() }.map {
                WakeWordPreferences.PersonalWakeTemplate(features = it.toList())
            }
        )
    }
}

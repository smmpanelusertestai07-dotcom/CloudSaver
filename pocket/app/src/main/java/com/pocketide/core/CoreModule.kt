package com.pocketide.core

import com.pocketide.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

fun createSettingsStore(graph: AppGraph): SettingsStore = MemorySettings().also { graph.hashCode() }

/** Placeholder until the settings module persists them. */
private class MemorySettings : SettingsStore {
    private val flow = MutableStateFlow(Settings())
    override val settings: StateFlow<Settings> = flow
    override fun update(change: (Settings) -> Settings) = flow.update(change)
}

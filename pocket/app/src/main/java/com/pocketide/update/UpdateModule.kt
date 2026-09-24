package com.pocketide.update

import android.app.Activity
import com.pocketide.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun createAppUpdater(graph: AppGraph): AppUpdater = StubUpdater().also { graph.hashCode() }

private class StubUpdater : AppUpdater {
    override val state: StateFlow<UpdateState> = MutableStateFlow(UpdateState.UpToDate)
    override suspend fun check() = Unit
    override suspend fun download() = Unit
    override fun install(activity: Activity) = Unit
    override fun schedule() = Unit
}

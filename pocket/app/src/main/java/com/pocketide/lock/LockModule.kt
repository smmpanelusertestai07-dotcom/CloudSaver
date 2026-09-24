package com.pocketide.lock

import androidx.fragment.app.FragmentActivity
import com.pocketide.AppGraph
import com.pocketide.model.AccessState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun createAccessGuard(graph: AppGraph): AccessGuard = StubAccess().also { graph.hashCode() }

fun createAppLock(graph: AppGraph): AppLock = StubLock().also { graph.hashCode() }

private class StubAccess : AccessGuard {
    override val state: StateFlow<AccessState> = MutableStateFlow(AccessState())
    override suspend fun check() = Unit
    override fun startPeriodicChecks() = Unit
}

private class StubLock : AppLock {
    override val unlocked: StateFlow<Boolean> = MutableStateFlow(true)
    override fun lockNow() = Unit
    override fun onBackground() = Unit
    override fun onForeground() = Unit
    override fun authenticate(activity: FragmentActivity, title: String, onDone: (Boolean) -> Unit) = onDone(true)
    override fun deviceSecure() = true
}

package com.pocketide.linux

import com.pocketide.AppGraph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

fun createComputer(graph: AppGraph): Computer = StubComputer().also { graph.hashCode() }

private class StubComputer : Computer {
    override val state: StateFlow<ComputerState> = MutableStateFlow(ComputerState.NotInstalled)
    override suspend fun install() = throw UnsupportedOperationException("stub")
    override suspend fun reset() = throw UnsupportedOperationException("stub")
    override fun start(command: LinuxCommand): Process = throw UnsupportedOperationException("stub")
    override suspend fun run(command: LinuxCommand, onLine: (String) -> Unit): Int = throw UnsupportedOperationException("stub")
    override fun stop(process: Process) = process.destroy()
    override suspend fun info() = ComputerInfo("", 0, "", "", null, null, null, 0, null)
    override fun sizeBytes(): Long = 0
}

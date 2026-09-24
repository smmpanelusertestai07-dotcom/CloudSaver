package com.pocketide.git

import com.pocketide.AppGraph
import java.io.File

fun createGitGate(graph: AppGraph): GitGate = StubGitGate().also { graph.hashCode() }

private class StubGitGate : GitGate {
    private fun no(): Nothing = throw IllegalStateException("stub")
    override suspend fun clone(cloneUrl: String, bareRepo: File, token: String) = no()
    override suspend fun fetch(bareRepo: File, token: String) = no()
    override suspend fun checkPost(bareRepo: File, branch: String, knownValues: List<String>): Verdict = no()
    override suspend fun push(bareRepo: File, branch: String, token: String, knownValues: List<String>): PushResult = no()
    override suspend fun stats(bareRepo: File, branch: String, base: String): Pair<Int, Int> = no()
}

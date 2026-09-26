package com.pocketide.ui.screens.project

import com.pocketide.projects.FakeBudget
import com.pocketide.sessions.FakeProjects
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A mobile-data yes for a project's clone covers that one try, however it ends, as an agent's Add and an app update do. */
class CloneOnceTest {
    private val budget = FakeBudget().apply { allowOnce(KIND, 80L * 1024 * 1024) }

    @Test
    fun `the yes ends when the clone is done`() = runBlocking {
        val result = cloneOnce(FakeProjects(emptyList()), budget, PROJECT, granted = KIND)

        assertTrue(result.isSuccess)
        assertEquals(listOf(KIND), budget.ended)
        assertTrue("a later clone asks again", KIND !in budget.grants)
    }

    @Test
    fun `the yes ends when the clone fails`() = runBlocking {
        val failing = FakeProjects(emptyList()) { throw IOException("connection reset") }

        val result = cloneOnce(failing, budget, PROJECT, granted = KIND)

        assertEquals("connection reset", result.exceptionOrNull()?.message)
        assertEquals(listOf(KIND), budget.ended)
    }

    @Test
    fun `the yes ends when the screen is left during the clone`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val slow = FakeProjects(emptyList()) {
            started.complete(Unit)
            awaitCancellation()
        }
        val screen = launch { cloneOnce(slow, budget, PROJECT, granted = KIND) }
        started.await()

        screen.cancel()
        screen.join()

        assertEquals(listOf(KIND), budget.ended)
    }

    @Test
    fun `a try the owner did not allow on mobile data ends no yes`() = runBlocking {
        cloneOnce(FakeProjects(emptyList()), budget, PROJECT, granted = null)

        assertTrue(budget.ended.isEmpty())
        assertTrue(KIND in budget.grants)
    }

    private companion object {
        const val PROJECT = "octo/app"
        const val KIND = "clone"
    }
}

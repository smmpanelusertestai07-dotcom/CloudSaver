package com.pocketide.ui.manage

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ActionRunnerTest {
    private val app = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val screens = mutableListOf<CoroutineScope>()

    private fun screen(): ActionRunner {
        val scope = CoroutineScope(Job() + Dispatchers.Default)
        screens += scope
        return ActionRunner(scope, app)
    }

    @After
    fun tearDown() {
        screens.forEach { it.cancel() }
        app.cancel()
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(5_000) {
        while (!condition()) delay(5)
    }

    @Test
    fun `a second tap on the same key does nothing while the first runs`() = runBlocking {
        val runner = screen()
        val gate = CompletableDeferred<Unit>()
        val runs = AtomicInteger()
        repeat(5) { runner.run("save") { runs.incrementAndGet(); gate.await() } }
        waitUntil { runs.get() == 1 }
        assertTrue(runner.isBusy("save"))
        gate.complete(Unit)
        waitUntil { !runner.isBusy("save") }
        assertEquals(1, runs.get())
    }

    @Test
    fun `work that outlives the screen cannot be started twice from a new screen`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val runs = AtomicInteger()
        val first = screen()
        first.run("delete-everything", outlivesScreen = true) { runs.incrementAndGet(); gate.await() }
        waitUntil { runs.get() == 1 }
        screens.first().cancel()
        val second = screen()
        assertTrue(second.isBusy("delete-everything"))
        second.run("delete-everything", outlivesScreen = true) { runs.incrementAndGet() }
        gate.complete(Unit)
        waitUntil { !second.isBusy("delete-everything") }
        assertEquals(1, runs.get())
    }

    @Test
    fun `leaving the screen does not cancel outliving work`() = runBlocking {
        val runner = screen()
        val finished = CountDownLatch(1)
        runner.run("reset", outlivesScreen = true) {
            delay(100)
            finished.countDown()
        }
        screens.single().cancel()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a failure becomes a value and frees the key`() = runBlocking {
        val runner = screen()
        val seen = CompletableDeferred<Throwable>()
        runner.run<Unit>("x", onFailure = { seen.complete(it) }) { error("boom") }
        assertEquals("boom", withTimeout(5_000) { seen.await() }.message)
        waitUntil { !runner.isBusy("x") }
    }

    @Test
    fun `a tap on a screen already gone never leaves its key stuck`() = runBlocking {
        val runner = screen()
        screens.single().cancel()
        runner.run("late") { Unit }
        waitUntil { !runner.isBusy("late") }
        assertFalse(runner.isBusy("late"))
    }

    @Test
    fun `many keys from many threads are all released`() = runBlocking {
        val runner = screen()
        val threads = (0 until 8).map { t ->
            Thread { repeat(50) { i -> runner.run("k$t-$i", outlivesScreen = i % 2 == 0) { delay(1) } } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        waitUntil { runner.busy.isEmpty() && OutlivingWork.keys.none { it.startsWith("k") } }
    }
}

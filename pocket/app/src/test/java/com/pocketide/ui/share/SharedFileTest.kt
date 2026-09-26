package com.pocketide.ui.share

import com.pocketide.ui.onMainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedFileTest {
    private val main = StandardTestDispatcher(name = "main")

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a share's result is shown on the main thread even when it arrives on a background one`() = runTest(main) {
        val testThread = Thread.currentThread()
        val shownOn = mutableListOf<Thread>()
        val say = onMainThread { shownOn += Thread.currentThread() }

        withContext(Dispatchers.Default) { say("Added.") }

        assertEquals(listOf(testThread), shownOn)
    }
}

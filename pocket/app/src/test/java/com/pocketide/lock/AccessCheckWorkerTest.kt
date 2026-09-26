package com.pocketide.lock

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class AccessCheckWorkerTest {

    @Test
    fun `the background check runs every six hours, and only with a network`() {
        val request = AccessCheckWorker.request()

        assertEquals(TimeUnit.HOURS.toMillis(6), request.workSpec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }
}

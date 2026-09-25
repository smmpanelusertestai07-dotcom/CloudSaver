package com.pocketide.sync

import com.pocketide.secrets.SecretKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Variables and Secrets changed on two phones: each change reaches the other, none is dropped. */
class SecretsSyncTest {
    private val clock = FakeClock()
    private val accounts = FakeAccounts(clock)
    private val project = "owner/app"

    private fun phone(id: String) = TestPhone(accounts, clock, deviceId = id, deviceName = id).apply { useSecretStore() }

    private val TestPhone.store get() = secretStore!!

    private fun TestPhone.names() = store.values.value.map { it.name }.toSet()

    private suspend fun TestPhone.value(name: String) = store.reveal(project, name)?.let { String(it) }

    @Test
    fun eachPhonesNewValuesReachTheOtherAndStayInDrive() = runBlocking {
        val a = phone("phone-a")
        a.store.set(project, "V1", SecretKind.VARIABLE, "one".toCharArray())
        a.engine.syncNow()
        val b = phone("phone-b")
        b.engine.restore(RestoreChoice.WIFI_ONLY)
        assertEquals(setOf("V1"), b.names())

        clock.advance(Durations.MINUTE)
        a.store.set(project, "A_ADDED", SecretKind.VARIABLE, "from a".toCharArray())
        a.engine.syncNow()

        clock.advance(Durations.MINUTE)
        b.engine.takeOver()
        assertEquals("the other phone's new value arrives", setOf("V1", "A_ADDED"), b.names())

        clock.advance(Durations.MINUTE)
        b.store.set(project, "B_ADDED", SecretKind.SECRET, "from b".toCharArray())
        b.engine.syncNow()

        val reader = phone("reader")
        reader.engine.restore(RestoreChoice.WIFI_ONLY)
        assertEquals("Drive keeps both phones' values", setOf("V1", "A_ADDED", "B_ADDED"), reader.names())
    }

    @Test
    fun changesMadeOnBothPhonesAreMergedValueByValue() = runBlocking {
        val a = phone("phone-a")
        a.store.set(project, "V1", SecretKind.VARIABLE, "one".toCharArray())
        a.store.set(project, "OLD", SecretKind.VARIABLE, "old".toCharArray())
        a.engine.syncNow()
        val b = phone("phone-b")
        b.engine.restore(RestoreChoice.WIFI_ONLY)

        // Phone A, which holds the lease, adds one value and removes another.
        clock.advance(Durations.MINUTE)
        a.store.set(project, "A_ADDED", SecretKind.VARIABLE, "from a".toCharArray())
        a.store.remove(project, "OLD")
        a.engine.syncNow()
        // Meanwhile the owner changes phone B's copy too, then uses phone B.
        clock.advance(Durations.MINUTE)
        b.store.set(project, "B_ADDED", SecretKind.SECRET, "from b".toCharArray())
        b.store.set(project, "V1", SecretKind.VARIABLE, "changed on b".toCharArray())
        b.engine.takeOver()

        assertEquals(setOf("V1", "A_ADDED", "B_ADDED"), b.names())
        assertEquals("changed on b", b.value("V1"))
        val reader = phone("reader")
        reader.engine.restore(RestoreChoice.WIFI_ONLY)
        assertEquals(setOf("V1", "A_ADDED", "B_ADDED"), reader.names())
        assertEquals("changed on b", reader.value("V1"))
        assertEquals("from a", reader.value("A_ADDED"))
    }
}

package com.pocketide.ui.manage

import com.pocketide.ui.components.Tone
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneRequirementsTest {
    private val gib = 1L shl 30

    private val ownersPhone = PhoneFacts(
        androidSdk = 33, androidRelease = "13", arm64 = true, playServices = true,
        totalRamBytes = (3.6 * gib).toLong(), freeStorageBytes = 20_000_000_000L, screenLock = true,
    )

    private fun tone(facts: PhoneFacts, label: String) = PhoneRequirements.check(facts).single { it.label == label }.tone

    @Test
    fun `a 4 GB phone meets the minimum but not the recommendation`() {
        assertEquals(Tone.WARN, tone(ownersPhone, "Memory"))
        assertEquals(Tone.OK, tone(ownersPhone, "Android"))
        assertEquals(Tone.OK, tone(ownersPhone, "Free storage"))
        assertEquals("This phone meets the minimum.", PhoneRequirements.summary(PhoneRequirements.check(ownersPhone)).text)
    }

    @Test
    fun `anything below the minimum is red`() {
        val weak = ownersPhone.copy(androidSdk = 28, arm64 = false, totalRamBytes = 3 * gib, freeStorageBytes = 5_000_000_000L, playServices = false)
        listOf("Android", "Processor", "Memory", "Free storage", "Google Play services").forEach { assertEquals(it, Tone.ERROR, tone(weak, it)) }
        assertEquals(Tone.ERROR, PhoneRequirements.summary(PhoneRequirements.check(weak)).tone)
    }

    @Test
    fun `a strong phone meets the recommendation`() {
        val strong = ownersPhone.copy(totalRamBytes = 8 * gib, freeStorageBytes = 100_000_000_000L)
        assertEquals("This phone meets the recommendation.", PhoneRequirements.summary(PhoneRequirements.check(strong)).text)
    }

    @Test
    fun `unknown facts are neutral, not failures`() {
        val unknown = ownersPhone.copy(totalRamBytes = 0, playServices = null, screenLock = null)
        assertEquals(Tone.NEUTRAL, tone(unknown, "Memory"))
        assertEquals(Tone.NEUTRAL, tone(unknown, "Google Play services"))
        assertEquals(Tone.NEUTRAL, tone(unknown, "Screen lock"))
    }
}

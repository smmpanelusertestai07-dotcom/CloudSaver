package com.pocketide.ui.manage

import com.pocketide.sync.PhoneSpace
import com.pocketide.sync.StorageSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneSpaceTextTest {
    private val gb = 1_000_000_000L
    private val storage = StorageSummary(phoneBytes = 7 * gb, phoneLimitBytes = 8 * gb, phoneFreeBytes = 20 * gb)

    @Test
    fun theNoticeShowsOnlyOnceTheShareIsNearlyFull() {
        assertNull(PhoneSpaceText.of(storage.copy(phone = PhoneSpace.OK)))
        assertEquals(
            "PocketIDE is using most of its space on this phone: 7 GB of its 8 GB limit, 20 GB free on the phone.",
            PhoneSpaceText.of(storage.copy(phone = PhoneSpace.NEARLY_FULL)),
        )
        assertEquals(
            "PocketIDE's space on this phone is full: 7 GB of its 8 GB limit, 20 GB free on the phone. " +
                "Clean now removes caches that are rebuilt when needed.",
            PhoneSpaceText.of(storage.copy(phone = PhoneSpace.FULL)),
        )
    }

    @Test
    fun cleaningSaysWhatItFreedOrWhatIsLeftToDo() {
        assertEquals("Freed 1.5 GB.", PhoneSpaceText.cleaned(1_500_000_000L))
        assertEquals("Nothing more to clean now. Raise the limit or delete old sessions.", PhoneSpaceText.cleaned(0))
    }
}

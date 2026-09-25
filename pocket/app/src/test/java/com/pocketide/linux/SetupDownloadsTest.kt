package com.pocketide.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupDownloadsTest {
    @Test fun `a confirmed set-up on mobile data covers every download it asks the data rules for`() {
        val sizes = ComputerSetup.setupDownloads()
        assertEquals(setOf(ComputerSetup.KIND_SETUP, ComputerSetup.KIND_UPDATE), sizes.keys)
        val setup = sizes.getValue(ComputerSetup.KIND_SETUP)
        assertTrue(setup >= LinuxPins.ubuntuBase.bytes + LinuxPins.codeServer.bytes + ComputerSetup.APT_BYTES)
        assertTrue(sizes.getValue(ComputerSetup.KIND_UPDATE) >= ComputerSetup.UPDATE_BYTES)
    }
}

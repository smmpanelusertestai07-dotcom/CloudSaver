package com.pocketide.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardedPortsTest {
    @Test
    fun `a port opens at GitHub's own forwarding address`() {
        assertEquals("https://fluffy-train-7x9-3000.app.github.dev/", ForwardedPorts.url("fluffy-train-7x9", 3000))
    }

    @Test
    fun `the desktop joins at once, scaled to the phone`() {
        val url = ForwardedPorts.desktop("fluffy-train-7x9")
        assertTrue(url, url.startsWith("https://fluffy-train-7x9-6080.app.github.dev/vnc.html?"))
        assertTrue(url, "autoconnect=true" in url && "resize=scale" in url)
    }

    @Test
    fun `only real port numbers are taken`() {
        assertEquals(8080, ForwardedPorts.port(" 8080 "))
        assertNull(ForwardedPorts.port("0"))
        assertNull(ForwardedPorts.port("65536"))
        assertNull(ForwardedPorts.port("3000/admin"))
        assertThrows(IllegalArgumentException::class.java) { ForwardedPorts.url("x", 0) }
    }
}

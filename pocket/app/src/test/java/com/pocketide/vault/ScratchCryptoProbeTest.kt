package com.pocketide.vault

import org.junit.Test

/** Throwaway probe, not committed. */
class ScratchCryptoProbeTest {
    @Test
    fun `probe - a lone surrogate in the extra password`() {
        val result = runCatching { HalfPassword.derive("pass\uD83D".toCharArray(), Argon2Cost(64, 1, 1), ByteArray(16)) }
        println("SCRATCH lone surrogate: ${result.exceptionOrNull()}")
    }

    @Test
    fun `probe - a pasted line of four million characters`() {
        val huge = "AGE-SECRET-KEY-1" + "Q".repeat(4_000_000)
        val start = System.nanoTime()
        val result = runCatching { KeyCopy.parse(huge) }
        println("SCRATCH huge line: ${result.exceptionOrNull()?.message} in ${(System.nanoTime() - start) / 1_000_000} ms")
    }
}

package com.pocketide.sessions

import com.pocketide.core.Ist
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class BranchNamesTest {
    private lateinit var zone: TimeZone

    @Before
    fun indianTime() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
    }

    @After
    fun restoreZone() = TimeZone.setDefault(zone)

    private fun at(utc: String) = Instant.parse(utc).toEpochMilli()

    @Test
    fun `the date is the day in India, not in UTC`() {
        assertEquals("2026-09-24", Ist.branchDate(at("2026-09-23T20:00:00Z")))
        assertEquals("2026-09-24", Ist.branchDate(at("2026-09-24T18:29:59Z")))
        assertEquals("2026-09-25", Ist.branchDate(at("2026-09-24T18:30:00Z")))
        assertEquals(
            "pocket/claude/2026-09-24-login-fix",
            BranchNames.base("claude", Ist.branchDate(at("2026-09-23T20:00:00Z")), BranchNames.slug("Login fix")),
        )
    }

    @Test
    fun `slugs keep lowercase letters, digits and single hyphens`() {
        assertEquals("fix-the-login-bug-2", BranchNames.slug("  Fix the LOGIN bug #2!! "))
        assertEquals("cafe-creme-brulee", BranchNames.slug("Café crème brûlée"))
        assertEquals("a-b", BranchNames.slug("a --- b"))
        assertEquals("tests-ci", BranchNames.slug("--tests & ci--"))
    }

    @Test
    fun `slugs fall back to session and stop at 40 characters without a trailing hyphen`() {
        assertEquals("session", BranchNames.slug(null))
        assertEquals("session", BranchNames.slug("   "))
        assertEquals("session", BranchNames.slug("नमस्ते 🙂"))
        val long = BranchNames.slug("make the settings screen remember the chosen theme across restarts")
        assertTrue(long.length <= 40)
        assertEquals("make-the-settings-screen-remember-the-ch", long)
        assertEquals("abcdefghij-abcdefghij-abcdefghij-abcdefg", BranchNames.slug("abcdefghij abcdefghij abcdefghij abcdefg-hij"))
        assertEquals("abcdefghij-abcdefghij-abcdefghij-abcdef", BranchNames.slug("abcdefghij abcdefghij abcdefghij abcdef hij"))
    }

    @Test
    fun `a taken name gets the first free number from 2`() {
        val base = "pocket/claude/2026-09-24-session"
        assertEquals(base, BranchNames.unique(base, emptySet()))
        assertEquals("$base-2", BranchNames.unique(base, setOf(base)))
        assertEquals("$base-4", BranchNames.unique(base, setOf(base, "$base-2", "$base-3")))
        assertEquals("$base-2", BranchNames.unique(base, setOf(base, "$base-3")))
    }

    @Test
    fun `agent ids become safe branch parts`() {
        assertEquals("pocket/codex/", BranchNames.prefix("codex"))
        assertEquals("pocket/kilocode-kilo-code/", BranchNames.prefix("kilocode.kilo-code"))
    }

    @Test
    fun `public repositories get neutral names that say nothing about the chat`() {
        assertEquals("5f0c2a4e", BranchNames.neutralSlug(SESSION_A))
    }
}

package com.pocketide.github

import com.pocketide.core.Settings
import com.pocketide.core.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubAppChoiceTest {
    private class MemorySettings : SettingsStore {
        private val flow = MutableStateFlow(Settings())
        override val settings: StateFlow<Settings> = flow
        override fun update(change: (Settings) -> Settings) = flow.update(change)
    }

    private val settings = MemorySettings()
    private val built = GitHubApp("Iv1.0123456789abcdef", "pocketide-built")

    @Test
    fun `client IDs are GitHub App client IDs, trimmed`() {
        assertEquals("Iv1.0123456789abcdef", GitHubAppChoice.clientId("  Iv1.0123456789abcdef\n"))
        assertEquals("Iv23liAbC0123456789z", GitHubAppChoice.clientId("Iv23liAbC0123456789z"))
        listOf(
            "",
            "Iv1.0123456789abcdeg", // not hex
            "Iv1.0123456789abcde", // 19 characters
            "Iv23liAbC0123456789zz", // 21 characters
            "Iv23liAbC012345-789z", // not alphanumeric
            "Ov23liAbC0123456789z", // an OAuth app, not a GitHub App
            "123456", // the App ID, not its client ID
            "Iv1.0123 456789abcdef",
        ).forEach { assertNull(it, GitHubAppChoice.clientId(it)) }
    }

    @Test
    fun `slugs come from the bare name or the App's pasted address`() {
        assertEquals("my-pocketide", GitHubAppChoice.slug(" my-pocketide "))
        assertEquals("my-pocketide", GitHubAppChoice.slug("https://github.com/apps/my-pocketide"))
        assertEquals("my-pocketide", GitHubAppChoice.slug("github.com/apps/My-PocketIDE/"))
        assertEquals("a".repeat(34), GitHubAppChoice.slug("a".repeat(34)))
        listOf("", "a".repeat(35), "my pocketide", "../evil", "my_pocketide", "https://github.com/settings/apps/x")
            .forEach { assertNull(it, GitHubAppChoice.slug(it)) }
    }

    @Test
    fun `the build's App is used until the owner enters one`() {
        val choice = GitHubAppChoice(settings, GitHubApp(" Iv1.0123456789abcdef ", " pocketide-built "))
        assertEquals(built, choice.current())
        assertNull(choice.entered())
        assertTrue(choice.hasBuiltIn)
    }

    @Test
    fun `an App the owner entered wins over the build's, and clearing it goes back`() {
        val choice = GitHubAppChoice(settings, built)
        assertEquals(GitHubAppSave.Saved(appChanged = true), choice.save(" Iv23liAbC0123456789z ", "github.com/apps/Mine"))
        val mine = GitHubApp("Iv23liAbC0123456789z", "mine")
        assertEquals(mine, choice.current())
        assertEquals(mine, choice.entered())
        assertEquals("Iv23liAbC0123456789z", settings.settings.value.gitHubAppClientId)

        assertEquals(GitHubAppSave.Saved(appChanged = false), choice.save("Iv23liAbC0123456789z", "renamed"))
        assertEquals(GitHubAppSave.Saved(appChanged = true), choice.clear())
        assertEquals(built, choice.current())
    }

    @Test
    fun `a build without an App is configured once the owner enters one`() {
        val choice = GitHubAppChoice(settings, GitHubApp("", ""))
        assertFalse(choice.current().configured)
        assertFalse(choice.hasBuiltIn)
        choice.save("Iv1.0123456789abcdef", "mine")
        assertTrue(choice.current().configured)
    }

    @Test
    fun `nothing wrong is saved, and each wrong field is named`() {
        val choice = GitHubAppChoice(settings, built)
        assertEquals(
            GitHubAppSave.Invalid(GitHubAppChoice.BAD_CLIENT_ID, GitHubAppChoice.BAD_SLUG),
            choice.save("123456", "my app"),
        )
        assertEquals(GitHubAppSave.Invalid(null, GitHubAppChoice.BAD_SLUG), choice.save("Iv1.0123456789abcdef", ""))
        assertEquals(GitHubAppSave.Invalid(GitHubAppChoice.BAD_CLIENT_ID, null), choice.save("", "mine"))
        assertEquals(Settings(), settings.settings.value)
        assertEquals(built, choice.current())
    }

    @Test
    fun `a stored value that is not an App ID is ignored`() {
        settings.update { it.copy(gitHubAppClientId = "garbage", gitHubAppSlug = "mine") }
        assertEquals(built, GitHubAppChoice(settings, built).current())
    }
}

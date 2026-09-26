package com.pocketide.ui.screens.secrets

import com.pocketide.secrets.ProjectValue
import com.pocketide.secrets.SecretKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InheritedSecretsTest {
    private fun value(projectId: String?, name: String, kind: SecretKind = SecretKind.SECRET) =
        ProjectValue(projectId, name, kind, updatedAt = 1, pushedToGitHub = false)

    @Test
    fun `a global Secret sent from one project reads as sent there alone`() {
        val global = value(null, "API_KEY").copy(sentTo = setOf("octo/a"))
        assertTrue(SecretsText.sentHere(global, "octo/a"))
        assertFalse("project B's builds cannot use it yet", SecretsText.sentHere(global, "octo/b"))
        assertEquals("In GitHub for 1 project", SecretsText.chip(global))
        assertEquals("In GitHub for 2 projects", SecretsText.chip(global.copy(sentTo = setOf("octo/a", "octo/b"))))
        assertNull("never sent", SecretsText.chip(value(null, "API_KEY")))
        assertTrue(SecretsText.deleting(global).contains("octo/a"))
    }

    @Test
    fun `a project's own Secret is in GitHub once sent, and its deletion names the repository`() {
        val own = value("octo/app", "SIGNING_KEY").copy(pushedToGitHub = true)
        assertEquals("In GitHub", SecretsText.chip(own))
        assertTrue(SecretsText.deleting(own).contains("octo/app"))
        assertEquals("It is removed from this phone and your Drive.", SecretsText.deleting(value("octo/app", "SIGNING_KEY")))
    }

    @Test
    fun `a project can send each global Secret it uses and does not replace`() {
        val all = listOf(
            value(null, "NPM_TOKEN"),
            value(null, "SIGNING_KEY"),
            value(null, "API_URL", SecretKind.VARIABLE),
            value("octo/app", "signing_key"),
            value("octo/other", "PLAY_KEY"),
        )
        // The project's own SIGNING_KEY wins; a Variable never goes to GitHub; another project's value is not here.
        assertEquals(listOf("NPM_TOKEN"), inheritedSecrets(all, "octo/app").map { it.name })
        assertEquals(listOf("NPM_TOKEN", "SIGNING_KEY"), inheritedSecrets(all, "octo/other").map { it.name })
    }
}

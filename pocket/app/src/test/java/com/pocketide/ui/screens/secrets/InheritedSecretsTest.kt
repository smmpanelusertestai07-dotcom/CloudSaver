package com.pocketide.ui.screens.secrets

import com.pocketide.secrets.ProjectValue
import com.pocketide.secrets.SecretKind
import org.junit.Assert.assertEquals
import org.junit.Test

class InheritedSecretsTest {
    private fun value(projectId: String?, name: String, kind: SecretKind = SecretKind.SECRET) =
        ProjectValue(projectId, name, kind, updatedAt = 1, pushedToGitHub = false)

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

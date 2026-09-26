package com.pocketide.ui.shell

import com.pocketide.vault.Accounts
import com.pocketide.vault.TestPhone
import com.pocketide.vault.VaultKeyFiles
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** "Try now" on the key banner and the Safety check always ends in a sentence, never in silence. */
class KeySaveTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val accounts = Accounts()

    private suspend fun setUpPhone() = TestPhone(accounts, folder.newFolder()).vault().also { it.setUp() }

    @Test
    fun `a key saved to GitHub says so`() = runTest {
        val vault = setUpPhone()
        accounts.gitHub.repos.remove(VaultKeyFiles.KEYRING_REPO)

        assertEquals(KeySave.Saved, KeySave.of(vault))
        assertEquals("Your chats' key is saved to GitHub.", KeySave.Saved.message)
    }

    @Test
    fun `without the GitHub App it says to install it`() = runTest {
        val vault = setUpPhone()
        accounts.gitHub.repos.remove(VaultKeyFiles.KEYRING_REPO)
        accounts.gitHub.appInstalled = false

        val result = KeySave.of(vault) as KeySave.Failed
        assertTrue(result.appMissing)
        assertTrue(result.message, result.message.contains("Install it"))
    }

    @Test
    fun `offline or signed out, it says what is wrong`() = runTest {
        val vault = setUpPhone()
        accounts.gitHub.repos.remove(VaultKeyFiles.KEYRING_REPO)
        accounts.gitHub.failWrites = 10

        val offline = KeySave.of(vault) as KeySave.Failed
        assertFalse(offline.appMissing)
        assertTrue(offline.message, offline.message.isNotBlank())

        accounts.account.value = null
        val signedOut = KeySave.of(vault) as KeySave.Failed
        assertTrue(signedOut.message, signedOut.message.contains("GitHub"))
    }
}

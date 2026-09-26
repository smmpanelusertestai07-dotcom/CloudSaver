package com.pocketide.ui.screens.onboarding

import com.pocketide.vault.Accounts
import com.pocketide.vault.KeyState
import com.pocketide.vault.TestPhone
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KeyStepsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val accounts = Accounts()

    private fun newPhone() = TestPhone(accounts, folder.newFolder()).vault()

    @Test
    fun `a key that cannot be rebuilt can be replaced with a new one, so set-up finishes`() = runTest {
        newPhone().setUp()
        accounts.gitHub.keyring().files.clear()

        val phone = newPhone()
        val lost = KeySteps.restore(phone, password = null, onPasswordUsed = {})
        assertTrue("the old key is lost: $lost", lost is KeyPhase.Lost)

        assertEquals(KeyPhase.Ready(restored = false), KeySteps.startOver(phone))
        assertEquals(KeyState.Ready, phone.state.value)
    }

    @Test
    fun `a first phone gets a new key, and a second phone rebuilds it`() = runTest {
        assertEquals(KeyPhase.Ready(restored = false), KeySteps.restore(newPhone(), password = null, onPasswordUsed = {}))
        assertEquals(KeyPhase.Ready(restored = true), KeySteps.restore(newPhone(), password = null, onPasswordUsed = {}))
    }
}

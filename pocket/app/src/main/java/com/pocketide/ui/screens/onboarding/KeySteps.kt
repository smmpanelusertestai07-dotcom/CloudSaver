package com.pocketide.ui.screens.onboarding

import com.pocketide.core.Redact
import com.pocketide.ui.manage.PlainError
import com.pocketide.vault.GitHubAppMissingException
import com.pocketide.vault.KeyState
import com.pocketide.vault.VaultKeys
import com.pocketide.vault.WrongPasswordException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Where the Drive step's key set-up stands. */
internal sealed interface KeyPhase {
    data object Working : KeyPhase
    data class NeedsPassword(val wrong: Boolean) : KeyPhase
    data class Lost(val why: String) : KeyPhase

    /** [appMissing]: the keyring could not be made until PocketIDE's GitHub App is installed. */
    data class Failed(val why: String, val appMissing: Boolean = false) : KeyPhase
    data class Ready(val restored: Boolean) : KeyPhase
}

/** The Drive step's key actions, apart from the screen, each ending in the phase the owner sees. */
internal object KeySteps {
    /** Rebuilds the key from its halves, or makes the first one. [password] is wiped afterwards. */
    suspend fun restore(vault: VaultKeys, password: CharArray?, onPasswordUsed: () -> Unit): KeyPhase = phaseOf {
        try {
            when (val result = vault.restore(password)) {
                KeyState.Ready, KeyState.OnlyOnPhone -> {
                    if (password != null) onPasswordUsed()
                    KeyPhase.Ready(restored = true)
                }
                KeyState.NeedsPassword -> KeyPhase.NeedsPassword(wrong = password != null)
                KeyState.None -> {
                    vault.setUp()
                    KeyPhase.Ready(restored = false)
                }
                is KeyState.Lost -> KeyPhase.Lost(Redact.text(result.why))
            }
        } catch (_: WrongPasswordException) {
            KeyPhase.NeedsPassword(wrong = true)
        } finally {
            password?.fill('\u0000')
        }
    }

    /**
     * "Make a new key", after the owner confirmed the old key is gone for good. Set-up refuses
     * here, since it never replaces a vault it cannot open: starting over is the owner's call.
     */
    suspend fun startOver(vault: VaultKeys): KeyPhase = phaseOf {
        vault.startOver()
        KeyPhase.Ready(restored = false)
    }

    /** Opens the vault with a saved key copy. */
    suspend fun importKeyCopy(vault: VaultKeys, text: String): KeyPhase = phaseOf {
        vault.importKeyCopy(text)
        KeyPhase.Ready(restored = true)
    }

    /** What [block] ended in; a failure becomes the phase that tells the owner what to do. */
    private suspend fun phaseOf(block: suspend () -> KeyPhase): KeyPhase = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        KeyPhase.Failed("No connection. Check the internet and try again.")
    } catch (e: GitHubAppMissingException) {
        KeyPhase.Failed(e.message.orEmpty(), appMissing = true)
    } catch (e: Exception) {
        KeyPhase.Failed(PlainError.of(e))
    }
}

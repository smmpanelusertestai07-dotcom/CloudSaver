package com.pocketide.vault

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream

sealed interface KeyState {
    data object None : KeyState
    data object Ready : KeyState
    /**
     * Half G of the current key is not saved (GitHub gone, or the keyring public or shared):
     * "Your chats' key is only on this phone" until reconnected; [VaultKeys.notice] says why.
     */
    data object OnlyOnPhone : KeyState
    /** A new phone (or one that fell behind) with the extra password on: ask for it to unwrap Half G. */
    data object NeedsPassword : KeyState
    /** Neither the phone key nor both halves are available. */
    data class Lost(val why: String) : KeyState
}

enum class RekeyReason { KEYRING_PUBLIC, KEYRING_COLLABORATOR, KEYRING_DELETED, OWNER_ASKED, MOVED_ACCOUNT }

data class KeyringCheck(val exists: Boolean, val isPrivate: Boolean, val collaborators: List<String>, val actionsDisabled: Boolean)

/**
 * The vault key: a random X25519 identity in the `age` format. The full key sits in this phone's
 * Keystore-wrapped storage; Half D (random) in Drive; Half G = key XOR Half D in the private
 * GitHub repo `pocketide-keyring`. Either the phone, or D + G together, rebuild it.
 */
interface VaultKeys {
    val state: StateFlow<KeyState>

    /**
     * First phone: makes the key, stores both halves and the phone copy. A Google account that
     * already has a vault is restored instead, never replaced: when that vault cannot be opened
     * this throws [VaultException] with the reason (the extra password is needed, or the key is
     * lost), and the owner may then choose [startOver].
     */
    suspend fun setUp()

    /**
     * "Start with a new key", after the owner confirmed that the old key is gone for good: makes a
     * new key for this Google account although its old vault cannot be opened. Chats saved with
     * the old key stay unreadable. Does nothing when this phone holds a key.
     */
    suspend fun startOver()

    /**
     * New phone or reinstall: fetches both halves and rebuilds the key. Returns Ready,
     * NeedsPassword (call again with the password), None (no vault in this Google account) or
     * Lost with a plain sentence. A wrong password throws [WrongPasswordException]; network
     * errors are thrown as they are. The caller clears [extraPassword] afterwards.
     */
    suspend fun restore(extraPassword: CharArray? = null): KeyState

    /**
     * New key and new halves; used on a public keyring, a collaborator, or a move. Older keys stay
     * on the phone and in Drive's key history; the sync engine re-encrypts every object whose
     * keyGeneration is below [generation].
     */
    suspend fun rekey(reason: RekeyReason)

    /**
     * Checks the keyring repo (exists, private, only the owner, Actions off) and keeps both halves
     * saved: re-keys when the current Half G is exposed, makes a deleted keyring again with fresh
     * halves, and finishes a change a restart interrupted. Call it on every sync. Throws
     * NotConnectedException when GitHub is not connected; the state is then OnlyOnPhone.
     */
    suspend fun checkKeyring(): KeyringCheck

    /**
     * Sets (or clears, with null) the optional extra password wrapping Half G (Argon2id). The key
     * is split afresh, so an older plain Half G in the keyring's git history pairs with nothing.
     * The caller clears [password] afterwards.
     */
    suspend fun setExtraPassword(password: CharArray?)

    /**
     * "Save a key copy": every key this phone holds, newest first, in age's identity-file format
     * with a comment per key; shown once for the owner to keep.
     */
    suspend fun exportKeyCopy(): String

    /**
     * Rebuilds from a saved key copy (the double-loss path), checked against the key check in
     * Drive; then saves fresh halves when GitHub is there. Throws [VaultException] when the text is
     * not a key copy or does not open this vault.
     */
    suspend fun importKeyCopy(text: String)

    /**
     * After "Delete everything": drops every key from this phone (memory and sealed storage) and
     * returns to [KeyState.None], so the next set-up makes a new vault. The Drive side is the
     * caller's to erase; the Half G left in GitHub pairs with nothing once Half D is gone.
     */
    suspend fun forget()

    fun cipher(): VaultCipher

    /** Current key generation (bumped by every re-key). */
    fun generation(): Int

    /**
     * One plain sentence when the key needs the owner (for example after PocketIDE changed the
     * key because the keyring repo became public), or null when all is well.
     */
    val notice: StateFlow<String?> get() = NO_NOTICE
}

private val NO_NOTICE: StateFlow<String?> = MutableStateFlow(null)

/** A vault problem, with a plain sentence the owner can act on. */
open class VaultException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The keyring could not be made because PocketIDE's GitHub App is not installed on the owner's
 * account: signing in alone does not install it. The fix is its install page.
 */
class GitHubAppMissingException :
    VaultException(
        "PocketIDE's GitHub App is not installed on your GitHub account, so it cannot make your private " +
            "pocketide-keyring repository. Install it, then try again.",
    )

/** The extra password did not open Half G. */
class WrongPasswordException : VaultException("That password is not right. Check it and try again.")

/** age v1 encryption to the vault's X25519 recipient. */
interface VaultCipher {
    fun encrypt(plain: InputStream, out: OutputStream)
    fun decrypt(encrypted: InputStream, out: OutputStream)
    fun encryptBytes(plain: ByteArray): ByteArray
    fun decryptBytes(encrypted: ByteArray): ByteArray
}

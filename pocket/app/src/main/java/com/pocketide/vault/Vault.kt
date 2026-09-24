package com.pocketide.vault

import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream

sealed interface KeyState {
    data object None : KeyState
    data object Ready : KeyState
    /** GitHub (Half G) is missing: "Your chats' key is only on this phone" until reconnected. */
    data object OnlyOnPhone : KeyState
    /** A new phone with the extra password on: ask for it to unwrap Half G. */
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

    /** First phone: makes the key, stores both halves and the phone copy. */
    suspend fun setUp()

    /** New phone or reinstall: fetches both halves and rebuilds the key. */
    suspend fun restore(extraPassword: CharArray? = null): KeyState

    /** New key, re-encrypt, new halves; used on a public keyring, a collaborator, or a move. */
    suspend fun rekey(reason: RekeyReason)

    /** Visibility and collaborators of the keyring repo; re-keys automatically when unsafe. */
    suspend fun checkKeyring(): KeyringCheck

    /** Sets (or clears, with null) the optional extra password wrapping Half G (Argon2id). */
    suspend fun setExtraPassword(password: CharArray?)

    /** "Save a key copy": the age secret key text, shown once for the owner to keep. */
    suspend fun exportKeyCopy(): String

    /** Rebuilds from a saved key copy (the double-loss path). */
    suspend fun importKeyCopy(text: String)

    fun cipher(): VaultCipher

    /** Current key generation (bumped by every re-key). */
    fun generation(): Int
}

/** age v1 encryption to the vault's X25519 recipient. */
interface VaultCipher {
    fun encrypt(plain: InputStream, out: OutputStream)
    fun decrypt(encrypted: InputStream, out: OutputStream)
    fun encryptBytes(plain: ByteArray): ByteArray
    fun decryptBytes(encrypted: ByteArray): ByteArray
}

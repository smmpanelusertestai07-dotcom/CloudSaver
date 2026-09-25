package com.pocketide.vault

import kotlinx.serialization.Serializable

/** Where the key's pieces live. Other modules must leave these Drive files alone. */
object VaultKeyFiles {
    /** Drive: Half D, plain JSON (random bytes, useless alone). */
    const val HALF_D = "keyhalf-d"

    /** Drive: a known text encrypted to the current key; it proves a rebuilt key is right. */
    const val KEY_CHECK = "keycheck"

    /** Drive: older keys encrypted to the current key, so older files stay readable. */
    const val KEY_HISTORY = "keyhistory"

    /** GitHub: the owner's private repo that holds Half G. */
    const val KEYRING_REPO = "pocketide-keyring"
    const val HALF_G_PATH = "half-g.json"

    val DRIVE_NAMES: Set<String> = setOf(HALF_D, KEY_CHECK, KEY_HISTORY)
}

internal const val KEY_FILE_FORMAT = 1

/** Far above any real number of key changes; a larger generation in a key file means it is damaged. */
internal const val MAX_GENERATION = 1_000_000

/** One key on the phone ("vault.keys" is a JSON list of these, newest first). */
@Serializable
internal data class StoredKey(val generation: Int, val ageSecretKey: String) {
    override fun toString() = "StoredKey(generation=$generation)"
}

@Serializable
internal data class HalfEntry(val generation: Int, val half: String)

/** `keyhalf-d` in Drive. */
@Serializable
internal data class HalfDFile(
    val v: Int = KEY_FILE_FORMAT,
    val generation: Int,
    val half: String,
    /** Older halves the Half G in GitHub may still pair with while a change is being saved. */
    val previous: List<HalfEntry>? = null,
) {
    fun entries(): List<HalfEntry> = listOf(HalfEntry(generation, half)) + previous.orEmpty()
}

/** `half-g.json` in the keyring repo: either the plain half or the half wrapped by the extra password. */
@Serializable
internal data class HalfGFile(
    val v: Int = KEY_FILE_FORMAT,
    val generation: Int,
    val half: String? = null,
    val wrapped: String? = null,
)

/** The key derived from the extra password, kept (sealed) so new halves can be wrapped without asking again. */
@Serializable
internal data class PasswordRecord(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
    val salt: String,
    val key: String,
) {
    override fun toString() = "PasswordRecord(memoryKiB=$memoryKiB, iterations=$iterations, parallelism=$parallelism)"
}

@Serializable
internal enum class ChangeKind {
    /** The first key of a new vault. */
    NEW,

    /** A new key; older files are re-encrypted by the sync engine. */
    REKEY,

    /** The same key split again into fresh halves. */
    RESPLIT,
}

/** A key change in progress. It is kept until every step is saved, so a restart resumes it unchanged. */
@Serializable
internal data class KeyChange(
    val kind: ChangeKind,
    val generation: Int,
    val ageSecretKey: String,
    val halfD: String,
    val reason: String? = null,
) {
    override fun toString() = "KeyChange(kind=$kind, generation=$generation, reason=$reason)"
}

/** What this phone knows about its key beyond the key itself ("vault.state"). */
@Serializable
internal data class PhoneState(
    /** The generation whose Half D and Half G are both saved; below the key's generation means "only on this phone". */
    val savedGeneration: Int = 0,
    val change: KeyChange? = null,
    val password: PasswordRecord? = null,
    /** The extra password is on, even when this phone does not know it (it was set on another phone). */
    val passwordOn: Boolean = false,
    /** When the Drive side (key check, history) and the keyring's Actions switch were last verified. */
    val checkedAt: Long = 0,
    val actionsOff: Boolean = false,
)

/** The sentences the owner sees. Plain, short, with the way out. */
internal object VaultText {
    private const val WAY_OUT = " Open PocketIDE on a phone that still has the key, or import a saved key copy."

    const val NO_KEY = "The key for your chats is not on this phone yet."
    const val CONNECT_GITHUB_FIRST = "Connect GitHub first."
    const val CONNECT_GITHUB = "Your chats' key is only on this phone. Connect GitHub so its other half can be saved there."
    const val CONNECT_GITHUB_TO_RESTORE = "Connect GitHub so the other half of your key can be fetched."
    const val DRIVE_HALF_MISSING = "The Drive half of your key is missing.$WAY_OUT"
    const val DRIVE_HALF_DAMAGED = "The Drive half of your key is damaged.$WAY_OUT"
    const val DRIVE_FILE_DAMAGED = "A key file in Drive is damaged.$WAY_OUT"
    const val GITHUB_HALF_MISSING =
        "The GitHub half of your key is missing: pocketide-keyring or its key file is gone, " +
            "or the PocketIDE GitHub App cannot see that repository.$WAY_OUT"
    const val GITHUB_HALF_DAMAGED = "The GitHub half of your key is damaged.$WAY_OUT"
    const val HALVES_APART = "The key halves in Drive and GitHub belong to different keys.$WAY_OUT"
    const val HALVES_WRONG = "The key halves do not rebuild your key.$WAY_OUT"
    const val CHECK_MISSING = "The key check in Drive is missing, so a rebuilt key cannot be trusted.$WAY_OUT"
    const val NEWER_APP = "Your key was saved by a newer PocketIDE. Update the app first."
    const val ANOTHER_PHONE = "Another phone changed your chats' key. Try again after this phone has synced."
    const val ANOTHER_VAULT =
        "Your Google Drive now holds a key made on another phone, so this phone's key is no longer saved there."
    const val PASSWORD_NEEDED = "Your key has an extra password. Enter it to open your chats."
    const val KEYRING_NOT_MADE =
        "PocketIDE could not make your private pocketide-keyring repository on GitHub. If it already exists, " +
            "give the PocketIDE GitHub App access to it: on GitHub, Settings, Applications, PocketIDE, Configure."
    const val HISTORY_UNREADABLE = "Chats saved before the last key change may not open on this phone."
    const val KEYRING_PUBLIC =
        "Your pocketide-keyring repository is public, so PocketIDE changed your chats' key. " +
            "Make the repository private again so the new key can be backed up there."
    const val PASSWORD_AGAIN =
        "Enter your extra password again in Settings so the new key can be backed up on GitHub."
    const val KEYRING_REMADE =
        "Your pocketide-keyring repository was missing, so PocketIDE made it again with a fresh half of your key."
    const val MAKE_PRIVATE_FIRST = "Make pocketide-keyring private, with no one else added, before setting a password."
    const val EMPTY_PASSWORD = "Choose a password that is not empty."
    const val PASSWORD_UNUSABLE = "That password has a character PocketIDE cannot read. Type it again."
    const val NOT_A_KEY_COPY = "This is not a PocketIDE key copy."
    const val NO_VAULT_TO_OPEN = "There is no PocketIDE vault in this Google account to open."
    const val COPY_DOES_NOT_OPEN = "This key copy does not open the vault in this Google account. Use your newest key copy."

    fun keyringShared(people: List<String>) =
        "Other people can open your pocketide-keyring repository (${people.joinToString(", ")}), so PocketIDE " +
            "changed your chats' key. Remove them so the new key can be backed up there."
}

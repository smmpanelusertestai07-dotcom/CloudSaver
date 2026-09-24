package com.pocketide.vault

import com.pocketide.core.AppJson
import com.pocketide.core.SecureStore
import com.pocketide.github.GitHubApi
import com.pocketide.github.RepoInfo
import com.pocketide.google.DriveStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.util.Base64

internal val STORED_KEYS = ListSerializer(StoredKey.serializer())

/** A key file that exists but cannot be used; the message says which one. */
internal class DamagedKeyFile(message: String) : VaultException(message)

/**
 * The phone's copy, sealed with the Keystore by [SecureStore]: "vault.keys" (a JSON list of
 * {generation, ageSecretKey}, newest first) and "vault.state" (this phone's progress).
 */
internal class PhoneKeys(private val store: SecureStore, private val io: CoroutineDispatcher = Dispatchers.IO) {
    class Loaded(val keys: List<VaultKey>, val state: PhoneState)

    /** Read once when the vault starts, so its state reflects the phone from the first moment. */
    fun load(): Loaded = Loaded(loadKeys(), loadState())

    suspend fun saveKeys(keys: List<VaultKey>) = withContext(io) {
        val stored = keys.map { StoredKey(it.generation, it.identity.encoded()) }
        store.putString(KEYS, AppJson.encodeToString(STORED_KEYS, stored))
    }

    suspend fun saveState(state: PhoneState) = withContext(io) {
        store.putString(STATE, AppJson.encodeToString(PhoneState.serializer(), state))
    }

    suspend fun clear() = withContext(io) {
        store.delete(KEYS)
        store.delete(STATE)
    }

    private fun loadKeys(): List<VaultKey> {
        val json = store.getString(KEYS) ?: return emptyList()
        return try {
            AppJson.decodeFromString(STORED_KEYS, json).map { VaultKey(it.generation, AgeIdentity.parse(it.ageSecretKey)) }
        } catch (e: IllegalArgumentException) {
            // Unreadable here means the Keystore lost its key: the halves in Drive and GitHub rebuild it.
            emptyList()
        }
    }

    private fun loadState(): PhoneState {
        val json = store.getString(STATE) ?: return PhoneState()
        return try {
            AppJson.decodeFromString(PhoneState.serializer(), json)
        } catch (e: IllegalArgumentException) {
            PhoneState()
        }
    }

    private companion object {
        const val KEYS = "vault.keys"
        const val STATE = "vault.state"
    }
}

/** The key's files in the Drive hidden folder and in the owner's GitHub keyring. */
internal class RemoteKeys(private val drive: DriveStore, private val gitHub: GitHubApi) {

    suspend fun has(name: String): Boolean = drive.find(name) != null

    /** A vault exists in this Google account when either key file is there. */
    suspend fun driveHasVault(): Boolean = has(VaultKeyFiles.HALF_D) || has(VaultKeyFiles.KEY_CHECK)

    suspend fun readDrive(name: String, damaged: String = VaultText.DRIVE_FILE_DAMAGED): ByteArray? {
        val file = drive.find(name) ?: return null
        if (file.size > MAX_KEY_FILE_BYTES) throw DamagedKeyFile(damaged)
        val sink = ByteArrayOutputStream()
        drive.download(file.id, sink)
        return sink.toByteArray()
    }

    suspend fun writeDrive(name: String, bytes: ByteArray) {
        drive.uploadBytes(name, bytes, drive.find(name)?.id)
    }

    suspend fun readHalfD(): HalfDFile? {
        val bytes = readDrive(VaultKeyFiles.HALF_D, VaultText.DRIVE_HALF_DAMAGED) ?: return null
        val file = decode(HalfDFile.serializer(), bytes, VaultText.DRIVE_HALF_DAMAGED)
        if (file.entries().any { !isHalf(it.half) }) throw DamagedKeyFile(VaultText.DRIVE_HALF_DAMAGED)
        return file
    }

    suspend fun writeHalfD(file: HalfDFile) = writeDrive(VaultKeyFiles.HALF_D, encode(HalfDFile.serializer(), file))

    suspend fun keyring(login: String): RepoInfo? = gitHub.repo(login, VaultKeyFiles.KEYRING_REPO)

    suspend fun createKeyring(): RepoInfo = gitHub.createPrivateRepo(VaultKeyFiles.KEYRING_REPO, KEYRING_DESCRIPTION)

    /** Everyone with access except the owner (GitHub lists the owner too). */
    suspend fun otherCollaborators(login: String): List<String> =
        gitHub.collaborators(login, VaultKeyFiles.KEYRING_REPO).filterNot { it.equals(login, ignoreCase = true) }

    suspend fun disableActions(login: String) = gitHub.setActionsEnabled(login, VaultKeyFiles.KEYRING_REPO, enabled = false)

    suspend fun readHalfG(login: String): HalfGFile? {
        val found = gitHub.readFile(login, VaultKeyFiles.KEYRING_REPO, VaultKeyFiles.HALF_G_PATH) ?: return null
        val file = decode(HalfGFile.serializer(), found.bytes, VaultText.GITHUB_HALF_DAMAGED)
        if ((file.half == null) == (file.wrapped == null) || file.half?.let(::isHalf) == false) {
            throw DamagedKeyFile(VaultText.GITHUB_HALF_DAMAGED)
        }
        return file
    }

    suspend fun writeHalfG(login: String, file: HalfGFile) {
        val existing = gitHub.readFile(login, VaultKeyFiles.KEYRING_REPO, VaultKeyFiles.HALF_G_PATH)
        gitHub.writeFile(
            login,
            VaultKeyFiles.KEYRING_REPO,
            VaultKeyFiles.HALF_G_PATH,
            encode(HalfGFile.serializer(), file),
            COMMIT_MESSAGE,
            existing?.sha,
        )
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        AppJson.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)

    /**
     * Parses a key file. A newer format is never treated as damaged: an older app must not
     * replace what a newer one wrote.
     */
    private fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray, damaged: String): T {
        val text = bytes.toString(Charsets.UTF_8)
        val version = try {
            AppJson.parseToJsonElement(text).jsonObject["v"]?.jsonPrimitive?.int
        } catch (e: IllegalArgumentException) {
            throw DamagedKeyFile(damaged)
        }
        if (version != null && version > KEY_FILE_FORMAT) throw VaultException(VaultText.NEWER_APP)
        return try {
            AppJson.decodeFromString(serializer, text)
        } catch (e: IllegalArgumentException) {
            throw DamagedKeyFile(damaged)
        }
    }

    private companion object {
        /** Key files are a few hundred bytes; the history grows by about a hundred per key change. */
        const val MAX_KEY_FILE_BYTES = 256L * 1024
        const val KEYRING_DESCRIPTION =
            "PocketIDE keeps half of your chats' key here. Keep this repository private and never add anyone to it."
        const val COMMIT_MESSAGE = "Save PocketIDE key half"
    }
}

internal fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

internal fun decodeBase64(text: String): ByteArray? = try {
    Base64.getDecoder().decode(text)
} catch (e: IllegalArgumentException) {
    null
}

/** A key half from JSON: exactly 32 bytes, or null. */
internal fun decodeHalf(text: String): ByteArray? = decodeBase64(text)?.takeIf { it.size == KeySplit.KEY_SIZE }

private fun isHalf(text: String) = decodeHalf(text)?.also { it.fill(0) } != null

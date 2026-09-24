package com.pocketide.vault

import com.pocketide.core.AppJson
import com.pocketide.core.Clock
import com.pocketide.github.GitHubAccount
import com.pocketide.github.NotConnectedException
import com.pocketide.github.RepoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

/**
 * The vault key in its three homes: the full key sealed on this phone, Half D in the Drive
 * hidden folder, Half G in the owner's private `pocketide-keyring` repo.
 *
 * Every change (a new vault, a new key, fresh halves of the same key) is recorded on the phone
 * first and then saved step by step, in an order where D + G in the stores always rebuild a key
 * that opens the key check and, through the history, every older key. Losing the phone at any
 * moment therefore never loses data, and a restart resumes the change where it stopped.
 */
internal class VaultKeysImpl(
    private val phone: PhoneKeys,
    private val remote: RemoteKeys,
    private val account: StateFlow<GitHubAccount?>,
    private val clock: Clock,
    private val onPasswordChanged: (Boolean) -> Unit = {},
    private val random: SecureRandom = SecureRandom(),
    private val passwordCost: Argon2Cost = Argon2Cost.DEFAULT,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) : VaultKeys {
    private val mutex = Mutex()

    /** Newest first; replaced as a whole, so readers outside the lock always see a consistent list. */
    @Volatile private var keys: List<VaultKey>
    private var phoneState: PhoneState
    private val mutableState: MutableStateFlow<KeyState>
    private val mutableNotice = MutableStateFlow<String?>(null)

    init {
        val loaded = phone.load()
        keys = loaded.keys
        phoneState = loaded.state
        mutableState = MutableStateFlow(stateFromPhone())
    }

    override val state: StateFlow<KeyState> = mutableState.asStateFlow()
    override val notice: StateFlow<String?> = mutableNotice.asStateFlow()

    private val liveCipher = object : VaultCipher {
        override fun encrypt(plain: InputStream, out: OutputStream) = Age.encrypt(listOf(activeKey().identity.recipient), plain, out, random)

        override fun decrypt(encrypted: InputStream, out: OutputStream) = Age.decrypt(allIdentities(), encrypted, out)

        override fun encryptBytes(plain: ByteArray) = Age.encryptBytes(listOf(activeKey().identity.recipient), plain, random)

        override fun decryptBytes(encrypted: ByteArray) = Age.decryptBytes(allIdentities(), encrypted)
    }

    /** First phone: makes the key and saves both halves. A Google account that already has a vault is restored instead, never replaced. */
    override suspend fun setUp() {
        mutex.withLock {
            val pending = phoneState.change
            when {
                pending != null -> apply(pending)
                keys.isNotEmpty() -> Unit
                remote.driveHasVault() -> restoreLocked(null)
                else -> start(changeTo(ChangeKind.NEW, generation = 1, AgeIdentity.generate(random)))
            }
        }
    }

    override suspend fun restore(extraPassword: CharArray?): KeyState = mutex.withLock {
        val pending = phoneState.change
        // A vault this phone began making is finished, not looked for elsewhere.
        if (pending?.kind == ChangeKind.NEW) {
            apply(pending)
            settle()
        } else {
            restoreLocked(extraPassword)
        }
    }

    override suspend fun rekey(reason: RekeyReason) {
        mutex.withLock { rekeyLocked(reason) }
    }

    override suspend fun checkKeyring(): KeyringCheck = mutex.withLock {
        val login = login()
        if (login == null) {
            gitHubUnavailable()
            throw NotConnectedException(VaultText.CONNECT_GITHUB)
        }
        try {
            checkLocked(login)
        } catch (e: NotConnectedException) {
            gitHubUnavailable()
            throw e
        }
    }

    /** The caller clears [password] after this returns. */
    override suspend fun setExtraPassword(password: CharArray?) {
        if (password != null && password.isEmpty()) throw VaultException(VaultText.EMPTY_PASSWORD)
        mutex.withLock {
            if (keys.isEmpty()) throw VaultException(VaultText.NO_KEY)
            val login = login() ?: throw VaultException(VaultText.CONNECT_GITHUB_FIRST)
            phoneState.change?.let { apply(it) }
            val repo = remote.keyring(login)
            if (repo != null && !isSafe(repo, remote.otherCollaborators(login))) throw VaultException(VaultText.MAKE_PRIVATE_FIRST)
            val record = password?.let { withContext(cpu) { HalfPassword.derive(it, passwordCost, random) }.toRecord() }
            savePhone(phoneState.copy(password = record, passwordOn = record != null))
            onPasswordChanged(record != null)
            // Fresh halves: the unwrapped Half G stays in the keyring's git history, and it must not pair with the new Half D.
            start(resplit(keys.first()))
        }
    }

    override suspend fun exportKeyCopy(): String {
        val all = keys
        if (all.isEmpty()) throw VaultException(VaultText.NO_KEY)
        return KeyCopy.format(all, clock.now())
    }

    override suspend fun importKeyCopy(text: String) {
        val copied = KeyCopy.parse(text)
        mutex.withLock {
            val check = remote.readDrive(VaultKeyFiles.KEY_CHECK) ?: throw VaultException(VaultText.NO_VAULT_TO_OPEN)
            val current = copied.firstOrNull { opensKeyCheck(check, it.identity) }
                ?: throw VaultException(VaultText.COPY_DOES_NOT_OPEN)
            val generation = current.generation ?: readHalfDOrNull()?.generation ?: 1
            val others = copied.filter { it !== current }.map { VaultKey(it.generation ?: 0, it.identity) }
            saveKeys(mergeKeys(listOf(VaultKey(generation, current.identity)) + readHistory(current.identity) + others + keys))
            settle()
            checkAfterImport()
        }
    }

    override suspend fun forget() {
        mutex.withLock {
            phone.clear()
            keys = emptyList()
            phoneState = PhoneState()
            mutableNotice.value = null
            mutableState.value = KeyState.None
        }
    }

    override fun cipher(): VaultCipher = liveCipher

    override fun generation(): Int = keys.firstOrNull()?.generation ?: 0

    private suspend fun start(change: KeyChange) {
        savePhone(phoneState.copy(change = change))
        apply(change)
    }

    /**
     * Saves [change]. Each step can run again safely, so an interrupted change resumes from the top.
     * A new key replaces the phone's key even when its Half G has to wait (keyring public, shared,
     * or GitHub gone): the old key may be exposed, so new data must not depend on it.
     */
    private suspend fun apply(change: KeyChange) {
        val identity = AgeIdentity.parse(change.ageSecretKey)
        val newKey = change.kind != ChangeKind.RESPLIT
        val previousKey = keys.firstOrNull { it.generation < change.generation }
        // Another phone's newer key wins, and its key check and history are left as they are.
        val current = readHalfDOrNull()
        if (current != null && supersedes(current, change)) {
            savePhone(phoneState.copy(change = null))
            throw VaultException(VaultText.ANOTHER_PHONE)
        }
        // 1. The key check and history open with the new key and the old one before any half points at the new key.
        if (newKey) writeKeyFiles(change.generation, listOfNotNull(identity.recipient, previousKey?.identity?.recipient))
        // 2. Half D of the new split, keeping the halves the Half G in GitHub may still pair with.
        val floor = if (newKey) previousKey?.generation else change.generation
        val kept = floor?.let { f -> current?.entries()?.filter { it.generation >= f && it.half != change.halfD }?.distinct() }
        remote.writeHalfD(HalfDFile(generation = change.generation, half = change.halfD, previous = kept?.ifEmpty { null }))
        // 3. Half G, only into a private keyring nobody else can open.
        val secret = identity.bytes()
        val halfD = requireNotNull(decodeHalf(change.halfD)) { "A saved change always holds a whole half" }
        val saved = publishHalfG(change.generation, KeySplit.halfG(secret, halfD))
        KeySplit.wipe(secret, halfD)
        // 4. The phone switches to the new key.
        if (newKey) saveKeys(mergeKeys(listOf(VaultKey(change.generation, identity)) + keys))
        // 5. Old halves, and the old key's access to the check and history, go once nothing needs them.
        val finished = saved || newKey
        if (finished) {
            remote.writeHalfD(HalfDFile(generation = change.generation, half = change.halfD))
            if (newKey) writeKeyFiles(change.generation, listOf(identity.recipient))
        }
        savePhone(
            phoneState.copy(
                change = if (finished) null else change,
                savedGeneration = if (saved) change.generation else phoneState.savedGeneration,
            ),
        )
        settle()
    }

    /** Drive already holds a newer key than [change], or a different key of the same generation. */
    private fun supersedes(current: HalfDFile, change: KeyChange): Boolean =
        current.generation > change.generation ||
            (change.kind != ChangeKind.RESPLIT && current.generation == change.generation && current.entries().none { it.half == change.halfD })

    private suspend fun writeKeyFiles(generation: Int, recipients: List<AgeRecipient>) {
        remote.writeDrive(VaultKeyFiles.KEY_CHECK, Age.encryptBytes(recipients, KEY_CHECK_TEXT, random))
        val older = keys.filter { it.generation < generation }.map { StoredKey(it.generation, it.identity.encoded()) }
        val history = AppJson.encodeToString(STORED_KEYS, older).toByteArray(Charsets.UTF_8)
        remote.writeDrive(VaultKeyFiles.KEY_HISTORY, Age.encryptBytes(recipients, history, random))
    }

    /**
     * Writes Half G to the keyring, creating the repo when it is missing. Returns false when it
     * must wait: GitHub is not connected, the repo is public or shared (a half there would stop
     * protecting anything), or the extra password is on but this phone does not know it.
     */
    private suspend fun publishHalfG(generation: Int, half: ByteArray): Boolean {
        try {
            val login = login() ?: return waitWith(VaultText.CONNECT_GITHUB)
            val repo = remote.keyring(login) ?: createKeyring(login)
            val others = remote.otherCollaborators(login)
            if (!isSafe(repo, others)) return waitWith(unsafeNotice(repo, others))
            val file = sealHalfG(generation, half) ?: return waitWith(VaultText.PASSWORD_AGAIN)
            remote.writeHalfG(login, file)
            return true
        } catch (e: NotConnectedException) {
            return waitWith(VaultText.CONNECT_GITHUB)
        } finally {
            half.fill(0)
        }
    }

    private fun waitWith(notice: String): Boolean {
        mutableNotice.value = notice
        return false
    }

    private fun sealHalfG(generation: Int, half: ByteArray): HalfGFile? {
        val password = phoneState.password?.toKey()
        return when {
            password != null -> HalfGFile(generation = generation, wrapped = base64(HalfPassword.wrap(half, password, random)))
            // Set on another phone: saving a plain half would drop the owner's password without asking.
            phoneState.passwordOn -> null
            else -> HalfGFile(generation = generation, half = base64(half))
        }
    }

    private suspend fun createKeyring(login: String): RepoInfo {
        val repo = remote.createKeyring()
        savePhone(phoneState.copy(actionsOff = disableActions(login)))
        return repo
    }

    /** Actions stay off in the keyring; a missing permission is reported, not fatal. */
    private suspend fun disableActions(login: String): Boolean = try {
        remote.disableActions(login)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: NotConnectedException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun restoreLocked(password: CharArray?): KeyState {
        try {
            val halfDFile = remote.readHalfD() ?: return when {
                keys.isNotEmpty() -> settle()
                remote.has(VaultKeyFiles.KEY_CHECK) -> unavailable(VaultText.DRIVE_HALF_MISSING)
                else -> KeyState.None.also { mutableState.value = it }
            }
            val login = login() ?: return unavailable(VaultText.CONNECT_GITHUB_TO_RESTORE)
            val halfGFile = try {
                remote.readHalfG(login)
            } catch (e: NotConnectedException) {
                return unavailable(VaultText.CONNECT_GITHUB_TO_RESTORE)
            } ?: return unavailable(VaultText.GITHUB_HALF_MISSING)
            if (halfGFile.generation <= generation()) return settle()
            val opened = openHalfG(halfGFile, password) ?: return askForPassword()
            try {
                return rebuild(halfDFile, halfGFile, opened)
            } finally {
                opened.half.fill(0)
            }
        } catch (e: DamagedKeyFile) {
            return unavailable(e.message ?: VaultText.HALVES_WRONG)
        }
    }

    /** Joins Half G with each Half D of its generation; the one that opens the key check is the key. */
    private suspend fun rebuild(halfDFile: HalfDFile, halfGFile: HalfGFile, halfG: OpenedHalf): KeyState {
        val candidates = halfDFile.entries().filter { it.generation == halfGFile.generation }
        if (candidates.isEmpty()) return unavailable(VaultText.HALVES_APART)
        val check = remote.readDrive(VaultKeyFiles.KEY_CHECK) ?: return unavailable(VaultText.CHECK_MISSING)
        val identity = candidates.firstNotNullOfOrNull { joinChecked(it, halfG.half, check) }
            ?: return unavailable(VaultText.HALVES_WRONG)
        saveKeys(mergeKeys(listOf(VaultKey(halfGFile.generation, identity)) + readHistory(identity) + keys))
        val passwordOn = halfGFile.wrapped != null
        savePhone(
            phoneState.copy(
                savedGeneration = halfGFile.generation,
                // A change this phone left for an older key can never be saved now.
                change = phoneState.change?.takeIf { it.generation >= halfGFile.generation },
                password = if (passwordOn) halfG.passwordKey?.toRecord() ?: phoneState.password else null,
                passwordOn = passwordOn,
            ),
        )
        onPasswordChanged(passwordOn)
        return settle()
    }

    private class OpenedHalf(val half: ByteArray, val passwordKey: PasswordKey?)

    /** Half G in the clear, or null when it is wrapped and the password is needed. */
    private suspend fun openHalfG(file: HalfGFile, password: CharArray?): OpenedHalf? {
        file.half?.let { return OpenedHalf(decodeHalf(it) ?: throw DamagedKeyFile(VaultText.GITHUB_HALF_DAMAGED), null) }
        val wrapped = file.wrapped?.let(::decodeBase64) ?: throw DamagedKeyFile(VaultText.GITHUB_HALF_DAMAGED)
        phoneState.password?.toKey()?.let { kept -> HalfPassword.unwrap(wrapped, kept)?.let { return OpenedHalf(it, kept) } }
        if (password == null) return null
        val unwrapped = withContext(cpu) { HalfPassword.unwrap(wrapped, password) }
        return OpenedHalf(unwrapped.half, unwrapped.key)
    }

    private suspend fun askForPassword(): KeyState {
        if (!phoneState.passwordOn) {
            savePhone(phoneState.copy(passwordOn = true))
            onPasswordChanged(true)
        }
        return KeyState.NeedsPassword.also { mutableState.value = it }
    }

    private fun joinChecked(entry: HalfEntry, halfG: ByteArray, check: ByteArray): AgeIdentity? {
        val halfD = decodeHalf(entry.half) ?: return null
        val secret = KeySplit.join(halfD, halfG)
        try {
            return AgeIdentity.fromBytes(secret).takeIf { opensKeyCheck(check, it) }
        } finally {
            KeySplit.wipe(halfD, secret)
        }
    }

    private fun opensKeyCheck(check: ByteArray, identity: AgeIdentity): Boolean = try {
        Age.decryptBytes(listOf(identity), check).contentEquals(KEY_CHECK_TEXT)
    } catch (e: AgeException) {
        false
    }

    /** Older keys from Drive. If the history cannot be read the current key still works, and the owner is told. */
    private suspend fun readHistory(identity: AgeIdentity): List<VaultKey> {
        val sealed = remote.readDrive(VaultKeyFiles.KEY_HISTORY) ?: return emptyList()
        return decodeHistory(sealed, identity) ?: emptyList<VaultKey>().also { mutableNotice.value = VaultText.HISTORY_UNREADABLE }
    }

    private fun decodeHistory(sealed: ByteArray, identity: AgeIdentity): List<VaultKey>? = try {
        val json = Age.decryptBytes(listOf(identity), sealed).toString(Charsets.UTF_8)
        AppJson.decodeFromString(STORED_KEYS, json).map { VaultKey(it.generation, AgeIdentity.parse(it.ageSecretKey)) }
    } catch (e: AgeException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private suspend fun rekeyLocked(reason: RekeyReason) {
        if (keys.isEmpty()) throw VaultException(VaultText.NO_KEY)
        phoneState.change?.let { pending ->
            apply(pending)
            if (pending.kind == ChangeKind.REKEY) return
        }
        val drive = readHalfDOrNull()
        if (drive != null && drive.generation > generation()) throw VaultException(VaultText.ANOTHER_PHONE)
        start(changeTo(ChangeKind.REKEY, generation() + 1, AgeIdentity.generate(random), reason))
    }

    private suspend fun checkLocked(login: String): KeyringCheck {
        mutableNotice.value = null
        val found = remote.keyring(login)
        if (found == null && keys.isEmpty() && phoneState.change == null) {
            return KeyringCheck(exists = false, isPrivate = false, collaborators = emptyList(), actionsDisabled = false)
        }
        val repo = found ?: createKeyring(login)
        val others = remote.otherCollaborators(login)
        val actionsOff = if (found == null) phoneState.actionsOff else keepActionsOff(login)
        val result = KeyringCheck(exists = true, isPrivate = repo.isPrivate, collaborators = others, actionsDisabled = actionsOff)
        val pending = phoneState.change
        if (pending != null) apply(pending)
        val active = keys.firstOrNull() ?: return result
        when {
            !isSafe(repo, others) -> protect(login, repo, others)
            found == null && pending == null -> remakeHalves(active)
            else -> reconcile(login)
        }
        return result
    }

    private suspend fun keepActionsOff(login: String): Boolean {
        if (phoneState.actionsOff && clock.now() - phoneState.checkedAt < DAY_MS) return true
        val off = disableActions(login)
        if (off != phoneState.actionsOff) savePhone(phoneState.copy(actionsOff = off))
        return off
    }

    /** The keyring is public or shared: a Half G of the key in use there must stop being useful. */
    private suspend fun protect(login: String, repo: RepoInfo, others: List<String>) {
        val exposed = readHalfGOrNull(login)?.generation ?: 0
        // This phone is behind another phone: take the newer key first, then change it.
        if (exposed > generation()) restoreLocked(null)
        if (exposed > generation()) return
        if (exposed == generation()) {
            rekeyLocked(if (!repo.isPrivate) RekeyReason.KEYRING_PUBLIC else RekeyReason.KEYRING_COLLABORATOR)
        } else if (phoneState.savedGeneration >= generation()) {
            // The key's Half G is not in the keyring, so only this phone can rebuild the key.
            savePhone(phoneState.copy(savedGeneration = exposed))
        }
        mutableNotice.value = unsafeNotice(repo, others)
        settle()
    }

    /** The keyring was missing and has been made again: save fresh halves of the same key. */
    private suspend fun remakeHalves(active: VaultKey) {
        start(resplit(active))
        if (mutableState.value == KeyState.Ready) mutableNotice.value = VaultText.KEYRING_REMADE
    }

    /** The keyring is safe: make sure Half D + Half G rebuild this phone's key, and tidy leftovers. */
    private suspend fun reconcile(login: String) {
        val active = keys.first()
        val halfG = readHalfGOrNull(login)
        val halfD = readHalfDOrNull()
        if (maxOf(halfG?.generation ?: 0, halfD?.generation ?: 0) > active.generation) {
            restoreLocked(null)
            return
        }
        // No Half D at all means the Drive folder was emptied: its key check and history go back first.
        verifyDrive(active, force = halfD == null)
        if (halfG == null || halfD == null || halfG.generation < active.generation || !pairHolds(active, halfD, halfG)) {
            start(resplit(active))
        } else {
            if (phoneState.savedGeneration != active.generation) savePhone(phoneState.copy(savedGeneration = active.generation))
            settle()
        }
    }

    /** True when Drive's Half D and this Half G rebuild [active]; drops halves Drive no longer needs. */
    private suspend fun pairHolds(active: VaultKey, halfD: HalfDFile, halfG: HalfGFile): Boolean {
        val g = when (val wrapped = halfG.wrapped) {
            null -> halfG.half?.let(::decodeHalf) ?: return false
            else -> {
                val blob = decodeBase64(wrapped) ?: return false
                val kept = phoneState.password?.toKey()
                try {
                    if (kept == null || !HalfPassword.madeWith(blob, kept)) return passwordSetElsewhere(active)
                    HalfPassword.unwrap(blob, kept) ?: return false
                } catch (e: DamagedKeyFile) {
                    return false
                }
            }
        }
        val secret = active.identity.bytes()
        try {
            val match = halfD.entries().firstOrNull { it.generation == active.generation && rebuilds(it, g, secret) } ?: return false
            if (halfD.previous != null || halfD.half != match.half) {
                remote.writeHalfD(HalfDFile(generation = active.generation, half = match.half))
            }
            return true
        } finally {
            KeySplit.wipe(secret, g)
        }
    }

    /**
     * Half G is wrapped with a password this phone does not know (set or changed on another
     * phone). This phone forgets any older password key, so it never replaces the half with a
     * plain one or one wrapped with an old password. The half is trusted only when this phone
     * already knew the key's halves were saved; otherwise fresh halves wait for the password.
     */
    private suspend fun passwordSetElsewhere(active: VaultKey): Boolean {
        if (!phoneState.passwordOn || phoneState.password != null) {
            savePhone(phoneState.copy(passwordOn = true, password = null))
            onPasswordChanged(true)
        }
        return phoneState.savedGeneration >= active.generation
    }

    private fun rebuilds(entry: HalfEntry, halfG: ByteArray, secret: ByteArray): Boolean {
        val halfD = decodeHalf(entry.half) ?: return false
        val joined = KeySplit.join(halfD, halfG)
        return org.bouncycastle.util.Arrays.constantTimeAreEqual(joined, secret).also { KeySplit.wipe(halfD, joined) }
    }

    /** Once a day, or when [force]d: the key check and history in Drive must open with the key in use; rewritten when not. */
    private suspend fun verifyDrive(active: VaultKey, force: Boolean) {
        if (!force && clock.now() - phoneState.checkedAt < DAY_MS) return
        val check = remote.readDrive(VaultKeyFiles.KEY_CHECK)
        val history = remote.readDrive(VaultKeyFiles.KEY_HISTORY)
        val fine = check != null && opensKeyCheck(check, active.identity) &&
            history != null && decodeHistory(history, active.identity) != null
        if (!fine) writeKeyFiles(active.generation, listOf(active.identity.recipient))
        savePhone(phoneState.copy(checkedAt = clock.now()))
    }

    /** After an import the halves are saved again when GitHub is there; otherwise the next keyring check does it. */
    private suspend fun checkAfterImport() {
        val login = login() ?: return gitHubUnavailable()
        try {
            checkLocked(login)
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotConnectedException) {
            gitHubUnavailable()
        } catch (e: Exception) {
            // The key is imported and in use; saving its halves is retried by the next keyring check.
            settle()
        }
    }

    private fun gitHubUnavailable() {
        if (keys.isEmpty()) return
        mutableNotice.value = VaultText.CONNECT_GITHUB
        mutableState.value = KeyState.OnlyOnPhone
    }

    private fun login(): String? = account.value?.login

    private fun stateFromPhone(): KeyState = when {
        keys.isEmpty() -> KeyState.None
        phoneState.savedGeneration >= keys.first().generation -> KeyState.Ready
        else -> KeyState.OnlyOnPhone
    }

    private fun settle(): KeyState = stateFromPhone().also { mutableState.value = it }

    /** A half is unavailable: a phone that has the key keeps working; a phone without it has lost it. */
    private fun unavailable(why: String): KeyState =
        if (keys.isNotEmpty()) settle() else KeyState.Lost(why).also { mutableState.value = it }

    private suspend fun readHalfDOrNull(): HalfDFile? = try {
        remote.readHalfD()
    } catch (e: DamagedKeyFile) {
        null
    }

    private suspend fun readHalfGOrNull(login: String): HalfGFile? = try {
        remote.readHalfG(login)
    } catch (e: DamagedKeyFile) {
        null
    }

    private suspend fun saveKeys(list: List<VaultKey>) {
        phone.saveKeys(list)
        keys = list
    }

    private suspend fun savePhone(state: PhoneState) {
        phone.saveState(state)
        phoneState = state
    }

    private fun activeKey(): VaultKey = keys.firstOrNull() ?: throw VaultException(VaultText.NO_KEY)

    private fun allIdentities(): List<AgeIdentity> = keys.map { it.identity }.ifEmpty { throw VaultException(VaultText.NO_KEY) }

    private fun resplit(active: VaultKey) = changeTo(ChangeKind.RESPLIT, active.generation, active.identity)

    /** A change to [identity] with a fresh split. Only Half D is recorded; Half G follows from the key. */
    private fun changeTo(kind: ChangeKind, generation: Int, identity: AgeIdentity, reason: RekeyReason? = null): KeyChange {
        val secret = identity.bytes()
        val halves = KeySplit.split(secret, random)
        try {
            return KeyChange(kind, generation, identity.encoded(), base64(halves.halfD), reason?.name)
        } finally {
            halves.wipe()
            secret.fill(0)
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        val KEY_CHECK_TEXT = "PocketIDE key check 1".toByteArray(Charsets.US_ASCII)

        fun isSafe(repo: RepoInfo, others: List<String>) = repo.isPrivate && others.isEmpty()

        fun unsafeNotice(repo: RepoInfo, others: List<String>) =
            if (!repo.isPrivate) VaultText.KEYRING_PUBLIC else VaultText.keyringShared(others)

        /** One list per key, newest first; the first of equal generations wins (the one just rebuilt or made). */
        fun mergeKeys(candidates: List<VaultKey>): List<VaultKey> =
            candidates.distinctBy { it.identity }.sortedByDescending { it.generation }

        fun PasswordRecord.toKey(): PasswordKey? {
            val saltBytes = decodeBase64(salt) ?: return null
            val keyBytes = decodeBase64(key) ?: return null
            return PasswordKey(Argon2Cost(memoryKiB, iterations, parallelism), saltBytes, keyBytes)
        }

        fun PasswordKey.toRecord() =
            PasswordRecord(cost.memoryKiB, cost.iterations, cost.parallelism, base64(salt), base64(key))
    }
}

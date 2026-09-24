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
import java.io.IOException
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
 *
 * Drive's key check says whose vault a Google account holds. A phone writes key files only into
 * a vault its own keys open, so a phone with an older key never overwrites a newer vault; new
 * vaults and new keys are numbered above every half still around, so older phones take them.
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

    override suspend fun setUp() {
        mutex.withLock {
            reloadIfEmpty()
            resumePending()
            if (keys.isNotEmpty()) return@withLock
            when (val found = restoreLocked(null)) {
                KeyState.None -> newVault()
                KeyState.NeedsPassword -> throw VaultException(VaultText.PASSWORD_NEEDED)
                is KeyState.Lost -> throw VaultException(found.why)
                KeyState.Ready, KeyState.OnlyOnPhone -> Unit
            }
        }
    }

    override suspend fun startOver() {
        mutex.withLock {
            reloadIfEmpty()
            if (keys.isNotEmpty()) return@withLock
            if (login() == null) throw VaultException(VaultText.CONNECT_GITHUB_FIRST)
            // Replaces any new vault this phone had begun: the owner asked for a fresh start.
            newVault()
        }
    }

    override suspend fun restore(extraPassword: CharArray?): KeyState = mutex.withLock {
        reloadIfEmpty()
        // A vault this phone began making is finished, not looked for elsewhere.
        if (phoneState.change?.kind == ChangeKind.NEW) {
            resumePending()
            if (keys.isNotEmpty()) return@withLock settle()
        }
        restoreLocked(extraPassword)
    }

    override suspend fun rekey(reason: RekeyReason) {
        mutex.withLock { rekeyLocked(reason) }
    }

    override suspend fun checkKeyring(): KeyringCheck = mutex.withLock {
        reloadIfEmpty()
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
            resumePending()
            val repo = remote.keyring(login)
            if (repo != null && !isSafe(repo, remote.otherCollaborators(login))) throw VaultException(VaultText.MAKE_PRIVATE_FIRST)
            // Fresh halves of an older key would be refused: this phone first takes the newer key at its next sync.
            if ((readHalfDOrNull()?.generation ?: 0) > generation()) throw VaultException(VaultText.ANOTHER_PHONE)
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
            // The copy's own number, unless Half D in Drive shows the vault got further since.
            val generation = maxOf(current.generation ?: 1, readHalfDOrNull()?.generation ?: 1)
            val others = copied.filter { it !== current }.map { VaultKey(it.generation ?: 0, it.identity) }
            saveKeys(withCurrent(VaultKey(generation, current.identity), readHistory(current.identity) + others + keys))
            // A new vault this phone had begun instead must never replace the one just opened.
            if (phoneState.change != null) savePhone(phoneState.copy(change = null))
            settle()
            checkAfterImport()
        }
    }

    override suspend fun forget() {
        mutex.withLock {
            keys = emptyList()
            phoneState = PhoneState()
            mutableNotice.value = null
            mutableState.value = KeyState.None
            onPasswordChanged(false)
            phone.clear()
        }
    }

    override fun cipher(): VaultCipher = liveCipher

    override fun generation(): Int = keys.firstOrNull()?.generation ?: 0

    /** A Keystore that failed for a moment at start leaves the phone looking empty: look again before acting on that. */
    private suspend fun reloadIfEmpty() {
        if (keys.isNotEmpty() || phoneState.change != null) return
        val loaded = phone.reload()
        if (loaded.keys.isEmpty() && loaded.state.change == null) return
        keys = loaded.keys
        phoneState = loaded.state
        settle()
    }

    /** Finishes a change a restart or a failure left pending; one another phone's key overtook is dropped. */
    private suspend fun resumePending() {
        phoneState.change?.let { apply(it) }
    }

    private suspend fun start(change: KeyChange) {
        savePhone(phoneState.copy(change = change))
        if (!apply(change)) throw VaultException(VaultText.ANOTHER_PHONE)
    }

    /**
     * Saves [change]. Each step can run again safely, so an interrupted change resumes from the top.
     * A new key replaces the phone's key even when its Half G has to wait (keyring public, shared,
     * or GitHub gone): the old key may be exposed, so new data must not depend on it. Returns false,
     * with the change dropped, when another phone's key has won in the meantime.
     */
    private suspend fun apply(change: KeyChange): Boolean {
        val identity = AgeIdentity.parse(change.ageSecretKey)
        val newKey = change.kind != ChangeKind.RESPLIT
        val previousKey = keys.firstOrNull { it.generation < change.generation }
        val current = readHalfDOrNull()
        val check = readDriveOrNull(VaultKeyFiles.KEY_CHECK)
        // Another phone's newer key wins, and its key check and history are left as they are.
        val overtaken = current != null && supersedes(current, change)
        if (overtaken || (change.kind != ChangeKind.NEW && check != null && opensWithNone(check, identity))) {
            savePhone(phoneState.copy(change = null))
            return false
        }
        // 1. The key check and history open with the new key and the old one before any half points at the new key.
        if (newKey) {
            writeKeyFiles(change.generation, listOfNotNull(identity.recipient, previousKey?.identity?.recipient))
        } else {
            ensureKeyFiles(VaultKey(change.generation, identity), check)
        }
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
        if (newKey) saveKeys(withCurrent(VaultKey(change.generation, identity), keys))
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
        return true
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

    /** Writes the key check and history again unless both open with [key] (a Drive folder emptied, or a damaged file). */
    private suspend fun ensureKeyFiles(key: VaultKey, check: ByteArray?) {
        val history = readDriveOrNull(VaultKeyFiles.KEY_HISTORY)
        val fine = check != null && opensKeyCheck(check, key.identity) && history != null && decodeHistory(history, key.identity) != null
        if (!fine) writeKeyFiles(key.generation, listOf(key.identity.recipient))
        savePhone(phoneState.copy(checkedAt = clock.now()))
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

    /**
     * Makes the private keyring. GitHub's own sentence for a failure here (a name already taken,
     * a missing permission) would mislead, since the owner chose neither: this one names the fix.
     */
    private suspend fun createKeyring(login: String): RepoInfo {
        val repo = try {
            remote.createKeyring()
        } catch (e: CancellationException) {
            throw e
        } catch (e: NotConnectedException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw VaultException(VaultText.KEYRING_NOT_MADE, e)
        }
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
            // Checked before any password is asked for: a half with no partner in Drive opens nothing.
            val candidates = halfDFile.entries().filter { it.generation == halfGFile.generation }
            if (candidates.isEmpty()) return unavailable(VaultText.HALVES_APART)
            val opened = openHalfG(halfGFile, password) ?: return askForPassword()
            try {
                return rebuild(candidates, halfGFile, opened)
            } finally {
                opened.half.fill(0)
            }
        } catch (e: DamagedKeyFile) {
            return unavailable(e.message ?: VaultText.HALVES_WRONG)
        }
    }

    /** Joins Half G with each Half D of its generation; the one that opens the key check is the key. */
    private suspend fun rebuild(candidates: List<HalfEntry>, halfGFile: HalfGFile, halfG: OpenedHalf): KeyState {
        val check = remote.readDrive(VaultKeyFiles.KEY_CHECK) ?: return unavailable(VaultText.CHECK_MISSING)
        val identity = candidates.firstNotNullOfOrNull { joinChecked(it, halfG.half, check) }
            ?: return unavailable(VaultText.HALVES_WRONG)
        saveKeys(withCurrent(VaultKey(halfGFile.generation, identity), readHistory(identity) + keys))
        val passwordOn = halfGFile.wrapped != null
        savePhone(
            phoneState.copy(
                savedGeneration = halfGFile.generation,
                // Only this phone's own change to exactly this key can still finish; any other is stale now.
                change = phoneState.change?.takeIf { it.generation == halfGFile.generation && it.ageSecretKey == identity.encoded() },
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

    /** True for a well-formed key file that neither this phone's keys nor [extra] open: another key's vault. */
    private fun opensWithNone(file: ByteArray, extra: AgeIdentity? = null): Boolean = try {
        Age.decryptBytes(keys.map { it.identity } + listOfNotNull(extra), file)
        false
    } catch (e: AgeNoMatchException) {
        true
    } catch (e: AgeException) {
        false
    }

    /** Older keys from Drive. If the history cannot be read the current key still works, and the owner is told. */
    private suspend fun readHistory(identity: AgeIdentity): List<VaultKey> {
        val sealed = try {
            remote.readDrive(VaultKeyFiles.KEY_HISTORY)
        } catch (e: DamagedKeyFile) {
            null.also { mutableNotice.value = VaultText.HISTORY_UNREADABLE }
        } ?: return emptyList()
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
        val pending = phoneState.change
        resumePending()
        // A new key that was already on its way is the one asked for.
        if (pending?.kind == ChangeKind.REKEY && generation() == pending.generation) return
        val drive = readHalfDOrNull()?.generation ?: 0
        // Ahead of this phone with a key check its keys do not open: another phone's newer key, taken at the next sync.
        if (drive > generation() && readDriveOrNull(VaultKeyFiles.KEY_CHECK)?.let { opensWithNone(it) } == true) {
            throw VaultException(VaultText.ANOTHER_PHONE)
        }
        // Numbered above a newer key another phone left half saved, so this one is never taken for an old change.
        start(changeTo(ChangeKind.REKEY, maxOf(generation(), drive) + 1, AgeIdentity.generate(random), reason))
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
        resumePending()
        if (keys.isEmpty()) return result
        when {
            !isSafe(repo, others) -> protect(login, repo, others)
            // Still waiting (for the extra password): the change is tried again at the next check.
            phoneState.change != null -> settle()
            else -> reconcile(login, remade = found == null)
        }
        return result
    }

    private suspend fun keepActionsOff(login: String): Boolean {
        if (phoneState.actionsOff && clock.now() - phoneState.checkedAt < DAY_MS) return true
        val off = disableActions(login)
        if (off != phoneState.actionsOff) savePhone(phoneState.copy(actionsOff = off))
        return off
    }

    /** The keyring is public or shared: a Half G that still pairs with Half D in Drive must stop being useful. */
    private suspend fun protect(login: String, repo: RepoInfo, others: List<String>) {
        val exposed = readHalfGOrNull(login)?.generation ?: 0
        // This phone is behind another phone: take the newer key first, then change it.
        if (exposed > generation()) restoreLocked(null)
        mutableNotice.value = unsafeNotice(repo, others)
        // Still behind (the extra password is needed): the phone that holds the newer key changes it.
        if (exposed > generation()) return
        val pairs = exposed == generation() && readHalfDOrNull()?.entries()?.any { it.generation == exposed } == true
        if (pairs) {
            rekeyLocked(if (!repo.isPrivate) RekeyReason.KEYRING_PUBLIC else RekeyReason.KEYRING_COLLABORATOR)
        } else if (phoneState.savedGeneration >= generation()) {
            // The key's two halves are not both saved, so only this phone can rebuild it.
            savePhone(phoneState.copy(savedGeneration = generation() - 1))
        }
        settle()
    }

    /**
     * The keyring is safe: make sure Half D + Half G rebuild this phone's key. A newer key from
     * another phone is taken; missing or stale halves are replaced, but only in a Drive vault that
     * this phone's keys open.
     */
    private suspend fun reconcile(login: String, remade: Boolean) {
        val active = keys.first()
        val wasSaved = phoneState.savedGeneration >= active.generation
        val halfG = readHalfGOrNull(login)
        val halfD = readHalfDOrNull()
        val driveAhead = (halfD?.generation ?: 0) > active.generation
        if (driveAhead || (halfG?.generation ?: 0) > active.generation) {
            restoreLocked(null)
            if (generation() > active.generation || mutableState.value == KeyState.NeedsPassword) return
        }
        // Another phone's newer key still being saved is left alone; Drive is tidied only at this key's generation.
        if (halfG != null && halfD != null && halfG.generation == active.generation && pairHolds(active, halfD, halfG, tidy = !driveAhead)) {
            if (clock.now() - phoneState.checkedAt >= DAY_MS) ensureKeyFiles(active, readDriveOrNull(VaultKeyFiles.KEY_CHECK))
            if (phoneState.savedGeneration != active.generation) savePhone(phoneState.copy(savedGeneration = active.generation))
            settle()
            return
        }
        val check = readDriveOrNull(VaultKeyFiles.KEY_CHECK)
        if (check != null && opensWithNone(check)) {
            // Another key owns this vault: another phone's newer key on its way (this phone takes it once
            // saved), or a vault made after this one was deleted, which this phone's key is no longer part of.
            if (!driveAhead) {
                mutableNotice.value = VaultText.ANOTHER_VAULT
                if (phoneState.savedGeneration >= active.generation) savePhone(phoneState.copy(savedGeneration = active.generation - 1))
            }
            settle()
            return
        }
        if (driveAhead) {
            // A newer key another phone left half saved blocks fresh halves of this one: a new key is saved instead.
            rekeyLocked(RekeyReason.KEYRING_DELETED)
            return
        }
        start(resplit(active))
        if (remade && wasSaved && mutableState.value == KeyState.Ready) mutableNotice.value = VaultText.KEYRING_REMADE
    }

    /** True when Drive's Half D and this Half G rebuild [active]; with [tidy], drops halves Drive no longer needs. */
    private suspend fun pairHolds(active: VaultKey, halfD: HalfDFile, halfG: HalfGFile, tidy: Boolean): Boolean {
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
            if (halfG.wrapped == null) passwordRemovedElsewhere()
            if (tidy && (halfD.previous != null || halfD.half != match.half)) {
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

    /** The keyring holds this key's Half G in the clear, so the extra password was removed: this phone must not wrap with it again. */
    private suspend fun passwordRemovedElsewhere() {
        if (!phoneState.passwordOn && phoneState.password == null) return
        savePhone(phoneState.copy(passwordOn = false, password = null))
        onPasswordChanged(false)
    }

    private fun rebuilds(entry: HalfEntry, halfG: ByteArray, secret: ByteArray): Boolean {
        val halfD = decodeHalf(entry.half) ?: return false
        val joined = KeySplit.join(halfD, halfG)
        return org.bouncycastle.util.Arrays.constantTimeAreEqual(joined, secret).also { KeySplit.wipe(halfD, joined) }
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

    /**
     * A new vault for this Google account. The keyring is made first, so a refusal from GitHub
     * leaves Drive untouched. The key is numbered above every half still around (a vault deleted
     * from Drive leaves its Half G in GitHub), so a phone holding an older key takes the new one
     * instead of overwriting it.
     */
    private suspend fun newVault() {
        val login = login()
        if (login != null) remote.keyring(login) ?: createKeyring(login)
        val drive = readHalfDOrNull()?.generation ?: 0
        val gitHub = login?.let { readHalfGOrNull(it)?.generation } ?: 0
        start(changeTo(ChangeKind.NEW, maxOf(drive, gitHub) + 1, AgeIdentity.generate(random)))
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

    /** A key file from Drive, or null when it is missing or too damaged to use. */
    private suspend fun readDriveOrNull(name: String): ByteArray? = try {
        remote.readDrive(name)
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

        /** [current] first, then every other key once, each numbered below it, so the list order is the key order. */
        fun withCurrent(current: VaultKey, others: List<VaultKey>): List<VaultKey> =
            (listOf(current) + others.map { if (it.generation < current.generation) it else VaultKey(current.generation - 1, it.identity) })
                .distinctBy { it.identity }
                .sortedByDescending { it.generation }

        fun PasswordRecord.toKey(): PasswordKey? {
            val saltBytes = decodeBase64(salt) ?: return null
            val keyBytes = decodeBase64(key) ?: return null
            return PasswordKey(Argon2Cost(memoryKiB, iterations, parallelism), saltBytes, keyBytes)
        }

        fun PasswordKey.toRecord() =
            PasswordRecord(cost.memoryKiB, cost.iterations, cost.parallelism, base64(salt), base64(key))
    }
}

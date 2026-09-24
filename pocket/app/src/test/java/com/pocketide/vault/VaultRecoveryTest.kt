package com.pocketide.vault

import com.pocketide.core.AppJson
import com.pocketide.github.GitHubAccount
import com.pocketide.github.RepoFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.SecureRandom
import java.util.Collections

/**
 * The vault when things go wrong across phones and accounts: a key that cannot be rebuilt, a vault
 * made again, a phone left in a drawer, a change a lost phone left half saved, a password changed
 * elsewhere, damaged or hostile key files, a Keystore hiccup, cancellation and concurrent calls.
 */
class VaultRecoveryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val accounts = Accounts()

    private fun newPhone() = TestPhone(accounts, folder.newFolder())

    private fun halfD() = checkNotNull(accounts.halfD()) { "no keyhalf-d in Drive" }

    private fun halfG() = checkNotNull(accounts.halfG()) { "no half-g.json in the keyring" }

    private fun VaultKeys.seal(text: String) = cipher().encryptBytes(text.toByteArray())

    private fun VaultKeys.open(sealed: ByteArray) = cipher().decryptBytes(sealed).toString(Charsets.UTF_8)

    private suspend fun VaultKeys.currentKey() = VaultFixtures.identities(exportKeyCopy()).first()

    private suspend inline fun <reified T : Throwable> failsWith(block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.java.simpleName}, got $e", e)
        }
        throw AssertionError("expected ${T::class.java.simpleName}, nothing was thrown")
    }

    /** "Delete everything": the Drive folder is emptied and the phone that asked forgets its key. */
    private suspend fun deleteEverything(vault: VaultKeys) {
        accounts.drive.names().forEach { accounts.drive.remove(it) }
        vault.forget()
    }

    private fun driveFiles() = accounts.drive.names().associateWith { checkNotNull(accounts.drive.bytes(it)).toList() }

    private fun putHalfD(file: HalfDFile) =
        accounts.drive.put(VaultKeyFiles.HALF_D, AppJson.encodeToString(HalfDFile.serializer(), file).toByteArray())

    private fun putHalfG(file: HalfGFile) {
        accounts.gitHub.keyring().files[VaultKeyFiles.HALF_G_PATH] =
            RepoFile(VaultKeyFiles.HALF_G_PATH, "old", AppJson.encodeToString(HalfGFile.serializer(), file).toByteArray())
    }

    @Test
    fun `set up never replaces a vault it cannot open, and says why`() = runTest {
        newPhone().vault().setUp()
        val before = driveFiles()
        accounts.gitHub.keyring().files.clear()

        val phone = newPhone().vault()
        assertEquals(VaultText.GITHUB_HALF_MISSING, failsWith<VaultException> { phone.setUp() }.message)
        assertEquals(KeyState.Lost(VaultText.GITHUB_HALF_MISSING), phone.state.value)
        assertEquals(before, driveFiles())
    }

    @Test
    fun `set up asks for the extra password instead of replacing a vault that has one`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        first.setExtraPassword("pw".toCharArray())
        val phone = newPhone().vault()
        assertEquals(VaultText.PASSWORD_NEEDED, failsWith<VaultException> { phone.setUp() }.message)
        assertEquals(KeyState.NeedsPassword, phone.state.value)
    }

    @Test
    fun `starting over makes a key numbered above the old one, and a phone holding the old key takes it`() = runTest {
        val drawer = newPhone().vault()
        drawer.setUp()
        val oldChat = drawer.seal("from the old key")
        drawer.rekey(RekeyReason.OWNER_ASKED)
        accounts.gitHub.keyring().files.clear()

        val phone = newPhone().vault()
        assertTrue(phone.restore() is KeyState.Lost)
        phone.startOver()
        assertEquals(KeyState.Ready, phone.state.value)
        assertEquals(3, phone.generation())
        val newChat = phone.seal("from the new key")

        drawer.checkKeyring()
        assertEquals("the phone in the drawer took the new key", 3, drawer.generation())
        assertEquals(KeyState.Ready, drawer.state.value)
        assertEquals("from the new key", drawer.open(newChat))
        assertEquals("from the old key", drawer.open(oldChat))
        val fresh = newPhone().vault()
        assertEquals(KeyState.Ready, fresh.restore())
        assertEquals("from the new key", fresh.open(newChat))
    }

    @Test
    fun `start over keeps a key the phone holds, and needs GitHub`() = runTest {
        val vault = newPhone().vault()
        vault.setUp()
        val key = vault.currentKey()
        vault.startOver()
        assertEquals(key, vault.currentKey())
        assertEquals(1, vault.generation())

        accounts.account.value = null
        assertEquals(VaultText.CONNECT_GITHUB_FIRST, failsWith<VaultException> { newPhone().vault().startOver() }.message)
    }

    @Test
    fun `a keyring the GitHub App cannot see is never made again over it, and Drive is left alone`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        val before = driveFiles()
        accounts.gitHub.keyringHidden = true

        val phone = newPhone().vault()
        assertEquals(KeyState.Lost(VaultText.GITHUB_HALF_MISSING), phone.restore())
        assertEquals(VaultText.KEYRING_NOT_MADE, failsWith<VaultException> { phone.startOver() }.message)
        assertEquals(VaultText.KEYRING_NOT_MADE, failsWith<VaultException> { first.checkKeyring() }.message)
        assertEquals("nothing in Drive was replaced", before, driveFiles())

        accounts.gitHub.keyringHidden = false
        assertEquals(KeyState.Ready, phone.restore())
        assertEquals(first.currentKey(), phone.currentKey())
    }

    @Test
    fun `a new vault after Delete everything is numbered above the Half G left in GitHub, so an old phone takes it`() = runTest {
        val a = newPhone().vault()
        a.setUp()
        a.rekey(RekeyReason.OWNER_ASKED)
        a.rekey(RekeyReason.OWNER_ASKED)
        val drawer = newPhone().vault()
        drawer.restore()
        assertEquals(3, drawer.generation())

        deleteEverything(a)
        a.setUp()
        assertEquals(4, a.generation())
        val chat = a.seal("after Delete everything")

        drawer.checkKeyring()
        assertEquals(4, drawer.generation())
        assertEquals("after Delete everything", drawer.open(chat))
        val fresh = newPhone().vault()
        assertEquals(KeyState.Ready, fresh.restore())
        assertEquals("after Delete everything", fresh.open(chat))
    }

    @Test
    fun `a Half G with a higher number left by a deleted vault is replaced, never waited on`() = runTest {
        val vault = newPhone().vault()
        vault.setUp()
        val stale = HalfPassword.derive("an old password".toCharArray(), Argon2Cost(256, 1, 1), SecureRandom())
        putHalfG(HalfGFile(generation = 7, wrapped = base64(HalfPassword.wrap(ByteArray(32), stale, SecureRandom()))))

        vault.checkKeyring()
        assertEquals("no password is asked for a half that pairs with nothing", KeyState.Ready, vault.state.value)
        assertEquals(1, halfG().generation)
        assertNotNull(halfG().half)
        val fresh = newPhone().vault()
        assertEquals(KeyState.Ready, fresh.restore())
        assertEquals(vault.currentKey(), fresh.currentKey())
    }

    @Test
    fun `a phone in a drawer never overwrites a vault made again after Delete everything`() = runTest {
        val a = newPhone().vault()
        a.setUp()
        val drawer = newPhone().vault()
        drawer.restore()
        deleteEverything(a)
        accounts.gitHub.repos.clear()
        a.setUp()
        val chat = a.seal("the new vault")
        val afterSetUp = driveFiles()
        accounts.now += 2 * DAY

        drawer.checkKeyring()
        assertEquals(VaultText.ANOTHER_VAULT, drawer.notice.value)
        assertEquals(KeyState.OnlyOnPhone, drawer.state.value)
        assertEquals("the drawer phone wrote nothing", afterSetUp, driveFiles())
        val fresh = newPhone().vault()
        assertEquals(KeyState.Ready, fresh.restore())
        assertEquals("the new vault", fresh.open(chat))
        a.checkKeyring()
        assertEquals(KeyState.Ready, a.state.value)
    }

    @Test
    fun `a public keyring is re-keyed even while a lost phone's half-saved key sits in Drive`() = runTest {
        val lost = newPhone().vault()
        lost.setUp()
        val phone = newPhone().vault()
        phone.restore()
        val chat = phone.seal("before the keyring went public")
        accounts.gitHub.failWrites = 1
        failsWith<IOException> { lost.rekey(RekeyReason.OWNER_ASKED) }
        assertEquals("the lost phone's key 2 got as far as Drive", 2, halfD().generation)

        accounts.gitHub.keyring().isPrivate = false
        phone.checkKeyring()
        assertEquals("changed, numbered above the unfinished key", 3, phone.generation())
        assertEquals(KeyState.OnlyOnPhone, phone.state.value)
        assertEquals(VaultText.KEYRING_PUBLIC, phone.notice.value)
        assertTrue("the public Half G pairs with nothing in Drive", halfD().entries().none { it.generation == halfG().generation })

        accounts.gitHub.keyring().isPrivate = true
        phone.checkKeyring()
        assertEquals(KeyState.Ready, phone.state.value)
        val fresh = newPhone().vault()
        assertEquals(KeyState.Ready, fresh.restore())
        assertEquals(3, fresh.generation())
        assertEquals("before the keyring went public", fresh.open(chat))
    }

    @Test
    fun `a deleted keyring is saved again even while a lost phone's half-saved key sits in Drive`() = runTest {
        val lost = newPhone().vault()
        lost.setUp()
        val phone = newPhone().vault()
        phone.restore()
        val chat = phone.seal("kept")
        accounts.gitHub.failWrites = 1
        failsWith<IOException> { lost.rekey(RekeyReason.OWNER_ASKED) }
        accounts.gitHub.repos.clear()

        phone.checkKeyring()
        assertEquals(KeyState.Ready, phone.state.value)
        val fresh = newPhone().vault()
        assertEquals(KeyState.Ready, fresh.restore())
        assertEquals("kept", fresh.open(chat))
    }

    @Test
    fun `the extra password can be set while a lost phone's half-saved key sits in Drive`() = runTest {
        val lost = newPhone().vault()
        lost.setUp()
        val phone = newPhone().vault()
        phone.restore()
        val chat = phone.seal("written before the password")
        accounts.gitHub.failWrites = 1
        failsWith<IOException> { lost.rekey(RekeyReason.OWNER_ASKED) }

        phone.setExtraPassword("pw".toCharArray())
        assertEquals(KeyState.Ready, phone.state.value)
        assertEquals(3, phone.generation())
        assertNotNull(halfG().wrapped)
        val fresh = newPhone().vault()
        assertEquals(KeyState.NeedsPassword, fresh.restore())
        assertEquals(KeyState.Ready, fresh.restore("pw".toCharArray()))
        assertEquals("written before the password", fresh.open(chat))
    }

    @Test
    fun `another phone's key change on its way is never rolled back or re-keyed over`() = runTest {
        val a = newPhone().vault()
        a.setUp()
        val b = newPhone().vault()
        b.restore()
        accounts.gitHub.failWrites = 1
        failsWith<IOException> { a.rekey(RekeyReason.OWNER_ASKED) }
        val inFlight = halfD()

        b.checkKeyring()
        assertEquals(1, b.generation())
        assertEquals(KeyState.Ready, b.state.value)
        assertEquals("B left A's unfinished Half D as it was", inFlight, halfD())

        a.checkKeyring()
        assertEquals(2, a.generation())
        b.checkKeyring()
        assertEquals(2, b.generation())
        assertEquals(a.currentKey(), b.currentKey())
    }

    @Test
    fun `a password removed on another phone is never put back by this phone`() = runTest {
        val a = newPhone().vault()
        a.setUp()
        a.setExtraPassword("pw".toCharArray())
        val bPhone = newPhone()
        val b = bPhone.vault()
        assertEquals(KeyState.Ready, b.restore("pw".toCharArray()))
        assertEquals(true, bPhone.passwordSetting)

        a.setExtraPassword(null)
        b.checkKeyring()
        assertEquals(false, bPhone.passwordSetting)
        b.rekey(RekeyReason.OWNER_ASKED)
        assertNotNull("the new Half G is plain, as the owner chose", halfG().half)
        assertEquals(KeyState.Ready, newPhone().vault().restore())
    }

    @Test
    fun `a password is not recorded while this phone is behind another phone's newer key`() = runTest {
        val a = newPhone().vault()
        a.setUp()
        val bPhone = newPhone()
        val b = bPhone.vault()
        b.restore()
        a.rekey(RekeyReason.OWNER_ASKED)

        assertEquals(VaultText.ANOTHER_PHONE, failsWith<VaultException> { b.setExtraPassword("pw".toCharArray()) }.message)
        assertEquals(false, bPhone.passwordSetting)
        assertNotNull(halfG().half)

        b.checkKeyring()
        b.setExtraPassword("pw".toCharArray())
        assertEquals(true, bPhone.passwordSetting)
        assertNotNull(halfG().wrapped)
        assertEquals(KeyState.NeedsPassword, newPhone().vault().restore())
    }

    @Test
    fun `an imported key copy wins over a new vault this phone had begun`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        val chat = first.seal("keep me")
        val copy = first.exportKeyCopy()
        accounts.gitHub.keyring().files.clear()

        val phone = newPhone().vault()
        assertTrue(phone.restore() is KeyState.Lost)
        accounts.drive.failUploads = 1
        failsWith<IOException> { phone.startOver() }
        phone.importKeyCopy(copy)
        phone.checkKeyring()

        assertEquals(first.currentKey(), phone.currentKey())
        val fresh = newPhone().vault()
        assertEquals(KeyState.Ready, fresh.restore())
        assertEquals("keep me", fresh.open(chat))
    }

    @Test
    fun `a key copy numbered below Drive's key is saved again at Drive's number`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        first.rekey(RekeyReason.OWNER_ASKED)
        first.rekey(RekeyReason.OWNER_ASKED)
        val chat = first.seal("three")
        val copy = first.exportKeyCopy().replace("# generation 3 (current)", "# generation 1 (current)")
        accounts.gitHub.repos.clear()

        val phone = newPhone().vault()
        phone.importKeyCopy(copy)
        assertEquals(3, phone.generation())
        assertEquals(first.currentKey(), phone.currentKey())
        assertEquals(KeyState.Ready, phone.state.value)
        val fresh = newPhone().vault()
        assertEquals(KeyState.Ready, fresh.restore())
        assertEquals("three", fresh.open(chat))
    }

    @Test
    fun `a key copy cannot make a stranger's key the one new chats are written with`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        val stranger = AgeIdentity.generate(SecureRandom())
        val tampered = "# generation 99\n${stranger.encoded()}\n" + first.exportKeyCopy()
        accounts.gitHub.repos.clear()

        val phone = newPhone().vault()
        phone.importKeyCopy(tampered)
        assertEquals(first.currentKey(), phone.currentKey())
        assertEquals("written after the import", first.open(phone.seal("written after the import")))
    }

    @Test
    fun `forgetting also turns the extra password setting off`() = runTest {
        val phone = newPhone()
        val vault = phone.vault()
        vault.setUp()
        vault.setExtraPassword("pw".toCharArray())
        vault.forget()
        assertEquals(false, phone.passwordSetting)
        assertNull(vault.notice.value)
    }

    @Test
    fun `a Keystore that could not open the key at start is read again, so the only copy is kept`() = runTest {
        val phone = newPhone()
        val vault = phone.vault()
        vault.setUp()
        val oldChat = vault.seal("with key 1")
        accounts.gitHub.keyring().isPrivate = false
        vault.checkKeyring()
        assertEquals("key 2 is only on this phone", KeyState.OnlyOnPhone, vault.state.value)
        val onlyCopy = vault.currentKey()

        phone.box.failOpens = 2
        val restarted = phone.vault()
        assertEquals(KeyState.None, restarted.state.value)
        assertEquals(KeyState.OnlyOnPhone, restarted.restore())
        assertEquals(onlyCopy, restarted.currentKey())
        assertEquals("with key 1", restarted.open(oldChat))
    }

    @Test
    fun `key files with impossible numbers are damaged, not trusted`() = runTest {
        newPhone().vault().setUp()
        val d = halfD()
        putHalfD(d.copy(generation = Int.MAX_VALUE))
        assertEquals(KeyState.Lost(VaultText.DRIVE_HALF_DAMAGED), newPhone().vault().restore())
        putHalfD(d.copy(previous = listOf(HalfEntry(-5, d.half))))
        assertEquals(KeyState.Lost(VaultText.DRIVE_HALF_DAMAGED), newPhone().vault().restore())
        putHalfD(d)
        val g = halfG()
        putHalfG(g.copy(generation = 0))
        assertEquals(KeyState.Lost(VaultText.GITHUB_HALF_DAMAGED), newPhone().vault().restore())
        putHalfG(g)
        assertEquals(KeyState.Ready, newPhone().vault().restore())
    }

    @Test
    fun `a Drive key file that grows past its listed size is stopped as damaged`() = runTest {
        newPhone().vault().setUp()
        accounts.drive.padDownloads = 300 * 1024
        assertEquals(KeyState.Lost(VaultText.DRIVE_HALF_DAMAGED), newPhone().vault().restore())
    }

    @Test
    fun `set up without GitHub makes a key that waits for GitHub, then saves both halves`() = runTest {
        accounts.account.value = null
        val vault = newPhone().vault()
        vault.setUp()
        assertEquals(KeyState.OnlyOnPhone, vault.state.value)
        assertEquals(VaultText.CONNECT_GITHUB, vault.notice.value)

        accounts.account.value = GitHubAccount(FakeGitHub.OWNER, 1, null, null)
        vault.checkKeyring()
        assertEquals(KeyState.Ready, vault.state.value)
        assertNull("a keyring made for the first time is not reported as made again", vault.notice.value)
        assertEquals(KeyState.Ready, newPhone().vault().restore())
    }

    @Test
    fun `nothing secret shows when key records are printed`() {
        val secret = AgeIdentity.generate(SecureRandom()).encoded()
        val change = KeyChange(ChangeKind.REKEY, 2, secret, "aGFsZg==", RekeyReason.OWNER_ASKED.name)
        val password = PasswordRecord(256, 1, 1, "c2FsdA==", "S0VZTUFURVJJQUw=")
        for (record in listOf(StoredKey(1, secret), change, password, PhoneState(change = change, password = password))) {
            val text = record.toString()
            assertFalse(text, text.contains(secret) || text.contains("S0VZTUFURVJJQUw="))
        }
    }

    @Test
    fun `a key change cancelled in the middle finishes at the next check, after a restart too`() = runBlocking {
        val phone = newPhone()
        val vault = phone.vault()
        vault.setUp()
        val chat = vault.seal("before")
        val gate = CompletableDeferred<Unit>()
        accounts.drive.uploadGate = gate
        val job = launch(Dispatchers.Default) { vault.rekey(RekeyReason.OWNER_ASKED) }
        accounts.drive.uploadsWaiting.first { it > 0 }
        job.cancel()
        job.join()
        accounts.drive.uploadGate = null
        gate.complete(Unit)

        assertEquals("the phone did not switch half way", 1, vault.generation())
        val restarted = phone.vault()
        assertEquals(1, restarted.generation())
        restarted.checkKeyring()
        assertEquals(2, restarted.generation())
        assertEquals(KeyState.Ready, restarted.state.value)
        assertEquals("before", restarted.open(chat))
        val fresh = newPhone().vault()
        assertEquals(KeyState.Ready, fresh.restore())
        assertEquals(2, fresh.generation())
        assertEquals("before", fresh.open(chat))
    }

    @Test
    fun `key changes, checks and encryption at the same time leave one consistent vault`() = runBlocking {
        val vault = newPhone().vault()
        vault.setUp()
        val sealed = Collections.synchronizedList(mutableListOf<Pair<String, ByteArray>>())
        coroutineScope {
            repeat(4) { launch(Dispatchers.Default) { vault.rekey(RekeyReason.OWNER_ASKED) } }
            repeat(4) { launch(Dispatchers.Default) { vault.checkKeyring() } }
            repeat(24) { i -> launch(Dispatchers.Default) { sealed += "chat $i" to vault.seal("chat $i") } }
        }
        assertEquals(5, vault.generation())
        assertEquals(KeyState.Ready, vault.state.value)
        val fresh = newPhone().vault()
        assertEquals(KeyState.Ready, fresh.restore())
        assertEquals(5, fresh.generation())
        sealed.forEach { (text, bytes) -> assertEquals(text, fresh.open(bytes)) }
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}

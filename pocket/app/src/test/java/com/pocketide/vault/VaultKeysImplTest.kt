package com.pocketide.vault

import com.pocketide.core.AppJson
import com.pocketide.github.NotConnectedException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class VaultKeysImplTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val accounts = Accounts()

    private fun newPhone() = TestPhone(accounts, folder.newFolder())

    private fun halfD() = checkNotNull(accounts.halfD()) { "no keyhalf-d in Drive" }

    private fun halfG() = checkNotNull(accounts.halfG()) { "no half-g.json in the keyring" }

    private fun keyFromHalves(d: String, g: String) = KeySplit.join(checkNotNull(decodeHalf(d)), checkNotNull(decodeHalf(g)))

    private fun VaultKeys.seal(text: String) = cipher().encryptBytes(text.toByteArray())

    private fun VaultKeys.open(sealed: ByteArray) = cipher().decryptBytes(sealed).toString(Charsets.UTF_8)

    private suspend inline fun <reified T : Throwable> failsWith(block: suspend () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw AssertionError("expected ${T::class.java.simpleName}, got $e", e)
        }
        throw AssertionError("expected ${T::class.java.simpleName}, nothing was thrown")
    }

    @Test
    fun `set up keeps the full key on the phone and one half in each place`() = runTest {
        val vault = newPhone().vault()
        assertEquals(KeyState.None, vault.state.value)
        vault.setUp()
        assertEquals(KeyState.Ready, vault.state.value)
        assertEquals(1, vault.generation())
        val d = halfD()
        val g = halfG()
        assertEquals(1, d.generation)
        assertEquals(1, g.generation)
        assertNull(d.previous)
        val phoneKey = VaultFixtures.identities(vault.exportKeyCopy()).first().bytes()
        assertArrayEquals(phoneKey, keyFromHalves(d.half, checkNotNull(g.half)))
        assertFalse(checkNotNull(decodeHalf(d.half)).contentEquals(phoneKey))
        assertFalse(checkNotNull(decodeHalf(checkNotNull(g.half))).contentEquals(phoneKey))
        val keyring = accounts.gitHub.keyring()
        assertTrue(keyring.isPrivate)
        assertFalse("Actions are off in the keyring", keyring.actionsEnabled)
        assertNotNull(accounts.drive.bytes(VaultKeyFiles.KEY_CHECK))
        assertNotNull(accounts.drive.bytes(VaultKeyFiles.KEY_HISTORY))
        assertNull(vault.notice.value)
    }

    @Test
    fun `the state at start comes from what the phone holds`() = runTest {
        val phone = newPhone()
        phone.vault().setUp()
        val restarted = phone.vault()
        assertEquals(KeyState.Ready, restarted.state.value)
        assertEquals(1, restarted.generation())
        assertEquals(KeyState.None, newPhone().vault().state.value)
    }

    @Test
    fun `a new phone rebuilds the same key from both halves`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        val chat = first.seal("hello from the first phone")
        val second = newPhone().vault()
        assertEquals(KeyState.Ready, second.restore())
        assertEquals(KeyState.Ready, second.state.value)
        assertEquals(1, second.generation())
        assertEquals("hello from the first phone", second.open(chat))
    }

    @Test
    fun `set up on a Google account that already has a vault opens it and never replaces it`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        val chat = first.seal("keep me")
        val before = halfD()
        val second = newPhone().vault()
        second.setUp()
        assertEquals(KeyState.Ready, second.state.value)
        assertEquals("keep me", second.open(chat))
        assertEquals(before, halfD())
    }

    @Test
    fun `a new phone with no vault anywhere has no key yet`() = runTest {
        assertEquals(KeyState.None, newPhone().vault().restore())
    }

    @Test
    fun `a wrong Half G is refused with a plain sentence`() = runTest {
        newPhone().vault().setUp()
        val wrong = HalfGFile(generation = 1, half = base64(ByteArray(32) { 7 }))
        accounts.gitHub.keyring().files[VaultKeyFiles.HALF_G_PATH] =
            com.pocketide.github.RepoFile(VaultKeyFiles.HALF_G_PATH, "x", AppJson.encodeToString(HalfGFile.serializer(), wrong).toByteArray())
        val second = newPhone().vault()
        val state = second.restore()
        assertEquals(KeyState.Lost(VaultText.HALVES_WRONG), state)
        assertEquals(0, second.generation())
    }

    @Test
    fun `a wrong or damaged Half D is refused`() = runTest {
        newPhone().vault().setUp()
        val d = halfD()
        accounts.drive.put(VaultKeyFiles.HALF_D, AppJson.encodeToString(HalfDFile.serializer(), d.copy(half = base64(ByteArray(32) { 3 }))).toByteArray())
        assertEquals(KeyState.Lost(VaultText.HALVES_WRONG), newPhone().vault().restore())
        accounts.drive.put(VaultKeyFiles.HALF_D, "{not json".toByteArray())
        assertEquals(KeyState.Lost(VaultText.DRIVE_HALF_DAMAGED), newPhone().vault().restore())
    }

    @Test
    fun `halves of two different keys are refused`() = runTest {
        newPhone().vault().setUp()
        accounts.drive.put(VaultKeyFiles.HALF_D, AppJson.encodeToString(HalfDFile.serializer(), halfD().copy(generation = 2)).toByteArray())
        assertEquals(KeyState.Lost(VaultText.HALVES_APART), newPhone().vault().restore())
    }

    @Test
    fun `a missing GitHub half is refused, and a new phone without GitHub is asked to connect it`() = runTest {
        newPhone().vault().setUp()
        accounts.account.value = null
        assertEquals(KeyState.Lost(VaultText.CONNECT_GITHUB_TO_RESTORE), newPhone().vault().restore())
        accounts.account.value = com.pocketide.github.GitHubAccount(FakeGitHub.OWNER, 1, null, null)
        accounts.gitHub.keyring().files.clear()
        assertEquals(KeyState.Lost(VaultText.GITHUB_HALF_MISSING), newPhone().vault().restore())
        accounts.gitHub.repos.clear()
        assertEquals(KeyState.Lost(VaultText.GITHUB_HALF_MISSING), newPhone().vault().restore())
    }

    @Test
    fun `a key file from a newer app is never treated as damaged`() = runTest {
        newPhone().vault().setUp()
        accounts.drive.put(VaultKeyFiles.HALF_D, """{"v":2,"generation":1,"shape":"new"}""".toByteArray())
        val error = failsWith<VaultException> { newPhone().vault().restore() }
        assertEquals(VaultText.NEWER_APP, error.message)
    }

    @Test
    fun `the extra password - a new phone needs it, a wrong one is a clear error, the right one opens`() = runTest {
        val phone = newPhone()
        val first = phone.vault()
        first.setUp()
        val chat = first.seal("private chat")
        first.setExtraPassword("correct horse".toCharArray())
        assertEquals(true, phone.passwordSetting)
        assertNull("Half G is no longer in the clear", halfG().half)
        assertNotNull(halfG().wrapped)
        assertEquals(KeyState.Ready, first.state.value)

        val second = newPhone()
        val vault = second.vault()
        assertEquals(KeyState.NeedsPassword, vault.restore())
        assertEquals(KeyState.NeedsPassword, vault.state.value)
        assertEquals(true, second.passwordSetting)
        val wrong = failsWith<WrongPasswordException> { vault.restore("wrong horse".toCharArray()) }
        assertEquals("That password is not right. Check it and try again.", wrong.message)
        assertEquals(KeyState.Ready, vault.restore("correct horse".toCharArray()))
        assertEquals("private chat", vault.open(chat))
    }

    @Test
    fun `setting the password splits the key afresh, so the plain half in git history is useless`() = runTest {
        val vault = newPhone().vault()
        vault.setUp()
        val oldPlainG = checkNotNull(halfG().half)
        vault.setExtraPassword("pw".toCharArray())
        val key = VaultFixtures.identities(vault.exportKeyCopy()).first().bytes()
        val d = halfD()
        assertNull("no old half is kept once the new one is saved", d.previous)
        assertFalse(keyFromHalves(d.half, oldPlainG).contentEquals(key))
        val history = accounts.gitHub.keyring().history
        assertEquals("both versions of Half G are in git history", 2, history.size)
    }

    @Test
    fun `clearing the password saves a plain half again`() = runTest {
        val phone = newPhone()
        val vault = phone.vault()
        vault.setUp()
        vault.setExtraPassword("pw".toCharArray())
        vault.setExtraPassword(null)
        assertEquals(false, phone.passwordSetting)
        assertNotNull(halfG().half)
        assertEquals(KeyState.Ready, newPhone().vault().restore())
    }

    @Test
    fun `an empty password is refused`() = runTest {
        val vault = newPhone().vault()
        vault.setUp()
        val error = failsWith<VaultException> { vault.setExtraPassword(CharArray(0)) }
        assertEquals(VaultText.EMPTY_PASSWORD, error.message)
    }

    @Test
    fun `a public keyring gets a new key whose GitHub half waits until the repo is private again`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        val oldChat = first.seal("written with key 1")
        accounts.gitHub.keyring().isPrivate = false

        val check = first.checkKeyring()
        assertFalse(check.isPrivate)
        assertEquals(2, first.generation())
        assertEquals(KeyState.OnlyOnPhone, first.state.value)
        assertEquals(VaultText.KEYRING_PUBLIC, first.notice.value)
        assertEquals("nothing new is written to a public repo", 1, halfG().generation)
        assertEquals(2, halfD().generation)
        assertNull("the old Half D is gone, so the exposed Half G opens nothing", halfD().previous)
        assertEquals(KeyState.Lost(VaultText.HALVES_APART), newPhone().vault().restore())

        // A second check while still public does not change the key again.
        first.checkKeyring()
        assertEquals(2, first.generation())

        val newChat = first.seal("written with key 2")
        accounts.gitHub.keyring().isPrivate = true
        first.checkKeyring()
        assertEquals(KeyState.Ready, first.state.value)
        assertNull(first.notice.value)
        assertEquals(2, halfG().generation)

        val second = newPhone().vault()
        assertEquals(KeyState.Ready, second.restore())
        assertEquals(2, second.generation())
        assertEquals("written with key 1", second.open(oldChat))
        assertEquals("written with key 2", second.open(newChat))
    }

    @Test
    fun `someone added to the keyring gets the key changed, and the owner alone is not someone`() = runTest {
        val vault = newPhone().vault()
        vault.setUp()
        assertEquals(emptyList<String>(), vault.checkKeyring().collaborators)
        assertEquals(1, vault.generation())

        accounts.gitHub.keyring().collaborators += "mallory"
        val check = vault.checkKeyring()
        assertEquals(listOf("mallory"), check.collaborators)
        assertEquals(2, vault.generation())
        assertEquals(KeyState.OnlyOnPhone, vault.state.value)
        assertTrue(checkNotNull(vault.notice.value).contains("mallory"))
    }

    @Test
    fun `a deleted keyring is made again with fresh halves while the phone has the key`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        val chat = first.seal("still here")
        val oldD = halfD().half
        accounts.gitHub.repos.clear()

        val check = first.checkKeyring()
        assertTrue(check.exists && check.isPrivate && check.actionsDisabled)
        assertEquals("the same key, no re-encryption", 1, first.generation())
        assertNotEquals(oldD, halfD().half)
        assertEquals(KeyState.Ready, first.state.value)
        assertEquals(VaultText.KEYRING_REMADE, first.notice.value)
        assertFalse(accounts.gitHub.keyring().actionsEnabled)

        val second = newPhone().vault()
        assertEquals(KeyState.Ready, second.restore())
        assertEquals("still here", second.open(chat))
    }

    @Test
    fun `when GitHub is gone the key is only on this phone, until it is connected again`() = runTest {
        val vault = newPhone().vault()
        vault.setUp()
        accounts.gitHub.connected = false
        failsWith<NotConnectedException> { vault.checkKeyring() }
        assertEquals(KeyState.OnlyOnPhone, vault.state.value)
        assertEquals(VaultText.CONNECT_GITHUB, vault.notice.value)

        accounts.gitHub.connected = true
        accounts.account.value = null
        failsWith<NotConnectedException> { vault.checkKeyring() }
        assertEquals(KeyState.OnlyOnPhone, vault.state.value)

        accounts.account.value = com.pocketide.github.GitHubAccount(FakeGitHub.OWNER, 1, null, null)
        vault.checkKeyring()
        assertEquals(KeyState.Ready, vault.state.value)
        assertNull(vault.notice.value)
        assertEquals(1, vault.generation())
    }

    @Test
    fun `a key change cut off before Half G is saved still restores, and finishes at the next check`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        val chat = first.seal("before the change")
        accounts.gitHub.failWrites = 1
        failsWith<IOException> { first.rekey(RekeyReason.OWNER_ASKED) }
        assertEquals("the phone keeps using the saved key", 1, first.generation())
        assertEquals(2, halfD().generation)
        assertEquals(listOf(1), halfD().previous?.map { it.generation })

        // The phone is lost now: a new phone still rebuilds key 1 from the old Half D kept beside the new one.
        val rescue = newPhone().vault()
        assertEquals(KeyState.Ready, rescue.restore())
        assertEquals(1, rescue.generation())
        assertEquals("before the change", rescue.open(chat))

        // The phone was not lost after all: its next check finishes the change.
        first.checkKeyring()
        assertEquals(2, first.generation())
        assertEquals(KeyState.Ready, first.state.value)
        assertNull(halfD().previous)
        val later = newPhone().vault()
        assertEquals(KeyState.Ready, later.restore())
        assertEquals(2, later.generation())
        assertEquals("before the change", later.open(chat))
    }

    @Test
    fun `a restart in the middle of a change resumes with the same new key`() = runTest {
        val phone = newPhone()
        phone.vault().setUp()
        accounts.drive.failUploads = 1
        failsWith<IOException> { phone.vault().rekey(RekeyReason.OWNER_ASKED) }
        val restarted = phone.vault()
        assertEquals(1, restarted.generation())
        restarted.checkKeyring()
        assertEquals(2, restarted.generation())
        val chat = restarted.seal("after the restart")
        val other = newPhone().vault()
        assertEquals(KeyState.Ready, other.restore())
        assertEquals("after the restart", other.open(chat))
    }

    @Test
    fun `every older key stays readable on a new phone after two key changes`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        val chats = mutableListOf(first.seal("one"))
        first.rekey(RekeyReason.OWNER_ASKED)
        chats += first.seal("two")
        first.rekey(RekeyReason.OWNER_ASKED)
        chats += first.seal("three")
        assertEquals(3, first.generation())

        val second = newPhone().vault()
        assertEquals(KeyState.Ready, second.restore())
        assertEquals(3, second.generation())
        assertEquals(listOf("one", "two", "three"), chats.map { second.open(it) })
    }

    @Test
    fun `the cipher encrypts to the newest key and opens files of older keys`() = runTest {
        val vault = newPhone().vault()
        vault.setUp()
        val old = vault.seal("old")
        vault.rekey(RekeyReason.OWNER_ASKED)
        val new = vault.seal("new")
        val copy = VaultFixtures.identities(vault.exportKeyCopy())
        assertEquals(2, copy.size)
        assertEquals("new", Age.decryptBytes(listOf(copy[0]), new).toString(Charsets.UTF_8))
        failsWith<AgeNoMatchException> { Age.decryptBytes(listOf(copy[1]), new) }
        assertEquals("old", vault.open(old))
    }

    @Test
    fun `a key copy rebuilds the vault after the phone and GitHub are both lost`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        val chat = first.seal("survives a double loss")
        val copy = first.exportKeyCopy()
        assertTrue(copy.startsWith("# PocketIDE key copy"))

        accounts.gitHub.repos.clear()
        val second = newPhone().vault()
        assertEquals(KeyState.Lost(VaultText.GITHUB_HALF_MISSING), second.restore())
        second.importKeyCopy(copy)
        assertEquals(KeyState.Ready, second.state.value)
        assertEquals("survives a double loss", second.open(chat))
        assertTrue(accounts.gitHub.keyring().isPrivate)

        val third = newPhone().vault()
        assertEquals(KeyState.Ready, third.restore())
        assertEquals("survives a double loss", third.open(chat))
    }

    @Test
    fun `a key copy that is not one, or does not open this vault, is refused`() = runTest {
        newPhone().vault().setUp()
        val vault = newPhone().vault()
        val stranger = AgeIdentity.generate(java.security.SecureRandom()).encoded()
        assertEquals(VaultText.COPY_DOES_NOT_OPEN, failsWith<VaultException> { vault.importKeyCopy(stranger) }.message)
        assertEquals(VaultText.NOT_A_KEY_COPY, failsWith<VaultException> { vault.importKeyCopy("hello") }.message)
        assertEquals(0, vault.generation())
    }

    @Test
    fun `a phone that fell behind takes the newer key from the halves`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        val behind = newPhone().vault()
        behind.restore()
        first.rekey(RekeyReason.OWNER_ASKED)
        val chat = first.seal("new key")

        behind.checkKeyring()
        assertEquals(2, behind.generation())
        assertEquals(KeyState.Ready, behind.state.value)
        assertEquals("new key", behind.open(chat))
        assertEquals("the newer key was not overwritten", 2, halfG().generation)
    }

    @Test
    fun `a password set on another phone is kept, never replaced by a plain half`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        val otherPhone = newPhone()
        val other = otherPhone.vault()
        other.restore()
        first.setExtraPassword("pw".toCharArray())

        other.checkKeyring()
        assertEquals("the other phone saw its halves saved before, so it trusts the new ones", KeyState.Ready, other.state.value)
        assertEquals(true, otherPhone.passwordSetting)

        // A new key made on the other phone waits for the password rather than dropping it.
        other.rekey(RekeyReason.OWNER_ASKED)
        assertEquals(KeyState.OnlyOnPhone, other.state.value)
        assertEquals(VaultText.PASSWORD_AGAIN, other.notice.value)
        assertEquals(1, halfG().generation)
        assertNotNull(halfG().wrapped)

        other.setExtraPassword("pw".toCharArray())
        assertEquals(KeyState.Ready, other.state.value)
        assertEquals(2, halfG().generation)
        assertNotNull(halfG().wrapped)
        assertEquals(KeyState.Ready, newPhone().vault().restore("pw".toCharArray()))
    }

    @Test
    fun `an imported key never swaps a password-wrapped half for a plain one`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        first.setExtraPassword("pw".toCharArray())
        val copy = first.exportKeyCopy()
        val wrappedBefore = halfG()

        val second = newPhone().vault()
        second.importKeyCopy(copy)
        assertEquals(KeyState.OnlyOnPhone, second.state.value)
        assertEquals(VaultText.PASSWORD_AGAIN, second.notice.value)
        assertEquals(wrappedBefore, halfG())
        assertEquals(KeyState.Ready, newPhone().vault().restore("pw".toCharArray()))
    }

    @Test
    fun `a first set-up cut off before Half G is saved is finished, not reported lost`() = runTest {
        val phone = newPhone()
        accounts.gitHub.failWrites = 1
        failsWith<IOException> { phone.vault().setUp() }
        val restarted = phone.vault()
        assertEquals(KeyState.None, restarted.state.value)
        assertEquals(KeyState.Ready, restarted.restore())
        assertEquals(1, restarted.generation())
        assertEquals(KeyState.Ready, newPhone().vault().restore())
    }

    @Test
    fun `a phone whose Keystore lost its key starts empty and rebuilds from the halves`() = runTest {
        val phone = newPhone()
        val first = phone.vault()
        first.setUp()
        val chat = first.seal("kept in Drive")
        // Android dropped the Keystore key: the sealed files are still there but no longer open.
        phone.dir.listFiles().orEmpty().forEach { it.writeBytes(byteArrayOf(9, 9, 9)) }
        val restarted = phone.vault()
        assertEquals(KeyState.None, restarted.state.value)
        assertEquals(KeyState.Ready, restarted.restore())
        assertEquals("kept in Drive", restarted.open(chat))
        assertEquals(KeyState.Ready, phone.vault().state.value)
    }

    @Test
    fun `an emptied Drive folder gets its key check, history and Half D back at the next check`() = runTest {
        val first = newPhone().vault()
        first.setUp()
        val old = first.seal("from key 1")
        first.rekey(RekeyReason.OWNER_ASKED)
        val new = first.seal("from key 2")
        accounts.drive.names().forEach { accounts.drive.remove(it) }

        first.checkKeyring()
        assertEquals(KeyState.Ready, first.state.value)
        assertEquals(2, first.generation())
        assertTrue(accounts.drive.names().containsAll(VaultKeyFiles.DRIVE_NAMES))
        val second = newPhone().vault()
        assertEquals(KeyState.Ready, second.restore())
        assertEquals(listOf("from key 1", "from key 2"), listOf(second.open(old), second.open(new)))
    }

    @Test
    fun `a stale change left on one phone never overwrites another phone's newer key`() = runTest {
        val a = newPhone().vault()
        a.setUp()
        val b = newPhone().vault()
        b.restore()
        // B's re-key stops at its first Drive write and stays pending on B.
        accounts.drive.failUploads = 1
        failsWith<IOException> { b.rekey(RekeyReason.OWNER_ASKED) }
        a.rekey(RekeyReason.OWNER_ASKED)
        val chat = a.seal("written by A with key 2")

        assertEquals(VaultText.ANOTHER_PHONE, failsWith<VaultException> { b.checkKeyring() }.message)
        val fresh = newPhone().vault()
        assertEquals("A's key check and history are intact", KeyState.Ready, fresh.restore())
        assertEquals("written by A with key 2", fresh.open(chat))

        b.checkKeyring()
        assertEquals(2, b.generation())
        assertEquals("written by A with key 2", b.open(chat))
    }

    @Test
    fun `a missing key check stops a rebuild, because the key could not be trusted`() = runTest {
        newPhone().vault().setUp()
        accounts.drive.remove(VaultKeyFiles.KEY_CHECK)
        assertEquals(KeyState.Lost(VaultText.CHECK_MISSING), newPhone().vault().restore())
    }

    @Test
    fun `forgetting after Delete everything leaves no key behind, and set-up makes a new vault`() = runTest {
        val phone = newPhone()
        val vault = phone.vault()
        vault.setUp()
        val oldKey = VaultFixtures.identities(vault.exportKeyCopy()).single()
        accounts.drive.names().forEach { accounts.drive.remove(it) }

        vault.forget()
        assertEquals(KeyState.None, vault.state.value)
        assertEquals(0, vault.generation())
        assertNull(vault.notice.value)
        assertEquals(VaultText.NO_KEY, failsWith<VaultException> { vault.exportKeyCopy() }.message)
        assertEquals(KeyState.None, phone.vault().state.value)

        vault.setUp()
        assertEquals(KeyState.Ready, vault.state.value)
        assertNotEquals(oldKey, VaultFixtures.identities(vault.exportKeyCopy()).single())
        assertEquals(KeyState.Ready, newPhone().vault().restore())
    }

    @Test
    fun `nothing can be changed or exported without a key`() = runTest {
        val vault = newPhone().vault()
        assertEquals(VaultText.NO_KEY, failsWith<VaultException> { vault.rekey(RekeyReason.OWNER_ASKED) }.message)
        assertEquals(VaultText.NO_KEY, failsWith<VaultException> { vault.exportKeyCopy() }.message)
        assertEquals(VaultText.NO_KEY, failsWith<VaultException> { vault.cipher().encryptBytes(ByteArray(1)) }.message)
    }
}

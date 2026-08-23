package app.cloudsaver.core.logic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureBackupTest {

    private val plain = """{"app":"CloudSaver","items":[1,2,3]}""".toByteArray()
    private val password = "correct horse battery".toCharArray()

    @Test
    fun roundTrip() {
        val blob = SecureBackup.encrypt(plain, password)
        assertArrayEquals(plain, SecureBackup.decrypt(blob, "correct horse battery".toCharArray()))
    }

    @Test
    fun encryptedBlobLooksNothingLikeThePlaintext() {
        val blob = SecureBackup.encrypt(plain, password)
        assertFalse(String(blob, Charsets.ISO_8859_1).contains("CloudSaver\""))
        assertTrue(SecureBackup.isEncrypted(blob))
        assertFalse(SecureBackup.isEncrypted(plain))
    }

    @Test(expected = SecureBackup.WrongPasswordException::class)
    fun wrongPasswordFails() {
        val blob = SecureBackup.encrypt(plain, password)
        SecureBackup.decrypt(blob, "wrong password here".toCharArray())
    }

    @Test(expected = SecureBackup.WrongPasswordException::class)
    fun tamperedFileFails() {
        val blob = SecureBackup.encrypt(plain, password)
        blob[blob.size - 5] = (blob[blob.size - 5] + 1).toByte()
        SecureBackup.decrypt(blob, password)
    }

    @Test(expected = SecureBackup.WrongPasswordException::class)
    fun tamperedHeaderFails() {
        val blob = SecureBackup.encrypt(plain, password)
        // Flip a salt byte: the derived key changes, so the tag no longer matches.
        blob[6] = (blob[6] + 1).toByte()
        SecureBackup.decrypt(blob, password)
    }

    @Test(expected = IllegalArgumentException::class)
    fun plainJsonIsRejectedAsEncrypted() {
        SecureBackup.decrypt(plain, password)
    }

    @Test
    fun saltAndIvAreRandomPerExport() {
        val a = SecureBackup.encrypt(plain, password)
        val b = SecureBackup.encrypt(plain, password)
        assertNotEquals(
            String(a, Charsets.ISO_8859_1),
            String(b, Charsets.ISO_8859_1)
        )
    }

    @Test
    fun emptyPayloadStillRoundTrips() {
        val blob = SecureBackup.encrypt(ByteArray(0), password)
        assertEquals(0, SecureBackup.decrypt(blob, password).size)
    }

    @Test
    fun passwordStrength() {
        assertEquals(SecureBackup.Strength.TOO_SHORT, SecureBackup.strengthOf("abc"))
        assertEquals(SecureBackup.Strength.WEAK, SecureBackup.strengthOf("password"))
        assertEquals(SecureBackup.Strength.FAIR, SecureBackup.strengthOf("Passw0rd123"))
        assertEquals(SecureBackup.Strength.STRONG, SecureBackup.strengthOf("Passw0rd!LongEnough"))
    }
}

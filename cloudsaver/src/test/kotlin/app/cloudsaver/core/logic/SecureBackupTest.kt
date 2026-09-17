package app.cloudsaver.core.logic

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureBackupTest {

    private val plain = """{"app":"CloudSaver","items":[1,2,3]}""".toByteArray()
    private val password = "correct horse battery".toCharArray()

    /**
     * The password for a backup outlives the trip to the file picker.
     *
     * Choosing where to save is another app's screen, and coming back from it
     * can recreate this one. A password held in the composition is null by
     * the time the chosen file arrives, and the backup is then written
     * readable under the name the person was told means encrypted - the one
     * failure this whole file exists to prevent. It waits in the view model,
     * which that trip cannot clear, and never in saved state, which is disk.
     */
    @Test
    fun `the backup password is not held where the file picker can clear it`() {
        val options = File("src/main/kotlin/app/cloudsaver/ui/screens/OptionsScreen.kt").readText()
        assertTrue(
            "the export must read the password from the view model",
            options.contains("vm.exportState(uri, vm.backupPassword")
        )
        assertFalse(
            "and must not keep it in composition",
            options.contains("var exportPassword by remember")
        )
        val vm = File("src/main/kotlin/app/cloudsaver/ui/AppViewModel.kt").readText()
        assertTrue("held by the view model", vm.contains("var backupPassword: String? = null"))
        assertFalse(
            "never in saved state, which is written to disk",
            vm.contains("rememberSaveable") || options.contains("rememberSaveable { mutableStateOf<String?>")
        )
    }

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

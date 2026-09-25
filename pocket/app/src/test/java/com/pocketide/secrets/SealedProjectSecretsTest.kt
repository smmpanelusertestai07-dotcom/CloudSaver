package com.pocketide.secrets

import com.pocketide.core.SecretBox
import com.pocketide.core.SecureStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SealedProjectSecretsTest {
    @get:Rule val temp = TemporaryFolder()

    /** Flips every bit, so a stored file never holds the plain value. */
    private class FlipBox : SecretBox {
        override fun seal(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0xFF).toByte() }
        override fun open(sealed: ByteArray) = seal(sealed)
    }

    private val pushed = mutableListOf<Triple<String, String, String>>()
    private var now = 1_000L

    private fun secrets(dir: java.io.File = temp.root) = SealedProjectSecrets(
        store = SecureStore(dir, FlipBox()),
        clock = { now },
        io = Dispatchers.Unconfined,
        pushSecret = { projectId, name, value -> pushed += Triple(projectId, name, value.toString(Charsets.UTF_8)) },
    )

    @Test
    fun namesFollowEnvironmentAndGitHubRules() {
        assertNull(SecretNames.problem("API_BASE", SecretKind.VARIABLE))
        assertNull(SecretNames.problem("_private2", SecretKind.SECRET))
        assertNotNull(SecretNames.problem("", SecretKind.VARIABLE))
        assertNotNull(SecretNames.problem("2FAST", SecretKind.VARIABLE))
        assertNotNull(SecretNames.problem("MY-KEY", SecretKind.SECRET))
        assertNotNull(SecretNames.problem("with space", SecretKind.SECRET))
        assertNotNull(SecretNames.problem("github_token", SecretKind.SECRET))
        assertNotNull(SecretNames.problem("PATH", SecretKind.VARIABLE))
        assertNotNull(SecretNames.problem("A".repeat(SecretNames.MAX_LENGTH + 1), SecretKind.VARIABLE))
        assertEquals("API_KEY", SecretNames.normalize("  api_key "))
    }

    @Test
    fun badNamesAreRefusedBeforeAnythingIsStored() = runTest {
        val s = secrets()
        try {
            s.set("alice/demo", "GITHUB_TOKEN", SecretKind.SECRET, "x".toCharArray())
            fail("GITHUB_ prefix must be refused")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("GITHUB_"))
        }
        assertTrue(s.values.value.isEmpty())
    }

    @Test
    fun screensSeeNamesButNeverValues() = runTest {
        val s = secrets()
        s.set("alice/demo", "deploy_key", SecretKind.SECRET, "s3cr3t-value-123".toCharArray())
        val shown = s.values.value.single()
        assertEquals("DEPLOY_KEY", shown.name)
        assertEquals(SecretKind.SECRET, shown.kind)
        assertFalse(shown.toString().contains("s3cr3t"))
        assertFalse(temp.root.walk().filter { it.isFile }.any { it.readText(Charsets.ISO_8859_1).contains("s3cr3t") })
        assertArrayEquals("s3cr3t-value-123".toCharArray(), s.reveal("alice/demo", "Deploy_Key"))
    }

    @Test
    fun roomsGetVariablesOnlyWithProjectOverridingGlobal() = runTest {
        val s = secrets()
        s.set(null, "API_BASE", SecretKind.VARIABLE, "https://global".toCharArray())
        s.set(null, "LOG_LEVEL", SecretKind.VARIABLE, "info".toCharArray())
        s.set("alice/demo", "API_BASE", SecretKind.VARIABLE, "https://project".toCharArray())
        s.set("alice/demo", "SIGNING_PASSWORD", SecretKind.SECRET, "never-in-a-room".toCharArray())
        s.set("bob/other", "OTHER", SecretKind.VARIABLE, "elsewhere".toCharArray())

        assertEquals(mapOf("API_BASE" to "https://project", "LOG_LEVEL" to "info"), s.variablesFor("alice/demo"))
    }

    @Test
    fun aVariableCanBeLimitedToOneRoom() = runTest {
        val s = secrets()
        s.set("alice/demo", "KILO_API_KEY", SecretKind.VARIABLE, "kilo-key".toCharArray())
        s.limitToRoom("alice/demo", "KILO_API_KEY", "kilocode.kilo-code")

        assertTrue(s.variablesFor("alice/demo").isEmpty())
        assertTrue(s.variablesFor("alice/demo", "claude").isEmpty())
        assertEquals(mapOf("KILO_API_KEY" to "kilo-key"), s.variablesFor("alice/demo", "kilocode.kilo-code"))
        // Changing the value keeps the limit.
        s.set("alice/demo", "KILO_API_KEY", SecretKind.VARIABLE, "kilo-key-2".toCharArray())
        assertEquals("kilocode.kilo-code", s.values.value.single().agentId)

        s.set("alice/demo", "DEPLOY", SecretKind.SECRET, "d".toCharArray())
        try {
            s.limitToRoom("alice/demo", "DEPLOY", "claude")
            fail("a Secret never reaches a room")
        } catch (expected: SecretsException) {
            assertTrue(expected.message!!.contains("Secrets never reach a room"))
        }
    }

    @Test
    fun checkPostGetsEveryValue() = runTest {
        val s = secrets()
        s.set(null, "A", SecretKind.VARIABLE, "value-a".toCharArray())
        s.set("alice/demo", "B", SecretKind.SECRET, "value-b".toCharArray())
        s.set("bob/other", "C", SecretKind.SECRET, "value-a".toCharArray())
        assertEquals(setOf("value-a", "value-b"), s.allValues().toSet())
        assertEquals(2, s.allValues().size)
    }

    @Test
    fun onlySecretsArePushedAndTheFlagFollowsTheValue() = runTest {
        val s = secrets()
        s.set("alice/demo", "API_BASE", SecretKind.VARIABLE, "https://x".toCharArray())
        s.set("alice/demo", "STORE_PASSWORD", SecretKind.SECRET, "hunter2-long".toCharArray())
        try {
            s.pushToGitHub("alice/demo", "API_BASE")
            fail("Variables stay in the room")
        } catch (expected: SecretsException) {
            assertTrue(pushed.isEmpty())
        }
        s.pushToGitHub("alice/demo", "STORE_PASSWORD")
        assertEquals(listOf(Triple("alice/demo", "STORE_PASSWORD", "hunter2-long")), pushed)
        assertTrue(s.values.value.first { it.name == "STORE_PASSWORD" }.pushedToGitHub)

        s.set("alice/demo", "STORE_PASSWORD", SecretKind.SECRET, "changed-value".toCharArray())
        assertFalse(s.values.value.first { it.name == "STORE_PASSWORD" }.pushedToGitHub)
    }

    @Test
    fun aValueSavedWhileTheOldOneIsSentIsNotMarkedOnGitHub() = runTest {
        lateinit var s: SealedProjectSecrets
        s = SealedProjectSecrets(
            store = SecureStore(temp.root, FlipBox()),
            clock = { now },
            io = Dispatchers.Unconfined,
            pushSecret = { _, name, _ ->
                // The owner saves a new value while the old one is on its way.
                now += 1_000
                s.set("alice/demo", name, SecretKind.SECRET, "new-value".toCharArray())
            },
        )
        s.set("alice/demo", "STORE_PASSWORD", SecretKind.SECRET, "old-value".toCharArray())

        s.pushToGitHub("alice/demo", "STORE_PASSWORD")

        assertEquals("new-value", s.reveal("alice/demo", "STORE_PASSWORD")?.concatToString())
        assertFalse("GitHub holds the old value", s.values.value.single().pushedToGitHub)
    }

    @Test
    fun aGlobalSecretCanBePushedToAProject() = runTest {
        val s = secrets()
        s.set(null, "SHARED_TOKEN", SecretKind.SECRET, "shared".toCharArray())
        s.pushToGitHub("alice/demo", "SHARED_TOKEN")
        assertEquals("alice/demo", pushed.single().first)
    }

    @Test
    fun exportImportRoundTripsToANewPhone() = runTest {
        val first = secrets(temp.newFolder("one"))
        first.set(null, "A", SecretKind.VARIABLE, "1-value".toCharArray())
        first.set("alice/demo", "B", SecretKind.SECRET, "2-value".toCharArray())
        val blob = first.exportBlob()
        assertArrayEquals("export is stable for unchanged data", blob, first.exportBlob())

        val second = secrets(temp.newFolder("two"))
        second.set(null, "STALE", SecretKind.VARIABLE, "old".toCharArray())
        second.importBlob(blob)

        assertEquals(listOf("A", "B"), second.values.value.map { it.name }.sorted())
        assertArrayEquals("2-value".toCharArray(), second.reveal("alice/demo", "B"))
        assertNull(second.reveal(null, "STALE"))
    }

    @Test
    fun aRemovedValueLeavesOnlyItsNameSoTheRemovalReachesOtherPhones() = runTest {
        val s = secrets()
        s.set("alice/demo", "DEPLOY_TOKEN", SecretKind.SECRET, "s3cr3t-value-123".toCharArray())
        now = 2_000L
        s.remove("alice/demo", "deploy_token")

        assertTrue(s.values.value.isEmpty())
        assertNull(s.reveal("alice/demo", "DEPLOY_TOKEN"))
        assertTrue(s.allValues().isEmpty())
        val blob = s.exportBlob().toString(Charsets.UTF_8)
        assertTrue(blob.contains("DEPLOY_TOKEN"))
        assertFalse(blob.contains("s3cr3t"))
    }

    @Test
    fun mergingKeepsEachPhonesLaterChangeValueByValue() = runTest {
        val mine = secrets(temp.newFolder("mine"))
        mine.set(null, "SHARED", SecretKind.VARIABLE, "old".toCharArray())
        mine.set(null, "GONE", SecretKind.VARIABLE, "bye".toCharArray())
        val theirs = secrets(temp.newFolder("theirs"))
        theirs.importBlob(mine.exportBlob())
        now = 2_000L
        mine.set(null, "MINE", SecretKind.VARIABLE, "m".toCharArray())
        theirs.set(null, "SHARED", SecretKind.VARIABLE, "new".toCharArray())
        theirs.remove(null, "GONE")
        theirs.set(null, "THEIRS", SecretKind.SECRET, "t".toCharArray())

        mine.mergeBlob(theirs.exportBlob())

        assertEquals(listOf("MINE", "SHARED", "THEIRS"), mine.values.value.map { it.name }.sorted())
        assertArrayEquals("new".toCharArray(), mine.reveal(null, "SHARED"))
        assertNull(mine.reveal(null, "GONE"))
        assertArrayEquals("m".toCharArray(), mine.reveal(null, "MINE"))
    }

    @Test
    fun anEmptyPhoneDoesNotExportAndReplaceTheVault() = runTest {
        try {
            secrets().exportBlob()
            fail("nothing saved yet must not export an empty set")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    @Test
    fun anUnreadableStoreIsReportedNotTreatedAsEmpty() = runTest {
        val dir = temp.newFolder("broken")
        secrets(dir).set(null, "A", SecretKind.VARIABLE, "x-value".toCharArray())
        val broken = SealedProjectSecrets(
            store = SecureStore(
                dir,
                object : SecretBox {
                    override fun seal(plain: ByteArray) = plain
                    override fun open(sealed: ByteArray): ByteArray = throw javax.crypto.AEADBadTagException()
                },
            ),
            clock = { now },
            io = Dispatchers.Unconfined,
            pushSecret = { _, _, _ -> },
        )
        try {
            broken.exportBlob()
            fail("an unreadable store must not export")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("could not be opened"))
        }
    }

    @Test
    fun importDropsEntriesWithInvalidNames() = runTest {
        val s = secrets()
        val blob = """[{"projectId":null,"name":"ok_name","kind":"VARIABLE","value":"v","updatedAt":1},
            {"projectId":null,"name":"bad-name","kind":"VARIABLE","value":"v","updatedAt":1}]""".toByteArray()
        s.importBlob(blob)
        assertEquals(listOf("OK_NAME"), s.values.value.map { it.name })
    }
}

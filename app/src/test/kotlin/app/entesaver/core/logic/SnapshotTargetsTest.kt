package app.entesaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the daily history file goes. One file, visible, outside the upload
 * folder: a new install can only read it back when the person picks it, and
 * the system file picker does not show hidden folders.
 */
class SnapshotTargetsTest {

    @Test
    fun `the history is one visible file the system picker can open`() {
        val targets = Defaults.SNAPSHOT_TARGETS
        assertTrue("there must be somewhere to write", targets.isNotEmpty())
        for ((dir, name) in targets) {
            assertEquals(Defaults.HISTORY_NAME, name)
            assertFalse(
                "$dir/$name: Android renames a hidden folder an app asks for, and the picker hides it",
                (dir.split('/') + name).any { it.startsWith(".") }
            )
        }
    }

    @Test
    fun `snapshots go where Android actually allows them`() {
        // The bug this guards: MediaStore refuses a non-media file under
        // Pictures - "Primary directory Pictures not allowed ... allowed
        // directories are [Download, Documents]" - so every automatic write
        // failed silently and an uninstall would have lost the history.
        for ((dir, name) in Defaults.SNAPSHOT_TARGETS) {
            val root = dir.substringBefore('/')
            assertTrue(
                "$dir/$name is under $root, which Android rejects for data files",
                root == "Documents" || root == "Download"
            )
        }
    }

    @Test
    fun `Documents first, Download only where a phone refuses Documents`() {
        assertEquals(
            listOf("Documents/Ente Saver", "Download/Ente Saver"),
            Defaults.SNAPSHOT_TARGETS.map { it.first }
        )
    }

    @Test
    fun `the old Pictures locations are still read, never written`() {
        val legacy = Defaults.LEGACY_SNAPSHOT_TARGETS.map { it.first }
        assertTrue(
            "an upgrade must still find its old state",
            legacy.any { it.startsWith("Pictures/") }
        )
        for (dir in legacy) {
            assertFalse(
                "$dir must not be written to again",
                Defaults.SNAPSHOT_TARGETS.any { it.first == dir }
            )
        }
    }

    @Test
    fun `the hidden folders used before 12_0 are read and emptied, never written`() {
        assertEquals(
            listOf("Documents/.cloudsaver", "Download/.cloudsaver", "Documents/_.cloudsaver", "Download/_.cloudsaver"),
            Defaults.PREVIOUS_SNAPSHOT_DIRS
        )
        for (dir in Defaults.PREVIOUS_SNAPSHOT_DIRS) {
            assertFalse("$dir must not be written to again", Defaults.SNAPSHOT_TARGETS.any { it.first == dir })
        }
    }

    /** Ente backs up the upload folder, so the history never goes there. */
    @Test
    fun nothingIsEverWrittenToTheOutputFolder() {
        for ((dir, name) in Defaults.SNAPSHOT_TARGETS) {
            assertFalse("$dir/$name sits in the upload folder", Defaults.isOutputPath(dir))
        }
    }

    /**
     * The folder is kept alive by the anchor rule - never deleting the newest
     * remaining copy - so no placeholder file is ever created.
     */
    @Test
    fun noPlaceholderNamesExist() {
        val names = Defaults.SNAPSHOT_TARGETS.map { it.second } + listOf(
            Defaults.SNAPSHOT_NAME,
            Defaults.SNAPSHOT_NAME_DOTFILE,
            Defaults.SNAPSHOT_NAME_VISIBLE
        )
        for (name in names) {
            assertFalse("no keep/placeholder files", name.contains("keep"))
            assertFalse(name.contains("placeholder"))
            assertFalse(name.contains("dummy"))
            // Snapshots are data, never media the gallery would show.
            assertFalse("$name must not look like media", name.endsWith(".jpg"))
            assertFalse(name.endsWith(".png"))
            assertFalse(name.endsWith(".mp4"))
        }
    }
}

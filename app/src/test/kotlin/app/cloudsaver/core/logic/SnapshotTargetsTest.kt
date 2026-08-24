package app.cloudsaver.core.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app must never leave a file the user can see unless they asked for one.
 * The output folder is theirs and their cloud app mirrors it, so a stray
 * state.json or placeholder there ends up in their photo library.
 */
class SnapshotTargetsTest {

    @Test
    fun everyTargetIsHidden() {
        val targets = Defaults.SNAPSHOT_TARGETS
        assertTrue("there must be somewhere to write", targets.isNotEmpty())
        val visible = targets.filterNot { (dir, name) ->
            Defaults.isHiddenSnapshotTarget(dir, name)
        }
        // There is no visible last resort any more: Documents and Download
        // both accept a hidden folder, so the app never has to leave a file
        // where someone browsing Files would find it.
        assertEquals("no visible target should remain: $visible", 0, visible.size)
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
    fun `both shared copies exist, in different roots`() {
        // One deletion should never be able to take the only copy with it.
        val roots = Defaults.SNAPSHOT_TARGETS.map { it.first.substringBefore('/') }.toSet()
        assertEquals("expected Documents and Download", setOf("Documents", "Download"), roots)
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
    fun hiddenIsDecidedByDotPrefix() {
        assertTrue(Defaults.isHiddenSnapshotTarget("Pictures/CloudSaver/.cloudsaver", "state.json"))
        assertTrue(Defaults.isHiddenSnapshotTarget("Documents/.cloudsaver", "state.json"))
        assertTrue(Defaults.isHiddenSnapshotTarget("Pictures/CloudSaver", ".cloudsaver.json"))
        assertFalse(Defaults.isHiddenSnapshotTarget("Documents/CloudSaver", "backup.json"))
        assertFalse(Defaults.isHiddenSnapshotTarget("Pictures/CloudSaver", "state.json"))
    }

    /**
     * The output folder is the one the cloud app uploads, so nothing may be
     * written there automatically except a hidden snapshot.
     */
    @Test
    fun nothingVisibleIsEverWrittenToTheOutputFolder() {
        for ((dir, name) in Defaults.SNAPSHOT_TARGETS) {
            if (Defaults.isOutputPath(dir)) {
                assertTrue(
                    "$dir/$name sits in the upload folder and must be hidden",
                    Defaults.isHiddenSnapshotTarget(dir, name)
                )
            }
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

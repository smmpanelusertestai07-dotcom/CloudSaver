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
    fun everyPreferredTargetIsHidden() {
        val targets = Defaults.SNAPSHOT_TARGETS
        assertTrue("there must be somewhere to write", targets.isNotEmpty())
        val hidden = targets.filter { (dir, name) -> Defaults.isHiddenSnapshotTarget(dir, name) }
        val visible = targets.filterNot { (dir, name) ->
            Defaults.isHiddenSnapshotTarget(dir, name)
        }
        assertTrue("at least one hidden target", hidden.size >= 2)
        assertEquals("only one visible last resort", 1, visible.size)

        // Every hidden target is tried before the visible one.
        val firstVisible = targets.indexOfFirst { (dir, name) ->
            !Defaults.isHiddenSnapshotTarget(dir, name)
        }
        assertEquals("the visible target must be last", targets.size - 1, firstVisible)
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

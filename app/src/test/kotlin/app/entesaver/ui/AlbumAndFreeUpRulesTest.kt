package app.entesaver.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The album pickers and the way into Free up space, as an owner met them:
 * three albums drawn as two, a long press that put every gallery app and
 * WhatsApp in front of a simple look, an album unticked in setup that came
 * back ticked, and Free up space showing another album's photos for a file
 * it could not offer.
 */
class AlbumAndFreeUpRulesTest {

    private val main = File("src/main/kotlin/app/entesaver")
    private fun src(path: String) = File(main, path).readText()

    @Test
    fun `three albums fit on one row on a phone`() {
        val grid = src("ui/components/AlbumPicker.kt")
        // Adaptive at 100 dp gave two columns in the 270-290 dp a card or a
        // dialog leaves on a 360 dp phone.
        assertFalse(grid.contains("GridCells.Adaptive(minSize = 100.dp)"))
        assertTrue(grid.contains("val columns = (this.maxWidth / 110.dp).toInt().coerceIn(3, 6)"))
        assertTrue(grid.contains("columns = GridCells.Fixed(columns)"))
        assertTrue("square covers, so a row is not half a dialog tall", grid.contains(".aspectRatio(1f)"))
    }

    @Test
    fun `a long press looks inside the album without leaving the app`() {
        val grid = src("ui/components/AlbumPicker.kt")
        val tile = grid.substringAfter("private fun AlbumTile(").substringBefore("\n}\n")
        assertTrue(tile.contains("onLongClick = onPeek"))
        assertFalse("the tile itself starts no other app", tile.contains("peekAlbum("))
        val sheet = grid.substringAfter("private fun AlbumPeekSheet(").substringBefore("\n}\n")
        assertTrue(sheet.contains("ModalBottomSheet("))
        assertTrue("the newest photos", sheet.contains("album.recentUris.chunked(3)"))
        assertTrue("the tick from where the look is", sheet.contains("onToggle(true)") && sheet.contains("onToggle(false)"))
        // The gallery only when asked for, through the phone's own viewer.
        assertTrue(sheet.contains("TextButton(onClick = { peekAlbum(context, cover) })"))
        val scanner = src("media/MediaScanner.kt")
        assertTrue(scanner.contains("recentUris = newest.take(PEEK_SIZE).map { it.uri }"))
    }

    @Test
    fun `an album toggle is read and written in one step`() {
        val repo = src("data/prefs/OptionsRepo.kt")
        val toggle = repo.substringAfter("suspend fun setBucketIncluded(").substringBefore("\n    }\n")
        assertTrue(toggle.contains("val excluded = p[K.EXCLUDED_BUCKETS] ?: emptySet()"))
        for (screen in listOf("ui/screens/OnboardingScreen.kt", "ui/screens/OptionsScreen.kt")) {
            val text = src(screen)
            // Every tile tap goes through the atomic toggle; a whole set
            // worked out from the screen's last frame is what two quick
            // taps used to overwrite each other with.
            assertFalse("$screen still writes the whole set per tap", text.contains("if (include) options.excludedBuckets - name"))
            assertFalse("$screen still writes the whole set per tap", text.contains("if (include) o.excludedBuckets - name"))
            assertTrue(text.contains("vm.setAlbumIncluded(name, include)"))
        }
        // A restore that finishes while setup is under way leaves the
        // person's own ticks alone - checked inside the write itself.
        assertTrue(repo.contains("if (onlyIfSetupUntouched && setupStarted) return@edit"))
        val recovery = src("engine/StartupRecovery.kt")
        assertTrue(recovery.contains("onlyIfSetupUntouched = true"))
        // Nor is the person lifted out of a setup they started meanwhile.
        assertTrue(recovery.contains("if (imported > 0 && untouched && stillUntouched)"))
    }

    @Test
    fun `Settings counts albums on the phone, not entries in a list`() {
        val options = src("ui/screens/OptionsScreen.kt")
        assertTrue(options.contains("R.plurals.folders_included"))
        assertTrue(options.contains("val included = phoneAlbums.count { it !in o.excludedBuckets }"))
    }

    @Test
    fun `a file handed to Free up space is shown, or the reason it cannot be`() {
        val vm = src("ui/ReclaimViewModel.kt")
        val selectOnly = vm.substringAfter("fun selectOnly(id: Long) {").substringBefore("\n    }\n")
        assertTrue("a filter left from the last visit must not hide it", selectOnly.contains("listFilter.value = ListFilters.State()"))
        assertTrue(vm.contains("explainHandOver(judged, now)"))
        val screen = src("ui/screens/ReclaimScreen.kt")
        assertTrue(screen.contains("R.string.freeup_handover_refused"))
        assertTrue("and the day the wait ends", screen.contains("R.string.freeup_handover_ready_on"))
    }

    @Test
    fun `favourites in the gallery are skipped, as the rule always said`() {
        val eligibility = src("engine/ReclaimEligibility.kt")
        assertFalse("a constant false skipped nothing", eligibility.contains("isFavourite = false"))
        assertTrue(eligibility.contains("MediaScanner(ctx, db).favouriteUris()"))
        val scanner = src("media/MediaScanner.kt")
        assertTrue(scanner.contains("\"\${MediaStore.MediaColumns.IS_FAVORITE} = 1\""))
        // A gallery that could not be asked is not a gallery with no
        // favourites: the list fails closed and nothing is offered.
        assertTrue(scanner.contains("fun favouriteUris(): Set<String>? {"))
        assertTrue(eligibility.contains("favourites == null || (row.contentUri != null && row.contentUri in favourites)"))
        // And the reason given for a handed-over file knows about the star.
        assertTrue(src("ui/ReclaimViewModel.kt").contains("favourite = ReclaimEligibility.isFavourite(row, favourites)"))
    }

    @Test
    fun `the hand-over note is precise and outlives a turn of the phone`() {
        val vm = src("ui/ReclaimViewModel.kt")
        val selectOnly = vm.substringAfter("fun selectOnly(id: Long) {").substringBefore("\n    }\n")
        for (reset in listOf("suggestion.value = null", "videosOnly.value = false", "minSizeFilter.value = 0L")) {
            assertTrue("$reset - a leftover narrowing hid the file", selectOnly.contains(reset))
        }
        // A date only when the wait is the last thing in the way, counted in
        // the same calendar days as the rule.
        assertTrue(vm.contains("refuse(candidate.copy(confirmedAgeDays = waited, addedDaysAgo = waited))"))
        assertTrue(vm.contains("Formats.dayAfter("))
        assertFalse("loading again must not drop the note", vm.substringAfter("fun load() {").substringBefore("\n    }\n").contains("handOver.value = null"))
        val screen = src("ui/screens/ReclaimScreen.kt")
        assertTrue(screen.contains("if (hostActivity?.isChangingConfigurations != true) rvm.clearHandOver()"))
    }

    @Test
    fun `filter chips wrap rather than run off the edge`() {
        val list = src("ui/components/ListFramework.kt")
        val row = list.substringAfter("fun ListFilterRow(").substringBefore("open?.let")
        assertTrue(row.contains("FlowRow("))
        assertFalse(row.contains("horizontalScroll"))
    }

    @Test
    fun `storage is shown as Android's own Settings shows it`() {
        val volumes = src("util/Volumes.kt")
        assertTrue(volumes.contains("stats.getTotalBytes(StorageManager.UUID_DEFAULT)"))
        val storage = src("ui/screens/StorageScreen.kt")
        assertTrue(storage.contains("Formats.bytes(vol.shownTotalBytes)"))
        assertFalse("the data partition is not the phone's size", storage.contains("Formats.bytes(vol.totalBytes)"))
    }
}

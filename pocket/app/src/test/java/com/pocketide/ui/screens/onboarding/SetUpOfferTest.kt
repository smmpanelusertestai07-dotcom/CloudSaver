package com.pocketide.ui.screens.onboarding

import com.pocketide.linux.ComputerState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SetUpOfferTest {
    private val installing = ComputerState.Installing("Unpacking Ubuntu…", 0.4f, 1, 2)
    private val broken = ComputerState.Broken("Set-up stopped.", "Try again.")

    @Test
    fun aComputerThatIsMissingOrStoppedIsOfferedAgain() {
        assertTrue(SetUpOffer.shows(ComputerState.NotInstalled))
        assertTrue(SetUpOffer.shows(broken))
        // Once started, the card stays to show its progress.
        assertTrue(SetUpOffer.shows(installing))
        assertFalse(SetUpOffer.shows(ComputerState.Ready))
        assertFalse(SetUpOffer.shows(ComputerState.Updating("Ubuntu's security fixes")))
    }

    @Test
    fun onlyAMissingOrStoppedComputerNeedsTheOwner() {
        assertTrue(SetUpOffer.needsOwner(ComputerState.NotInstalled))
        assertTrue(SetUpOffer.needsOwner(broken))
        assertFalse(SetUpOffer.needsOwner(installing))
        assertFalse(SetUpOffer.needsOwner(ComputerState.Ready))
    }

    @Test
    fun homeAndTheComputerScreenOfferTheSetUp() {
        val screens = File("src/main/java/com/pocketide/ui/screens")
        for (file in listOf("home/HomeScreen.kt", "computer/ComputerScreen.kt")) {
            val source = File(screens, file).readText()
            assertTrue("$file does not offer the set-up", source.contains("SetUpOffer.shows(") && source.contains("SetUpComputerCard("))
        }
    }

    @Test
    fun nothingPromisesASetUpThatHappensByItself() {
        val main = File("src/main/java/com/pocketide")
        val promises = listOf("rebuilt the next time", "set up again on next use", "Home offers it again")
        val found = main.walk().filter { it.extension == "kt" }.flatMap { file ->
            val text = file.readText()
            promises.filter { text.contains(it, ignoreCase = true) }.map { "${file.name}: $it" }
        }.toList()
        assertTrue(found.joinToString(), found.isEmpty())
    }
}

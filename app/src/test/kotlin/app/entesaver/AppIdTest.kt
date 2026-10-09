package app.entesaver

import app.entesaver.core.logic.RunDecider
import app.entesaver.data.EarlierInstall
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's id on Android is app.entesaver. Everything that names the app
 * from outside its code - the long-press shortcuts, the CI harness - has to
 * name that id, or it silently opens nothing. And a phone that still has the
 * earlier app (app.cloudsaver, until 12.2) is told to remove it, since both
 * would work on the same photos.
 */
class AppIdTest {

    private val appId = Regex("""applicationId = "([^"]+)"""")
        .find(File("build.gradle.kts").readText())!!.groupValues[1]

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, ".github/workflows/build.yml").isFile }

    @Test
    fun `the app is Ente Saver's own id, not the earlier app's`() {
        assertEquals("app.entesaver", appId)
        assertTrue(appId != EarlierInstall.PACKAGE)
    }

    @Test
    fun `the long-press shortcuts open this app`() {
        val shortcuts = File("src/main/res/xml/shortcuts.xml").readText()
        val targets = Regex("""android:targetPackage="([^"]+)"""").findAll(shortcuts)
            .map { it.groupValues[1] }.toSet()
        assertEquals(setOf(appId), targets)
    }

    @Test
    fun `the emulator harness tests this app`() {
        val e2e = File(root, ".github/scripts/emulator-e2e.sh").readText()
        assertTrue(e2e.contains("\nPKG=$appId\n"))
        assertTrue(e2e.contains("\nEARLIER_PKG=${EarlierInstall.PACKAGE}\n"))
    }

    @Test
    fun `nothing is made, and nothing offered for removal, while the earlier app is installed`() {
        // Both apps would make a copy of every new photo.
        val gates = File("src/main/kotlin/app/entesaver/work/Gates.kt").readText()
        val gate = gates.substringAfter("fun resourceGate(").substringBefore("\n    }\n")
        assertTrue(gate.contains("if (EarlierInstall.isInstalled(context)) return \"earlier_app\""))
        assertEquals(RunDecider.Wait.EARLIER_APP, RunDecider.waitForResource("earlier_app"))
        // Its copies are its own, still waiting for Ente: never "leftovers".
        val vm = File("src/main/kotlin/app/entesaver/ui/AppViewModel.kt").readText()
        val leftovers = vm.substringAfter("fun detectLeftoverFiles()").substringBefore("\n    fun ")
        assertTrue(leftovers.contains("EarlierInstall.isInstalled(ctx)"))
    }

    @Test
    fun `Home offers to remove the earlier app while it is installed`() {
        val vm = File("src/main/kotlin/app/entesaver/ui/AppViewModel.kt").readText()
        assertTrue(vm.contains("earlierInstall = EarlierInstall.isInstalled(ctx)"))
        val home = File("src/main/kotlin/app/entesaver/ui/screens/HomeScreen.kt").readText()
        val card = home.substringAfter("if (health.earlierInstall) {", "").substringBefore("\n        }\n")
        assertTrue("Home must show the card", card.isNotEmpty())
        assertTrue(card.contains("OemPages.openAppInfoOf(context, EarlierInstall.PACKAGE)"))
    }
}

package app.cloudsaver

import app.cloudsaver.R
import app.cloudsaver.util.AppLooks
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The home screen's side of the app: the name and icon a person picks, the
 * icon pack that dresses Ente up as a gallery, and the pictures both use.
 * Launchers read these by name, from outside the app, so nothing at build
 * time notices when one goes missing.
 */
class IconPackTest {

    private fun xml(path: String): Element =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path)).documentElement

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private val manifest = xml("src/main/AndroidManifest.xml")
    private val aliases = manifest.children("activity-alias")

    private fun Element.isLauncher(): Boolean =
        children("category").any { it.getAttribute("android:name") == "android.intent.category.LAUNCHER" }

    @Test
    fun `every look is a launcher alias of the one real activity`() {
        assertEquals(AppLooks.Look.entries.map { it.alias }.toSet(), aliases.map { it.getAttribute("android:name") }.toSet())
        for (alias in aliases) {
            assertEquals(".HostActivity", alias.getAttribute("android:targetActivity"))
            assertTrue("${alias.getAttribute("android:name")} must be on the home screen", alias.isLauncher())
        }
        val host = manifest.children("activity").single { it.getAttribute("android:name") == ".HostActivity" }
        assertTrue("the real activity has no icon of its own, or there would be two", !host.isLauncher())
    }

    @Test
    fun `a fresh install shows exactly one icon - the default look, under the old name`() {
        val enabled = aliases.filter { it.getAttribute("android:enabled") != "false" }
        assertEquals(listOf(AppLooks.DEFAULT.alias), enabled.map { it.getAttribute("android:name") })
        // The old activity name: home screen icons and shortcuts made before
        // the update keep pointing at something that exists.
        assertEquals(".MainActivity", AppLooks.DEFAULT.alias)
    }

    @Test
    fun `launchers can find the icon pack`() {
        val pack = manifest.children("activity").single { it.getAttribute("android:name") == ".IconPackActivity" }
        assertEquals("true", pack.getAttribute("android:exported"))
        val actions = pack.children("action").map { it.getAttribute("android:name") }
        assertTrue(actions.containsAll(listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME")))
    }

    @Test
    fun `the pack offers Ente exactly one icon, also on Realme and Oppo`() {
        val res = File("src/main/res/xml/appfilter.xml").readText()
        assertEquals("launchers read either copy", res, File("src/main/assets/appfilter.xml").readText())
        val components = xml("src/main/res/xml/appfilter.xml").children("item").map { it.getAttribute("component") }
        // Ente's launcher entry is its enabled alias (IconGreen by default);
        // release builds take the LAUNCHER category off MainActivity.
        assertEquals(listOf("ComponentInfo{io.ente.photos/io.ente.photos.IconGreen}"), components)

        // The ColorOS / Realme UI / OxygenOS 13-15 launcher's per-app editor
        // lists one icon per component key plus one per package, and drops the
        // last one. 18 entries once showed as 20 copies of the same picture.
        val keys = mutableSetOf<String>()
        for (c in components) {
            val inner = c.removePrefix("ComponentInfo{").removeSuffix("}").lowercase()
            keys += inner
            keys += inner.substringBefore('/')
        }
        assertEquals("Realme and Oppo show (keys - 1) icons: exactly one", 2, keys.size)
    }

    @Test
    fun `every picture the pack names exists and survives shrinking`() {
        val named = (
            xml("src/main/res/xml/appfilter.xml").children("item") +
                xml("src/main/res/xml/drawable.xml").children("item")
            ).map { it.getAttribute("drawable") }.toSet()
        assertEquals("one icon: Ente as the phone's Photos", setOf("iconpack_photos"), named)
        for (name in named) {
            assertTrue(name, File("src/main/res/drawable-nodpi/$name.png").isFile)
        }
        assertEquals(
            File("src/main/res/xml/drawable.xml").readText(),
            File("src/main/assets/drawable.xml").readText()
        )
        val keep = File("src/main/res/raw/keep.xml").readText()
        assertTrue(keep.contains("@drawable/iconpack_*"))
        assertTrue(keep.contains("@xml/appfilter"))
        assertTrue(keep.contains("@xml/drawable"))
    }

    @Test
    fun `Ente Saver has one icon, and only its name can change`() {
        assertEquals(listOf(".MainActivity", ".AliasSaver"), AppLooks.Look.entries.map { it.alias })
        assertEquals(listOf(R.string.app_name, R.string.app_name_classic), AppLooks.Look.entries.map { it.nameRes })
        for (alias in aliases) {
            assertEquals("@mipmap/ic_launcher", alias.getAttribute("android:icon"))
        }
        assertTrue(File("src/main/res/mipmap-anydpi/ic_shortcut_photos.xml").isFile)
    }
}

package com.pocketide.docs

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticesTest {

    private val notices: String by lazy {
        // Unit tests run with the module directory as the working directory.
        val file = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .map { File(it, DocsContent.NOTICES_ASSET) }
            .firstOrNull { it.isFile }
        // Lines are wrapped for reading; compare phrases across line breaks.
        checkNotNull(file) { "notices asset not found" }.readText().replace(Regex("\\s+"), " ")
    }

    @Test
    fun `notices name every part and its licence`() {
        val missing = REQUIRED.filterNot { (part, licence) -> notices.contains(part) && notices.contains(licence) }
        assertTrue("missing from the notices: $missing", missing.isEmpty())
    }

    @Test
    fun `GPL source is said to be published with each release`() {
        assertTrue(notices.contains("The GPL source for each release is published beside it"))
    }

    @Test
    fun `notices use only https links and no placeholders`() {
        assertTrue(!notices.contains("http://"))
        assertTrue(listOf("TODO", "FIXME", "lorem").none { notices.contains(it, ignoreCase = true) })
    }

    private companion object {
        val REQUIRED = listOf(
            "PocketIDE" to "Apache License 2.0",
            "PRoot" to "GPL-2.0",
            "talloc" to "LGPL-3.0",
            "libandroid-shmem" to "BSD 3-Clause",
            "Ubuntu" to "Canonical",
            "code-server" to "MIT licence",
            "xterm.js" to "MIT licence",
            "Bouncy Castle" to "MIT X11",
            "JGit" to "EDL-1.0",
            "OkHttp" to "Apache License 2.0",
            "AndroidX" to "Apache License 2.0",
            "kotlinx" to "Apache License 2.0",
            "Google Play services" to "Google APIs Terms of Service",
            "Material icons" to "Apache License 2.0",
            "The agents and their extensions" to "publisher's terms",
        )
    }
}

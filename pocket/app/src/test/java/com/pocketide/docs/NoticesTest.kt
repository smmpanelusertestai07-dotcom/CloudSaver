package com.pocketide.docs

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticesTest {

    private val raw: String by lazy {
        // Unit tests run with the module directory as the working directory.
        val file = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .map { File(it, DocsContent.NOTICES_ASSET) }
            .firstOrNull { it.isFile }
        checkNotNull(file) { "notices asset not found" }.readText()
    }

    /** Lines are wrapped for reading; compare phrases across line breaks. */
    private val notices: String by lazy { raw.replace(Regex("\\s+"), " ") }

    /** PocketIDE's own words, before the licence texts it reproduces exactly. */
    private val ownText: String by lazy { notices.substringBefore(LICENCE_TEXTS) }

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
    fun `licences that must travel with the APK are there in full`() {
        assertTrue(notices.contains(LICENCE_TEXTS))
        val missing = FULL_TEXTS.filterNot { notices.contains(it) }
        assertTrue("licence texts missing: $missing", missing.isEmpty())
    }

    @Test
    fun `the Help page's licence table agrees with the notices`() {
        val section = checkNotNull(DocsContent.section("open-source"))
        val table = section.blocks.filterIsInstance<DocBlock.Table>().single()
        val disagree = table.rows.filterNot { (part, licence) ->
            val name = part.substringBefore(" (").substringBefore(",").substringBefore(" and ")
            notices.contains(name) && notices.contains(licence.substringBefore(" ("), ignoreCase = true)
        }
        assertTrue("rows the notices do not back: $disagree", disagree.isEmpty())
    }

    @Test
    fun `parts that are downloaded are not listed as inside the APK`() {
        val inside = ownText.substringAfter("INSIDE THE APK").substringBefore("DOWNLOADED AT RUN TIME")
        val wrong = DOWNLOADED.filter { inside.contains(it) }
        assertTrue("listed inside the APK but downloaded at run time: $wrong", wrong.isEmpty())
    }

    @Test
    fun `PocketIDE's own text uses only https links and no placeholders`() {
        assertTrue(!ownText.contains("http://"))
        assertTrue(listOf("TODO", "FIXME", "lorem").none { notices.contains(it, ignoreCase = true) })
    }

    private companion object {
        const val LICENCE_TEXTS = "FULL LICENCE TEXTS"

        val REQUIRED = listOf(
            "PocketIDE" to "Apache License 2.0",
            "PRoot" to "GPL-2.0",
            "talloc" to "LGPL-3.0",
            "libandroid-shmem" to "BSD 3-Clause",
            "Ubuntu" to "Canonical",
            "code-server" to "MIT licence",
            "xterm.js" to "MIT licence",
            "Bouncy Castle" to "MIT License",
            "JGit" to "EDL-1.0",
            "OkHttp" to "Apache License 2.0",
            "AndroidX" to "Apache License 2.0",
            "kotlinx" to "Apache License 2.0",
            "Google Play services" to "Google APIs Terms of Service",
            "Material icons" to "Apache License 2.0",
            "The agents and their extensions" to "publisher's terms",
        )

        /** Opening words of each licence text the APK's own parts require to accompany them. */
        val FULL_TEXTS = listOf(
            "Apache License Version 2.0, January 2004",
            "GNU GENERAL PUBLIC LICENSE Version 2, June 1991",
            "GNU LESSER GENERAL PUBLIC LICENSE Version 3, 29 June 2007",
            "GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007",
            "Copyright (c) 2013, Sergii Pylypenko",
            "Eclipse Distribution License - v 1.0",
            "Copyright (c) 2004-2017 QOS.ch",
        )

        val DOWNLOADED = listOf("xterm.js", "code-server", "Chromium", "Playwright")
    }
}

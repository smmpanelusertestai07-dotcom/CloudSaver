package com.pocketide.docs

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocsContentTest {

    @Test
    fun `guide has the planned sections in order`() {
        assertEquals(GUIDE_IDS, DocsContent.guide.map { it.id })
        assertEquals(GUIDE_IDS + LEGAL_IDS, DocsContent.sections.map { it.id })
    }

    @Test
    fun `guide body stays within the word budget`() {
        val perSection = DocsContent.guide.associate { it.id to wordCount(sectionText(it)) }
        val words = perSection.values.sum()
        assertTrue("guide is $words words $perSection; the budget is 2,500 to 3,600", words in 2_500..3_600)
    }

    @Test
    fun `every FAQ question from the plan is answered`() {
        val asked = DocsContent.faq.map { it.question }.toSet()
        val missing = (PLAN_FAQ + LATER_FAQ).filterNot { it in asked }
        assertTrue("missing FAQ questions: $missing", missing.isEmpty())
        val unlisted = asked - (PLAN_FAQ + LATER_FAQ).toSet()
        assertTrue("FAQ questions no list names: $unlisted", unlisted.isEmpty())
    }

    @Test
    fun `Help's own page ids never clash with a section or a question`() {
        val ids = DocsContent.sections.map { it.id } + DocsContent.faq.map { it.id }
        val clashes = ids.filter { it in HELP_PAGE_IDS || it.startsWith(AgentPages.pageId("")) }
        assertTrue("ids Help uses for other pages: $clashes", clashes.isEmpty())
    }

    @Test
    fun `paths through other companies' screens say their labels can move`() {
        for (id in listOf("without-the-app", "privacy", "conditions")) {
            assertTrue(id, sectionText(requireSection(id)).contains("if a label moved, the path is still right"))
        }
    }

    @Test
    fun `what is still being tested on phones says so`() {
        val marked = DocsContent.guide.filter { sectionText(it).contains(BEING_TESTED) }.map { it.id }
        assertTrue("sections marked: $marked", marked.containsAll(listOf("requirements", "security", "conditions")))
    }

    @Test
    fun `security says what a compromised agent can and cannot reach`() {
        val security = requireSection("security")
        val reach = security.blocks.filterIsInstance<DocBlock.Table>().first { it.header.first().contains("reach") }
        val answers = reach.rows.map { it.first() to it.last() }
        assertTrue(answers.any { (what, answer) -> what.startsWith("Its room") && answer.startsWith("Yes") })
        assertTrue(answers.any { (what, answer) -> what.contains("Other rooms") && answer.startsWith("No") })
        assertTrue(answers.any { (what, answer) -> what.contains("GitHub token") && answer.startsWith("No") })
        val text = sectionText(security)
        assertTrue(text.contains("prompt injection", ignoreCase = true))
        assertTrue(text.contains("PRoot is not a sandbox"))
    }

    @Test
    fun `ids are unique and kebab-case`() {
        val ids = DocsContent.sections.map { it.id } + DocsContent.faq.map { it.id }
        val duplicates = ids.groupBy { it }.filter { it.value.size > 1 }.keys
        assertTrue("duplicate ids: $duplicates", duplicates.isEmpty())
        val bad = ids.filterNot { KEBAB.matches(it) }
        assertTrue("ids not in kebab-case: $bad", bad.isEmpty())
    }

    @Test
    fun `every FAQ answer links to an existing section`() {
        val sectionIds = DocsContent.sections.map { it.id }.toSet()
        val broken = DocsContent.faq.filter { it.sectionId == null || it.sectionId !in sectionIds }
        assertTrue("FAQ entries with a missing section: ${broken.map { it.id }}", broken.isEmpty())
    }

    @Test
    fun `FAQ answers are one to four short sentences`() {
        val tooLong = DocsContent.faq.filter { entry ->
            val sentences = entry.answer.flatMap { sentences(blockLines(it).joinToString(" ")) }
            sentences.size !in 1..4
        }
        assertTrue("FAQ answers outside 1 to 4 sentences: ${tooLong.map { it.id }}", tooLong.isEmpty())
    }

    @Test
    fun `sentences stay plain and short`() {
        val long = everyLine().flatMap(::sentences).filter { wordCount(it) > MAX_SENTENCE_WORDS }
        assertTrue("sentences over $MAX_SENTENCE_WORDS words: $long", long.isEmpty())
    }

    @Test
    fun `no placeholder text anywhere`() {
        val found = everyLine().filter { line -> PLACEHOLDERS.any { line.contains(it, ignoreCase = true) } }
        assertTrue("placeholder text: $found", found.isEmpty())
    }

    @Test
    fun `prices, limits and percentages carry an as-of date`() {
        val undated = mutableListOf<String>()
        for (section in DocsContent.sections) {
            section.blocks.forEachIndexed { index, block ->
                if (block is DocBlock.Table) {
                    val frozen = (block.header + block.rows.flatten()).filter(::isFrozenNumber)
                    if (frozen.isNotEmpty() && !hasDatedNeighbour(section.blocks, index)) {
                        undated += "${section.id}: table with $frozen"
                    }
                } else {
                    blockLines(block)
                        .filter { isFrozenNumber(it) && !isDated(it) }
                        .forEach { undated += "${section.id}: $it" }
                }
            }
        }
        val otherLines = DocsContent.faq.flatMap { it.answer.flatMap(::blockLines) } +
            DocsContent.glossary.map { it.meaning } + DocsContent.sections.map { it.summary }
        otherLines.filter { isFrozenNumber(it) && !isDated(it) }.forEach { undated += it }
        assertTrue("numbers that change need \"as of\": $undated", undated.isEmpty())
    }

    @Test
    fun `the frozen-number check recognises prices, limits and percentages`() {
        listOf("$4 a month", "Linux $0.006", "Battery at 20% or less", "2,000 minutes", "200 MB a day", "₹0")
            .forEach { assertTrue(it, isFrozenNumber(it)) }
        listOf("8 GB free", "within 30 days", "at least 14 days old", "Android 10 or newer")
            .forEach { assertFalse(it, isFrozenNumber(it)) }
    }

    @Test
    fun `every link is https`() {
        val links = DocsContent.sections.flatMap { it.blocks }.filterIsInstance<DocBlock.Link>()
        assertTrue(links.isNotEmpty())
        val bad = links.filterNot { it.url.startsWith("https://") && it.label.isNotBlank() }
        assertTrue("links that are not https: $bad", bad.isEmpty())
        val plain = everyLine().filter { it.contains("http://") }
        assertTrue("plain http in text: $plain", plain.isEmpty())
    }

    @Test
    fun `sections that cite GitHub's prices link to GitHub`() {
        val actions = requireSection("github-actions").blocks.filterIsInstance<DocBlock.Link>().map { it.url }
        assertTrue(actions.any { it.startsWith("https://github.com/") })
        assertTrue(actions.any { it.startsWith("https://docs.github.com/") })
    }

    @Test
    fun `glossary has 30 to 50 unique terms`() {
        val terms = DocsContent.glossary.map { it.term.lowercase() }
        assertTrue("glossary has ${terms.size} terms", terms.size in 30..50)
        assertEquals("duplicate glossary terms", terms.size, terms.toSet().size)
        assertTrue(DocsContent.glossary.all { it.meaning.isNotBlank() })
    }

    @Test
    fun `the app's own words are used`() {
        val text = DocsContent.guide.joinToString(" ") { sectionText(it) }
        val missing = APP_WORDS.filterNot { text.contains(it) }
        assertTrue("guide never uses: $missing", missing.isEmpty())
    }

    @Test
    fun `permissions section explains exactly the manifest's permissions`() {
        val declared = manifestPermissions()
        assertTrue("no permissions read from the manifest", declared.size >= MANIFEST_PERMISSIONS.size)
        assertTrue(declared.containsAll(MANIFEST_PERMISSIONS))
        val table = requireSection("permissions").blocks.filterIsInstance<DocBlock.Table>().single()
        val explained = table.rows.map { it.first() }
        assertEquals("explained twice", explained.size, explained.toSet().size)
        assertEquals("permissions and their explanations differ", declared, explained.toSet())
        assertTrue(table.rows.all { it.last().isNotBlank() })
    }

    @Test
    fun `every agent tool the docs name is one the MCP server really offers`() {
        val tools = mcpToolNames()
        assertTrue("no tools read from mcp.py", tools.contains("run_build"))
        val named = everyLine().flatMap { line -> SNAKE_CASE.findAll(line).map { it.value } }.toSet() - NOT_TOOLS
        val unknown = named - tools
        assertTrue("docs name tools agents cannot call: $unknown", unknown.isEmpty())
    }

    @Test
    fun `a file from the phone goes in through the app, not through GitHub`() {
        val menuItem = "Add file to this session"
        assertTrue("the agent menu no longer says \"$menuItem\"", agentMenuSource().contains("Text(\"$menuItem\")"))
        val answer = DocsContent.faq.single { it.id == "file-from-phone" }.answer.flatMap(::blockLines).joinToString(" ")
        assertTrue(answer, answer.contains(menuItem) && answer.contains("share it to PocketIDE"))
        assertFalse(answer, answer.contains("GitHub"))
        assertTrue(sectionText(requireSection("how-it-works")).contains(menuItem))
    }

    @Test
    fun `terms and privacy policy are dated`() {
        for (id in listOf("terms", "privacy-policy")) {
            assertTrue(sectionText(requireSection(id)).contains("24 Sep 2026"))
        }
    }

    @Test
    fun `every section has a title, a summary and content`() {
        for (section in DocsContent.sections) {
            assertTrue(section.id, section.title.isNotBlank() && section.summary.isNotBlank())
            assertTrue(section.id, section.blocks.isNotEmpty())
            section.blocks.filterIsInstance<DocBlock.Table>().forEach { table ->
                assertTrue("${section.id}: ragged table", table.rows.all { it.size == table.header.size })
            }
            section.blocks.filterIsInstance<DocBlock.Note>().forEach {
                assertTrue("${section.id}: tone ${it.tone}", it.tone in setOf(TONE_INFO, TONE_WARN, TONE_TIP))
            }
        }
    }

    @Test
    fun `lookup helpers find sections and their questions`() {
        assertEquals("deleting", DocsContent.section("deleting")?.id)
        assertEquals(null, DocsContent.section("no-such-section"))
        assertTrue(DocsContent.faqFor("deleting").isNotEmpty())
    }

    private fun requireSection(id: String) = checkNotNull(DocsContent.section(id)) { "no section $id" }

    /** The permissions the app's own manifest declares, short names like "INTERNET". */
    private fun manifestPermissions(): Set<String> {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .firstOrNull { it.isFile }
        return USES_PERMISSION.findAll(checkNotNull(manifest) { "manifest not found" }.readText())
            // tools:node="remove" takes a permission a library would merge in back out.
            .filterNot { it.value.contains("tools:node=\"remove\"") }
            .map { it.groupValues[1].substringAfterLast('.') }
            .toSet()
    }

    /** The tool names the rooms' MCP server lists, read from the script agents really run. */
    private fun mcpToolNames(): Set<String> {
        val script = listOf(File("src/main/assets/rooms/mcp.py"), File("app/src/main/assets/rooms/mcp.py"))
            .firstOrNull { it.isFile }
        val tools = checkNotNull(script) { "mcp.py not found" }.readText()
            .substringAfter("\nTOOLS = [").substringBefore("\nTOOL_NAMES")
        return TOOL_NAME.findAll(tools).map { it.groupValues[1] }.toSet()
    }

    /** The agent screen's source, whose menu labels the docs quote. */
    private fun agentMenuSource(): String {
        val path = "src/main/java/com/pocketide/ui/screens/project/ProjectScreens.kt"
        val source = listOf(File(path), File("app/$path")).firstOrNull { it.isFile }
        return checkNotNull(source) { "ProjectScreens.kt not found" }.readText()
    }

    private fun sectionText(section: DocSection) =
        (listOf(section.summary) + section.blocks.flatMap(::blockLines)).joinToString(" ")

    private fun everyLine(): List<String> =
        DocsContent.sections.flatMap { s -> listOf(s.title, s.summary) + s.blocks.flatMap(::blockLines) } +
            DocsContent.faq.flatMap { listOf(it.question) + it.answer.flatMap(::blockLines) } +
            DocsContent.glossary.flatMap { listOf(it.term, it.meaning) }

    private fun hasDatedNeighbour(blocks: List<DocBlock>, index: Int) =
        listOfNotNull(blocks.getOrNull(index - 1), blocks.getOrNull(index + 1))
            .filter { it is DocBlock.Note || it is DocBlock.Paragraph }
            .any { neighbour -> blockLines(neighbour).any(::isDated) }

    private fun isDated(line: String) = line.contains("as of", ignoreCase = true)

    private fun isFrozenNumber(line: String) =
        PRICE.containsMatchIn(line) || PERCENT.containsMatchIn(line) || MINUTES.containsMatchIn(line) ||
            (DIGIT.containsMatchIn(line) && PER_PERIOD.containsMatchIn(line))

    private fun sentences(text: String) = text.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }

    private fun wordCount(text: String) = text.split(WHITESPACE).count { word -> word.any { it.isLetterOrDigit() } }

    private companion object {
        const val MAX_SENTENCE_WORDS = 45

        val KEBAB = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
        val WHITESPACE = Regex("\\s+")
        val SENTENCE_END = Regex("(?<=[.!?])\\s+(?=[A-Z\"(])")
        val DIGIT = Regex("\\d")
        val PRICE = Regex("[\$₹€£]\\s?\\d|\\d\\s?(USD|INR)\\b")
        val PERCENT = Regex("\\d\\s?%")
        val MINUTES = Regex("\\d[\\d,]*\\s*(minutes?|min)\\b", RegexOption.IGNORE_CASE)
        val PER_PERIOD = Regex("(/|\\ba |\\bper |\\beach )(month|day|year)\\b", RegexOption.IGNORE_CASE)
        val PLACEHOLDERS = listOf("TODO", "FIXME", "lorem", "ipsum", "TBD", "XXX")

        val GUIDE_IDS = listOf(
            "what-it-is", "requirements", "how-it-works", "agents", "no-third-party", "your-data",
            "without-the-app", "deleting", "recovery", "the-key", "security", "privacy", "safety",
            "github-actions", "conditions", "limits", "if-something-breaks", "permissions",
        )

        /** Pages Help shows that are not doc sections. */
        val HELP_PAGE_IDS = setOf("faq", "glossary")

        val SNAKE_CASE = Regex("\\b[a-z]+(_[a-z0-9]+)+\\b")
        val TOOL_NAME = Regex(""""name":\s*"([a-z_]+)"""")

        /** Snake-case words the docs quote that are not tools: error codes sign-in pages show. */
        val NOT_TOOLS = setOf("invalid_request", "invalid_client")

        val USES_PERMISSION = Regex("""<uses-permission[^>]*android:name="([^"]+)"[^>]*>""")
        val LEGAL_IDS = listOf("terms", "privacy-policy", "open-source")

        val APP_WORDS = listOf(
            "computer", "room", "session", "Put on main", "check-post", "Recently deleted", "Your data",
            "Official", "Verified publisher", "Variables", "Secrets", "Preview", "Media",
        )

        val MANIFEST_PERMISSIONS = listOf(
            "INTERNET", "ACCESS_NETWORK_STATE", "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_SPECIAL_USE",
            "FOREGROUND_SERVICE_DATA_SYNC", "POST_NOTIFICATIONS", "WAKE_LOCK", "USE_BIOMETRIC",
            "REQUEST_INSTALL_PACKAGES", "RECEIVE_BOOT_COMPLETED",
        )

        /** Plan §14, in its order. "…or to another Google account?" is written out in full. */
        val PLAN_FAQ = listOf(
            "Is my code or chat visible to anyone?",
            "What does each AI company get?",
            "Can one agent read another's chats?",
            "What if I lose my phone?",
            "What if I uninstall?",
            "How do I move to a new phone?",
            "How do I move to another Google account?",
            "How do I delete one chat, or everything?",
            "Where is Recently deleted?",
            "Do I need to remember any code?",
            "What does the extra password change?",
            "Why only these agents?",
            "When will a new official agent appear?",
            "Why no local models?",
            "Can I see my claude.ai or ChatGPT chats here?",
            "Does it work offline?",
            "Why is the app locked?",
            "What happens when Drive is full?",
            "How many builds do I get free?",
            "Public or private repo?",
            "Can it build iOS apps?",
            "Why no Docker on the phone?",
            "Does battery saver stop it?",
            "How much space does it use?",
            "Is it safe to share my Drive or keyring?",
            "When is a deleted chat really gone?",
            "Where can I see or edit my Drive data?",
            "Why only GitHub Actions?",
            "How much mobile data does it use?",
            "Where is the terminal, and do I need it?",
            "Can I see my website while an agent builds it?",
            "If I lose GitHub (or my phone), are my chats safe?",
            "Does the 30-day count restart after a reinstall?",
            "Do the AI companies keep my chats anyway?",
            "Where do I put API keys so the agent can use them safely?",
            "Will an extension on the VS Code Marketplace show up here?",
            "Can agents run tasks on a schedule?",
            "When I reopen a chat, will I see the screenshots and videos again?",
            "Can an agent download a virus onto my phone?",
            "How many free build minutes do I have left?",
            "Why are there agents from other companies in \"More agents\"?",
        )

        /** Questions added after the plan, from the research round on the owner's guides. */
        val LATER_FAQ = listOf(
            "Would a faster phone make the agents faster?",
            "Can this get my account suspended?",
            "Does PocketIDE add files to my repo?",
            "Does it work for a team?",
            "Is this VS Code, and can I add Pylance?",
            "What if the network drops mid-answer?",
            "How do I get a file from my phone into a project?",
        )
    }
}

/** The plain text lines of a block, as a reader would select them. */
internal fun blockLines(block: DocBlock): List<String> = when (block) {
    is DocBlock.Paragraph -> listOf(block.text)
    is DocBlock.Bullets -> block.items
    is DocBlock.Steps -> block.items
    is DocBlock.Table -> block.header + block.rows.flatten()
    is DocBlock.Note -> listOf(block.text)
    is DocBlock.Link -> listOf(block.label)
}

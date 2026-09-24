package com.pocketide.ui.screens.help

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pocketide.docs.DocSection
import com.pocketide.docs.DocsContent
import com.pocketide.docs.FaqEntry
import com.pocketide.docs.GlossaryEntry
import com.pocketide.ui.components.SectionCard
import com.pocketide.ui.components.SelectableText
import com.pocketide.ui.manage.EmptyNote
import com.pocketide.ui.manage.HelpHit
import com.pocketide.ui.manage.HelpSearch
import com.pocketide.ui.manage.Hint
import com.pocketide.ui.manage.ManagePage
import com.pocketide.ui.manage.NavRow
import com.pocketide.ui.manage.SectionLabel
import com.pocketide.ui.manage.rememberGraph
import com.pocketide.ui.nav.PocketNav

/** Ids of the Help pages that are not doc sections. */
object HelpPages {
    const val FAQ = "faq"
    const val GLOSSARY = "glossary"
}

/**
 * The in-app docs: a searchable index, one page per section (every text natively selectable),
 * the questions people ask, the glossary, and a page per installed agent made from its details.
 */
@Composable
fun HelpScreen(sectionId: String?, nav: PocketNav) {
    val graph = rememberGraph()
    val agents by graph.agents.installed.collectAsStateWithLifecycle()
    val agentPages = remember(agents) { agents.map { DocsContent.agentPage(it) } }
    val id = sectionId?.trim()?.takeIf { it.isNotEmpty() }
    when {
        id == null -> HelpIndex(agentPages, nav)
        id == HelpPages.FAQ -> FaqPage(openId = null, nav = nav)
        id == HelpPages.GLOSSARY -> GlossaryPage(nav)
        DocsContent.faq.any { it.id == id } -> FaqPage(openId = id, nav = nav)
        else -> {
            val section = DocsContent.sections.firstOrNull { it.id == id } ?: agentPages.firstOrNull { it.id == id }
            if (section != null) SectionPage(section, nav) else MissingPage(nav)
        }
    }
}

@Composable
private fun HelpIndex(agentPages: List<DocSection>, nav: PocketNav) {
    var query by rememberSaveable { mutableStateOf("") }
    val sections = DocsContent.sections
    val hits = remember(query, agentPages) {
        HelpSearch.search(query, sections + agentPages, DocsContent.faq, DocsContent.glossary)
    }
    ManagePage("Help", nav) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search help") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
        }
        if (query.isNotBlank()) searchResults(hits, nav) else contents(sections, agentPages, nav)
    }
}

private fun LazyListScope.searchResults(hits: List<HelpHit>, nav: PocketNav) {
    if (hits.isEmpty()) {
        item { EmptyNote(Icons.Outlined.SearchOff, "Nothing found", "Try another word, or fewer words.") }
        return
    }
    items(hits, key = { hitKey(it) }) { hit ->
        val (title, kind, target) = when (hit) {
            is HelpHit.Section -> Triple(hit.section.title, "Page", hit.section.id)
            is HelpHit.Faq -> Triple(hit.entry.question, "Question", hit.entry.id)
            is HelpHit.Term -> Triple(hit.entry.term, "Glossary", HelpPages.GLOSSARY)
        }
        HelpCard(title = title, overline = kind, body = hit.snippet) { nav.help(target) }
    }
}

private fun hitKey(hit: HelpHit): String = when (hit) {
    is HelpHit.Section -> "s:" + hit.section.id
    is HelpHit.Faq -> "f:" + hit.entry.id
    is HelpHit.Term -> "t:" + hit.entry.term
}

private fun LazyListScope.contents(sections: List<DocSection>, agentPages: List<DocSection>, nav: PocketNav) {
    if (sections.isEmpty()) {
        item {
            EmptyNote(
                Icons.AutoMirrored.Outlined.MenuBook,
                "No help pages in this version",
                "The agent pages below come from each agent's own details.",
            )
        }
    } else {
        item { SectionLabel("Guide") }
        items(sections, key = { "s:" + it.id }) { section ->
            HelpCard(title = section.title, overline = null, body = section.summary.ifBlank { null }) { nav.help(section.id) }
        }
    }
    if (agentPages.isNotEmpty()) {
        item { SectionLabel("Agents") }
        items(agentPages, key = { "a:" + it.id }) { page ->
            HelpCard(title = page.title, overline = null, body = page.summary.ifBlank { null }, icon = true) { nav.help(page.id) }
        }
    }
    item { SectionLabel("More") }
    item {
        SectionCard(null) {
            NavRow(Icons.Outlined.QuestionAnswer, "Questions and answers", countOrNone(DocsContent.faq.size, "question")) {
                nav.help(HelpPages.FAQ)
            }
            NavRow(Icons.Outlined.Translate, "Glossary", countOrNone(DocsContent.glossary.size, "word")) {
                nav.help(HelpPages.GLOSSARY)
            }
        }
    }
    item {
        Hint(
            "Press and hold any text in Help to copy, share or translate it.",
            Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
}

private fun countOrNone(n: Int, word: String) = if (n == 0) "None in this version" else "$n ${if (n == 1) word else word + "s"}"

@Composable
private fun HelpCard(title: String, overline: String?, body: String?, icon: Boolean = false, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            if (icon) {
                Icon(Icons.Outlined.SmartToy, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (overline != null) {
                    Text(overline, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (body != null) Hint(body)
            }
        }
    }
}

@Composable
private fun SectionPage(section: DocSection, nav: PocketNav) {
    val related = remember(section.id) { DocsContent.faq.filter { it.sectionId == section.id } }
    ManagePage(section.title, nav) {
        item {
            Text(
                section.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp).semantics { heading() },
            )
        }
        if (section.summary.isNotBlank()) {
            item { SelectableText(section.summary, Modifier.fillMaxWidth(), sizeSp = 16f) }
        }
        if (section.blocks.isEmpty() && section.summary.isBlank()) {
            item { EmptyNote(Icons.AutoMirrored.Outlined.MenuBook, "Nothing here yet", "This page has no text in this version.") }
        }
        items(section.blocks.size) { index -> DocBlockView(section.blocks[index], nav) }
        if (related.isNotEmpty()) {
            item { SectionLabel("Questions") }
            items(related, key = { it.id }) { entry ->
                NavRow(Icons.AutoMirrored.Outlined.HelpOutline, entry.question, null) { nav.help(entry.id) }
            }
        }
    }
}

@Composable
private fun FaqPage(openId: String?, nav: PocketNav) {
    val faq = DocsContent.faq
    var open by rememberSaveable(openId) { mutableStateOf(listOfNotNull(openId)) }
    ManagePage("Questions and answers", nav) {
        if (faq.isEmpty()) {
            item { EmptyNote(Icons.Outlined.QuestionAnswer, "No questions yet", "Questions and answers are not in this version.") }
        }
        items(faq, key = { it.id }) { entry ->
            val expanded = entry.id in open
            FaqItem(entry, expanded, nav) { open = if (expanded) open - entry.id else open + entry.id }
        }
    }
}

@Composable
private fun FaqItem(entry: FaqEntry, expanded: Boolean, nav: PocketNav, onToggle: () -> Unit) {
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "faqArrow")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(entry.question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "Hide answer" else "Show answer",
                modifier = Modifier.rotate(turn),
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                entry.answer.forEach { DocBlockView(it, nav) }
                val sectionId = entry.sectionId
                val section = sectionId?.let { id -> DocsContent.sections.firstOrNull { it.id == id } }
                if (section != null) {
                    NavRow(Icons.AutoMirrored.Outlined.MenuBook, "More in \"${section.title}\"", null) { nav.help(section.id) }
                }
            }
        }
    }
}

@Composable
private fun GlossaryPage(nav: PocketNav) {
    val terms = remember { DocsContent.glossary.sortedBy { it.term.lowercase() } }
    ManagePage("Glossary", nav) {
        if (terms.isEmpty()) {
            item { EmptyNote(Icons.Outlined.Translate, "No glossary yet", "The glossary is not in this version.") }
        }
        items(terms, key = { it.term }) { GlossaryItem(it) }
    }
}

@Composable
private fun GlossaryItem(entry: GlossaryEntry) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(entry.term, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        SelectableText(entry.meaning, Modifier.fillMaxWidth())
    }
}

@Composable
private fun MissingPage(nav: PocketNav) {
    ManagePage("Help", nav) {
        item { EmptyNote(Icons.Outlined.SearchOff, "Page not found", "This help page is not in this version of the app.") }
        item { NavRow(Icons.AutoMirrored.Outlined.MenuBook, "All help pages", null) { nav.help(null) } }
    }
}

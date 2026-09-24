package com.pocketide.ui.screens.help

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BulletSpan
import android.text.style.StyleSpan
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.pocketide.docs.DocBlock
import com.pocketide.ui.components.SelectableText
import com.pocketide.ui.components.Tone
import com.pocketide.ui.components.toneColor
import com.pocketide.ui.manage.LinkRow
import com.pocketide.ui.nav.PocketNav

/** Renders one doc block. All text uses the platform's own selection (Copy, Select all, Share, Translate). */
@Composable
fun DocBlockView(block: DocBlock, nav: PocketNav) {
    when (block) {
        is DocBlock.Paragraph -> SelectableText(block.text, Modifier.fillMaxWidth())
        is DocBlock.Bullets -> SelectableText(bullets(block.items), Modifier.fillMaxWidth())
        is DocBlock.Steps -> SelectableText(steps(block.items), Modifier.fillMaxWidth())
        is DocBlock.Table -> StackedTable(block)
        is DocBlock.Note -> NoteBlock(block)
        is DocBlock.Link -> LinkRow(block.label, block.url, nav)
    }
}

@Composable
private fun bullets(items: List<String>): CharSequence {
    val density = LocalDensity.current
    val gap = with(density) { 10.dp.roundToPx() }
    val radius = with(density) { 2.5.dp.roundToPx() }
    val color = MaterialTheme.colorScheme.primary.toArgb()
    val out = SpannableStringBuilder()
    items.forEachIndexed { index, item ->
        val start = out.length
        out.append(item)
        if (index < items.lastIndex) out.append('\n')
        out.setSpan(BulletSpan(gap, color, radius), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return out
}

private fun steps(items: List<String>): CharSequence {
    val out = SpannableStringBuilder()
    items.forEachIndexed { index, item ->
        val start = out.length
        out.append("${index + 1}.  ")
        out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        out.append(item)
        if (index < items.lastIndex) out.append("\n\n")
    }
    return out
}

/**
 * A table becomes one card per row on a phone: the first cell as the card's title and every
 * other cell under its column name. Real columns do not fit a narrow screen at large text sizes.
 */
@Composable
private fun StackedTable(table: DocBlock.Table) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in table.rows) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                SelectableText(tableRow(table.header, row), Modifier.fillMaxWidth().padding(14.dp))
            }
        }
    }
}

internal fun tableRow(header: List<String>, row: List<String>): CharSequence {
    val out = SpannableStringBuilder()
    val title = row.firstOrNull().orEmpty()
    if (title.isNotBlank()) {
        out.append(title)
        out.setSpan(StyleSpan(Typeface.BOLD), 0, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    for (i in 1 until row.size) {
        val cell = row[i]
        if (cell.isBlank()) continue
        if (out.isNotEmpty()) out.append('\n')
        val label = header.getOrNull(i)?.takeIf { it.isNotBlank() }
        if (label != null) {
            val start = out.length
            out.append(label).append(": ")
            out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        out.append(cell)
    }
    return out
}

@Composable
private fun NoteBlock(note: DocBlock.Note) {
    val (tone, icon) = when (note.tone.lowercase()) {
        "warn" -> Tone.WARN to Icons.Outlined.WarningAmber
        "tip" -> Tone.OK to Icons.Outlined.Lightbulb
        else -> Tone.NEUTRAL to Icons.Outlined.Info
    }
    val color = if (tone == Tone.NEUTRAL) MaterialTheme.colorScheme.primary else toneColor(tone)
    Surface(color = color.copy(alpha = 0.10f), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            SelectableText(note.text, Modifier.weight(1f))
        }
    }
}

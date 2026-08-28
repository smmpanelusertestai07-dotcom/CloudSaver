package app.cloudsaver.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.BuildConfig
import app.cloudsaver.R
import app.cloudsaver.core.logic.Platform
import app.cloudsaver.ui.goTo
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.Routes
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.BrandMark
import app.cloudsaver.ui.components.KeyValueRow
import app.cloudsaver.util.AppLog
import app.cloudsaver.util.Formats
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Movie
import app.cloudsaver.core.logic.Preset
import app.cloudsaver.core.logic.QualityKept
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Tune
import app.cloudsaver.ui.components.SegmentedChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
private fun HelpPage(
    nav: NavHostController,
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back)
                )
            }
            // A share of the row rather than whatever the title asks for.
            // Beside the back arrow at the largest accessibility font a title
            // like "Quality explained" is wider than a 320 dp phone, and with
            // nothing holding it the end of it was simply drawn past the edge
            // of the screen. A weight lets it wrap onto a second line instead.
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun HelpScreen(vm: AppViewModel, nav: NavHostController) {
    HelpPage(nav, stringResource(R.string.nav_help)) {
        HelpLink(stringResource(R.string.help_faq)) { nav.goTo(Routes.HELP_FAQ) }
        HelpLink(stringResource(R.string.help_deleted)) { nav.goTo(Routes.HELP_DELETED) }
        // The page this opens is titled "Quality explained", and Settings calls
        // it that too. Only this link ever said "Quality & Technology", so
        // tapping it landed you somewhere apparently else.
        HelpLink(stringResource(R.string.quality_explained_title)) {
            nav.goTo(Routes.HELP_QUALITY)
        }
        HelpLink(stringResource(R.string.help_logs)) { nav.goTo(Routes.HELP_LOGS) }
        HelpLink(stringResource(R.string.help_privacy)) { nav.goTo(Routes.HELP_PRIVACY) }
        HelpLink(stringResource(R.string.help_licenses)) { nav.goTo(Routes.HELP_LICENSES) }
        HelpLink(stringResource(R.string.help_about)) { nav.goTo(Routes.HELP_ABOUT) }
        Text(
            stringResource(R.string.about_version_line, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun HelpLink(label: String, onClick: () -> Unit) {
    AppCard(modifier = Modifier.padding(vertical = 4.dp), onClick = onClick) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The label takes the room that is left and wraps; without a
            // weight a long one - or any one at 200% text - pushed the chevron
            // off the end of the card and out of the screen.
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

private val FAQ = listOf(
    R.string.faq_q1 to R.string.faq_a1,
    R.string.faq_q2 to R.string.faq_a2,
    R.string.faq_q3 to R.string.faq_a3,
    R.string.faq_q4 to R.string.faq_a4,
    R.string.faq_q5 to R.string.faq_a5,
    R.string.faq_q6 to R.string.faq_a6,
    R.string.faq_q7 to R.string.faq_a7,
    R.string.faq_q8 to R.string.faq_a8,
    R.string.faq_q9 to R.string.faq_a9,
    R.string.faq_q10 to R.string.faq_a10,
    R.string.faq_q11 to R.string.faq_a11,
    R.string.faq_q12 to R.string.faq_a12,
    R.string.faq_q13 to R.string.faq_a13,
    R.string.faq_q14 to R.string.faq_a14
)

/**
 * Every place a file can disappear from, and what happens next (DD5).
 *
 * The FAQ answers these one at a time; this page is the whole map on one
 * screen, for the moment someone is actually worried. Six conditions, one
 * card each - a real table would fight large fonts and narrow phones.
 */
@Composable
fun HelpDeletedScreen(nav: NavHostController) {
    HelpPage(nav, stringResource(R.string.help_deleted)) {
        Text(
            stringResource(R.string.help_deleted_intro),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        val rows = listOf(
            R.string.deleted_r1_t to R.string.deleted_r1_b,
            R.string.deleted_r2_t to R.string.deleted_r2_b,
            R.string.deleted_r3_t to R.string.deleted_r3_b,
            R.string.deleted_r4_t to R.string.deleted_r4_b,
            R.string.deleted_r5_t to R.string.deleted_r5_b,
            R.string.deleted_r6_t to R.string.deleted_r6_b
        )
        for ((title, body) in rows) {
            AppCard(modifier = Modifier.padding(vertical = 4.dp)) {
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
fun HelpFaqScreen(nav: NavHostController) {
    // Saveable, not just remembered: turning the phone while an answer is
    // open rebuilds the screen, and the answer someone was halfway
    // through reading closed itself every time.
    var open by rememberSaveable { mutableIntStateOf(-1) }
    HelpPage(nav, stringResource(R.string.help_faq)) {
        FAQ.forEachIndexed { index, (q, a) ->
            val expanded = open == index
            val arrow by animateFloatAsState(
                targetValue = if (expanded) 0f else -90f,
                label = "faqArrow"
            )
            AppCard(
                modifier = Modifier.padding(vertical = 4.dp),
                onClick = { open = if (expanded) -1 else index }
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(q),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .rotate(arrow)
                    )
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Text(
                        stringResource(a),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
        // The worry behind half these questions, gathered on one page.
        HelpLink(stringResource(R.string.faq_deleted_link)) {
            nav.goTo(Routes.HELP_DELETED)
        }
    }
}

/**
 * What optimising does, in five short blocks and no jargon.
 *
 * The glossary is gone with the words it existed to explain: a page that has
 * to define "bits per pixel" before it can make its point was making the
 * wrong point. What is left is what happens, how small, how it looks, what
 * this phone actually measured, and the one real choice.
 */
@Composable
fun HelpQualityScreen(nav: NavHostController, vm: AppViewModel) {
    val measured by vm.measuredQuality.collectAsStateWithLifecycle()
    val kept by vm.detailKept.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshMeasuredQuality() }

    val options by vm.options.collectAsStateWithLifecycle()
    val preset = options.preset

    HelpPage(nav, stringResource(R.string.quality_explained_title)) {
        // Which setting is on right now, what it means in numbers, and the
        // other two - all three visible, so choosing does not mean guessing
        // what the names stand for.
        AppCard(modifier = Modifier.padding(vertical = 4.dp), tonal = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                // The heading takes the room left beside the icon and
                // wraps; unheld it ran past the card at a large text size.
                Text(
                    stringResource(R.string.quality_current_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
            SegmentedChoice(
                listOf(
                    Preset.STORAGE_SAVER.name to stringResource(R.string.preset_storage),
                    Preset.BALANCED.name to stringResource(R.string.preset_balanced),
                    Preset.MAX_SAVER.name to stringResource(R.string.preset_max)
                ),
                preset.name
            ) { vm.setPreset(Preset.valueOf(it)) }
            Text(
                stringResource(
                    R.string.quality_current_limits,
                    QualityKept.photoCapMp(preset),
                    QualityKept.videoCapLongSide(preset),
                    QualityKept.jpegQuality(preset)
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                stringResource(
                    R.string.quality_current_headroom,
                    // Every number the app prints goes through Formats, and
                    // this one did not. It was rounded by a hand-written
                    // String.format pinned to American formatting, so on a
                    // phone set to a language that writes numbers differently
                    // this single figure disagreed with every other number on
                    // the screen. Formats rounds it the same way and writes it
                    // the way the phone writes numbers.
                    Formats.count(QualityKept.screenHeadroom(preset).roundToInt())
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            // CC3.2: how it looks, per preset - deliberately separate from the
            // pixel table below, and said to be, because "kept 33% of the
            // pixels" and "looks 97% the same" are both true at once and the
            // difference is the entire subject of this screen.
            Text(
                stringResource(
                    when (preset) {
                        Preset.STORAGE_SAVER -> R.string.quality_looks_storage
                        Preset.BALANCED -> R.string.quality_looks_balanced
                        Preset.MAX_SAVER -> R.string.quality_looks_max
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                stringResource(R.string.quality_looks_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                stringResource(R.string.applies_to_new_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        QualityBlock(
            Icons.Outlined.AutoAwesome,
            stringResource(R.string.quality_what_title),
            stringResource(R.string.quality_what)
        )

        // Detail kept, per common size, on the preset that is actually on.
        AppCard(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Straighten,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                // The heading takes the room left beside the icon and
                // wraps; unheld it ran past the card at a large text size.
                Text(
                    stringResource(R.string.quality_detail_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                stringResource(R.string.quality_detail_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
            )
            for (mp in listOf(12.0, 24.0, 48.0)) {
                KeyValueRow(
                    stringResource(R.string.quality_detail_photo, mp.toInt()),
                    stringResource(
                        R.string.quality_detail_kept,
                        QualityKept.photoDetailKeptPercent(mp, preset)
                    )
                )
            }
            for ((label, side) in listOf("1080p" to 1920, "4K" to 3840)) {
                KeyValueRow(
                    label,
                    stringResource(
                        R.string.quality_detail_kept,
                        QualityKept.videoDetailKeptPercent(side, preset)
                    )
                )
            }
        }

        AppCard(modifier = Modifier.padding(vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Compress,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                // The heading takes the room left beside the icon and
                // wraps; unheld it ran past the card at a large text size.
                Text(
                    stringResource(R.string.quality_table_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
            KeyValueRow(
                stringResource(R.string.quality_row1_k),
                stringResource(R.string.quality_row1_v)
            )
            KeyValueRow(
                stringResource(R.string.quality_row2_k),
                stringResource(R.string.quality_row2_v)
            )
            KeyValueRow(
                stringResource(R.string.quality_row3_k),
                stringResource(R.string.quality_row3_v)
            )
            KeyValueRow(
                stringResource(R.string.quality_row4_k),
                stringResource(R.string.quality_row4_v)
            )
        }

        QualityBlock(
            Icons.Outlined.Visibility,
            stringResource(R.string.quality_look_title),
            stringResource(R.string.quality_look)
        )

        // This phone's own numbers: the only figures on the page that are a
        // measurement rather than a guide.
        AppCard(modifier = Modifier.padding(vertical = 4.dp), tonal = measured.hasAny) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Insights,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                // The heading takes the room left beside the icon and
                // wraps; unheld it ran past the card at a large text size.
                Text(
                    stringResource(R.string.quality_measured_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
            if (!measured.hasAny) {
                Text(
                    stringResource(R.string.quality_measured_none),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                if (measured.photoCount > 0) {
                    Text(
                        stringResource(
                            R.string.quality_measured_photos,
                            "${measured.photoShrinkPercent}%",
                            Formats.count(measured.photoCount),
                            QualityKept.photoCapMp(preset)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                if (measured.videoCount > 0) {
                    Text(
                        stringResource(
                            R.string.quality_measured_videos,
                            "${measured.videoShrinkPercent}%",
                            Formats.count(measured.videoCount),
                            QualityKept.videoCapLongSide(preset)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            // The one figure that answers "how much quality did I lose" with a
            // measurement instead of a policy: the pixels the encoder really
            // kept, averaged over the files it really encoded.
            kept?.let { k ->
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(
                    stringResource(R.string.quality_kept_overall, k.percent),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    pluralStringResource(R.plurals.quality_kept_overall_sub, k.files, k.files),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        QualityBlock(
            Icons.Outlined.Movie,
            stringResource(R.string.quality_codec_title),
            stringResource(R.string.quality_codec_text)
        )

        Text(
            stringResource(R.string.quality_originals_safe),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    }
}

/** One titled block of the quality page. */
@Composable
private fun QualityBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    AppCard(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            // The heading gets the rest of the row, so it wraps under itself
            // rather than running past the card at a large text size.
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HelpLogsScreen(nav: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // The tail of the log, already cut into blocks of lines.
    //
    // It used to be one string of up to forty thousand characters handed to a
    // single Text, and one Text is one paragraph layout over the whole forty
    // kilobytes - measured again every time the screen is measured, which is
    // opening the page, turning the phone and every change of font size. On
    // the old phones this app is written for that is a visible stall with
    // nothing on screen yet. A log is lines, so it is split into blocks of a
    // hundred lines and each block is laid out on its own. Nothing is left
    // out and nothing is shortened: the blocks are the same characters in the
    // same order, drawn one under the other, so the page reads exactly as it
    // did before.
    var blocks by remember { mutableStateOf(emptyList<String>()) }
    val shareTitle = stringResource(R.string.logs_share)
    // Reading the file and splitting it are both work, and LaunchedEffect runs
    // on the main thread, so both happen on the IO dispatcher: on a slow phone
    // this was blocking the frame that was meant to draw the screen.
    LaunchedEffect(Unit) {
        blocks = withContext(Dispatchers.IO) {
            val tail = AppLog.readTail(context)
            if (tail.isEmpty()) {
                emptyList<String>()
            } else {
                tail.lineSequence().chunked(100).map { it.joinToString("\n") }.toList()
            }
        }
    }
    HelpPage(nav, stringResource(R.string.help_logs)) {
        // Two buttons side by side is two buttons wide, and at the largest
        // accessibility font on a 320 dp phone the second one left the screen.
        // Wrapping puts it on the next line instead, at every width and font
        // size, and keeps the order.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = {
                try {
                    val file = AppLog.file(context)
                    if (file.exists()) {
                        val uri = FileProvider.getUriForFile(
                            context, "app.cloudsaver.fileprovider", file
                        )
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, shareTitle))
                    }
                } catch (e: Exception) {
                    // sharing is optional
                }
            }) {
                Text(shareTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(onClick = {
                // Deleting the two log files is disk work, and it was being
                // done on the main thread the instant the button was pressed -
                // a dropped frame at best, and on a phone with a slow or busy
                // filesystem a button that visibly sticks under the finger.
                // What is on screen clears straight away; the files go on the
                // IO dispatcher.
                blocks = emptyList<String>()
                scope.launch { withContext(Dispatchers.IO) { AppLog.clear(context) } }
            }) {
                Text(
                    stringResource(R.string.logs_clear),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        AppCard(modifier = Modifier.padding(vertical = 8.dp)) {
            if (blocks.isEmpty()) {
                Text(
                    stringResource(R.string.logs_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                for (block in blocks) {
                    Text(
                        block,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun HelpPrivacyScreen(nav: NavHostController) {
    HelpPage(nav, stringResource(R.string.help_privacy)) {
        // Six short blocks with icons rather than one paragraph. The old wall
        // of text said all of this and nobody read any of it.
        PrivacyBlock(
            Icons.Outlined.WifiOff,
            stringResource(R.string.privacy_b1_title),
            stringResource(R.string.privacy_b1)
        )
        PrivacyBlock(
            Icons.Outlined.Visibility,
            stringResource(R.string.privacy_b2_title),
            stringResource(R.string.privacy_b2)
        )
        PrivacyBlock(
            Icons.Outlined.Storage,
            stringResource(R.string.privacy_b3_title),
            stringResource(R.string.privacy_b3)
        )
        PrivacyBlock(
            Icons.Outlined.Shield,
            stringResource(R.string.privacy_b4_title),
            stringResource(R.string.privacy_b4)
        )
        PrivacyBlock(
            Icons.Outlined.Info,
            stringResource(R.string.privacy_b5_title),
            stringResource(R.string.privacy_b5)
        )
        PrivacyBlock(
            Icons.Outlined.Gavel,
            stringResource(R.string.privacy_b6_title),
            stringResource(R.string.privacy_b6)
        )
    }
}

/** One titled block of the privacy page: icon, heading, three sentences. */
@Composable
private fun PrivacyBlock(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    body: String
) {
    AppCard(modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            // The heading gets the rest of the row, so it wraps under itself
            // rather than running past the card at a large text size.
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
fun HelpLicensesScreen(nav: NavHostController) {
    HelpPage(nav, stringResource(R.string.help_licenses)) {
        AppCard {
            Text(
                stringResource(R.string.licenses_intro),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(R.string.licenses_text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
fun HelpAboutScreen(vm: AppViewModel, nav: NavHostController) {
    val options by vm.options.collectAsStateWithLifecycle()
    val preset = options.preset
    HelpPage(nav, stringResource(R.string.help_about)) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(size = 52.dp)
                // The mark keeps its size; the words beside it take what is
                // left. Without a weight the version line was measured at the
                // width it wanted and, at a large text size on a narrow phone,
                // that width was past the edge of the card.
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        stringResource(R.string.about_version_line, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 14.dp)
            )
            Text(
                stringResource(R.string.about_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                stringResource(R.string.about_partner),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        // What this phone gets, and nothing about any other phone. Version
        // ranges and "2019 onwards" made a reader work out whether the
        // sentence applied to them; this one already knows.
        AppCard(modifier = Modifier.padding(top = 10.dp)) {
            Text(
                stringResource(R.string.about_requires_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            val release = Platform.releaseName(android.os.Build.VERSION.SDK_INT)
            val full = Platform.supportFor(android.os.Build.VERSION.SDK_INT) ==
                Platform.Support.FULL
            // What the app needs, then what this particular phone gets. The
            // first is the question someone asks before installing; the
            // second is the only one the phone in their hand can answer.
            Text(
                stringResource(R.string.about_requires_min),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Text(
                stringResource(
                    if (full) R.string.about_running_full else R.string.about_running_ten,
                    release
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (full) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        // What it may access, said on the page where someone checks. A
        // permission list is one of the few things an About page owes a
        // reader, and it is two sentences here rather than a settings dive.
        AppCard(modifier = Modifier.padding(top = 10.dp)) {
            Text(
                stringResource(R.string.about_permissions_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.about_permissions_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        // What a person actually checks here: that the app cannot reach the
        // internet, and what "updates" means for an app built to need none.
        // The build-chain facts that used to fill this card - package name,
        // build number, the signing fingerprint - are release-page material,
        // and every one of them ships in the release notes instead.
        AppCard(modifier = Modifier.padding(top = 10.dp)) {
            Text(
                stringResource(R.string.about_network_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.about_network_none),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                stringResource(R.string.about_updates_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        // The setting a reader is most likely to want from this page, shown as
        // a fact with one way to act on it. The control itself stays on Quality
        // explained: two places to change the same thing is two places to
        // disagree about what it currently is.
        AppCard(
            modifier = Modifier.padding(top = 10.dp),
            onClick = { nav.goTo(Routes.HELP_QUALITY) }
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                // The heading takes the room left beside the icon and
                // wraps; unheld it ran past the card at a large text size.
                Text(
                    stringResource(R.string.about_quality_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
            Text(
                stringResource(
                    R.string.about_quality_line,
                    stringResource(
                        when (preset) {
                            Preset.STORAGE_SAVER -> R.string.preset_storage
                            Preset.BALANCED -> R.string.preset_balanced
                            Preset.MAX_SAVER -> R.string.preset_max
                        }
                    ),
                    QualityKept.photoCapMp(preset),
                    QualityKept.videoCapLongSide(preset)
                ),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                stringResource(R.string.about_quality_change),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        AppCard(modifier = Modifier.padding(top = 10.dp), onClick = { nav.goTo(Routes.HELP_PRIVACY) }) {
            Text(
                stringResource(R.string.help_privacy),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                stringResource(R.string.about_privacy_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

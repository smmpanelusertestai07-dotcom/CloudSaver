package app.cloudsaver.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.R
import app.cloudsaver.core.logic.BackupScope
import app.cloudsaver.core.logic.CapacityMath
import app.cloudsaver.core.logic.Preset
import app.cloudsaver.data.CloudApps
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.components.AnimatedNumber
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.HeroCard
import app.cloudsaver.ui.components.MetricTile
import app.cloudsaver.ui.components.SegmentedChoice
import app.cloudsaver.util.Formats
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * "How much fits in your cloud", inline wherever it is needed.
 *
 * It used to be a screen of its own reached by a button, which meant leaving
 * the place the question was asked to get the answer. It now sits inside
 * Storage, next to the numbers it is talking about.
 */
@Composable
fun CalculatorContent(vm: AppViewModel, modifier: Modifier = Modifier) {
    val options by vm.options.collectAsStateWithLifecycle()
    val gallery by vm.calcGallery.collectAsStateWithLifecycle()
    val ratios by vm.calcRatios.collectAsStateWithLifecycle()
    val source by vm.calcSource.collectAsStateWithLifecycle()

    LaunchedEffect(options.preset, options.codec, options.excludedBuckets, source) {
        vm.refreshCalculator()
    }

    var mode by remember(options.scope) {
        mutableStateOf(
            when (options.scope) {
                BackupScope.PHOTOS -> CapacityMath.CalcMode.PHOTOS
                BackupScope.VIDEOS -> CapacityMath.CalcMode.VIDEOS
                BackupScope.ALL -> CapacityMath.CalcMode.BOTH
            }
        )
    }
    var mixOverride by remember { mutableStateOf<Float?>(null) }
    var freeText by remember { mutableStateOf("") }
    var prefilled by remember { mutableStateOf(false) }

    val cloudApp = CloudApps.byId(options.cloudSingle)
    LaunchedEffect(cloudApp.id) {
        if (!prefilled && freeText.isEmpty()) {
            cloudApp.prefillGb?.let {
                freeText = it.toString()
                prefilled = true
            }
        }
    }

    val defaultShare = gallery?.let { CapacityMath.defaultMixShare(it).toFloat() } ?: 0.5f
    val share = mixOverride ?: defaultShare
    val freeGb = freeText.replace(',', '.').toDoubleOrNull()
    val estimate = remember(freeGb, mode, share, gallery, ratios) {
        val g = gallery
        val r = ratios
        if (freeGb != null && freeGb > 0 && g != null && r != null) {
            CapacityMath.estimate(freeGb, mode, share.toDouble(), g, r)
        } else {
            null
        }
    }

    Column(modifier) {
        // Which figures are in use, stated and switchable. An estimate whose
        // basis is invisible is a number the user cannot argue with.
        Text(
            stringResource(R.string.calc_source_label),
            style = MaterialTheme.typography.labelLarge
        )
        val measuredAvailable = ratios?.let {
            (mode != CapacityMath.CalcMode.VIDEOS && it.photoMeasured) ||
                (mode != CapacityMath.CalcMode.PHOTOS && it.videoMeasured)
        } ?: false
        SegmentedChoice(
            listOf(
                CapacityMath.Source.MEASURED.name to stringResource(R.string.calc_source_mine),
                CapacityMath.Source.TYPICAL.name to stringResource(R.string.calc_source_typical)
            ),
            (source ?: if (measuredAvailable) {
                CapacityMath.Source.MEASURED
            } else {
                CapacityMath.Source.TYPICAL
            }).name
        ) { vm.setCalcSource(CapacityMath.Source.valueOf(it)) }

        Spacer(Modifier.height(8.dp))
        SegmentedChoice(
            listOf(
                CapacityMath.CalcMode.PHOTOS.name to stringResource(R.string.scope_photos),
                CapacityMath.CalcMode.VIDEOS.name to stringResource(R.string.scope_videos),
                CapacityMath.CalcMode.BOTH.name to stringResource(R.string.calc_mode_both)
            ),
            mode.name
        ) { mode = CapacityMath.CalcMode.valueOf(it) }

        if (mode == CapacityMath.CalcMode.BOTH) {
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    R.string.calc_mix_label,
                    (share * 100).roundToInt(),
                    ((1 - share) * 100).roundToInt()
                ),
                style = MaterialTheme.typography.labelLarge
            )
            Slider(
                value = share,
                onValueChange = { mixOverride = it },
                valueRange = 0f..1f
            )
        }

        Spacer(Modifier.height(8.dp))
        AppCard {
            Text(
                stringResource(R.string.calc_input_label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            OutlinedTextField(
                value = freeText,
                onValueChange = { freeText = it },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (gb in listOf(5, 10, 20, 50, 100, 200)) {
                    androidx.compose.material3.FilterChip(
                        selected = freeText == gb.toString(),
                        onClick = { freeText = gb.toString() },
                        label = { Text(stringResource(R.string.calc_chip_gb, gb)) }
                    )
                }
            }
            if (prefilled) {
                Text(
                    stringResource(R.string.calc_prefill_note, cloudApp.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        val scheme = MaterialTheme.colorScheme
        if (estimate == null) {
            AppCard {
                Text(
                    stringResource(R.string.calc_enter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant
                )
            }
        } else {
            // Headline first, detail after: the answer is "how much fits".
            HeroCard {
                Text(
                    if (estimate.typicalEstimate) {
                        stringResource(R.string.calc_badge_typical)
                    } else {
                        pluralStringResource(
                            R.plurals.calc_badge_measured,
                            estimate.sampleCount,
                            estimate.sampleCount
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f)
                )
                AnimatedNumber(
                    value = stringResource(R.string.calc_hero_value, fmt(estimate.originalsGB)),
                    color = Color.White,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    stringResource(R.string.calc_hero_caption, fmt(freeGb ?: 0.0)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f)
                )
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (mode != CapacityMath.CalcMode.VIDEOS) {
                    MetricTile(
                        value = fmtCount(estimate.photoCount),
                        label = stringResource(R.string.calc_tile_photos),
                        modifier = Modifier.weight(1f)
                    )
                }
                if (mode != CapacityMath.CalcMode.PHOTOS) {
                    MetricTile(
                        value = fmt(estimate.videoHours),
                        label = stringResource(R.string.calc_tile_video_hours),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            val galleryGb = gallery?.let {
                val bytes = when (mode) {
                    CapacityMath.CalcMode.PHOTOS -> it.photoBytes
                    CapacityMath.CalcMode.VIDEOS -> it.videoBytes
                    CapacityMath.CalcMode.BOTH -> it.photoBytes + it.videoBytes
                }
                bytes / CapacityMath.GB
            } ?: 0.0
            AppCard(tonal = estimate.fits) {
                Text(
                    if (estimate.fits) {
                        stringResource(
                            R.string.calc_spare,
                            fmt(((freeGb ?: 0.0) - estimate.backlogGB).coerceAtLeast(0.0))
                        )
                    } else {
                        stringResource(R.string.calc_needs, fmt(estimate.needMoreGB))
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (estimate.fits) scheme.onPrimaryContainer else scheme.error
                )
                Text(
                    stringResource(
                        R.string.calc_gallery_line,
                        fmt(galleryGb),
                        fmt(estimate.backlogGB)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (estimate.fits) {
                        scheme.onPrimaryContainer.copy(alpha = 0.85f)
                    } else {
                        scheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 4.dp)
                )
                val monthlyGb = gallery?.let {
                    (it.monthlyPhotoBytes + it.monthlyVideoBytes) / CapacityMath.GB
                } ?: 0.0
                if (monthlyGb > 0) {
                    Text(
                        stringResource(R.string.calc_pace, fmt(monthlyGb)),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (estimate.fits) {
                            scheme.onPrimaryContainer.copy(alpha = 0.85f)
                        } else {
                            scheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (estimate.fits && estimate.monthsLeft >= 0) {
                    Text(
                        stringResource(R.string.calc_months, fmt(estimate.monthsLeft)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            AppCard {
                Text(
                    when (options.preset) {
                        Preset.STORAGE_SAVER -> stringResource(R.string.calc_quality_storage)
                        Preset.BALANCED -> stringResource(R.string.calc_quality_balanced)
                        Preset.MAX_SAVER -> stringResource(R.string.calc_quality_max)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.calc_estimate_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * GB to two decimals, the way a cloud plan is written.
 *
 * Two significant figures rounded 1.04 GB to 1.0 and 12.4 to 12, which read
 * as different precisions on the same screen and made small differences
 * vanish. Plans are sold in GB, so the calculator counts in GB.
 */
private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

/** Counts are whole things: photos and files, with thousands separators. */
private fun fmtCount(v: Long): String = Formats.count(v)

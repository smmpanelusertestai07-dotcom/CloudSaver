package app.litesaver.ui.screens

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.litesaver.R
import app.litesaver.core.logic.BackupScope
import app.litesaver.core.logic.CapacityMath
import app.litesaver.core.logic.Preset
import app.litesaver.data.CloudApps
import app.litesaver.ui.AppViewModel
import app.litesaver.ui.components.GlassCard
import app.litesaver.ui.components.PillChoice
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Cloud calculator (13.C): live "how much fits" estimates while typing. */
@Composable
fun CalculatorScreen(vm: AppViewModel, nav: NavHostController) {
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
            Text(
                stringResource(R.string.calc_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
        CalculatorContent(vm)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun CalculatorContent(vm: AppViewModel, modifier: Modifier = Modifier) {
    val options by vm.options.collectAsStateWithLifecycle()
    val gallery by vm.calcGallery.collectAsStateWithLifecycle()
    val ratios by vm.calcRatios.collectAsStateWithLifecycle()

    LaunchedEffect(options.preset, options.codec, options.excludedBuckets) {
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
        PillChoice(
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
        GlassCard {
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
            ) {
                for (gb in listOf(5, 10, 20, 50, 100, 200)) {
                    TextButton(onClick = { freeText = gb.toString() }) { Text("$gb") }
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

        Spacer(Modifier.height(8.dp))
        if (estimate == null) {
            GlassCard {
                Text(
                    stringResource(R.string.calc_enter),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            GlassCard {
                Text(
                    if (estimate.typicalEstimate) {
                        stringResource(R.string.calc_badge_typical)
                    } else {
                        stringResource(R.string.calc_badge_measured, estimate.sampleCount)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        R.string.calc_line1,
                        fmt(freeGb ?: 0.0),
                        fmt(estimate.originalsGB)
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                when (mode) {
                    CapacityMath.CalcMode.PHOTOS -> Text(
                        stringResource(R.string.calc_cap_photos, fmtCount(estimate.photoCount)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    CapacityMath.CalcMode.VIDEOS -> Text(
                        stringResource(R.string.calc_cap_videos, fmt(estimate.videoHours)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    CapacityMath.CalcMode.BOTH -> Text(
                        stringResource(
                            R.string.calc_cap_both,
                            fmtCount(estimate.photoCount),
                            fmt(estimate.videoHours)
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(4.dp))
                val galleryGb = gallery?.let {
                    val bytes = when (mode) {
                        CapacityMath.CalcMode.PHOTOS -> it.photoBytes
                        CapacityMath.CalcMode.VIDEOS -> it.videoBytes
                        CapacityMath.CalcMode.BOTH -> it.photoBytes + it.videoBytes
                    }
                    bytes / CapacityMath.GB
                } ?: 0.0
                Text(
                    stringResource(
                        R.string.calc_gallery_line,
                        fmt(galleryGb),
                        fmt(estimate.backlogGB)
                    ) + " " + if (estimate.fits) {
                        stringResource(R.string.calc_fits)
                    } else {
                        stringResource(R.string.calc_needs, fmt(estimate.needMoreGB))
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (estimate.fits && estimate.monthsLeft >= 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.calc_months, fmt(estimate.monthsLeft)),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when (options.preset) {
                        Preset.STORAGE_SAVER -> stringResource(R.string.calc_quality_storage)
                        Preset.BALANCED -> stringResource(R.string.calc_quality_balanced)
                        Preset.MAX_SAVER -> stringResource(R.string.calc_quality_max)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.calc_estimate_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 2 significant figures, trailing ".0" trimmed. */
private fun fmt(v: Double): String {
    val r = CapacityMath.round2sf(v)
    return if (r == floor(r) && abs(r) < 1e15) r.toLong().toString() else r.toString()
}

private fun fmtCount(v: Long): String = fmt(v.toDouble())

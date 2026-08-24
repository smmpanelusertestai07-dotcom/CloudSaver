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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import app.cloudsaver.R
import app.cloudsaver.core.logic.CapacityMath
import app.cloudsaver.data.CloudApps
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.components.AnimatedNumber
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.HeroCard
import app.cloudsaver.ui.components.MetricGrid
import app.cloudsaver.ui.components.MetricTile
import app.cloudsaver.ui.components.SectionHeader
import app.cloudsaver.ui.theme.OnBrand
import app.cloudsaver.ui.theme.OnBrandMuted
import app.cloudsaver.ui.theme.TabularFigures
import app.cloudsaver.util.Formats

/**
 * "How much of my gallery fits in my cloud?"
 *
 * A screen of its own, and measured only. It used to carry a My-files/Typical
 * selector and a photo-to-video slider, which asked the user to supply two
 * numbers the app already knows: the split comes from their actual gallery,
 * and whether the ratios are measured is a fact, not a preference. Both are
 * gone. What is left is one thing to type and the answer.
 */
@Composable
fun CalculatorScreen(vm: AppViewModel, nav: NavHostController) {
    val options by vm.options.collectAsStateWithLifecycle()
    val gallery by vm.calcGallery.collectAsStateWithLifecycle()
    val ratios by vm.calcRatios.collectAsStateWithLifecycle()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(options.preset, options.codec, options.excludedBuckets) {
        vm.refreshCalculator()
        vm.refreshProfile()
    }

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

    val freeGb = freeText.replace(',', '.').toDoubleOrNull()
    // The real split of this gallery. No slider, because the answer is not a
    // matter of opinion.
    val share = gallery?.let { CapacityMath.defaultMixShare(it) } ?: 0.5
    val estimate = remember(freeGb, share, gallery, ratios) {
        val g = gallery
        val r = ratios
        if (freeGb != null && freeGb > 0 && g != null && r != null) {
            CapacityMath.estimate(freeGb, CapacityMath.CalcMode.BOTH, share, g, r)
        } else {
            null
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(top = 8.dp, start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // 1. The one thing the app cannot know.
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.CloudQueue,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.calc_input_label),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                OutlinedTextField(
                    value = freeText,
                    onValueChange = { freeText = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (gb in listOf(5, 10, 20, 50, 100, 200)) {
                        FilterChip(
                            selected = freeText == gb.toString(),
                            onClick = { freeText = gb.toString() },
                            label = { Text(stringResource(R.string.calc_chip_gb, gb)) }
                        )
                    }
                }
                Text(
                    stringResource(R.string.calc_check_inside),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            Spacer(Modifier.height(12.dp))
            if (estimate == null) {
                AppCard {
                    Text(
                        stringResource(R.string.calc_enter),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(28.dp))
                return@Column
            }

            // 2. The answer.
            HeroCard {
                AnimatedNumber(
                    value = stringResource(R.string.calc_hero_value, fmt(estimate.originalsGB)),
                    color = OnBrand
                )
                Text(
                    stringResource(R.string.calc_hero_caption, fmt(freeGb ?: 0.0)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnBrandMuted
                )
            }

            Spacer(Modifier.height(12.dp))
            MetricGrid(
                listOf(
                    { m: Modifier ->
                        MetricTile(
                            Formats.count(estimate.photoCount),
                            stringResource(R.string.calc_tile_photos),
                            m
                        )
                    },
                    { m: Modifier ->
                        MetricTile(
                            Formats.hours(estimate.videoHours),
                            stringResource(R.string.calc_tile_video_hours),
                            m
                        )
                    }
                )
            )

            // 3. Their gallery against that.
            SectionHeader(stringResource(R.string.calc_your_gallery))
            val galleryGb = gallery?.let {
                (it.photoBytes + it.videoBytes) / CapacityMath.GB
            } ?: 0.0
            AppCard(tonal = estimate.fits) {
                Text(
                    stringResource(
                        R.string.calc_gallery_line, fmt(galleryGb), fmt(estimate.backlogGB)
                    ),
                    style = MaterialTheme.typography.bodyMedium.merge(TabularFigures),
                    color = if (estimate.fits) scheme.onPrimaryContainer else scheme.onSurface
                )
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
                    color = if (estimate.fits) scheme.onPrimaryContainer else scheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            // 4. Pace, only with enough history to mean anything.
            val monthlyGb = gallery?.let {
                (it.monthlyPhotoBytes + it.monthlyVideoBytes) / CapacityMath.GB
            } ?: 0.0
            if (monthlyGb > 0 && estimate.fits && estimate.monthsLeft >= 0) {
                Spacer(Modifier.height(4.dp))
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Speed,
                            contentDescription = null,
                            tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            stringResource(R.string.calc_pace, fmt(monthlyGb)),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        stringResource(R.string.calc_months, fmt(estimate.monthsLeft)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, start = 30.dp)
                    )
                }
            }

            // 5. Quality, always visible - it is the reason the numbers work.
            SectionHeader(stringResource(R.string.calc_quality_header))
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.PhotoLibrary,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(
                            R.string.calc_quality_photos, profile.photos.shrinkPercent
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Icon(
                        Icons.Outlined.Movie,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(
                            R.string.calc_quality_videos, profile.videos.shrinkPercent
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Icon(
                        Icons.Outlined.Tune,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.calc_quality_limits),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                }
            }

            // 6. Where the figures came from. Never hidden behind an expander:
            // a number whose basis is invisible cannot be argued with.
            Text(
                if (estimate.typicalEstimate) {
                    stringResource(R.string.calc_basis_rough)
                } else {
                    stringResource(
                        R.string.calc_basis_measured,
                        Formats.count(profile.photos.samples),
                        Formats.count(profile.videos.samples)
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp)
            )
            Spacer(Modifier.height(28.dp))
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

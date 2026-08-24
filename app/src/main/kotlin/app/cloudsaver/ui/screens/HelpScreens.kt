package app.cloudsaver.ui.screens

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.cloudsaver.BuildConfig
import app.cloudsaver.R
import app.cloudsaver.core.logic.Platform
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.Routes
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.BrandMark
import app.cloudsaver.ui.components.KeyValueRow
import app.cloudsaver.util.AppLog
import app.cloudsaver.util.Formats

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
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        content()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun HelpScreen(vm: AppViewModel, nav: NavHostController) {
    HelpPage(nav, stringResource(R.string.nav_help)) {
        HelpLink(stringResource(R.string.help_faq)) { nav.navigate(Routes.HELP_FAQ) }
        HelpLink(stringResource(R.string.help_quality)) { nav.navigate(Routes.HELP_QUALITY) }
        HelpLink(stringResource(R.string.help_logs)) { nav.navigate(Routes.HELP_LOGS) }
        HelpLink(stringResource(R.string.help_privacy)) { nav.navigate(Routes.HELP_PRIVACY) }
        HelpLink(stringResource(R.string.help_licenses)) { nav.navigate(Routes.HELP_LICENSES) }
        HelpLink(stringResource(R.string.help_about)) { nav.navigate(Routes.HELP_ABOUT) }
        HelpLink(stringResource(R.string.uninstall_title)) { nav.navigate(Routes.UNINSTALL) }
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
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
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
    R.string.faq_q14 to R.string.faq_a14,
    R.string.faq_q15 to R.string.faq_a15,
    R.string.faq_q16 to R.string.faq_a16,
    R.string.faq_q17 to R.string.faq_a17,
    R.string.faq_q18 to R.string.faq_a18,
    R.string.faq_q19 to R.string.faq_a19,
    R.string.faq_q20 to R.string.faq_a20,
    R.string.faq_q21 to R.string.faq_a21,
    R.string.faq_q22 to R.string.faq_a22,
    R.string.faq_q23 to R.string.faq_a23,
    R.string.faq_q24 to R.string.faq_a24,
    R.string.faq_q25 to R.string.faq_a25,
    R.string.faq_q26 to R.string.faq_a26
)

@Composable
fun HelpFaqScreen(nav: NavHostController) {
    var open by remember { mutableIntStateOf(-1) }
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
    }
}

@Composable
fun HelpQualityScreen(nav: NavHostController, vm: AppViewModel) {
    val measured by vm.measuredQuality.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshMeasuredQuality() }

    HelpPage(nav, stringResource(R.string.quality_explained_title)) {
        // This phone's own numbers come first: they are the only figures here
        // that are a measurement rather than an estimate.
        AppCard(modifier = Modifier.padding(vertical = 4.dp), tonal = measured.hasAny) {
            Text(
                stringResource(R.string.quality_measured_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (!measured.hasAny) {
                Text(
                    stringResource(R.string.quality_measured_none),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                if (measured.photoCount > 0) {
                    Text(
                        stringResource(
                            R.string.quality_measured_photos,
                            "${measured.photoShrinkPercent}%",
                            Formats.count(measured.photoCount)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (measured.videoCount > 0) {
                    Text(
                        stringResource(
                            R.string.quality_measured_videos,
                            "${measured.videoShrinkPercent}%",
                            Formats.count(measured.videoCount)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Text(
                stringResource(R.string.quality_percent_meaning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        AppCard(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                stringResource(R.string.quality_caps),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(R.string.quality_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        AppCard(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                stringResource(R.string.quality_codec_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.quality_codec_text),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        AppCard(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                stringResource(R.string.quality_table_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            KeyValueRow(stringResource(R.string.quality_row1_k), stringResource(R.string.quality_row1_v))
            KeyValueRow(stringResource(R.string.quality_row2_k), stringResource(R.string.quality_row2_v))
            KeyValueRow(stringResource(R.string.quality_row3_k), stringResource(R.string.quality_row3_v))
            KeyValueRow(stringResource(R.string.quality_row4_k), stringResource(R.string.quality_row4_v))
        }
        AppCard(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                stringResource(R.string.quality_outro),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                stringResource(R.string.quality_originals_safe),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun HelpLogsScreen(nav: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    val shareTitle = stringResource(R.string.logs_share)
    LaunchedEffect(Unit) { text = AppLog.readTail(context) }
    HelpPage(nav, stringResource(R.string.help_logs)) {
        Row {
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
            }) { Text(shareTitle) }
            Spacer(Modifier.padding(horizontal = 4.dp))
            OutlinedButton(onClick = {
                AppLog.clear(context)
                text = ""
            }) { Text(stringResource(R.string.logs_clear)) }
        }
        AppCard(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                if (text.isEmpty()) stringResource(R.string.logs_empty) else text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = if (text.isEmpty()) null else FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun HelpPrivacyScreen(nav: NavHostController) {
    HelpPage(nav, stringResource(R.string.help_privacy)) {
        AppCard {
            Text(
                stringResource(R.string.privacy_text),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
fun HelpLicensesScreen(nav: NavHostController) {
    HelpPage(nav, stringResource(R.string.help_licenses)) {
        AppCard {
            Text(
                stringResource(R.string.licenses_text),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun HelpAboutScreen(vm: AppViewModel, nav: NavHostController) {
    HelpPage(nav, stringResource(R.string.help_about)) {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(size = 52.dp)
                Column(Modifier.padding(start = 12.dp)) {
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
        }
        AppCard(modifier = Modifier.padding(top = 10.dp)) {
            KeyValueRow(stringResource(R.string.about_version), BuildConfig.VERSION_NAME)
            KeyValueRow(stringResource(R.string.about_package), BuildConfig.APPLICATION_ID)
            KeyValueRow(
                stringResource(R.string.about_network),
                stringResource(R.string.about_network_value)
            )
            Text(
                stringResource(R.string.about_partner),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        // Compatibility belongs on About, where people look for it before
        // sending the file to someone else. It states what fully works, not
        // just what installs.
        AppCard(modifier = Modifier.padding(top = 10.dp)) {
            Text(
                stringResource(R.string.about_requires_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            KeyValueRow(
                stringResource(R.string.about_requires),
                stringResource(R.string.about_requires_value)
            )
            KeyValueRow(
                stringResource(R.string.about_running_on),
                stringResource(
                    R.string.about_running_value,
                    Platform.releaseName(android.os.Build.VERSION.SDK_INT),
                    android.os.Build.VERSION.SDK_INT
                )
            )
            Text(
                stringResource(R.string.about_support_full),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                stringResource(R.string.about_support_ten),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            // Say plainly which of the two this phone is.
            Text(
                stringResource(
                    if (Platform.supportFor(android.os.Build.VERSION.SDK_INT) ==
                        Platform.Support.FULL
                    ) {
                        R.string.about_this_phone_full
                    } else {
                        R.string.about_this_phone_limited
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = if (Platform.supportFor(android.os.Build.VERSION.SDK_INT) ==
                    Platform.Support.FULL
                ) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        // Build number and hashes matter to about one reader in a thousand,
        // and reading like a crash report to the rest.
        var technical by remember { mutableStateOf(false) }
        AppCard(modifier = Modifier.padding(top = 10.dp), onClick = { technical = !technical }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.about_technical),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                val arrow by animateFloatAsState(
                    targetValue = if (technical) 0f else -90f,
                    label = "technicalArrow"
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(arrow)
                )
            }
            AnimatedVisibility(
                visible = technical,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column {
                    KeyValueRow(
                        stringResource(R.string.about_build),
                        BuildConfig.VERSION_CODE.toString()
                    )
                    KeyValueRow(
                        stringResource(R.string.about_cert),
                        BuildConfig.EXPECTED_CERT_SHA256.ifEmpty {
                            stringResource(R.string.about_cert_dev)
                        }
                    )
                }
            }
        }
        AppCard(modifier = Modifier.padding(top = 10.dp), onClick = { nav.navigate(Routes.HELP_PRIVACY) }) {
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

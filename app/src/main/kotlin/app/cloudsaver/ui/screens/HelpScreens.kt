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
import app.cloudsaver.ui.AppViewModel
import app.cloudsaver.ui.Routes
import app.cloudsaver.ui.components.AppCard
import app.cloudsaver.ui.components.KeyValueRow
import app.cloudsaver.util.AppLog

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
        HelpLink(stringResource(R.string.help_rerun_setup)) { vm.restartOnboarding() }
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
    R.string.faq_q18 to R.string.faq_a18
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
fun HelpQualityScreen(nav: NavHostController) {
    HelpPage(nav, stringResource(R.string.help_quality)) {
        AppCard(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                stringResource(R.string.quality_intro),
                style = MaterialTheme.typography.bodyMedium
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
                Icon(
                    painterResource(R.drawable.ic_stat_cloud),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
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
            KeyValueRow(
                stringResource(R.string.about_version),
                BuildConfig.VERSION_NAME
            )
            KeyValueRow(
                stringResource(R.string.about_build),
                BuildConfig.VERSION_CODE.toString()
            )
            KeyValueRow(
                stringResource(R.string.about_package),
                BuildConfig.APPLICATION_ID
            )
            KeyValueRow(
                stringResource(R.string.about_network),
                stringResource(R.string.about_network_value)
            )
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

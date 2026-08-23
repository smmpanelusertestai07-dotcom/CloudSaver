package app.litesaver.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.litesaver.BuildConfig
import app.litesaver.R
import app.litesaver.ui.AppViewModel
import app.litesaver.ui.Routes
import app.litesaver.ui.components.GlassCard
import app.litesaver.ui.components.KeyValueRow
import app.litesaver.util.LiteLog

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
    }
}

@Composable
private fun HelpLink(label: String, onClick: () -> Unit) {
    GlassCard(modifier = Modifier.padding(vertical = 4.dp), onClick = onClick) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
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
    R.string.faq_q16 to R.string.faq_a16
)

@Composable
fun HelpFaqScreen(nav: NavHostController) {
    var open by remember { mutableIntStateOf(-1) }
    HelpPage(nav, stringResource(R.string.help_faq)) {
        FAQ.forEachIndexed { index, (q, a) ->
            GlassCard(
                modifier = Modifier.padding(vertical = 4.dp),
                onClick = { open = if (open == index) -1 else index }
            ) {
                Text(
                    stringResource(q),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                if (open == index) {
                    Text(
                        stringResource(a),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun HelpQualityScreen(nav: NavHostController) {
    HelpPage(nav, stringResource(R.string.help_quality)) {
        GlassCard(modifier = Modifier.padding(vertical = 4.dp)) {
            Text(
                stringResource(R.string.quality_intro),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        GlassCard(modifier = Modifier.padding(vertical = 4.dp)) {
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
        GlassCard(modifier = Modifier.padding(vertical = 4.dp)) {
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
        GlassCard(modifier = Modifier.padding(vertical = 4.dp)) {
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
    LaunchedEffect(Unit) { text = LiteLog.readTail(context) }
    HelpPage(nav, stringResource(R.string.help_logs)) {
        Row {
            OutlinedButton(onClick = {
                try {
                    val file = LiteLog.file(context)
                    if (file.exists()) {
                        val uri = FileProvider.getUriForFile(
                            context, "app.litesaver.fileprovider", file
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
                LiteLog.clear(context)
                text = ""
            }) { Text(stringResource(R.string.logs_clear)) }
        }
        GlassCard(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                if (text.isEmpty()) stringResource(R.string.logs_empty) else text,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun HelpPrivacyScreen(nav: NavHostController) {
    HelpPage(nav, stringResource(R.string.help_privacy)) {
        GlassCard {
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
        GlassCard {
            Text(
                stringResource(R.string.licenses_text),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun HelpAboutScreen(vm: AppViewModel, nav: NavHostController) {
    val sha by vm.apkSha.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.computeApkSha() }
    HelpPage(nav, stringResource(R.string.help_about)) {
        GlassCard {
            KeyValueRow(stringResource(R.string.about_version), BuildConfig.VERSION_NAME)
            Text(
                stringResource(R.string.about_sha),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                sha.ifEmpty { "..." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.about_text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

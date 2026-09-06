package app.novacalc.ui

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.novacalc.BuildConfig
import app.novacalc.R
import app.novacalc.data.CalculatorSettings
import app.novacalc.data.ThemeMode
import app.novacalc.engine.AngleUnit
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: CalculatorSettings,
    viewModel: CalculatorViewModel,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState()).testTag("settings_screen"),
        ) {
            SectionTitle(stringResource(R.string.section_appearance))
            ListItem(
                headlineContent = { Text(stringResource(R.string.theme)) },
                supportingContent = {
                    val labels = listOf(R.string.theme_system, R.string.theme_light, R.string.theme_dark)
                    val modes = ThemeMode.entries
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        modes.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = settings.themeMode == mode,
                                onClick = { viewModel.setThemeMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                                modifier = Modifier.testTag("theme_${mode.name.lowercase()}"),
                            ) { Text(stringResource(labels[index])) }
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SwitchRow(
                    title = stringResource(R.string.dynamic_color),
                    subtitle = stringResource(R.string.dynamic_color_desc),
                    checked = settings.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                    tag = "switch_dynamic",
                )
            }

            SectionTitle(stringResource(R.string.section_feedback))
            SwitchRow(
                title = stringResource(R.string.haptics),
                subtitle = stringResource(R.string.haptics_desc),
                checked = settings.haptics,
                onCheckedChange = viewModel::setHaptics,
                tag = "switch_haptics",
            )
            SwitchRow(
                title = stringResource(R.string.keep_screen_on),
                subtitle = stringResource(R.string.keep_screen_on_desc),
                checked = settings.keepScreenOn,
                onCheckedChange = viewModel::setKeepScreenOn,
                tag = "switch_keep_screen_on",
            )

            SectionTitle(stringResource(R.string.section_calculation))
            ListItem(
                headlineContent = { Text(stringResource(R.string.angle_unit)) },
                supportingContent = {
                    val units = AngleUnit.entries
                    val labels = listOf(R.string.angle_degrees, R.string.angle_radians)
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        units.forEachIndexed { index, unit ->
                            SegmentedButton(
                                selected = settings.angleUnit == unit,
                                onClick = { viewModel.setAngleUnit(unit) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = units.size),
                                modifier = Modifier.testTag("angle_${unit.name.lowercase()}"),
                            ) { Text(stringResource(labels[index])) }
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.decimal_places)) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.decimal_places_desc, settings.maxFractionDigits))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Slider(
                                value = settings.maxFractionDigits.toFloat(),
                                onValueChange = { viewModel.setMaxFractionDigits(it.roundToInt()) },
                                valueRange = 0f..12f,
                                steps = 11,
                                modifier = Modifier.weight(1f).testTag("slider_decimals").semantics { contentDescription = "Decimal places" },
                            )
                            Text(
                                text = settings.maxFractionDigits.toString(),
                                modifier = Modifier.padding(start = 12.dp).testTag("decimals_value"),
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
            SwitchRow(
                title = stringResource(R.string.grouping),
                subtitle = stringResource(R.string.grouping_desc),
                checked = settings.grouping,
                onCheckedChange = viewModel::setGrouping,
                tag = "switch_grouping",
            )

            SectionTitle(stringResource(R.string.section_about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.app_name)) },
                supportingContent = { Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.testTag("about_version"),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_stack)) },
                supportingContent = { Text(stringResource(R.string.about_licence)) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.about_privacy)) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, tag: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.testTag(tag))
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

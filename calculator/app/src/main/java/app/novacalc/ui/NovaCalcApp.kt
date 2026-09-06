package app.novacalc.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.novacalc.CalcApplication
import app.novacalc.ui.theme.NovaCalcTheme

private const val ROUTE_CALCULATOR = "calculator"
private const val ROUTE_SETTINGS = "settings"

@Composable
fun NovaCalcApp() {
    val context = LocalContext.current
    val container = (context.applicationContext as CalcApplication).container
    val viewModel: CalculatorViewModel = viewModel(factory = CalculatorViewModel.factory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    NovaCalcTheme(themeMode = state.settings.themeMode, dynamicColor = state.settings.dynamicColor) {
        KeepScreenOn(state.settings.keepScreenOn)
        val navController = rememberNavController()
        NavHost(navController = navController, startDestination = ROUTE_CALCULATOR) {
            composable(ROUTE_CALCULATOR) {
                CalculatorRoute(
                    viewModel = viewModel,
                    state = state,
                    onOpenSettings = { navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true } },
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(settings = state.settings, viewModel = viewModel, onBack = { navController.popBackStack() })
            }
        }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val activity = LocalActivity.current
    DisposableEffect(enabled, activity) {
        val window = activity?.window
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

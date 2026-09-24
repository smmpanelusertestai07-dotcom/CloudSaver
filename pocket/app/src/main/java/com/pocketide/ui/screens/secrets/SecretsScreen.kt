package com.pocketide.ui.screens.secrets

import androidx.compose.runtime.Composable
import com.pocketide.ui.components.PendingScreen
import com.pocketide.ui.nav.PocketNav

@Composable
fun SecretsScreen(projectId: String?, nav: PocketNav) = PendingScreen("Variables and Secrets").also { nav.hashCode() }


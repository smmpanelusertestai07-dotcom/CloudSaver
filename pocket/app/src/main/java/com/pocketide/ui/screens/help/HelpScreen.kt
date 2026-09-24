package com.pocketide.ui.screens.help

import androidx.compose.runtime.Composable
import com.pocketide.ui.components.PendingScreen
import com.pocketide.ui.nav.PocketNav

@Composable
fun HelpScreen(sectionId: String?, nav: PocketNav) = PendingScreen("Help ${sectionId.orEmpty()}").also { nav.hashCode() }


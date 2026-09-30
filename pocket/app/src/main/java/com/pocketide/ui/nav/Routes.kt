package com.pocketide.ui.nav

import kotlinx.serialization.Serializable

// The app's destinations. The three in the bottom bar are tabs; the rest open over them.

@Serializable
data object HomeRoute

/** The computer: Google Cloud Shell, its account, limits, data and safety. */
@Serializable
data object ComputerRoute

@Serializable
data object SettingsRoute

@Serializable
data object DataRoute

@Serializable
data object HelpRoute

@Serializable
data class HelpPageRoute(val id: String)

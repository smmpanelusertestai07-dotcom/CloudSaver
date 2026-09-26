package com.pocketide.ui.nav

import kotlinx.serialization.Serializable

// The app's destinations. The four in the bottom bar are tabs; the rest open over them.

@Serializable
data object HomeRoute

@Serializable
data object ComputerRoute

@Serializable
data object UsageRoute

@Serializable
data object SettingsRoute

@Serializable
data object DataRoute

@Serializable
data object HelpRoute

@Serializable
data class HelpPageRoute(val id: String)

@Serializable
data object NewProjectRoute

@Serializable
data object OpenRepoRoute

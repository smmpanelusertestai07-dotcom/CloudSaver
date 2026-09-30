package com.pocketide.ui.nav

import kotlinx.serialization.Serializable

// The app's destinations. The three in the bottom bar are tabs; the rest open over them.

@Serializable
data object HomeRoute

@Serializable
data object ComputerRoute

@Serializable
data object SettingsRoute

/**
 * The agent screen: code-server full screen, showing [agentId]'s own screen, or a terminal when
 * [terminal] is true, with [command] typed in it (an agent's sign-in).
 */
@Serializable
data class WorkspaceRoute(val agentId: String = "", val terminal: Boolean = false, val command: String = "", val title: String = "")

@Serializable
data object AddAgentsRoute

@Serializable
data object KeysRoute

@Serializable
data object DataRoute

@Serializable
data object HelpRoute

/** Google Cloud Shell, Google's own Linux computer: set-up, data, limits and safety. */
@Serializable
data object CloudShellRoute

@Serializable
data class HelpPageRoute(val id: String)

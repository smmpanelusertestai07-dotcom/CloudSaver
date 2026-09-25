package com.pocketide.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What an extension's package.json says about its own screen. An agent PocketIDE can show full
 * screen contributes a view container with a webview view in it; VS Code gives every view a
 * `<viewId>.focus` command and every extension container a `workbench.view.extension.<id>` one.
 *
 * Chat participants alone are not enough: they live in VS Code's own chat, which every room
 * turns off (`chat.disableAIFeatures`).
 */
internal object AgentScreens {
    /** Where a container can sit, preferring the secondary side bar, which maximises cleanly. */
    private val LOCATIONS = listOf("secondarySidebar", "activitybar", "panel")

    /** The command that shows the extension's own webview, or null when it has none. */
    fun openCommand(packageJson: JsonObject): String? = webviews(packageJson).firstOrNull()?.let { "$it.focus" }

    /** True when [command] is one the extension declares, or one VS Code derives from its views. */
    fun commandExists(packageJson: JsonObject, command: String): Boolean {
        val contributes = packageJson.obj("contributes") ?: return false
        val declared = contributes.array("commands")?.mapNotNull { (it as? JsonObject)?.string("command") }.orEmpty()
        if (command in declared) return true
        if (command.endsWith(".focus") && command.removeSuffix(".focus") in views(contributes)) return true
        val containerPrefix = "workbench.view.extension."
        return command.startsWith(containerPrefix) && command.removePrefix(containerPrefix) in containers(contributes)
    }

    /** Webview view ids inside the extension's own view containers, best location first. */
    fun webviews(packageJson: JsonObject): List<String> {
        val contributes = packageJson.obj("contributes") ?: return emptyList()
        val views = contributes.obj("views") ?: return emptyList()
        val containers = contributes.obj("viewsContainers") ?: return emptyList()
        return LOCATIONS.flatMap { location ->
            containers.array(location)?.mapNotNull { (it as? JsonObject)?.string("id") }.orEmpty()
        }.flatMap { container ->
            views.array(container)?.mapNotNull { view ->
                (view as? JsonObject)?.takeIf { it.string("type") == "webview" }?.string("id")
            }.orEmpty()
        }.distinct()
    }

    private fun views(contributes: JsonObject): Set<String> =
        contributes.obj("views")?.values?.flatMap { list ->
            (list as? JsonArray)?.mapNotNull { (it as? JsonObject)?.string("id") }.orEmpty()
        }.orEmpty().toSet()

    private fun containers(contributes: JsonObject): Set<String> =
        contributes.obj("viewsContainers")?.values?.flatMap { list ->
            (list as? JsonArray)?.mapNotNull { (it as? JsonObject)?.string("id") }.orEmpty()
        }.orEmpty().toSet()

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.array(key: String) = this[key] as? JsonArray
    private fun JsonObject.string(key: String) = runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()
}

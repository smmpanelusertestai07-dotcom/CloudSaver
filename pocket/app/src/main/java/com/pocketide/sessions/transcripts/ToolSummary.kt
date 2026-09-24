package com.pocketide.sessions.transcripts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Tool calls in one line each ("Bash: npm test", "Edit: src/App.kt"); outputs are not shown. */
internal object ToolSummary {

    /** A tool call whose arguments are a JSON object (Claude's tool_use, Antigravity's tool calls). */
    fun of(name: String?, args: JsonObject?): String {
        val tool = name?.trim()?.takeIf { it.isNotEmpty() } ?: "Tool"
        val detail = args?.let(::detailOf)
        return oneLine(if (detail.isNullOrBlank()) tool else "$tool: $detail", MAX_CHARS)
    }

    /** Codex's function_call, whose arguments are JSON inside a string. */
    fun ofJsonArguments(name: String?, arguments: String?): String =
        of(name, arguments?.let(::parseObject))

    /** Codex's custom tool calls; apply_patch lists the files it touches. */
    fun ofFreeform(name: String?, input: String?): String {
        val files = input?.let { PATCH_FILE.findAll(it).map { m -> m.groupValues[1].trim() }.toList() }.orEmpty()
        val detail = if (files.isNotEmpty()) files.joinToString(", ") else input?.lineSequence()?.firstOrNull { it.isNotBlank() }
        val tool = name?.takeIf { it.isNotBlank() } ?: "Tool"
        return oneLine(if (detail.isNullOrBlank()) tool else "$tool: $detail", MAX_CHARS)
    }

    private fun detailOf(args: JsonObject): String? {
        for (key in PREFERRED_KEYS) {
            val value = args[key] ?: continue
            value.asText()?.takeIf { it.isNotBlank() }?.let { return it }
            (value as? JsonArray)?.let(::commandOf)?.let { return it }
        }
        return args.values.firstNotNullOfOrNull { it.asText()?.takeIf { text -> text.isNotBlank() } }
    }

    /** A command given as argv; `bash -lc "<script>"` shows just the script. */
    fun commandOf(argv: JsonArray): String? {
        val parts = argv.mapNotNull { it.asText() }
        if (parts.isEmpty()) return null
        val shell = parts.size >= 3 && parts[0].substringAfterLast('/') in SHELLS && parts[1] in SHELL_FLAGS
        return if (shell) parts.drop(2).joinToString(" ") else parts.joinToString(" ")
    }

    private const val MAX_CHARS = 160
    private val PATCH_FILE = Regex("^\\*\\*\\* (?:Add|Update|Delete) File: (.+)$", RegexOption.MULTILINE)
    private val SHELLS = setOf("bash", "sh", "zsh", "dash")
    private val SHELL_FLAGS = setOf("-c", "-lc", "-ic")
    private val PREFERRED_KEYS = listOf(
        "command", "cmd", "CommandLine",
        "file_path", "path", "AbsolutePath", "TargetFile", "DirectoryPath", "notebook_path",
        "pattern", "query", "Query", "url", "Url",
        "description", "prompt",
    )
}

package com.pocketide.schedule

/**
 * The agents' own command-line modes, run inside the agent's room for a scheduled task. Each CLI
 * uses the login already in its room's home: PocketIDE passes no token, key or account.
 *
 * - Claude Code: `claude -p` with the binary the VS Code extension bundles; never `--bare`, which
 *   ignores the subscription login. Edits are accepted, anything else follows the room's own
 *   permission rules, and nothing waits for a person.
 * - Codex: `codex exec` with the binary the extension bundles; the room's config.toml decides the
 *   sandbox and approvals.
 * - Antigravity: `agy -p`, JSON output, because a refused permission still exits 0 and only the
 *   JSON status tells success from failure.
 */
object HeadlessCommand {
    const val CLAUDE = "claude"
    const val CODEX = "codex"
    const val ANTIGRAVITY = "antigravity"

    val supported = setOf(CLAUDE, CODEX, ANTIGRAVITY)

    /** The task's time limit, also handed to agy (its own default is five minutes). */
    const val TIME_LIMIT_MINUTES = 60

    /**
     * Finds the newest bundled binary and runs it in the worktree. The prompt and the folder are
     * positional arguments ($2, $3), so nothing in them is ever read as shell code.
     */
    internal val SCRIPT = """
        set -eu
        agent=${'$'}1
        prompt=${'$'}2
        cd "${'$'}3"
        newest() { for d in "${'$'}@"; do if [ -d "${'$'}d" ]; then printf '%s\n' "${'$'}d"; fi; done | sort -V | tail -n 1; }
        missing() { echo "${'$'}1 is not installed in this room yet. Open the agent once, then try again." >&2; exit 127; }
        exts="${'$'}HOME/.local/share/code-server/extensions"
        case "${'$'}agent" in
          claude)
            bin="${'$'}(newest "${'$'}exts"/anthropic.claude-code-*)/resources/native-binary/claude"
            [ -x "${'$'}bin" ] || bin="${'$'}(command -v claude || true)"
            [ -n "${'$'}bin" ] || missing "Claude Code"
            exec "${'$'}bin" -p "${'$'}prompt" --output-format text --permission-mode acceptEdits --permission-prompts none ;;
          codex)
            bin="${'$'}(newest "${'$'}exts"/openai.chatgpt-*)/bin/linux-aarch64/codex"
            [ -x "${'$'}bin" ] || bin="${'$'}(command -v codex || true)"
            [ -n "${'$'}bin" ] || missing "Codex"
            exec "${'$'}bin" exec --skip-git-repo-check --color never "${'$'}prompt" ;;
          antigravity)
            bin="${'$'}HOME/.gemini/bin/agy"
            [ -x "${'$'}bin" ] || missing "Antigravity"
            exec "${'$'}bin" -p "${'$'}prompt" --output-format json --print-timeout ${TIME_LIMIT_MINUTES}m ;;
        esac
        echo "This agent has no command-line mode." >&2
        exit 64
    """.trimIndent()

    /** The argv for [agentId], or null when the agent has no headless mode. */
    fun argv(agentId: String, prompt: String, guestWorktree: String): List<String>? {
        if (agentId !in supported) return null
        // A prompt that starts with "-" would be read as an option by the CLI.
        val safePrompt = if (prompt.startsWith("-")) " $prompt" else prompt
        return listOf("/bin/sh", "-c", SCRIPT, "pocketide-task", agentId, safePrompt, guestWorktree)
    }

    /** Environment that keeps a run quiet and predictable; never a token. */
    fun env(agentId: String): Map<String, String> = when (agentId) {
        ANTIGRAVITY -> mapOf("AGY_CLI_DISABLE_AUTO_UPDATE" to "true")
        else -> emptyMap()
    }

    /**
     * Whether a finished run worked. agy reports a refused permission with exit code 0, so its
     * JSON status decides; the others go by the exit code.
     */
    fun succeeded(agentId: String, exitCode: Int, output: List<String>): Boolean {
        if (exitCode != 0) return false
        if (agentId != ANTIGRAVITY) return true
        val status = output.asReversed().firstNotNullOfOrNull { STATUS.find(it)?.groupValues?.get(1) }
        return status == "SUCCESS"
    }

    private val STATUS = Regex("\"status\"\\s*:\\s*\"([A-Z_]+)\"")
}

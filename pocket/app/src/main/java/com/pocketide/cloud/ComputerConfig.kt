package com.pocketide.cloud

import com.pocketide.github.NewFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The files PocketIDE adds to a project in `.devcontainer/pocketide/`, which GitHub reads when it
 * makes the project's computer: GitHub's default image, the three official agents from the VS Code
 * Marketplace, a desktop the owner can watch (the official desktop-lite feature, on port 6080),
 * VS Code settings made for a phone, and a small script that puts those settings back every time
 * the computer starts, guards the code on GitHub against rewritten history, and gives each agent
 * a browser it can drive on that desktop.
 *
 * A project's own `.devcontainer/devcontainer.json` is never touched: GitHub makes PocketIDE's
 * computer from this folder's file, named when the computer is created.
 */
object ComputerConfig {
    const val FOLDER = ".devcontainer/pocketide"
    const val DEVCONTAINER = "$FOLDER/devcontainer.json"
    const val SETUP = "$FOLDER/setup.sh"
    const val SETTINGS = "$FOLDER/vscode-settings.json"

    /** Raised whenever the files change; the first line of each file names it. */
    const val VERSION = 2
    private const val MARKER_PREFIX = "Made by PocketIDE (set-up version "

    /** The marker is on the first line or two of each file (after a shebang). */
    private const val MARKER_LINES = 3
    const val MARKER = "$MARKER_PREFIX$VERSION)."

    /**
     * GitHub's default Codespaces image. GitHub keeps it ready on its machines, so a computer
     * starts sooner, and does not count its size against the free storage.
     */
    const val IMAGE = "mcr.microsoft.com/devcontainers/universal:2"

    /** The official extensions, each from its maker's verified publisher account. */
    val EXTENSIONS = listOf("anthropic.claude-code", "openai.chatgpt", "google.google-antigravity")

    /**
     * Official dev container features: a light desktop served in the browser (noVNC), where the
     * agents' browser windows show, and Claude Code's command-line tool for sign-in by code.
     */
    val FEATURES = linkedMapOf(
        "ghcr.io/devcontainers/features/desktop-lite:1" to JsonObject(emptyMap()),
        "ghcr.io/anthropics/devcontainer-features/claude-code:1.0" to JsonObject(emptyMap()),
    )

    /** The desktop's web page (noVNC), forwarded privately: only the owner's GitHub sign-in opens it. */
    const val DESKTOP_PORT = 6080

    private val pretty = Json { prettyPrint = true }

    fun files(): List<NewFile> = listOf(
        NewFile(DEVCONTAINER, devcontainer()),
        NewFile(SETTINGS, pretty.encodeToString(JsonObject.serializer(), vscodeSettings()) + "\n"),
        NewFile(SETUP, SETUP_SCRIPT, executable = true),
    )

    /** What a repository's copy of [DEVCONTAINER] says about its set-up files. */
    fun status(devcontainerText: String?): SetUpFiles {
        val version = devcontainerText?.let(::versionIn) ?: return SetUpFiles.MISSING
        return if (version >= VERSION) SetUpFiles.CURRENT else SetUpFiles.OUTDATED
    }

    internal fun versionIn(text: String): Int? =
        text.lineSequence().take(MARKER_LINES).firstNotNullOfOrNull { line ->
            line.substringAfter(MARKER_PREFIX, "").substringBefore(')').toIntOrNull()
        }

    internal fun devcontainer(): String {
        val config = buildJsonObject {
            put("name", "PocketIDE")
            put("image", IMAGE)
            put("features", JsonObject(FEATURES))
            put("forwardPorts", buildJsonArray { add(JsonPrimitive(DESKTOP_PORT)) })
            put(
                "portsAttributes",
                buildJsonObject {
                    put(
                        "$DESKTOP_PORT",
                        buildJsonObject {
                            put("label", "Desktop")
                            put("onAutoForward", "silent")
                        },
                    )
                },
            )
            // Browsers need more shared memory than a container's default 64 MB.
            put("runArgs", buildJsonArray { add(JsonPrimitive("--shm-size=1g")) })
            put(
                "customizations",
                buildJsonObject {
                    put(
                        "vscode",
                        buildJsonObject {
                            put("extensions", buildJsonArray { EXTENSIONS.forEach { add(JsonPrimitive(it)) } })
                            put("settings", vscodeSettings())
                        },
                    )
                },
            )
            put("postCreateCommand", "bash $SETUP create")
            // Not postAttachCommand: that one runs in a terminal the phone screen would open over the agent.
            put("postStartCommand", "bash $SETUP start")
        }
        return """
            |// $MARKER The cloud computer PocketIDE opens for this project.
            |// PocketIDE rewrites the files in this folder when its defaults change. Your own dev
            |// container set-up, if you have one, stays in .devcontainer/devcontainer.json.
            |
        """.trimMargin() + pretty.encodeToString(JsonObject.serializer(), config) + "\n"
    }

    /**
     * VS Code settings for a phone screen with the agents in front: no status, menu or activity
     * bar, the agents' side bar opened full screen, Enter for a new line (Send sends), and no
     * built-in AI chat next to the official agents. The theme follows the phone's light or dark.
     */
    internal fun vscodeSettings(): JsonObject = JsonObject(PHONE_SETTINGS + AGENT_SETTINGS)

    private val PHONE_SETTINGS: Map<String, JsonElement> = linkedMapOf(
        "workbench.startupEditor" to JsonPrimitive("none"),
        "workbench.secondarySideBar.defaultVisibility" to JsonPrimitive("maximized"),
        "workbench.activityBar.location" to JsonPrimitive("hidden"),
        "workbench.statusBar.visible" to JsonPrimitive(false),
        "window.menuBarVisibility" to JsonPrimitive("hidden"),
        "window.commandCenter" to JsonPrimitive(false),
        "workbench.layoutControl.enabled" to JsonPrimitive(false),
        "workbench.editor.showTabs" to JsonPrimitive("single"),
        "workbench.panel.opensMaximized" to JsonPrimitive("always"),
        "workbench.tips.enabled" to JsonPrimitive(false),
        "workbench.welcomePage.walkthroughs.openOnInstall" to JsonPrimitive(false),
        "workbench.reduceMotion" to JsonPrimitive("on"),
        "workbench.sash.size" to JsonPrimitive(20),
        "window.autoDetectColorScheme" to JsonPrimitive(true),
        "workbench.preferredDarkColorTheme" to JsonPrimitive("Default Dark Modern"),
        "workbench.preferredLightColorTheme" to JsonPrimitive("Default Light Modern"),
        "breadcrumbs.enabled" to JsonPrimitive(false),
        "editor.minimap.enabled" to JsonPrimitive(false),
        "editor.wordWrap" to JsonPrimitive("on"),
        "editor.stickyScroll.enabled" to JsonPrimitive(false),
        "editor.dragAndDrop" to JsonPrimitive(false),
        "editor.hover.delay" to JsonPrimitive(1500),
        "diffEditor.renderSideBySide" to JsonPrimitive(false),
        "files.autoSave" to JsonPrimitive("afterDelay"),
        "extensions.ignoreRecommendations" to JsonPrimitive(true),
        "chat.disableAIFeatures" to JsonPrimitive(true),
        "telemetry.telemetryLevel" to JsonPrimitive("off"),
        // A task file an agent wrote must not run by itself when the folder opens.
        "task.allowAutomaticTasks" to JsonPrimitive("off"),
    )

    private val AGENT_SETTINGS: Map<String, JsonElement> = linkedMapOf(
        "claudeCode.preferredLocation" to JsonPrimitive("sidebar"),
        "claudeCode.useCtrlEnterToSend" to JsonPrimitive(true),
        "claudeCode.hideOnboarding" to JsonPrimitive(true),
        "chatgpt.openOnStartup" to JsonPrimitive(true),
        "chatgpt.composerEnterBehavior" to JsonPrimitive("cmdAlways"),
    )

    /**
     * Runs inside the cloud computer, never on the phone. Every step can run again, and none can
     * keep the computer from opening: a failure is written to the log and the next step runs.
     */
    private val SETUP_SCRIPT = """
        |#!/usr/bin/env bash
        |# $MARKER
        |# Runs inside this project's cloud computer (a GitHub Codespace), never on your phone:
        |#   create  once, when GitHub makes the computer
        |#   start   each time the computer starts, the first time too
        |set -uo pipefail
        |
        |here="${'$'}(cd "${'$'}(dirname "${'$'}{BASH_SOURCE[0]}")" && pwd)"
        |
        |# VS Code ranks machine settings above settings made in a browser or synced from another
        |# device, so putting PocketIDE's file back at every start undoes any drift.
        |put_back_settings() {
        |  local dir="${'$'}HOME/.vscode-remote/data/Machine"
        |  mkdir -p "${'$'}dir" &&
        |    cp "${'$'}here/vscode-settings.json" "${'$'}dir/settings.json.pocketide" &&
        |    mv -f "${'$'}dir/settings.json.pocketide" "${'$'}dir/settings.json"
        |}
        |
        |# The computer's own GitHub token reaches this repository only. This hook also stops the
        |# two pushes that would rewrite what is on GitHub: a force push, and deleting the default
        |# branch. New commits and new branches push as usual. A hook of the project's own wins.
        |guard_pushes() {
        |  local hook
        |  hook="${'$'}(git rev-parse --git-path hooks/pre-push 2>/dev/null)" || return 0
        |  [ -e "${'$'}hook" ] && ! grep -q "PocketIDE's push guard" "${'$'}hook" && return 0
        |  mkdir -p "${'$'}(dirname "${'$'}hook")"
        |  cat > "${'$'}hook" <<'HOOK'
        |#!/usr/bin/env bash
        |# PocketIDE's push guard: no force push, and no deleting the default branch.
        |default="${'$'}(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null)"
        |default="${'$'}{default#origin/}"
        |while read -r _local_ref local_sha remote_ref remote_sha; do
        |  if [[ "${'$'}local_sha" =~ ^0+${'$'} ]]; then
        |    if [ -n "${'$'}default" ] && [ "${'$'}remote_ref" = "refs/heads/${'$'}default" ]; then
        |      echo "PocketIDE: deleting the ${'$'}default branch on GitHub is blocked here." >&2
        |      exit 1
        |    fi
        |  elif [[ ! "${'$'}remote_sha" =~ ^0+${'$'} ]] && ! git merge-base --is-ancestor "${'$'}remote_sha" "${'$'}local_sha" 2>/dev/null; then
        |    echo "PocketIDE: this push would rewrite history on GitHub (${'$'}remote_ref), so it is blocked." >&2
        |    echo "Pull first, or push to a new branch." >&2
        |    exit 1
        |  fi
        |done
        |exit 0
        |HOOK
        |  chmod 755 "${'$'}hook"
        |}
        |
        |# Claude Code reads policy from /etc/claude-code, which it cannot change itself. These rules
        |# only add refusals; everything else stays as the owner sets it in Claude Code.
        |guard_claude() {
        |  sudo -n true 2>/dev/null || return 0
        |  sudo mkdir -p /etc/claude-code &&
        |    sudo tee /etc/claude-code/managed-settings.json >/dev/null <<'POLICY'
        |{
        |  "permissions": {
        |    "deny": [
        |      "Bash(git push --force:*)",
        |      "Bash(git push -f:*)",
        |      "Bash(git push --force-with-lease:*)",
        |      "Bash(git push --no-verify:*)",
        |      "Bash(gh repo delete:*)",
        |      "Bash(gh auth token:*)"
        |    ]
        |  }
        |}
        |POLICY
        |}
        |
        |# Codex's own command-line tool, from its npm package. "codex login --device-auth" signs in
        |# with a code where the panel's ChatGPT button cannot: its page returns to localhost, which
        |# a phone never reaches. The tool and the panel share the sign-in.
        |install_codex() {
        |  command -v codex >/dev/null 2>&1 && return 0
        |  npm install -g @openai/codex >/dev/null
        |}
        |
        |# Google Chrome, which the agents' browser tools open in a window on the desktop.
        |install_chrome() {
        |  command -v google-chrome >/dev/null 2>&1 && return 0
        |  npx -y playwright@latest install --with-deps chrome >/dev/null
        |}
        |
        |# Two browser tools (MCP servers) for every agent: Playwright to use a site or app like a
        |# person, Chrome DevTools for its console, network and speed. Both open Chrome on the
        |# desktop (display :1), so the owner can watch. Entries the owner already has stay.
        |browser_tools() {
        |  if command -v claude >/dev/null 2>&1; then
        |    claude mcp get playwright >/dev/null 2>&1 ||
        |      claude mcp add --scope user playwright --env DISPLAY=:1 -- npx -y @playwright/mcp@latest --caps=devtools >/dev/null
        |    claude mcp get chrome-devtools >/dev/null 2>&1 ||
        |      claude mcp add --scope user chrome-devtools --env DISPLAY=:1 -- npx -y chrome-devtools-mcp@latest --no-usage-statistics >/dev/null
        |  fi
        |  if command -v codex >/dev/null 2>&1; then
        |    codex mcp get playwright >/dev/null 2>&1 ||
        |      codex mcp add playwright --env DISPLAY=:1 -- npx -y @playwright/mcp@latest --caps=devtools >/dev/null
        |    codex mcp get chrome-devtools >/dev/null 2>&1 ||
        |      codex mcp add chrome-devtools --env DISPLAY=:1 -- npx -y chrome-devtools-mcp@latest --no-usage-statistics >/dev/null
        |  fi
        |  local gemini="${'$'}HOME/.gemini/config/mcp_config.json"
        |  [ -e "${'$'}gemini" ] && return 0
        |  mkdir -p "${'$'}(dirname "${'$'}gemini")"
        |  cat > "${'$'}gemini" <<'GEMINI'
        |{
        |  "mcpServers": {
        |    "playwright": {"command": "npx", "args": ["-y", "@playwright/mcp@latest", "--caps=devtools"], "env": {"DISPLAY": ":1"}},
        |    "chrome-devtools": {"command": "npx", "args": ["-y", "chrome-devtools-mcp@latest", "--no-usage-statistics"], "env": {"DISPLAY": ":1"}}
        |  }
        |}
        |GEMINI
        |}
        |
        |# The same few lines for all three agents, in the file of instructions each one always
        |# reads. Only the part between PocketIDE's two marker lines is ever rewritten.
        |NOTE_START="<!-- PocketIDE: start -->"
        |NOTE_END="<!-- PocketIDE: end -->"
        |NOTE="${'$'}(cat <<'TEXT'
        |## Working with PocketIDE
        |- The owner reads this on a phone. Keep replies short: a few lines, lists rather than tables.
        |- This is the owner's GitHub Codespace. Commit and push finished work; never force-push.
        |- A desktop runs here on display :1. When you test in a browser, keep it visible (not
        |  headless), so the owner can watch in PocketIDE (menu > Desktop). The playwright and
        |  chrome-devtools tools open Chrome there.
        |- Web apps: start the dev server and give its port; the owner opens it with menu > Web preview.
        |- Android apps: if /dev/kvm is missing, no emulator runs here. Test on GitHub Actions (its
        |  Linux runners have the emulator), and publish a debug APK as a GitHub pre-release, so the
        |  owner can install it on the phone.
        |TEXT
        |)"
        |
        |instructions() {
        |  local file
        |  for file in "${'$'}HOME/.claude/CLAUDE.md" "${'$'}HOME/.codex/AGENTS.md" "${'$'}HOME/.gemini/GEMINI.md"; do
        |    mkdir -p "${'$'}(dirname "${'$'}file")" && touch "${'$'}file" || continue
        |    {
        |      sed "/${'$'}NOTE_START/,/${'$'}NOTE_END/d" "${'$'}file"
        |      printf '%s\n%s\n%s\n' "${'$'}NOTE_START" "${'$'}NOTE" "${'$'}NOTE_END"
        |    } > "${'$'}file.pocketide" && mv -f "${'$'}file.pocketide" "${'$'}file"
        |  done
        |}
        |
        |run() {
        |  "${'$'}@" || echo "PocketIDE set-up: ${'$'}1 did not finish; the computer works without it." >&2
        |}
        |
        |case "${'$'}{1:-start}" in
        |  create) run guard_claude; run install_codex; run install_chrome ;;
        |  start) run put_back_settings; run guard_pushes; run browser_tools; run instructions ;;
        |  *) echo "usage: setup.sh create|start" >&2 ;;
        |esac
        |exit 0
        |
    """.trimMargin()
}

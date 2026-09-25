package com.pocketide.sessions

import com.pocketide.sessions.transcripts.clip
import com.pocketide.sessions.transcripts.oneLine

/**
 * The first message for an agent that takes over a session: what the owner asked for, what
 * changed so far, where the previous agent stopped and what is left. Plain text, so every agent
 * reads it the same way.
 */
internal object HandOffNote {
    private const val FILES_SHOWN = 20
    private const val GOAL_CHARS = 600
    private const val LAST_STEP_CHARS = 800

    fun build(fromAgent: String, goal: String, files: List<ChangedFile>, lastStep: String?, leftBehind: Boolean): String = buildString {
        appendLine("You are taking over a session that $fromAgent started in this project. This branch starts at its last commit.")
        appendLine()
        appendLine("Goal: ${oneLine(goal, GOAL_CHARS)}")
        appendLine()
        if (files.isEmpty()) {
            appendLine("Files changed so far: none yet.")
        } else {
            appendLine("Files changed so far:")
            files.take(FILES_SHOWN).forEach { appendLine("- ${it.path} (+${it.added} -${it.removed})") }
            if (files.size > FILES_SHOWN) appendLine("- and ${files.size - FILES_SHOWN} more")
        }
        if (!lastStep.isNullOrBlank()) {
            appendLine()
            appendLine("Where $fromAgent stopped: ${clip(lastStep, LAST_STEP_CHARS)}")
        }
        if (leftBehind) {
            appendLine()
            appendLine("$fromAgent also left changes that were not committed. They stay in its own folder and are not on this branch.")
        }
        appendLine()
        append("What is left: finish the goal. Check the files above, run the tests, and commit your work to this branch.")
    }
}

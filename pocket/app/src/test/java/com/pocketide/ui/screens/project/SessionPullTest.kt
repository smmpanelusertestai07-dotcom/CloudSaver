package com.pocketide.ui.screens.project

import com.pocketide.github.PullRequest
import com.pocketide.github.WorkflowRun
import com.pocketide.ui.components.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionPullTest {
    private fun pull(state: String, merged: Boolean = false, mergeable: Boolean? = true) =
        PullRequest(5, "https://github.com/octo/app/pull/5", state, merged, mergeable)

    private fun run(id: Long, branch: String) = WorkflowRun(
        id = id, name = "Android", branch = branch, status = "completed", conclusion = "success",
        createdAt = "2026-09-25T10:00:00Z", updatedAt = "2026-09-25T10:05:00Z", htmlUrl = "https://github.com/octo/app/actions/runs/$id",
    )

    @Test fun `a session's pull request reads as open, conflicting, merged or closed`() {
        assertEquals("Open" to Tone.OK, pullStatus(pull("open")))
        assertEquals("Open" to Tone.OK, pullStatus(pull("open", mergeable = null)))
        assertEquals("Open · has conflicts" to Tone.WARN, pullStatus(pull("open", mergeable = false)))
        assertEquals("Merged" to Tone.OK, pullStatus(pull("closed", merged = true)))
        assertEquals("Closed without merging" to Tone.NEUTRAL, pullStatus(pull("closed")))
    }

    @Test fun `the checks shown are the newest run on the session's own branch`() {
        val runs = listOf(run(3, "main"), run(2, "pocket/claude/login"), run(1, "pocket/claude/login"))
        assertEquals(2L, latestRunOn(runs, "pocket/claude/login")?.id)
        assertNull(latestRunOn(runs, "pocket/codex/other"))
    }
}

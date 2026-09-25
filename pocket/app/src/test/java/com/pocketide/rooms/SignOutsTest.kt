package com.pocketide.rooms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignOutsTest {
    @Test fun `each agent signs out with the CLI of its newest extension`() {
        val folders = listOf(
            "anthropic.claude-code-2.1.99-linux-arm64",
            "anthropic.claude-code-2.1.281-linux-arm64",
            "openai.chatgpt-26.908.40401-linux-arm64",
            "pocketide.pocketide-companion-3.0.0",
        )
        assertEquals(
            listOf("/root/.local/share/code-server/extensions/anthropic.claude-code-2.1.281-linux-arm64/resources/native-binary/claude", "auth", "logout"),
            SignOuts.command("claude", folders),
        )
        assertEquals(
            listOf("/root/.local/share/code-server/extensions/openai.chatgpt-26.908.40401-linux-arm64/bin/linux-aarch64/codex", "logout"),
            SignOuts.command("codex", folders),
        )
        assertNull("Antigravity has no sign-out to run", SignOuts.command("antigravity", folders))
        assertNull("nothing installed, nothing to run", SignOuts.command("codex", emptyList()))
    }

    @Test fun `signed in means the agent's own sign-in file is there`() {
        assertEquals(".claude/.credentials.json", SignOuts.signInFile("claude"))
        assertEquals(".codex/auth.json", SignOuts.signInFile("codex"))
        assertNull(SignOuts.signInFile("antigravity"))
    }
}

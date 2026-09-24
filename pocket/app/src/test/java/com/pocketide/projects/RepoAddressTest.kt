package com.pocketide.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepoAddressTest {
    private val demo = RepoAddress("alice", "demo")

    @Test
    fun `every form people paste reads as owner and name`() {
        listOf(
            "alice/demo",
            "  alice/demo/  ",
            "https://github.com/alice/demo",
            "https://www.github.com/alice/demo.git",
            "http://github.com/alice/demo/",
            "github.com/alice/demo",
            "www.github.com/alice/demo",
            "git@github.com:alice/demo.git",
            "ssh://git@github.com/alice/demo.git",
            "https://github.com/alice/demo?tab=readme-ov-file",
            "https://github.com/alice/demo#readme",
            "https://github.com/alice/demo/tree/main/src",
            "HTTPS://GITHUB.COM/alice/demo",
        ).forEach { assertEquals(it, demo, RepoAddress.parse(it)) }
    }

    @Test
    fun `other hosts and names github would refuse are not addresses`() {
        listOf(
            "https://gitlab.com/alice/demo",
            "gitlab.com/alice/demo",
            "git@bitbucket.org:alice/demo.git",
            "https://github.com/alice",
            "alice",
            "",
            "alice/..",
            "alice/.",
            "-alice/demo",
            "al ice/demo",
            "alice/de mo",
            "alice/${"x".repeat(101)}",
        ).forEach { assertNull(it, RepoAddress.parse(it)) }
    }

    @Test
    fun `dots, underscores and hyphens are fine in names`() {
        assertEquals(RepoAddress("my-org", "my_app.v2"), RepoAddress.parse("https://github.com/my-org/my_app.v2.git"))
    }
}

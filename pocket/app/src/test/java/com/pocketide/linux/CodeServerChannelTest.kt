package com.pocketide.linux

import com.pocketide.github.PublicRelease
import com.pocketide.github.ReleaseAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodeServerChannelTest {
    private val floor = CodeServerPin(
        version = "4.138.0",
        url = "https://github.com/coder/code-server/releases/download/v4.138.0/code-server-4.138.0-linux-arm64.tar.gz",
        sha256 = "a".repeat(64),
        bytes = 225_000_000,
    )
    private val digest = "b".repeat(64)

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        sha256: String? = digest,
        bytes: Long = 226_000_000,
        url: ((String) -> String)? = null,
    ): PublicRelease {
        val version = tag.removePrefix("v")
        val name = "code-server-$version-linux-arm64.tar.gz"
        val download = url?.invoke(name) ?: "https://github.com/coder/code-server/releases/download/$tag/$name"
        val assets = listOf(
            ReleaseAsset("code-server-$version-linux-amd64.tar.gz", bytes, download.replace("arm64", "amd64"), "c".repeat(64)),
            ReleaseAsset(name, bytes, download, sha256),
        )
        return PublicRelease(tag, "", "2026-10-01T00:00:00Z", prerelease, assets)
    }

    @Test fun `a newer stable release becomes the pin, with GitHub's digest and size`() {
        val pin = CodeServerChannel.newer(release("v4.139.1"), floor)
        assertEquals(
            CodeServerPin(
                version = "4.139.1",
                url = "https://github.com/coder/code-server/releases/download/v4.139.1/code-server-4.139.1-linux-arm64.tar.gz",
                sha256 = digest,
                bytes = 226_000_000,
            ),
            pin,
        )
    }

    @Test fun `the pin stays when the release is not newer`() {
        assertNull(CodeServerChannel.newer(release("v4.138.0"), floor))
        assertNull(CodeServerChannel.newer(release("v4.137.2"), floor))
    }

    @Test fun `pre-releases and a new major version wait for an app update`() {
        assertNull(CodeServerChannel.newer(release("v4.140.0", prerelease = true), floor))
        assertNull(CodeServerChannel.newer(release("v4.140.0-rc.1"), floor))
        assertNull(CodeServerChannel.newer(release("v5.0.0"), floor))
    }

    @Test fun `a release without a usable digest, address or size is never used`() {
        assertNull("no digest", CodeServerChannel.newer(release("v4.140.0", sha256 = null), floor))
        assertNull("not a SHA-256", CodeServerChannel.newer(release("v4.140.0", sha256 = "b".repeat(63)), floor))
        assertNull("elsewhere", CodeServerChannel.newer(release("v4.140.0", url = { "https://example.com/$it" }), floor))
        assertNull("empty", CodeServerChannel.newer(release("v4.140.0", bytes = 0), floor))
        assertNull("absurd", CodeServerChannel.newer(release("v4.140.0", bytes = 5_000_000_000), floor))
        assertNull("not a version", CodeServerChannel.newer(release("nightly"), floor))
    }
}

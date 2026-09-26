package com.pocketide.agents

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl
import okio.Buffer
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One published version of a pretend extension, with the package it serves. */
class FakeVersion(
    val version: String,
    val engine: String = "^1.94.0",
    val preRelease: Boolean = false,
    val verified: Boolean = true,
    val timestamp: String = "2026-09-01T00:00:00Z",
    /** The package.json inside the .vsix (and served as its manifest). */
    val packageJson: String,
    /** Serve a checksum that does not match the package. */
    val wrongChecksum: Boolean = false,
    /** Serve a signature made by another key. */
    val wrongSignature: Boolean = false,
)

class FakeExtension(
    val namespace: String,
    val name: String,
    val target: String = "linux-arm64",
    val verified: Boolean = true,
    val downloads: Long = 1_000_000,
    val categories: List<String> = listOf("AI"),
    val license: String? = "MIT",
    val deprecated: Boolean = false,
    val versions: MutableList<FakeVersion> = mutableListOf(),
) {
    val id get() = "$namespace.$name".lowercase()
}

/**
 * A pretend Open VSX on a MockWebServer: search, per-extension and per-version answers, the
 * all-versions query, and each version's .vsix, .sha256, .sigzip and manifest, signed with a
 * real Ed25519 key made for the test.
 */
class OpenVsxFixture {
    val extensions = mutableListOf<FakeExtension>()
    val requests = mutableListOf<String>()
    private val key: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val otherKey: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    lateinit var base: HttpUrl

    fun add(extension: FakeExtension): FakeExtension = extension.also { extensions += it }

    val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val url = request.url
            requests += url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
            val segments = url.pathSegments
            if (segments.firstOrNull() != "api") return notFound()
            val rest = segments.drop(1)
            return when {
                rest == listOf("-", "search") -> json(search(url))
                rest == listOf("-", "query") -> json(query(url))
                rest == listOf("-", "public-key") -> text(pem())
                rest.size >= 2 -> extensionAnswer(rest)
                else -> notFound()
            } ?: notFound()
        }
    }

    fun vsixBytes(version: FakeVersion): ByteArray = zip(mapOf("extension/package.json" to version.packageJson.toByteArray()))

    private fun extensionAnswer(rest: List<String>): MockResponse? {
        val extension = extensions.firstOrNull { it.namespace.equals(rest[0], true) && it.name.equals(rest[1], true) } ?: return null
        val tail = rest.drop(2)
        val fileAt = tail.indexOf("file")
        if (fileAt >= 0) {
            val version = extension.versions.firstOrNull { it.version == tail[fileAt - 1] } ?: return null
            return file(extension, version, tail[fileAt + 1])
        }
        return when (tail.size) {
            0 -> json(details(extension, newest(extension) ?: return null))
            2 -> {
                if (tail[0] != extension.target) return null
                val version = if (tail[1] == "latest") newest(extension) else extension.versions.firstOrNull { it.version == tail[1] }
                json(details(extension, version ?: return null))
            }
            else -> null
        }
    }

    private fun newest(extension: FakeExtension) = extension.versions.maxByOrNull { SemVer.parse(it.version)!! }

    private fun search(url: HttpUrl): JsonObject {
        val category = url.queryParameter("category")
        val offset = url.queryParameter("offset")?.toInt() ?: 0
        val rows = extensions.filter { category in it.categories }.sortedByDescending { it.downloads }
        return buildJsonObject {
            put("offset", offset)
            put("totalSize", rows.size)
            put("extensions", JsonArray(rows.drop(offset).map { row(it) }))
        }
    }

    private fun row(extension: FakeExtension) = buildJsonObject {
        put("namespace", extension.namespace)
        put("name", extension.name)
        put("version", newest(extension)?.version ?: "0.0.0")
        put("verified", extension.verified)
        put("downloadCount", extension.downloads)
        put("displayName", extension.name.replaceFirstChar { it.uppercase() })
        put("deprecated", extension.deprecated)
    }

    private fun query(url: HttpUrl): JsonObject {
        val extension = extensions.firstOrNull {
            it.namespace.equals(url.queryParameter("namespaceName"), true) && it.name.equals(url.queryParameter("extensionName"), true)
        }
        val all = extension?.versions?.sortedByDescending { SemVer.parse(it.version)!! }.orEmpty()
        val offset = url.queryParameter("offset")?.toInt() ?: 0
        val size = url.queryParameter("size")?.toInt() ?: all.size
        val page = all.drop(offset).take(size)
        return buildJsonObject {
            put("totalSize", all.size)
            put("extensions", JsonArray(page.map { details(extension!!, it) }))
        }
    }

    private fun details(extension: FakeExtension, version: FakeVersion): JsonObject {
        val files = fileBase(extension, version)
        return buildJsonObject {
            put("namespace", extension.namespace)
            put("name", extension.name)
            put("version", version.version)
            put("targetPlatform", extension.target)
            put("preRelease", version.preRelease)
            put("timestamp", version.timestamp)
            put("verified", version.verified && extension.verified)
            put("downloadable", true)
            put("deprecated", extension.deprecated)
            put("displayName", extension.name.replaceFirstChar { it.uppercase() })
            put("namespaceDisplayName", extension.namespace)
            put("description", "An agent")
            put("downloadCount", extension.downloads)
            put("categories", buildJsonArray { extension.categories.forEach { add(JsonPrimitive(it)) } })
            extension.license?.let { put("license", it) }
            put("repository", "https://github.com/${extension.namespace}/${extension.name}")
            put("engines", buildJsonObject { put("vscode", version.engine) })
            put("downloads", buildJsonObject { put(extension.target, "$files/${vsixName(extension, version)}") })
            put(
                "files",
                buildJsonObject {
                    put("download", "$files/${vsixName(extension, version)}")
                    put("sha256", "$files/${vsixName(extension, version)}.sha256")
                    put("signature", "$files/${vsixName(extension, version)}.sigzip")
                    put("publicKey", base.resolve("/api/-/public-key").toString())
                    put("manifest", "$files/package.json")
                },
            )
        }
    }

    private fun fileBase(extension: FakeExtension, version: FakeVersion) =
        base.resolve("/api/${extension.namespace}/${extension.name}/${extension.target}/${version.version}/file").toString()

    private fun vsixName(extension: FakeExtension, version: FakeVersion) =
        "${extension.namespace}.${extension.name}-${version.version}@${extension.target}.vsix"

    private fun file(extension: FakeExtension, version: FakeVersion, name: String): MockResponse? {
        val vsix = vsixBytes(version)
        return when (name) {
            vsixName(extension, version) -> MockResponse.Builder().body(Buffer().write(vsix)).build()
            "${vsixName(extension, version)}.sha256" -> text(if (version.wrongChecksum) sha256(byteArrayOf(1)) else sha256(vsix))
            "${vsixName(extension, version)}.sigzip" -> MockResponse.Builder().body(Buffer().write(sigzip(sign(vsix, if (version.wrongSignature) otherKey else key)))).build()
            "package.json" -> text(version.packageJson)
            else -> null
        }
    }

    private fun pem(): String = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder().encodeToString(key.public.encoded) + "\n-----END PUBLIC KEY-----\n"

    companion object {
        private const val ZIP_TIME = 1_700_000_000_000L

        fun sign(bytes: ByteArray, key: KeyPair): ByteArray =
            Signature.getInstance("Ed25519").apply { initSign(key.private); update(bytes) }.sign()

        fun sigzip(signature: ByteArray): ByteArray = zip(mapOf(".signature.manifest" to "{}".toByteArray(), ".signature.sig" to signature, ".signature.p7s" to ByteArray(0)))

        fun zip(entries: Map<String, ByteArray>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                for ((name, bytes) in entries) {
                    // A fixed time: the same entries always make the same bytes (and checksum).
                    zip.putNextEntry(ZipEntry(name).apply { time = ZIP_TIME })
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun sha512(bytes: ByteArray): String = MessageDigest.getInstance("SHA-512").digest(bytes).joinToString("") { "%02x".format(it) }

        /** A package.json with the given commands and, when [screen], a webview agent screen `<name>.chat`. */
        fun packageJson(
            publisher: String,
            name: String,
            version: String,
            engine: String = "^1.94.0",
            commands: List<String> = emptyList(),
            screen: Boolean = true,
        ): String {
            val view = if (screen) "webview" else "tree"
            val json = buildJsonObject {
                put("publisher", publisher)
                put("name", name)
                put("version", version)
                put("engines", buildJsonObject { put("vscode", engine) })
                put(
                    "contributes",
                    buildJsonObject {
                        put("commands", JsonArray(commands.map { buildJsonObject { put("command", it) } }))
                        put(
                            "viewsContainers",
                            buildJsonObject { put("activitybar", buildJsonArray { add(buildJsonObject { put("id", "$name-side") }) }) },
                        )
                        put(
                            "views",
                            buildJsonObject {
                                put("$name-side", buildJsonArray { add(buildJsonObject { put("id", "$name.chat"); put("type", view) }) })
                            },
                        )
                    },
                )
            }
            return json.toString()
        }

        private fun json(body: JsonObject) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body.toString()).build()

        private fun text(body: String) = MockResponse.Builder().body(body).build()

        private fun notFound() = MockResponse.Builder().code(404).body("{\"error\":\"Not found\"}").build()
    }
}

package com.pocketide.git

import com.pocketide.core.AgentFiles
import com.pocketide.core.AppJson
import com.pocketide.core.FileClass
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * What a path alone says, whatever the file holds. Project instruction files are never matched.
 * A copy of a home folder in the repo (dotfiles, or a backup of a room) is judged by
 * [AgentFiles], the list that also decides what sync uploads and what Your data shows.
 */
internal object PathRules {
    class Hit(val kind: FindingKind, val detail: String)

    // Written by Claude Code in its home, ~/.claude. A project's own .claude/ holds settings,
    // commands, agents, skills and hooks, which belong in the repo.
    private val claudeHomeData = setOf(
        "projects", "todos", "statsig", "shell-snapshots", "file-history", "session-env", "sessions",
        "paste-cache", "image-cache", "uploads", "backups", "feedback-bundles", "usage-data", "debug",
        "agent-memory-local", "jobs", "daemon", "history.jsonl", "stats-cache.json",
    )

    // Antigravity keeps its conversations under ~/.gemini/antigravity (older builds: ~/.gemini/jetski).
    private val antigravityStores =
        setOf("brain", "conversations", "implicit", "annotations", "browser_recordings", "code_tracker")

    private val keystoreExtensions = listOf(".jks", ".keystore", ".p12", ".pfx")
    private val sshKeys = setOf("id_rsa", "id_dsa", "id_ecdsa", "id_ed25519", "id_ecdsa_sk", "id_ed25519_sk")
    private val envTemplates = setOf("example", "sample", "template")

    // Where a home's credentials sit. The project folders .claude, .github and the like are left
    // out: AgentFiles' name patterns (…credential…, …token) are meant for a home and would catch
    // a project's own hooks and scripts.
    private val credentialHomes = setOf(".config", ".ssh", ".gnupg", ".local", ".git-credentials", ".netrc")
    private val agentHomes = setOf(".claude", ".codex", ".gemini")

    // Claude Code reads a project's own instructions from these too.
    private val projectInstructions = listOf(Regex("^\\.claude/CLAUDE\\.md$"), Regex("^\\.claude/rules/[^/]+\\.md$"))

    const val CLAUDE_DATA =
        "Claude Code's own data (chats, history or snapshots). It is kept in your Drive, never on GitHub."
    const val CLAUDE_SIGN_IN = "Claude Code sign-in data. It never leaves the phone."
    const val CODEX_DATA = "Codex's own data (chats, sign-in or history). It never goes to GitHub."
    const val GEMINI_DATA = "Gemini or Antigravity data (chats, sign-in or history). It never goes to GitHub."
    const val ANTIGRAVITY_DATA = "Antigravity chat data. It is kept in your Drive, never on GitHub."
    const val GOOGLE_SIGN_IN = "A Google sign-in file. It never leaves the phone."
    const val ENV_FILE = "A .env file, which holds secrets. Keep it out of git (list it in .gitignore)."
    const val KEYSTORE = "A signing keystore. Keep it out of git; builds get it from GitHub Secrets."
    const val SSH_KEY = "An SSH private key."
    const val HOME_CREDENTIAL = "A sign-in or key file from a home folder. It never leaves the phone."
    const val AGENT_DATA = "An AI agent's own data (chats or memory). It is kept in your Drive, never on GitHub."

    fun check(path: String): Hit? {
        val parts = path.split('/')
        aiData(parts)?.let { return Hit(FindingKind.AI_DATA, it) }
        if (isSyncedAgentData(parts)) return Hit(FindingKind.AI_DATA, AGENT_DATA)
        secretFile(parts.last())?.let { return Hit(FindingKind.SECRET, it) }
        if (isHomeCredential(parts)) return Hit(FindingKind.SECRET, HOME_CREDENTIAL)
        return null
    }

    /** The path from each dot folder on, as if that folder sat in a home. */
    private fun homeSuffixes(parts: List<String>, roots: Set<String>): Sequence<String> =
        parts.indices.asSequence().filter { parts[it] in roots }.map { parts.subList(it, parts.size).joinToString("/") }

    private fun isSyncedAgentData(parts: List<String>): Boolean =
        homeSuffixes(parts, agentHomes).any { suffix ->
            AgentFiles.classify(suffix) == FileClass.SYNC && projectInstructions.none { it.matches(suffix) }
        }

    private fun isHomeCredential(parts: List<String>): Boolean =
        homeSuffixes(parts, credentialHomes).any(AgentFiles::isSecret)

    private fun aiData(parts: List<String>): String? {
        val name = parts.last()
        if (name == ".claude.json" || name.startsWith(".claude.json.")) return CLAUDE_SIGN_IN
        if (name == "oauth_creds.json") return GOOGLE_SIGN_IN
        for (i in 0 until parts.size - 1) {
            val next = parts[i + 1]
            val nextIsFile = i + 1 == parts.size - 1
            when (parts[i]) {
                ".claude" -> when {
                    next == ".credentials.json" -> return CLAUDE_SIGN_IN
                    next in claudeHomeData -> return CLAUDE_DATA
                }
                ".codex" -> return CODEX_DATA
                ".gemini" -> if (!(nextIsFile && next == "settings.json")) return GEMINI_DATA
                "antigravity", "jetski" -> if (!nextIsFile && next in antigravityStores) return ANTIGRAVITY_DATA
                // Antigravity's per-conversation logs, wherever a copy of its store ends up.
                ".system_generated" -> return ANTIGRAVITY_DATA
            }
        }
        return null
    }

    private fun secretFile(name: String): String? {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            (lower == ".env" || lower.startsWith(".env.")) && lower.substringAfterLast('.') !in envTemplates -> ENV_FILE
            keystoreExtensions.any { lower.endsWith(it) } -> KEYSTORE
            lower in sshKeys -> SSH_KEY
            else -> null
        }
    }
}

/**
 * Build outputs: they are kept in the session's Media, and builds come from GitHub Actions or
 * the phone, never from files in git. Libraries a project vendors on purpose (.jar, .so, .dll)
 * are not matched: Gradle's wrapper and Android's jniLibs are committed by design.
 */
internal object BuildOutputs {
    private val extensions = setOf("apk", "aab", "apks", "xapk", "ipa", "dex", "class", "o", "exe", "dmg", "msi")
    private val gradleOutputs = setOf("outputs", "intermediates", "tmp")

    fun check(path: String): String? {
        val parts = path.split('/')
        val extension = parts.last().substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension in extensions) return "A build output (.$extension). Builds are kept in Media, not in git."
        return if (inBuildFolder(parts.dropLast(1))) "A file from a build folder. Builds are kept in Media, not in git." else null
    }

    private fun inBuildFolder(folders: List<String>): Boolean = folders.indices.any { i ->
        val name = folders[i]
        name == ".gradle" || name == "DerivedData" || (name == "build" && folders.getOrNull(i + 1) in gradleOutputs)
    }
}

/** Token shapes the check-post looks for in the lines a commit adds. */
internal object SecretPatterns {
    private class Shape(
        val detail: String,
        /** Cheap substrings, one of which any match contains; most text has none of them. */
        val hints: List<String>,
        val regex: Regex,
        /** File names (lower case) where this shape is expected and harmless. */
        val exemptFiles: Set<String> = emptySet(),
    )

    private val shapes = listOf(
        Shape(
            "Contains a GitHub token.",
            listOf("ghp_", "gho_", "ghu_", "ghs_", "ghr_", "github_pat_"),
            Regex("""\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})"""),
        ),
        Shape(
            "Contains a Google API key.",
            listOf("AIza"),
            Regex("""(?<![A-Za-z0-9_-])AIza[A-Za-z0-9_-]{35}(?![A-Za-z0-9_-])"""),
            // Firebase's config files carry the app's API key by design: it identifies the app and
            // is restricted in the Google Cloud console, and projects commit these files.
            exemptFiles = setOf("google-services.json", "googleservice-info.plist"),
        ),
        Shape("Contains a Google sign-in token.", listOf("ya29."), Regex("""\bya29\.[A-Za-z0-9_-]{30,}""")),
        Shape("Contains a Slack token.", listOf("xox"), Regex("""\bxox[abprs]-[A-Za-z0-9-]{10,}""")),
        Shape("Contains a Stripe live key.", listOf("_live_"), Regex("""\b[rs]k_live_[A-Za-z0-9]{20,}""")),
        Shape("Contains an Anthropic API key.", listOf("sk-ant-"), Regex("""\bsk-ant-[A-Za-z0-9_-]{20,}""")),
        Shape(
            "Contains an OpenAI API key.",
            listOf("sk-"),
            Regex("""\bsk-(?:(?:proj|svcacct|admin)-[A-Za-z0-9_-]{20,}|[A-Za-z0-9]{32,})"""),
        ),
        Shape("Contains an npm token.", listOf("npm_"), Regex("""\bnpm_[A-Za-z0-9]{36,}""")),
        Shape("Contains a PyPI token.", listOf("pypi-"), Regex("""\bpypi-[A-Za-z0-9_-]{50,}""")),
        Shape("Contains an age secret key.", listOf("AGE-SECRET-KEY-1"), Regex("""AGE-SECRET-KEY-1[0-9A-Z]{50,}""")),
        Shape("Contains a Hugging Face token.", listOf("hf_"), Regex("""\bhf_[A-Za-z0-9]{30,}""")),
        Shape("Contains a Twilio API key.", listOf("SK"), Regex("""\bSK[0-9a-fA-F]{32}\b""")),
        Shape(
            "Contains a private key.",
            listOf("PRIVATE KEY"),
            Regex("""-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?-----"""),
        ),
    )

    private const val AWS_KEY = "Contains an AWS access key."
    private val awsKeyId = Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b""")
    private val awsSecret = Regex("""(?<![A-Za-z0-9/+=])[A-Za-z0-9/+]{40}(?![A-Za-z0-9/+=])""")
    private val awsNamedSecret =
        Regex("""(?i)(?:aws_?secret_?(?:access_?)?key|secretaccesskey)\W{0,4}[A-Za-z0-9/+]{40}(?![A-Za-z0-9/+=])""")

    /** How far from a key ID its secret usually sits: the same or a neighbouring line. */
    private const val AWS_NEARBY_CHARS = 400

    private const val REGISTRY_PASSWORD = "Contains a package registry password or token."
    private val registryFiles = setOf(".npmrc", ".pypirc")

    // `_authToken=${NPM_TOKEN}` reads the token from the environment and is the safe way.
    private val registryPassword =
        Regex("""(?im)^\s*(?:[^\s=#;]*:)?(?:_?password|_auth|_authtoken)\s*[=:]\s*(?!\$\{)[^\s#;]+""")

    /** Plain details of every kind of secret in [text], a file named [fileName] (or a commit message). */
    fun find(fileName: String, text: String): List<String> {
        val name = fileName.lowercase(Locale.ROOT)
        val found = LinkedHashSet<String>()
        for (shape in shapes) {
            if (name in shape.exemptFiles || shape.hints.none(text::contains)) continue
            if (shape.regex.containsMatchIn(text)) found += shape.detail
        }
        if (hasAwsKey(text)) found += AWS_KEY
        if (name in registryFiles && registryPassword.containsMatchIn(text)) found += REGISTRY_PASSWORD
        return found.toList()
    }

    private fun hasAwsKey(text: String): Boolean {
        if (awsNamedSecret.containsMatchIn(text)) return true
        return awsKeyId.findAll(text)
            // AWS's documentation uses this key ID in every example.
            .filterNot { it.value.endsWith("EXAMPLE") }
            .any { keyId ->
                val from = maxOf(0, keyId.range.first - AWS_NEARBY_CHARS)
                val to = minOf(text.length, keyId.range.last + 1 + AWS_NEARBY_CHARS)
                awsSecret.findAll(text.substring(from, to)).any { looksRandom(it.value) }
            }
    }

    // A real secret key mixes digits and both cases; this keeps out words and git commit IDs.
    private fun looksRandom(candidate: String) =
        candidate.any(Char::isDigit) && candidate.any(Char::isUpperCase) && candidate.any(Char::isLowerCase)
}

/** Claude Code and Codex keep chats as JSON Lines whose records are easy to recognise. */
internal object Transcripts {
    const val DETAIL = "An AI chat transcript. Chats are kept in your Drive, never on GitHub."

    /** Enough for the first records; one holding a pasted image can be far longer. */
    const val HEAD_BYTES = 1 shl 20

    /** A transcript can open with summary lines before the first message. */
    private const val MAX_RECORDS = 20

    private val codexType = Regex(""""type"\s*:\s*"(?:session_meta|response_item)"""")
    private val claudeType = Regex(""""type"\s*:\s*"(?:user|assistant)"""")
    private val claudeSession = Regex(""""sessionId"\s*:\s*"""")

    fun applies(path: String): Boolean = path.endsWith(".jsonl", ignoreCase = true)

    /** Whether one of the first records in [head] is a Claude Code or Codex transcript record. */
    fun found(head: String): Boolean =
        head.lineSequence().filter(String::isNotBlank).take(MAX_RECORDS).any(::isRecord)

    private fun isRecord(line: String): Boolean {
        val parsed = runCatching { AppJson.parseToJsonElement(line) }.getOrNull()
            // A line cut off at HEAD_BYTES does not parse; judge it by its fields instead.
            ?: return looksLikeRecord(line)
        val record = parsed as? JsonObject ?: return false
        val type = record.text("type")
        return type == "session_meta" || type == "response_item" ||
            ((type == "user" || type == "assistant") && record.text("sessionId") != null)
    }

    private fun looksLikeRecord(line: String): Boolean = line.trimStart().startsWith("{") &&
        (codexType.containsMatchIn(line) || (claudeType.containsMatchIn(line) && claudeSession.containsMatchIn(line)))

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/**
 * The project's Variables and Secrets, as the check-post looks for them. Values shorter than
 * [MIN_LENGTH] would match ordinary text and are skipped; a value spanning several lines is
 * looked for line by line.
 */
internal class KnownValues(values: List<String>) {
    private val needles: List<String> = values
        .flatMap { value -> value.lines().map(String::trim) }
        .filter { it.length >= MIN_LENGTH }
        .distinct()

    // The same needles as raw UTF-8 bytes, one char per byte, to search binary files.
    private val byteNeedles: List<String> by lazy {
        needles.map { String(it.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1) }
    }

    fun foundIn(text: String): Boolean = needles.any(text::contains)

    /** Whether [content] holds a value that [previous] (the file before the commit) did not. */
    fun newIn(content: ByteArray, previous: ByteArray?): Boolean {
        if (needles.isEmpty()) return false
        val now = String(content, Charsets.ISO_8859_1)
        val before = previous?.let { String(it, Charsets.ISO_8859_1) }
        return byteNeedles.any { now.contains(it) && (before == null || !before.contains(it)) }
    }

    companion object {
        const val MIN_LENGTH = 6
        const val DETAIL = "Contains the value of one of this project's Variables or Secrets."
    }
}

package com.pocketide.projects

import java.io.File
import java.io.IOException

/**
 * A bare repository's refs, read from the phone's side (loose ref files and `packed-refs`) without
 * starting Linux. One instance is one snapshot: create a new one after the repository changed.
 */
internal class BareRefs(private val bare: File) {

    /** Packed refs by full name, with the commit each points at. */
    private val packed: Map<String, String> by lazy { readPacked() }

    /** The clone exists and is complete (a clone in progress lives under another name). */
    fun isCloned(): Boolean = SafeFiles.isFile(File(bare, "HEAD")) && SafeFiles.isDirectory(File(bare, "objects"))

    fun exists(ref: String): Boolean = isSafe(ref) && (SafeFiles.isFile(File(bare, ref)) || ref in packed)

    /** The object [ref] points at, or null when it does not exist (symbolic refs are not followed). */
    fun sha(ref: String): String? {
        if (!isSafe(ref)) return null
        val loose = File(bare, ref)
        if (SafeFiles.isFile(loose)) {
            return try {
                loose.readText().trim().takeIf { SHA.matches(it) }
            } catch (unreadable: IOException) {
                null
            }
        }
        return packed[ref]
    }

    /** The first of [refs] that exists. */
    fun firstOf(vararg refs: String): String? = refs.firstOrNull { exists(it) }

    /** Full names of the refs under [prefix] (for example "refs/heads/"). */
    fun under(prefix: String): Set<String> {
        require(prefix.endsWith("/") && isSafe(prefix.trimEnd('/'))) { "Bad ref prefix" }
        val names = HashSet<String>()
        packed.keys.filterTo(names) { it.startsWith(prefix) }
        val root = File(bare, prefix)
        for (file in SafeFiles.files(root)) {
            val name = prefix + file.relativeTo(root).invariantSeparatorsPath
            if (!name.endsWith(".lock")) names += name
        }
        return names
    }

    /** Short names of the local branches. */
    fun localBranches(): List<String> = under(HEADS).map { it.removePrefix(HEADS) }.sorted()

    private fun readPacked(): Map<String, String> {
        val file = File(bare, "packed-refs")
        if (!SafeFiles.isFile(file)) return emptyMap()
        return try {
            file.readLines().asSequence()
                .filter { it.isNotEmpty() && it[0] != '#' && it[0] != '^' }
                .mapNotNull { line ->
                    val name = line.substringAfter(' ', "")
                    if (name.startsWith("refs/")) name to line.substringBefore(' ') else null
                }
                .toMap()
        } catch (unreadable: IOException) {
            emptyMap()
        }
    }

    private fun isSafe(ref: String) =
        ref.startsWith("refs/") && ref.split('/').none { it.isEmpty() || it == "." || it == ".." }

    companion object {
        const val HEADS = "refs/heads/"
        const val ORIGIN = "refs/remotes/origin/"
        private val SHA = Regex("[0-9a-f]{40}|[0-9a-f]{64}")
    }
}

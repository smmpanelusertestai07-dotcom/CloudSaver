package com.pocketide.vault

import com.pocketide.core.Ist

/** One key on this phone. */
internal class VaultKey(val generation: Int, val identity: AgeIdentity)

/**
 * "Save a key copy": every key this phone holds, newest first, in the identity-file format of
 * `age-keygen`, so the copy also works with the reference `age -d -i copy.txt`.
 */
internal object KeyCopy {
    class Copied(val generation: Int?, val identity: AgeIdentity)

    private val GENERATION = Regex("""\bgeneration (\d+)""")

    fun format(keys: List<VaultKey>, savedAt: Long): String = buildString {
        append("# PocketIDE key copy, saved ").append(Ist.dateTime(savedAt)).append(".\n")
        append("# Anyone who has this text can open your chats. Keep it private.\n")
        keys.forEachIndexed { index, key ->
            append("# generation ").append(key.generation)
            if (index == 0) append(" (current)")
            append(", public key ").append(key.identity.recipient.encoded()).append('\n')
            append(key.identity.encoded()).append('\n')
        }
    }

    /** Keys from a copy; a `# generation N` comment names the key on the next line. */
    fun parse(text: String): List<Copied> {
        val copied = ArrayList<Copied>()
        var generation: Int? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#") -> generation = GENERATION.find(line)?.groupValues?.get(1)?.toIntOrNull()
                else -> {
                    val identity = try {
                        AgeIdentity.parse(line)
                    } catch (e: IllegalArgumentException) {
                        throw VaultException(VaultText.NOT_A_KEY_COPY)
                    }
                    copied += Copied(generation, identity)
                    generation = null
                }
            }
        }
        if (copied.isEmpty()) throw VaultException(VaultText.NOT_A_KEY_COPY)
        return copied
    }
}

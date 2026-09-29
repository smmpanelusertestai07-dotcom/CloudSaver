package com.pocketide.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** One of the owner's keys: an environment variable every agent and terminal sees. */
@Serializable
data class Key(val name: String, val value: String)

/**
 * The owner's keys (API keys, tokens, any other variable), sealed on this phone with a key that
 * lives in the Android Keystore. code-server gets them as its environment when it starts, so the
 * agents' extensions, their command-line tools and the terminals all see them; they are never
 * written to a file inside Linux, and never leave the phone.
 */
class KeyStore(private val secure: SecureStore) {
    private val mutableKeys = MutableStateFlow(load())
    val keys: StateFlow<List<Key>> = mutableKeys.asStateFlow()

    /** Adds [name] or replaces its value. */
    @Synchronized
    fun put(name: String, value: String) {
        problem(name, value)?.let { throw IllegalArgumentException(it) }
        save(keys.value.filterNot { it.name == name } + Key(name, value))
    }

    @Synchronized
    fun remove(name: String) = save(keys.value.filterNot { it.name == name })

    /** Every key, for "Delete everything". */
    @Synchronized
    fun clear() = save(emptyList())

    fun environment(): Map<String, String> = keys.value.associate { it.name to it.value }

    private fun save(list: List<Key>) {
        val sorted = list.sortedBy { it.name }
        if (sorted.isEmpty()) {
            secure.delete(STORE)
        } else {
            secure.putString(STORE, AppJson.encodeToString(ListSerializer(Key.serializer()), sorted))
        }
        mutableKeys.value = sorted
    }

    private fun load(): List<Key> = secure.getString(STORE)
        ?.let { runCatching { AppJson.decodeFromString(ListSerializer(Key.serializer()), it) }.getOrNull() }
        .orEmpty()

    companion object {
        private const val STORE = "keys"
        private const val MAX_VALUE = 32 * 1024
        private val NAME = Regex("[A-Z_][A-Z0-9_]{0,63}")

        /** Names Linux itself sets for every program; a key may not replace them. */
        val RESERVED = setOf("HOME", "USER", "LOGNAME", "SHELL", "PATH", "TERM", "LANG", "TZ", "TMPDIR", "BROWSER", "PWD")

        /** Why [name] = [value] cannot be a key, in the owner's words; null when it can. */
        fun problem(name: String, value: String): String? = when {
            !NAME.matches(name) -> "Use capital letters, digits and _ only, starting with a letter or _ (like OPENAI_API_KEY)."
            name in RESERVED -> "$name is set by Linux itself."
            value.isEmpty() -> "The value is empty."
            value.length > MAX_VALUE -> "The value is too long."
            value.contains('\u0000') -> "The value contains a character a variable cannot hold."
            else -> null
        }
    }
}

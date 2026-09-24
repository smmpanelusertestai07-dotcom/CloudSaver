package com.pocketide.github

import com.pocketide.core.AppJson
import com.pocketide.core.SecureStore
import kotlinx.serialization.Serializable

/** The user's tokens and who they belong to, sealed with the Keystore key. Times are UTC epoch ms. */
@Serializable
internal data class StoredTokens(
    val access: String,
    /** Null when the App has token expiry switched off. */
    val accessExpiresAt: Long? = null,
    val refresh: String? = null,
    val refreshExpiresAt: Long? = null,
    val login: String,
    val id: Long,
    val name: String? = null,
    val avatar: String? = null,
) {
    fun account() = GitHubAccount(login, id, name, avatar)

    fun withAccount(account: GitHubAccount) = copy(login = account.login, id = account.id, name = account.name, avatar = account.avatarUrl)

    /** Renew a little early, so a token never expires in the middle of a clone or a push. */
    fun needsRefresh(now: Long): Boolean = accessExpiresAt != null && now >= accessExpiresAt - REFRESH_MARGIN_MS

    fun accessExpired(now: Long): Boolean = accessExpiresAt != null && now >= accessExpiresAt

    fun refreshExpired(now: Long): Boolean = refreshExpiresAt != null && now >= refreshExpiresAt

    // Tokens must never reach a log through a data class's generated toString.
    override fun toString() = "StoredTokens(login=$login)"

    companion object {
        const val REFRESH_MARGIN_MS = 5 * 60_000L
    }
}

/**
 * The tokens in [SecureStore], plus a marker that remembers "access was removed" across restarts,
 * so the lock keeps saying why until the owner reconnects or signs out.
 */
internal class TokenStore(private val store: SecureStore) {
    fun load(): StoredTokens? {
        val json = store.getString(TOKENS) ?: return null
        return runCatching { AppJson.decodeFromString(StoredTokens.serializer(), json) }.getOrNull()
    }

    fun save(tokens: StoredTokens) {
        store.putString(TOKENS, AppJson.encodeToString(StoredTokens.serializer(), tokens))
        store.delete(REVOKED)
    }

    fun forget(revoked: Boolean) {
        store.delete(TOKENS)
        if (revoked) store.putString(REVOKED, "1") else store.delete(REVOKED)
    }

    fun wasRevoked(): Boolean = store.has(REVOKED)

    private companion object {
        const val TOKENS = "github.tokens"
        const val REVOKED = "github.revoked"
    }
}

package com.pocketide.github

import com.pocketide.core.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import kotlin.math.ceil

/** Every sentence this module shows the owner, in one place. */
internal object GitHubText {
    const val NOT_CONFIGURED =
        "PocketIDE doesn't know your GitHub App yet, so GitHub cannot be connected. " +
            "Enter the App's client ID on the sign-in screen, or in Settings > GitHub App."
    const val NOT_CONNECTED = "GitHub is not connected. Sign in with GitHub again."
    const val ACCESS_REMOVED = "GitHub access was removed or has expired. Connect GitHub again."
    const val RENEW_FAILED = "GitHub sign-in could not be renewed. Try again in a minute."
    const val UNEXPECTED = "GitHub sent an answer PocketIDE did not expect. Try again in a minute."

    const val DEVICE_FLOW_OFF =
        "Device sign-in is switched off for your GitHub App. Tick \"Enable Device Flow\" in the App's settings on GitHub, then try again."
    const val BAD_CLIENT_ID = "GitHub does not know this App client ID. Copy it again from the App's page on GitHub."
    const val VERIFY_EMAIL = "GitHub needs your primary email verified first. Verify it on GitHub, then try again."
    const val SIGN_IN_FAILED = "GitHub sign-in did not finish. Try again."
    const val PROFILE_FAILED = "You approved PocketIDE, but GitHub did not answer afterwards. Try again."

    const val NOT_FOUND = "GitHub could not find it, or PocketIDE's GitHub App has no access to it."
    const val FORBIDDEN = "PocketIDE's GitHub App is not allowed to do this. Check the App's permissions on GitHub."
    const val CONFLICT = "It changed on GitHub in the meantime. Reload and try again."
    const val REJECTED = "GitHub did not accept that. Check the details and try again."
    const val NAME_TAKEN = "That name is already used on your GitHub account. Choose another name."
    const val SERVER = "GitHub is having trouble right now. Try again in a few minutes."
    const val OTHER = "GitHub answered with an error. Try again in a minute."
    const val API_RETIRED = "GitHub changed its API. Update PocketIDE to its newest version."
    const val IS_FOLDER = "That path is a folder on GitHub, not a file."
    const val TOO_BIG = "That file is too big to read through GitHub's API."
    const val BAD_NAME = "Use letters, numbers, dots, hyphens or underscores in GitHub names."
    const val BAD_PATH = "That file path is not valid."

    const val USAGE_UNAVAILABLE =
        "GitHub does not share this account's usage with apps. Open GitHub's billing page to see it."

    fun slowDown(waitMs: Long?): String {
        if (waitMs == null) return "GitHub asked PocketIDE to slow down. Try again in a minute."
        val minutes = ceil(waitMs / 60_000.0).toInt().coerceAtLeast(1)
        return if (minutes == 1) {
            "GitHub asked PocketIDE to slow down. Try again in a minute."
        } else {
            "GitHub asked PocketIDE to slow down. Try again in $minutes minutes."
        }
    }
}

/** Turns a GitHub error status and body into a [GitHubException] with a plain sentence. */
internal object GitHubErrors {
    fun of(status: Int, body: String): GitHubException = GitHubException(sentence(status, body), status)

    private fun sentence(status: Int, body: String): String = when {
        status == 404 -> GitHubText.NOT_FOUND
        status == 403 -> GitHubText.FORBIDDEN
        status == 409 -> GitHubText.CONFLICT
        ApiVersionChoice.refuses(status, body) -> GitHubText.API_RETIRED
        status == 422 && details(body).any { it.contains("already exists") } -> GitHubText.NAME_TAKEN
        status == 400 || status == 422 -> GitHubText.REJECTED
        status >= 500 -> GitHubText.SERVER
        else -> GitHubText.OTHER
    }

    /** GitHub's `message` and `errors[].message` (or plain strings), lower-cased, for matching only. */
    fun details(body: String): List<String> {
        val root = runCatching { AppJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val messages = mutableListOf<String>()
        (root["message"] as? JsonPrimitive)?.contentOrNull?.let(messages::add)
        (root["errors"] as? JsonArray)?.forEach { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.let(messages::add)
                is JsonObject -> item["message"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.let(messages::add)
                else -> Unit
            }
        }
        return messages.map { it.lowercase(Locale.ROOT) }
    }
}

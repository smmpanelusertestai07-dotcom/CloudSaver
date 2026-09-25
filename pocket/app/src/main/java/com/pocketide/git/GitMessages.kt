package com.pocketide.git

import com.pocketide.core.Redact
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.util.Locale
import javax.net.ssl.SSLException

/** What the owner reads when a git step does not go through. */
internal object GitMessages {
    const val NOT_GITHUB = "Only GitHub repositories over HTTPS can be used."
    const val NOT_ON_PHONE = "This project is not on the phone yet. Open it to download it."
    const val UNSAFE_COPY =
        "This project's copy on the phone was changed in an unsafe way, so it was not used. " +
            "Remove the project from this phone and open it again."
    const val FOREIGN_OBJECTS =
        "This project's copy on the phone points to files outside it, so it was not used. " +
            "Remove the project from this phone and open it again."
    const val UNKNOWN_REMOTE =
        "PocketIDE does not know which GitHub repository this copy belongs to. " +
            "Remove the project from this phone and open it again."
    const val ANOTHER_COPY = "A different repository is already stored in this project's place on the phone."
    const val NEWER_ON_GITHUB = "GitHub has newer commits on this branch."
    const val ONLY_SESSION_BRANCHES = "Only session branches can be deleted."
    const val NOT_CONFIRMED = "GitHub did not confirm the change. Try again."
    const val DELETE_REFUSED = "GitHub did not allow deleting this branch."
    const val OFFLINE = "Could not reach GitHub. Check the connection and try again."
    const val SLOW = "GitHub took too long to answer. Try again."
    const val TLS = "The secure connection to GitHub failed. Check the phone's date and time, and the network."
    const val NO_REPO = "GitHub could not find this repository, or PocketIDE's GitHub App has no access to it."
    const val SIGN_IN = "GitHub did not accept the sign-in. Connect GitHub again."
    const val NOT_PERMITTED = "PocketIDE's GitHub App is not allowed to do this in this repository."
    const val FAILED = "Git could not finish this step. Try again."
    const val STORAGE_FULL = "The phone's storage is full. Free some space, then try again."
    const val NOT_AN_APPROVAL = "This approval does not name a workflow change. Run the check-post again."

    fun missingBranch(name: String) = "The branch $name is not on the phone."

    fun invalidBranch(name: String) = "\"$name\" is not a valid branch name."

    /**
     * GitHub gives a short [reason] for the refused branch and explains it in its "remote:"
     * messages ([remoteSaid]), with codes such as GH006; both are read, only [reason] is shown.
     */
    fun refusedBecause(reason: String, remoteSaid: String): String {
        val said = "$reason $remoteSaid".lowercase(Locale.ROOT)
        return when {
            "gh013" in said || "rule violation" in said || "secret" in said ->
                "GitHub's repository rules or secret scanning refused the push."
            "gh006" in said || "protected branch" in said ->
                "This branch is protected on GitHub, so the push was refused."
            "gh007" in said || "private email" in said ->
                "A commit uses an email address GitHub keeps private. Commit with your GitHub noreply address."
            "gh001" in said || "large file" in said ->
                "GitHub refused a file that is too large."
            "workflow" in said ->
                "PocketIDE's GitHub App needs the Workflows permission to change files in .github/workflows."
            reason.isBlank() -> "GitHub refused the push."
            else -> "GitHub refused the push: " + Redact.text(reason.trim().take(200))
        }
    }
}

/** A plain sentence for [error], without JGit's internals. */
internal fun plainReason(error: Throwable): String {
    if (error is GitGateException) return error.message
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    val said = chain.joinToString(" ") { it.message.orEmpty() }.lowercase(Locale.ROOT)
    return when {
        chain.any { it is UnknownHostException || it is ConnectException || it is NoRouteToHostException } ->
            GitMessages.OFFLINE
        chain.any { it is SocketTimeoutException } -> GitMessages.SLOW
        chain.any { it is SSLException || it is CertificateException } -> GitMessages.TLS
        chain.any { it is NoRemoteRepositoryException } || "repository not found" in said -> GitMessages.NO_REPO
        "not authorized" in said || "authentication" in said -> GitMessages.SIGN_IN
        "not permitted" in said -> GitMessages.NOT_PERMITTED
        // The system's words for ENOSPC, whichever file ran out of room.
        "no space left" in said -> GitMessages.STORAGE_FULL
        else -> GitMessages.FAILED
    }
}

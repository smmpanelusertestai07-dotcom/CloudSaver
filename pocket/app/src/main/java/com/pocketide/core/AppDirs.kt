package com.pocketide.core

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Where everything lives in the app's private storage. One place, so every module agrees.
 *
 * Host paths (Android side) and the guest paths each room sees inside Linux:
 *
 * | Host                                   | Guest (inside a room)        | Seen by            |
 * |----------------------------------------|------------------------------|--------------------|
 * | rootfs/                                | /                            | every room         |
 * | rooms/<agent>/home/                    | /root                        | that room only     |
 * | rooms/<agent>/tmp/                     | /tmp                         | that room only     |
 * | bridge/<agent>/ ([bridgeDirName])      | /run/pocketide               | that room only     |
 * | repos/<project>.git (bare clones)      | /repos/<project>.git         | every room         |
 * | work/<agent>/<project>/<session>/      | /work/<project>/<session>    | that room only     |
 * | work/<agent>/<project>/.media/<sess>/  | /work/<project>/.media/<s>   | that room only     |
 *
 * Project ids contain "/", so on disk they are written as "<owner>__<repo>" ([projectDirName]).
 */
class AppDirs(val base: File, val cacheBase: File) {
    val rootfs = File(base, "rootfs")
    val rooms = File(base, "rooms")
    val bridge = File(base, "bridge")
    val repos = File(base, "repos")
    val work = File(base, "work")
    /** Encrypted local queue of pieces waiting for Drive; survives reboots and kills. */
    val queue = File(base, "queue")
    /** Encrypted local copy of the vault index and small state. */
    val vault = File(base, "vault")
    /** Build outputs kept on the phone (the last 3 per project). */
    val builds = File(base, "builds")
    /** Where each Media file came from (session, agent, build). */
    val mediaMeta = File(base, "media-meta")
    /** The owner's scheduled tasks. */
    val schedules = File(base, "schedules.json")
    /** Scratch only, safe to empty at any time: includes media-staging/ and actions-* build downloads. */
    val downloads = File(cacheBase, "downloads")
    val share = File(cacheBase, "share")
    val apk = File(cacheBase, "apk")
    val prootTmp = File(base, "proot-tmp")
    val procFakes = File(base, "proc-fakes")
    val logs = File(base, "logs")

    fun roomHome(agentId: String) = File(rooms, "$agentId/home")
    fun roomTmp(agentId: String) = File(rooms, "$agentId/tmp")
    fun roomBridge(agentId: String) = File(bridge, bridgeDirName(agentId))
    fun roomWork(agentId: String) = File(work, agentId)
    fun bareRepo(projectId: String) = File(repos, "${projectDirName(projectId)}.git")
    fun worktree(agentId: String, projectId: String, sessionId: String) =
        File(roomWork(agentId), "${projectDirName(projectId)}/$sessionId")
    fun sessionMedia(agentId: String, projectId: String, sessionId: String) =
        File(roomWork(agentId), "${projectDirName(projectId)}/.media/$sessionId")

    companion object {
        const val GUEST_HOME = "/root"
        const val GUEST_BRIDGE = "/run/pocketide"
        const val GUEST_REPOS = "/repos"
        const val GUEST_WORK = "/work"
        const val GUEST_TOOLS = "/opt/pocketide"

        fun from(context: Context) = AppDirs(context.filesDir, context.cacheDir)

        fun projectDirName(projectId: String) = projectId.replace("/", "__")

        /**
         * The phone socket's host path must fit a Unix socket address (107 bytes), and it passes
         * through proot, so a long agent id (Open VSX ids can be) gets a short, stable folder
         * name. "~" cannot start an agent id, so a hashed name never meets a real one.
         */
        fun bridgeDirName(agentId: String): String {
            val bytes = agentId.toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAX_BRIDGE_NAME) return agentId
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return "~" + digest.joinToString("") { "%02x".format(it) }.take(HASHED_NAME_HEX)
        }

        private const val MAX_BRIDGE_NAME = 32
        private const val HASHED_NAME_HEX = 16
        fun guestBareRepo(projectId: String) = "$GUEST_REPOS/${projectDirName(projectId)}.git"
        fun guestWorktree(projectId: String, sessionId: String) =
            "$GUEST_WORK/${projectDirName(projectId)}/$sessionId"
        fun guestMedia(projectId: String, sessionId: String) =
            "$GUEST_WORK/${projectDirName(projectId)}/.media/$sessionId"
    }
}

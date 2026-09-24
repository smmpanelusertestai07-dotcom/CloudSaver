package com.pocketide.model

import kotlinx.serialization.Serializable

@Serializable
enum class ObjectKind {
    /** An append-only piece of a transcript: new bytes only, compressed then encrypted. */
    CHAT_PIECE,
    /** A screenshot, video or file the agent made for the owner, or an attachment. */
    MEDIA,
    /** Agent memory and user-level instructions (CLAUDE.md, AGENTS.md, GEMINI.md, memory files). */
    MEMORY,
    /** Synced settings. */
    SETTINGS,
    /** Project Variables and Secrets. */
    SECRETS,
    /** Any other agent state file that must survive a new phone (not logins). */
    AGENT_STATE,
}

/** One encrypted file in the Drive hidden app folder. Names reveal nothing. */
@Serializable
data class VaultObject(
    /** Opaque Drive file name, e.g. "o-<random>". */
    val name: String,
    val driveId: String? = null,
    val kind: ObjectKind,
    val sessionId: String? = null,
    val agentId: String? = null,
    /** Logical path relative to the room's home (or media folder) the bytes belong to. */
    val path: String,
    /** For chat pieces: where these bytes start in the logical file. */
    val offset: Long = 0,
    /** Plaintext length. */
    val length: Long,
    /** Encrypted size in Drive. */
    val storedBytes: Long,
    /** SHA-256 of the plaintext (hex): identical files are stored once. */
    val sha256: String,
    val createdAt: Long,
    /** Key generation that encrypted it; re-key re-encrypts older generations. */
    val keyGeneration: Int = 1,
)

/** "One phone at a time": device, heartbeat and expiry, kept in the index. */
@Serializable
data class Lease(
    val deviceId: String,
    val deviceName: String,
    val heartbeatAt: Long,
    val expiresAt: Long,
)

/** The encrypted index in Drive: the single source of truth for what the vault holds. */
@Serializable
data class VaultIndex(
    val schema: Int = 1,
    val updatedAt: Long,
    /** Monotonic; a writer must hold the lease and bump it, so lost updates are detected. */
    val revision: Long = 0,
    val keyGeneration: Int = 1,
    val lease: Lease? = null,
    val projects: List<Project> = emptyList(),
    val sessions: List<SessionRecord> = emptyList(),
    val objects: List<VaultObject> = emptyList(),
    /** Synced subset of settings (retention choices, limits), JSON. */
    val settingsJson: String? = null,
)

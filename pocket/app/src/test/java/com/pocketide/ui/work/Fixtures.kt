package com.pocketide.ui.work

import com.pocketide.model.SessionRecord
import com.pocketide.model.SessionStatus

internal fun session(
    id: String,
    agentId: String = "claude",
    projectId: String = "me/app",
    title: String = "Session $id",
    lastActivityAt: Long = 1_000,
    status: SessionStatus = SessionStatus.OPEN,
    branch: String = "pocket/$agentId/2026-09-24-$id",
    deletedAt: Long? = null,
    backUp: Boolean = true,
    pendingBytes: Long = 0,
    transcriptBytes: Long = 0,
    mediaBytes: Long = 0,
): SessionRecord = SessionRecord(
    id = id,
    agentId = agentId,
    projectId = projectId,
    title = title,
    branch = branch,
    startedAt = 0,
    lastActivityAt = lastActivityAt,
    status = status,
    deletedAt = deletedAt,
    backUp = backUp,
    pendingBytes = pendingBytes,
    transcriptBytes = transcriptBytes,
    mediaBytes = mediaBytes,
    deviceId = "phone",
)

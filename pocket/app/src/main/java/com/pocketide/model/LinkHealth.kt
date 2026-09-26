package com.pocketide.model

/** How the GitHub sign-in is doing, from the last online check. */
enum class LinkHealth {
    NOT_CONNECTED,
    OK,

    /** No network. Offline is not signed out: nothing needs fixing. */
    OFFLINE,

    /** Access was revoked or has expired: the owner signs in again. */
    REVOKED,
}

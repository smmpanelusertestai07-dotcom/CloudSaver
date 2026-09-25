package com.pocketide.limiter

/** Google Play services as far as sign-in to Drive is concerned. */
enum class PlayServices { OK, MISSING, DISABLED, NEEDS_UPDATE }

/** The phone facts §2's minimum is judged on. */
data class PhoneFacts(val abis: List<String>, val sdk: Int, val totalRamBytes: Long, val playServices: PlayServices)

/** §2: who can run PocketIDE at all, and the one sentence that says why not. */
object Requirements {
    const val MIN_SDK = 29

    /**
     * A "4 GB" phone reports about 3.6 GB to apps (the rest is kept by the kernel and the GPU),
     * and a "3 GB" phone well under 3 GB, so the line sits between them.
     */
    const val MIN_RAM_BYTES = 3_400_000_000L

    private const val ARM64 = "arm64-v8a"

    fun unsupportedReason(facts: PhoneFacts): String? = when {
        ARM64 !in facts.abis ->
            "This phone has a 32-bit processor. PocketIDE's Linux computer needs a 64-bit (arm64) phone."
        facts.sdk < MIN_SDK ->
            "PocketIDE needs Android 10 or newer."
        facts.totalRamBytes in 1 until MIN_RAM_BYTES ->
            "PocketIDE needs a phone with at least 4 GB of memory. With less, the Linux computer cannot run next to Android."
        facts.playServices == PlayServices.MISSING ->
            "This phone doesn't have Google Play services, which PocketIDE needs to keep your chats in Google Drive."
        facts.playServices == PlayServices.DISABLED ->
            "Google Play services is turned off. Turn it on in the phone's Settings › Apps, then open PocketIDE again."
        facts.playServices == PlayServices.NEEDS_UPDATE ->
            "Google Play services needs an update. Update it from the Play Store, then open PocketIDE again."
        else -> null
    }
}

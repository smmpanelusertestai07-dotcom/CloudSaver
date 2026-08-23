package app.cloudsaver.core.logic

/**
 * What each cloud app can do, so the pipeline can adapt without ever asking.
 *
 * Two properties matter. A cloud with a *free-up* feature deletes its own
 * uploads from the phone once they are safe, which hands the app a direct
 * per-file signal. A cloud that *de-duplicates by hash* will collapse a
 * re-sent file instead of storing it twice, so a re-release is cheap there
 * and worth avoiding elsewhere.
 *
 * Defaults are only a starting point: watching an app actually free up space
 * promotes it, and the user is never asked a question about any of this.
 */
object CloudCapability {

    data class Caps(
        val hasFreeUpSpace: Boolean,
        val hasHashDedupe: Boolean
    )

    private val DEFAULTS = mapOf(
        "ente" to Caps(hasFreeUpSpace = true, hasHashDedupe = true),
        "immich" to Caps(hasFreeUpSpace = true, hasHashDedupe = true),
        "nextcloud" to Caps(hasFreeUpSpace = true, hasHashDedupe = false),
        "mega" to Caps(hasFreeUpSpace = false, hasHashDedupe = false),
        "proton" to Caps(hasFreeUpSpace = false, hasHashDedupe = false),
        "filen" to Caps(hasFreeUpSpace = false, hasHashDedupe = false),
        "onedrive" to Caps(hasFreeUpSpace = false, hasHashDedupe = false),
        "other" to Caps(hasFreeUpSpace = false, hasHashDedupe = false)
    )

    fun defaultsFor(cloudId: String): Caps =
        DEFAULTS[cloudId] ?: Caps(hasFreeUpSpace = false, hasHashDedupe = false)

    /**
     * Whether a copy that vanished on its own can be believed.
     *
     * Only clouds that free up space remove their own uploads, so only there
     * does a disappearance mean success rather than someone deleting a file.
     */
    fun hasDisappearanceOracle(caps: Caps): Boolean = caps.hasFreeUpSpace

    /**
     * How long to wait before re-sending a copy that vanished without proof.
     *
     * Where the cloud de-duplicates by hash a re-send costs the user nothing,
     * so there is no reason to wait. Everywhere else a slow upload that has
     * not finished yet looks exactly like a lost file, and a day's patience
     * avoids turning that into a second copy in their account.
     */
    fun resendQuietPeriodMs(caps: Caps): Long =
        if (caps.hasHashDedupe) 0L else 24 * 3_600_000L
}

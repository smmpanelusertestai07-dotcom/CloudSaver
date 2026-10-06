package app.entesaver.core.logic

/**
 * What Ente can do, so the pipeline can lean on it without ever asking.
 *
 * Two properties matter. Ente's *free up space* deletes its own uploads from
 * the phone once they are safe, which hands the app a direct per-file signal.
 * Ente *de-duplicates by content*, so a re-sent file is collapsed instead of
 * stored twice, which makes a re-release cheap. Both are on from the start;
 * watching Ente actually free up space is recorded as well, and the person
 * is never asked a question about any of this.
 */
object CloudCapability {

    data class Caps(
        val hasFreeUpSpace: Boolean,
        val hasHashDedupe: Boolean
    )

    val ENTE = Caps(hasFreeUpSpace = true, hasHashDedupe = true)

    /**
     * Whether a copy that vanished on its own can be believed.
     *
     * Only an app that frees up space removes its own uploads, so only there
     * does a disappearance mean success rather than someone deleting a file.
     */
    fun hasDisappearanceOracle(caps: Caps): Boolean = caps.hasFreeUpSpace

    /**
     * How long to wait before re-sending a copy that vanished without proof.
     *
     * Where the cloud de-duplicates by content a re-send costs the person
     * nothing, so there is no reason to wait. Otherwise a slow upload that has
     * not finished yet looks exactly like a lost file, and a day's patience
     * avoids turning that into a second copy in their account.
     */
    fun resendQuietPeriodMs(caps: Caps): Long =
        if (caps.hasHashDedupe) 0L else 24 * 3_600_000L
}

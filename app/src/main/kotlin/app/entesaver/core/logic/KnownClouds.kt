package app.entesaver.core.logic

/**
 * Cloud apps this app recognises but no longer works with.
 *
 * Ente Saver works with Ente Photos only. A phone keeps other cloud apps too,
 * and each keeps downloaded and cached copies under Android/media/<package>,
 * where MediaStore indexes them like any photo - optimising those would make
 * copies of copies, so their folders are refused. Before 11 a person could
 * also have sent copies through one of them, and a copy sent there is still
 * there, so the names are kept to say where. Nothing here is offered, opened
 * or measured.
 */
object KnownClouds {

    val ENTE: List<String> = listOf(
        "io.ente.photos", "io.ente.photos.independent", "io.ente.photos.fdroid"
    )

    private const val ENTE_LABEL = "Ente Photos"

    /** Package name to display name, for the apps earlier versions offered. */
    private val OTHER_LABELS: Map<String, String> = linkedMapOf(
        "mega.privacy.android.app" to "MEGA",
        "io.filen.app" to "Filen",
        "me.proton.android.drive" to "Proton Drive",
        "com.nextcloud.client" to "Nextcloud",
        "app.alextran.immich" to "Immich",
        "com.microsoft.skydrive" to "OneDrive",
        "com.google.android.apps.photos" to "Google Photos",
        "com.dropbox.android" to "Dropbox"
    )

    /** The ids earlier versions stored for the chosen app, to its display name. */
    private val LEGACY_IDS: Map<String, String> = mapOf(
        "mega" to "MEGA", "filen" to "Filen", "proton" to "Proton Drive",
        "nextcloud" to "Nextcloud", "immich" to "Immich", "onedrive" to "OneDrive"
    )

    val ALL_PACKAGES: List<String> = ENTE + OTHER_LABELS.keys

    /** A batch's recorded package as a name a person reads. */
    fun labelOf(pkg: String): String =
        if (pkg in ENTE) ENTE_LABEL else OTHER_LABELS[pkg] ?: pkg

    /**
     * The app an earlier version was set to, when it was not Ente: its name,
     * "" for the old "Other app" entry, or null when there is nothing to say.
     */
    fun previousChoice(id: String): String? = when (id) {
        "", "ente" -> null
        "other" -> ""
        else -> LEGACY_IDS[id] ?: ""
    }
}

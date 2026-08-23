package app.litesaver.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.litesaver.R

/**
 * Cloud backup apps (13.B). LiteSaver is built for end-to-end encrypted clouds
 * that do no compression of their own; the only technical requirement is that
 * the app can auto-back-up ONE user-chosen local folder. LiteSaver itself never
 * talks to any server - it only detects, launches and (with Usage Access)
 * measures these apps on-device.
 */
data class CloudApp(
    val id: String,
    val label: String,
    val packages: List<String>,
    val e2ee: Boolean,
    val supported: Boolean,
    val recommended: Boolean = false,
    /** Setup checklist (max 5 short lines). */
    val checklistRes: Int? = null,
    /** Why this app cannot work with LiteSaver (shown greyed out). */
    val unsupportedReasonRes: Int? = null,
    /** Typical free-plan GB for the calculator prefill - editable, never a fact. */
    val prefillGb: Int? = null
)

object CloudApps {

    val ALL: List<CloudApp> = listOf(
        CloudApp(
            "ente", "Ente Photos",
            listOf("io.ente.photos", "io.ente.photos.independent", "io.ente.photos.fdroid"),
            e2ee = true, supported = true, recommended = true,
            checklistRes = R.string.cl_ente, prefillGb = 10
        ),
        CloudApp(
            "mega", "MEGA", listOf("mega.privacy.android.app"),
            e2ee = true, supported = true,
            checklistRes = R.string.cl_mega, prefillGb = 20
        ),
        CloudApp(
            "filen", "Filen", listOf("io.filen.app"),
            e2ee = true, supported = true,
            checklistRes = R.string.cl_filen, prefillGb = 10
        ),
        CloudApp(
            "proton", "Proton Drive", listOf("me.proton.android.drive"),
            e2ee = true, supported = true,
            checklistRes = R.string.cl_proton, prefillGb = 5
        ),
        CloudApp(
            "nextcloud", "Nextcloud", listOf("com.nextcloud.client"),
            e2ee = false, supported = true,
            checklistRes = R.string.cl_nextcloud
        ),
        CloudApp(
            "immich", "Immich", listOf("app.alextran.immich"),
            e2ee = false, supported = true,
            checklistRes = R.string.cl_immich
        ),
        CloudApp(
            "onedrive", "OneDrive", listOf("com.microsoft.skydrive"),
            e2ee = false, supported = true,
            checklistRes = R.string.cl_onedrive, prefillGb = 5
        ),
        CloudApp(
            "other", "Other app", emptyList(),
            e2ee = false, supported = true,
            checklistRes = R.string.cl_other
        ),
        CloudApp(
            "gphotos", "Google Photos", listOf("com.google.android.apps.photos"),
            e2ee = false, supported = false,
            unsupportedReasonRes = R.string.cl_gphotos_no
        ),
        CloudApp(
            "dropbox", "Dropbox", listOf("com.dropbox.android"),
            e2ee = false, supported = false,
            unsupportedReasonRes = R.string.cl_dropbox_no
        )
    )

    val SELECTABLE: List<CloudApp> = ALL.filter { it.supported }

    fun byId(id: String): CloudApp = ALL.firstOrNull { it.id == id } ?: ALL[0]

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        false
    }

    fun installedPackage(context: Context, app: CloudApp): String? =
        app.packages.firstOrNull { isInstalled(context, it) }

    /** "Other app" needs no detection; it is always considered available. */
    fun isAppInstalled(context: Context, id: String): Boolean {
        val app = byId(id)
        return app.id == "other" || installedPackage(context, app) != null
    }

    /** First installed supported app; Ente stays the default when nothing is found. */
    fun detectDefault(context: Context): CloudApp =
        SELECTABLE.firstOrNull { it.packages.isNotEmpty() && installedPackage(context, it) != null }
            ?: ALL[0]

    fun uidOf(context: Context, pkg: String): Int? = try {
        context.packageManager.getApplicationInfo(pkg, 0).uid
    } catch (e: Exception) {
        null
    }

    /** Opens the cloud app (its own Free-up screen has no public deep link). */
    fun launch(context: Context, id: String): Boolean {
        val pkg = installedPackage(context, byId(id)) ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}

package com.pocketide;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * One preferences file, and the names of everything in it.
 *
 * Scattering getSharedPreferences calls is how two screens end up reading different files and
 * disagreeing about the same setting. Everything goes through here, and every key is declared
 * here, so the full stored state of this app can be read in one screenful.
 */
final class Prefs {
    static final String FILE = "pocketide";

    // Set-up
    static final String INSTALLED = "Linux_installed";
    static final String STAGE = "setup_stage";
    static final String SETUP_STARTED_AT = "setup_started_at";
    static final String SETUP_ELAPSED_MS = "setup_elapsed_ms";
    static final String EDITOR_PASSWORD = "editor_password";

    // Appearance
    static final String THEME = "theme";              // system | light | dark
    static final String EDITOR_LAYOUT = "editor_layout"; // phone | desktop
    static final String EDITOR_ZOOM = "editor_zoom";  // tenths, e.g. 15 == 1.5
    static final String ROTATION = "rotation";        // auto | portrait | landscape

    // Network
    static final String DATA_CAP_MB = "data_cap_mb";  // 0 == no limit
    static final String DATA_USED_DAY = "data_used_day";
    static final String DATA_USED_BYTES = "data_used_bytes";
    static final String WIFI_ONLY = "wifi_only";

    // Extensions
    static final String ALLOW_UNVERIFIED = "allow_unverified";
    static final String INSTALLED_EXTENSIONS = "installed_extensions"; // ids, newline separated

    // ---------------------------------------------------------------- staying current
    /**
     * Whether the machine catches itself up on its own. On by default.
     *
     * What "on" covers is deliberately narrow: Ubuntu's security updates, and nothing else.
     * The editor's own version and the extensions move only when the owner asks, because those
     * change what the workspace looks like. See Updates.java for the whole reasoning.
     */
    static final String AUTO_UPDATE = "auto_update";
    static final String UPDATE_CHECKED_AT = "update_checked_at";
    /** When a check was last ATTEMPTED, which is not the same as when one succeeded. */
    static final String UPDATE_TRIED_AT = "update_tried_at";
    static final String UPDATE_UBUNTU_SECURITY = "update_ubuntu_security";
    static final String UPDATE_UBUNTU_ALL = "update_ubuntu_all";
    static final String UPDATE_EDITOR_CURRENT = "update_editor_current";
    static final String UPDATE_EDITOR_LATEST = "update_editor_latest";
    static final String UPDATE_EXTENSIONS = "update_extensions";
    static final String UPDATE_SUPPORTED_UNTIL = "update_supported_until";
    static final String UPDATE_LAST_RESULT = "update_last_result";
    static final String UPDATE_LAST_RUN_AT = "update_last_run_at";
    /**
     * Whether the editor itself moves on its own. On by default, and narrower than it sounds:
     * on Wi-Fi, once a day, only while the editor is closed, and only through the staged,
     * verified, reversible swap in pocketide-update.sh. Off, the editor moves only when asked.
     */
    static final String AUTO_UPDATE_EDITOR = "auto_update_editor";

    // ---------------------------------------------------------------- the app itself
    /** Whether the app asks, once a day, if a newer PocketIDE has been published. On by default. */
    static final String APP_UPDATE_CHECK = "app_update_check";
    static final String APP_UPDATE_CHECKED_AT = "app_update_checked_at";
    static final String APP_UPDATE_TRIED_AT = "app_update_tried_at";
    static final String APP_UPDATE_LATEST = "app_update_latest";
    static final String APP_UPDATE_PAGE = "app_update_page";
    static final String APP_UPDATE_APK = "app_update_apk";
    static final String APP_UPDATE_APK_BYTES = "app_update_apk_bytes";

    // Diagnostics
    static final String LAST_FAILURE = "last_failure";
    static final String LAST_FAILURE_AT = "last_failure_at";

    // ---------------------------------------------------------------- the app lock
    static final String APP_LOCK = "app_lock";
    /** Set when the lock turned itself off because the phone's own screen lock was removed. */
    static final String LOCK_NOTICE = "app_lock_notice";
    /** Whether the owner asked for the phone's storage inside the workspace. Off by default. */
    static final String PHONE_FILES = "phone_files";
    /** The phone has been paired with itself over Wireless debugging at least once. See Phone. */
    static final String PHONE_PAIRED = "phone_paired";
    /** True once Android 12's ceiling on helper processes was lifted through the phone's own adb. */
    static final String PHANTOM_LIFTED = "phantom_lifted";
    /** Timestamp of the last exit the owner has already been told about. */
    static final String EXIT_SEEN_AT = "exit_seen_at";
    /**
     * True while the service has Linux running, false once it has stopped tidily. If the
     * process dies it keeps whatever it was, which is how the next start knows whether a
     * Task-Manager stop or a low-memory kill actually took anything down. See Exits.
     */
    static final String LINUX_WAS_RUNNING = "linux_was_running";
    /**
     * Whether notifications have ever been asked for.
     *
     * Needed because Android gives no way to tell "never asked" from "asked twice and refused
     * for good": shouldShowRequestPermissionRationale is false in both. Without this the app
     * treats a fresh install as a permanent refusal and sends the owner to a Settings page
     * instead of showing them the prompt.
     */
    static final String ASKED_NOTIFICATIONS = "asked_notifications";

    private Prefs() {}

    static SharedPreferences of(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}

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

    // Network
    static final String DATA_CAP_MB = "data_cap_mb";  // 0 == no limit
    static final String DATA_USED_DAY = "data_used_day";
    static final String DATA_USED_BYTES = "data_used_bytes";
    static final String WIFI_ONLY = "wifi_only";

    // Extensions
    static final String ALLOW_UNVERIFIED = "allow_unverified";
    static final String INSTALLED_EXTENSIONS = "installed_extensions"; // ids, newline separated

    // Diagnostics
    static final String LAST_FAILURE = "last_failure";
    static final String LAST_FAILURE_AT = "last_failure_at";
    static final String SEEN_PERMISSION_INTRO = "seen_permission_intro";

    // ---------------------------------------------------------------- the app lock
    static final String APP_LOCK = "app_lock";
    /** Set when the lock turned itself off because the phone's own screen lock was removed. */
    static final String LOCK_NOTICE = "app_lock_notice";
    /** Whether the owner asked for the phone's storage inside the workspace. Off by default. */
    static final String PHONE_FILES = "phone_files";
    /** Timestamp of the last exit the owner has already been told about. */
    static final String EXIT_SEEN_AT = "exit_seen_at";

    private Prefs() {}

    static SharedPreferences of(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}

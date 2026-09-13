package com.pocketagent.mobile;

import android.content.Context;

/** Compatibility guard for legacy download preferences. Public phone mounts are disabled. */
final class PhoneFiles {
    private PhoneFiles() {}

    static boolean allowed(Context context) {
        return false;
    }
}

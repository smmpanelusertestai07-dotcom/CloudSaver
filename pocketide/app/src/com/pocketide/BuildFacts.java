package com.pocketide;

/**
 * The few numbers the screens have to agree with the build about.
 *
 * These are duplicated in build.sh, which is the only way to get them into the APK's manifest,
 * and tests/version_agreement.py fails the build if the two ever disagree. An app that shows
 * one version in Settings and reports another to the package manager is an app whose bug
 * reports cannot be trusted.
 */
final class BuildFacts {
    private BuildFacts() {}

    static final String VERSION_NAME = "1.9.0";
    static final int VERSION_CODE = 190;

    /** What the whole set-up costs, so the screen can say it before spending anyone's data. */
    static final long BASE_DOWNLOAD_BYTES = 410L * 1000 * 1000;
}

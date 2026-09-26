package com.pocketide.docs

/**
 * What the owner sets up once, outside the app, so their build of PocketIDE may use Drive. Not
 * counted in the guide's word budget: it is done once, and the app points here when Google does
 * not know the build.
 */
internal object OwnerSetUp {
    const val GOOGLE_CLOUD_TITLE = "Google Cloud set-up"

    val googleCloud = section(
        "google-cloud",
        GOOGLE_CLOUD_TITLE,
        "The one-time set-up in your Google Cloud project that lets your build of PocketIDE use Drive.",
        p(
            "Google lets an app into Drive only when a Google Cloud project knows that exact build: its package " +
                "name and the SHA-1 of the certificate that signed it. When Google does not know this build, " +
                "PocketIDE says so and shows both.",
        ),
        steps(
            "In the Google Cloud console, create a project, or pick the one you keep for PocketIDE.",
            "In APIs and services, turn on the Google Drive API.",
            "In Google Auth Platform, fill in the app's details, and under Data access add the scope drive.appdata: " +
                "PocketIDE's own hidden folder, nothing else.",
            "Under Audience, publish the app so its status is In production. In Testing, Google ends the access " +
                "within days and PocketIDE has to ask again.",
            "Under Clients, create an OAuth client of type Android with the package com.pocketide " +
                "(com.pocketide.debug for a debug build) and the SHA-1 of your signing certificate.",
            "Wait a few minutes, then tap Try again in PocketIDE.",
        ),
        info(
            "A debug build and a release build are signed with different keys, so each needs its own Android " +
                "client. $LABELS_NOTE",
        ),
        link("Google Cloud console: Clients", DocLinks.GOOGLE_CLOUD_CLIENTS),
    )

    val all = listOf(googleCloud)
}

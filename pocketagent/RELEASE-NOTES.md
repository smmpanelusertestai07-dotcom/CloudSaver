# PocketAgent 14.0.0-beta.12 — new name and app identity

Version **14.0.0-beta.12**, code **432**, introduces the PocketAgent identity throughout the
native Android app and its retained Ubuntu runtime. App labels, Java/application package,
source directory, themes, helper commands, generated runtime files, notification text,
build artifacts and CI use the new name. The manifest and Java namespace now both use
`com.pocketagent.mobile`; the build no longer rewrites a different manifest package.

The approved coding-brackets and AI-spark artwork replaces the previous pocket/desktop mark.
The launcher resources include density-specific legacy icons and adaptive foreground/background
layers, with a monochrome layer for Android's themed icons. Notifications use a separate,
transparent white silhouette; they do not reuse the full-color square launcher art.

**Installation changes:** PocketAgent is a new Android application ID, separate from
PocketDesk (`com.pocketdesk.mobile`) and PocketLinux (`com.pocketlinux`). This APK cannot
update those packages in place or automatically read their private projects, chats, settings
or sign-ins. Export source from the old app, import it into PocketAgent and connect accounts
again. Keeping the same signing certificate does not transfer data between package IDs.
Keep the old app until important project exports are checked.

The local signing keystore and alias use PocketAgent filenames, while the existing
certificate remains unchanged. A certificate's original subject cannot be rebranded without
issuing a new certificate. Source archives exclude all signing keys, runtime data and
credentials. Fresh source builds produce explicitly labelled development APKs; future
PocketAgent updates must keep a stable package and signing identity.

This release changes branding and Android identity; it does not add provider entitlements,
transfer login sessions or certify compatibility on every device. Existing beta.11 agent
switching, downloads, chat and account boundaries remain documented in [NATIVE-BETA.md](NATIVE-BETA.md).
Build/resource checks do not replace real-phone launcher, notification, login or runtime tests.

Earlier release notes retain their original product names in the
[historical archive](history/PRE-REBRAND-RELEASE-NOTES.md).

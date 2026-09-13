# PocketAgent 14.0.0-beta.12 — Native mobile IDE

PocketAgent is a native Android interface for official coding agents running inside an
app-private Ubuntu userspace on the phone. A project can switch between supported agents;
each keeps its own harness, account, models, usage and conversations. It is an independent
development beta, not an official provider app or a complete desktop replacement.

This release adopts the **PocketAgent** name, `com.pocketagent.mobile` application and Java
package, and the approved coding-brackets/AI-spark icon. Launcher, themed launcher,
notifications, app screens, source paths, build outputs and runtime helpers use the new brand.
See [release notes](RELEASE-NOTES.md) and the [capability and setup guide](NATIVE-BETA.md).

## Install

Use an **ARM64 Android 10+** phone. Ubuntu and tools download during initial setup.
The new application ID installs PocketAgent separately from the previous PocketDesk
(`com.pocketdesk.mobile`) and PocketLinux (`com.pocketlinux`) apps. Android does not transfer
those apps' private projects, history, settings or accounts to the new package. Export any
important projects from the old app and import them using PocketAgent's Android file picker;
connect provider accounts in the new app. Keep the old app until the export is verified.

## Build

The build uses the Android SDK and Java directly: no Gradle, Maven or AndroidX.
Install JDK 17+, platform 35 and build-tools 35.0.0, then:

```bash
export ANDROID_SDK_ROOT=/path/to/android-sdk
bash build.sh
```

The build has version code **432**, targets API 35, requires API 29 and packages ARM64 native
libraries with 16 KB ZIP alignment. Output is `build/PocketAgent-v14.0.0-beta.12-release.apk`,
or `build/PocketAgent-v14.0.0-beta.12-devkey-release.apk` for a generated development key.

The signing key is in the repository on purpose, at `.signing/pocketagent-local.jks`.
Android refuses an update signed with a different key, and taking one means uninstalling --
which deletes every project, chat and connected account. This app is installed by hand from an
APK, so a key that changed between builds would cost its owner everything on every update. The
key is therefore deliberately public and is **not a secret**: anyone can sign an APK with it, so
it proves nothing about who built a file. Check what you install.

For a Play Store release, use your own private key: configure `POCKETAGENT_KEYSTORE`,
`POCKETAGENT_STORE_PASS`, `POCKETAGENT_KEY_PASS` (defaults to the store password) and optional
`POCKETAGENT_KEY_ALIAS` (defaults to `pocketagent`), and keep that key out of the repository.
An explicitly configured missing keystore fails the build rather than silently minting another.

Run host regressions after building, with `ANDROID_SDK_ROOT` still set. Native fixtures
use the compiled `build/classes` and the pinned JSON-Java 20250517 runtime (test-only):

```bash
mkdir -p /tmp/pocketagent-test-deps
curl --fail --location --proto '=https' --tlsv1.2 \
  https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar \
  --output /tmp/pocketagent-test-deps/json-20250517.jar
export POCKETAGENT_JSON_JAR=/tmp/pocketagent-test-deps/json-20250517.jar
printf '%s  %s\n' '3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796' "$POCKETAGENT_JSON_JAR" | sha256sum --check --strict
bash tests/run-tests.sh
```

The GitHub Actions workflow at `.github/workflows/pocketagent.yml` installs these dependencies,
builds the APK and runs all registered regressions before publishing its downloadable artifact. Stable CI
signing uses repository secrets `POCKETAGENT_KEYSTORE_B64`, `POCKETAGENT_STORE_PASS`,
`POCKETAGENT_KEY_PASS` and optionally `POCKETAGENT_KEY_ALIAS`; without them CI generates a
separate development key. Shared source excludes `.signing/`, build output and account data.

## Current guide and history

- [Setup, features, permissions and limits](NATIVE-BETA.md)
- [Current release notes](RELEASE-NOTES.md)
- [Open-source notices](OPEN_SOURCE_NOTICES.md)
- [Historical desktop/readme documentation](history/PRE-REBRAND-README.md)
- [Historical release notes through beta.11](history/PRE-REBRAND-RELEASE-NOTES.md)

Historical documents retain their original product names and claims so they do not present
old desktop features or old signing/storage practices as current PocketAgent behavior.

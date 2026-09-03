# CloudSaver

**Save your cloud space — light copies, untouched originals.**

An offline Android app that makes smaller copies of your photos and videos so
the cloud you already pay for holds more of them. It does not upload anything
itself, and it never deletes an original on its own.

## What it does

Encrypted clouds upload what they are handed. Almost none of them make a file
smaller first, so a plan fills up and the usual answer is to buy more storage.
CloudSaver optimises copies into `Pictures/CloudSaver`; you point a cloud app
you already trust — Ente, MEGA, Filen, Proton Drive, Nextcloud, Immich,
OneDrive — at that one folder. Your originals stay exactly where they are.

Removing an original is always your decision, made through Android's own
confirmation dialog, and only after the app has evidence the copy reached your
cloud. The app itself has no code path that deletes an original.

## What it will never do

- **Reach the internet.** Both network permissions are stripped from the
  manifest; the shipped APK holds no `INTERNET` permission at all. There is no
  server, no account, and no analytics.
- Judge your photographs — no similar-photo detection, no blur or quality
  scoring.
- Delete an original automatically, for any reason.
- Recommend a cloud provider or mention a price.

## Install

Download the APK from [Releases](../../releases) and open it on your phone.

Two things to know:

1. **Check the file first.** Each release prints its APK's SHA-256 and the
   SHA-256 of the certificate it was signed with. Compare the file you
   downloaded against the printed value before installing.
2. **Play Protect will warn** about a sideloaded APK — "More details" →
   "Install anyway".

If the app ever shows a "Modified copy" banner, the build you are running was
not signed with CloudSaver's key. Deleting stays disabled and it will not
re-enable; replace it with a release from this repository.

## Building it yourself

```
./gradlew assembleRelease
```

Requires JDK 17. `minSdk` 29, `targetSdk` 36. The release build runs R8 in
full mode and the APK is gated at 15 MB in CI (it currently sits near 6 MB).

Release builds are signed from repository secrets — `KEYSTORE_B64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. **Generate that key on your
own machine and keep it private.** CI will refuse to publish a release without
it, because a key that exists only inside one CI run produces an APK Android
cannot install over any other build.

## How it is tested

- 500 unit tests on the JVM. Many are *source-text rules*: they read the
  source and the strings as text and assert properties of them — that no two
  attention chips say the same words, that no button label is cut to one line,
  that the About card names every permission the manifest holds, that the app
  never claims to upload anything, that the release workflow never publishes a
  private key.
- 108 instrumented tests across 16 classes, on real emulators against a real
  gallery, over API 29 through 36 on every push.
- Every test failure photographs the screen it failed on.

`RELEASE_MATRIX.md` states one row per requirement with the file, screen or
test that proves it, and its counts are read off the source tree by
`MatrixHonestyTest` rather than typed by hand.

## Licence

Apache License 2.0 — see [LICENSE](LICENSE).

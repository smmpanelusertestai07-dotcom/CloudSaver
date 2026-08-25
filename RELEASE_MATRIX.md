# Verification matrix (AA6)

One row per claim. "Done" means the named file, screen or test proves it;
nothing here is asserted from memory.

| # | Claim | Status | Evidence |
|---|---|---|---|
| 1 | Every gallery album can be included, and nothing outside the chosen albums is touched | Done | Album step in setup + Settings; `excludedBuckets` filters `MediaScanner.totals`/`queryAll`; ScanSourcesTest |
| 2 | Every format the device can decode is handled; the rest are copied as-is | Done | `PhotoCompressor`/`VideoCompressor` probe decoders and fall back to `copyAsIs` with the reason recorded; MIME from MediaStore, never extension alone |
| 3 | New apps and new folders are picked up automatically | Done | Every scheduled run rescans MediaStore; no allow-list of folders exists (only exclusions: own output, hidden, cloud-local per `ScanSources`) |
| 4 | Quality matches the chosen preset and is measured on the user's own files | Done | `Presets` drives both encoders; `QualityKept` + per-file `srcPixels`/`outPixels` (Room v5); QualityKeptTest |
| 5 | Originals are never modified, moved, renamed or deleted by the app itself | Done | The only original-deleting path is `ReclaimEngine`, behind proof + Android's own dialog; RowActionsTest proves removal is never offered without proof |
| 6 | Temporary copies stay within the configured space limit and are cleaned automatically | Done | `Storage.cleanTemp` each run; `maxExtraMb` gate in the stager; leftover-files card in the Free up space hub |
| 7 | The daily limit caps what is added to the upload folder each day | Done | `ReleasePlanner.plan` against `dailyCapBytes`; explained beside the control and in the FAQ (Z10.5) |
| 8 | Upload proof is obtained automatically on every supported cloud app | Done | Disappearance proof + paced byte-matching in `MaintainEngine`/`UsageVerifier`; no routine user action; per-file proof line in the UI |
| 9 | Nothing is ever uploaded twice by CloudSaver's own behaviour | Done | Ledger by content digest (`alreadyDelivered`); reattach adopts returned copies; returned-name copies are never re-queued (Z4, ReturnedCopyTest) |
| 10 | Works after reboot, app update, clear data, reinstall and a cloud app change | Done | WorkManager persists; `StartupRecovery` restores from the hidden snapshot; migrations 1..6 with MigrationTest; cloud-switch resets + sheet (Z10.1) |
| 11 | No internet permission, no account, nothing leaves the phone | Done | INTERNET/ACCESS_NETWORK_STATE stripped with tools:node="remove"; crash sharing is a manual share sheet (BB3.3) |
| 12 | Every destructive action is manual, proof-checked, reversible where the OS allows, confirmed by Android | Done | Reclaim/Duplicates: proof re-checked at action time, trash-first (API 30+), system dialog always; three-line warning card (Z1.3) |
| 13 | Every screen icon-led, grouped, readable at 200%, identical structure in both themes | Done | Shared list framework + Dimens rhythm; ThemePurityTest (no colour literals) and ContrastTest (WCAG both themes); 200% relies on sp text + AutoSize tiles — full-device sweep not run (see 18n note) |
| 14 | Every number shown is either measured or labelled as an estimate | Done | `MediaProfile.shrinkPercent` null until measured; projection carries its basis; "rough estimate" labels; partial access shows waiting text, never a number (BB1) |
| 15 | Every failure state is visible in plain words, on screen and in Activity | Done | Health chips (battery, cloud, space, stalled work, missing SD), Activity Problem entries, crash card (BB3), stalled-chain card (Z10.6) |
| 16 | Partial media access is detected and never produces a number | Done | `Permissions.mediaAccess` three-way level; scan refuses inside `MediaScanner.scan`; MediaAccessTest (7 tests) |
| 17 | The SD card is offered only when genuinely writable; a failed release falls back safely | Done | `Volumes.probeWritable` + `VolumeRules`; both pickers filter; releaser verifies the landing volume and retries once on internal; VolumeRulesTest |
| 18 | A crash leaves a readable local trace and a single honest card | Done | `CrashLog` handler + Home card + Share log; CrashLogTest (instrumented) |

**Not done, and why:**

- **The manual device passes** (Z9.5, AA6.4's three new checks, the 200%
  font-scale sweep in row 13, and TalkBack): these need a real phone with a
  real gallery, a real cloud app, an SD card and hardware encoders. Nothing
  in the build environment can run them. Everything automatable about the
  same claims is covered by the 383 unit tests and the instrumented suite.

**Refused permanently (Z7.2), by design:**
similar-photo detection; blur or screenshot quality scoring; automatic
deletion of originals; cloud recommendations; plan or price claims; anything
needing the internet permission; background jobs that only remind; and
"re-optimise everything with the new quality setting" — re-sending files the
cloud already holds would create second copies there.

**Restricted-permissions note (Z6.5):** READ_MEDIA_IMAGES and
READ_MEDIA_VIDEO are the app's core function — reading the gallery it
optimises. Use is narrow: reading media metadata and content for
compression; no analysis, no inference, nothing leaves the phone.

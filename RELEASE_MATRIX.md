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
| 8 | Upload proof is obtained automatically on every supported cloud app | Done | Disappearance proof + paced byte-matching in `MaintainEngine`/`UsageVerifier`; a release now counts only after the row is re-read as visible (`ReleaseVerdict`, CC1), so proof is only ever sought for files the cloud app can actually see |
| 9 | Nothing is ever uploaded twice by CloudSaver's own behaviour | Done | Ledger by content digest (`alreadyDelivered`); reattach adopts returned copies; returned-name copies never re-queued (Z4); a failed release deletes its broken row before retrying, so a retry can never leave two copies (CC1.1, ReleaseVerdictTest) |
| 10 | Works after reboot, app update, clear data, reinstall and a cloud app change | Done | WorkManager persists; `StartupRecovery` restores from the hidden snapshot; migrations 1..6 with MigrationTest; cloud-switch resets + sheet (Z10.1) |
| 11 | No internet permission, no account, nothing leaves the phone | Done | INTERNET/ACCESS_NETWORK_STATE stripped with tools:node="remove"; crash sharing is a manual share sheet (BB3.3) |
| 12 | Every destructive action is manual, proof-checked, reversible where the OS allows, confirmed by Android | Done | Reclaim/Duplicates: proof re-checked at action time, trash-first (API 30+), system dialog always; three-line warning card (Z1.3) |
| 13 | Every screen icon-led, grouped, readable at 200%, identical structure in both themes | Done | Shared list framework + Dimens rhythm; ThemePurityTest (no colour literals) and ContrastTest (WCAG both themes); 200% relies on sp text + AutoSize tiles — full-device sweep not run (see 18n note) |
| 14 | Every number shown is either measured or labelled as an estimate | Done | `MediaProfile.shrinkPercent` null until measured; projection carries its basis; "rough estimate" labels; partial access shows waiting text, never a number (BB1) |
| 15 | Every failure state is visible in plain words, on screen and in Activity | Done | Health chips (battery, cloud, space, stalled work, missing SD), Activity Problem entries, crash card (BB3), stalled-chain card (Z10.6) |
| 16 | Partial media access is detected and never produces a number | Done | `Permissions.mediaAccess` three-way level; scan refuses inside `MediaScanner.scan`; MediaAccessTest (7 tests) |
| 17 | The SD card is offered only when genuinely writable; a failed release falls back safely | Done | `Volumes.probeWritable` + `VolumeRules`; both pickers filter; releaser verifies the landing volume and retries once on internal; VolumeRulesTest |
| 18 | A crash leaves a readable local trace and a single honest card | Done | `CrashLog` handler + Home card + Share log; CrashLogTest (instrumented) |
| 19 | Release visibility is verified end to end: a copy counts as released only after the row is re-read as present, finished, non-empty and in the right folder | Done | `ReleaseVerdict` + the re-query in `Releaser.releaseOne`; ReleaseVerdictTest proves RELEASED is unreachable without it; stale-pending repair at 15 minutes; the folder is re-scanned so the album appears at once |
| 20 | Terminology and estimate labelling are audited by test | Done | PlainEnglishTest bans the retired terms (reclaim, space users, exact duplicates); the calculator carries its Typical-estimate badge inside the result box until the 20+20 gate passes (CC8); "optimise" is the one verb everywhere, checked by whole-word test (DD4) |
| 21 | The app lock fails closed, and the status bar is readable in every theme | Done | Lock.kt has no fail-open branch; KeyguardManager is the authority; enabling verifies identity first; a removed screen lock disables the app lock visibly; LockPolicyTest (6 tests). The insets controller follows the painted palette, not the system setting, in CloudSaverTheme |
| 22 | Reasonable-but-wrong user moves meet a warning at the moment they happen, and nothing is ever touched | Done | Foreign files in the upload folder: counted each pass, Home chip + Activity note, the counting pass has no way to act on them; old-install cleanup can only offer pipeline-named leftovers; the free-up sheet always carries the check-your-cloud warning with an Open button; kept copies explain the double-copy trap; UserMistakeShieldTest holds string + wiring for every warning |
| 23 | Replace-with-light works after the cloud collected the copy: remade from the original, proved before removal | Done | Pin path resolves stage file, then folder copy (hash-checked), else remakes with the current preset; landed file is read back, hashed and opened before IS_PENDING drops and before the removal request exists; failures leave the batch named; a remade copy's only database write is keptUri; LightCopyTest |

**Not done, and why:**

- **The manual device passes** (Z9.5, AA6.4's three new checks, the 200%
  font-scale sweep in row 13, and TalkBack): these need a real phone with a
  real gallery, a real cloud app, an SD card and hardware encoders. Nothing
  in the build environment can run them. Everything automatable about the
  same claims is covered by the 418 unit tests and the instrumented suite.

**Manual device checklist (CC11.3) — for the human tester:**

1. Tap Optimise now: a visible Pictures/CloudSaver album appears in the
   gallery, and the cloud app sees it.
2. About shows nothing technical until Advanced is opened.
3. In Quality explained, tapping the three preset chips updates the summary,
   the appearance line and both tables live.
4. A row on Duplicate files opens in the system chooser (Just once / Always).
5. A mixed selection on Files reads "Optimise 3 of 5 selected" with the
   skipped count named.
6. The calculator badge flips from "Typical estimate" to "Measured from your
   files" after 20 photos and 20 videos are optimised.
7. Delete a .cloudsaver folder from Download: it is recreated within a day,
   with no chip and no alert.

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

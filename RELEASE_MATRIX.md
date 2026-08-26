# Verification matrix — CloudSaver, final release

One row per requirement. "Done" means a named file, screen or test proves it.
Nothing here is asserted from memory: every row was re-walked against the
source for this release, including the rows that were already marked Done.

| # | Requirement | Status | Evidence |
|---|---|---|---|
| R01 | Watches the whole gallery: every volume, every included album, new folders picked up automatically; own output, hidden, legacy-pipeline and cloud-local folders always excluded | Done | `MediaScanner.queryAll` walks `MediaStore.getExternalVolumeNames`; no allow-list of folders exists, only `ScanSources.exclusionReason` (own output, hidden/.nomedia, legacy names, contents-look-like-output, cloud-local); album ticks filter on top; ScanSourcesTest |
| R02 | Every format the device can decode is handled; the rest are copied as-is with the reason shown; HDR tone-mapped or preserved | Done | MIME comes from MediaStore, never the extension; `PhotoCompressor`/`VideoCompressor` probe the decoder and fall back to `copyAsIs(reason)`; `MediaTraits.hdrOf` + `deviceSupportsHdrHevcEncode` decide keep-HDR vs tone-map; the reason reaches the Files row and the as-is card |
| R03 | Presets are a ceiling, never an upscale; already-small files copied as-is; H.264 default with HEVC option; result checks; dates, EXIF and GPS preserved | Done | `Presets.spec` (1080p/16 MP/JPEG 82 default); `BitrateCalc.outputDims` returns the source size when it is already under the limit; `shouldCopyAsIs` for already-efficient video; `ATTEMPTS` chain VBR→CBR→software→as-is with `resultAcceptable`; ExifInterface copy incl. GPS; QualityKeptTest, BitrateCalcTest |
| R04 | Originals are never modified, moved, renamed or deleted by the app itself | Done | Every `contentResolver.delete` in the codebase is either an app-created file (staged copy, released copy, kept light copy) or inside the user-confirmed path — `createTrashRequest`/`createDeleteRequest` on API 30+, the per-file `RecoverableSecurityException` consent loop below it; RowActionsTest proves removal is never offered without proof |
| R05 | Space caps enforced; staging hidden; leftover files clearable; the upload folder's visibility stated honestly and never hidden with .nomedia | Done | the pipeline deletes its own work files as it goes (stager on failure, releaser after release, recovery on purge); anything a crash left behind is cleared by the leftover-work-files button in Storage and the Free up hub, which now skips files younger than an hour so it cannot delete a copy being written; `maxExtraMb` gate in the stager and `minFreeMb` reserve in `DeletePlanner`; no source writes a .nomedia file anywhere; the album fact is printed beside the folder path, in setup and in faq_a2 |
| R06 | The daily release limit is the data cap, stated honestly | Done | `ReleasePlanner.plan` against `dailyCapBytes` with `Pacing.carryForward`; `daily_limit_note` says the app limits only what enters the folder and that the upload timing and network belong to the cloud app |
| R07 | Upload proof obtained automatically on every cloud app, and degraded honestly where it cannot be | Done | Disappearance proof + paced byte accounting in `MaintainEngine`/`UsageVerifier` (`CONFIRMED_EXACT` > `CONFIRMED_PACED` > `VERIFIED` > `AGED`); adaptive `Pacing` ladder on the clean-confirm streak; `CloudWatchdog` learns per-app behaviour and pauses deletions; first-chain card and 48-hour stalled card; usage-access-off shows the chip and the Verify fallback; EvidenceRulesTest, PacingTest, FirstChainTest |
| R08 | Nothing is ever uploaded twice by the app's own behaviour | Done | Ledger by output digest, checked before every release (`Releaser.alreadyDelivered`) and surviving reinstall through the snapshot; `Fingerprint.fp16` is name+size+date, so a moved file keeps its identity; returned copies recognised by `ScanSources.pipelineIdOf` anywhere on disk; duplicates optimised once; quality changes apply to new items only |
| R09 | The Free up space hub: three removal modes, duplicates, largest files, leftover work files, on one list framework, with the warning at the exact spot | Done | `FreeSpaceHubScreen` + `ListFramework` (search, Type/Album/Size/Proof filters, sort, multi-select, "N of M" wording, live totals); `RowActions.splitFor`; `ReclaimRules.Mode` × 3; the confirmation sheet always carries the check-your-cloud warning with an Open button; Recently freed with Restore; RowActionsTest, ListConsistencyTest, UserMistakeShieldTest |
| R10 | Self-heal: unsent copy remade twice then skipped with a reason, sent copy counts as proof, folder recreated, snapshots rebuilt daily, foreign files flagged and never touched | Done | `EvidenceRules.onCopyMissing` (RESEND → GIVE_UP at two), anchor rule in `pacedRelease`, `dailySnapshot` + `sharedTargetsPresent`, `MaintainEngine.foreignFiles` counts and reports without a single write path to those files; UserMistakeShieldTest asserts the pass stays inert |
| R11 | SMART scheduling with every wait state explained; Optimise now bypasses the schedule, never the safety limits | Done | `RunDecider.decide` (charge/battery/saver/thermal/screen/budget) as a pure function with RunDeciderTest; Home prints the current `Wait` in plain words with its reset; `optimise_now_override_*` strings name what the button will and will not skip |
| R12 | Survives reboot, update, clear-data, reinstall, phone change, SD removal, partial access and OS updates | Done | WorkManager `ensure` on every launch; `StartupRecovery` (crash handler → snapshot restore → purge → schedule) with restore now once per install; Room migrations 1..6 with MigrationTest; `Volumes.probeWritable` gates the SD option and the releaser verifies the landing volume; `Permissions.mediaAccess` blocks scanning under partial access and shows waiting text instead of a number; PermanenceTest, MediaAccessTest, VolumeRulesTest |
| R13 | One design system: icon-led rows, one palette, one type scale, one formatter, both themes structurally identical, plurals, no zero states | Done | `Theme.kt` tokens + `Dimens`; ThemePurityTest (no colour literals outside the palette) and ContrastTest (WCAG in both themes); `Formats` is the only number formatter; 31 plurals all complete; Home keeps the hero card, health chips, lifecycle tiles, allowance and the state-aware button; the insets controller follows the painted palette so the status bar is readable in every theme |
| R14 | Help: FAQ, "If something is deleted", Quality explained with live preset compare, Privacy, About, Activity, Logs, crash card | Done | `HelpScreens.kt` — 14 FAQ answers, the six-condition deleted map, the live preset comparison, six privacy blocks, About with the requirement line, permissions statement and technical facts; `ActivityScreen` (30 days, plain sentences); `HelpLogsScreen`; HelpContentTest |
| R15 | The manual set is exactly: albums, cloud app, Optimise now, Pause, the Free up actions, per-item actions, Save/Restore | Done | Nothing else in the app starts, stops or removes anything: the scheduler, the scanner, the stager, the releaser, evidence, snapshots and cleanup all run themselves; `RowActions` decides per-item offers from state; the trial is the one extra tap and it only optimises three photos locally |
| R16 | Permanently refused features are absent | Done | ProductBoundariesTest scans every source file with comments stripped for similar-photo detection, blur or quality scoring, automatic deletion, cloud recommendations or prices, and re-optimise-everything, and holds the worker set to jobs that do work rather than remind; the only mention of any refused feature in the codebase is the comment explaining the refusal |
| R17 | Setup asks in order, cannot be finished with zero albums, and Settings holds exactly the charter's groups | Done | `OnboardingSteps` (welcome, media, albums, notifications, battery, usage, cloud, ready) with the album step required, its running count and measured size of the ticked albums (absent rather than zero until measured, and mirrored in Settings), and the summary warning + one-tap correction that returns to the summary; `OptionsScreen` renders eight groups covering scope, albums, layout, cloud, speed, daily cap, keep-free, may-use, storage, quality, codec, theme, lock, alerts, pause, backup/restore; SetupFlowTest |
| R18 | Cloud picker: E2EE-first, honest per-app notes, unsupported apps greyed out with the reason | Done | `CloudApps.ALL` — Ente (recommended), MEGA (video-quality note), Filen, Proton, Nextcloud, Immich, OneDrive (not-E2EE note), Other; Google Photos and Dropbox present but unselectable with their one-line reasons; per-app checklists of five lines or fewer |
| R19 | Calculator and the measured profile are one source of truth, and never render a zero as a fact | Done | `CapacityMath` + `MediaProfile` (per preset+codec, outlier-clamped, sample gate, accuracy self-check); the Typical-estimate badge sits inside the result box until 20 photos and 20 videos are measured; `shrinkPercent` is null until measured, so Home, Quality explained and Largest files all say "waiting" instead of 0%; CapacityMathTest, MediaProfileTest, ProjectionTest |
| R20 | Exactly two notification channels, and the app works with notifications denied | Done | ProductBoundariesTest pins exactly two `createNotificationChannel` calls, the working channel's silent/no-badge/low-importance settings, the deletion of the retired channel, the 24-hour de-duplication and 7-day mute constants, and the two permission checks plus the SecurityException catch that make a denied permission silence rather than a crash; `clearWorking` in the worker's finally block stops the ongoing icon outliving a run |
| T1 | One module, MediaStore only, explicit migrations, one helper per job, R8 full mode, APK under 15 MB | Done | No MANAGE_EXTERNAL_STORAGE; one `Formats`, one `Projection`, one list framework, one theme file, one deletion path; `Locks` mutexes at the domain entry points; no `runBlocking` on the main thread; release APK 6.5 MB against the 15 MB gate in CI |
| T2 | Platform behaviour decided from current documentation, not memory | Done | The version-dependent decisions carry their reason in the source: per-SDK biometric authenticators, trash from API 30, notification permission from 33, FGS types by API, dynamic colour from 31, `IS_PENDING` publish-then-stamp ordering |
| T3 | Least privilege, exported=false, tamper evidence, FLAG_SECURE, fail-closed lock | Done | Manifest declares only what is used and strips both network permissions; `TamperCheck` compares the signing certificate; `SecureScreen()` on the lock screen and on the free-up screen, pinned by LockPolicyTest; `Lock.kt` has no fail-open branch and the whole app sits behind it; LockPolicyTest |

**Not done, and why**

- **The owner's own device pass.** Ten minutes on a real phone with a real
  gallery, a real cloud app, an SD card and hardware encoders. Nothing in a
  build environment can run it: the emulator has no cloud app to watch and no
  hardware encoder to fail. Everything automatable about the same claims is
  covered by the 442 unit tests and the instrumented suite.

**The owner's 10-minute device checklist**

1. Tick an album in setup, close the app, reopen it — the tick is still there.
2. Tap "Try it on a few photos": three photos are optimised and the sizes shown.
3. Finish setup, then check the gallery for a Pictures/CloudSaver album and
   check the cloud app can see that folder.
4. Turn the app lock on, leave the app, come back: it asks before anything is
   readable, on every tab.
5. In Quality explained, tap the three preset chips — summary, appearance line
   and both tables change with them.
6. Open About: version, build, package, no-network line and the full signing
   fingerprint are all readable.
7. Delete a .cloudsaver folder from Download: it comes back within a day, with
   no chip and no alert.
8. Free up space on one file: the sheet warns you to check the cloud first,
   Android asks, and the file lands in the gallery trash.

**Refused permanently, by design**

Similar-photo detection; blur or quality scoring; automatic deletion of
originals; cloud recommendations; plan or price claims; anything needing the
internet permission; background jobs that only remind; and "re-optimise
everything with the new quality setting" — re-sending files the cloud already
holds would create second copies there.

**Restricted-permissions note**

READ_MEDIA_IMAGES and READ_MEDIA_VIDEO are the app's core function — reading
the gallery it optimises. Use is narrow: reading media metadata and content to
make the smaller copies. No analysis, no inference, nothing leaves the phone.

**Honest scorecard**

Stated without softening, so the app is judged on what it actually is:

- **Two apps are needed.** CloudSaver makes the small copies; a cloud app you
  choose uploads them. CloudSaver never uploads anything itself.
- **Temporary copies exist.** Optimised copies sit in Pictures/CloudSaver until
  the cloud app collects them, within the space limit you set — and that folder
  is visible as an album in your gallery while they wait, because that is what
  lets the cloud app see it. Most gallery apps can hide an album; CloudSaver
  never hides it, as that would hide it from the cloud app too.
- **Proof of upload is strong but inferred.** No end-to-end encrypted cloud
  reports per-file status to another app, so proof is built from the copy
  disappearing after upload and from bytes the cloud app actually transmitted.
  It is evidence, not a receipt — which is why every removal of an original is
  yours to confirm.
- **Risk is minimised by design, not eliminated.** Originals are only ever
  removed by you, through Android's own dialog, into the gallery trash where
  the OS allows it. The app itself never deletes an original, at any point, for
  any reason.

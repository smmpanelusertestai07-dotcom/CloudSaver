# CloudSaver — the running verification log

Required by X5. Every item states done or not done, and why.

This is a dated record, not a statement about the app as it stands today:
each section was written when that round shipped and is left as it was
written, counts included. What is true of the current build is in
`RELEASE_MATRIX.md`, which ships inside every release and whose own numbers
are read off the source tree by `MatrixHonestyTest` rather than typed.

## v4.0 verification, point by point

## Critical bugs

- **W1.1 Snapshot storage — done.** Automatic snapshots had never been written:
  MediaStore refuses a data file under Pictures ("allowed directories are
  [Download, Documents]"), so two of the three targets failed silently. They
  now go to `Documents/.cloudsaver/state.json` and
  `Download/.cloudsaver/state.json`, plus a third copy in app-private storage,
  read newest-first with the old Pictures paths still searched on upgrade. A
  failure of both shared copies is a Problem entry in Activity.
- **W1.2 Theme on every screen — done.** Every screen already composed inside
  the app theme; the visible fault was the navigation bar's selected pill,
  drawn from `secondaryContainer`, which was mint green under an indigo
  header. The secondary family is now the brand blue, the bar is opaque, and
  the two screens that hard-coded white on the fixed gradient inherit it
  instead. A test fails the build on any colour literal outside the theme
  package.
- **W1.3 Gallery total — done.** `DURATION` was in the projection for the
  Images collection, which several devices reject; the exception took the
  whole cursor with it, so 22 GB of photos totalled zero. Videos ask for it,
  images do not, and a failed query marks the total incomplete rather than
  presenting partial numbers as a conclusion.
- **W1.4 Projections — done.** One shared `Projection` function, used
  everywhere: this phone's measured ratio where it exists, typical ratios
  where it does not, photos and videos projected separately and added. The
  basis travels with the number so a screen can label it an estimate.
- **W1.5 Activity wording — done.** Settings changes are stored as a
  `SETTING:VALUE` token and rendered as a sentence from the same strings the
  control uses. Repeats of one setting inside five minutes collapse. A test
  fails on any user-visible string containing a shouted constant.

## Design system

- **W2.1 Icons — done** for every row, header, tile and dialog reached in this
  pass (Home, Storage, Settings, Find space, Activity, Privacy, Quality).
- **W2.2 Type scale — done.** Screen titles are `headlineSmall`, section
  headers `titleSmall`, row titles `bodyLarge`, descriptions `bodyMedium`,
  numbers `titleMedium` with tabular figures. Instrumented tests cover 200%
  font scale on the fixed-height components.
- **W2.3 Cards — done.** Grouped, gapped, no divider lines, card surface one
  step off the page.
- **W2.4 Clipboard — done.** Clipboard writes exist in one helper, used only
  by the two folder-path components; a test fails if a second file writes to
  the clipboard.
- **W2.5 Motion — done.** State changes cross-fade; the counter no longer
  animates at all, which is what stopped it ghosting.

## Screens

- **W3 Storage — done.** Four groups, icon-led rows, zero-value rows hidden,
  the calculator moved out, the long folder paragraph replaced by one line.
- **W4 Find space — done.** Shared search, chips, row and selection bar.
  Duplicates can remove extras (trash-first, Android's own dialog, keeper
  never selectable, "Keep this one instead" available, full folder shown for
  every entry). Biggest files gains search, three sorts, thumbnails and the
  three actions; Remove appears only where proof allows and hands the file to
  Reclaim rather than deleting it locally.
- **W5 Proof — done.** One plain "how we know" line per file from one
  function; the Reclaim confirmation counts how many carry which proof;
  eligibility is re-checked at the moment of action and anything that dropped
  out is named in the result.
- **W6 Calculator — done.** Its own screen. The My-files/Typical selector and
  the photo/video mix slider are gone; the split comes from the real gallery.
  Quality is always visible, and the basis line says how many of the user's
  own files the figures rest on.
- **W7 Activity and Logs — done.** Icons per event type, Export and Clear in
  an app-bar overflow, day grouping and tap-through retained. Logs remains
  under Help.
- **W8 Help content — done.** About states only what this phone gets, with
  package and build behind Advanced. Privacy is six titled blocks. Quality
  explained is five blocks with the glossary removed. Licences gains its
  opening sentence.
- **W9 FAQ — done, with one conflict resolved.** Exactly twelve questions,
  each standing alone. **Conflict:** W9.2 requires exactly twelve while X4
  permits deleting only the six entries W9.1 names; the file held twenty-eight
  and both cannot hold. W9.2 was followed because it states a checkable end
  state and W12.1 requires a test asserting the count. No content was lost —
  the other sixteen were merged into the twelve, not deleted.
- **W10 Storage location — done.** Offered in the setup summary and in
  Settings, only where a removable volume exists, with the "new files only"
  confirmation before the change.

## Audit and release

- **W11 Automation audit — partly done, and the reason is a conflict.** W11.1
  asks for any remaining control a well-designed app would decide by itself to
  be removed; X4 states that nothing beyond its own list may be deleted, and
  that list contains no settings. Following X3, the later document wins and no
  setting was removed. Every surviving control is justified below instead.
- **W12 Tests and release — done except the manual pass.** 314 unit tests and
  the instrumented suite are green; lint is clean; the APK is signed and
  attached to the release. **W12.3 has not been done and cannot be from here:**
  it is a manual pass on a real phone (remove a duplicate and restore it from
  the gallery trash, optimise-first reordering, SD card removal, a fresh
  install restoring from the Documents snapshot). Nothing in this session has
  run against a real gallery, a real cloud app or real hardware encoding.

## W11.2 — why each manual control survives

| Control | Why the app cannot decide it |
|---|---|
| Albums | R1. It is a decision about someone's photographs. |
| Cloud app | R1. Detected, but the user may run several. |
| Optimise now | R1. |
| Pause | R1. |
| Reclaim space and Find space | R1. |
| Save / Restore backup | R1. |
| Media type | Photos-only or videos-only is a preference, not a fact. |
| Upload folder layout | Cloud apps differ in what they can be pointed at. |
| Speed | A battery-against-speed trade-off belongs to the owner. |
| Daily upload limit | Metered data is a financial constraint the app cannot see. |
| Free space to keep / space CloudSaver may use | Personal comfort thresholds. |
| Storage location | Hardware choice; shown only where a card exists. |
| Quality | The central trade-off, and the reason the app exists. |
| Video format | Depends on what the user's other devices can play. |
| Theme, App lock, Alerts | Ordinary personal preferences. |
| Files you excluded | A list of past decisions, shown only when non-empty. |

---

# 4.1.0 — clarification patch X6 and the screenshot round

Point by point against the message that arrived with five screenshots.

## X6 — Exact duplicates is not removed

Verified present, not re-added: `StorageScreen.kt` renders the row under
**Find space** whenever `findSpace.duplicateBytes > 0`, with the reclaimable
size on the right, and hides it at zero exactly like every other zero-value
row. The screen behind it was already fully actionable per W4.3 — search,
multi-select, **Remove extras** into the gallery trash, the keeper shown with
its full folder path, and **Keep this one instead** on every extra. The one
thing W4.3 listed that was missing was filters, so the group list now has
**Most space / Most copies / Name**, matching the sort chips on Biggest files.

## The rendering fault in the screenshot

Not an animation problem. A `LazyColumn` inside a `Column` asks for the
parent's *full* height unless it is given `weight(1f)`, so its rows are laid
out past the bottom of the screen and the header ends up underneath them.
Files and Activity were fixed first; auditing every `LazyColumn` in the app
then found the same bug unreported on three more screens — Biggest files,
Reclaim history and Kept copies. All seven now use `weight(1f)`.

## The green shadow, in both themes

Two separate causes, both removed:

- `AppBackground` drew two large blurred circles as siblings of the content
  with nothing clipping them, so they bled over whatever was on top. They now
  live in a clipped layer behind the content, and the second circle is violet
  rather than cyan — a soft cyan glow on a dark surface reads as green.
- `BrandCyan` and `BrandMint` have been deleted from the palette entirely, not
  merely stopped being used. A colour that is still there is a colour the next
  gradient can pick up again.

## Quality preserved, everywhere, measured

This is the part that needed real work rather than new wording. The app was
asserting quality figures it had no measurement for, so the encoders now
record what they actually did:

- `CompressResult` carries `srcPixels` and `outPixels`; both compressors fill
  them from the dimensions they already had in hand, at no extra cost.
- Room v5 stores them per file (`MIGRATION_4_5`, covered by `MigrationTest`).
  Rows written before this keep 0, which every reader treats as "not
  measured" rather than as 0%.
- `QualityKept.measuredDetailKeptPercent` returns null when it does not know,
  and no screen invents a number in its place.

Where the figure now appears: the Home hero, next to space saved, because
"what did it cost me" is the immediate next question; the Quality explained
screen, as an average over the files it was measured on; the per-file compare
sheet; and the trial card, which used to end on "the photos still look the
same on a phone" — a claim nobody could check — and now reports the pixels
that actually survived those three files.

## Current preset, shown and changeable

- **Quality explained** already carried the segmented control; it keeps it.
- **About** shows the selected preset with its real caps and links to that
  control rather than duplicating it. Two places to change one setting is two
  places to disagree about what it currently is.

## Terminology

"Clear all" → **Deselect all** (it deselected; it cleared nothing). "3 photos"
against a button reading "Optimise 3 files" → both now say photos, and both
now say the number that is really waiting: the button was hard-coded to three
on a phone that had two. "On this phone so far" → **Measured on your files**.
Video caps read "1920 px across" rather than "1920 across".

## Still not done

W12.3, the manual pass on a real phone, is unchanged from 4.0.0: it has not
been done and cannot be done from a build container.

---

# 5.0.0 — FINAL PROMPT v5.0, Y1 to Y11

One line per section, saying what was done. What could not be done is at the
bottom, stated plainly rather than left to be discovered.

## The screenshots that came with the prompt

Four faults were visible in them, and all four were real:

- **"Photos come out about 100% smaller."** `shrinkPercent` computed
  `(1 - ratio) * 100` and an unmeasured ratio is `0.0`, so "no measurement"
  rendered as the largest possible saving. It is nullable now; where there is
  no measurement the screen says so.
- **"Your gallery now: 0.00 GB"** on a phone holding 3,471 files.
  `ProfileBuilder.current()` returned an empty profile whenever no
  `media_profile` row existed. How big a gallery is comes off a MediaStore
  scan and has nothing to do with whether anything has been encoded, so the
  row is built on demand.
- **Tapping a skipped-reason chip stranded the user in Files with the Home
  tab dead.** A plain `navigate()` to a tab route pushes a second copy of
  that tab; the bar then reads the app as already being there. Every route
  now goes through `goTo`.
- **The skipped chips were sliced off at the screen edge** — "1 · You askec".
  They are facts, not filters, so they are full-width rows with an icon.

## Y1 to Y11

- **Y1 — one shared list framework.** `ListScreenScaffold` gives Files, Exact
  duplicates, Biggest files, Reclaim and Kept copies the same search, filter
  chips that state their value and scroll rather than wrap, a sort sheet, a
  selection that survives rotation and process death, skeleton rows, and
  separate empty states for "nothing here" and "nothing matches these
  filters". `ListFilters` holds the rules as pure functions, tested.
- **Y2 — row actions by state.** `RowActions` decides from a row's state
  alone, so no screen can offer an action that cannot do anything. An
  optimised copy is no longer offered "never optimise": that is what made the
  counters disagree with no way to tell why. Removing an original still
  requires per-file proof, and skipping a file is undoable from a snackbar
  that says where the list lives.
- **Y3 — wording.** No implementation vocabulary reaches the user; "waiting
  for proof" is replaced by the file's real state everywhere. Enforced by
  `PlainEnglishTest` rather than by memory.
- **Y4 — Exact duplicates rewritten.** Two plain opening lines, an
  always-visible warning card (with the Android 10 wording where there is no
  trash), group headers over "checked byte by byte", full filters and
  selection, and a confirmation sheet before Android's own dialog.
- **Y5 — Biggest files.** Sentence header, rows saying what the file would
  become, actions from the shared rule, and a bottom bar that says "3 of 12
  are not backed up yet" and offers to narrow rather than failing part way.
- **Y6 — Files.** Type, Album and Size chips beside Status, sort in the
  shared sheet, long-press selection.
- **Y7 — Reclaim and Kept copies.** Reclaim keeps its modes, targets and
  two-step confirm — it is the deletion path, not something to rewrite for
  tidiness — but gains the shared search and one filter row in place of three
  private rows of chips. Kept copies is on the scaffold, and its per-row
  full-width button moved into the overflow.
- **Y8 — theme.** The palette was already defined once by role with no
  hard-coded colours outside the theme file, so the work was proving it:
  `ContrastTest` computes WCAG relative luminance for every pair the app
  uses, in both themes, and holds them to 4.5:1 and 3:1. All pass. `Dimens`
  puts the rhythm in one place.
- **Y9 — navigation.** Every route audited; `NavigationSafetyTest` fails the
  build on a direct `navigate()`, on a `goTo` that stops distinguishing tabs
  from pushes, and on any screen that can be entered but not left.
- **Y10 — automation.** The manual set is unchanged and is the set Y10.1
  lists. No manual action skips proof, trash-first, Android's own dialog, or
  the result summary.
- **Y11 — cleanup and gate.** 22 dead strings and two superseded components
  (`ChipRow`, `SelectionBar`) removed; 712 strings declared, none unused; one
  formatter, one projection, one list framework, one theme package, no TODOs.
  R8 full mode is on. Lint, 354 unit tests and the instrumented compile are
  green.

## One deletion path — what that means exactly

Only `ReclaimEngine` removes a user's original, and only with per-file proof,
trash-first, and Android's own confirmation. Four other places call
`contentResolver.delete`, and all four target files **CloudSaver itself
wrote** into `Pictures/CloudSaver`: a kept light copy the user asked to
remove, leftovers from an earlier install, a stuck pending output, and a
released copy being cleaned up. Routing those through the reclaim path would
be wrong, not tidier — it would demand cloud proof before the app may tidy up
after itself.

## Not done, and why

- **Y11.6, the manual device pass.** Not done and not possible from a build
  container: it needs a real phone, a real gallery, a real cloud app and real
  hardware encoding. Nothing in this session has run against any of them.
  This is the same gap recorded at 4.0.0 and 4.1.0.
- **Signing secrets.** `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
  `KEY_PASSWORD` are the repository owner's to add. Until they exist, release
  builds are unsigned and will not install over an earlier version.

---

# 6.0.0 — FINAL PROMPT v8.0

Executed in the order Z9.1 gives: BB1, BB2, BB3 first, then Z1, Z4, Z5,
Z10.1, Z10.2, Z10.4, Z10.6, then the rest. Full AA6 matrix in
RELEASE_MATRIX.md, which CI appends to every release's notes.

## Correction of earlier claims (AA6.2)

Earlier sections of this file described partial media access, the SD-card
question and crash handling only as wording or settings work. **Z2/BB1
(partial access), Z3.1/BB2 (the writability probe) and Z10.3/BB3 (crash
visibility) were NOT implemented before this pass.** Before it: partial
access was treated as usable and produced wrong totals silently; the chosen
SD volume was handed to MediaStore untested, so on many phones every SD
release failed with nothing on screen; and a crash left no trace at all.
All three are now implemented and tested (MediaAccessTest, VolumeRulesTest,
CrashLogTest).

## What this pass changed, by section

- **BB1** — three-way media access level; scanning refuses inside the
  scanner; worker, trial and calculator check the level; Home card, Files
  chip, waiting text instead of totals; setup keeps the user on the
  permission step under PARTIAL; snapshots record the level.
- **BB2** — per-volume writability probe (a real pending insert, one byte,
  finalise, delete), cached with an OS-update reset; both pickers offer only
  volumes that pass, with the reason when one is absent; the releaser
  verifies the landing volume and retries once on internal.
- **BB3** — uncaught-exception handler appends version/device/stack to the
  app log and hands on to the system; one card next launch; sharing is
  manual, nothing is ever sent.
- **Z1** — the Free up space hub over the four sections, volume header with
  free-before/after, three-line warning card shared by every removal
  surface, confirmation sheets name the proof and the holding cloud app.
- **Z4** — output-pattern names recognised per file anywhere, recorded as
  "came back from your cloud", never queued, never granted proof; cloud
  apps' Android/media directories excluded; .nomedia enforcement is the
  platform's own.
- **Z5** — double-backup card in setup, always shown, acknowledgement
  recorded.
- **Z10.1** — switch resets learning; one-time sheet; the holding app named
  in item details and the removal sheet.
- **Z10.2** — the gallery-album fact under every folder path and in the FAQ,
  with the reason there is deliberately no .nomedia.
- **Z10.4** — About: how sideloaded updates work; signing digest behind
  Advanced from BuildConfig.EXPECTED_CERT_SHA256.
- **Z10.5** — what the daily limit does and does not control, beside the
  control and in the FAQ.
- **Z10.6** — first-chain success and 48-hour stall cards, pure decision
  (FirstChainTest), driven from the maintenance pass.
- **Z3.3/Z3.4** — missing-SD Home chip; ≥4 GB files routed to internal
  storage with the reason logged.
- **AA3.3** — release/reclaim/ledger mutexes at the entry points.
- **AA3.5** — migration 6: indices on isVideo, bucket, sizeBytes, evidence,
  batchId, proven by MigrationTest against sqlite_master.
- **FAQ stays at twelve**: the two new answers merged into faq_a2 and
  faq_a6 rather than growing the list past the earlier fixed count.

## Still not possible from this environment

The manual device passes (Z9.5, AA6.4): real phone, real gallery, real
cloud app, real SD card, TalkBack, 200% sweep. Recorded in the matrix as
the one "Not done", with everything automatable about the same claims
covered by 383 unit tests and the instrumented suite.

---

# 6.1.0 — FINAL PROMPT v9.0, the closing pass

CC1 first, as ordered, then CC2–CC10, each gated on tests.

- **The screenshot bug**: SummaryLine weighted only its label, so a long
  value starved it to one character and "Backing up" rendered vertically.
  Both halves weighted now. Found beside it: setup offered "Start backing
  up" with zero albums selected — a backup that would do nothing for ever —
  and now says so with a one-tap way back to the albums step.
- **CC1**: the un-pend update was fire-and-forget; a silent failure left the
  row IS_PENDING=1 (invisible to the gallery and the cloud app) yet marked
  RELEASED. Releases are now re-read and must be present, finished,
  non-empty and in the right folder before RELEASED is set; failures delete
  the broken row, keep the item STAGED, and reach Activity. Stale-pending
  repair: 24 h → 15 min, run before and after every release pass. The folder
  is media-scanned after each pass. The "in upload folder" tile counts only
  visible RELEASED rows; pacing-held files stay under "to optimise" with a
  line saying they follow as uploads are confirmed. The worker logs that a
  manual run chains release+verify (CC1.2 — it always did; now provable).
- **CC2**: About shows nothing technical above the Advanced expander; the
  Updates text lives inside it, two sentences.
- **CC3**: the preset chips were already live end to end; added the
  per-preset appearance line with the pixels-vs-looks note, and the new
  measured-empty wording.
- **CC4**: Reclaim → Free up, Biggest/space users → Largest files, Exact
  duplicates → Duplicate files, everywhere a user reads; locked by test.
- **CC5**: Open, first, on every duplicate row including the keeper, through
  the one chooser helper; row resolved off the main thread.
- **CC6**: mixed selections split into eligible and skipped with the counts
  said ("Optimise 3 of 5 selected"), hidden at zero; pure rule + tests.
- **CC7**: the Optimise now line names the exact rule it bypasses (budget,
  charger, screen, battery); guards unchanged and never bypassed; FAQ 13.
- **CC8**: the Typical-estimate badge sits inside the calculator's result
  box with the one-line explanation, switching to Measured after the gate.
- **CC9**: no snapshot before onboarding completes; a deleted .cloudsaver
  folder heals the same day (existence checked, not only the day marker);
  FAQ 14 explains the folders; the Privacy page names them.
- **CC10**: FAQ count 12 → 14, exactly the two new answers.

397 unit tests, lint clean. Matrix rows 19–20 added, rows 8–9 evidence
amended, and the CC11.3 manual checklist ships in every release's notes.

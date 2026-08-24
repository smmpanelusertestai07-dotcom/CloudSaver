# CloudSaver 4.0.0 — v4.0 verification, point by point

Required by X5. Every item states done or not done, and why.

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

# TESTING_SPEC.md

**Project:** RetroVault
**Role:** Verification strategy
**Authority:** `CONSTITUTION.md`

## 1. Testing objective

Prove that RetroVault avoids confidently making wrong identity decisions.

A false positive is more serious than a missed match.

## 2. Test layers

- unit
- property
- integration
- database
- filesystem adapter
- parser corpus
- matching corpus
- UI workflow
- end-to-end
- performance

## 3. Domain tests

Test:

- identity equality
- relationship invariants
- evidence aggregation
- confidence states
- naming policies
- collision detection
- idempotence

## 4. DAT corpus

Maintain representative fixtures for:

- valid Logiqx XML
- large DATs
- malformed XML
- missing fields
- duplicate entries
- multiple machines
- multiple sources
- version changes

Do not commit copyrighted DAT datasets unless redistribution permits it. Use synthetic fixtures or permitted metadata samples where required.

## 5. ROM matching corpus

Test categories:

- exact verified dumps
- region variants
- revisions
- alternate names
- scene naming
- scrubbed naming
- misleading names
- duplicate files
- unsupported files
- ambiguous candidates
- no match

The corpus should include adversarial examples designed to trigger false positives.

## 6. Scanner tests

Verify:

- recursive traversal
- cancellation
- progress events
- filtering
- hash escalation
- archive inspection
- large file handling
- inaccessible files
- malformed archives

## 7. Rename tests

Verify:

- dry run has zero mutation
- duplicate destination rejection
- collision detection
- invalid-name rejection
- partial failure recovery
- journal consistency
- idempotent rerun
- stale plan rejection

## 8. Database tests

Verify all migrations from supported baseline versions.

Test foreign keys, transactions, rollback, indexing, FTS rebuild, and interrupted operations.

## 9. Property tests

Useful properties:

`normalize(normalize(name)) == normalize(name)`

`plan(plan(x)) == plan(x)` where state remains unchanged.

Exact hash evidence must never produce a weaker identity than a contradictory fuzzy filename.

Unknown candidate must never become exact solely because fuzzy score increases.

## 10. Performance tests

Benchmark realistic libraries at:

- 1,000 files
- 10,000 files
- 50,000 files
- 100,000+ files where practical

Track memory, scan time, hashing time, database time, and UI responsiveness.

## 11. Regression policy

Every production bug that can be reproduced becomes a test.

Tests should encode the bug's invariant, not only the original filename.

## 12. Release gate

No release candidate if:

- exact-match tests fail
- false-positive regression appears
- migration loses user data
- rename journal becomes inconsistent
- scanner can mutate files without validated plan
- core domain depends on Android/UI

## 13. Guiding rule

**Test the wrong answer harder than the right answer.**


## Media, coverage and PSP disc scenarios

The following must be covered, and are:

**Media recognition**
- `.iso`, `.cso`, `.pbp` recognised as optical disc; PSP UMD images specifically.
- Disc images across generations (`.cue`, `.chd`, `.gdi`, `.rvz`, `.wbfs`, `.nrg`).
- Cartridge extensions recognised as cartridge.
- `.bin`, `.img`, `.rom` left `UNKNOWN` rather than guessed.
- Unrecognised and absent extensions left `UNKNOWN`.
- The SQL backfill table agrees with live classification.

**Dataset provenance**
- Redump, No-Intro, TOSEC and MAME inferred from what the DAT states.
- An unrecognised dataset is `UNKNOWN`, never misattributed.

**Coverage assessment**
- Disc image against a cartridge-only catalogue is out of scope, naming both media.
- Disc image against a disc catalogue is in scope.
- One covering dataset among several is enough.
- An empty catalogue reports "nothing imported", not "no match".
- Unmeasured coverage makes no claim.
- A file of unknown medium is never declared out of scope.
- A dataset of unrecognised media covers everything.
- Uncommitted and unmatchable records contribute no coverage.

**PSP end-to-end**
- PSP ISO against a Redump PSP DAT: exact, verified, renamed.
- PSP ISO against a No-Intro cartridge DAT: out of catalogue scope, nothing renamed, the medium named, counted separately from `unmatched`.
- Importing the disc dataset turns an out-of-scope library into a verified one.
- A disc DAT that does not list a particular disc reports plain absence, not uncovered media.
- A mixed library identifies each medium against the dataset that covers it.
- No bytes are read for an out-of-scope artifact.

**Verified against inferred**
- A hash match is `VERIFIED_CONTENT`; a filename match is `INFERRED`.
- Every state that may carry an identity reports a basis other than `NONE`, and every state that may not reports `NONE`.
- Inferred identity never renames without confirmation; out-of-scope never renames at all.

**Media as evidence, not exclusion**
- A cartridge record never outranks a disc record for a disc image, and carries a visible reason for losing.
- A medium disagreement weakens without excluding.
- A disc preserved in another form (`.chd` against a catalogued `.cue`) registers no disagreement at all.

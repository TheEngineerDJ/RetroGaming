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
- Provenance matching is whole-word: `Gamemaster` is not MAME.
- Provenance is read from the description as well as the header name.

**Coverage assessment**
- Disc image against a cartridge-only catalogue is out of scope, naming both media.
- Disc image against a disc catalogue is in scope.
- One covering dataset among several is enough.
- An empty catalogue reports "nothing imported", not "no match".
- Unmeasured coverage makes no claim.
- A file of unknown medium is never declared out of scope.
- A dataset holding any record of unrecognised medium covers everything.
- A dataset that indexes nothing searchable makes no claim either way.
- Only a wholly recognised catalogue can put an artifact out of scope.
- A catalogue that cannot report coverage degrades to unmeasured instead of failing the scan.
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

## Specification integrity

The source cites constitutional sections by number, so those citations are checked:

- Exactly one constitution file exists. A second differing only in filename case cannot coexist on a case-insensitive filesystem, and the two would number their sections independently.
- Every `Constitution section N` cited anywhere in the source resolves to a section heading.
- No two sections share a label. A letter suffix is part of the label, so `166` and `166A` are distinct.
- Part I keeps the body numbering (0-288) and Part II is offset (301-341), with 289-299 left empty as the gap between them.
- No source file contains a control character. A stray NUL makes a file read as binary to grep, diff and review tooling.

## Audit findings kept as regression tests

**Honest absence.** A read failure and an observed absence must not produce the same verdict:

- Reconciliation of an interrupted rename returns `RECONCILED_UNKNOWN` when storage could not be read, and still reaches a verdict when it could.
- An already-terminal operation is untouched even when storage is unreadable.
- A file whose state could not be read is reported as unreadable, not as missing; a genuinely absent file is still reported as stale.

**No unearned certainty.** An exact state is only claimed when an algorithm actually verified it, even if the catalogue port misbehaves and answers a hash lookup with a record carrying no such hash. The result degrades to a structural match.

**No silent persistence loss.** A scan whose intermediate batch writes fail reports the failure; a scan that persists everything reports none.

**Guards that guard.** A forbidden dependency written as a fully qualified name in the body, rather than as an import, is caught in both the domain and application boundary tests. Forbidden package prefixes are dot-terminated so `com.retrovault.app` cannot flag `com.retrovault.application`.

**Citations resolve.** Every `<SPEC>.md section N` cited anywhere in the source points at a section that exists, in every derived specification, not only the constitution.


## Canonical entities and durable corrections

**Projection.** One record projects into the whole chain with its structural edges; promotion is idempotent; two datasets describing one release converge on one release and one artifact; regional variants and revisions stay separate releases of one work; two representations of one release are two artifacts; a hashless record does not collide with another; everything promoted is `DERIVED`; the dataset's set name survives as an alias.

**Deliberate under-merge.** The same title on two platforms stays two works, because section 32 requires a port to be evidenced rather than inferred.

**Relationships.** An edge must connect the kinds its type declares; an entity cannot relate to itself; an edge is keyed by its endpoints and type; derivation relations exist but are never structural.

**Correction scope.** Keyed by content, so it survives a rename; refused when only CRC32 is available; an archive is corrected by its contained ROM, not by the zip; a correction made against MD5 still applies once SHA1 is known.

**Correction application.** Overrides an exact hash match; is never presented as content verification; the overruled candidate survives with its evidence; the reason and previous claim reach the explanation; a rejection selects nothing; naming a release RetroVault can no longer find is still a rejection; a superseded or withdrawn correction changes nothing; applying twice is idempotent.

**Automation.** A corrected identity may be renamed without asking again; a rejected one never is.

**History.** Superseding preserves the earlier correction; a superseded correction must name its successor; only active corrections reach a scan.

**Persistence.** Promoting twice writes one graph; a derived write never demotes a confirmed entity or edge; a release resolves back to the records describing it; unmatchable records are not offered; deleting a work cascades; a correction outlives the catalogue it was made against.

**End to end.** A scan projects what it identified into the graph; rescanning does not duplicate it; a correction survives a rescan and outranks an exact hash match; a corrected file is renamed to what the user said; withdrawing restores automatic identification; a rejection stops the rename entirely; correction history survives the dataset.

**Migration.** Version 1 reaches version 4 in one pass; the entity graph enforces its own foreign keys; the release-id backfill agrees with what a fresh import writes.

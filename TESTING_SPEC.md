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

**Automation.** A correction the content does not corroborate still requires review; one a cryptographic hash corroborates may be renamed automatically; CRC32 agreement alone does not authorise it; corroboration never turns a user assertion into verified content; content agreement survives a resolution being read back from storage, where signals arrive as `Recorded`; a rejected identity is never renamed.

**History.** Superseding preserves the earlier correction; a superseded correction must name its successor; only active corrections reach a scan.

**Persistence.** Promoting twice writes one graph; a derived write never demotes a confirmed entity or edge; a release resolves back to the records describing it; unmatchable records are not offered; deleting a work cascades; a correction outlives the catalogue it was made against.

**End to end.** A scan projects what it identified into the graph; rescanning does not duplicate it; a correction survives a rescan and outranks an exact hash match; a correction the content contradicts is believed but not acted on; the same file is renamed once the user confirms it; a correction the content corroborates is renamed without further confirmation; withdrawing restores automatic identification; a rejection stops the rename entirely; correction history survives the dataset.

**Queries.** Platforms and works are found by id and searched by name, alias and normalized title; a search term is never treated as a wildcard; a truncated page says so; a caller cannot raise the bound above the maximum; a work search and a platform filter apply together; releases are reachable from their work and their platform; an artifact carries the hashes that identify it; the graph is walkable in both directions; an unreadable edge type is skipped rather than guessed at.

**Provenance queries.** Every contributing dataset is named; derived and user-established are distinguished; edges travel with the report; an artifact carries its whole correction history including superseded and withdrawn entries; a correction against other content does not surface; only artifacts carry corrections.

**Historical identity.** A display name an entity stops carrying is retained as an alias and stays findable; a current name is never also one of its own aliases; timestamps record first sighting and last change; first sighting never moves; a row predating timestamps reports unknown rather than the epoch; a skipped write to a confirmed entity is not an update.

**Migration.** Version 1 reaches the current version in one pass; the entity graph enforces its own foreign keys; the release-id backfill agrees with what a fresh import writes; version 5 adds timestamps to every entity table without backdating the rows already there.


## The Android layer, from the JVM

The Android modules are excluded from the build when no SDK is present, so these run as ordinary JVM tests against the sources as text. They are a floor, not a substitute for compiling.

**Wiring.** Every `com.retrovault.*` import in the Android sources resolves to a declaration the core still has; every port the app needs has an Android implementation; the composition root passes `applyCorrections`, `corrections` and `entities` into the scan — the arguments that are optional on the use case and therefore silent when forgotten.

**SAF honesty.** `stat` reports a provider that returns no cursor, and a URI that cannot be addressed, as failures rather than as an observed absence.


## Modified dumps

**Detection.** iNES, FDS and Lynx headers are recognised by magic and not claimed without it; the Atari 7800 magic is matched at its offset; a SNES copier header is recognised from size alone and a headerless dump is left alone; a file that is only a header has no payload; an interleaved SMD dump is never claimed; an unreadable prefix yields no header rather than an assumed one; only magic formats cost a read.

**Identification.** A headered ROM resolves to `MODIFIED_MATCH` against the catalogued payload with `VERIFIED_CONTENT` basis and an explanation naming the header; a headerless dump is still an exact match; a headered ROM is not renamed without review; opting in renames it in one pass and never rewrites its bytes.

**Persistence.** An observation records the header it saw, and one scanned before headers were recognised records none.


## Reviewing, history, undo and browsing

**Review.** A subject carries every candidate with the evidence for and against it; choosing one records a correction naming its release, derived the same way the entity graph derives it; an identity that is not a candidate cannot be recorded; rejecting records that none is right; the previous claim survives; history shows superseded entries and labels each by the game it named rather than by the identifier it stored; withdrawing leaves the record; a file with no cryptographic hash cannot be corrected durably.

**Undo.** Only renames that took effect can be put back; an interrupted rename nobody could explain never is, nor a dry run; a batch is reversed in the order it ran, backwards; a file that is gone, renamed by something else, of a different size, or whose original name is occupied is refused, as is an unreadable file or folder; one unsafe step blocks the whole reversal while still being shown; a case-only reversal stages through a third name; excluding a step revalidates the rest.

**Undo end to end.** A completed rename is put back exactly; undoing is recorded rather than erasing what happened; a file changed since the rename is not put back; history is readable newest first and stops offering what has been restored.

**Promotion.** Identical identities are written once per batch, not once per file; a graph failure is reported rather than discarded.

**Search ranking.** An exact title outranks a longer title containing it; a result says whether it matched exactly, after normalization, through an alias or partially, and which text it hit; an exact alias outranks a partial title; equal matches fall back to title order deterministically.

**Browsing.** Works list with the number of releases each actually has; opening one shows its releases, their cryptographic digests and every contributing dataset; a release with no recorded region says so; opening a work that is gone fails rather than showing an empty one.

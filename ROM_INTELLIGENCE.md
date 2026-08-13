# ROM_INTELLIGENCE.md

**Project:** RetroVault
**Document Role:** Derived technical specification
**Status:** Foundational / implementation-ready specification
**Authority:** `CONSTITUTION.md`

## 1. Purpose

ROM Intelligence identifies local game artifacts, resolves them against canonical preservation data, explains evidence, and safely produces canonical filenames.

ROM Intelligence is not fundamentally a renaming feature. Renaming is one action performed after identity resolution.

Core pipeline:

**Artifact → Observation → Candidate Set → Evidence → Resolution → Confidence → Human Decision when required → Action → Audit Record**

No destructive action may occur before resolution reaches an explicitly permitted confidence state.

## 2. Inputs

Supported inputs may include:

- ROM files
- disc images
- compressed archives
- multi-file disc sets
- cartridge dumps
- firmware files where applicable
- patched or modified images
- scene releases
- scrubbed files
- homebrew
- hacks
- translations
- prototypes
- bad dumps
- overdumps
- files with misleading or incomplete names

The engine must treat filename as evidence, not identity.

## 3. Evidence hierarchy

Evidence strength is contextual. Default ordering:

1. cryptographic hash matching authoritative dump metadata
2. complete authoritative dump identity including size and hashes
3. validated structural properties
4. byte size
5. normalized filename tokens
6. release/group/scene metadata
7. fuzzy textual similarity
8. user-supplied assertions

Lower-tier evidence may narrow candidates but must not silently override stronger contradictory evidence.

## 4. DAT ingestion

The engine shall support offline DAT ingestion.

Initial priority:

- No-Intro
- Redump

DAT ingestion shall:

- parse Logiqx-style XML
- stream large files
- avoid loading entire DAT documents into memory
- preserve source identity
- preserve DAT version/date where available
- preserve source filename
- retain original machine/game/release metadata
- retain hashes and sizes
- detect malformed records
- support multiple DATs
- merge indexes without losing provenance

Imported DAT data is evidence. It is not automatically universal truth.

## 5. Canonical identity

A resolved artifact must point toward canonical identity rather than merely a display filename.

Canonical identity may include:

- platform
- title
- release
- region
- languages
- revision
- version
- serial
- publisher/developer metadata
- dump status
- preservation status
- source authority

Filename generation occurs only after identity has been established.

## 6. Matching pipeline

### Stage 0 — Media and coverage

Determine the artifact's media type from its identity-bearing name (the contained entry for an archive, the file itself otherwise).

Determine whether any imported dataset covers that medium, using coverage **measured** from the records each dataset indexes.

If no dataset covers the medium — or nothing has been imported — stop here and report the artifact as out of catalogue scope. No content is read. The result names the medium, names what the imported datasets do cover, and states the remedy.

Every uncertain case continues into the ladder. An unknown medium, a dataset of unrecognised media, or unmeasured coverage all mean "proceed": a scope judgement must never cause a missed match.

### Stage 1 — File discovery

Enumerate files through platform-specific storage adapters.

Android implementation shall prefer direct `DocumentsContract` cursor traversal over repeated `DocumentFile` calls where performance requires it.

### Stage 2 — Cheap filtering

Use low-cost signals first:

- extension
- file size
- archive status
- known platform context
- filename tokens

Files whose byte size appears nowhere in relevant DAT indexes may skip cryptographic hashing unless another rule explicitly requires hashing.

Records for which the dataset states no size are always considered: an unknown size cannot rule a record out.

### Stage 3 — CRC32

CRC32 may be used as a fast candidate filter.

CRC32 collision is not sufficient for final identity.

Ambiguous CRC matches must escalate.

### Stage 4 — MD5 / SHA1

Calculate stronger hashes only when required.

Where a dataset offers a cryptographic hash — which No-Intro and Redump records essentially always do — escalation is expected, so the digests may be computed in the same pass as CRC32 to avoid reading the artifact twice. This changes when bytes are read, never what is concluded from them.

Hash calculation must be asynchronous and I/O-bound.

Expected Android implementation:

`Kotlin Coroutines + Dispatchers.IO`

### Stage 5 — Archive inspection

ZIP archives may be inspected without requiring the archive itself to be treated as the game artifact.

The engine must distinguish:

- archive identity
- contained-file identity
- archive metadata

Nested archives require explicit limits to prevent pathological recursion.

### Stage 6 — Filename normalization

Normalize names by separating meaningful tokens from noise.

Examples of removable noise include:

- region tags when not required for candidate separation
- release-group tags
- emulator-specific suffixes
- memory-card suffixes
- scraper tags
- download-site tags
- bracket noise
- duplicate numbering

Normalization must never destroy information permanently. Original filename remains immutable evidence.

### Stage 7 — Fuzzy matching

When cryptographic identity cannot resolve an artifact, textual matching may produce candidates.

Fuzzy matching must be:

- deterministic
- explainable
- bounded
- confidence-scored
- reversible

Example transformations may remove tokens such as `(USA)` or `-memorypsp` for comparison while preserving them in original evidence.

Fuzzy matching cannot silently convert an uncertain candidate into an exact match.

## 7. Candidate resolution

Every candidate must carry evidence.

Conceptual structure:

```text
Candidate
├── Identity
├── Evidence[]
├── Score
├── Confidence
├── Conflicts[]
└── ResolutionState
```

Resolution states should distinguish at minimum:

- `EXACT_HASH`
- `EXACT_MULTI_HASH`
- `STRUCTURAL_MATCH`
- `STRONG_METADATA_MATCH`
- `FUZZY_MATCH`
- `AMBIGUOUS`
- `NO_MATCH`
- `CONFLICT`
- `UNSUPPORTED`

Exact and heuristic states must never be represented by one generic boolean such as `matched=true`.

## 8. Confidence

Confidence is not certainty.

Confidence must be derived from evidence and conflicts.

A high score with contradictory authoritative evidence remains unresolved.

The engine should expose reasons such as:

- SHA1 exact match
- CRC32 + size exact match
- filename strongly matches canonical title
- region token conflicts
- multiple releases share same CRC32
- DAT source disagreement

Users must be able to understand why a result was selected.

## 9. Ambiguity handling

Ambiguity is first-class state.

When multiple candidates remain plausible, the engine must:

1. gather additional inexpensive evidence
2. request stronger hashes where useful
3. compare platform/context
4. compare region/revision/language tokens
5. present candidates if still unresolved
6. prohibit automatic destructive action

The engine must prefer **unknown** over confident-looking wrong data.

## 10. Canonical filename generation

Canonical filename is a projection of identity.

It must not be treated as primary database identity.

Naming policy shall be configurable by profile.

Profiles may target:

- No-Intro-style output
- Redump-style output
- RetroArch-friendly output
- user-defined frontend conventions

A naming profile must define:

- token order
- separators
- region representation
- revision representation
- language representation
- version representation
- disc numbering
- special-status tags
- illegal filesystem characters
- maximum length handling

## 10A. Media type

Media type is first-class metadata on both catalogued dumps and observations, drawn from a versioned controlled vocabulary.

Recognised classes include cartridge, optical disc, floppy disk, tape, hard disk, digital download and arcade board, plus `UNKNOWN` as a valid escape state.

Rules:

- Media type is derived from a name and is therefore representation, never identity.
- It must never on its own exclude a candidate. A medium disagreement is a weighted contradiction with a visible reason, not an exclusion.
- Extensions belonging to more than one medium (`.bin`, `.img`, `.rom`) resolve to `UNKNOWN`. An unknown medium only widens what is considered.
- A cartridge record must not outrank a disc record when the artifact on disk is a disc image.

Optical-disc images — including PSP UMD rips as `.iso`, `.cso` and `.pbp` — are identified by the same evidence ladder as any other dump. They are never treated as unsupported for being large or for being discs.

## 10B. Dataset provenance and coverage

Every imported dataset records the preservation project that produced it, read from the DAT header and the user-supplied provider namespace. Provenance explains results; it never restricts what is consulted.

Coverage is measured per dataset from the media types of the records it indexes, counting only records fit for matching. A dataset whose disc entries are all `nodump` placeholders does not cover discs.

Coverage is read once per scan, not once per artifact.

## 10C. Reporting absence

The engine distinguishes:

- **not listed** — datasets cover this medium, none describes this artifact;
- **not covered** — no dataset covers this medium;
- **nothing imported** — the catalogue cannot speak.

These are separate result states, separately counted in a scan summary, and separately worded. "No catalogue match" is never presented as "unknown game".

## 10D. Verified against inferred identity

Alongside its confidence label, every result carries an identity basis: verified content, structural, inferred, or none.

Filename and text fallback remain required capabilities and always produce an *inferred* basis. Inferred identity is a usable result and is never presented as content verification; it never renames without confirmation.

## 11. Rename safety

Rename is a transaction-like operation.

Before execution:

- resolve entire batch
- detect duplicate destinations
- detect source/destination collisions
- validate filesystem constraints
- validate permissions
- validate unsupported operations
- produce preview

No partial execution should occur merely because first entries succeeded.

The system shall maintain a rename journal sufficient to reconstruct intended and completed operations.

Where platform limitations prevent atomic rollback, the UI must state that limitation.

## 12. Dry run

Every batch operation must support dry-run mode.

Dry run shows:

`Original → Proposed → Match Type → Confidence → Evidence`

No filesystem mutation occurs during dry run.

## 13. Idempotence

Running normalization twice against an already-normalized file should produce no further mutation.

Desired property:

`normalize(normalize(x)) = normalize(x)`

Where identity is unchanged, repeated scans must converge on the same result.

## 14. Progressive scanning

Large collections must produce progressive results.

The UI must not wait for complete-library scanning before displaying useful results.

Each result should move through states such as:

`DISCOVERED → FILTERED → HASHING → MATCHED → REVIEW_REQUIRED → RESOLVED`

Failures must remain visible without terminating unrelated work.

## 15. Performance

The engine must optimize for thousands to potentially hundreds of thousands of files.

Rules:

- stream filesystem traversal
- avoid unnecessary IPC
- avoid unnecessary hashing
- cache reusable hashes
- index DAT metadata
- perform expensive work only when evidence requires it
- bound concurrency
- avoid loading entire archives/DATs into memory
- support cancellation
- release resources promptly

Performance must never justify weakening identity correctness.

## 16. Android storage

Android storage is infrastructure, not domain logic.

The domain must depend on abstractions such as:

- `FileSource`
- `DirectoryWalker`
- `HashReader`
- `RenameExecutor`

Android SAF implementation belongs in infrastructure.

The known implementation optimization is recursive `DocumentsContract` cursor traversal to reduce massive IPC overhead associated with repeated `DocumentFile` operations.

## 17. Implementation baseline

The first vertical slice establishes:

- modular architecture with the dependency direction `UI → Application → Domain ← Infrastructure`
- core logic independent of UI, Android, SQLite and the filesystem
- SAF directory traversal over `DocumentsContract` cursors
- streaming Logiqx XML parsing
- offline DAT indexing by size, hash and title token, with provenance preserved
- streaming CRC32/MD5/SHA1 hashing with bounded concurrency
- ZIP inspection without extraction
- size-based filtering, reported as evidence
- progressive scanner output with cancellation
- ambiguous CRC escalation to a cryptographic hash
- whole-batch validation before mutation
- SAF in-place renaming with a durable journal and reconciliation
- filename sanitization and typed token classification
- bounded fuzzy fallback matching
- Jetpack Compose UI foundation

Everything above is covered by JVM tests except the three Android adapters
(SAF traversal, SAF rename, Compose), which are written but await verification
on a device against real storage providers. That gap is recorded rather than
glossed over: an unverified claim about provider behaviour is not evidence.

This baseline is implementation evidence, not a constraint that prevents architectural improvement.

## 18. Testing

Test corpus must include:

- exact known dumps
- duplicate hashes
- CRC collisions/ambiguity
- region variants
- revisions
- alternate titles
- scrubbed scene releases
- malformed filenames
- misleading filenames
- archives
- nested archives
- missing DAT entries
- malformed DAT files
- conflicting DAT sources
- duplicate destination names
- filesystem rename failures
- permission failures
- cancellation during scanning
- interrupted batch operations

Every discovered production bug should become a permanent regression test where practical.

## 19. Privacy

ROM Intelligence operates locally by default.

Scanning must not require uploading ROM contents or hashes to an external service.

External metadata services, if ever introduced, must be explicit adapters and opt-in.

The system must not make network access a hidden requirement for basic identification.

## 20. Auditability

For every consequential action, retain enough information to explain:

- original artifact
- original filename
- observed properties
- candidate identities
- selected identity
- evidence
- confidence/resolution state
- naming policy
- proposed action
- completed action
- timestamp/session

This enables debugging, rollback, user trust, and future engine improvement.

## 21. Separation of concerns

ROM Intelligence must not contain UI concerns.

UI must not implement matching rules.

DAT parsers must not decide user-facing naming policy.

Hashing must not know about Android.

Renaming must not determine identity.

Identity resolution must not depend on a particular frontend.

These boundaries are architectural invariants.

## 22. Future expansion

The same identity engine should eventually support:

- physical games
- cartridge identification
- disc identification
- arcade software
- firmware
- save files
- patches
- translations
- hacks
- homebrew
- hardware compatibility
- collection completeness
- preservation status

ROM normalization is therefore the first domain implementation, not the final product definition.

## 23. Defining principle

**Never rename a file because it looks right. Rename it because RetroVault can explain why it believes the file represents that canonical identity.**

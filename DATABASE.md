# DATABASE.md

**Project:** RetroVault
**Role:** Canonical persistence specification
**Authority:** `CONSTITUTION.md`

## 1. Purpose

SQLite is local source of operational truth.

Database stores identity, evidence, relationships, collection state, scan history, and user decisions. It does not store raw ROM content.

## 2. Principles

- deterministic schema
- explicit identifiers
- foreign-key integrity
- migrations only forward
- no hidden destructive migrations
- provenance preserved
- uncertainty preserved
- derived data rebuildable where practical
- indexes serve actual access patterns
- database remains useful offline

## 3. Identity model

Major entities require stable internal IDs independent of filenames, DAT names, and external IDs.

Core entities:

- Platform
- PlatformRevision
- Game
- Release
- SoftwareArtifact
- DumpIdentity
- FileObservation
- Evidence
- Source
- Relationship
- CollectionItem
- StorageLocation
- ScanSession
- RenameOperation
- NamingProfile
- UserDecision

External identifiers must be namespaced by source.

Example:

`no_intro:game:12345`
`redump:release:67890`

Never assume two external IDs are globally unique.

## 4. Artifact separation

Do not collapse these concepts:

`File` ≠ `Archive` ≠ `Dump` ≠ `Release` ≠ `Game`

A local file is an observation of something. It is not automatically canonical identity.

## 5. Evidence model

Evidence records should capture:

- subject entity
- evidence type
- source
- observed value
- normalized value where applicable
- timestamp
- confidence contribution
- provenance
- supersession state

Evidence should be append-oriented. Corrections should not erase historical truth without a migration reason.

## 6. Relationship model

Relationships must be explicit and typed.

Examples:

- release-of-game
- dump-of-release
- file-represents-dump
- revision-of
- regional-variant-of
- translated-from
- patched-from
- hack-of
- contained-in-archive
- requires-firmware
- compatible-with-hardware

A generic untyped relationship table may exist internally, but user-facing semantics require typed relationships.

## 7. Collection model

Collection state is user-specific.

Separate:

- known canonical release
- user believes they own it
- local artifact exists
- artifact has been verified
- artifact is backed up
- artifact is missing

Ownership must never be inferred solely from a filename.

## 8. Local observation model

A file observation records what scanner saw at a point in time:

- URI/path reference
- filename
- extension
- byte size
- modified timestamp if available
- archive/container status
- hashes obtained
- scan session
- storage location

Observations may become stale.

## 9. Hash storage

Store algorithm + digest separately or in a normalized hash table.

Supported baseline:

- CRC32
- MD5
- SHA1

Architecture must permit future algorithms without schema redesign.

Hashes are evidence, not identity by themselves.

## 10. Scan sessions

Every scan belongs to a session.

Session records should include:

- start/end time
- root location
- configuration
- DAT/index versions
- naming profile
- scanner version
- counts
- cancellation/failure state

This permits reproducibility and stale-result detection.

## 11. Rename journal

Rename operations require durable intent.

Record:

- operation ID
- batch ID
- source URI
- destination URI
- resolved identity
- evidence/resolution state
- naming profile
- precondition
- execution state
- error
- timestamps

States:

`PLANNED → VALIDATED → EXECUTING → COMPLETED`

Failure states must be explicit.

## 12. Derived data

Search indexes, cached candidate scores, normalized tokens, and presentation summaries are derived data.

They may be rebuilt from canonical records.

Canonical evidence must not depend on derived indexes.

## 13. Full-text search

SQLite FTS may index:

- canonical titles
- alternate titles
- serials
- filenames
- publisher/developer
- searchable metadata

FTS results must resolve back to canonical IDs.

FTS must never become identity authority.

## 14. DAT import storage

DAT source metadata must retain:

- source name
- source version
- import timestamp
- source checksum where available
- machine/platform
- raw canonical fields needed for reprocessing

Imported records should be versioned so DAT updates do not silently mutate historical scan explanations.

## 15. Migrations

Every schema change requires:

- migration number
- deterministic migration code
- forward test
- downgrade strategy decision
- data-loss assessment

Production migration must fail safely.

Never silently drop user collection state.

## 16. Transactions

Use transactions for:

- DAT imports
- batch metadata updates
- rename journal state transitions
- user decisions
- relationship changes

Long-running file hashing must not hold database transactions open.

## 17. Concurrency

Database writes must be serialized through application-level repositories/use cases where practical.

Concurrent scan workers may calculate observations independently, then commit bounded batches.

## 18. Privacy

No ROM payloads in database.

No cloud dependency for core operation.

No telemetry required for identity resolution.

## 19. Integrity rules

Examples:

- foreign keys enforced
- canonical entity IDs immutable
- external source IDs namespaced
- duplicate hashes allowed when they genuinely represent duplicate artifacts
- filename uniqueness not assumed
- collection item may reference multiple observations
- deleted filesystem file does not delete historical observation automatically

## 20. Performance

Indexes must support:

- hash lookup
- size + platform filtering
- canonical title search
- filename search
- scan-session queries
- unresolved observations
- collection browsing
- rename journal lookup

Do not index every field by default.

## 21. Recovery

Database corruption must not be able to corrupt source ROM files.

Filesystem operations and database operations must be independently recoverable.

Rename journal provides reconciliation after interrupted execution.

## 22. Testing

Database tests must cover:

- fresh install
- every migration path
- foreign-key violations
- duplicate entities
- duplicate observations
- DAT re-import
- stale scans
- interrupted rename operations
- concurrent scans
- search index rebuild
- export/import

## 23. Export

Users must eventually be able to export portable collection knowledge without exporting ROM payloads.

Export should include enough identity/evidence data to reconstruct collection state.

Format must be versioned.

## 24. Non-goals

Database does not:

- execute hashing
- traverse Android storage
- rename files
- render UI
- call network services
- determine fuzzy similarity

Those belong to domain/application/infrastructure layers.

## 25. Guiding rule

**Store what was observed, what was concluded, why it was concluded, and what the user decided. Never store only the conclusion.**

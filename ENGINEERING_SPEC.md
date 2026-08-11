# ENGINEERING_SPEC.md

**Project:** RetroVault
**Role:** Implementation rules
**Authority:** `CONSTITUTION.md`

## 1. Architecture

Required dependency direction:

`UI → Application → Domain ← Infrastructure`

Domain must not depend on Android, Compose, SQLite, filesystem APIs, or network clients.

## 2. Modules

Recommended initial modules:

- `core-domain`
- `core-application`
- `core-data`
- `core-identification`
- `platform-android`
- `feature-scanner`
- `feature-renamer`
- `app`

Names may change if dependency boundaries remain intact.

## 3. Kotlin

Use idiomatic Kotlin.

Prefer:

- immutable data
- sealed types for finite states
- explicit result/error types
- dependency injection through constructors
- suspend functions for I/O
- coroutines with bounded concurrency

Avoid global mutable state.

## 4. Android

Android APIs remain at platform boundary.

Storage Access Framework implementation must be isolated.

Compose renders application state. Compose must not contain matching or rename rules.

## 5. Application layer

Use cases coordinate workflows.

Initial use cases:

- ImportDat
- ScanLocation
- ResolveArtifact
- ReviewCandidate
- GenerateRenamePlan
- ValidateRenamePlan
- ExecuteRenamePlan
- ReconcileInterruptedRename

## 6. Domain layer

Domain owns:

- identities
- evidence
- candidates
- confidence
- naming policies
- resolution states
- rename plans
- invariants

Domain must be deterministic.

## 7. Repository interfaces

Infrastructure implements interfaces for:

- DAT storage
- artifact reading
- hash calculation
- observation persistence
- canonical identity lookup
- rename execution
- scan session persistence

Application/domain must depend on interfaces, not implementations.

## 8. Error model

Expected failures are typed.

Examples:

- PermissionDenied
- UnsupportedStorage
- InvalidDat
- HashReadFailure
- AmbiguousMatch
- DestinationCollision
- RenameFailed
- Cancellation

Unexpected exceptions must not be swallowed.

## 9. Cancellation

Long operations must support cooperative cancellation.

Cancellation must leave persistent state consistent.

## 10. Testing

Every domain rule requires unit tests.

Every infrastructure adapter requires integration tests where practical.

Critical workflows require end-to-end tests.

Tests must cover false positives, not merely successful matches.

## 11. Determinism

Same input + same knowledge sources + same configuration must produce same resolution.

If nondeterminism is unavoidable, record its source.

## 12. Logging

Logs must support diagnosis without leaking ROM content.

Never log full user storage paths unless explicitly enabled for diagnostics.

Never log ROM payloads.

## 13. Performance budgets

Do not optimize blindly.

Measure:

- directory traversal throughput
- DAT import time
- hash throughput
- memory usage
- database query latency
- UI update frequency
- rename execution time

Large scans must not freeze UI.

## 14. Security

Treat filenames, DAT files, archive metadata, and filesystem responses as untrusted input.

Prevent:

- path traversal
- invalid destination generation
- archive recursion abuse
- excessive resource consumption
- malformed XML parser failures

## 15. Git discipline

Small coherent commits.

No generated secrets.

No local ROM collections in repository.

No proprietary DAT files committed unless redistribution rights permit it.

Documentation changes and implementation changes should be separable when practical.

## 16. Definition of done

A feature is not complete merely because it compiles.

Definition includes:

- tests
- failure handling
- cancellation where relevant
- persistence behavior
- UI state
- documentation
- performance consideration
- regression coverage

## 17. Implementation order

1. domain primitives
2. database schema
3. DAT parser/index
4. hashing
5. candidate resolver
6. scanner
7. rename planning
8. Android SAF adapter
9. application orchestration
10. Compose UI
11. integration tests
12. performance testing

## 18. Anti-shortcuts

Do not:

- put business logic in ViewModels
- use filename equality as identity
- use fuzzy matching as exact identity
- couple domain to SQLite
- couple scanner to Compose
- make network services mandatory
- swallow errors to make tests pass
- weaken safety to simplify UX

## 19. Build philosophy

Build the smallest correct vertical slice.

Then expand.

Prefer boring, testable mechanisms over clever abstractions.

## 20. Guiding rule

**Correctness first. Explainability second. Performance third. Convenience fourth.**

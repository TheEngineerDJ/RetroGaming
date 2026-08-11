# BUILD_PLAN.md

**Project:** RetroVault
**Status:** Initial implementation plan
**Authority:** `CONSTITUTION.md` + derived specifications

## 1. Objective

Build a narrow, trustworthy vertical slice first:

**Android storage → DAT index → identify ROM → explain match → preview canonical rename → safely rename → persist audit trail**

## 2. Milestones

### M0 — Repository baseline

- inspect existing Android project
- establish module boundaries
- establish Kotlin/toolchain versions
- establish test infrastructure
- document current implementation gaps

### M1 — Domain foundation

Implement pure domain types:

- Platform
- Game
- Release
- Artifact identity
- Evidence
- Candidate
- ResolutionState
- NamingProfile
- RenamePlan

No Android dependencies.

### M2 — Persistence

Implement SQLite schema and repositories.

Verify migrations and indexes.

### M3 — DAT ingestion

Implement streaming Logiqx parser.

Build indexed lookup.

Persist source/version/provenance.

### M4 — Hashing

Implement streaming CRC32, MD5, SHA1.

Add bounded coroutine concurrency.

Add ZIP inspection.

### M5 — Resolution engine

Implement deterministic candidate pipeline:

size → CRC32 → stronger hash → normalized metadata → fuzzy fallback

Expose evidence and conflicts.

### M6 — Scanner

Implement progressive scan pipeline.

Persist observations and scan sessions.

Support cancellation.

### M7 — Rename planner

Generate canonical names from resolved identity.

Validate entire batch before mutation.

Implement dry run + journal.

### M8 — Android SAF

Implement storage adapter.

Optimize recursive traversal.

Implement safe rename operation.

### M9 — UI

Implement minimal Compose workflow:

folder selection → scan → results → detail → rename preview → execution → history

### M10 — Verification

Run corpus, integration, migration, performance, and end-to-end tests.

## 3. Explicit non-goals for first vertical slice

Do not initially build:

- social features
- accounts
- cloud collection sync
- marketplace/value tracking
- broad metadata scraping
- recommendation engine
- AI identification service
- elaborate collection dashboards

The first milestone must prove identity resolution and safe action.

## 4. Exit criteria

First vertical slice is complete when a real Android device can:

1. select local ROM directory through SAF
2. scan recursively
3. load offline DAT data
4. identify known dumps by hash
5. identify selected scrubbed/scene files through fallback matching
6. distinguish uncertain matches
7. display evidence
8. generate canonical filenames
9. preview complete batch
10. reject collisions
11. rename safely
12. retain operation history
13. survive cancellation/failure without corrupting source files

## 5. Principle

**Prove the dangerous part first: identity.**

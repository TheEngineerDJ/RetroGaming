# RetroVault System Architecture

## Status

Derived from `Constitution.md`.

This document converts constitutional principles into an implementation-oriented architecture. It does not override the Constitution.

---

# 1. Architectural Prime Directive

RetroVault must remain understandable, testable, replaceable, offline-capable, and evidence-preserving.

Core domain logic must never depend on UI, Android framework details, network services, vendor APIs, or a specific storage implementation.

Dependency direction must point inward toward stable domain concepts.

---

# 2. System Shape

RetroVault is a layered, modular system:

```text
Presentation
    ↓
Application / Use Cases
    ↓
Domain
    ↓
Ports / Interfaces
    ↓
Infrastructure
```

Cross-cutting concerns:
- identity
- evidence
- provenance
- confidence
- auditability
- privacy
- performance

The domain must be usable without Android.

---

# 3. Core Modules

## 3.1 Domain Core

Owns canonical entities, value objects, relationships, claims, evidence, confidence, identity rules, and invariants.

No UI dependencies.
No Android dependencies.
No database dependencies.

## 3.2 Identity Engine

Converts observations into candidate identities.

Responsibilities:
- normalization
- deterministic matching
- hash matching
- metadata matching
- fuzzy matching
- candidate ranking
- ambiguity detection
- confidence calculation
- human confirmation

The engine must expose reasoning inputs. It must never return an unexplained magic answer.

## 3.3 Evidence Engine

Stores and evaluates evidence supporting claims and identifications.

Responsibilities:
- evidence registration
- source provenance
- evidence linking
- confidence contribution
- contradiction detection
- verification state
- audit trail

## 3.4 Knowledge Graph

Stores canonical entities and typed relationships.

The graph is logical architecture, not necessarily a graph database.

SQLite remains a preferred initial persistence technology.

## 3.5 Collection Engine

Represents user-owned or user-observed objects.

Responsibilities:
- ownership
- storage location
- condition
- completeness
- acquisition history
- provenance
- duplicate detection
- collection organization

## 3.6 Import Engine

Converts external data into internal observations.

Examples:
- No-Intro DAT
- Redump DAT
- CSV
- XML
- JSON
- local filesystem/SAF observations
- future community datasets

Imports must preserve source identity and import version.

## 3.7 Search Engine

Provides retrieval across canonical entities, aliases, relationships, claims, evidence, and user collection data.

Search must support both exact and exploratory discovery.

## 3.8 Action Engine

Performs controlled operations on user-owned data.

Examples:
- rename ROM
- move file
- export metadata
- create backup
- apply collection classification

Actions must be previewable, validated, auditable, and reversible where technically possible.

## 3.9 Presentation Layer

Displays domain state.

The UI must not implement identity rules, matching heuristics, persistence logic, or business rules.

## 3.10 Infrastructure Layer

Adapters for:
- SQLite
- Android Storage Access Framework
- filesystem access
- hashing libraries
- XML parsers
- network sources
- image/document storage
- future external APIs

Infrastructure is replaceable.

---

# 4. Dependency Rules

Allowed:

```text
UI → Application → Domain
Infrastructure → Domain ports
Application → Domain
```

Forbidden:

```text
Domain → Android
Domain → Compose
Domain → SQLite
Domain → HTTP client
Domain → filesystem
UI → direct database mutation
UI → matching algorithm
```

No shortcut is justified merely because it is faster to implement.

---

# 5. Ports and Adapters

External systems must be accessed through interfaces owned by the application/domain boundary.

Examples:

- `RomSource`
- `DatSource`
- `Hasher`
- `FileEnumerator`
- `FileRenamer`
- `EntityRepository`
- `ClaimRepository`
- `EvidenceRepository`
- `SearchIndex`
- `Clock`
- `IdGenerator`

Concrete Android or third-party implementations live outside core logic.

---

# 6. Processing Pipeline

General identification pipeline:

```text
Observation
→ Normalize
→ Extract signals
→ Deterministic match
→ Candidate generation
→ Candidate scoring
→ Evidence evaluation
→ Confidence classification
→ Human review when required
→ Canonical identity
→ Optional action
```

No destructive action occurs before identity reaches the required confidence threshold.

---

# 7. Observation vs Identity

An observation describes what the system actually saw.

An identity describes what the system believes the observation represents.

Example:

```text
Observation:
filename = "Pokemon Red (USA) [!].zip"
size = 524288
crc32 = ...
container = ZIP

Identity:
Game = Pokémon Red
Release = USA release
Dump = canonical verified dump
```

Observations must remain available even after identification.

This prevents later canonicalization changes from destroying original evidence.

---

# 8. Match Strategy

Matching must proceed from strongest evidence to weakest.

Recommended order:

1. exact cryptographic identity
2. exact known structural identity
3. exact DAT-derived identity
4. size + checksum evidence
5. normalized metadata
6. structured filename signals
7. fuzzy textual similarity
8. contextual inference

A weaker signal must not override stronger contradictory evidence without explicit rules.

---

# 9. Confidence and Actions

Identification states:

- `UNMATCHED`
- `CANDIDATE`
- `PROBABLE`
- `CONFIRMED`
- `CONFLICTED`
- `REJECTED`

Action policy:

| State | Automatic rename | User review |
|---|---:|---:|
| UNMATCHED | No | Optional |
| CANDIDATE | No | Yes |
| PROBABLE | Policy-dependent | Recommended |
| CONFIRMED | Yes | Optional |
| CONFLICTED | No | Required |
| REJECTED | No | No |

Thresholds must be configurable and versioned.

---

# 10. Database Boundary

Repositories expose domain-oriented operations.

Domain code must not know table names.

Database schema must not dictate domain terminology.

Persistence must support:
- migrations
- transactions
- indexes
- full-text search where useful
- provenance
- historical records
- deterministic identifiers
- safe recovery

---

# 11. Offline-First Rule

Core identification, collection management, search, hashing, DAT processing, and rename operations must work without network access when required source data is already present locally.

Network access may enrich knowledge.

Network access must not be required for basic ownership or local-file operations.

---

# 12. Android Boundary

Android-specific infrastructure includes:
- Storage Access Framework
- `DocumentsContract`
- permissions
- lifecycle
- background execution
- notifications
- Compose

These remain outside domain logic.

The existing optimized SAF cursor strategy belongs in infrastructure.

---

# 13. Performance Architecture

Large collections are normal, not exceptional.

Design targets:
- streaming processing
- bounded memory
- incremental results
- cancellation
- resumability
- minimal IPC
- indexed lookups
- avoidance of repeated hashing
- avoid loading entire archives into memory

A scan of thousands of files must not require one object graph containing every file.

---

# 14. Failure Architecture

Failures must be typed and observable.

Examples:
- inaccessible file
- permission denied
- malformed DAT
- unsupported archive
- hash failure
- ambiguous identity
- rename collision
- stale file reference
- interrupted scan
- database failure
- corrupt persisted state

One bad file must not silently terminate a complete scan unless system integrity is at risk.

---

# 15. Auditability

Consequential operations create audit records.

At minimum:
- scan started
- scan completed
- identity assigned
- identity changed
- rename proposed
- rename executed
- rename failed
- user override
- import performed
- source updated

Audit history must distinguish system decisions from user decisions.

---

# 16. Determinism

Given identical:
- input
- DAT version
- normalization rules
- matching rules
- configuration

The identity engine should produce identical results.

AI or external enrichment must not silently alter deterministic local identification.

---

# 17. Versioning

Version independently:
- schema
- DAT source
- normalization rules
- matching algorithm
- filename policy
- confidence model
- import adapter

A result must be explainable against the versions that produced it.

---

# 18. Security and Privacy

Local collection information is private by default.

Do not transmit filenames, hashes, collection inventories, or personal metadata without explicit user-controlled behavior.

Secrets and credentials must never enter the domain model.

External enrichment must minimize data exposure.

---

# 19. Testing Architecture

Every domain rule must be testable without Android.

Test layers:

1. pure unit tests
2. domain integration tests
3. persistence tests
4. adapter tests
5. corpus tests
6. performance tests
7. end-to-end UI tests

The ROM intelligence system requires a permanent regression corpus containing:
- clean dumps
- scene releases
- scrubbed files
- ambiguous names
- malformed archives
- duplicate identities
- regional variants
- known false-positive cases

---

# 20. Architectural Rule

If a module cannot be explained in terms of domain responsibility, input, output, and dependency direction, it is not ready to become production architecture.

Complexity must be justified by domain value.

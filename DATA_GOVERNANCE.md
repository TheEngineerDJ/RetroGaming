# DATA_GOVERNANCE.md

**Project:** RetroVault  
**Authority:** `CONSTITUTION.md`

## 1. Purpose

Define how RetroVault acquires, validates, transforms, stores, publishes, corrects, and retires knowledge.

The product's long-term value depends on data quality more than feature count.

## 2. Data classes

Every important record belongs conceptually to one or more classes:

- canonical entity
- observation
- claim
- evidence
- derived value
- user-private data
- imported external data
- system diagnostic data

These classes must not be silently mixed.

## 3. Provenance

Every externally sourced fact should retain:

- source identity
- source type
- source location where appropriate
- acquisition time
- source version where available
- transformation history
- verification state

A transformed record must remain traceable to its input.

## 4. Import policy

Imports are observations until validated.

Import pipelines must:

1. identify source
2. validate structure
3. normalize without destroying originals
4. detect duplicates
5. detect conflicts
6. preserve source identifiers
7. record import version
8. promote records only according to defined trust rules

An importer must never silently overwrite higher-quality existing evidence.

## 5. Source licensing

Before redistributing imported data, determine applicable licence and redistribution rights.

The repository must not contain copyrighted third-party datasets merely because they are useful for development.

Fixtures should be synthetic, minimal, user-supplied, public-domain, or otherwise permitted.

## 6. Canonical promotion

Imported information can become canonical only when its provenance and quality satisfy the relevant verification policy.

Canonical does not mean permanent.

Canonical records remain correctable and versioned.

## 7. Corrections

A correction should preserve:

- previous value
- corrected value
- reason
- evidence
- contributor/system actor
- timestamp

Do not erase the old value merely to make current data look clean.

## 8. Conflicting evidence

Conflict must be represented explicitly.

Preferred value may be selected for presentation, but alternatives remain available when materially relevant.

Conflict resolution should identify why one source was preferred.

## 9. Derived data

Derived values include:

- search indexes
- candidate scores
- price statistics
- compatibility summaries
- normalized tokens
- rarity indicators
- recommendations

Derived values must be rebuildable or clearly marked as cached.

## 10. User data

User collection data is private by default.

User decisions must not silently become public facts.

A user's observation that a cartridge has a particular PCB revision can become community evidence only through an explicit contribution workflow.

## 11. Deletion

Deletion policy depends on data class.

User-private records may be deleted according to product controls.

Canonical historical knowledge should normally be archived rather than hard-deleted.

Illegal or dangerous material may require removal subject to applicable policy.

Deletion events should themselves be auditable where legally and technically appropriate.

## 12. Data quality checks

Automated checks should detect:

- impossible dates
- broken references
- duplicate canonical entities
- contradictory region assignments
- impossible hardware relationships
- orphan claims
- invalid hashes
- malformed identifiers
- unsupported relationship types
- unexplained confidence changes

## 13. Data lifecycle

Conceptual lifecycle:

`DISCOVERED → IMPORTED → NORMALIZED → VALIDATED → CANONICAL → CORRECTED/UPDATED → ARCHIVED`

Not every record reaches canonical status.

## 14. Reproducibility

Important transformations should be reproducible from:

- source version
- transformation version
- configuration
- input identity

This applies especially to identification, statistics, and imports.

## 15. Governance boundary

Automated systems may suggest.

Rules may validate.

Contributors may propose.

Evidence determines trust.

No single mechanism should silently convert an inference into established fact.

## 16. Guiding rule

**Never lose provenance in the name of cleanliness.**

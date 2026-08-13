# DECISION_RECORD.md

**Project:** RetroVault  
**Authority:** `CONSTITUTION.md`

## Purpose

Record major architectural and product decisions so future implementation does not repeatedly reopen settled questions.

This is not a substitute for the Constitution. When a decision changes constitutional intent, update `CONSTITUTION.md` first.

## Decision format

Each significant decision should record:

- decision ID
- date
- status
- context
- options considered
- decision
- rationale
- consequences
- superseded decision, if applicable

## Initial decisions

### DR-001 — Constitution-first architecture

**Status:** Accepted

`CONSTITUTION.md` is product authority. Derived documents and code must align with it.

### DR-002 — Offline-first core

**Status:** Accepted

Local identification, collection operations, search over local knowledge, and file actions must not require network access.

### DR-003 — Evidence-backed identity

**Status:** Accepted

Identity resolution must preserve evidence and uncertainty. Filename similarity alone cannot establish verified identity.

### DR-004 — SQLite initial persistence

**Status:** Accepted

Use SQLite initially because it is embedded, offline, transactional, mature, and sufficient for the first knowledge graph implementation.

The logical graph must remain independent from SQLite.

### DR-005 — Android as first client

**Status:** Accepted

Android is the first implementation target because the immediate product problem is local ROM organization and Android storage access.

The domain must remain portable.

### DR-006 — Safe action pipeline

**Status:** Accepted

Consequential file operations use:

`PLAN → VALIDATE → JOURNAL → EXECUTE → RECONCILE`

### DR-007 — False positive costs more than missed match

**Status:** Accepted

Identification must prefer unresolved/review states over unsupported certainty.

### DR-008 — AI is subordinate to evidence

**Status:** Accepted

AI may assist discovery, normalization, summarization, candidate generation, and contribution workflows.

AI cannot silently upgrade evidence quality or become the source of truth.

### DR-009 — User data remains separate from public knowledge

**Status:** Accepted

Private collection observations do not become canonical facts without explicit contribution and verification.

### DR-010 — Historical truth is preserved

**Status:** Accepted

Corrections update current interpretation while retaining meaningful historical evidence and previous states.

## Change rule

When a decision is superseded:

1. preserve old decision record
2. create new decision record
3. update affected specifications
4. update Constitution if principle changes
5. update implementation
6. add regression coverage where relevant

## Guiding rule

**Decisions should make future work faster without making future thinking impossible.**

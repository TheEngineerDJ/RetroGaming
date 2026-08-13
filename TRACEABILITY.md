# TRACEABILITY.md

**Purpose:** Keep implementation documents coherent with the Constitution.

## Authority chain

`CONSTITUTION.md`
→ `ARCHITECTURE.md`
→ `DOMAIN_MODEL.md`
→ `ROM_INTELLIGENCE.md`
→ `DATABASE.md`
→ `UX_SPEC.md`
→ `ENGINEERING_SPEC.md`
→ `TESTING_SPEC.md`
→ `SECURITY_SPEC.md`
→ `BUILD_PLAN.md`
→ implementation

`CLAUDE_CODE.md` explains how an implementation agent must consume this chain.

## Core invariant

All derived documents may add implementation detail.

They may not contradict the Constitution.

If contradiction is discovered:

1. stop affected implementation
2. identify conflict
3. amend Constitution if constitutional decision is wrong
4. update derived specification
5. resume implementation

## Cross-document invariants

### Identity

`ROM_INTELLIGENCE.md`, `DOMAIN_MODEL.md`, and `DATABASE.md` must preserve separation between file, dump, release, game, and platform.

### Evidence

Evidence must survive from scanner → resolver → database → UI → audit record.

### Safety

`UX_SPEC.md`, `ENGINEERING_SPEC.md`, `SECURITY_SPEC.md`, and `ROM_INTELLIGENCE.md` must all preserve the rule that unresolved identity cannot trigger automatic rename.

### Offline-first

Core identification must remain functional without network services.

### Architecture

Domain cannot depend on Android, Compose, SQLite, or network implementations.

### Testing

Every identity rule must be testable independently of UI.

## Review checklist

Before implementation milestone completion:

- Does code match domain model?
- Does persistence retain evidence?
- Does scanner emit enough information for resolution?
- Does resolver expose reasons, not just scores?
- Does UI distinguish exact from heuristic results?
- Can rename operation be audited and reconciled?
- Are failures safe?
- Are new rules covered by regression tests?
- Did implementation introduce hidden network dependence?
- Did any shortcut weaken the Constitution?

## Completion state

The specification set is considered implementation-ready when these documents agree on the first vertical slice:

**SAF storage → observation → DAT/hash identification → evidence-backed resolution → canonical naming → validated rename plan → safe execution → persisted audit.**

# UX_SPEC.md

**Project:** RetroVault
**Role:** Product and interaction specification
**Authority:** `CONSTITUTION.md`

## 1. UX objective

RetroVault must make complex preservation data understandable without forcing users to understand preservation terminology.

Primary UX principle:

**Complexity belongs in the engine. Clarity belongs in the interface.**

## 2. Primary workflow

Core flow:

`Select storage → Scan → Identify → Review → Preview action → Execute → Verify`

Never hide identity uncertainty behind a successful-looking progress bar.

## 3. First-run

First run should establish:

- what RetroVault does
- what data stays local
- storage permission
- optional DAT sources
- default naming profile
- first scan location

Do not require account creation.

## 4. Scan screen

Show:

- files discovered
- files processed
- exact matches
- heuristic matches
- ambiguous items
- unmatched items
- errors
- current activity
- cancellation control

Progress must communicate useful work, not merely percentage.

## 5. Result states

Minimum visual distinction:

- Exact
- Strong
- Review required
- Ambiguous
- No match
- Error

Do not use color alone to communicate state.

## 6. Match detail

User can inspect:

- original filename
- proposed canonical name
- platform
- release identity
- region
- revision
- hashes
- size
- matching evidence
- source DAT
- confidence
- conflicting evidence

The interface should answer:

**Why did RetroVault choose this?**

## 7. Review workflow

User can:

- accept
- reject
- choose another candidate
- mark unresolved
- exclude file

User decisions must be persisted.

## 8. Rename preview

Before mutation show:

`OLD NAME → NEW NAME`

Also show:

- match type
- confidence
- warnings
- collisions
- invalid destinations

Batch execution is disabled until validation passes.

## 9. Collection view

Eventually provide views for:

- platforms
- games
- releases
- variants
- verified artifacts
- unresolved artifacts
- missing collection items

Collection UI must distinguish **known** from **owned** from **verified**.

## 10. Search

Search should accept human terms:

- game title
- alternate title
- serial
- filename
- platform
- release

Search results should converge on canonical entities rather than raw filenames.

## 11. Expert mode

Expert users may expose:

- raw hashes
- DAT source/version
- evidence weights
- candidate scores
- scanner diagnostics
- naming tokens
- storage URI

Expert controls should reveal complexity, not change identity rules.

## 12. Destructive actions

Renaming is reversible where platform permits.

Deletion is not part of initial ROM Intelligence scope.

Any destructive future operation requires explicit confirmation and stronger safeguards than rename.

## 13. Errors

Errors must explain:

- what failed
- what remains safe
- whether retry is possible
- whether user action is required

Do not show raw stack traces as primary UX.

## 14. Accessibility

Required baseline:

- scalable text
- screen-reader labels
- non-color state communication
- adequate touch targets
- meaningful focus order
- predictable navigation

## 15. Offline behavior

Core workflow must work without network connectivity.

Network availability must never be represented as required for basic scanning.

## 16. UX anti-patterns

Never:

- claim exact match for fuzzy match
- silently rename
- hide collisions
- imply ownership from file presence
- make users understand XML/DAT internals to perform normal work
- expose raw confidence numbers without interpretation
- block useful results until every file finishes

## 17. Product personality

RetroVault should feel:

- precise
- trustworthy
- technical without being hostile
- archival without being museum-like
- powerful without being cluttered

The product should feel like serious tooling built by people who understand games and preservation.

## 18. Initial UI boundary

Initial Android implementation should focus on:

1. folder selection
2. scan configuration
3. progressive scan results
4. match inspection
5. batch rename preview
6. safe rename execution
7. operation history

Do not build broad collection-management features before the identification workflow is trustworthy.

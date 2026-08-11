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

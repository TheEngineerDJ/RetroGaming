# RELEASE_SPEC.md

**Project:** RetroVault  
**Authority:** `Constitution.md`

## 1. Purpose

Define what must be true before RetroVault is shipped to users.

A release is a product state, not merely a compiled APK.

## 2. Release channels

Conceptual channels:

- development
- internal testing
- beta
- production

Each channel may expose different diagnostics, feature flags, and data sources, but core trust rules remain unchanged.

## 3. Release identity

Every build must be traceable to:

- source commit
- application version
- schema version
- identification-rule version
- naming-policy version
- DAT/index versions where applicable
- build environment

## 4. Quality gates

Production release requires:

- clean build
- all required automated tests passing
- migration tests passing
- no known critical data-loss issue
- no known unsafe automatic rename issue
- Android runtime smoke test
- representative ROM corpus test
- cancellation test
- rename collision test
- recovery/reconciliation test

## 5. Identification gate

Before release, verify that:

- exact hashes resolve correctly
- conflicting hashes never resolve as exact
- region distinctions survive matching
- revision distinctions survive matching
- fuzzy matches cannot silently become exact
- no-match remains no-match
- catalogue failure is distinguishable from no-match

## 6. Rename gate

Before release, verify:

- dry run performs no mutation
- complete batch validates before mutation
- collisions stop execution
- invalid destination names stop execution
- journal exists before mutation
- interrupted operations reconcile without guessing
- rerunning completed operations is safe

## 7. Android gate

Test on at least one physical Android device.

Where supported by product scope, test:

- internal storage
- removable storage
- SAF permissions
- large directories
- process interruption
- revoked permissions
- device restart

## 8. Data gate

Verify that release data:

- retains provenance
- does not contain accidental user data
- does not include unauthorized third-party datasets
- has reproducible migrations
- can rebuild derived indexes

## 9. Performance gate

Measure realistic workloads.

Do not define success solely as “the app did not crash.”

Track:

- startup
- scan throughput
- DAT import time
- hashing throughput
- memory
- database latency
- UI responsiveness
- rename execution

## 10. Rollback

Every production release must have a documented rollback or forward-fix strategy.

Database migrations must not depend on impossible downgrade assumptions.

Filesystem mutations must remain independent from application rollback.

## 11. Release notes

Release notes should describe user-visible changes and important safety/data changes.

Do not claim hardware validation that was not performed.

Do not claim complete platform support from JVM-only tests.

## 12. Post-release monitoring

Core product operation should not require telemetry.

When diagnostics exist, they must be privacy-preserving and explicitly governed.

User-reported failures should become regression tests where reproducible.

## 13. Guiding rule

**Never ship confidence that testing did not earn.**

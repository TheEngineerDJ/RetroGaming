# API_SPEC.md

**Project:** RetroVault
**Role:** Future API and interoperability contract
**Authority:** `Constitution.md`

## 1. Purpose

RetroVault should eventually expose knowledge without making the web application the core product.

API design must preserve the same evidence and identity semantics as the local system.

## 2. API principles

- read-first
- explicit versions
- stable identifiers
- provenance available
- confidence available
- pagination mandatory for large collections
- deterministic filtering
- no silent schema changes

## 3. Resource model

Likely resources:

- games
- releases
- platforms
- hardware
- revisions
- people
- companies
- artifacts
- evidence
- claims
- relationships
- benchmarks
- prices
- compatibility

## 4. Identity

Internal IDs are stable but opaque.

External identifiers are namespaced.

API consumers must not infer identity from display names.

## 5. Claims

Important API responses should expose enough context to distinguish:

`value + source + evidence + confidence + temporal scope`

A plain value endpoint may exist for convenience, but must not erase provenance internally.

## 6. Versioning

Breaking changes require a new API version.

Additive fields should be designed so old clients can ignore them safely.

## 7. Pagination

Use stable cursors for large datasets.

Avoid offset pagination where records can change frequently.

## 8. Search

Search should return relevance plus canonical identity.

Search ranking must not change identity resolution.

## 9. Rate limits

Public APIs must protect infrastructure without discriminating against legitimate archival use.

## 10. Export vs API

API access is not a substitute for user export.

Users should be able to obtain their own collection and contribution data in portable form.

## 11. Offline-first boundary

The Android identification workflow must not depend on this API.

API outages must not prevent local ROM identification.

## 12. Future integrations

Potential integrations:
- emulator frontends
- collection managers
- preservation tools
- media catalogues
- hardware databases
- websites
- research tools

Integrations must consume canonical identities rather than scrape presentation pages.

## 13. Guiding rule

**Expose the knowledge graph without exposing the internal implementation as a permanent contract.**

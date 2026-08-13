# PRESERVATION_SPEC.md

**Project:** RetroVault
**Role:** Digital and historical preservation rules
**Authority:** `CONSTITUTION.md`

## 1. Purpose

Preservation means retaining knowledge, provenance, context, and reproducibility across time.

RetroVault must preserve more than current answers.

## 2. Preservation priorities

1. identity
2. provenance
3. evidence
4. context
5. historical state
6. reproducibility
7. presentation

## 3. Digital artifacts

An artifact record should distinguish:
- original source
- acquisition context
- container format
- file identity
- hashes
- dump method where known
- tool/version where known
- modifications
- verification state

A filename is never sufficient provenance.

## 4. Modified artifacts

Distinguish:
- verified original dump
- verified modified dump
- translation
- patch-applied artifact
- hack
- trainer
- reconstruction
- uncertain modification

Never destroy original identity because modified artifacts are easier to catalogue.

## 5. Preservation evidence

Where legally and technically appropriate, preserve metadata about:
- manuals
- packaging
- labels
- PCB photographs
- screenshots
- scans
- repair documentation
- interviews
- contemporary advertisements
- magazines
- catalogs

Metadata should survive even when binary media cannot be redistributed.

## 6. Checksums

Cryptographic hashes provide integrity evidence.

Store algorithm, digest, source and verification context.

A hash proves equality with the hashed bytes. It does not by itself prove historical authenticity or ownership.

## 7. Source snapshots

When source content changes, important claims should retain historical source context where permitted.

Do not pretend today's page represents yesterday's information.

## 8. Tool provenance

Preservation workflows should record tools when they materially affect results.

Example:

`dump tool + version + settings + date`

This supports reproducibility and later investigation.

## 9. Historical corrections

Corrections must not erase the fact that an earlier claim existed when that history matters.

Prefer:

`old claim → correction → supporting evidence`

over silent replacement.

## 10. Legal boundary

Preservation metadata and copyrighted content are distinct.

The platform must not assume that preservation importance grants redistribution rights.

Repository content must respect licenses and copyright.

## 11. User-owned content

User files remain under user control.

Core metadata should never require uploading ROM payloads to a server.

## 12. Export

Preservation data should eventually be exportable in open, documented formats.

Users must not be locked into proprietary internal identifiers.

## 13. Long-term resilience

Avoid dependence on:
- one cloud provider
- one database engine
- one AI model
- one external metadata provider
- one URL remaining alive forever

External dependencies should be replaceable.

## 14. Guiding rule

**Preserve enough context that another person can understand not only what we believe, but why we believe it.**

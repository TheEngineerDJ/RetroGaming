# RetroVault Canonical Domain Model

## Status

Derived from `CONSTITUTION.md`.

This document defines the canonical conceptual model used by RetroVault. It is intentionally implementation-neutral. Database tables, Kotlin classes, API payloads, and UI models derive from these concepts.

---

# 1. Fundamental Rule

Do not model retro gaming as a list of games.

Model entities, observations, claims, evidence, relationships, and context.

A name is not identity.
A file is not a game.
A release is not a dump.
A console is not a hardware revision.
A price is not value.
A source is not proof.
A prediction is not verification.

---

# 2. Entity Families

Canonical entities fall into these families:

1. Software
2. Hardware
3. Media
4. People and organizations
5. Publications and documentation
6. Physical objects
7. Digital artifacts
8. Collection objects
9. Sources and evidence
10. Measurements and observations
11. Events and historical states

---

# 3. Software Model

## 3.1 Game Concept

Abstract work independent of a particular release.

Example:

`Super Mario Bros.` as a work.

The concept may have multiple releases, ports, revisions, translations, remakes, and regional variants.

## 3.2 Release

A commercially or historically meaningful distribution of a game concept.

A release may have:
- platform
- region
- language
- publisher
- developer
- release date
- media
- title
- product code
- rating
- packaging

## 3.3 Version

A software state within a release family where content or behavior materially differs.

## 3.4 Build

A specific compiled software artifact or development state.

Builds matter when preservation, compatibility, research, or identification requires the distinction.

## 3.5 Port

A release derived for another hardware/software platform.

## 3.6 Remake

A substantially recreated implementation of an existing game work.

## 3.7 Remaster

A materially enhanced presentation or technical revision retaining substantial identity with an earlier work.

## 3.8 Patch

A modification applied to another software artifact.

## 3.9 Translation

A language modification of an existing software artifact.

## 3.10 Hack / Modification

A modification changing behavior, content, presentation, or other characteristics.

## 3.11 Homebrew

Software developed outside the original commercial development context.

## 3.12 Prototype

A non-final development artifact with preservation significance.

## 3.13 Demo

A software artifact intended as a demonstration rather than the complete commercial product.

---

# 4. Digital Artifact Model

A digital artifact is a concrete file or set of files observed by the system.

Examples:
- ROM image
- disc image
- tape image
- floppy image
- archive
- patch file
- save file
- firmware image
- executable
- BIOS image

Digital artifact identity must be separate from software identity.

One software release can have many dumps.
One dump can be distributed under many filenames.
One archive can contain multiple digital artifacts.

---

# 5. Dump Model

A dump represents a preservation capture of a physical or digital source.

A dump may have:
- source media
- dump method
- dumper/tool
- date
- operator
- verification status
- checksums
- known database identifiers
- provenance

Dump identity must not be inferred solely from filename.

---

# 6. Hash Model

Hashes are evidence about bytes.

Supported initial algorithms:
- CRC32
- MD5
- SHA-1

Future algorithms may be added.

A hash record should preserve:
- algorithm
- value
- byte scope
- artifact/container context
- calculation method/version
- timestamp where relevant

A CRC32 collision is not an identity proof.

A hash must always be scoped to what was hashed.

---

# 7. Archive Model

Archives are containers, not automatically software identities.

A ZIP may contain:
- one ROM
- multiple ROMs
- manuals
- metadata
- patches
- unrelated files

The system must distinguish:

`Archive → contains → Artifact`

from:

`Artifact → represents → Release`

Hashing an archive and hashing its contained ROM are different observations.

---

# 8. Hardware Model

## 8.1 Hardware Family

Conceptual product family.

Example:

Game Boy family.

## 8.2 Hardware Model

Specific commercially identifiable model.

## 8.3 Hardware Revision

Meaningful manufacturing or engineering revision.

Revision distinctions may include:
- PCB
- CPU
- memory
- display
- power system
- connectors
- board layout
- component substitutions
- firmware
- compatibility

## 8.4 Development Hardware

Development kits and test hardware must be represented separately from retail hardware.

---

# 9. Media Model

Media represents physical distribution/storage technology.

Examples:
- cartridge
- optical disc
- floppy
- cassette
- card
- proprietary media

Media should connect to:
- release
- region
- physical object
- PCB
- digital artifact
- save technology
- packaging

---

# 10. PCB Model

A PCB is a physical board, not merely a cartridge.

It may have:
- board identifier
- revision
- components
- ROM chip
- RAM
- mapper
- save hardware
- region evidence
- manufacturing markings
- photographs

Multiple physical objects may share the same PCB design.

A PCB can therefore be an entity distinct from an individual cartridge.

---

# 11. Firmware Model

Firmware is software associated with hardware operation.

Firmware requires:
- target hardware
- version
- region where relevant
- release/build identity
- checksum where useful
- update relationship
- compatibility scope

Firmware versions must not be merged merely because user-visible behavior appears identical.

---

# 12. Peripheral Model

Peripherals include:
- controllers
- adapters
- memory cards
- link cables
- guns
- microphones
- cameras
- printers
- modems
- expansion hardware
- specialty accessories

Each may have compatibility relationships with hardware, software, regions, and revisions.

---

# 13. Physical Object Model

A physical object is an individual observable instance.

Examples:
- one console owned by a user
- one cartridge
- one boxed copy
- one controller
- one PCB

Physical objects may share a canonical identity while differing in condition, modifications, provenance, or completeness.

---

# 14. Collection Object Model

A collection object connects a user to an object or digital artifact.

It may record:
- ownership state
- acquisition date
- acquisition source
- acquisition price
- storage location
- condition
- completeness
- notes
- photographs
- provenance
- current status

Collection state belongs to the user/context.

Canonical identity belongs to the knowledge model.

---

# 15. Person and Organization Model

Represent:
- developers
- programmers
- artists
- composers
- publishers
- manufacturers
- distributors
- licensors
- preservation organizations
- community contributors

Roles must be relationship-scoped.

A person may be developer on one project and producer on another.

Do not encode roles permanently into person identity.

---

# 16. Publication Model

Publications include:
- manuals
- magazines
- advertisements
- catalogues
- service manuals
- strategy guides
- developer documentation
- press releases
- interviews
- websites
- forum posts
- videos

A publication may contain many evidence items.

---

# 17. Source Model

A source identifies origin.

Fields conceptually include:
- source type
- publisher/owner
- URL or physical location
- publication date
- access date
- archival identifier
- reliability context
- language

Source reliability must remain contextual rather than universal.

---

# 18. Evidence Model

Evidence is an addressable supporting observation.

Examples:
- page 42 of manual
- photograph of PCB
- measured latency
- SHA-1 result
- DAT entry
- physical inspection
- controlled compatibility test

Evidence can support, contradict, or qualify claims.

---

# 19. Claim Model

Canonical knowledge should be expressible as:

`Subject → Predicate → Object`

with:

- scope
- source
- evidence
- confidence
- temporal validity
- contributor
- verification state

Example:

`Console Revision B → uses CPU → SH-2 revision X`

A later revision can have a different claim without corrupting the parent model.

---

# 20. Relationship Model

Relationships are first-class entities when they need evidence, history, or attributes.

Core relationship types include:

- developed_by
- published_by
- manufactured_by
- distributed_by
- licensed_by
- released_on
- released_in
- localized_to
- derived_from
- port_of
- remake_of
- remaster_of
- compilation_of
- patches
- translates
- modifies
- contains
- represented_by
- dumped_from
- runs_on
- compatible_with
- incompatible_with
- requires
- supports
- includes
- bundled_with
- variant_of
- revision_of
- successor_to
- predecessor_of
- replaces
- related_to
- owned_by
- observed_in
- evidenced_by

Relationship semantics must remain explicit.

---

# 21. Temporal Model

Knowledge can change without identity changing.

Temporal data should distinguish:

- valid time: when the fact was true
- observed time: when RetroVault observed it
- recorded time: when the system stored it

Example:

A console price in 1995 and its price in 2026 are different observations of the same object identity.

---

# 22. Identity Model

Every canonical entity requires a stable internal identifier.

Display names are mutable.
Aliases are allowed.
Regional names are allowed.
Historical names are preserved.

Identity merges must be explicit.

Identity splits must preserve predecessor evidence.

Never destroy the historical identity path during a merge.

---

# 23. Alias Model

Aliases may include:
- alternate spelling
- regional title
- transliteration
- historical name
- abbreviation
- catalogue identifier
- common community name
- filename token

Aliases improve discovery.

Aliases must not automatically imply equivalence when ambiguity exists.

---

# 24. Identification Observation

An identification observation records what was actually found.

For a ROM file:

- original filename
- path/location reference
- byte size
- extension
- container
- hashes
- extracted metadata
- normalized filename
- DAT matches
- candidate identities

This observation must remain immutable after scan completion, except through explicit correction/versioning.

---

# 25. Identification Candidate

A candidate links an observation to a possible canonical entity.

It should preserve:
- candidate entity
- matching signals
- signal strengths
- contradictions
- confidence
- matcher version
- timestamp

The user or system may promote a candidate to confirmed identity.

---

# 26. Match Evidence

Signals include:

### Strong
- SHA-1
- exact known dump identity
- exact DAT match

### Medium
- MD5
- CRC32 with size
- exact structural metadata
- exact product code

### Weak
- normalized filename
- fuzzy similarity
- region token
- title similarity
- contextual platform inference

Signals must be independently represented so explanations remain possible.

---

# 27. Filename Model

Filename is an observation and presentation layer.

It must never become canonical identity.

RetroVault should support:

`Artifact identity → Naming policy → Canonical filename`

Different frontends may require different naming policies.

The canonical identity remains unchanged.

---

# 28. Naming Policy

A naming policy defines deterministic output from canonical metadata.

It may control:
- title
- region
- language
- revision
- version
- status tags
- disc number
- track number
- special metadata
- extension

Policies must be versioned.

A future policy must not silently rewrite an existing collection.

---

# 29. Save Data Model

Save data is separate from software identity.

It may relate to:
- game release
- software version
- hardware
- save format
- physical cartridge
- storage device
- user collection

Save data may have preservation and migration value independent of the original ROM.

---

# 30. Compatibility Model

Compatibility is conditional.

Represent:

`Subject → works_with → Target`

plus conditions such as:
- hardware revision
- firmware
- adapter
- region
- software version
- modification
- configuration

Never reduce conditional compatibility to a universal yes/no when the condition matters.

---

# 31. Modification Model

A modification changes an otherwise identifiable object or artifact.

Examples:
- modded console
- translated ROM
- patched executable
- flashcart firmware
- aftermarket shell
- upgraded storage

Modification must reference its base identity.

Original identity must remain recoverable.

---

# 32. Provenance Model

Provenance answers:

Where did this thing come from?

For digital artifacts:
- source media
- dump method
- dump source
- archive source
- acquisition source

For physical objects:
- seller
- previous owner where appropriate
- acquisition date
- photographs
- receipts where appropriate
- inspection history

Provenance should never be invented from assumptions.

---

# 33. Negative Knowledge

The system must be able to record that something is not established.

Examples:
- claimed revision not found
- compatibility not reproduced
- supposed prototype unsupported
- filename candidate rejected
- authenticity signal contradicted

Negative evidence prevents the same failed hypothesis from repeatedly resurfacing.

---

# 34. Entity Merge Rules

Merge only when identity equivalence is sufficiently established.

A merge must preserve:
- previous identifiers
- aliases
- claims
- evidence
- relationships
- audit history

If uncertain, link entities as related rather than merge them.

---

# 35. Entity Split Rules

Split when one canonical entity incorrectly represents materially distinct identities.

A split must preserve:
- original evidence
- affected claims
- provenance
- previous references
- correction history

Historical references must remain resolvable.

---

# 36. Canonical Identity Principle

The system's central pipeline is:

```text
Messy Artifact
→ Observation
→ Signals
→ Candidate Identity
→ Evidence Evaluation
→ Canonical Identity
→ Relationships
→ Context
→ Action
```

The final action may be rename, classify, display, compare, export, preserve, or do nothing.

This pipeline is the bridge between the existing ROM-normalization implementation and the larger RetroVault platform.

---

# 37. Model Invariants

The following must remain true:

1. Identity is not filename.
2. Identity is not hash alone.
3. Observation is not claim.
4. Claim is not evidence.
5. Source is not evidence.
6. Region is not merely text.
7. Revision is not merely version.
8. Condition does not redefine canonical identity.
9. Ownership does not redefine canonical identity.
10. Modification does not erase original identity.
11. Uncertainty is valid state.
12. Historical identity remains recoverable.
13. User corrections outrank automatic suggestions for that user's collection.
14. Stronger evidence outranks weaker evidence unless scope differs.
15. Destructive actions require sufficient identity confidence.

---

# 38. Canonical Data Model Test

A proposed new entity should exist only when collapsing it into another entity would lose information needed for at least one of:

- identification
- preservation
- compatibility
- history
- collection management
- repair
- valuation
- search
- provenance

A proposed new relationship should exist when the connection itself carries meaning, evidence, conditions, or history.

This prevents ontology bloat while protecting important distinctions.


## Media type, dataset coverage and identity basis

`MediaType` is a versioned controlled vocabulary (`media-v1`) covering cartridge, optical disc, floppy disk, tape, hard disk, digital download and arcade board, with `UNKNOWN` as a valid escape state.

- `DumpRecord.mediaType` is stored, derived at import from the catalogued rom name.
- `FileObservation.mediaType` is computed from the identity-bearing name, so it can always be recomputed from the observation and never becomes a second source of truth.

`DatasetKind` (`dataset-kind-v1`) records which preservation project produced a dataset — No-Intro, Redump, TOSEC, MAME, GoodTools or unknown — read from what the DAT states about itself. It is provenance, not authority: nothing consults it to decide what to search.

`DatasetCoverage` is what one dataset actually indexes, measured from the media types of its matchable records. `CatalogueCoverage` aggregates them; `CatalogueCoverage.UNMEASURED` means a caller did not look, and is not the same as "nothing is covered".

`DatasetCompatibility.assess` is a pure function returning `Covered`, `NoDatasets` or `MediaNotCovered`. Every uncertain input resolves to `Covered`: unmeasured coverage, an unknown observed medium, a dataset holding any record of unrecognised medium, and a dataset that indexes nothing all read as covered. Only a catalogue whose every indexed medium is recognised, and does not include the observed one, can put an artifact out of scope.

`ResolutionState` gains `OUT_OF_CATALOGUE_SCOPE`, distinct from `NO_MATCH`. `NO_MATCH` means the datasets cover this medium and do not list this artifact; `OUT_OF_CATALOGUE_SCOPE` means they never had standing to say anything.

`IdentityBasis` — `VERIFIED_CONTENT`, `STRUCTURAL`, `INFERRED`, `USER_ASSERTED`, `NONE` — is derived from the resolution state and answers "resting on what", where confidence answers "how sure". A state that may carry a selected identity always has a basis other than `NONE`, and a state that may not always has `NONE`. `USER_ASSERTED` is deliberately not folded into `VERIFIED_CONTENT`: a person naming a release is the highest authority over their own collection and is still not a statement about the bytes.


## The canonical entity model

`Platform -> Work -> Release -> Artifact` (Constitution section 305), implemented in `com.retrovault.domain.entity`.

- **Platform** is family-level (Constitution section 33). Hardware models and revisions are not modelled yet; when they are they hang below a platform rather than replacing it.
- **Work** is the game concept (section 31). It carries no region, revision or platform: those distinguish releases *of* it.
- **Release** is one published form. Its identity is exactly `CanonicalIdentityKey` — the key the resolver already groups records by. That is not a coincidence: the key is how a release is recognised, and the release is the entity it recognises.
- **Artifact** is one digital image of a release (section 38). A disc preserved as `.cue` and as `.chd` is one release with two artifacts.

Entities are *projections* of `DumpRecord`, not a second copy of it. A dump record is external evidence written by a dataset (section 145); an entity is RetroVault's reading of what that evidence describes. One record projects into one artifact, but records from several datasets project into the *same* release, which is the whole reason the distinction is needed.

Identifiers are derived, not generated, so promoting the same identity twice produces the same entity. `dump_record.release_id` stores the projection key, which is what lets a correction name any catalogued release rather than only ones a scan happened to promote.

### Deliberate under-merges

`EntityPromoter` scopes a work to its platform, so the same title on SNES and on PSP is two works. Section 32 requires a port to be an explicit, evidenced relationship, so deriving one work automatically would be exactly the inference that section forbids. Two works joined later by a confirmed `PORT_OF` edge lose nothing; a wrong merge is expensive to undo.

Likewise a platform is identified by name alone, so two datasets naming one console differently produce two platforms. Merging them is a canonical merge, which section 43 reserves for human or high-confidence evidence.

### Relationships

`RelationshipType` is a controlled, versioned vocabulary (Constitution section 40). Structural edges — `RELEASE_OF`, `RUNS_ON`, `IMAGE_OF` — are what make the graph a hierarchy and are derived automatically. Derivation edges — `PORT_OF`, `REMAKE_OF`, `REMASTER_OF`, `INCLUDED_IN` — are historical claims section 32 says must not be inferred from marketing language, so nothing derives one; they exist only as `CONFIRMED`.

Section 40 lists many more relations. Each needs an entity type RetroVault does not yet have, and a relation whose other end cannot exist describes nothing, so they arrive with the entities they connect.

### Provenance

`EntityProvenance` is `DERIVED` or `CONFIRMED`. A derived write never overwrites a confirmed row — enforced in SQL, not in the caller, because a caller can forget. Section 43: automation proposes, people establish.

## Durable user corrections

`IdentityCorrection` implements Constitution section 69 and DOMAIN_MODEL invariant 13.

**Scope is content, never filename or observation id.** A filename is representation, so a correction keyed on one would follow the wrong file the moment anything was renamed — including by RetroVault. An observation id is minted per scan, so a correction keyed on one would silently stop applying. Only a cryptographic hash is accepted: CRC32 is a discriminator rather than content proof (section 148), and 32 bits would eventually attach a user's assertion to bytes they never saw. A correction that cannot be made durable is refused rather than made.

**Corrections outrank automatic identification, without claiming verification.** `USER_CORRECTED` carries the user's identity with `IdentityBasis.USER_ASSERTED`; the overruled candidate stays in the result with its evidence intact (section 44). `USER_REJECTED` selects nothing and is distinct from `NO_MATCH`: the catalogue did answer and a person rejected it.

**History is append-only.** Superseding writes a new row and marks the old one, so "what did I say before, and why" stays answerable (section 70). Withdrawing marks a row withdrawn, which is a different fact from never having corrected.

**Correcting an identity is not authorising a rename.** A correction settles what RetroVault *believes*; whether the file may be touched is a separate question, because confidence alone never authorises a mutation (Constitution section 262) and an assertion is a claim about identity rather than a measurement of it. So `USER_CORRECTED` is reviewable like any other unverified identity. It becomes `AUTOMATIC` only when the content independently agrees — when a cryptographic hash of the bytes matches the digest catalogued for the release the user named — and then the rename rests on the measurement, with the correction having merely pointed at it. CRC32 agreement does not count (section 148). A rejected identity is never renamed at all.

`Candidate.hasIndependentContentAgreement` reads that from the evidence rather than from the resolution state, so the answer follows what was actually established rather than how the identity arrived. It matches on the signal's stable id, not on its Kotlin type: a persisted signal returns from storage as `MatchSignal.Recorded`, and the rename planner only ever sees persisted resolutions.

## Reading the entity graph

`EntityQueries` (application layer) is the read surface for `Platform -> Work -> Release -> Artifact`, separate from `EntityGraph`, which writes it. The split is not ceremony: a caller that only browses should not be able to promote or relate, and the read surface is where result bounds and provenance exposure are enforced.

**Every list query is bounded.** `EntityPage<T>` carries `hasMore`, measured by fetching one row more than the caller asked for rather than by a second `COUNT(*)`. Section 249 requires bounded memory for large collections, and a page that cannot say it was truncated misleads the user about their own library. `MAX_LIMIT` is a ceiling a caller cannot raise; an out-of-range limit is clamped rather than honoured or rejected.

**Search matches aliases and normalized titles**, using the same normalizer the resolver uses, so "Legend of Zelda, The" finds a work stored as "The Legend of Zelda" without a second set of rules to keep in step. Section 43 makes aliases search aids. User search text is escaped before it reaches `LIKE`: someone typing `100%` is searching for a title, not writing a wildcard.

**Relationships are returned in both directions.** A release's incoming `IMAGE_OF` edges are how a caller reaches its artifacts; a graph that could only be walked downwards would leave half of it unreachable.

**`provenanceOf` hides nothing.** It carries the entity's provenance, its timestamps, its aliases, the datasets whose records project into it (section 196: a dataset must never become an invisible authority), every edge touching it, and — for an artifact — the complete correction history including superseded and withdrawn entries. A history that shows only the current answer is not a history (section 70). `independentSourceCount` counts datasets rather than claiming corroboration, because several sources are not automatically independent confirmation (section 46).

## Historical identity

Constitution sections 41 and 70 and invariant 12 require earlier knowledge to stay reconstructable. RetroVault does not yet have a temporal system, and inventing one before there are dated facts to hold would be architecture ahead of evidence. What is implemented is the minimum that stops history being *destroyed* in the meantime:

- **A display name an entity stops carrying is retained as an alias.** Section 43 already lists historical names among the aliases an entity must preserve, so this needs no new concept: a user searching for what their library used to be called still finds it.
- **`first_seen_at` and `last_updated_at`** record when RetroVault learned about an entity and when it last changed its mind. `EntityTimestamps` reports the migration default as *unknown* rather than as 1 January 1970 — "the row predates RetroVault recording this" is a different fact from a timestamp at the epoch, and reporting one as the other would be a fabricated observation.
- **Correction history is append-only**, so what a user asserted before a supersession stays answerable.

Release dates, manufacture dates, acquisition dates and the rest of section 41's list are facts about entities RetroVault does not model yet. They arrive with those entities.


## Modified dumps

Constitution section 200 requires the matching architecture to classify a representation difference before rejecting a candidate, and section 306 requires **Modified** to be a distinguishable state: "artifact differs from a known reference but may retain useful identity evidence". A copier-headered ROM is the ordinary case — and the common one, because a large share of real SNES and NES libraries carry headers.

`RomHeaderDetector` recognises prefixes that can be **skipped** to reach the catalogued dump: iNES, Famicom Disk System, Lynx and Atari 7800 by magic number, and the SNES copier header by size, because every SNES dump is a whole number of kilobytes and a file 512 bytes over a kilobyte boundary is carrying one. An interleaved Mega Drive SMD dump is deliberately *not* claimed: recovering its payload is a transform rather than an offset, and naming it would promise an identification RetroVault cannot make.

The header travels on the observation, which is a record of what was *seen*. `identityBearingSize()` subtracts it and `contentRef` carries a byte offset, so the whole existing pipeline — size filter, CRC escalation, cryptographic match — operates on the payload without any stage knowing why. That ordering matters: without it, size filtering excludes every catalogued record before a hash is computed, and the file falls through to filename matching, which for a headered library means content identification effectively does not happen.

`MODIFIED_MATCH` is kept apart from the exact states. Both rest on a measurement, and only one describes a file a preservation dataset would accept; telling a user their headered copy *is* the catalogued dump would be false. `IdentityBasis` is `VERIFIED_CONTENT` — the bytes carrying identity were measured — while `ConfidenceLevel` is `STRONG` rather than `EXACT`.

Renaming one needs review by default. The measurement is real, but the canonical name describes the dump the dataset holds and this file is not that dump, so `AutomationPolicy.allowHeaderedAutomation` lets a user normalising a headered library opt in once rather than confirming each file.

Detection is deliberately asymmetric in its failure modes. A header wrongly detected shifts every following byte and matches nothing, costing a read; a header wrongly missed costs the fallback that always existed. Neither can produce a *wrong* identity, because identity still rests on a cryptographic hash of whatever the payload turns out to be — and when the first bytes cannot be read at all, no header is claimed rather than one being assumed.

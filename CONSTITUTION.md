# RETROVAULT CONSTITUTION

**Project Name:** RetroVault
**Working Name:** RetroVault
**Document Version:** 1.2
**Last Updated:** 2026-08-13
**Status:** Product and engineering authority

---

## 1. Purpose

RetroVault is a preservation-first retro-gaming intelligence platform.

Its first job is simple:

> Determine what a user's digital game artifact actually is, explain why, and allow safe normalization of its representation.

Its long-term purpose is larger:

> Become a trustworthy local-first knowledge and management layer connecting game artifacts, canonical releases, platforms, preservation evidence, collections, hardware, metadata and history.

RetroVault must never sacrifice identity correctness for convenience.

---

## 2. Constitutional hierarchy

When documents conflict, authority follows this order:

1. `CONSTITUTION.md`
2. security and safety specifications
3. domain and architecture specifications
4. data/preservation specifications
5. feature specifications
6. UX specifications
7. implementation plans
8. code comments and implementation convenience

A lower document cannot silently weaken a higher rule.

If reality proves a specification factually wrong, record and correct the specification rather than preserving fiction.

---

## 3. Foundational principles

### 3.1 Identity before presentation

A filename is representation.

A ROM file is an artifact.

A game is a conceptual work.

A release is a specific published form.

A dump is a preservation artifact with measurable evidence.

These concepts must never be conflated.

### 3.2 Evidence before assertion

Every non-trivial identification must have evidence.

The system must be able to answer:

> Why do you believe this artifact is this release?

### 3.3 Uncertainty is data

Unknown, ambiguous, conflicting and unsupported states are legitimate outcomes.

The system must never convert uncertainty into false certainty merely to improve completion statistics.

### 3.4 False positives are worse than false negatives

A missed identification can be reviewed later.

A wrong identification can silently corrupt a collection.

Therefore:

**Conservative automation is mandatory.**

### 3.5 Local-first

Core identification, scanning, metadata use, planning and renaming must work offline.

Network services may improve knowledge or synchronization but cannot become prerequisites for core ownership workflows.

### 3.6 Reversible before destructive

Every operation capable of changing user data must first produce an explicit plan.

The plan must be validated before execution.

Operations must be auditable and recoverable where technically possible.

### 3.7 Provenance survives transformation

When information is copied, normalized, merged or derived, its origin must remain recoverable.

### 3.8 Open standards over proprietary lock-in

Prefer DAT, XML, SQLite, JSON, CSV, hashes and other documented formats where appropriate.

User data must remain exportable.

### 3.9 Boring core, sophisticated intelligence

Core mechanisms should be deterministic, testable and unsurprising.

Sophistication belongs in evidence synthesis and user experience, not hidden side effects.

---

## 4. Product thesis

Existing ROM managers demonstrate that hash-based verification and DAT-driven organization work, but their workflows frequently expose preservation terminology directly to ordinary users.

RetroVault should hide unnecessary complexity without hiding evidence.

No-Intro explicitly positions its DATs as catalogues for ROM managers and provides online database access; its naming convention exists to enforce consistency. citeturn0search1turn0search0

RomVault demonstrates the value of DAT aggregation, verification and explicit action planning, but community discussion also identifies the learning curve created by DAT-centric workflows. RetroVault should preserve the strong underlying model while making the experience understandable to normal users. citeturn0search5turn0reddit55

Therefore RetroVault's differentiator is not merely:

> "Rename ROMs."

It is:

> **"Understand my collection."**

---

## 5. Domain model

The minimum conceptual model is:

`Platform → Work → Release → Artifact → Observation → Evidence → Resolution`

Additional concepts include:

- Region
- Language
- Edition
- Revision
- Version
- Disc/part
- Media type
- File format
- Naming representation
- Source
- Provenance
- Collection membership
- Verification state
- User decision
- Operation

A canonical entity is not necessarily a file.

A file can be identified without being verified as a preservation-quality dump.

Presence in a collection does not imply authenticity.

---

## 6. Preservation model

RetroVault must distinguish at least:

- **Known:** metadata identifies the artifact or candidate.
- **Matched:** available evidence corresponds to a known database record.
- **Verified:** cryptographic evidence satisfies the relevant reference criteria.
- **Modified:** artifact differs from a known reference but may retain useful identity evidence.
- **Unverified:** insufficient evidence exists.
- **Ambiguous:** multiple plausible identities remain.
- **Conflicted:** evidence contradicts itself.
- **Unsupported:** no applicable reference source exists.

No-Intro documentation distinguishes verified, not verified and bad dump states. RetroVault must preserve source-specific status rather than flattening all sources into a single universal truth value. citeturn0search2

Redump records demonstrate that disc identity can involve track-level hashes, media metadata, offsets, sectors and physical-disc observations, not merely one file hash. citeturn0search9

Therefore the data model must support evidence appropriate to media type.

---

## 7. Hash policy

Supported hash algorithms may include:

- CRC32
- MD5
- SHA-1
- SHA-256 where a source provides it or future policy requires it

Hash strength is contextual.

A CRC32 match is not automatically a final identity when stronger reference evidence exists.

A hash mismatch does not automatically prove that an artifact is unrelated when the source describes transformed, trimmed, header-skipped, split or otherwise special representations.

The engine must model what was hashed.

For archives, RetroVault must distinguish:

- archive-file hash
- contained-file hash
- contained-file identity

A ZIP containing a matching ROM is not necessarily itself the canonical ROM artifact.

---

## 8. DAT policy

DAT files are reference sources, not unquestionable universal truth.

Every imported DAT must retain:

- source/project
- filename
- version where available
- date where available
- parser version
- import timestamp
- source fingerprint/hash
- records imported
- warnings/errors

DAT ingestion must be streaming and tolerant of large datasets.

No-Intro conventions include fields for native filenames, scene filenames, item/extension information and hashes. RetroVault must retain useful source semantics instead of reducing DAT entries to title + CRC. citeturn0search3

Multiple DAT sources may corroborate an identity.

Multiple sources may also disagree.

Agreement and disagreement must both be represented.

---

## 9. Matching ladder

Resolution should use increasingly expensive evidence.

Preferred sequence:

1. cheap structural filtering
2. file size
3. CRC32
4. stronger hashes when required
5. archive/member inspection
6. normalized metadata
7. filename semantics
8. fuzzy similarity
9. user review

This is an optimization strategy, not an epistemic hierarchy.

Cheap evidence can eliminate work.

It cannot automatically override stronger contradictory evidence.

Size filtering is a performance optimization and evidence signal. It must not be represented as proof that a game is absent.

---

## 10. Fuzzy matching

Fuzzy matching exists to recover useful identity from non-standard representation.

It must never masquerade as cryptographic verification.

Filename normalization may remove known representation tags such as:

- region markers
- language markers
- dump/release tags
- scene suffixes
- emulator-specific suffixes
- memory-card or scrubber tags
- redundant punctuation

But normalization must preserve identity-bearing distinctions such as:

- sequel numbering
- revision
- edition
- disc number
- platform
- region where relevant

Similarity alone cannot establish exact identity.

A high fuzzy score can produce a candidate.

It cannot create `VERIFIED` status.

---

## 11. Explainability

For every automatic decision, RetroVault should retain enough evidence to explain:

- what candidate was selected
- what candidates were rejected
- which evidence agreed
- which evidence conflicted
- which source supplied the evidence
- whether the decision was exact, derived or heuristic
- confidence/resolution state

The user should never need to understand XML or hash algorithms to use normal workflows.

Expert mode may expose the technical evidence.

---

## 12. Naming

Canonical naming is a projection of identity.

Identity must not be inferred solely from desired output name.

Naming policies must be:

- deterministic
- configurable
- platform-aware
- collision-safe
- reversible through operation history

Different frontends may require different naming representations.

RetroVault therefore separates canonical identity from filename policy.

No-Intro's naming conventions exist specifically to improve consistency across DAT releases; RetroVault should consume that knowledge while allowing user-facing naming profiles. citeturn0search0

---

## 13. Rename safety

Rename is a controlled mutation.

Required pipeline:

`Observe → Resolve → Plan → Validate → Preview → Journal → Execute → Verify → Record`

Validation must detect:

- duplicate destinations
- existing unrelated files
- invalid names
- unavailable permissions
- stale observations
- source disappearance
- destination conflicts

The entire batch must validate before mutation begins.

The journal must be durable before mutation.

Interrupted operations must reconcile from observed filesystem state rather than guessing.

---

## 14. Storage

Android Storage Access Framework is an infrastructure boundary.

Domain code must never depend on Android storage APIs.

Recursive traversal must avoid known IPC-heavy patterns where possible.

Storage providers are untrusted infrastructure and may behave inconsistently.

The application must handle:

- revoked permissions
- missing documents
- provider errors
- stale URIs
- duplicate names
- inaccessible files
- large directories
- slow providers

---

## 15. Architecture

Required dependency direction:

`UI → Application → Domain ← Infrastructure`

Domain must not depend on:

- Android
- Compose
- SQLite
- filesystem APIs
- XML parsers
- network clients
- coroutine dispatchers
- cryptographic implementations

Infrastructure implements domain/application ports.

UI renders application state.

ViewModels coordinate presentation state but do not contain identity rules.

---

## 16. Database

SQLite is the preferred local persistence engine.

Persistence must model identity separately from observations and evidence.

Required characteristics:

- transactional writes
- foreign-key integrity
- migrations
- indexed lookup paths
- audit history
- operation journals
- provenance
- deterministic reconstruction

Database schema must not become the domain model by accident.

---

## 17. Security and privacy

Core workflows are local-first.

No account is required for local operation.

Do not upload ROM content by default.

Do not transmit user storage paths by default.

Do not collect behavioral analytics merely because analytics are convenient.

Future network features must disclose:

- what leaves the device
- why
- where it goes
- whether it is optional
- how to disable it

ROM metadata may have licensing implications even when ROM binaries are never transmitted.

The product must not facilitate unauthorized acquisition or distribution of copyrighted games.

No proprietary or copyrighted DAT collection should be bundled unless redistribution rights permit it.

---

## 18. Data governance

Every canonical record requires provenance.

Derived data must identify its derivation source and algorithm/version where material.

Conflicting sources must remain separately attributable.

Corrections must not silently erase historical knowledge.

User-private metadata must remain distinguishable from public/reference metadata.

A future synchronization service must use explicit conflict resolution rather than last-write-wins for identity-critical fields.

---

## 19. Source trust

Source trust is field-specific, not absolute.

A source may be authoritative for one property and weak for another.

Trust evaluation may consider:

- source provenance
- verification methodology
- recency
- corroboration
- scope
- known limitations

RetroVault must not invent a global numerical "truth score" that obscures these distinctions.

---

## 20. Historical preservation

RetroVault is not merely an organizer.

It must preserve knowledge about:

- original representation
- detected identity
- user decisions
- source data
- metadata corrections
- previous filenames
- rename operations
- verification state over time

A corrected record should not make it impossible to understand what the system previously believed and why.

---

## 21. Collection semantics

Collection membership is separate from identity.

A user may possess:

- an exact verified dump
- an unverified dump
- a modified dump
- multiple regional releases
- duplicates
- incomplete multi-disc sets
- unknown artifacts

RetroVault must represent these without forcing the user into a single binary "owned/not owned" model.

---

## 22. Platform awareness

A game title without platform context is often insufficient for identity.

Platform metadata must support:

- commercial consoles
- handhelds
- computers
- arcade systems
- optical media platforms
- cartridge systems
- disk systems
- digital-only platforms where appropriate

Media-specific evidence must be supported.

---

## 23. Media-specific intelligence

ROMs are not all the same class of artifact.

Future engines may need to understand:

- cartridges
- floppy disks
- optical discs
- hard-disk images
- tape images
- arcade ROM sets
- compressed containers
- CHD-like container formats
- multi-file software packages

Redump's published disc records demonstrate the importance of track-level evidence and physical-media metadata. citeturn0search9

Therefore the architecture must avoid a universal "one file = one hash = one game" assumption.

### 23.1 Media type is first-class metadata

Media type is not a display attribute and not a derived convenience. It is recorded on every catalogued dump and on every observation, and it is versioned like any other controlled vocabulary.

A PSP UMD image, a Dreamcast GD-ROM rip and a SNES cartridge dump are different classes of artifact catalogued by different projects. Treating them as generic "ROMs" is what causes a fully catalogued library to appear unidentifiable.

Media type is inferred from the artifact's name and is therefore representation, never identity. It follows that:

- Media type must never, on its own, exclude a candidate. One release is legitimately preserved as `.cue`+`.bin`, `.chd` and `.iso`; a difference of form is not a difference of release.
- A medium disagreement between a file and a catalogue record weakens that candidate and must be visible as a reason.
- An extension that belongs to more than one medium — `.bin`, `.img`, `.rom` — must resolve to unknown rather than to a guess. An unknown medium only ever widens what RetroVault will consider.

### 23.2 Optical-disc dumps are optical-disc dumps

PSP UMD images, and disc images generally, are optical media. RetroVault must not apply cartridge-shaped assumptions to them, must not treat a large disc image as an unsupported artifact, and must identify them by the same evidence ladder as any other dump.

---

## 23A. Dataset provenance and coverage

### 23A.1 Provenance is recorded

Every imported dataset records which preservation project produced it, read from what the DAT states about itself. Provenance explains a result. It never restricts what RetroVault consults.

### 23A.2 Coverage is measured, never assumed

What a dataset covers is measured from the records it actually indexes — their media types, counted only for records fit for matching. A project's reputation is not evidence about the file in front of the user.

A dataset whose records carry no recognisable medium is treated as covering everything. RetroVault's failure to recognise an extension must never become a refusal to search.

### 23A.3 Incompatibility must be detected and stated

When a scanned artifact's medium is covered by no imported dataset, RetroVault must say so, name the medium, name what the imported datasets do cover, and state the remedy.

This check runs before any content is read. Hashing a 1.5 GB disc image against a cartridge-only catalogue cannot produce a match, and the honest answer is already available.

---

## 23B. Absence of a match is not a statement about the artifact

"No catalogue record matches this file" must never be presented as "unknown game".

RetroVault distinguishes at minimum:

- **Not listed** — the imported datasets cover this kind of artifact and do not describe this one. Weak evidence about the file; it may be a modified dump, or a release nobody has catalogued.
- **Not covered** — no imported dataset covers this kind of artifact at all. This is a fact about the catalogue and carries no information about the file whatsoever.
- **Nothing imported** — the catalogue cannot speak at all.

These have different remedies, so they are different states, are counted separately in a scan summary, and are worded differently to the user.

Every uncertain case must resolve towards "covered", so that a scope judgement can never be the reason a real match is missed.

---

## 24. Performance philosophy

Performance matters because retro collections can contain tens or hundreds of thousands of files.

Optimize in this order:

1. avoid unnecessary work
2. avoid unnecessary I/O
3. index repeated lookups
4. stream large data
5. bound concurrency
6. batch database operations
7. parallelize only where measurement supports it

Memory usage must remain bounded for large collections.

UI progress must be progressive.

The system must remain cancellable.

---

## 25. Testing philosophy

Tests must attack incorrect conclusions, not merely confirm successful paths.

Required categories:

- unit
- property
- parser corpus
- matching corpus
- integration
- database migration
- filesystem
- Android
- UI workflow
- end-to-end
- performance

Every production identity bug becomes a regression test.

A passing test suite is not evidence that Android behavior is verified unless Android code actually compiled and executed.

---

## 26. Android verification gate

JVM tests cannot establish Android correctness.

A release cannot claim Android readiness until:

- Android modules compile
- debug APK builds
- application installs
- SAF folder selection works
- recursive traversal works on real storage providers
- rename works
- cancellation works
- permission loss is handled
- UI workflow completes

Emulator testing is useful.

At least one real-device pass is required before claiming storage-provider compatibility.

---

## 27. Offline-first product boundary

The following must remain functional without network access:

- scan
- DAT import
- identity matching against local sources
- fuzzy matching
- review
- rename planning
- rename execution
- audit/history
- export of local data

Future online features are additive.

---

## 28. AI policy

AI may assist with:

- candidate generation
- natural-language explanations
- metadata normalization suggestions
- anomaly detection
- research assistance

AI must not silently override deterministic evidence.

AI-generated assertions require provenance and confidence.

For identity-critical automation, deterministic evidence remains the final authority unless an explicit human-review workflow says otherwise.

AI must never be required for basic collection access.

---

## 29. UX philosophy

RetroVault should feel:

- precise
- calm
- trustworthy
- technical without being hostile
- powerful without clutter
- archival without feeling like museum software

The product should explain complexity rather than expose complexity.

Primary workflow:

`Select → Scan → Understand → Review → Preview → Execute → Verify`

---

## 30. Product layers

### Layer 1 — Artifact intelligence

What is this file?

### Layer 2 — Collection intelligence

What do I have?

### Layer 3 — Preservation intelligence

How trustworthy and historically significant is it?

### Layer 4 — Knowledge graph

How do games, releases, platforms, people, publishers, regions and artifacts relate?

### Layer 5 — Hardware intelligence

What hardware, firmware, emulator or configuration relates to this software?

### Layer 6 — Personal operating layer

How should this collection be organized, backed up, synchronized and used?

The product must earn each layer by making previous layers trustworthy.

---

## 31. Competitive position

RetroVault must not become "RomVault with prettier screens."

Rom managers prove the value of deterministic DAT-driven organization.

Frontends prove the value of attractive presentation.

Metadata databases prove the value of broad game knowledge.

Preservation projects prove the value of provenance and verification.

RetroVault should connect these layers while keeping the user's local collection at the center.

Its defining capability should be:

> **An evidence-backed understanding of the user's actual collection.**

---

## 32. Scope discipline

Do not build broad features before the identity engine is trustworthy.

Initial priority:

1. artifact observation
2. identification
3. evidence
4. canonical naming
5. safe mutation
6. history
7. collection intelligence
8. preservation intelligence
9. broader knowledge
10. integrations

Social features, marketplaces, recommendations and cloud services are not foundational requirements.

---

## 33. Build philosophy

Build inward-out.

1. Domain
2. Application
3. Persistence
4. Identification
5. Infrastructure
6. Android
7. UI
8. End-to-end verification

Do not create UI abstractions before domain behavior is stable.

Do not optimize unmeasured code.

Do not add abstractions solely because they appear architecturally sophisticated.

---

## 34. Release philosophy

A release is not complete because it compiles.

Release gates include:

- tests
- static checks
- migration verification
- Android build
- real-device validation
- rename safety
- regression suite
- documentation
- reproducible version metadata

Unknown behavior must be documented as unknown.

---

## 35. Long-term interoperability

RetroVault must be able to export useful knowledge without requiring RetroVault itself to remain installed.

Exports should eventually support:

- canonical game/release data
- artifact observations
- hashes
- verification states
- provenance
- rename history
- collection membership
- user annotations

Avoid proprietary export formats as the only option.

---

## 36. Constitutional anti-patterns

Never:

- rename without validated identity
- call fuzzy matching exact
- treat filename equality as identity
- silently discard conflicting evidence
- turn database failure into "no match"
- require cloud services for local ownership
- put domain rules in Compose
- couple domain to Android
- overwrite provenance
- erase historical corrections
- hide uncertainty
- collect unnecessary personal data
- bundle restricted data without redistribution rights
- optimize based solely on benchmarks that do not represent real collections

---

## 37. Decision test

When considering any feature, ask:

1. Does it improve understanding of the user's collection?
2. Does it preserve evidence and provenance?
3. Does it reduce risk of incorrect action?
4. Does it work offline when core ownership is involved?
5. Does it preserve interoperability?
6. Does it make the system more trustworthy?
7. Does it justify its complexity?

If the answer to several is no, reject or defer the feature.

---

## 38. Ultimate product test

A user should eventually be able to point RetroVault at a chaotic collection and receive something far more valuable than a renamed folder.

RetroVault should tell them:

- what each artifact probably is
- what is verified
- what is uncertain
- what is modified
- which releases they actually possess
- which duplicates they have
- which important variants they lack
- how evidence supports each conclusion
- what changed over time
- how their collection relates to the wider history of the platform

And it should do so without asking the user to become a ROM-preservation expert first.

That is the product.

---

## 38A. Verified identity and inferred identity

Every resolution states what its identity claim rests on, independently of how confident it is:

- **Verified** — a cryptographic hash of the content matched a catalogued digest.
- **Structural** — size and CRC32 agree and the catalogue offered nothing stronger.
- **Inferred** — identity was read from the filename or metadata. The bytes were not verified.
- **None** — no identity was established.

Confidence and basis answer different questions and must both be carried through the domain, the preview and the audit record. A user deciding whether to accept a rename needs both.

Filename and text fallback remain a required capability. They are never presented as content verification.

---

## 39. Current implementation reality

At version 1.1, the repository contains a substantial JVM-tested implementation of the first vertical slice.

The implementation has passed its JVM test suite with zero failures in the development environment.

Android-specific modules have required real-device/Android-SDK verification separately.

Documentation must never claim Android functionality has been verified merely because JVM tests pass.

---

## 40. External research basis — 2026-08-12

This constitutional revision incorporated current external research into preservation and ROM-management practice.

Relevant sources include:

- No-Intro's current project description and DAT/database role. citeturn0search1
- No-Intro naming convention requirements. citeturn0search0
- No-Intro DAT navigation and verification terminology. citeturn0search2
- No-Intro file convention, including native/scene filenames and hash fields. citeturn0search3
- No-Intro preservation-status overview. citeturn0search8
- RomVault supported DAT architecture. citeturn0search5
- Redump's detailed optical-disc record structure. citeturn0search9

These sources inform the Constitution but do not become dependencies or authorities over it.

RetroVault must remain source-aware rather than source-dependent.

---

## 41. Final constitutional rule

When convenience conflicts with correctness:

**Choose correctness.**

When complexity conflicts with clarity:

**Hide complexity, not evidence.**

When automation conflicts with user control:

**Require review.**

When sources conflict:

**Preserve the conflict.**

When uncertain:

**Say uncertain.**

When a feature does not strengthen the core product:

**Do not build it.**

When the product can become meaningfully better:

**Challenge the existing design.**

RetroVault is not defined by what it can rename.

It is defined by how confidently, transparently and safely it can understand what a retro-gaming artifact is.

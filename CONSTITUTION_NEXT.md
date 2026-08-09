# CONSTITUTION NEXT — Integration Draft

This file contains the next constitutional layer for integration into `Constitution.md`.

It is not a second constitution. It is a continuation and review package prepared because the current GitHub write interface available in this session cannot append to an existing text blob without replacing the complete file.

The existing constitution remains authoritative until these sections are merged.

---

# 192. Canonical Software Identity Model

ROM normalization exposed a missing level of precision: a software file, a software dump, a software build, and a commercial release are different things.

RetroVault must model them separately.

Conceptual chain:

Game Work → Release → Software Revision / Build → Physical Media → Digital Dump

Not every release exposes every layer.

A digital file may represent a dump of a physical release.
A digital file may instead represent a patched or reconstructed software state.
A commercial release may contain one of several builds.

The system must never assume that one filename maps directly to one Game entity.

---

# 193. Software State Taxonomy

The software layer should distinguish at minimum:

- original commercial release
- revision
- rerelease
- prototype
- beta
- demo
- kiosk/demo build
- promotional build
- development build
- review build
- localization build
- translated build
- patched build
- fan translation
- hack
- homebrew
- bootleg
- reproduction
- trainer-modified release
- cracked release
- reconstructed image
- unknown software state

These categories may overlap.

A translation can also be a patched build.
A prototype can also be a development build.
A bootleg can contain a modified commercial game.

The ontology must represent relationships rather than forcing mutually exclusive labels.

---

# 194. Originality vs Identity

Originality and identity are separate dimensions.

A modified ROM may still identify its source release with high confidence.

A reproduction cartridge may reproduce an authentic release while remaining a non-original physical object.

A patched software file may derive from an identifiable original build without being identical to it.

Therefore:

Identity answers “what is this derived from or representing?”

Originality answers “what is its status relative to the original artifact?”

These must never be collapsed.

---

# 195. Hash Evidence Rules

Hashes are evidence about bytes, not automatically evidence about history.

An exact SHA1 match can establish that two files have identical content for the hashed scope.

It cannot by itself establish:
- which physical cartridge produced the file
- whether the file was legally obtained
- whether the source media was authentic
- whether the dump process was trustworthy
- whether the file represents the earliest known revision

Hash identity is therefore powerful but bounded evidence.

The platform must distinguish:

byte identity → dump identity → software identity → physical provenance.

Each layer requires its own evidence.

---

# 196. DAT Authority Boundary

No-Intro, Redump, and other preservation DATs are valuable reference systems.

They must not become invisible authorities whose classifications cannot be questioned.

RetroVault should preserve:
- exact source dataset
- source version
- source entry
- imported timestamp
- mapping to canonical identity
- conflicts
- later corrections

A DAT can be correct about hashes while being incomplete about historical relationships.

A community source can be weak for hashes while containing valuable hardware evidence.

Authority is claim-specific.

---

# 197. DAT Mapping Model

A DAT entry should map to RetroVault through an explicit external-record relationship.

Conceptually:

DAT Source → DAT Set → DAT Entry → External File Record → RetroVault Candidate / Identity

The mapping must preserve one-to-many and many-to-one cases.

Two DAT entries may represent the same underlying software identity for different preservation purposes.

One RetroVault release may correspond to multiple DAT entries.

Do not force a false one-to-one mapping.

---

# 198. Dump Identity

A dump is an observation of digital content extracted from media.

Where available, preservation metadata may include:
- source media type
- source physical object
- dump hardware
- dump software
- operator
- date
- verification process
- checksums
- errors
- repair or reconstruction steps

A verified hash does not automatically mean a verified dump.

Dump provenance is a separate evidence layer.

---

# 199. Bad Dumps and Damaged Data

The platform must allow imperfect digital artifacts to exist as evidence.

Possible states include:
- verified dump
- unverified dump
- bad dump
- incomplete dump
- truncated file
- corrupt archive
- damaged source
- reconstructed data
- unknown dump status

A bad dump may still be historically valuable.

Do not delete it from knowledge merely because it fails modern verification.

---

# 200. Headers, Padding, and Containers

Digital representation can alter byte-level properties without necessarily changing underlying software identity.

The system must account for:
- copier headers
- console headers
- padding
- interleaving
- byte-swapping
- container formats
- archive wrappers
- cue/bin structures
- multi-file disc layouts

A file mismatch must not immediately become an identity mismatch.

The matching architecture should be able to classify representation differences before rejecting a candidate.

---

# 201. Disc Image Model

Disc-based software requires a richer model than one-file ROM matching.

The system should eventually distinguish:
- disc
- disc side
- track
- session
- filesystem
- cue sheet
- binary track data
- metadata file
- multi-disc set
- disc revision

A `.cue` file is metadata describing disc layout.
A `.bin` file may contain track data.
Neither should automatically be treated as the complete software identity.

Multi-disc releases must preserve disc ordering and set membership.

---

# 202. Archive Model

Archives are containers, not canonical software identities.

The system must represent:

Archive → contains → Digital Artifact

An archive can contain:
- one ROM
- multiple ROMs
- manuals
- artwork
- metadata
- patches
- unrelated files

Archive-level and contained-file-level identities must remain separate.

---

# 203. Save Data as a Separate Artifact

Save files are not ROMs.

They should eventually be modeled independently.

A save artifact may relate to:
- game release
- software revision
- platform
- memory technology
- physical cartridge
- emulator
- date

This creates future capabilities around:
- save migration
- backup
- corruption analysis
- compatibility
- archival preservation

Save data must never be accidentally renamed or classified as software content.

---

# 204. Firmware as Compatibility Context

Firmware is not merely another file attached to hardware.

Firmware can change behavior.

Compatibility claims should therefore be able to reference firmware version or firmware state.

Example:

Accessory A → compatible with → Hardware B
under Firmware C

The same accessory may become incompatible under Firmware D.

Historical firmware states must remain addressable.

---

# 205. Hardware Revision Identification

Hardware identification should follow the same evidence-first architecture as ROM identification.

Input:

Photograph → visible markings → dimensions → ports → PCB evidence → component markings → candidate revisions → confidence

The system should distinguish:
- model identification
- revision identification
- region identification
- production-period inference
- modification detection

A model can be known while its revision remains unknown.

Do not force revision certainty merely because the model is obvious.

---

# 206. Physical Object Evidence Graph

A physical object should accumulate observations without overwriting its identity.

Example:

Object O
→ photographed on date X
→ label observed
→ PCB photographed
→ chip Y observed
→ shell replaced
→ display modified
→ authenticity assessed

Each observation remains historically addressable.

The object becomes a longitudinal record rather than a static catalogue entry.

---

# 207. Modification Chain

Modification history should be composable.

Example:

Original Console
→ HDMI modification
→ storage modification
→ shell replacement
→ controller modification

The current state can therefore be reconstructed from its original state plus documented modifications.

This is critical for collectors, repairers, preservationists, and authenticity research.

---

# 208. Authenticity Evidence Matrix

Authenticity should be evaluated from multiple independent signals.

Potential signals include:
- shell geometry
- label printing
- PCB layout
- chip markings
- soldering patterns
- component dates
- screws
- serial relationships
- packaging
- manual
- known manufacturing variants
- provenance

No single signal should become universal proof.

The system should show which signals support or contradict an authenticity conclusion.

---

# 209. Collection Ownership Model

Ownership is a user relationship, not a property of the canonical entity.

One canonical cartridge release may have thousands of owners.

Each user-owned object must have its own private collection record.

Public entity:

“This release exists.”

Private record:

“I own this physical instance.”

These layers must remain separate in storage, permissions, and UX.

---

# 210. Collection State

Collection state should support:
- owned
- previously owned
- wanted
- sold
- traded
- loaned
- lost
- damaged
- destroyed
- unknown

Historical ownership matters.

A sold object should not disappear from the user's history merely because it is no longer owned.

---

# 211. Collection Completeness Semantics

Completeness must always declare its denominator.

Examples:

“82% complete” is meaningless without scope.

“82 of 100 selected USA releases identified” is meaningful.

Possible denominators include:
- canonical releases in selected DAT
- releases in selected region
- games in user-defined list
- known hardware variants
- known revisions

The system must never manufacture completeness by choosing an artificially convenient denominator.

---

# 212. Search as Identity Resolution

Search should not merely retrieve text.

It should resolve intent.

A query such as:

“snes 1chip yellow screen fix”

may refer to:
- platform
- hardware revision
- symptom
- repair topic

Search should therefore combine entity recognition, relationship traversal, and evidence retrieval.

Search result ranking should explain identity ambiguity where relevant.

---

# 213. Search Result Classes

Search should distinguish:
- exact entity
- alias match
- relationship match
- evidence match
- document match
- candidate identity
- fuzzy match
- historical match

A user searching for a model number should not receive a random article merely because the number appears in its text.

Identity relevance should outrank raw textual frequency.

---

# 214. Search Index Is Derived Data

Search indexes are projections of canonical knowledge.

They may be rebuilt.

They must not become the authoritative store for identity.

A search index can be deleted and reconstructed without changing canonical truth.

This principle prevents search implementation from corrupting ontology.

---

# 215. Offline Search

Core personal-library search should remain useful offline where technically practical.

Offline search may operate over:
- local collection records
- cached entity data
- local DAT indexes
- local identification results
- locally stored evidence

Freshness should be visible where remote synchronization matters.

---

# 216. Synchronization Model

Synchronization must distinguish:
- local creation
- remote creation
- local edit
- remote edit
- conflict
- merge
- deletion request
- archival state

Never silently choose one user's observation over another when evidence conflicts.

The synchronization layer must preserve provenance.

---

# 217. Conflict Resolution in Shared Knowledge

Conflicts should be resolved by evidence and scope, not last-write-wins.

If two users report different PCB markings, the system should ask:
- are they different revisions?
- are the objects different regions?
- are observations from different dates?
- is one observation erroneous?
- is the relationship scoped differently?

Database conflict resolution must understand domain semantics.

---

# 218. User Corrections

A correction should not require a user to prove expertise before contributing an observation.

The platform should separate:

“I observed this.”

from:

“I conclude this.”

A novice can provide excellent evidence.
An expert can make an incorrect conclusion.

Evidence quality should therefore remain independently assessable.

---

# 219. Evidence Requests

When identification is uncertain, the platform should identify the most valuable next observation.

Example:

“Photograph rear PCB.”

“Provide model number.”

“Confirm region.”

“Show label edge.”

This is more useful than simply saying “confidence low.”

The system should optimize for information gain.

---

# 220. Information Gain Principle

When multiple observations could resolve uncertainty, prefer the observation that most efficiently separates remaining candidates.

Conceptually:

Current candidates → identify distinguishing feature → request evidence → reduce candidate set

This turns identification into an interactive diagnostic process.

It should be reusable across:
- ROMs
- cartridges
- consoles
- accessories
- PCB revisions
- manuals
- packaging

---

# 221. Human-in-the-Loop Boundary

Automation should handle high-confidence repetitive work.

Humans should handle ambiguous high-impact decisions.

The system should not force humans to review obvious exact matches.

It should not allow automation to silently resolve consequential ambiguity.

The goal is not zero human intervention.

The goal is intelligent allocation of human attention.

---

# 222. Confidence Is Not Probability by Default

A score such as 92 should not imply a mathematically calibrated 92% probability unless calibration has actually been demonstrated.

Use language such as:
- confidence score
- evidence strength
- candidate ranking

If probability is displayed, its methodology and calibration must be documented.

---

# 223. Evidence Weighting

Evidence should be weighted according to:
- reliability
- directness
- independence
- specificity
- temporal relevance
- reproducibility
- source quality

A direct PCB photograph can outweigh ten copied catalogue entries.

A current marketplace listing should not outweigh a manufacturer service manual for technical specifications merely because it is newer.

Recency and authority are separate dimensions.

---

# 224. Source Independence Graph

Source independence should eventually be represented explicitly.

Conceptually:

Source A → copied by → Source B
Source A → cited by → Source C
Source D → independently observed → same fact

This enables the system to distinguish apparent consensus from independent corroboration.

---

# 225. Provenance Chain

Every important derived conclusion should be traceable through a chain such as:

Conclusion
→ contributing claims
→ evidence items
→ sources
→ observations
→ underlying object or document

The deeper the claim, the more important the chain becomes.

A user should be able to inspect why the platform believes something.

---

# 226. Reproducible Identification

Important identification decisions should be reproducible from recorded evidence and methodology.

If a future algorithm produces a different candidate, the platform should be able to compare:
- old evidence
- new evidence
- old scoring method
- new scoring method
- changed ontology

This prevents AI model updates from silently rewriting history.

---

# 227. Algorithm Versioning

Any algorithm materially affecting canonical output should have a version.

Examples:
- filename tokenizer
- fuzzy matcher
- confidence aggregator
- price estimator
- search ranker
- image classifier
- compatibility inference

Derived results should retain algorithm version where necessary for reproducibility.

---

# 228. AI Model Versioning

AI-assisted results must record model and prompt methodology where practical and safe.

A future model must not silently rewrite previously accepted knowledge merely because it produces a different answer.

AI output should remain a proposal layer until accepted through evidence-aware processes.

---

# 229. AI as Research Assistant

The strongest AI role is not pretending to be the database.

It is helping users navigate the database.

AI may:
- summarize evidence
- explain relationships
- identify missing information
- propose candidates
- translate terminology
- extract structured facts
- suggest next observations

The canonical graph remains the authority.

AI is an interface and reasoning aid.

---

# 230. AI Hallucination Containment

AI-generated statements must never become canonical merely because they were generated from canonical data.

Generated text must be traceable to structured facts where possible.

If the model adds information not present in evidence, that addition must be treated as inference.

The system should prefer:

“Source says X. Based on this, the system infers Y.”

over:

“Y is true.”

---

# 231. Knowledge Pollution Defense

The platform must actively defend against low-quality bulk content.

Threats include:
- scraped pages
- AI-generated spam
- duplicate submissions
- fabricated specifications
- fake rarity claims
- marketplace manipulation
- malicious corrections
- copied evidence

Contribution throughput must never be optimized at the expense of evidence quality.

---

# 232. Abuse-Resistant Contribution

High-impact operations should require stronger controls.

Examples:
- merging canonical entities
- changing authenticity state
- deleting evidence
- altering historical facts
- changing hardware compatibility
- changing canonical identifiers

Low-risk observations can remain easy to submit.

Risk-based governance is preferable to treating every edit equally.

---

# 233. Audit Trail

Important mutations must produce durable audit events.

An audit event should identify:
- actor
- action
- target
- previous state
- resulting state
- reason where applicable
- evidence
- timestamp
- algorithm version where relevant

Audit logs are part of preservation, not merely security.

---

# 234. Data Deletion Philosophy

Deletion must distinguish:
- remove from presentation
- revoke publication
- archive
- retract claim
- delete private user data
- destroy legally required data

These are not the same operation.

Public historical knowledge should generally be archived or retracted with trace rather than silently erased.

Private user data must remain subject to user-controlled deletion where legally and technically required.

---

# 235. Export Philosophy

Users should be able to export private collection data in documented formats.

Exports should include enough identifiers to reconnect records to public entities after migration.

Export should preserve:
- entity IDs
- external IDs
- collection state
- notes
- provenance
- timestamps
- condition
- ownership history

A CSV may be convenient.
A structured machine-readable format should also exist.

---

# 236. Import Philosophy

Import is the inverse of export but not a blind mirror.

Imported data must be:
- parsed
- mapped
- validated
- deduplicated where appropriate
- provenance-tagged
- previewed
- conflict-aware

Import must never silently overwrite canonical truth.

---

# 237. Schema Governance

Database schema changes require domain reasoning.

Before adding a field, ask:

Is this an attribute or an entity?

Can it vary independently?

Does it require provenance?

Does it require temporal scope?

Can it be unknown?

Can multiple values coexist?

If the answer reveals independent behavior, create a richer model instead of another overloaded field.

---

# 238. Controlled Vocabulary Governance

Terms such as region, revision, media type, compatibility state, authenticity state, and software status should use controlled vocabularies where practical.

Controlled vocabularies must be:
- versioned
- documented
- extensible
- localizable
- historically aware

A controlled vocabulary must not become so rigid that new discoveries cannot be represented.

Unknown and other/unspecified remain valid escape states.

---

# 239. Database Constraints as Constitutional Enforcement

Where a rule can be enforced safely by schema or database constraints, prefer enforcement over convention.

Examples:
- unique stable IDs
- valid foreign relationships
- required provenance for selected claim classes
- valid enum states
- no impossible self-relationships where prohibited

Application code should not be the only guardian of critical invariants.

---

# 240. SQLite-Compatible Philosophy

If SQLite is used for local storage, its strengths should shape implementation choices without dictating the conceptual model.

Prefer:
- simple relational structures
- explicit constraints
- deterministic queries
- transactional writes
- WAL where appropriate
- indexes based on measured workloads
- migration scripts

Do not turn SQLite into a document store merely because JSON is convenient.

Structured relationships belong in structured tables.

JSON remains useful for genuinely variable payloads and preserved raw evidence.

---

# 241. Local Database Boundary

The local database should separate:
- canonical cached knowledge
- user-private collection data
- scan sessions
- derived indexes
- temporary processing state

Temporary scanner state should not accidentally become canonical user history.

Private collection records should not be uploaded merely because public knowledge synchronization occurs.

---

# 242. Scan Session Model

A long-running scan should be represented as a session.

A session may record:
- start time
- end time
- target location
- configuration
- DAT versions
- naming profile
- algorithm versions
- counts
- errors
- results
- cancellation state

This creates reproducibility and useful diagnostics without retaining unnecessary file contents.

---

# 243. Stale Scan Protection

A scan result can become invalid before execution.

Files may be:
- renamed externally
- deleted
- modified
- moved
- replaced

Before destructive execution, the system should revalidate enough identity to detect stale results.

A scan result must never be assumed current indefinitely.

---

# 244. Rename Collision Semantics

Collision handling must be explicit.

Possible cases:
- destination exists and is same file
- destination exists and is different file
- two sources resolve to same destination
- case-insensitive collision
- provider rejects destination

The planner must distinguish harmless idempotence from destructive collision.

---

# 245. Batch Failure Semantics

When true atomicity is unavailable, the system must report partial execution honestly.

Example:

100 planned
98 completed
2 failed

The result must identify exactly which operations succeeded and failed.

Never display “complete” when only the request was submitted.

---

# 246. Idempotence

Running the same normalization operation twice should produce no unnecessary changes.

A canonical filename should remain canonical.

A previously normalized library should be recognized as already normalized.

Idempotence reduces risk and makes automation predictable.

---

# 247. Determinism

Given identical inputs, dataset versions, configuration, and algorithm versions, the identification pipeline should produce the same result.

Non-deterministic AI assistance must not control irreversible operations without deterministic safeguards.

Determinism is especially important for batch processing.

---

# 248. Performance Budgeting

Performance requirements should be measurable.

The constitution should eventually define targets for:
- directory discovery throughput
- DAT indexing time
- hashing throughput
- archive inspection
- memory usage
- UI update frequency
- cancellation latency
- rename planning

Targets should be based on representative hardware and real libraries.

Never optimize for synthetic benchmarks that do not represent user workloads.

---

# 249. Resource Safety

Large libraries create resource risks.

The system must avoid:
- unbounded queues
- unbounded memory growth
- excessive parallel hashing
- runaway archive extraction
- endless retry loops
- UI event floods

Backpressure and bounded concurrency should be architectural concepts.

---

# 250. Failure Is Data

Operational failure can itself provide useful information.

Examples:
- provider rejects rename
- DAT entry malformed
- archive corrupt
- hash interrupted
- permission revoked
- candidate ambiguous

Failures should become structured states where useful rather than generic exceptions shown to users.

This improves diagnostics and future product intelligence.

---

# 251. Telemetry Boundary

Telemetry should never be required to understand a user's private collection.

If anonymous operational telemetry exists, it should focus on:
- crash rates
- performance
- provider compatibility
- feature failures

Do not transmit ROM filenames, hashes, collection contents, or directory structures by default.

---

# 252. Security of Local Processing

Local-first does not mean automatically secure.

The client must consider:
- malicious archives
- crafted files
- path traversal
- decompression bombs
- malformed XML
- resource exhaustion
- untrusted metadata

Parsing code must treat external files as hostile input.

---

# 253. Legal Safety Boundary

The platform should not conflate identification, metadata, preservation research, and content distribution.

The ROM normalization utility can identify and rename files without becoming a distribution service.

Future capabilities involving copyrighted content must be reviewed separately for jurisdiction and purpose.

The constitution should preserve this distinction.

---

# 254. Product Architecture Boundary

The core domain must not depend on:
- Jetpack Compose
- Android UI
- one storage provider
- one frontend
- one DAT provider
- one AI model
- one cloud backend

Adapters may depend on these technologies.

The domain model must not.

This is a direct application of the project's existing modularity principle.

---

# 255. Platform Adapter Model

External systems should enter through adapters.

Examples:
- No-Intro adapter
- Redump adapter
- Android SAF adapter
- RetroArch naming adapter
- EmulationStation naming adapter
- image/OCR adapter
- marketplace adapter
- hardware database adapter

Adapters translate external representations into canonical internal concepts.

They do not redefine those concepts.

---

# 256. Integration Testing Across Adapters

Adapter correctness must be tested at boundaries.

A No-Intro import test should prove mapping and provenance.

A SAF test should prove traversal and rename semantics.

A frontend naming test should prove deterministic projection.

An image identification test should prove candidate generation without silently creating canonical truth.

Integration failures must not corrupt the underlying graph.

---

# 257. Product Surface Priority

The product should prioritize workflows that repeatedly exercise the core identity architecture.

High-value surfaces include:

1. Identify something.
2. Normalize something.
3. Understand something.
4. Compare something.
5. Verify something.
6. Preserve something.
7. Organize something.
8. Research something.

A feature that does none of these should face a high burden of proof.

---

# 258. First Product Wedge

The ROM normalization utility is an unusually strong first wedge because the user problem is concrete and measurable.

Input:

Messy local library.

Output:

Correctly identified and interoperable library.

The user immediately experiences value.

The same identity engine can later support broader workflows.

This is preferable to starting with a giant catalogue that offers little immediate differentiation.

---

# 259. Wedge-to-Platform Expansion

Expansion should follow demonstrated user value.

ROM normalization
→ software identity
→ collection management
→ hardware identity
→ physical collection
→ compatibility
→ repair
→ preservation
→ research
→ shared knowledge graph

Each expansion should reuse the same identity, evidence, provenance, and relationship architecture.

Do not build nine separate products.

Build one system with multiple entry points.

---

# 260. Review Finding — The Constitution Is Strongest Where It Separates Concepts

The strongest recurring pattern in the existing constitution is separation:

Game vs Release.
Release vs Build.
Physical Object vs Digital Image.
Identity vs Condition.
Identity vs Authenticity.
Observation vs Interpretation.
Source vs Evidence.
Public Knowledge vs Private Collection.
Exact Match vs Heuristic Match.
Canonical Identity vs External Identity.
Current State vs Historical State.

This pattern should become an explicit design heuristic.

Whenever a proposed feature appears to require one overloaded object containing multiple meanings, challenge the design first.

---

# 261. Review Finding — Evidence Must Be First-Class Everywhere

The constitution consistently argues for evidence but previously leaves some product systems more implicit than others.

Future systems should therefore ask, by default:

“What evidence supports this?”

This applies to:
- specifications
- prices
- rarity
- compatibility
- authenticity
- identification
- repair procedures
- historical events
- software relationships
- hardware revisions
- AI-generated conclusions

Evidence should not be an optional decoration added after the feature exists.

---

# 262. Review Finding — Confidence Must Be Contextual

The earlier confidence scale is useful but too generic to govern every subsystem by itself.

Confidence must be evaluated against:
- claim type
- evidence type
- risk
- reversibility
- consequence of error

A 90-confidence filename match may be acceptable for a search suggestion.

The same score may be unacceptable for automatic renaming.

Therefore confidence must always be interpreted together with action risk.

---

# 263. Review Finding — Risk-Based Automation

Automation policy should depend on consequence, not confidence alone.

Low-risk action + strong evidence → automate.

High-risk action + strong evidence → verify according to policy.

Low-risk action + uncertain evidence → allow suggestion.

High-risk action + uncertain evidence → require human review.

This is more robust than one universal confidence threshold.

---

# 264. Review Finding — Preservation and Usability Are Not Opposites

The constitution should not frame preservation and convenience as competing goals by default.

The correct architecture often allows both:

Preserve original state.

Create derived usable state.

Maintain reversible relationship between them.

This principle should guide future normalization, transcoding, metadata cleanup, collection organization, and frontend interoperability.

---

# 265. Review Finding — Canonical Identity Must Be Stable, Not Static

Canonical identity should remain stable while its understanding can evolve.

A record may gain:
- new aliases
- new evidence
- new relationships
- corrected attributes
- historical scope
- better confidence

The identity itself should not change merely because knowledge improves.

If an identity was incorrectly merged, the correction should be a merge/split event rather than silent mutation.

---

# 266. Review Finding — The Graph Needs a Temporal Dimension

The constitution already contains historical principles, but temporal scope should become a cross-cutting requirement.

Claims, relationships, compatibility, ownership, firmware, prices, company relationships, and naming can all change over time.

A future data model should therefore support temporal validity wherever materially relevant.

Do not add timestamps merely for auditing.

Add temporal semantics where the meaning of the fact depends on time.

---

# 267. Review Finding — The Graph Needs Negative Knowledge

The existing constitution correctly states that “no evidence found” is not proof of nonexistence.

This should become operational.

The system should be able to record research states such as:

searched → source set → date → result → scope

This allows RetroVault to distinguish:

“We have no record.”

from:

“We searched these sources and found no record.”

The second is meaningful preservation knowledge.

---

# 268. Review Finding — Provenance Must Survive Transformation

Every transformation should preserve lineage where practical.

Examples:

Raw filename → normalized filename.

DAT record → mapped entity.

Photograph → extracted text.

Observation → interpretation.

Claim → aggregate conclusion.

Source data → derived index.

A transformation should not destroy the relationship to its input merely because the output is more convenient.

---

# 269. Review Finding — The Product Should Optimize for Information Gain

The strongest identification concept introduced so far is not fuzzy matching.

It is choosing the next observation that most reduces uncertainty.

This can become a platform-wide principle:

When uncertainty exists, do not merely display uncertainty.

Tell the user what evidence would resolve it.

That creates a useful action from incomplete knowledge.

---

# 270. Review Finding — The ROM Tool Validates the Broader Thesis

The ROM normalizer is not a side project.

It validates the central architectural hypothesis:

Messy input → evidence extraction → candidate generation → identity resolution → canonical projection → useful action.

If this architecture works reliably for ROMs, it creates a credible foundation for broader retro-gaming identification.

That makes the current implementation strategically important.

---

# 271. Review Finding — Do Not Build the Knowledge Graph as a Giant Scraped Database

The constitution's trust model is incompatible with indiscriminate scraping.

Bulk acquisition can accelerate coverage but also imports:
- duplicated errors
- stale information
- copied claims
- broken provenance
- contradictory terminology
- AI-generated pollution

Imports should therefore be treated as evidence ingestion pipelines, not truth ingestion pipelines.

---

# 272. Review Finding — Community Is a Research Network, Not a Content Engine

The community model should emphasize observation and correction.

A user submitting one high-quality PCB photograph may create more lasting value than a user publishing one hundred generic posts.

Contribution systems should optimize for evidence density, not content volume.

---

# 273. Review Finding — AI Should Increase Evidence Accessibility

The best use of AI in RetroVault is to make complex evidence understandable.

It should reduce the cost of:
- finding evidence
- comparing evidence
- translating evidence
- extracting evidence
- explaining evidence
- identifying missing evidence

It should not reduce the standard required for truth.

---

# 274. Review Finding — The Interface Must Never Hide the Trust State

A polished UI can accidentally imply certainty.

Every important identity result should communicate enough trust state to distinguish:
- verified
- strong candidate
- probable
- ambiguous
- unknown

Visual design may simplify the presentation.

It must not simplify away epistemic meaning.

---

# 275. Review Finding — The Constitution Should Govern Action, Not Just Data

The current constitution is strongest as a data philosophy.

The next implementation phase must translate it into action policies.

For every consequential operation, define:

Evidence required → confidence state → allowed automation → user confirmation → audit → rollback/recovery.

This applies to:
- merge
- split
- rename
- delete
- publish
- correct
- identify
- authenticate
- synchronize

---

# 276. Review Finding — The Product Needs a Formal Risk Taxonomy

Future implementation should classify actions by risk.

Suggested levels:

R0 — read-only suggestion.

R1 — reversible presentation change.

R2 — user-data mutation with easy recovery.

R3 — consequential user-data mutation.

R4 — shared canonical knowledge mutation.

R5 — irreversible or legally sensitive operation.

Automation requirements should increase with risk.

---

# 277. Review Finding — Canonical Knowledge and User Convenience Must Be Separate Layers

A frontend wants one filename.
A collector may want another.
A preservationist may want another.

The canonical graph should not compromise to satisfy any one representation.

Projection layers solve this cleanly.

This principle should apply beyond filenames to:
- labels
- sorting
- collection views
- exports
- API responses
- search aliases

---

# 278. Review Finding — The First Release Should Be Narrow but Deep

The first product should not attempt to represent all retro gaming history.

It should demonstrate exceptional correctness in a constrained domain.

The ROM normalization workflow is suitable because it exercises:
- identity
- external datasets
- hashes
- fuzzy matching
- local storage
- privacy
- batch mutation
- interoperability
- user trust

That is unusually high architectural leverage for one workflow.

---

# 279. Review Finding — Build the Identity Engine Before the Feature Zoo

The project should resist building disconnected features such as:
- price tracker
- game database
- repair wiki
- collection app
- emulator frontend
- AI chatbot

as separate systems.

Instead, each should become a client of the identity and evidence architecture if eventually built.

This preserves coherence.

---

# 280. Review Finding — The Ultimate Product Loop

The platform's deepest loop is:

User encounters uncertainty.

→ RetroVault identifies entities.

→ Evidence is gathered.

→ Uncertainty is reduced.

→ Relationships become visible.

→ User takes action.

→ New observations are captured.

→ Knowledge improves.

→ Future users encounter less uncertainty.

This is the product loop to optimize.

---

# 281. Constitutional Readiness Gate

Before major implementation begins, the product should be able to answer clearly:

What is canonical identity?

What is evidence?

What is a claim?

What is a source?

What is an observation?

What is derived data?

What is private user data?

What can be automated?

What requires confirmation?

What can be changed?

What must be preserved?

What happens when evidence conflicts?

What happens when identity is uncertain?

What happens when an external dataset changes?

What happens when the software or hardware is modified?

What happens when the user wants to leave?

These questions now have constitutional direction.

---

# 282. Build Readiness Decision

The constitution is ready for implementation when the team can derive architecture from it without inventing fundamental product philosophy during coding.

The remaining decisions should increasingly be implementation detail rather than identity-level uncertainty.

Implementation may still reveal contradictions.

When it does, return to the constitution.

Do not solve constitutional problems permanently inside code.

---

# 283. Immediate Implementation Boundary

The first implementation target should remain the ROM normalization workflow.

It should prove:

Local library → DAT ingestion → efficient scan → exact identity → heuristic fallback → confidence → safe batch rename → frontend-compatible output.

The architecture must be designed so that these components can later feed the broader RetroVault identity graph.

Do not build a throwaway ROM renamer.

Build the first client of the identity engine.

---

# 284. Final Review Conclusion

The constitution's central thesis is coherent:

RetroVault is not fundamentally a database of retro games.

It is a system for resolving, preserving, connecting, and explaining retro-gaming identity.

ROM normalization is the first concrete expression of that thesis.

The most important architectural principle is therefore:

**Messy artifact → evidence → identity → relationships → context → action.**

Everything else should serve that chain.

If a future feature cannot strengthen it, challenge the feature.

If a future architecture breaks it, reject the architecture.

If a future business decision corrupts it, reject the business decision.

That is the constitutional standard.

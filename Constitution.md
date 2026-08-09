# CONSTITUTION.md
## Living Product Constitution

Project Name: RetroVault
Working Name: RetroVault
Document Version: 2.0.0
Last Updated: 2026-08-09

---

# 0. Constitution

This document is the single source of truth for the entire project.

It governs product purpose, philosophy, data, architecture, experience, preservation, community, business model, and future expansion.

It is not a backlog.
It is not a changelog.
It is not a list of disconnected features.
It is not a temporary specification.

Other project documents derive from this document.
Implementation derives from this document.
Product decisions derive from this document.

When another document conflicts with this constitution, this constitution wins.

When implementation reveals that a constitutional decision is wrong, the constitution must be amended first. Code must not silently redefine product philosophy.

Earlier sections may be rewritten whenever better reasoning emerges. Historical wording has no special authority. Coherence matters more than chronology.

The constitution must grow as one system.

---

# 1. Vision

Create the world's most trusted operating system for retro gaming knowledge.

Not the largest database.
Not the loudest community.
Not the fastest-growing marketplace.
Not the most feature-heavy app.

The trusted one.

Retro gaming knowledge is fragmented across manuals, forums, wikis, databases, collector communities, repair sites, videos, marketplace listings, personal archives, and undocumented experience.

RetroVault exists to connect that knowledge, preserve its provenance, expose uncertainty, and make it useful.

The successful product should make difficult retro-gaming questions easier to answer years from now than they are today.

---

# 2. Mission

Preserve, organize, verify, connect, and expose retro gaming knowledge through a trusted evidence-based knowledge graph.

---

# 3. Defining Insight

Retro gaming is not a collection of games.

It is a network of relationships.

A game relates to releases, regions, languages, publishers, developers, hardware, media, revisions, manuals, patches, prices, owners, reviews, compatibility, and historical events.

A console relates to revisions, firmware, accessories, controllers, displays, cables, repair procedures, games, peripherals, manufacturing changes, and regional releases.

A cartridge relates to a game, region, label, shell, PCB, ROM, save technology, revision, authenticity evidence, packaging, and collector value.

The relationships are often more valuable than the individual records.

The graph is the product.

The interface is a window into that graph.

---

# 4. Product Identity

RetroVault is a retro gaming knowledge platform.

It combines:
- knowledge graph
- collection intelligence
- identification
- preservation
- compatibility
- benchmarks
- pricing reference
- repair knowledge
- hardware knowledge
- software knowledge
- community contribution
- future API access

RetroVault is not primarily:
- an emulator frontend
- a social network
- a marketplace
- a content farm
- a news feed
- an advertising platform
- an AI chatbot

Those things may connect to the platform later, but none defines its identity.

---

# 5. Prime Directive

Build the most trusted retro gaming knowledge platform.

Everything exists to:
- preserve knowledge
- organize knowledge
- verify knowledge
- connect knowledge
- retrieve knowledge
- explain knowledge
- make knowledge useful

If a feature does not strengthen one of those goals, its inclusion requires strong justification.

---

# 6. Product Promise

When a user asks RetroVault a question, the system should provide:

- fast answer
- clear answer
- useful answer
- sourced answer
- confidence appropriate to evidence

Unknown must remain unknown.

Conflicting evidence must remain visible when it matters.

A plausible answer must never be presented as verified merely because it sounds convincing.

---

# 7. Core Principles

## 7.1 Truth Before Completeness

A smaller trusted dataset is more valuable than a larger contaminated dataset.

Missing data can be added.
False data spreads.

## 7.2 Evidence Before Popularity

A claim does not become true because many people repeat it.

Popularity can be evidence of belief.
It is not automatically evidence of fact.

## 7.3 Relationships Before Lists

A list tells users what exists.
A relationship explains how things fit together.

RetroVault must prioritize the latter.

## 7.4 Preservation Before Engagement

The product must preserve useful knowledge even when that knowledge is not commercially exciting or socially engaging.

## 7.5 Unknown Before Incorrect

Unknown is a valid state.

The system must never force certainty merely because a field expects a value.

## 7.6 History Before Convenience

Current truth is not the only useful truth.

Old revisions, discontinued products, obsolete compatibility, historical prices, and incorrect past beliefs can all have archival value.

## 7.7 Structure Before Features

A strong data model can support many experiences.
A pile of features cannot repair a weak data model.

## 7.8 Trust Before Growth

Growth that damages trust is negative growth.

## 7.9 Depth Before Breadth

It is better to understand an entity deeply than to maintain shallow records for everything.

Breadth should expand without sacrificing depth.

---

# 8. Product Test

Every proposed feature must answer at least one of these:

Does it improve preservation?

Does it improve verification?

Does it improve discovery?

Does it improve retrieval?

Does it improve understanding?

Does it improve relationships between entities?

Does it materially improve collection intelligence?

Does it create defensible proprietary value?

If none apply, reject it.

A feature may still be rejected even when one answer is yes if its complexity, cost, risk, or distraction outweighs its value.

---

# 9. Defining Product Moat

The moat is not the user interface.

The moat is not an AI model.

The moat is not a list of features.

The moat is the accumulated system of:

- structured entities
- relationships
- provenance
- verification
- historical state
- contributor reputation
- identification signals
- benchmark methodology
- compatibility knowledge
- regional distinctions
- high-quality retrieval
- consistent terminology

The longer the platform operates correctly, the harder its accumulated knowledge becomes to reproduce.

The product must therefore optimize for compounding knowledge.

---

# 10. Design Axioms

One identity.

Many sources.

Every claim traceable.

Evidence visible.

History preserved.

Unknown allowed.

Relationships explicit.

Search first.

Context available.

Complexity hidden until needed.

The interface should expose structure without making users understand the database.

---

# 11. Target Users

## Collector

Needs:
- variants
- condition
- authenticity
- completeness
- region
- value
- collection organization
- acquisition history

## Player

Needs:
- what to play
- best version
- platform availability
- compatibility
- setup information
- controller support
- difficulty and duration context

## Repairer

Needs:
- board revisions
- schematics
- service manuals
- known faults
- replacement parts
- component information
- repair procedures
- revision differences

## Modder

Needs:
- hardware compatibility
- firmware
- patches
- modifications
- flash carts
- accessories
- known limitations

## Seller

Needs:
- identification
- regional value
- condition context
- authenticity signals
- price history
- variant distinctions

## Preservationist

Needs:
- provenance
- revisions
- dumps and hashes where legally appropriate
- scans
- manuals
- photographs
- historical context
- source history

## Reviewer / Creator

Needs:
- structured facts
- repeatable benchmarks
- comparisons
- source links
- technical specifications
- historical context

These user groups overlap.
The data model must not force a person into one role.

---

# 12. Product Pillars

## 12.1 Knowledge Graph
Everything meaningful connects.

## 12.2 Collection Intelligence
User-owned objects become structured data with provenance, condition, value context, and relationships.

## 12.3 Identification
The platform helps identify unknown physical and digital objects.

## 12.4 Value Reference
Pricing becomes historical, regional, condition-aware evidence rather than one arbitrary number.

## 12.5 Compatibility
The system explains what works, what does not, and under which conditions.

## 12.6 Benchmarks
Performance claims become measurable records rather than vague opinions.

## 12.7 Preservation
Knowledge, sources, revisions, scans, photographs, and historical state remain accessible.

## 12.8 Physical Ecosystem
Physical products may extend the platform when they reinforce organization, preservation, identification, repair, or display.

---

# 13. What RetroVault Must Never Become

RetroVault must not become:

- an ad-filled content farm
- an engagement casino
- a generic AI answer machine
- a shallow game catalogue
- a social network where popularity decides truth
- a marketplace optimized for transaction volume over trust
- a nostalgia-themed toy with weak data underneath
- an SEO site producing pages solely to capture search traffic
- a database dump that users cannot understand
- a closed system that prevents users from exporting their own information

---

# 14. Trust Model

Trust is a system property.

Trust comes from:

- source provenance
- visible uncertainty
- consistent terminology
- correction history
- contributor reputation
- repeatable measurements
- separation of observation and inference
- clear distinction between official and community information

The product must show enough evidence for users to understand why a claim exists.

Not every page needs every source exposed immediately.
But important claims must always have a path back to evidence.

---

# 15. Source Hierarchy

## Level 1 — Primary Evidence

- original hardware
- original physical media
- official manuals
- official documentation
- manufacturer documentation
- developer interviews
- original packaging
- contemporary official publications
- direct archival evidence

## Level 2 — Strong Secondary Evidence

- physical inspection
- PCB analysis
- controlled testing
- serious preservation research
- documented collector research
- established specialist databases
- photographed evidence

## Level 3 — Community Evidence

- community submissions
- forum reports
- collector observations
- marketplace observations
- repair anecdotes
- user photographs without full provenance

## Level 4 — Inference

- AI suggestions
- heuristic matching
- statistical inference
- probable identification
- reconstructed historical information

Level 4 can be useful.
Level 4 must never silently become Level 1.

---

# 16. Claim Model

A fact should not simply exist as text.

Important information should be represented as a claim with:

- subject
- predicate
- value or object
- source
- observed date where relevant
- publication date where relevant
- confidence
- contributor
- verification state
- historical validity

This allows competing claims to coexist without corrupting the underlying record.

Example:

Console X → uses → CPU Y

Console X → uses revised CPU → CPU Z

Both can be true when tied to different hardware revisions.

The database must model that difference rather than choosing one simplified answer.

---

# 17. Confidence

Confidence is not truth.

Confidence describes how strongly available evidence supports a claim.

Suggested initial scale:

- 100 = directly verified
- 90 = multiple strong independent sources
- 75 = highly likely
- 50 = plausible
- 25 = weak
- 0 = unknown

These values are guidance, not mathematical truth.

Confidence must be explainable.

A score without evidence is decoration.

---

# 18. Uncertainty Rules

Unknown is valid.

Uncertain is valid.

Disputed is valid.

Contradictory is valid.

Historical error is valid.

The system must distinguish:

- unknown
- unverified
- disputed
- inferred
- probable
- verified
- deprecated
- disproven

Do not collapse these states into one boolean such as `verified = true`.

---

# 19. Preservation Rules

Never delete historical knowledge merely because it is inconvenient.

Archive instead.

Version important records.

Preserve sources.

Preserve old names.

Preserve discontinued revisions.

Preserve historical prices.

Preserve obsolete compatibility information when historically useful.

Preserve competing claims when unresolved.

Preserve correction history.

A broken source can still be useful evidence that once existed.

A deprecated product can still matter.

A failed hypothesis can still explain how knowledge evolved.

---

# 20. Identity Rules

Identity and attributes must remain separate.

A physical object can have:
- identity
- variant
- revision
- region
- condition
- provenance

These must not be collapsed into one name string.

For example:

"Pokémon Red" is not sufficient identity for every physical copy.

A complete representation may require:
- game
- release
- region
- language
- media
- packaging variant
- cartridge revision
- condition
- authenticity state

The same principle applies to hardware.

---

# 21. Region Rules

Region is first-class data.

USA ≠ Europe ≠ Japan ≠ Korea ≠ Australia.

Never merge by convenience.

Region can affect:
- title
- release date
- publisher
- packaging
- language
- manual
- rating
- cartridge or disc revision
- compatibility
- accessories
- pricing
- collector value

A global object may have many regional releases.

Regional releases should link to the parent object without losing their individual identity.

---

# 22. Version and Revision Rules

Version is not synonymous with edition.

Revision is not synonymous with region.

Remake is not synonymous with port.

Port is not synonymous with emulation.

Remaster is not synonymous with original release.

The ontology must explicitly represent these distinctions.

Hardware revisions must be independently addressable when they change meaningful behavior, components, compatibility, appearance, manufacturing, or repair requirements.

Software versions must be independently addressable when behavior, compatibility, content, or preservation identity changes materially.

---

# 23. Condition Rules

Condition describes the state of an object.

It does not define identity.

Suggested bands:

- sealed
- near mint
- excellent
- good
- fair
- poor
- damaged
- broken
- incomplete
- reproduction
- custom

Condition systems may become more granular later.

Never allow condition labels to overwrite objective observations.

"Good" is subjective.

"Original shell, heavy scratches, label 80% intact" is evidence.

Both can coexist.

---

# 24. Price System

Price is an observation, not a permanent property of an object.

Every price observation should preserve, where available:

- amount
- currency
- region
- date
- source
- marketplace
- sale or asking price
- condition
- completeness
- shipping
- taxes where relevant
- authenticity state
- confidence

Never represent one number as universal value.

Price presentation should prefer:

- sample count
- recent observations
- low
- median
- high
- regional split
- condition split
- completeness split
- trend where statistically meaningful

Sparse data must visibly remain sparse.

---

# 25. Benchmark System

A benchmark is a measurement event.

A benchmark must not be treated as an intrinsic property unless methodology supports that conclusion.

Every benchmark should preserve:

- subject hardware
- hardware revision
- firmware
- software version
- test application
- test method
- environment
- equipment
- operator where relevant
- date
- raw measurement
- units
- source

Measurements must use explicit units.

Battery claims must identify test conditions.

Latency claims must identify measurement method.

Performance claims must distinguish measured data from subjective impressions.

---

# 26. Compatibility System

Compatibility is conditional.

A simple yes/no model is insufficient for complex retro hardware.

Compatibility may depend on:

- hardware revision
- firmware
- region
- accessory
- cable
- software version
- patch
- adapter
- timing behavior
- display
- controller

Compatibility states should support at least:

- confirmed compatible
- compatible with conditions
- partially compatible
- requires modification
- requires patch
- unreliable
- incompatible
- unknown

Every meaningful compatibility claim should explain why.

---

# 27. Identification System

Identification is a core product capability.

The platform should help identify unknown objects through:

- barcode
- serial number
- catalogue number
- label text
- OCR
- packaging
- photograph
- image matching
- PCB characteristics
- connector layout
- shell characteristics
- regional clues
- manual search
- user-entered descriptions

Identification must produce candidates with evidence.

The system should not merely output a guess.

A good identification result answers:

What is it?

Why do we think so?

What alternatives exist?

What evidence would distinguish them?

What confidence should the user have?

---

# 28. Search Philosophy

Search is the front door.

Users should not need to know RetroVault's internal taxonomy to use it.

Search must support:

- exact titles
- aliases
- regional titles
- partial titles
- misspellings
- platform
- manufacturer
- developer
- publisher
- genre
- year
- region
- condition
- variant
- compatibility
- repair terminology
- benchmark terminology
- natural-language questions

Search should understand entity relationships.

Examples:

"games like Golden Sun"

"Japanese Mega Drive games"

"Game Boy cartridges with battery saves"

"which GBA revision has the brighter screen"

"fake Pokémon cartridge signs"

"controllers compatible with original SNES"

"best handheld for PS1 under R3000"

The result should not merely be a list of matching text.

It should expose the answer, supporting evidence, and useful relationships.

---

# 29. Search Result Hierarchy

Search results should prioritize:

1. direct answer
2. exact entity
3. highly relevant related entities
4. supporting evidence
5. deeper exploration

The system must avoid burying the obvious answer under SEO-style filler.

---

# 30. Core Entities

The initial ontology includes:

- Game
- Game Release
- Franchise
- Console
- Handheld
- Hardware Revision
- Accessory
- Controller
- Cartridge
- Disc
- Manual
- Packaging
- Firmware
- Operating System
- Emulator
- ROM / Image
- Patch
- Modification
- Developer
- Publisher
- Manufacturer
- Person
- Region
- Language
- Genre
- Rating
- Price Record
- Benchmark
- Compatibility Record
- Repair Guide
- Component
- PCB Revision
- Photo
- Video
- Document
- Source
- Claim
- Collection Item
- Collection
- Marketplace Listing
- Community Note
- Historical Event

This list is not frozen.

New entities require an ontological reason.

Do not create entities merely because creating a table feels convenient.

---

# 31. Entity Rules

Every entity should have:

- stable identifier
- entity type
- canonical name
- aliases where applicable
- status
- provenance
- creation timestamp
- modification timestamp

Important entities should additionally support:

- confidence
- sources
- historical versions
- relationships
- regional scope
- notes

A record should never need its name string to encode all identity information.

---

# 32. Relationship Rules

Everything meaningful should connect.

Examples:

Game → release
Game Release → region
Game Release → platform
Game Release → language
Game → developer
Game → publisher
Game → franchise
Game → manual
Game → packaging
Game → price observations
Game → compatibility records
Game → patches

Cartridge → game release
Cartridge → region
Cartridge → PCB revision
Cartridge → authenticity evidence
Cartridge → collection item

Hardware → manufacturer
Hardware → revision
Hardware → firmware
Hardware → accessories
Hardware → controllers
Hardware → games
Hardware → repair guides
Hardware → benchmarks

Firmware → hardware revision
Firmware → compatibility

Accessory → compatible hardware
Accessory → compatible software

Benchmark → hardware + configuration + methodology
Price → object + market context
Claim → subject + predicate + object + evidence

Relationships are first-class data.

---

# 33. Collection Intelligence

A collection is not merely a list of owned games.

A collection represents a user's relationship with physical and digital objects.

A collection item may include:

- object identity
- acquisition date
- acquisition price
- current condition
- completeness
- authenticity confidence
- region
- location
- notes
- photographs
- provenance
- purchase source
- current estimated value
- desired state

Private collection data belongs to the user.

The platform must not exploit collection data to manipulate users into purchases.

---

# 34. Collection Philosophy

The platform should help users answer:

What do I own?

What exactly do I own?

What am I missing?

What version do I own?

What is unusual about it?

What is it worth?

What condition is it in?

What should I preserve?

What duplicates do I have?

What connects these objects?

Collection intelligence should turn ownership into knowledge.

---

# 35. AI Rules

AI is an assistant, never the source of truth.

AI may:

- search semantically
- summarize source clusters
- OCR text
- extract structured information
- suggest entity matches
- detect probable duplicates
- classify images
- translate documents
- identify probable hardware characteristics
- suggest likely relationships
- detect anomalies
- assist contributor workflows

AI must not:

- invent facts
- invent prices
- silently merge records
- overwrite verified information
- hide uncertainty
- fabricate sources
- present generated text as primary evidence

AI-generated conclusions must remain distinguishable from verified records.

Where possible, AI should expose the evidence supporting its suggestion.

---

# 36. Human Verification

Human review is required for important canonical changes.

The higher the impact of a claim, the stronger the review requirement.

High-impact examples:

- canonical identity merges
- hardware revision definitions
- authenticity claims
- preservation hashes
- major compatibility claims
- historically significant claims
- safety-critical repair information

Automation may prepare changes.
Automation must not automatically turn uncertainty into canonical truth.

---

# 37. Community Rules

Community is a source of knowledge.

Community is not automatically the authority.

Community may:

- submit data
- submit photographs
- submit measurements
- flag errors
- suggest corrections
- add observations
- provide historical context
- debate disputed claims

Community submissions must retain attribution and provenance.

Users must be able to distinguish:

- official information
- verified information
- community observation
- opinion
- inference

Popularity must never determine factual truth by itself.

---

# 38. Contribution Model

Contribution should reward quality rather than volume.

A useful contributor is one who improves the graph.

Signals may include:

- accuracy
- evidence quality
- correction history
- specialist knowledge
- reproducibility
- completeness
- respectful collaboration

Gamification must not encourage low-quality data generation.

No meaningless points economy.
No contribution farming.
No artificial streak pressure.

---

# 39. Moderation Philosophy

Moderation protects knowledge quality.

It must not exist primarily to manufacture engagement.

Moderation should distinguish:

- incorrect information
- unsupported information
- disputed information
- malicious information
- spam
- harassment
- legitimate disagreement

Disagreement is not abuse.

A minority claim with strong evidence must remain possible.

---

# 40. Preservation Philosophy

RetroVault is a preservation project as much as a consumer product.

Preservation means more than storing ROM files.

It includes preserving:

- names
- identities
- variants
- revisions
- manuals
- packaging
- photographs
- hardware behavior
- repair knowledge
- compatibility knowledge
- contributor observations
- historical sources
- discontinued products
- failed claims
- terminology

Preservation must respect applicable copyright, licensing, and access restrictions.

The platform should preserve metadata and provenance even when distributing an underlying asset is not legally or practically appropriate.

---

# 41. Digital Preservation

Where legally and technically appropriate, preservation metadata may include:

- file identity
- hashes
- format
- size
- dump information
- revision
- source provenance
- verification status
- preservation history

The system must distinguish metadata about an archival object from possession or distribution of that object.

---

# 42. Physical Preservation

Physical objects are evidence.

Photographs should preserve details such as:

- labels
- serial numbers
- PCB layouts
- connectors
- screws
- shells
- packaging
- manuals
- regional markings
- manufacturing marks

Images should retain provenance where possible.

Do not crop away the very detail that makes an image useful as evidence.

---

# 43. Repair Knowledge

Repair information should be treated as structured knowledge, not merely articles.

A repair record may contain:

- device
- revision
- symptom
- diagnosis
- cause
- component
- procedure
- required tools
- difficulty
- risk
- evidence
- contributor
- date

Procedures must distinguish verified repair methods from anecdotal suggestions.

Safety warnings must be prominent when applicable.

---

# 44. Compatibility Philosophy

Compatibility is one of the platform's highest-value relationship types.

The user does not merely need to know whether two things are related.

The user needs to know the conditions under which they work.

Therefore compatibility records should prefer:

Object A + configuration + Object B → result + conditions + evidence

rather than:

Object A → compatible with Object B

---

# 45. Marketplace Philosophy

A marketplace may exist only if it improves user knowledge or transaction trust.

Marketplace features may include:

- listing comparison
- historical sales
- condition context
- regional context
- authenticity signals
- completeness analysis
- variant identification

The platform must not manipulate users through:

- fake scarcity
- fabricated demand
- hidden sponsored rankings
- misleading valuations
- undisclosed affiliate incentives

If commercial incentives conflict with factual presentation, factual presentation wins.

---

# 46. Physical Product Philosophy

Physical products may extend the platform where they solve real problems.

Potential categories:

- storage
- display
- protection
- charging
- organization
- identification
- repair
- documentation

Physical products should connect to exact digital records where useful.

A QR code, identifier, or similar bridge may connect a physical object to its knowledge record.

Physical products must not exist merely because merchandise can generate revenue.

---

# 47. User Experience Rules

## Speed

Search should feel immediate.

## Clarity

A screen should have a dominant purpose.

## Density

High information density is acceptable when hierarchy remains clear.

## Calm

The interface should not constantly demand attention.

## Confidence

Users should understand what is known and what is uncertain.

## Depth

Simple first view.
Deep information available immediately after.

## Escape

Users should always be able to move backward, close detail, or return to their original task.

---

# 48. UI Tone

Premium.
Measured.
Quiet.
Precise.
Technical where appropriate.
Human where useful.

Never:

- childish
- cartoonish
- noisy
- gimmicky
- artificially nostalgic
- overloaded with CRT effects
- dependent on rainbow arcade aesthetics

Retro content provides emotion.
The interface provides structure.

---

# 49. Information Architecture

The platform should organize around user intent rather than internal database tables.

Users think:

"What is this?"

"Is this genuine?"

"What does it work with?"

"What version is this?"

"What is it worth?"

"What should I buy?"

"How do I repair it?"

"What happened historically?"

The interface should answer those questions directly while allowing users to explore the underlying graph.

---

# 50. Data Philosophy

Database is core.
UI is a window.
AI is an assistant.

Never reverse that hierarchy.

Data must come before:

- screens
- prompts
- styling
- marketing
- monetization

If the data model is weak, every future feature becomes harder.

If the data model is strong, many future experiences become possible without changing the underlying truth model.

---

# 51. Data Model Rules

The data model is law.

Rules:

- stable identifiers
- explicit types
- explicit units
- explicit currencies
- explicit regions
- explicit versions
- explicit provenance
- no silent merges
- no destructive overwrites
- historical state preserved
- relationships first-class

Do not encode structured meaning solely inside free-text fields when a relationship or structured field should exist.

---

# 52. Data Quality

Data quality is multidimensional.

A record can be:

- complete but poorly sourced
- incomplete but strongly sourced
- detailed but contradictory
- sparse but highly reliable

Therefore quality cannot be represented by one simplistic score.

The system should eventually distinguish:

- completeness
- confidence
- provenance quality
- freshness
- verification state
- consistency

---

# 53. Canonical Identity

Canonical records represent conceptual entities.

Aliases, regional names, abbreviations, translations, marketplace names, and community nicknames should map to canonical identities where appropriate.

Canonicalization must never erase meaningful distinctions.

A merge is a high-impact operation.

When uncertainty exists, link records provisionally rather than permanently merging them.

---

# 54. History Model

Important records should support historical state.

History should answer:

What did we believe?

When did we believe it?

Why did we believe it?

What changed?

What evidence caused the change?

Who changed it?

The latest value alone is insufficient for preservation-grade data.

---

# 55. Documentation Rules

Documentation is part of the product.

Documentation must define:

- ontology
- entity definitions
- relationship definitions
- source rules
- confidence rules
- naming rules
- revision rules
- benchmark methodology
- compatibility methodology
- moderation rules
- edit rules
- status rules

If documentation drifts, the system drifts.

---

# 56. Engineering Rules

Implementation must be:

- readable
- testable
- modular
- observable
- reversible
- documented

Avoid:

- hidden state
- giant managers
- magic behavior
- copy-paste business logic
- implicit units
- implicit currencies
- unstable identifiers
- silent data mutation

Every module should have a clear responsibility.

Architecture should support future growth without prematurely building an enormous distributed system.

Prefer boring technology when boring technology is sufficient.

Complexity must earn its place.

---

# 57. Performance Rules

Fast first.
Pretty second.

Priorities:

1. search responsiveness
2. navigation responsiveness
3. perceived page speed
4. progressive media loading
5. sensible caching
6. offline capability where valuable

Slow software damages trust because users interpret waiting as uncertainty or failure.

---

# 58. Privacy Rules

User data belongs to the user.

Do not sell private collection data.

Do not expose inventory unnecessarily.

Do not use private collection data to manipulate purchasing behavior.

Do not collect location unless required.

Do not force cloud storage when local storage is sufficient.

Users should be able to export meaningful personal data.

Privacy is not a legal afterthought.
It is product design.

---

# 59. Security Rules

Protect:

- accounts
- collections
- private notes
- photographs
- valuation information
- wishlists
- credentials
- private contribution drafts

Security architecture should include appropriate:

- encryption
- access control
- audit trails
- backup strategy
- recovery strategy
- abuse prevention

Security mechanisms must be proportional to actual risk.

---

# 60. Monetisation Rules

Potential revenue sources:

- premium subscription
- professional tools
- API access
- exports
- accessory sales
- honest affiliate relationships
- marketplace services

Core knowledge must not become intentionally crippled merely to create artificial upgrade pressure.

Paid features should provide genuine additional value.

Sponsored or commercial information must never masquerade as neutral factual information.

---

# 61. API Philosophy

An API is a future expression of the knowledge graph.

It must not become an uncontrolled dump of proprietary or user-private information.

Public API design should distinguish:

- public canonical knowledge
- licensed data
- contributor-owned data
- user-private data
- internal moderation data

API consumers must receive stable identifiers and explicit versioning where appropriate.

---

# 62. Offline and Resilience Philosophy

Retro gaming knowledge should remain useful when connectivity is imperfect.

The platform should progressively support:

- cached records
- saved collections
- downloaded references
- offline identification aids
- local search subsets

Offline capability should be designed around valuable use cases rather than implemented as a checkbox.

---

# 63. Accessibility

Accessibility is part of quality.

The interface should support:

- readable typography
- adequate contrast
- keyboard navigation where applicable
- screen readers where applicable
- non-color-only information
- sensible touch targets
- reduced motion
- clear error states

A preservation platform that excludes users unnecessarily contradicts its own mission.

---

# 64. Internationalization

Retro gaming is global.

The data model must not assume one country, currency, language, date format, rating system, or marketplace.

Internationalization must exist at the data level, not merely the UI level.

Titles may vary by region and language.

Currencies must remain explicit.

Dates must remain unambiguous.

---

# 65. Legal and Ethical Boundaries

The platform must respect applicable:

- copyright
- trademark
- privacy
- licensing
- archival access restrictions
- marketplace terms

Preservation does not automatically grant redistribution rights.

The product should preserve metadata, provenance, identification, and historical knowledge even when an underlying copyrighted asset cannot be redistributed.

The platform must not encourage piracy merely because retro content is difficult to obtain.

---

# 66. Roadmap Philosophy

Build in layers.

## Foundation

- ontology
- source model
- identity model
- search
- core records
- relationships

## Intelligence

- collection
- identification
- pricing
- compatibility
- repair
- benchmarks

## Preservation

- archival metadata
- revision tracking
- historical state
- evidence management

## Ecosystem

- community contribution
- API
- marketplace tools
- physical products
- integrations

Do not build layers out of order merely because later features are more exciting.

---

# 67. Feature Prioritization

Priority should consider:

- user value
- knowledge value
- trust value
- strategic differentiation
- data compounding
- implementation complexity
- maintenance cost
- legal risk
- privacy risk
- opportunity cost

A flashy feature with low compounding value should usually lose to an unglamorous feature that strengthens the knowledge graph.

---

# 68. Product Development Rule

Do not ask:

"What feature should we add next?"

Ask:

"What important problem remains unsolved?"

Then ask:

"What data, relationship, workflow, or capability solves it?"

Only then decide whether that solution needs a feature.

---

# 69. Anti-Feature Philosophy

Removing features is product development.

If a feature:

- adds noise
- duplicates another workflow
- weakens trust
- creates maintenance burden
- encourages bad data
- distracts from core purpose
- exists only because competitors have it

it should be considered for removal.

Feature count is not product quality.

---

# 70. Default User Journey

A user may arrive with almost no knowledge.

They search or identify something.

RetroVault establishes identity.

It shows the relevant context.

It exposes region and version.

It provides compatibility and value where available.

It shows evidence.

It reveals related entities.

The user may then save the object, compare it, investigate it, repair it, buy it, preserve it, or contribute knowledge.

The core loop is:

**Question → Identity → Context → Evidence → Relationship → Action → Knowledge improvement**

---

# 71. Knowledge Compounding Loop

The product becomes stronger when every useful interaction improves future answers.

Example:

User identifies cartridge.

Identification creates a collection record.

User photographs PCB.

PCB evidence improves revision knowledge.

Revision knowledge improves future identification.

Future identification improves collection accuracy.

Collection data reveals missing variants.

Missing variants trigger preservation work.

Preservation work creates new sources.

Sources strengthen the graph.

The loop compounds.

This compounding behavior is a core strategic objective.

---

# 72. The Graph Must Explain Itself

Users should never need to understand graph theory.

But the product should make relationships obvious.

A user looking at a game should naturally discover:

- releases
- regions
- hardware
- versions
- manuals
- developers
- publishers
- patches
- compatibility
- prices
- related games

A user looking at hardware should naturally discover:

- revisions
- firmware
- accessories
- controllers
- games
- repairs
- benchmarks
- known faults

Discovery should feel natural rather than database-like.

---

# 73. Evidence Presentation

Evidence should be close to the claim it supports.

Do not bury important evidence behind multiple unrelated screens.

The interface should distinguish:

**Verified fact**

**Observed measurement**

**Community report**

**Inference**

**Opinion**

These are different knowledge types.

---

# 74. Content Quality Rules

Every piece of structured knowledge should answer:

What is this?

Where did it come from?

How certain are we?

When was it true?

What does it connect to?

Can someone challenge it?

If these questions cannot be answered for important information, the record is incomplete.

---

# 75. Naming Rules

Canonical names must be:

- stable
- unambiguous
- human-readable
- source-aware
- region-aware where necessary

Do not use marketing language as canonical identity.

Do not allow marketplace naming conventions to define canonical entities.

Aliases should preserve useful real-world terminology.

---

# 76. Status Model

Entities and claims need explicit status.

Potential statuses:

- draft
- incomplete
- active
- verified
- disputed
- deprecated
- archived
- rejected
- superseded

Status describes lifecycle.
Confidence describes evidence.
These must remain separate.

---

# 77. No Silent Mutation

Important data must never change without traceability.

When a canonical value changes, the system should retain:

- previous value
- new value
- reason
- contributor
- timestamp
- supporting evidence

A clean database with no history can be less trustworthy than a messy database with a complete audit trail.

---

# 78. No Orphan Knowledge

Useful records should connect to the graph.

An isolated photo is less useful than a photo linked to:

hardware → revision → region → source → claim

An isolated price is less useful than:

game release → region → condition → marketplace → price observation

Orphan records may exist temporarily during ingestion.

They should not become the permanent architecture.

---

# 79. Comparison Philosophy

RetroVault should be excellent at comparisons.

Comparisons should normalize relevant dimensions without hiding meaningful differences.

Compare:

- hardware
- revisions
- games
- regional releases
- accessories
- prices
- benchmarks
- compatibility

Never reduce a multidimensional comparison to one arbitrary score unless the methodology is explicit.

---

# 80. Recommendation Philosophy

Recommendations must be explainable.

If RetroVault recommends an item, it should be possible to understand why.

Possible factors:

- compatibility
- price
- condition
- region
- collection fit
- user preferences
- benchmark performance
- historical importance

Never optimize recommendations solely for commercial revenue.

---

# 81. No Fake Authority

The platform must never use visual design to imply certainty that the data does not possess.

Bad examples:

- arbitrary 98% confidence badges
- unsupported expert labels
- fake certification symbols
- unexplained "best" rankings
- invented rarity scores

Authority must come from evidence and methodology.

---

# 82. No Fake Rarity

Rarity is a complex historical property.

Do not infer rarity solely from current marketplace scarcity.

Rarity may consider:

- production
- distribution
- survival
- regional availability
- documented quantities
- current observations

When evidence is weak, describe scarcity observations rather than declaring absolute rarity.

---

# 83. No Single Score For Everything

The platform should resist universal scoring systems.

A game does not need one number for:

- quality
- rarity
- value
- preservation importance
- historical importance

A console does not need one number for:

- performance
- desirability
- reliability
- collector value

Different dimensions should remain distinct unless there is a defensible reason to combine them.

---

# 84. Engineering Principle: Boring Core, Powerful Surface

The underlying system should favor:

- explicit schemas
- deterministic behavior
- reproducibility
- clear migrations
- observable processes
- testable rules

The user-facing experience can be sophisticated.

The underlying truth machinery should be boring enough to trust.

---

# 85. Engineering Principle: Reversibility

Early decisions should be easy to change.

Use stable identifiers and explicit relationships so presentation can evolve without destroying data.

Avoid embedding business logic into irreversible data transformations.

Prefer migrations with rollback strategies.

---

# 86. Engineering Principle: Deterministic Core

Where deterministic rules are sufficient, use them.

AI should enhance uncertain tasks.

AI should not replace deterministic identity, normalization, validation, or integrity rules merely because AI is fashionable.

---

# 87. Engineering Principle: Observable Pipelines

Data ingestion should be traceable.

A pipeline should make it possible to answer:

What entered the system?

What transformation occurred?

What was rejected?

What was inferred?

What was verified?

What was published?

Silent ingestion is unacceptable for important data.

---

# 88. Source Ingestion Philosophy

External data is raw material, not truth.

Imported data must retain source identity and provenance.

Different sources may disagree.

The system should preserve source-level information rather than flattening everything into one anonymous record.

Ingestion must be designed for reconciliation.

---

# 89. External Source Trust

A source may be excellent for one domain and weak for another.

Trust must be contextual.

For example:

A manufacturer may be authoritative for official specifications.

A repair specialist may be more authoritative for failure modes.

A collector database may be more useful for obscure packaging variants.

The source hierarchy is guidance, not a simplistic global ranking.

---

# 90. Data Import Rules

Imported data must not automatically become canonical data.

Import stages should conceptually separate:

1. acquisition
2. parsing
3. normalization
4. matching
5. validation
6. reconciliation
7. verification
8. publication

This separation prevents low-quality source data from silently contaminating the canonical graph.

---

# 91. Future-Proofing

The product should survive changes in:

- devices
- operating systems
- marketplaces
- AI models
- APIs
- storage technologies
- web frameworks
- interface trends
- commercial partners

The knowledge model must outlive the implementation.

---

# 92. Long-Term Preservation Horizon

Design for decades, not quarters.

A record created today may still be useful in 2045.

Therefore:

- identifiers should remain stable
- provenance should survive migrations
- dates should remain unambiguous
- historical state should remain accessible
- exports should be possible
- formats should be documented
- dependencies should not be assumed permanent

---

# 93. Public Knowledge vs Private Knowledge

The platform must clearly separate:

Public canonical knowledge.

Contributor-submitted knowledge.

Private user collection data.

Private notes.

Commercial or licensed data.

Internal moderation data.

These categories have different ownership, access, retention, and export rules.

---

# 94. User Ownership

Users must not become trapped by the platform.

Where practical, users should be able to export:

- collections
- notes
- photographs
- personal metadata
- saved lists
- identifiers

The platform earns loyalty by being useful, not by making exit painful.

---

# 95. Monetization Boundary

Money may influence product scope.

Money may not influence factual truth.

A paid listing must not rank above a better factual answer merely because it pays.

Affiliate relationships must not distort price presentation.

Sponsored content must be clearly identified.

---

# 96. Brand Philosophy

RetroVault should feel like a serious institution built for people who happen to love old games.

Not a toy store.
Not a nostalgia meme page.
Not an arcade-themed dashboard.

The brand should communicate:

- trust
- permanence
- intelligence
- craftsmanship
- curiosity
- preservation

The emotional appeal comes from the subject matter.
The brand provides confidence.

---

# 97. Product Voice

Copy should be:

- concise
- precise
- confident when evidence supports confidence
- transparent when uncertainty exists
- technically accurate
- free of unnecessary hype

Avoid:

- clickbait
- fake urgency
- exaggerated claims
- childish slang
- manufactured excitement

---

# 98. Information Density

Retro gaming users often want technical detail.

The product should not dumb down information merely to look simple.

Instead:

Simple surface.
Deep structure.

Users should be able to go from:

"What is this?"

to:

"What revision is this?"

to:

"What changed electrically?"

to:

"Which source proves it?"

without leaving the product.

---

# 99. The Product Should Reward Curiosity

Discovery is valuable.

A user who finds one game should naturally be able to discover:

its developer,
its publisher,
its regional releases,
its hardware,
its sequels,
its contemporaries,
its manual,
its patches,
its preservation history.

This should feel like exploration rather than endless scrolling.

---

# 100. The Product Should Reward Precision

Users who care about exact revisions, variants, regions, or hardware differences should find increasing depth rather than hitting a simplified ceiling.

Precision is not a niche failure.

Precision is part of the product's identity.

---

# 101. What Success Looks Like

Success is not measured only by:

- downloads
- page views
- subscriptions
- listings
- daily active users

Important success indicators include:

- trusted records created
- claims verified
- sources preserved
- useful relationships created
- successful identifications
- corrected misinformation
- useful contributions
- repeat research sessions
- collection accuracy
- benchmark reproducibility

Commercial metrics matter.
They do not define product quality.

---

# 102. What Failure Looks Like

Failure includes:

- large database with weak provenance
- AI-generated misinformation at scale
- users unable to distinguish fact from opinion
- shallow records for thousands of objects
- broken regional distinctions
- silent data corruption
- noisy interface hiding useful information
- commercial incentives distorting truth
- community volume replacing expertise
- inability to preserve history

A product can be popular and still fail these tests.

---

# 103. Final Decision Rule

When uncertain, choose the option that best preserves:

1. truth
2. provenance
3. relationships
4. reversibility
5. user trust
6. long-term usefulness

Short-term convenience comes after those.

---

# 104. Never Do This

Never:

- invent facts
- invent prices
- fabricate sources
- hide uncertainty
- silently merge entities
- silently overwrite history
- flatten regional differences
- treat AI as authority
- use popularity as proof
- use ads to distort knowledge
- create fake scarcity
- create fake rarity
- manufacture engagement
- build features merely because competitors have them
- sacrifice data integrity for launch speed
- lock users into their own data
- turn preservation into content farming
- make the interface louder to compensate for weak product value

---

# 105. Final Rule

Every important decision must ultimately answer:

**Does this make retro gaming knowledge more trustworthy, more connected, more useful, or more permanent?**

If not, it does not belong in RetroVault.

---

# 106. Constitution Growth Rule

This document is intentionally incomplete.

That is not a defect.

The constitution should grow as difficult product questions are solved.

Future additions must deepen existing principles rather than create disconnected feature lists.

When new knowledge contradicts an earlier assumption:

1. identify the contradiction
2. determine which principle is stronger
3. rewrite affected sections
4. preserve necessary historical rationale
5. update version
6. continue from the improved model

The objective is not to reach a large document.

The objective is to reach a coherent one.

The document may eventually exceed 100,000 words.

Length is justified only when it reduces ambiguity.

---

# 107. Working Standard

Every future product decision should be treated as if it will still matter ten years from now.

Every important data decision should be treated as if someone will rely on it twenty years from now.

Every preservation decision should be treated as if the original source may disappear tomorrow.

Every interface decision should be treated as a temporary window onto a permanent body of knowledge.

The platform may change.

The knowledge should endure.

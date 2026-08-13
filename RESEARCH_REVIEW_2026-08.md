# RetroVault Constitutional Research Review — 2026-08

**Purpose:** External research review of the product constitution and strategic direction.

**Status:** Constitutional guidance. No implementation dependency unless adopted into `CONSTITUTION.md`.

## 1. Executive finding

The original RetroVault concept remains strategically sound.

The strongest moat remains a structured, provenance-aware retro-gaming knowledge graph rather than AI itself.

The constitution correctly emphasizes evidence, uncertainty, historical preservation, relationships, and exportability.

The main refinement required is to make the platform explicitly **interoperable and source-aware**, while preventing third-party databases from becoming accidental authorities.

## 2. No-Intro / ROM naming

No-Intro's published naming convention exists to improve consistency and quality of DAT releases. This validates using canonical naming as an interoperability layer rather than treating a filename as identity.

Implication:

- canonical filename is a representation of identity
- DAT naming conventions are valuable evidence and interoperability targets
- canonical naming must remain separate from underlying entity identity
- RetroVault must not assume every useful canonical name is universally correct

Source: No-Intro Naming Convention — https://wiki.no-intro.org/index.php?title=Naming_Convention

## 3. RetroAchievements

RetroAchievements exposes game metadata, game hashes, supported-file information, revisions, and achievement-related data through APIs. Its documentation also explicitly labels hashes to distinguish supported versions, translations, bug fixes, cosmetic hacks, quality-of-life hacks, and other variants.

This is important for RetroVault because a ROM artifact can be technically valid while representing a modified or non-original software state.

Constitutional implication:

**Artifact identity must distinguish original release, verified dump, modified dump, patch, translation, hack, and other derived software states.**

RetroAchievements should be treated as a potential integration/source, not as the canonical authority for all retro-game identity.

Sources:
- https://docs.retroachievements.org/guidelines/content/hash-labels.html
- https://api-docs.retroachievements.org/v1/get-game-hashes.html
- https://api-docs.retroachievements.org/v1/get-game.html

## 4. MobyGames

MobyGames provides structured game, platform, genre, group, screenshot, and cover data through an API. It also distinguishes game-level and platform-level information.

This reinforces the need for a multi-source entity model. RetroVault should be capable of linking external identifiers without allowing one external taxonomy to become the internal ontology.

Constitutional implication:

**External identifiers are mappings, not identity.**

A RetroVault entity may have many external identifiers. External records must retain source and retrieval provenance.

Source: MobyGames API documentation — https://www.mobygames.com/info/api/

## 5. Price intelligence

PriceCharting states that its pricing system uses sold-listing data and considers recent sales, median, average, age weighting, outliers, and sale dates. It separates loose, complete, new, and graded conditions.

This supports the constitution's decision to model price as observations rather than as a permanent property.

Further refinement:

- asking price ≠ completed sale
- listing price ≠ realized market price
- condition classification is itself uncertain
- marketplace coverage can bias results
- currency conversion introduces another observation layer
- regional markets must remain separate until evidence supports aggregation

Price intelligence must preserve raw observations where legally and technically possible, not only the derived estimate.

Source: PriceCharting methodology — https://www.pricecharting.com/page/methodology

## 6. AI positioning

External systems increasingly provide game identification, metadata and search APIs. AI therefore cannot be treated as a durable product moat.

The constitution's AI-second principle remains correct.

The stronger formulation is:

**AI may propose entities, claims, relationships, classifications, searches, summaries, or actions. It may not silently promote them to trusted knowledge.**

Every AI-derived result should retain its provenance and inference status until independently supported.

## 7. Knowledge graph architecture

Research supports a layered model rather than one giant undifferentiated database:

`Canonical entities`
→ `Relationships`
→ `Claims`
→ `Evidence`
→ `Sources`
→ `Observations`
→ `Derived intelligence`
→ `User-specific state`

This prevents personal collection information, marketplace observations, community claims, and canonical historical facts from contaminating one another.

## 8. Identification architecture

The original ROM-renaming problem remains an excellent first vertical slice because it exercises the core trust architecture:

`artifact`
→ `signals`
→ `candidate identities`
→ `evidence`
→ `resolution`
→ `explainable action`

This same architecture later supports:

- cartridge identification
- hardware identification
- authenticity assistance
- PCB identification
- firmware identification
- listing analysis
- barcode identification
- image-assisted identification

The first product therefore remains strategically aligned with the long-term platform.

## 9. Interoperability requirement

RetroVault should not attempt to replace every existing retro-gaming database.

It should become a high-quality **linking and interpretation layer**.

External identifiers should be first-class mappings for sources such as:

- No-Intro
- Redump
- MobyGames
- RetroAchievements
- platform-specific databases
- manufacturer documentation
- community archives
- future sources

No external source should be silently treated as universally authoritative.

## 10. New constitutional principle recommended

### Interoperability Before Isolation

RetroVault should integrate with existing trusted ecosystems wherever doing so improves user value, while preserving its own evidence and ontology.

The goal is not to own every record.

The goal is to make fragmented knowledge interoperable.

## 11. New constitutional principle recommended

### Observations Before Estimates

Whenever a derived value can be represented as underlying observations, preserve the observations.

This applies especially to:

- prices
- benchmarks
- compatibility
- authenticity indicators
- rarity
- collection valuation
- identification confidence

Derived values may change as methodology improves. The underlying observations should remain recoverable.

## 12. New constitutional principle recommended

### Artifact State Is First-Class

RetroVault must distinguish the abstract game/release from the particular artifact representing it.

An artifact may be:

- original verified dump
- modified dump
- patched dump
- translated dump
- hack
- homebrew
- preservation image
- unknown derivative

This prevents the knowledge graph from collapsing all ROM files into one game identity.

## 13. Strategic conclusion

The original master concept remains the correct destination.

The constitution is not drifting away from it.

The correct product hierarchy is:

`Trusted knowledge graph`
→ `Identification`
→ `Collection intelligence`
→ `Price intelligence`
→ `Hardware/compatibility intelligence`
→ `Benchmarks`
→ `AI assistance`
→ `Community`
→ `API/interoperability`
→ `Physical ecosystem`

The ROM renamer is not the destination.

It is the first proof that RetroVault can turn messy real-world retro-gaming artifacts into trustworthy structured knowledge.

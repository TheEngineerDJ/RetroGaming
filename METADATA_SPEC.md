# METADATA_SPEC.md

**Project:** RetroVault
**Role:** Metadata and canonical-data rules
**Authority:** `Constitution.md`

## 1. Purpose

Define how RetroVault represents retro-gaming facts without collapsing distinct concepts into convenient strings.

## 2. Canonical vs derived metadata

Canonical metadata is explicitly stored and sourced.

Derived metadata may be calculated from canonical data.

Examples of derived data:
- normalized search tokens
- display titles
- age calculations
- regional summaries
- price statistics
- compatibility summaries
- candidate scores

Derived data must be rebuildable.

## 3. Naming

Every important entity may have multiple names.

Store separately:
- canonical name
- native name
- localized name
- transliteration
- alternate name
- historical name
- abbreviation
- filename representation
- publisher/developer spelling

Never overwrite original names during normalization.

## 4. Dates

Dates must preserve precision.

Supported conceptual precision:
- exact date
- month
- year
- range
- unknown

Never invent day/month values merely to fit a database field.

A date should carry region/scope where relevant.

## 5. People and companies

Separate person identity from credited role.

A person may have multiple roles across projects.

Company identity must not be inferred solely from branding.

Company names should preserve historical names and ownership relationships.

## 6. Platforms

Separate:

`Platform Family → Model → Revision`

Examples of meaningful distinctions include hardware architecture, region, video output, storage, controller interface, firmware, or compatibility.

Avoid creating separate platforms for cosmetic differences.

## 7. Games

Separate:

`Game Concept → Release → Version/Revision → Artifact`

A game concept represents the underlying work.

A release represents a market/platform/regional publication.

A version represents materially different software.

An artifact represents a concrete digital or physical instance.

## 8. Credits

Credits must support:
- person
- organization
- role
- scope
- source
- certainty

Do not infer authorship from job titles alone.

Preserve disputed credits where evidence conflicts.

## 9. Media

Physical media and digital images are distinct entities.

A cartridge can contain a ROM.
A disc can contain multiple tracks/filesystems.
A dump is a digital representation.

Do not treat filename as media identity.

## 10. Languages

Language and region are independent.

A release may have:
- one language
- multiple languages
- language-dependent content
- text language
- audio language
- subtitle language

## 11. Relationships

Relationships must carry type and, where meaningful:
- direction
- scope
- source
- confidence
- temporal validity

Examples:

`release → published-by → company`
`release → port-of → game`
`revision → revision-of → release`
`hardware → compatible-with → accessory`

## 12. Unknown values

Use explicit unknown/null states.

Do not use placeholders such as:
- N/A
- ???
- 0
- Unknown Company
- 1900-01-01

unless those strings themselves are canonical source data.

## 13. Conflicts

Conflicting metadata must remain representable.

Do not silently choose one value because it is easier to display.

Presentation may choose a preferred value while retaining alternatives and evidence.

## 14. External identifiers

External IDs require source namespace.

Example:

`mobygames:1234`
`igdb:5678`
`no_intro:...`
`redump:...`

External IDs are references, not internal identity.

## 15. Localization

Canonical identity must not depend on interface language.

Search should support localized names while resolving to the same canonical entity when appropriate.

## 16. Data quality

Metadata quality checks should detect:
- impossible dates
- duplicate canonical identities
- circular relationships where forbidden
- region contradictions
- revision contradictions
- orphan records
- invalid external IDs
- unsupported relationship types

## 17. Historical state

Metadata may change over time.

Important changes should preserve:
- previous value
- new value
- source
- reason
- timestamp
- contributor

## 18. Guiding rule

**Store the thing, its names, its relationships, its evidence, and its history separately.**

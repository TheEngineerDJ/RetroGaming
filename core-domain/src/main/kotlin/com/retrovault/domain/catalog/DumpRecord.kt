package com.retrovault.domain.catalog

import com.retrovault.domain.identity.DatSourceId
import com.retrovault.domain.identity.DumpRecordId
import com.retrovault.domain.identity.DumpStatus
import com.retrovault.domain.identity.HashAlgorithm
import com.retrovault.domain.identity.HashDigests
import com.retrovault.domain.identity.HashValue
import com.retrovault.domain.identity.LanguageCode
import com.retrovault.domain.identity.PlatformName
import com.retrovault.domain.identity.RegionCode
import com.retrovault.domain.identity.RegionVocabulary
import com.retrovault.domain.identity.ReleaseFlag
import com.retrovault.domain.naming.FilenameTokenizer
import com.retrovault.domain.naming.NormalizedTitle
import com.retrovault.domain.naming.TitleNormalizer

/**
 * Provenance of one imported dataset.
 *
 * Constitution section 196: a DAT must never become an invisible authority.
 * Every record therefore carries the dataset, its version and when it was
 * imported, so a past decision stays explainable after the dataset changes
 * (Constitution section 184).
 */
data class DatSourceRef(
    val id: DatSourceId,
    /** Provider namespace, e.g. `no_intro`, `redump`. */
    val provider: String,
    /** DAT header `<name>`, e.g. `Nintendo - Super Nintendo Entertainment System`. */
    val setName: String,
    /** DAT header `<version>` where present. */
    val version: String?,
    val platform: PlatformName,
    /** Epoch milliseconds of the import. */
    val importedAtEpochMillis: Long,
    /** SHA1 of the source document where the importer computed one. */
    val sourceDigest: String? = null,
) {
    init {
        require(provider.isNotBlank()) { "DAT provider must not be blank" }
        require(setName.isNotBlank()) { "DAT set name must not be blank" }
    }

    /** Stable, namespaced external reference (DATABASE.md section 3). */
    val externalNamespace: String get() = "$provider:${version ?: "unversioned"}"
}

/**
 * One catalogued dump: a `<rom>` inside a `<game>` inside a DAT.
 *
 * This is *external evidence*, not RetroVault canonical identity
 * (Constitution section 145). The distinction matters because two DAT sources
 * may describe the same bytes differently, and both readings must survive.
 */
data class DumpRecord(
    val id: DumpRecordId,
    val source: DatSourceRef,
    /** `<game name="...">` verbatim. */
    val setName: String,
    /** `<rom name="...">` verbatim. */
    val romName: String,
    /**
     * Catalogued byte size, or `null` when the dataset does not state one.
     *
     * Some DATs omit `size` on `<disk>` entries. Dropping those records would
     * lose every hash they carry, so an unknown size is modelled as unknown
     * rather than as a parse failure - and it produces no size evidence in
     * either direction.
     */
    val size: Long?,
    val hashes: HashDigests,
    val platform: PlatformName,
    val canonicalTitle: String,
    val normalizedTitle: NormalizedTitle,
    val regions: List<RegionCode>,
    val languages: List<LanguageCode>,
    val revision: String?,
    val version: String?,
    val discNumber: Int?,
    val flags: Set<ReleaseFlag>,
    val status: DumpStatus,
    /** Namespaced identifier from the source dataset where one exists. */
    val externalId: String?,
) {
    init {
        require(size == null || size >= 0) { "DumpRecord size must not be negative" }
        require(romName.isNotBlank()) { "DumpRecord romName must not be blank" }
    }

    /** Whether this record can contribute size evidence at all. */
    val hasKnownSize: Boolean get() = size != null

    /**
     * Whether this record may be used to identify a local file.
     *
     * ROMRenamer excludes `nodump`/`baddump` entries from its index for the
     * same reason: their hashes are placeholders or belong to a known-broken
     * dump, so matching against them asserts a wrong identity confidently.
     */
    val isMatchable: Boolean get() = status.isReliableForMatching

    val crc32: HashValue? get() = hashes[HashAlgorithm.CRC32]
    val md5: HashValue? get() = hashes[HashAlgorithm.MD5]
    val sha1: HashValue? get() = hashes[HashAlgorithm.SHA1]

    /** Extension implied by the catalogued rom name, e.g. `sfc`. */
    val romExtension: String?
        get() = romName.substringAfterLast('.', missingDelimiterValue = "").takeIf { it.isNotEmpty() }

    /** The strongest hash this record can be matched on, if any. */
    val strongestAvailableHash: HashAlgorithm?
        get() = when {
            hashes.contains(HashAlgorithm.SHA1) -> HashAlgorithm.SHA1
            hashes.contains(HashAlgorithm.MD5) -> HashAlgorithm.MD5
            hashes.contains(HashAlgorithm.CRC32) -> HashAlgorithm.CRC32
            else -> null
        }

    /**
     * The identity this record points at, ignoring which dataset described it.
     *
     * Two records from No-Intro and Redump that describe the same release are
     * corroboration, not ambiguity. Comparing this key is how the resolver
     * tells those two situations apart.
     */
    val canonicalIdentityKey: CanonicalIdentityKey
        get() = CanonicalIdentityKey(
            platform = platform.value,
            normalizedTitle = normalizedTitle.key,
            regions = RegionVocabulary.sort(regions).map { it.code },
            revision = revision,
            version = version,
            discNumber = discNumber,
            flags = flags.map { it.name }.sorted(),
        )

    companion object {
        /**
         * Builds a record from raw DAT fields, deriving the structured title,
         * region, language and flag signals from the set name.
         *
         * DAT set names follow the same convention as ROM filenames, so the
         * same tokenizer is used for both. That is deliberate: it means a
         * catalogue entry and a local file are read by identical rules.
         */
        @Suppress("LongParameterList")
        fun derive(
            id: DumpRecordId,
            source: DatSourceRef,
            setName: String,
            romName: String,
            size: Long?,
            hashes: HashDigests,
            status: DumpStatus = DumpStatus.GOOD,
            externalId: String? = null,
            declaredRegions: List<RegionCode> = emptyList(),
            declaredLanguages: List<LanguageCode> = emptyList(),
        ): DumpRecord {
            val parsed = FilenameTokenizer.tokenize(setName)
            val regions = (declaredRegions + parsed.regions).distinct()
            val languages = (declaredLanguages + parsed.languages).distinct()
            return DumpRecord(
                id = id,
                source = source,
                setName = setName,
                romName = romName,
                size = size,
                hashes = hashes,
                platform = source.platform,
                canonicalTitle = parsed.titleText.ifBlank { setName },
                normalizedTitle = TitleNormalizer.normalize(parsed.titleText.ifBlank { setName }),
                regions = regions,
                languages = languages,
                revision = parsed.revision,
                version = parsed.version,
                discNumber = parsed.discNumber,
                flags = parsed.flags,
                status = status,
                externalId = externalId,
            )
        }
    }
}

/**
 * A dataset-independent identity fingerprint.
 *
 * DOMAIN_MODEL.md section 43: automated matching may propose identity links,
 * but this key only ever *groups* records; it never merges canonical entities.
 */
data class CanonicalIdentityKey(
    val platform: String,
    val normalizedTitle: String,
    val regions: List<String>,
    val revision: String?,
    val version: String?,
    val discNumber: Int?,
    val flags: List<String>,
) {
    /** Short human-readable form used in evidence descriptions. */
    fun describe(): String = buildString {
        append(normalizedTitle)
        if (regions.isNotEmpty()) append(" [").append(regions.joinToString(",")).append("]")
        revision?.let { append(" rev ").append(it) }
        discNumber?.let { append(" disc ").append(it) }
    }
}

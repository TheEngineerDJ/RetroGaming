package com.retrovault.domain.identity

/**
 * Stable machine identifiers.
 *
 * Constitution section 80: human-readable names are not sufficient identifiers.
 * Every canonical entity carries an identifier that survives renaming,
 * translation, correction and storage migration.
 */

/** Identifies one imported DAT dataset (source + set + version). */
@JvmInline
value class DatSourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "DatSourceId must not be blank" }
    }
}

/** Identifies one `<rom>` record inside one imported DAT dataset. */
@JvmInline
value class DumpRecordId(val value: String) {
    init {
        require(value.isNotBlank()) { "DumpRecordId must not be blank" }
    }
}

/** Identifies one thing the scanner actually saw at one point in time. */
@JvmInline
value class ObservationId(val value: String) {
    init {
        require(value.isNotBlank()) { "ObservationId must not be blank" }
    }
}

@JvmInline
value class ScanSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "ScanSessionId must not be blank" }
    }
}

@JvmInline
value class RenamePlanId(val value: String) {
    init {
        require(value.isNotBlank()) { "RenamePlanId must not be blank" }
    }
}

@JvmInline
value class RenameBatchId(val value: String) {
    init {
        require(value.isNotBlank()) { "RenameBatchId must not be blank" }
    }
}

@JvmInline
value class RenameOperationId(val value: String) {
    init {
        require(value.isNotBlank()) { "RenameOperationId must not be blank" }
    }
}

@JvmInline
value class PlanEntryId(val value: String) {
    init {
        require(value.isNotBlank()) { "PlanEntryId must not be blank" }
    }
}

/**
 * Identifies one canonical entity in the knowledge graph.
 *
 * Constitution section 305 states the model as
 * `Platform -> Work -> Release -> Artifact -> Observation -> Evidence -> Resolution`.
 * The first four are canonical entities and each carries its own identifier
 * type, so a function that takes a release cannot be handed a work.
 *
 * These identifiers are *derived* from the identity they describe rather than
 * generated, so promoting the same identity twice produces the same entity
 * instead of a duplicate. That is what makes a rescan idempotent.
 */
@JvmInline
value class PlatformId(val value: String) {
    init {
        require(value.isNotBlank()) { "PlatformId must not be blank" }
    }
}

/** Identifies one game concept - the underlying work (Constitution section 31). */
@JvmInline
value class WorkId(val value: String) {
    init {
        require(value.isNotBlank()) { "WorkId must not be blank" }
    }
}

/** Identifies one specific published form of a work (Constitution section 31). */
@JvmInline
value class ReleaseId(val value: String) {
    init {
        require(value.isNotBlank()) { "ReleaseId must not be blank" }
    }
}

/** Identifies one digital image of a release (Constitution section 38). */
@JvmInline
value class ArtifactId(val value: String) {
    init {
        require(value.isNotBlank()) { "ArtifactId must not be blank" }
    }
}

/** Identifies one durable user correction (Constitution section 69). */
@JvmInline
value class CorrectionId(val value: String) {
    init {
        require(value.isNotBlank()) { "CorrectionId must not be blank" }
    }
}

/** Logical platform label carried by a DAT header, e.g. "Nintendo - Super Nintendo Entertainment System". */
@JvmInline
value class PlatformName(val value: String) {
    init {
        require(value.isNotBlank()) { "PlatformName must not be blank" }
    }
}

/**
 * Opaque handle to a stored artifact.
 *
 * The domain must not know whether this is a SAF document URI, a file path or
 * anything else (ARCHITECTURE.md section 4). Infrastructure owns the encoding;
 * the domain only ever compares and carries it.
 */
@JvmInline
value class StorageRef(val value: String) {
    init {
        require(value.isNotBlank()) { "StorageRef must not be blank" }
    }
}

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

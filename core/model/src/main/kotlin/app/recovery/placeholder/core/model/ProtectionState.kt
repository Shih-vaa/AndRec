package app.recovery.placeholder.core.model

/**
 * Protection axis: what is true about one asset version on the OLD device. SPEC §4.
 *
 * Independent of [RecoveryState]. Never merge the two axes, and never treat a later
 * state as implied by an earlier one: a completed upload is [UPLOADED_UNVERIFIED], not
 * [BACKUP_VERIFIED].
 *
 * This is vocabulary only. Transition rules arrive with the PDI (Phase 1) and the
 * upload engine (Phase 2).
 */
enum class ProtectionState {
    /** Seen in an inventory. Nothing else is implied. */
    DISCOVERED,

    /** Eligible, but no copy exists yet. */
    NOT_BACKED_UP,

    /** Copy in progress. */
    UPLOADING,

    /** Last attempt failed; reason recorded; retryable. */
    BACKUP_FAILED,

    /** Bytes stored; integrity not yet confirmed. */
    UPLOADED_UNVERIFIED,

    /** Integrity confirmed by a defined method (SPEC §8.6). */
    BACKUP_VERIFIED,

    /** We cannot read it (permission, app-private, vault, OEM). Reason recorded. */
    INACCESSIBLE,

    /** Recoverable only through the owning app's own mechanism (guided). */
    NEEDS_APP_SPECIFIC,

    /** Content lives in another service we reference but do not copy. Not an independent backup. */
    EXTERNALLY_HELD,

    /** No usable recovery source known. */
    NO_SOURCE,
}

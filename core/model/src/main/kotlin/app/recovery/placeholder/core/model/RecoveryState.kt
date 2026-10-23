package app.recovery.placeholder.core.model

/**
 * Recovery axis: what is true about one asset version on the NEW device. SPEC §4.
 *
 * Independent of [ProtectionState]. A finished copy is not a verified restore.
 *
 * This is vocabulary only. Transition rules arrive with the restore engine (Phase 3).
 */
enum class RecoveryState {
    /** A verified source exists. */
    RECOVERY_AVAILABLE,

    /** In progress. */
    RESTORING,

    /** Present on the new device and verified (hash match). */
    RESTORED_VERIFIED,

    /** Present but not exactly verified (e.g. transcoded; probable match only). */
    RESTORED_UNVERIFIED,

    /** Available through a connected service on the new device. */
    PRESENT_VIA_SERVICE,

    /** Not on the new device and no restore performed. */
    NOT_FOUND,

    /** Guided step needed. */
    REQUIRES_USER_ACTION,
}

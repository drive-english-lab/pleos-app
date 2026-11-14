package io.github.driveenglishlab.pleos.domain

/** Actual evidence classifications supplied by AuthSessionStore/ProfileRepository, not guessed
 * from a null session. No policy operation clears credentials or creates a guest identity.
 */
enum class IdentityRecoveryState { RESTORED, RECOVERY_RETRYABLE, IDENTITY_UNAVAILABLE, ACCOUNT_SIGN_IN_REQUIRED, SYNC_PENDING }
enum class IdentitySyncState { LOCAL_ONLY, OUTBOX_PENDING, SERVER_CONFIRMED }
enum class ProtectedIdentityEvidence { AVAILABLE, ABSENT, READ_RETRYABLE, UNREADABLE, UNKNOWN }
data class IdentityRecoveryEvidence(val owner: OwnerContext?, val protectedIdentity: ProtectedIdentityEvidence,
    val accountSignInRequired: Boolean = false, val verifiedServerBinding: ProfileContext? = null,
    val currentServerAcknowledgement: Boolean = false, val pendingOutbox: Boolean = false,
    val localUnconfirmedRecords: Boolean = false, val transitionPending: Boolean = false)
data class IdentityRecoveryProjection(val owner: OwnerContext?, val state: IdentityRecoveryState,
    val syncState: IdentitySyncState, val retrySameIdentity: Boolean, val optionalSignIn: Boolean = true,
    val serverConfirmedOnly: Boolean = true)

object IdentityRecoveryPolicy {
    fun project(evidence: IdentityRecoveryEvidence): IdentityRecoveryProjection {
        val sync = if (evidence.pendingOutbox) IdentitySyncState.OUTBOX_PENDING else IdentitySyncState.LOCAL_ONLY
        val owner = evidence.owner
        fun result(state: IdentityRecoveryState, retry: Boolean = false, actualSync: IdentitySyncState = sync) =
            IdentityRecoveryProjection(owner, state, actualSync, retry)
        if (evidence.protectedIdentity == ProtectedIdentityEvidence.ABSENT && evidence.accountSignInRequired)
            return result(IdentityRecoveryState.ACCOUNT_SIGN_IN_REQUIRED)
        if (evidence.protectedIdentity in setOf(ProtectedIdentityEvidence.ABSENT, ProtectedIdentityEvidence.UNREADABLE))
            return result(IdentityRecoveryState.IDENTITY_UNAVAILABLE)
        if (evidence.protectedIdentity in setOf(ProtectedIdentityEvidence.READ_RETRYABLE, ProtectedIdentityEvidence.UNKNOWN) || owner == null)
            return result(IdentityRecoveryState.RECOVERY_RETRYABLE, true)
        val sameBinding = evidence.currentServerAcknowledgement && evidence.verifiedServerBinding == ProfileContext(owner.profileId, owner.privacyEpoch)
        if (!sameBinding || evidence.pendingOutbox || evidence.localUnconfirmedRecords || evidence.transitionPending)
            return result(IdentityRecoveryState.SYNC_PENDING, true)
        return result(IdentityRecoveryState.RESTORED, actualSync = IdentitySyncState.SERVER_CONFIRMED)
    }
}

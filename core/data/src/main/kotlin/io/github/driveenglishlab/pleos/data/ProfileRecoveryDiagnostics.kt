package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence
import java.io.IOException
import java.security.GeneralSecurityException
import javax.crypto.AEADBadTagException
import kotlinx.serialization.SerializationException

/** Only bounded enum values and booleans may leave this diagnostic boundary. */
internal enum class ProfileConnectionStage {
    IDLE, OWNER_READ, PROTECTED_IDENTITY_READ, AUTH_CLIENT_INIT, CREATION_FENCE,
    AUTH_CREATE, AUTH_REFRESH, AUTH_IMPORT, AUTH_CURRENT_SESSION, CURRENT_SCOPE,
    BOOTSTRAP_OPERATION, BOOTSTRAP_HTTP, PROFILE_HTTP, ACCEPT_PROFILE, COMPLETE,
}
internal enum class ProfileConnectionFailure {
    NONE, IO_RETRYABLE, CIPHERTEXT_INVALID, KEYSTORE_UNAVAILABLE, STATE_OR_CREATION_FENCE,
    DECODE_OR_CLAIMS_INVALID, AUTH_PROVIDER_OR_UNKNOWN, SESSION_UNAVAILABLE,
}
internal data class ProfileConnectionTrace(val stage:ProfileConnectionStage=ProfileConnectionStage.IDLE,
    val failure:ProfileConnectionFailure=ProfileConnectionFailure.NONE)
internal data class ProtectedIdentityDiagnostic(
    val encryptedFilePresent:Boolean?,val creationStarted:Boolean?,val sessionPresent:Boolean?,
    val evidence:ProtectedIdentityEvidence,val failure:ProfileConnectionFailure=ProfileConnectionFailure.NONE,
)
internal data class ProfileRecoveryDiagnostic(val stage:ProfileConnectionStage,val failure:ProfileConnectionFailure,
    val identity:ProtectedIdentityDiagnostic,val ownerPresent:Boolean,val authCreationFencePresent:Boolean?,
    val lastConnection:ProfileConnectionTrace)

/** No exception message, stack, request URL, token, identifier or ciphertext is retained. */
internal fun profileConnectionFailure(error:Exception):ProfileConnectionFailure=when(error) {
    is AEADBadTagException->ProfileConnectionFailure.CIPHERTEXT_INVALID
    is GeneralSecurityException->ProfileConnectionFailure.KEYSTORE_UNAVAILABLE
    is SerializationException->ProfileConnectionFailure.DECODE_OR_CLAIMS_INVALID
    is IOException->ProfileConnectionFailure.IO_RETRYABLE
    is IllegalStateException->ProfileConnectionFailure.STATE_OR_CREATION_FENCE
    is IllegalArgumentException->ProfileConnectionFailure.DECODE_OR_CLAIMS_INVALID
    else->ProfileConnectionFailure.AUTH_PROVIDER_OR_UNKNOWN
}

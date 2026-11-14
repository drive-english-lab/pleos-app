package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.LearningSession
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import java.util.UUID
import android.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

internal interface ProfileAuth {
    suspend fun ensureSession(allowCreate: Boolean, beforeCreate: suspend () -> Unit): LearningSession
    suspend fun currentSession(): LearningSession?
    suspend fun bootstrapOperation(profileId: UUID): UUID
    suspend fun protectedEvidence(): io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence = io.github.driveenglishlab.pleos.domain.ProtectedIdentityEvidence.UNKNOWN
    suspend fun diagnosticState():ProtectedIdentityDiagnostic=ProtectedIdentityDiagnostic(null,null,null,protectedEvidence())
    suspend fun ensureSessionTraced(allowCreate:Boolean,beforeCreate:suspend()->Unit,stage:(ProfileConnectionStage)->Unit):LearningSession {
        stage(ProfileConnectionStage.PROTECTED_IDENTITY_READ)
        return ensureSession(allowCreate,beforeCreate)
    }
}

/** SDK handles anonymous Auth and refresh. The server alone verifies JWT/ownership.
 * Manual loading/refresh avoids SDK load failures being converted into a new anonymous signup.
 */
@OptIn(io.github.jan.supabase.annotations.SupabaseInternal::class)
internal class SupabaseProfileAuth(private val store: AuthSessionStore) : ProfileAuth {
    private val mutex = Mutex()
    private val client by lazy {
        check(BuildConfig.SUPABASE_URL.isNotBlank()) { "Auth is not configured." }
        createSupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) {
            defaultLogLevel = LogLevel.NONE
            httpConfig { followRedirects = false }
            install(Auth) {
                autoLoadFromStorage = false; autoSaveToStorage = true; alwaysAutoRefresh = false
                enableLifecycleCallbacks = false; sessionManager = store
            }
        }
    }
    override suspend fun ensureSession(allowCreate: Boolean, beforeCreate: suspend () -> Unit): LearningSession =
        ensureSessionTraced(allowCreate,beforeCreate){}
    override suspend fun ensureSessionTraced(allowCreate:Boolean,beforeCreate:suspend()->Unit,stage:(ProfileConnectionStage)->Unit):LearningSession = mutex.withLock {
        stage(ProfileConnectionStage.PROTECTED_IDENTITY_READ)
        val saved = store.identity()
        stage(ProfileConnectionStage.AUTH_CLIENT_INIT)
        val previous = client.auth.currentSessionOrNull() ?: saved.session
        if (previous == null) {
            stage(ProfileConnectionStage.CREATION_FENCE)
            check(allowCreate && !saved.creationStarted) { "Existing session needs recovery." }
            beforeCreate()
            store.beginCreation() // Durable fence before non-idempotent Auth signup.
            stage(ProfileConnectionStage.AUTH_CREATE)
            client.auth.signInAnonymously()
        } else {
            val current = if (previous.expiresAt <= Clock.System.now() + 60.seconds) {
                stage(ProfileConnectionStage.AUTH_REFRESH)
                client.auth.refreshSession(previous.refreshToken)
            } else previous
            stage(ProfileConnectionStage.AUTH_IMPORT)
            client.auth.importSession(current, autoRefresh = false)
        }
        stage(ProfileConnectionStage.AUTH_CURRENT_SESSION)
        currentSession() ?: error("Auth session unavailable.")
    }
    override suspend fun currentSession(): LearningSession? {
        val session = client.auth.currentSessionOrNull() ?: return null
        if (session.expiresAt <= Clock.System.now()) return null
        val parts = session.accessToken.split('.'); require(parts.size == 3)
        val claims = learningJson.parseToJsonElement(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING).toString(Charsets.UTF_8)).jsonObject
        val subject = UUID.fromString(claims.getValue("sub").jsonPrimitive.content)
        require(subject.toString() == session.user?.id)
        return LearningSession(subject, UUID.fromString(claims.getValue("session_id").jsonPrimitive.content),
            UUID.fromString(store.identity().installationId), session.accessToken)
    }
    override suspend fun protectedEvidence() = store.protectedEvidence()
    override suspend fun diagnosticState()=store.diagnosticState()
    override suspend fun bootstrapOperation(profileId: UUID) = store.bootstrapOperation(profileId)
    internal suspend fun signInGoogle(idToken: String, verifiedNonce: String?) {
        require(idToken.isNotBlank())
        client.auth.signInWith(IDToken) { this.idToken = idToken; provider = Google; nonce = verifiedNonce }
    }
    internal suspend fun linkGoogle(idToken: String, verifiedNonce: String?) {
        require(idToken.isNotBlank())
        client.auth.linkIdentityWithIdToken(Google, idToken) { nonce = verifiedNonce }
    }
    /** Recovery of this isolated session only; never refreshes or creates an identity. */
    internal suspend fun importCapturedSession(session: io.github.jan.supabase.auth.user.UserSession) {
        client.auth.importSession(session, autoRefresh = false)
    }
    internal suspend fun signOutLocal() { client.auth.signOut(SignOutScope.LOCAL) }
    internal suspend fun close() { client.close() }
}

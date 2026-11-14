package io.github.driveenglishlab.pleos.data

import android.content.Context
import androidx.room.withTransaction
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class IsolatedLogout(val request: WirePersonalLogoutRequest, val ownerRevision: Long, val privacyEpoch: Long,
    val presentationToken: String, val priorSession: io.github.jan.supabase.auth.user.UserSession, val serverConfirmed: Boolean = false, val providerConfirmed: Boolean = false)

/** First fence the current owner durably. SDK sign-out only touches the protected old client. */
class OwnerTransition(private val context: Context, private val database: LearningDatabase,
    private val profiles: ProfileRepository, private val origin: String, private val allowLocalHttp: Boolean = false) {
    suspend fun logout(): LearningReply<Unit> {
        val call = profiles.current() ?: return LearningReply.Failed("SESSION_UNAVAILABLE", true)
        val op = UUID.randomUUID(); val reference = "learning-logout-$op"
        val request = WirePersonalLogoutRequest(op.toString(), call.owner.profileWire(), call.session.sessionId.toString(), "5")
        val active = AuthSessionStore(context)
        val identity = active.identity()
        val state = IsolatedLogout(request, call.owner.revision, call.owner.privacyEpoch, call.owner.presentationToken.toString(), requireNotNull(identity.session))
        // Journal before credentials move. A crash blocks connect/current and can resume here.
        database.withTransaction {
            database.learning().fenceOwner(call.owner, op, "logout", reference)
            FeatureLifecycle(database).isolateForOwnerTransition(call.owner.profileId)
        }
        AuthSessionStore(context, reference).initializeIsolated(identity.copy(accountState = learningJson.encodeToString(IsolatedLogout.serializer(), state)))
        active.replaceForTransition(identity.session?.user?.id, StoredIdentity(installationId = identity.installationId))
        profiles.useFreshGuestAuth(SupabaseProfileAuth(active), call.owner.revision + 1)
        // Actual server/provider revocation may remain pending; fresh guest creation is independent.
        return LearningReply.Stored(call, Unit)
    }

    suspend fun recoverAndRetry() {
        for (journal in database.learning().pendingOwnerTransitions().filter { it.kind == "logout" }) {
            val reference = journal.isolatedSessionReference ?: continue
            val isolated = AuthSessionStore(context, reference)
            var identity = isolated.identity()
            val active = AuthSessionStore(context)
            if (identity.accountState == null) {
                // Crash after Room fence, before isolated copy. The active store is still old.
                val previous = active.identity()
                val session = previous.session ?: continue
                val request = WirePersonalLogoutRequest(journal.operationId, WireCommonProfileContext(journal.profileId,
                    requireNotNull(database.learning().currentOwner()).privacyEpoch), session.accessToken.split('.').let {
                        val claims = learningJson.parseToJsonElement(android.util.Base64.decode(it[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP).toString(Charsets.UTF_8))
                        claims.jsonObject.getValue("session_id").jsonPrimitive.content
                    }, "5")
                val value = IsolatedLogout(request, journal.ownerRevision, request.profileContext.privacyEpoch, UUID.randomUUID().toString(), session)
                isolated.initializeIsolated(previous.copy(accountState = learningJson.encodeToString(IsolatedLogout.serializer(), value)))
                identity = isolated.identity()
            }
            val value = learningJson.decodeFromString(IsolatedLogout.serializer(), requireNotNull(identity.accountState))
            val local = database.learning().currentOwner()
            if (local?.status == "transition" && local.profileId == journal.profileId) {
                val current = active.identity()
                if (current.session?.user?.id == identity.session?.user?.id) active.replaceForTransition(current.session?.user?.id, StoredIdentity(installationId = identity.installationId))
                profiles.useFreshGuestAuth(SupabaseProfileAuth(active), journal.ownerRevision + 1)
            }
            val oldAuth = SupabaseProfileAuth(isolated)
            try {
                var updated = value
                if (!updated.serverConfirmed) {
                    if (identity.session == null) isolated.initializeIsolated(identity.copy(session = value.priorSession))
                    val oldSession = oldAuth.ensureSession(false) { error("No signup in logout retry") }
                    val owner = OwnerContext(UUID.fromString(journal.profileId), value.privacyEpoch, value.ownerRevision, UUID.fromString(value.presentationToken))
                    val call = LearningCallScope(owner, oldSession)
                    val oldApi = LearningApi(context, origin, LearningSessionSource { call }, allowLocalHttp)
                    if (oldApi.logoutCurrentSession(call, value.request) is LearningReply.Stored) {
                        updated = updated.copy(serverConfirmed = true)
                        isolated.saveAccountState(learningJson.encodeToString(IsolatedLogout.serializer(), updated))
                    }
                }
                if (updated.serverConfirmed && !updated.providerConfirmed) {
                    // Server revocation may prevent a refresh. Replay provider LOCAL sign-out
                    // with the protected captured identity, without touching the fresh guest.
                    oldAuth.importCapturedSession(value.priorSession)
                    oldAuth.signOutLocal()
                    updated = updated.copy(providerConfirmed = true)
                    isolated.saveAccountState(learningJson.encodeToString(IsolatedLogout.serializer(), updated))
                }
                if (updated.serverConfirmed && updated.providerConfirmed) database.learning().updateOwnerJournal(journal.operationId, "complete")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
              catch (_: Exception) { /* Preserve isolated retry journal; never sign out a fresh guest. */ }
            finally { oldAuth.close() }
        }
    }
}

/** Auth/settings and independent band/daily choices survive learning erasure. */
internal suspend fun eraseLocalLearning(database: LearningDatabase, profile: String, epoch: Long) {
    require(database.inTransaction()) { "Erasure must share the confirmed owner/epoch transaction." }
    require(epoch>=0)
    FeatureLifecycle(database).clearConfirmedThroughEpoch(UUID.fromString(profile),epoch)
    val db = database.openHelper.writableDatabase
    for (table in listOf("learning_outbox", "history_coverage", "practice_binding", "presentation_ticket", "presentation_observation",
        "presentation_receipt", "dialogue_completion"))
        db.execSQL("DELETE FROM $table WHERE profileId = ? AND privacyEpoch <= ?", arrayOf<Any>(profile, epoch))
    db.execSQL("DELETE FROM learning_record WHERE profileId = ? AND privacyEpoch <= ? AND kind != 'profile_settings_command'", arrayOf<Any>(profile, epoch))
}

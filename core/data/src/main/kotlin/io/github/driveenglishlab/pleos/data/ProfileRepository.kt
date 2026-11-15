package io.github.driveenglishlab.pleos.data

import android.content.Context
import androidx.room.withTransaction
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stable guest identity and settings. No implicit login, merge, resume selection or playback. */
class ProfileRepository internal constructor(
    private val database: LearningDatabase,
    private var auth: ProfileAuth,
    apiFactory: (LearningSessionSource) -> LearningApi,
) : LearningSessionSource {
    constructor(context: Context, database: LearningDatabase, serviceOrigin: String, allowLocalHttp: Boolean = false) : this(
        database, SupabaseProfileAuth(AuthSessionStore(context.applicationContext)),
        { sessions -> LearningApi(context.applicationContext, serviceOrigin, sessions, allowLocalHttp) })

    @Volatile private var transitioning = false
    @Volatile private var confirmedBinding: ProfileContext? = null
    private val mutex = Mutex()
    private val presentationToken = UUID.randomUUID()
    private val api = apiFactory(this)
    private val dao get() = database.learning()
    @Volatile internal var lastConnectionDiagnostic=ProfileConnectionTrace()
        private set
    private fun connectionStage(stage:ProfileConnectionStage) { lastConnectionDiagnostic=ProfileConnectionTrace(stage) }

    /** Read-only bounded diagnostics; never invokes Auth create/refresh/import or backend traffic. */
    internal suspend fun inspectRecoveryDiagnostics():ProfileRecoveryDiagnostic {
        val identity=auth.diagnosticState()
        val local=dao.currentOwner()
        val fenced=local?.let{dao.authCreation(it.profileId)!=null}
        val incomplete=identity.sessionPresent==false && (identity.creationStarted==true || fenced==true)
        return ProfileRecoveryDiagnostic(
            if(identity.failure!=ProfileConnectionFailure.NONE)ProfileConnectionStage.PROTECTED_IDENTITY_READ
            else if(incomplete)ProfileConnectionStage.CREATION_FENCE else ProfileConnectionStage.IDLE,
            if(incomplete)ProfileConnectionFailure.STATE_OR_CREATION_FENCE else identity.failure,
            identity,local!=null,fenced,lastConnectionDiagnostic)
    }

    /** Debug instrumentation bridge: fixed keys, enum values and nullable booleans only. */
    suspend fun debugRecoveryDiagnostics():Map<String,String> {
        check(BuildConfig.DEBUG) { "Debug diagnostics unavailable." }
        val value=inspectRecoveryDiagnostics()
        return linkedMapOf("stage" to value.stage.name,"failure" to value.failure.name,
            "protected_evidence" to value.identity.evidence.name,
            "encrypted_file_present" to value.identity.encryptedFilePresent.toString(),
            "creation_started" to value.identity.creationStarted.toString(),"session_present" to value.identity.sessionPresent.toString(),
            "owner_present" to value.ownerPresent.toString(),"auth_creation_fence_present" to value.authCreationFencePresent.toString(),
            "last_connection_stage" to value.lastConnection.stage.name,"last_connection_failure" to value.lastConnection.failure.name)
    }

    override suspend fun current(): LearningCallScope? = try {
        if (transitioning) return null
        val owner = dao.currentOwner() ?: return null
        if (owner.status !in setOf("provisional", "active")) return null
        val session = auth.currentSession() ?: return null
        if (transitioning || dao.currentOwner() != owner) return null
        LearningCallScope(OwnerContext(UUID.fromString(owner.profileId), owner.privacyEpoch, owner.revision, presentationToken), session)
    } catch (error: CancellationException) { throw error }
      catch (_: Exception) { null }

    /** Reports only current binding receipts and retained journals, never a promise of total recovery. */
    suspend fun recoveryProjection(): IdentityRecoveryProjection {
        val local = dao.currentOwner()
        val owner = local?.let { OwnerContext(UUID.fromString(it.profileId), it.privacyEpoch, it.revision, presentationToken) }
        var evidence = auth.protectedEvidence()
        val current = current()
        if (evidence == ProtectedIdentityEvidence.AVAILABLE && current == null && !transitioning && local?.status in setOf("active", "provisional"))
            evidence = ProtectedIdentityEvidence.READ_RETRYABLE
        val profile = local?.let { dao.record(it.profileId, it.privacyEpoch, stableRecordId("profile-view").toString()) }
            ?.let { learningJson.decodeFromString(WirePersonalProfileView.serializer(), it.payloadJson) }
        val pending = local?.let {
            dao.pending(it.profileId, it.privacyEpoch).isNotEmpty() || database.progress().pending(it.profileId, it.privacyEpoch).isNotEmpty() ||
                database.aiLearning().pending(it.profileId, it.privacyEpoch).isNotEmpty() || database.segments().pending(it.profileId, it.privacyEpoch).isNotEmpty() ||
                database.review().pending(it.profileId, it.privacyEpoch, it.revision).isNotEmpty() || dao.pendingProfileSettings(it.profileId) != null
        } ?: false
        return IdentityRecoveryPolicy.project(IdentityRecoveryEvidence(owner, evidence,
            accountSignInRequired = profile?.profileKind == "linked", verifiedServerBinding = confirmedBinding,
            currentServerAcknowledgement = current?.owner == owner && confirmedBinding == owner?.let { ProfileContext(it.profileId, it.privacyEpoch) },
            pendingOutbox = pending, transitionPending = transitioning || local?.status !in setOf("active", "provisional")))
    }

    /** On restoration/network/storage failure retain the same local owner and immutable outbox. */
    suspend fun connect(allowCreation: Boolean = true): LearningReply<WirePersonalProfileView> = mutex.withLock {
        try {
            connectionStage(ProfileConnectionStage.OWNER_READ)
            val local = dao.provisionalOwner()
            if (local.status !in setOf("provisional", "active")) return@withLock LearningReply.Failed("PROFILE_TRANSITION_PENDING", false)
            auth.ensureSessionTraced(allowCreate = allowCreation && local.status == "provisional" && dao.authCreation(local.profileId) == null,beforeCreate = {
                database.withTransaction {
                    check(dao.currentOwner() == local && dao.authCreation(local.profileId) == null)
                    dao.insertJournal(OwnerJournal(UUID.randomUUID().toString(), local.profileId, local.revision, "auth_creation", "started", null))
                }
            },stage=::connectionStage)
            connectionStage(ProfileConnectionStage.CURRENT_SCOPE)
            val scope = current() ?: run {
                lastConnectionDiagnostic=ProfileConnectionTrace(ProfileConnectionStage.CURRENT_SCOPE,ProfileConnectionFailure.SESSION_UNAVAILABLE)
                return@withLock LearningReply.Failed("SESSION_UNAVAILABLE", true)
            }
            connectionStage(ProfileConnectionStage.BOOTSTRAP_OPERATION)
            val operation = auth.bootstrapOperation(UUID.fromString(local.profileId))
            connectionStage(ProfileConnectionStage.BOOTSTRAP_HTTP)
            when (val boot = api.bootstrapProfile(scope, WirePersonalBootstrapRequest(operation.toString(), local.profileId, "5"))) {
                is LearningReply.Failed -> boot
                is LearningReply.Stored -> {
                    if (boot.value.profileContext.profileId != local.profileId) return@withLock LearningReply.Failed("PROFILE_MERGE_REQUIRED", false)
                    // Bootstrap receipts are immutable; read the current epoch/version separately.
                    connectionStage(ProfileConnectionStage.PROFILE_HTTP)
                    when (val latest = api.readProfile(scope)) {
                        is LearningReply.Failed -> latest
                        is LearningReply.Stored -> {
                            connectionStage(ProfileConnectionStage.ACCEPT_PROFILE)
                            acceptProfile(scope, latest.value).also{if(it is LearningReply.Stored)connectionStage(ProfileConnectionStage.COMPLETE)}
                        }
                    }
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            lastConnectionDiagnostic=lastConnectionDiagnostic.copy(failure=profileConnectionFailure(error))
            LearningReply.Failed("SESSION_RECOVERY_REQUIRED", true)
        }
    }

    suspend fun refresh(): LearningReply<WirePersonalProfileView> = mutex.withLock {
        val scope = current() ?: return@withLock LearningReply.Failed("SESSION_UNAVAILABLE", true)
        when (val result = api.readProfile(scope)) {
            is LearningReply.Failed -> result
            is LearningReply.Stored -> acceptProfile(scope, result.value)
        }
    }

    /** Persist exact operation before sending. Retry this command; never replace a pending edit. */
    suspend fun updateSettings(request: WirePersonalSettingsPatch): LearningReply<WirePersonalProfileView> = mutex.withLock {
        val scope = current() ?: return@withLock LearningReply.Failed("SESSION_UNAVAILABLE", true)
        try { api.validateSettings(request) }
        catch (_: IllegalArgumentException) { return@withLock LearningReply.Failed("INVALID_CONTRACT", false) }
        if (request.profileContext.profileId != scope.owner.profileId.toString() || request.profileContext.privacyEpoch > scope.owner.privacyEpoch)
            return@withLock LearningReply.Failed("SESSION_CHANGED", false)
        val payload = learningJson.encodeToString(WirePersonalSettingsPatch.serializer(), request)
        database.withTransaction {
            requireCurrent(scope.owner)
            val old = dao.pendingProfileSettings(scope.owner.profileId.toString())
            if (old != null && old.payloadJson != payload) return@withTransaction false
            if (old == null) dao.insertRecord(LearningRecord(scope.owner.profileId.toString(), scope.owner.privacyEpoch,
                request.operationId, "profile_settings_command", payload, 1))
            true
        }.let { if (!it) return@withLock LearningReply.Failed("SETTINGS_PENDING", false) }
        sendSettings(scope, request, payload)
    }

    suspend fun retrySettings(): LearningReply<WirePersonalProfileView> = mutex.withLock {
        val scope = current() ?: return@withLock LearningReply.Failed("SESSION_UNAVAILABLE", true)
        val command = dao.pendingProfileSettings(scope.owner.profileId.toString())
            ?: return@withLock LearningReply.Failed("NO_PENDING_SETTINGS", false)
        sendSettings(scope, learningJson.decodeFromString(WirePersonalSettingsPatch.serializer(), command.payloadJson), command.payloadJson)
    }

    suspend fun hasPendingSettings(): Boolean {
        val owner = current()?.owner ?: return false
        return dao.pendingProfileSettings(owner.profileId.toString()) != null
    }

    /** Explicit reconciliation only: retry original bytes first. A confirmed CAS rejection may
     * retire that rejected command after a current read; no stale edit is automatically rebased.
     */
    suspend fun reconcileSettings(): LearningReply<WirePersonalProfileView> = mutex.withLock {
        val scope = current() ?: return@withLock LearningReply.Failed("SESSION_UNAVAILABLE", true)
        val command = dao.pendingProfileSettings(scope.owner.profileId.toString())
            ?: return@withLock when (val latest = api.readProfile(scope)) {
                is LearningReply.Failed -> latest
                is LearningReply.Stored -> acceptProfile(scope, latest.value)
            }
        val request = learningJson.decodeFromString(WirePersonalSettingsPatch.serializer(), command.payloadJson)
        val original = sendSettings(scope, request, command.payloadJson)
        if (original !is LearningReply.Failed || original.code != "VERSION_MISMATCH") return@withLock original
        when (val latest = api.readProfile(scope)) {
            is LearningReply.Failed -> latest
            is LearningReply.Stored -> acceptProfile(scope, latest.value, request.operationId to command.payloadJson)
        }
    }

    private suspend fun sendSettings(scope: LearningCallScope, request: WirePersonalSettingsPatch, payload: String): LearningReply<WirePersonalProfileView> =
        when (val result = api.updatePreferences(scope, request)) {
            is LearningReply.Failed -> result
            is LearningReply.Stored -> acceptProfile(scope, result.value, request.operationId to payload)
        }

    internal suspend fun reloadProtectedAuth(replacement: ProfileAuth) = mutex.withLock {
        val oldSession = auth.currentSession() ?: error("Current identity unavailable")
        val fresh = replacement.ensureSession(false) { error("No automatic creation") }
        require(fresh.subjectId == oldSession.subjectId && fresh.installationId == oldSession.installationId)
        val previous = auth; auth = replacement
        (previous as? SupabaseProfileAuth)?.close()
    }

    internal suspend fun maintenanceScope(): LearningCallScope? {
        val local = dao.currentOwner()?.takeIf { it.status == "delete_pending" } ?: return current()
        val session = auth.ensureSession(false) { error("Creation is not permitted during deletion") }
        return LearningCallScope(OwnerContext(UUID.fromString(local.profileId), local.privacyEpoch, local.revision, presentationToken), session)
    }

    internal suspend fun adoptIdentity(newAuth: ProfileAuth, profile: WirePersonalProfileView, expectedRevision: Long,
        cleanupSource: Pair<String, Long>? = null, transitionOperation: String? = null, mayAdopt: () -> Boolean = { true }): LearningReply<WirePersonalProfileView> = mutex.withLock { withContext(NonCancellable) {
        transitioning = true
        try {
        val session = newAuth.ensureSession(false) { error("No automatic signup during adoption") }
        if (!mayAdopt()) return@withContext LearningReply.Failed("ACCOUNT_ACTION_CONTEXT_CHANGED", false)
        val owner = database.withTransaction {
            val previous = requireNotNull(dao.currentOwner())
            require(previous.revision == expectedRevision && previous.status == "transition")
            FeatureLifecycle(database).isolateForOwnerTransition(UUID.fromString(previous.profileId))
            database.openHelper.writableDatabase.query("SELECT MAX(privacyEpoch) FROM learning_record WHERE profileId=? AND kind='profile_view'",
                arrayOf<Any>(profile.profileContext.profileId)).use{rows->
                if(rows.moveToFirst() && !rows.isNull(0))require(rows.getLong(0)<=profile.profileContext.privacyEpoch){"Confirmed profile epoch cannot regress."}
            }
            if(profile.profileContext.privacyEpoch>0)eraseLocalLearning(database,profile.profileContext.profileId,profile.profileContext.privacyEpoch-1)
            LocalOwner(profileId = profile.profileContext.profileId, privacyEpoch = profile.profileContext.privacyEpoch,
                revision = previous.revision + 1, status = "active").also { next ->
                dao.putOwner(next)
                cleanupSource?.let { (source, epoch) -> require(source != next.profileId); eraseLocalLearning(database, source, epoch) }
                transitionOperation?.let { dao.updateOwnerJournal(it, "complete") }
                dao.saveRead(OwnerContext(UUID.fromString(next.profileId), next.privacyEpoch, next.revision, presentationToken),
                    LearningRecord(next.profileId, next.privacyEpoch, stableRecordId("profile-view").toString(), "profile_view", learningJson.encodeToString(WirePersonalProfileView.serializer(), profile), profile.version))
            }
        }
        val previousAuth = auth; auth = newAuth
        (previousAuth as? SupabaseProfileAuth)?.close()
        LearningReply.Stored(LearningCallScope(OwnerContext(UUID.fromString(owner.profileId), owner.privacyEpoch, owner.revision, presentationToken), session), profile)
        } finally { transitioning = false }
    } }

    internal suspend fun useFreshGuestAuth(newAuth: ProfileAuth, expectedRevision: Long) = mutex.withLock { withContext(NonCancellable) {
        transitioning = true
        try {
        database.withTransaction {
            val previous = requireNotNull(dao.currentOwner())
            require(previous.revision == expectedRevision && previous.status == "transition")
            FeatureLifecycle(database).isolateForOwnerTransition(UUID.fromString(previous.profileId))
            dao.putOwner(LocalOwner(profileId = UUID.randomUUID().toString(), privacyEpoch = 0, revision = previous.revision + 1, status = "provisional"))
        }
        val previousAuth = auth; auth = newAuth
        (previousAuth as? SupabaseProfileAuth)?.close()
        } finally { transitioning = false }
    } }

    internal suspend fun reconcileDeletion(profile: WirePersonalProfileView, operation: String, expectedRevision: Long,
        receipt: WirePersonalDeletionReceipt?) = mutex.withLock { withContext(NonCancellable) {
        database.withTransaction {
            val local = requireNotNull(dao.currentOwner())
            require(local.status == "delete_pending" && local.revision == expectedRevision && local.profileId == profile.profileContext.profileId &&
                profile.profileContext.privacyEpoch > local.privacyEpoch)
            if (receipt != null) require(receipt.profileContext.profileId == local.profileId && receipt.previousPrivacyEpoch == local.privacyEpoch &&
                receipt.profileContext.privacyEpoch <= profile.profileContext.privacyEpoch && receipt.operationId == operation)
            eraseLocalLearning(database, local.profileId, profile.profileContext.privacyEpoch - 1)
            val next = local.copy(privacyEpoch = profile.profileContext.privacyEpoch, revision = local.revision + 1, status = "active")
            dao.putOwner(next)
            val owner = OwnerContext(UUID.fromString(next.profileId), next.privacyEpoch, next.revision, presentationToken)
            dao.saveRead(owner, readRecord(owner, stableRecordId("profile-view"), "profile_view", learningJson.encodeToString(WirePersonalProfileView.serializer(), profile), profile.version))
            val result = kotlinx.serialization.json.buildJsonObject {
                put("operation_id", kotlinx.serialization.json.JsonPrimitive(operation))
                put("requested_epoch", kotlinx.serialization.json.JsonPrimitive(local.privacyEpoch))
                put("confirmed_server_epoch", kotlinx.serialization.json.JsonPrimitive(next.privacyEpoch))
                put("resolution", kotlinx.serialization.json.JsonPrimitive(if (receipt != null) "own_deletion_receipt" else "old_generation_erased_elsewhere"))
            }
            dao.saveRead(owner, readRecord(owner, UUID.fromString(operation), "deletion_resolution", result.toString(), 1))
            dao.updateOwnerJournal(operation, "complete")
        }
    } }

    internal suspend fun confirmDeletion(receipt: WirePersonalDeletionReceipt, expectedRevision: Long) = mutex.withLock {
        database.withTransaction {
            val local = requireNotNull(dao.currentOwner())
            require(local.profileId == receipt.profileContext.profileId && local.revision == expectedRevision && local.status == "delete_pending" &&
                local.privacyEpoch == receipt.previousPrivacyEpoch && receipt.profileContext.privacyEpoch == local.privacyEpoch + 1)
            eraseLocalLearning(database, local.profileId, local.privacyEpoch)
            dao.putOwner(local.copy(privacyEpoch = receipt.profileContext.privacyEpoch, revision = local.revision + 1, status = "active"))
            dao.updateOwnerJournal(receipt.operationId, "complete")
        }
    }

    private suspend fun requireCurrent(owner: OwnerContext) {
        val value = dao.currentOwner()
        require(value != null && value.profileId == owner.profileId.toString() && value.privacyEpoch == owner.privacyEpoch &&
            value.revision == owner.revision && value.status in setOf("active", "provisional")) { "Owner changed." }
    }

    private suspend fun acceptProfile(scope: LearningCallScope, value: WirePersonalProfileView,
                                      acknowledged: Pair<String, String>? = null): LearningReply<WirePersonalProfileView> {
        val now = current()
        if (now?.owner != scope.owner || now.session.subjectId != scope.session.subjectId || now.session.sessionId != scope.session.sessionId ||
            now.session.installationId != scope.session.installationId)
            return LearningReply.Failed("SESSION_CHANGED", false)
        return database.withTransaction {
            requireCurrent(scope.owner)
            require(value.profileContext.profileId == scope.owner.profileId.toString() && value.profileContext.privacyEpoch >= scope.owner.privacyEpoch)
            val previous = requireNotNull(dao.currentOwner())
            val epoch = value.profileContext.privacyEpoch
            if (epoch > previous.privacyEpoch) {
                // A server-confirmed history generation change discards old learning, not settings.
                eraseLocalLearning(database,previous.profileId,epoch-1)
            }
            val owner = previous.copy(privacyEpoch = epoch, status = "active",
                revision = previous.revision + if (epoch > previous.privacyEpoch) 1 else 0)
            dao.putOwner(owner)
            val newScope = scope.copy(owner = scope.owner.copy(privacyEpoch = epoch, revision = owner.revision))
            val id = stableRecordId("profile-view").toString()
            val cached = dao.record(owner.profileId, epoch, id)
            val accepted = if (cached != null && cached.version > value.version)
                learningJson.decodeFromString(WirePersonalProfileView.serializer(), cached.payloadJson) else value
            dao.saveRead(newScope.owner, LearningRecord(owner.profileId, epoch, id, "profile_view",
                learningJson.encodeToString(WirePersonalProfileView.serializer(), accepted), accepted.version))
            dao.putHistoryCoverage(LocalHistoryCoverage(owner.profileId, epoch, accepted.historyCoverage.origin,
                accepted.historyCoverage.version, accepted.historyCoverage.historyGap))
            acknowledged?.let { (operation, bytes) -> check(dao.acknowledgeProfileSettings(owner.profileId, operation, bytes) == 1) }
            confirmedBinding = ProfileContext(newScope.owner.profileId, newScope.owner.privacyEpoch)
            LearningReply.Stored(newScope, accepted)
        }
    }
}

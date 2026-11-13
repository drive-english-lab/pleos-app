package io.github.driveenglishlab.pleos.data

import android.content.Context
import androidx.room.withTransaction
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stable guest identity and settings. No implicit login, merge, resume selection or playback. */
class ProfileRepository internal constructor(
    private val database: LearningDatabase,
    private val auth: ProfileAuth,
    apiFactory: (LearningSessionSource) -> LearningApi,
) : LearningSessionSource {
    constructor(context: Context, database: LearningDatabase, serviceOrigin: String) : this(
        database, SupabaseProfileAuth(AuthSessionStore(context.applicationContext)),
        { sessions -> LearningApi(context.applicationContext, serviceOrigin, sessions) })

    private val mutex = Mutex()
    private val presentationToken = UUID.randomUUID()
    private val api = apiFactory(this)
    private val dao get() = database.learning()

    override suspend fun current(): LearningCallScope? {
        val owner = dao.currentOwner() ?: return null
        if (owner.status !in setOf("provisional", "active")) return null
        val session = auth.currentSession() ?: return null
        return LearningCallScope(OwnerContext(UUID.fromString(owner.profileId), owner.privacyEpoch, owner.revision, presentationToken), session)
    }

    /** On restoration/network/storage failure retain the same local owner and immutable outbox. */
    suspend fun connect(): LearningReply<WirePersonalProfileView> = mutex.withLock {
        try {
            val local = dao.provisionalOwner()
            if (local.status !in setOf("provisional", "active")) return@withLock LearningReply.Failed("PROFILE_TRANSITION_PENDING", false)
            auth.ensureSession(allowCreate = local.status == "provisional" && dao.authCreation(local.profileId) == null) {
                database.withTransaction {
                    check(dao.currentOwner() == local && dao.authCreation(local.profileId) == null)
                    dao.insertJournal(OwnerJournal(UUID.randomUUID().toString(), local.profileId, local.revision, "auth_creation", "started", null))
                }
            }
            val scope = current() ?: return@withLock LearningReply.Failed("SESSION_UNAVAILABLE", true)
            val operation = auth.bootstrapOperation(UUID.fromString(local.profileId))
            when (val boot = api.bootstrapProfile(scope, WirePersonalBootstrapRequest(operation.toString(), local.profileId, "5"))) {
                is LearningReply.Failed -> boot
                is LearningReply.Stored -> {
                    if (boot.value.profileContext.profileId != local.profileId) return@withLock LearningReply.Failed("PROFILE_MERGE_REQUIRED", false)
                    // Bootstrap receipts are immutable; read the current epoch/version separately.
                    when (val latest = api.readProfile(scope)) {
                        is LearningReply.Failed -> latest
                        is LearningReply.Stored -> acceptProfile(scope, latest.value)
                    }
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { LearningReply.Failed("SESSION_RECOVERY_REQUIRED", true) }
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

    private suspend fun sendSettings(scope: LearningCallScope, request: WirePersonalSettingsPatch, payload: String): LearningReply<WirePersonalProfileView> =
        when (val result = api.updatePreferences(scope, request)) {
            is LearningReply.Failed -> result
            is LearningReply.Stored -> acceptProfile(scope, result.value, request.operationId to payload)
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
                val db = database.openHelper.writableDatabase
                for (table in listOf("learning_outbox", "history_coverage", "practice_binding", "presentation_ticket",
                    "presentation_observation", "presentation_receipt", "dialogue_completion",
                    "speaking_records_cache", "speaking_records_journal", "ai_learning_cache", "ai_learning_journal")) {
                    db.execSQL("DELETE FROM $table WHERE profileId = ? AND privacyEpoch < ?", arrayOf<Any>(previous.profileId, epoch))
                }
                db.execSQL("DELETE FROM learning_record WHERE profileId = ? AND privacyEpoch < ? AND kind != 'profile_settings_command'", arrayOf<Any>(previous.profileId, epoch))
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
            LearningReply.Stored(newScope, accepted)
        }
    }
}

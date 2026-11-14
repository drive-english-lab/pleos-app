package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Selected band is a preference for the next selection, not a learner diagnosis or a material. */
data class LearningPreferenceState(val owner: OwnerContext? = null, val band: LearningBand = LearningBand.BASIC,
    val explicit: Boolean = false, val local: Boolean = false, val pending: Boolean = false, val conflict: Boolean = false,
    val serverVersion: Long = 0, val serverChoiceId: UUID? = null, val remoteChanged: Boolean = false,
    val loading: Boolean = false, val failed: Boolean = false)

data class NextSelectionPreference(val band: LearningBand, val preferenceVersion: Long, val choiceId: UUID?, val remoteChanged: Boolean)

class LearningPreferenceRepository(private val api: AiLearningApi, private val database: LearningDatabase,
    private val current: suspend (OwnerContext) -> Boolean) {
    private val lane = Mutex()
    private val mutable = MutableStateFlow(LearningPreferenceState())
    val state = mutable.asStateFlow()
    private val dao get() = database.aiLearning()

    suspend fun bind(scope: LearningCallScope) = lane.withLock {
        if (!current(scope.owner)) return@withLock
        mutable.value = projection(scope)
    }
    suspend fun clear() { mutable.value = LearningPreferenceState() }

    private suspend fun cached(scope: LearningCallScope): WireAiLearningLearningPreferenceView? =
        dao.preference(scope.owner.profileId.toString())?.let { featureJson.decodeFromString(WireAiLearningLearningPreferenceView.serializer(), it.payloadJson) }

    private suspend fun projection(scope: LearningCallScope, remoteChanged: Boolean = false, failed: Boolean = false): LearningPreferenceState {
        val server = cached(scope)
        val last = dao.latestPreference(scope.owner.profileId.toString())
        val local = last?.takeIf { it.state in setOf("pending", "sending", "conflict") }
        val choice = local?.let { featureJson.decodeFromString(WireAiLearningLearningPreferenceRequest.serializer(), it.payloadJson).choice } ?: server?.choice
        return LearningPreferenceState(scope.owner, choice?.band?.band() ?: server?.effectiveBand?.band() ?: LearningBand.BASIC,
            explicit = choice?.explicit == true, local = local != null, pending = local?.state in setOf("pending", "sending"), conflict = local?.state == "conflict",
            serverVersion = server?.preferenceVersion ?: 0, serverChoiceId = server?.choice?.choiceId?.let(UUID::fromString), remoteChanged = remoteChanged, failed = failed)
    }

    /** Durable original bytes precede the immediate local overlay. A further explicit choice
     * supersedes old commands; their IDs/payloads remain retained and are never rebased.
     */
    suspend fun choose(scope: LearningCallScope, band: LearningBand): LearningReply<LearningPreferenceState> = lane.withLock {
        if (!current(scope.owner)) return@withLock LearningReply.Failed("SESSION_CHANGED", false)
        val server = cached(scope)
        val previous = dao.latestPreference(scope.owner.profileId.toString())
        val choice = WireAiLearningLearningPreferenceChoice(UUID.randomUUID().toString(), band.wireBand(), true)
        val request = WireAiLearningLearningPreferenceRequest("5", "1", UUID.randomUUID().toString(), scope.owner.profileId.toString(),
            server?.preferenceVersion ?: 0, server?.choice?.choiceId, choice)
        val bytes = featureJson.encodeToString(WireAiLearningLearningPreferenceRequest.serializer(), request)
        dao.replacePreferenceIntent(scope.owner, LocalAiLearningPreferenceJournal(request.operationId, request.profileId, scope.owner.revision,
            (previous?.localSequence ?: 0) + 1, featureJson.encodeToString(WireAiLearningLearningPreferenceChoice.serializer(), choice),
            bytes, LearningJson.hash(bytes), "pending", false))
        val value = projection(scope)
        if (current(scope.owner)) mutable.value = value
        LearningReply.Stored(scope, value)
    }

    suspend fun refresh(scope: LearningCallScope): LearningReply<LearningPreferenceState> = lane.withLock {
        if (!current(scope.owner)) return@withLock LearningReply.Failed("SESSION_CHANGED", false)
        if (mutable.value.owner == scope.owner) mutable.value = mutable.value.copy(loading = true, failed = false)
        when (val reply = api.readLearningPreference(scope)) {
            is LearningReply.Failed -> {
                if (current(scope.owner)) mutable.value = projection(scope, failed = true)
                reply
            }
            is LearningReply.Stored -> {
                if (!current(scope.owner)) return@withLock LearningReply.Failed("SESSION_CHANGED", false)
                val old = cached(scope)
                acceptPreference(scope, reply.value)
                val changed = old != null && (old.preferenceVersion != reply.value.preferenceVersion || old.choice != reply.value.choice)
                val value = projection(scope, changed)
                mutable.value = value
                LearningReply.Stored(scope, value)
            }
        }
    }

    suspend fun flush(scope: LearningCallScope): LearningReply<Unit> = lane.withLock { flushLocked(scope) }
    private suspend fun flushLocked(scope: LearningCallScope): LearningReply<Unit> {
        if (!current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
        for (row in dao.pendingPreferences(scope.owner.profileId.toString())) {
            require(LearningJson.hash(row.payloadJson) == row.payloadHash)
            if (row.ownerRevision > scope.owner.revision) return LearningReply.Failed("OWNER_CHANGED", false)
            if (row.state == "pending" && !dao.transition(scope.owner, row.operationId, "pending", "sending", true)) continue
            val request = featureJson.decodeFromString(WireAiLearningLearningPreferenceRequest.serializer(), row.payloadJson)
            when (val reply = api.setLearningPreference(scope, request)) {
                is LearningReply.Failed -> {
                    if (!current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
                    val conflict = reply.code in setOf("VERSION_MISMATCH", "RECORD_CONFLICT", "IDEMPOTENCY_CONFLICT")
                    dao.transition(scope.owner, row.operationId, "sending", if (conflict) "conflict" else "pending", true)
                    // Read the actual server baseline for a later explicit choice. This never
                    // rewrites/retries the conflicted original command under fresh CAS values.
                    if (conflict) when (val read = api.readLearningPreference(scope)) {
                        is LearningReply.Stored -> if (current(scope.owner)) acceptPreference(scope, read.value)
                        is LearningReply.Failed -> Unit
                    }
                    if (current(scope.owner)) mutable.value = projection(scope, failed = !conflict)
                    return reply
                }
                is LearningReply.Stored -> {
                    if (!current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
                    require(reply.value.operationId == request.operationId)
                    acceptPreference(scope, reply.value.preference)
                    dao.transition(scope.owner, row.operationId, "sending", "acknowledged", true)
                }
            }
        }
        if (!current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
        val value = projection(scope)
        mutable.value = value
        if (value.conflict) return LearningReply.Failed("PREFERENCE_CONFLICT", false)
        return LearningReply.Stored(scope, Unit)
    }

    /** Fresh article only. Current format changes inherit their source sidecar instead. */
    suspend fun prepareNextSelection(scope: LearningCallScope): LearningReply<NextSelectionPreference> = lane.withLock {
        val flushed = flushLocked(scope)
        if (flushed is LearningReply.Failed) return@withLock flushed
        val before = cached(scope)
        when (val reply = api.readLearningPreference(scope)) {
            is LearningReply.Failed -> reply
            is LearningReply.Stored -> {
                if (!current(scope.owner)) return@withLock LearningReply.Failed("SESSION_CHANGED", false)
                acceptPreference(scope, reply.value)
                val changed = before != null && (before.preferenceVersion != reply.value.preferenceVersion || before.choice != reply.value.choice)
                val value = projection(scope, changed)
                mutable.value = value
                if (value.pending || value.conflict) LearningReply.Failed("PREFERENCE_SYNC_REQUIRED", false)
                else LearningReply.Stored(scope, NextSelectionPreference(reply.value.effectiveBand.band(), reply.value.preferenceVersion,
                    reply.value.choice?.choiceId?.let(UUID::fromString), changed))
            }
        }
    }

    /** Server action receipts can update this setting without a second manual-choice operation. */
    internal suspend fun acceptPreference(scope: LearningCallScope, value: WireAiLearningLearningPreferenceView) {
        validateLearningPreference(scope.owner, value)
        dao.savePreference(scope.owner, LocalAiLearningPreference(value.profileId, value.preferenceVersion,
            featureJson.encodeToString(WireAiLearningLearningPreferenceView.serializer(), value)))
    }
    internal suspend fun projectAccepted(scope: LearningCallScope) {
        if (current(scope.owner)) mutable.value = projection(scope)
    }
}

internal fun String.band() = LearningBand.valueOf(uppercase(Locale.ROOT))
internal fun LearningBand.wireBand() = name.lowercase(Locale.ROOT)
internal fun validateLearningPreference(owner: OwnerContext, value: WireAiLearningLearningPreferenceView) {
    require(value.profileId == owner.profileId.toString() && value.appliesFrom == "next_selection")
    if (value.preferenceVersion == 0L) require(value.choice == null && value.effectiveBand == "basic")
    else require(value.preferenceVersion > 0 && value.choice?.explicit == true && value.choice.band == value.effectiveBand)
    value.effectiveBand.band()
}

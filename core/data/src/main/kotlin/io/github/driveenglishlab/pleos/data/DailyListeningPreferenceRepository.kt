package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Exact original choice time evidence is optional; current sync time never substitutes it. */
fun interface OriginalChoiceClock { fun boundary(choiceId: UUID): DailyListeningBoundary }
data class DailyListeningState(val choice: WireProgressDailyChoice? = null, val local: Boolean = false,
    val pending: Boolean = false, val conflict: Boolean = false, val timeUnresolved: Boolean = false) {
    fun listenOnly(now: Instant?): Boolean {
        if (choice?.decision != "listen_only") return false
        val boundary = choice.boundary
        return boundary !is WireProgressConfirmedDailyBoundary || now == null || now < Instant.parse(boundary.expiresAtUtc)
    }
}

class DailyListeningPreferenceRepository(private val api: LearningProgressApi, private val database: LearningDatabase,
    private val clock: OriginalChoiceClock = OriginalChoiceClock { DailyListeningBoundary.Unresolved(runCatching { ZoneId.systemDefault() }.getOrNull()) }) {
    private val lane = Mutex()
    private var clockAnchor: Pair<Instant, Long>? = null
    fun trustedNow(): Instant? = clockAnchor?.let { (instant, anchor) ->
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < anchor) null else instant.plusMillis(now - anchor)
    }
    private suspend fun saveView(scope: LearningCallScope, value: WireProgressDailyPreferenceView) {
        val old = dao.preference(scope.owner.profileId.toString())
        if (old?.version == value.preferenceVersion) {
            val previous = featureJson.decodeFromString(WireProgressDailyPreferenceView.serializer(), old.payloadJson)
            require(previous.choice == value.choice && previous.profileContext == value.profileContext)
        } else dao.savePreference(scope.owner, LocalProgressPreference(scope.owner.profileId.toString(), value.preferenceVersion,
            featureJson.encodeToString(WireProgressDailyPreferenceView.serializer(), value)))
        clockAnchor = Instant.parse(value.serverClock.observedAtUtc) to android.os.SystemClock.elapsedRealtime()
    }
    private val dao get() = database.progress()
    suspend fun state(scope: LearningCallScope): DailyListeningState {
        val row = dao.latestPreference(scope.owner.profileId.toString())
        val cache = dao.preference(scope.owner.profileId.toString())?.let { featureJson.decodeFromString(WireProgressDailyPreferenceView.serializer(), it.payloadJson) }
        val local = row?.takeIf { !it.isolated && it.state != "acknowledged" }
        val choice = local?.let { featureJson.decodeFromString(WireProgressDailyPreferenceRequest.serializer(), it.payloadJson).choice } ?: cache?.choice
        return DailyListeningState(choice, local != null, local?.state in setOf("pending", "sending"), local?.state == "conflict", choice?.boundary is WireProgressUnresolvedDailyBoundary)
    }
    suspend fun choose(scope: LearningCallScope, listenOnly: Boolean): DailyListeningState = lane.withLock {
        val previous = state(scope)
        val last = dao.latestPreference(scope.owner.profileId.toString())
        val cache = dao.preference(scope.owner.profileId.toString())?.let { featureJson.decodeFromString(WireProgressDailyPreferenceView.serializer(), it.payloadJson) }
        val choiceId = UUID.randomUUID()
        val boundary = if (!listenOnly && previous.choice != null) previous.choice.boundary else clock.boundary(choiceId).wireBoundary()
        val choice = WireProgressDailyChoice(choiceId.toString(), if (listenOnly) "listen_only" else "explicit_default", boundary)
        val request = WireProgressDailyPreferenceRequest("5", "1", UUID.randomUUID().toString(), WireProgressProfileSessionContext(scope.owner.profileId.toString()),
            cache?.preferenceVersion ?: 0, cache?.choice?.choiceId, maxOf(last?.localSequence ?: 0, dao.lastInstallationSequence()) + 1, choice)
        val payload = featureJson.encodeToString(WireProgressDailyPreferenceRequest.serializer(), request)
        dao.queuePreference(scope.owner, LocalProgressPreferenceJournal(request.operationId, scope.owner.profileId.toString(), scope.owner.revision,
            request.localSequence, featureJson.encodeToString(WireProgressDailyChoice.serializer(), choice), payload, LearningJson.hash(payload), "pending", false))
        state(scope)
    }
    suspend fun refresh(scope: LearningCallScope): LearningReply<DailyListeningState> = when(val reply = api.readDailyListeningPreference(scope)) {
        is LearningReply.Failed -> reply
        is LearningReply.Stored -> {
            saveView(scope, reply.value)
            LearningReply.Stored(scope, state(scope))
        }
    }
    suspend fun flush(scope: LearningCallScope): LearningReply<Unit> = lane.withLock {
        while (true) {
            val rows = dao.pendingPreferences(scope.owner.profileId.toString())
            if (rows.isEmpty()) return@withLock LearningReply.Stored(scope, Unit)
            for (row in rows) {
                require(LearningJson.hash(row.payloadJson) == row.payloadHash)
                if (row.state == "pending" && !dao.transition(scope.owner, row.operationId, "pending", "sending", true)) continue
                val request = featureJson.decodeFromString(WireProgressDailyPreferenceRequest.serializer(), row.payloadJson)
                when(val reply = api.setDailyListeningPreference(scope, request)) {
                    is LearningReply.Failed -> {
                        dao.transition(scope.owner, row.operationId, "sending", if (reply.code in setOf("VERSION_MISMATCH", "RECORD_CONFLICT")) "conflict" else "pending", true)
                        return@withLock reply
                    }
                    is LearningReply.Stored -> {
                        val view = WireProgressDailyPreferenceView("5", "1", reply.value.profileContext, reply.value.preferenceVersion, reply.value.acceptedChoice, reply.value.serverClock)
                        saveView(scope, view)
                        dao.transition(scope.owner, row.operationId, "sending", "acknowledged", true)
                    }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE") LearningReply.Failed("SYNC_NOT_READY", true)
    }
}

private fun DailyListeningBoundary.wireBoundary(): WireProgressDailyBoundary = when(this) {
    is DailyListeningBoundary.Confirmed -> WireProgressConfirmedDailyBoundary("confirmed", selectedLocalDate.toString(), selectedZone.id,
        selectedAt.toString(), expiresAt.toString(), clockEvidenceRef)
    is DailyListeningBoundary.Unresolved -> WireProgressUnresolvedDailyBoundary("unresolved", JsonNull, selectedZone?.id, JsonNull, JsonNull, JsonNull)
}

package io.github.driveenglishlab.pleos.data

import androidx.room.withTransaction
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All displayed evaluation times are the stored original provider result times. */
data class LearningRecommendationState(val owner: OwnerContext? = null,
    val view: WireAiLearningLearningRecommendationView? = null, val loading: Boolean = false,
    val failed: Boolean = false, val pendingAction: Boolean = false, val conflict: Boolean = false,
    val failureCode: String? = null) {
    val status get() = view?.status?.let { RecommendationStatus.valueOf(it.uppercase(Locale.ROOT)) }
    val maySuggest get() = !loading && !failed && !pendingAction && !conflict && status == RecommendationStatus.READY
    val evaluatedAt get() = view?.recommendation?.evaluatedAt
    /** kept/dismissed remain quiet unless the user explicitly reopens review. */
    fun mayAct(explicitReview: Boolean) = !loading && !failed && !pendingAction && !conflict &&
        (status == RecommendationStatus.READY || explicitReview && status in setOf(RecommendationStatus.KEPT, RecommendationStatus.DISMISSED))
}

class LearningRecommendationRepository(private val api: AiLearningApi, private val database: LearningDatabase,
    private val preferences: LearningPreferenceRepository, private val current: suspend (OwnerContext) -> Boolean) {
    private val lane = Mutex()
    private val mutable = MutableStateFlow(LearningRecommendationState())
    val state = mutable.asStateFlow()
    private val dao get() = database.aiLearning()

    suspend fun clear() { mutable.value = LearningRecommendationState() }
    suspend fun bind(scope: LearningCallScope) = lane.withLock {
        if (!current(scope.owner)) return@withLock
        val saved = dao.cache(scope.owner.profileId.toString(), scope.owner.privacyEpoch, "recommendation", "current")
        val view = saved?.let { featureJson.decodeFromString(WireAiLearningLearningRecommendationView.serializer(), it.payloadJson) }
        view?.let { validateRecommendation(scope.owner, it) }
        // A restored cached ready state is not a fresh action grant. A current read reopens it.
        mutable.value = LearningRecommendationState(owner = scope.owner, view = view, failed = view != null, failureCode = if (view != null) "CURRENT_READ_REQUIRED" else null)
        projectPending(scope)
    }

    private suspend fun cached(scope: LearningCallScope): WireAiLearningLearningRecommendationView? =
        dao.cache(scope.owner.profileId.toString(), scope.owner.privacyEpoch, "recommendation", "current")?.let {
            featureJson.decodeFromString(WireAiLearningLearningRecommendationView.serializer(), it.payloadJson)
        }

    private fun advances(previous: WireAiLearningLearningRecommendationView?, value: WireAiLearningLearningRecommendationView): Boolean {
        if (previous == null) return true
        if (value.recommendationRevision < previous.recommendationRevision) return false
        val old = previous.recommendation; val next = value.recommendation
        if (old != null && next != null) {
            if (old.recommendationId == next.recommendationId && next.version < old.version) return false
            if (old.basisSignature == next.basisSignature && previous.status in setOf("kept", "dismissed", "applied") && value.status == "ready") return false
            if (old.recommendationId == next.recommendationId && old.evaluatedAt != null && next.evaluatedAt != null && old.evaluatedAt != next.evaluatedAt) return false
        }
        // Same generation cannot replace a committed result with an earlier in-flight read.
        if (value.recommendationRevision == previous.recommendationRevision && old?.evaluatedAt != null &&
            next == null && value.status == "pending") return false
        return true
    }

    private suspend fun accept(scope: LearningCallScope, value: WireAiLearningLearningRecommendationView): Boolean {
        validateRecommendation(scope.owner, value)
        if (!advances(cached(scope), value)) return false
        dao.saveRecommendationProjection(scope.owner, LocalAiLearningCache(scope.owner.profileId.toString(), scope.owner.privacyEpoch,
            "recommendation", "current", value.recommendationRevision,
            featureJson.encodeToString(WireAiLearningLearningRecommendationView.serializer(), value)))
        return true
    }

    suspend fun refresh(scope: LearningCallScope): LearningReply<LearningRecommendationState> = lane.withLock {
        if (!current(scope.owner)) return@withLock LearningReply.Failed("SESSION_CHANGED", false)
        if (mutable.value.owner != scope.owner) mutable.value = LearningRecommendationState(owner = scope.owner)
        mutable.value = mutable.value.copy(loading = true, failed = false, failureCode = null)
        when (val reply = api.readLearningRecommendation(scope, scope.owner.privacyEpoch)) {
            is LearningReply.Failed -> {
                if (current(scope.owner)) mutable.value = mutable.value.copy(loading = false, failed = true, failureCode = reply.code)
                reply
            }
            is LearningReply.Stored -> {
                if (!current(scope.owner)) return@withLock LearningReply.Failed("SESSION_CHANGED", false)
                if (!accept(scope, reply.value)) {
                    mutable.value = mutable.value.copy(loading = false, failed = true, failureCode = "STALE_RESPONSE")
                    return@withLock LearningReply.Failed("STALE_RESPONSE", true)
                }
                mutable.value = mutable.value.copy(view = reply.value, loading = false, failed = false, failureCode = null)
                projectPending(scope)
                LearningReply.Stored(scope, mutable.value)
            }
        }
    }

    /** Only an explicit user action freezes these original CAS values. A failed action never
     * changes ID/basis/preference to whichever recommendation becomes current later.
     */
    suspend fun act(scope: LearningCallScope, action: RecommendationAction, explicitReview: Boolean = false): LearningReply<LearningRecommendationState> = lane.withLock {
        if (!current(scope.owner) || mutable.value.owner != scope.owner) return@withLock LearningReply.Failed("SESSION_CHANGED", false)
        if (!mutable.value.mayAct(explicitReview)) return@withLock LearningReply.Failed("RECOMMENDATION_NOT_ACTIONABLE", false)
        if (dao.unsettledPreferences(scope.owner.profileId.toString()).isNotEmpty()) return@withLock LearningReply.Failed("PREFERENCE_SYNC_REQUIRED", false)
        val view = requireNotNull(mutable.value.view); val recommendation = requireNotNull(view.recommendation)
        val pref = dao.preference(scope.owner.profileId.toString())?.let { featureJson.decodeFromString(WireAiLearningLearningPreferenceView.serializer(), it.payloadJson) }
            ?: return@withLock LearningReply.Failed("PREFERENCE_READ_REQUIRED", false)
        validateLearningPreference(scope.owner, pref)
        val request = WireAiLearningLearningRecommendationActionRequest("5", "1", UUID.randomUUID().toString(), scope.owner.profileWire(),
            recommendation.recommendationId, recommendation.version, recommendation.evidenceRevision, recommendation.basisSignature,
            pref.preferenceVersion, pref.choice?.choiceId, action.name.lowercase(Locale.ROOT))
        val bytes = featureJson.encodeToString(WireAiLearningLearningRecommendationActionRequest.serializer(), request)
        dao.queue(scope.owner, LocalAiLearningJournal(request.operationId, scope.owner.profileId.toString(), scope.owner.privacyEpoch, scope.owner.revision,
            "recommendation_action", bytes, LearningJson.hash(bytes), "pending", false))
        projectPending(scope)
        when (val result = flushLocked(scope)) {
            is LearningReply.Failed -> result
            is LearningReply.Stored -> LearningReply.Stored(scope, mutable.value)
        }
    }

    suspend fun flush(scope: LearningCallScope): LearningReply<Unit> = lane.withLock { flushLocked(scope) }
    private suspend fun flushLocked(scope: LearningCallScope): LearningReply<Unit> {
        if (!current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
        for (row in dao.pending(scope.owner.profileId.toString(), scope.owner.privacyEpoch).filter { it.kind == "recommendation_action" }) {
            if (row.ownerRevision != scope.owner.revision || !current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
            require(LearningJson.hash(row.payloadJson) == row.payloadHash)
            if (row.state == "pending" && !dao.transition(scope.owner, row.operationId, "pending", "sending", false)) continue
            val request = featureJson.decodeFromString(WireAiLearningLearningRecommendationActionRequest.serializer(), row.payloadJson)
            when (val reply = api.actOnLearningRecommendation(scope, request)) {
                is LearningReply.Failed -> {
                    if (!current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
                    val conflict = reply.code in setOf("VERSION_MISMATCH", "RECORD_CONFLICT", "STALE_OPERATION", "IDEMPOTENCY_CONFLICT", "SNAPSHOT_CHANGED")
                    dao.transition(scope.owner, row.operationId, "sending", if (conflict) "conflict" else "pending", false)
                    projectPending(scope)
                    mutable.value = mutable.value.copy(failed = !conflict, failureCode = reply.code)
                    return reply
                }
                is LearningReply.Stored -> {
                    if (!current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
                    val receipt = reply.value
                    require(receipt.operationId == request.operationId)
                    validateLearningPreference(scope.owner, receipt.preference)
                    validateRecommendation(scope.owner, receipt.recommendation)
                    val accepted = receipt.recommendation.recommendation
                    require(accepted != null && accepted.recommendationId == request.recommendationId &&
                        accepted.evidenceRevision == request.expectedEvidenceRevision && accepted.basisSignature == request.expectedBasisSignature &&
                        accepted.version == request.expectedRecommendationVersion + 1 && receipt.recommendation.status == when (request.action) {
                            "apply" -> "applied"; "keep" -> "kept"; "dismiss" -> "dismissed"; else -> error("Unknown retained action")
                        })
                    if (request.action == "apply") require(receipt.preference.preferenceVersion == request.expectedPreferenceVersion + 1 &&
                        receipt.preference.choice?.band == accepted.recommendedBand && receipt.preference.choice?.explicit == true)
                    else require(receipt.preference.preferenceVersion == request.expectedPreferenceVersion && receipt.preference.choice?.choiceId == request.expectedChoiceId)
                    val replaces = advances(cached(scope), receipt.recommendation)
                    // The original receipt still acknowledges the original outbox even if a
                    // later current snapshot already replaced it. It must not replace that view.
                    val currentView = if (replaces) receipt.recommendation else requireNotNull(cached(scope))
                    dao.acknowledgeRecommendationAction(scope.owner, row.operationId,
                        LocalAiLearningCache(row.profileId, row.privacyEpoch, "recommendation", "current", currentView.recommendationRevision,
                            featureJson.encodeToString(WireAiLearningLearningRecommendationView.serializer(), currentView)),
                        LocalAiLearningCache(row.profileId, row.privacyEpoch, "recommendation_action_receipt", row.operationId, 1,
                            featureJson.encodeToString(WireAiLearningLearningRecommendationActionReceipt.serializer(), receipt)),
                        LocalAiLearningPreference(receipt.preference.profileId, receipt.preference.preferenceVersion,
                            featureJson.encodeToString(WireAiLearningLearningPreferenceView.serializer(), receipt.preference)))
                    preferences.projectAccepted(scope)
                    if (mutable.value.owner == scope.owner && replaces) mutable.value = mutable.value.copy(view = receipt.recommendation, loading = false, failed = false, failureCode = null)
                }
            }
        }
        if (!current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
        projectPending(scope)
        return if (mutable.value.conflict) LearningReply.Failed("RECOMMENDATION_CONFLICT", false) else LearningReply.Stored(scope, Unit)
    }

    private suspend fun projectPending(scope: LearningCallScope) {
        if (!current(scope.owner)) return
        val recommendation = mutable.value.view?.recommendation
        val preference = dao.preference(scope.owner.profileId.toString())?.let { featureJson.decodeFromString(WireAiLearningLearningPreferenceView.serializer(), it.payloadJson) }
        val rows = dao.unsettled(scope.owner.profileId.toString(), scope.owner.privacyEpoch).filter { row ->
            if (row.kind != "recommendation_action") false else {
                val original = featureJson.decodeFromString(WireAiLearningLearningRecommendationActionRequest.serializer(), row.payloadJson)
                recommendation != null && original.recommendationId == recommendation.recommendationId &&
                    original.expectedRecommendationVersion == recommendation.version && original.expectedEvidenceRevision == recommendation.evidenceRevision &&
                    original.expectedBasisSignature == recommendation.basisSignature && preference != null &&
                    original.expectedPreferenceVersion == preference.preferenceVersion && original.expectedChoiceId == preference.choice?.choiceId
            }
        }
        if (mutable.value.owner == scope.owner) mutable.value = mutable.value.copy(pendingAction = rows.any { it.state in setOf("pending", "sending") },
            conflict = rows.any { it.state == "conflict" })
    }
}

private fun validateRecommendation(owner: OwnerContext, view: WireAiLearningLearningRecommendationView) {
    require(view.profileContext == owner.profileWire() && view.windowLessonCount in 0..10 && view.eligibleFirstAnswerCount <= view.windowLessonCount &&
        view.distinctArticleCount <= view.windowLessonCount && view.evidence.size.toLong() == view.windowLessonCount)
    require(view.evidence.map { it.lessonId }.distinct().size == view.evidence.size)
    val eligible = view.evidence.filter { it.eligibility == "eligible" }
    require(eligible.size.toLong() == view.eligibleFirstAnswerCount)
    require(eligible.map { it.articleId }.distinct().size.toLong() == view.distinctArticleCount)
    eligible.forEach { require(it.firstAttemptId != null && it.lessonOrder != null && it.evidenceRef != null && it.taskBand != null && it.exclusionReasons.isEmpty()) }
    if (view.status == "insufficient_evidence") require(view.recommendation == null)
    if (view.status in setOf("ready", "kept", "dismissed", "applied")) {
        require(view.windowCoverage == "complete" && view.eligibleFirstAnswerCount >= 5 && view.distinctArticleCount >= 3 &&
            view.recommendation?.recommendedBand != null && view.recommendation.evaluatedAt != null)
    }
    view.recommendation?.let { require(if (view.status == "stale") it.evidenceRevision <= view.recommendationRevision else it.evidenceRevision == view.recommendationRevision) }
}

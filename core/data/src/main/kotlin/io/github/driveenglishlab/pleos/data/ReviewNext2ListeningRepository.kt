package io.github.driveenglishlab.pleos.data

import androidx.room.withTransaction
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** Joins existing canonical source6/stream storage, never creates a parallel selection engine. */
fun interface ReviewNext2StatePort {
    suspend fun accept(scope: LearningCallScope, request: WireReview2ReviewNextRequest2, response: WireReview2ReviewNextResponse2): Boolean
}
data class ReviewNext2State(val owner: OwnerContext? = null, val intent: PlaybackRequestIntent? = null,
    val response: WireReview2ReviewNextResponse2? = null, val loading: Boolean = false, val errorCode: String? = null,
    val businessStateCurrent: Boolean = false, val requiresExplicitResume: Boolean = true)
data class ReviewNext2Activation(val intent: PlaybackRequestIntent, val response: WireReview2ReviewNextResponse2)

/** Durable original input/admission + versioned outcome. No output/navigation API is owned here. */
class ReviewNext2ListeningRepository(private val api: IncrementalLearningApi, private val codecs: IncrementalContractJson,
    private val database: LearningDatabase, private val dao: ServiceIntentDao, private val reviewDao: ReviewDao,
    private val sharedState: ReviewNext2StatePort, private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val utcNow: () -> Instant = Instant::now, val processToken: UUID = UUID.randomUUID()) {
    private val lane = Mutex()
    @Volatile private var visibleOperation: UUID? = null
    private val mutable = MutableStateFlow(ReviewNext2State())
    val state = mutable.asStateFlow()
    private val businessVersions = ConcurrentHashMap<String, Long>()

    fun newBinding(owner: OwnerContext, operationId: UUID, contextToken: UUID, kind: PlaybackRequestKind,
        channel: PlaybackRequestChannel, requestedArticleId: UUID?, requestedSourceSnapshotId: UUID?, listeningMode: ListeningMode,
        returnToken: CatalogViewReturnToken? = null): PlaybackRequestBinding {
        val began = elapsedMs()
        return PlaybackRequestBinding(owner, operationId, contextToken, processToken, kind, channel, requestedArticleId, requestedSourceSnapshotId,
            listeningMode, began, Math.addExact(began, channel.budgetMs), returnToken)
    }
    fun clear() { visibleOperation = null; businessVersions.clear(); mutable.value = ReviewNext2State() }

    suspend fun submit(scope: LearningCallScope, request: WireReview2ReviewNextRequest2, binding: PlaybackRequestBinding,
        guard: PlaybackActivationGuard, maxPolls: Int = 5): LearningReply<ReviewNext2State> = lane.withLock {
        if (!api.isCurrent(scope) || binding.owner != scope.owner) return@withLock failedSession()
        return@withLock safely(scope) {
            validateRequest(scope, request, binding)
            val payload = codecs.encode("review2", "ReviewNextRequest2", request, WireReview2ReviewNextRequest2.serializer())
            val canonical = incrementalCanonical(codecs.parse("review2", payload))
            val hash = LearningJson.hash(canonical)
            val local = LocalPlaybackRequest(request.operationId, scope.owner.profileId.toString(), scope.owner.privacyEpoch, scope.owner.revision,
                binding.contextToken.toString(), binding.processToken.toString(), encodePlaybackBinding(binding, request.streamId), hash, "active", 0, null, null, 0, null, false)
            val registered = database.withTransaction {
                reviewDao.queue(scope.owner, LocalReviewOperation(request.operationId, local.profileId, local.privacyEpoch, local.ownerRevision,
                    "review2_next", request.streamId, canonical, hash))
                dao.register(scope.owner, local)
            }
            visibleOperation = binding.operationId
            exchange(scope, request, registered, guard, maxPolls)
        }
    }

    /** Observation is exactly the original POST bytes, never a new operation/job/deadline. */
    suspend fun observe(scope: LearningCallScope, operationId: UUID, guard: PlaybackActivationGuard,
        maxPolls: Int = 5): LearningReply<ReviewNext2State> = lane.withLock {
        if (!api.isCurrent(scope)) return@withLock failedSession()
        safely(scope) {
            val row = requireOwned(scope, operationId)
            val operation = requireNotNull(reviewDao.operation(operationId.toString()))
            require(operation.kind == "review2_next" && operation.payloadHash == row.requestHash && operation.status != "isolated")
            val request = codecs.decode("review2", "ReviewNextRequest2", operation.payloadJson, WireReview2ReviewNextRequest2.serializer())
            exchange(scope, request, row, guard, maxPolls)
        }
    }
    suspend fun restore(scope: LearningCallScope, operationId: UUID): LearningReply<ReviewNext2State> = lane.withLock {
        if (!api.isCurrent(scope)) return@withLock failedSession()
        safely(scope) {
            val row = requireOwned(scope, operationId)
            val old = intent(row)
            val restored = PlaybackRequestPolicy.restored(old)
            val next = row.copy(state = restored.state.name.lowercase())
            if (next != row) require(dao.advance(scope.owner, row, next))
            visibleOperation = operationId
            val value = readState(scope, next, error = "EXPLICIT_RESUME_REQUIRED")
            publish(scope, value)
            LearningReply.Stored(scope, value)
        }
    }
    /** Cancellation has its own DB transaction, never waits behind a pending HTTP read. */
    suspend fun cancel(scope: LearningCallScope, operationId: UUID): Boolean {
        if (!api.isCurrent(scope)) return false
        val next = database.withTransaction {
            val old = requireOwned(scope, operationId)
            dao.stopPendingChoice(scope.owner,old)
            val cancelled = PlaybackRequestPolicy.cancel(intent(old))
            val changed = old.copy(state = cancelled.state.name.lowercase())
            if (changed != old && !dao.advance(scope.owner, old, changed)) return@withTransaction null
            changed
        } ?: return false
        publish(scope, readState(scope, next, error = "EXPLICIT_RESUME_REQUIRED"))
        return true
    }

    /** Call with fresh owner/context/process/time/permission, then perform product output outside. */
    suspend fun claimActivation(scope: LearningCallScope, operationId: UUID, guard: PlaybackActivationGuard,
        claimId: UUID = UUID.randomUUID()): ReviewNext2Activation? = lane.withLock {
        if (!api.isCurrent(scope)) return@withLock null
        val old = requireOwned(scope, operationId)
        val response = response(old) ?: return@withLock null
        if (businessVersions[old.operationId] != old.outcomeVersion) return@withLock null
        val original = intent(old)
        val decision = PlaybackRequestPolicy.claim(original, freshGuard(guard), claimId)
        val changed = when (decision) { is PlaybackActivationDecision.Claimed -> decision.intent; is PlaybackActivationDecision.StateOnly -> decision.intent }
        val next = old.copy(state = changed.state.name.lowercase(), activationClaimId = changed.activationClaimId?.toString())
        if (next != old && !dao.advance(scope.owner, old, next)) return@withLock null
        publish(scope, readState(scope, next))
        if (decision is PlaybackActivationDecision.Claimed) ReviewNext2Activation(changed, response) else null
    }

    private suspend fun exchange(scope: LearningCallScope, request: WireReview2ReviewNextRequest2, original: LocalPlaybackRequest,
        suppliedGuard: PlaybackActivationGuard, maxPolls: Int): LearningReply<ReviewNext2State> {
        var row = original
        var current = intent(row)
        val guard = freshGuard(suppliedGuard)
        if (current.binding.processToken != processToken) {
            current = PlaybackRequestPolicy.restored(current)
            val next = row.copy(state = current.state.name.lowercase())
            if (next != row) require(dao.advance(scope.owner, row, next))
            row = next
        }
        if (current.state != PlaybackRequestState.ACTIVE || current.outcome != null && current.outcome != PlaybackRequestOutcome.PENDING) {
            val value = readState(scope, row)
            publish(scope, value)
            return LearningReply.Stored(scope, value)
        }
        val reserved = PlaybackRequestPolicy.reservePoll(current, guard, maxPolls)
        if (reserved == null) {
            val expired = row.copy(state = PlaybackRequestPolicy.expire(current).state.name.lowercase())
            if (expired != row) require(dao.advance(scope.owner, row, expired))
            val value = readState(scope, expired, error = "ORIGINAL_INPUT_WINDOW_ENDED")
            publish(scope, value)
            return LearningReply.Stored(scope, value)
        }
        val polling = row.copy(polls = reserved.polls)
        require(dao.advance(scope.owner, row, polling)); row = polling
        publish(scope, readState(scope, row, loading = true))
        val remaining = PlaybackRequestPolicy.remainingMs(reserved, guard)
        val response: LearningReply<ReviewNext2State> = withTimeoutOrNull(remaining) {
            val operation = requireNotNull(reviewDao.operation(row.operationId))
            val reply = api.call("review2", "/v2/listening/next", "post", scope, codecs.parse("review2", operation.payloadJson), WireReview2ReviewNextResponse2.serializer())
            when (reply) {
                is LearningReply.Stored -> accept(scope, request, row, reply.value, suppliedGuard)
                is LearningReply.Failed -> reply
            }
        } ?: LearningReply.Failed("DEADLINE_EXCEEDED", true)
        if (response is LearningReply.Failed && api.isCurrent(scope)) {
            val latest = requireOwned(scope, UUID.fromString(row.operationId))
            val now = freshGuard(suppliedGuard)
            val expired = if (PlaybackRequestPolicy.remainingMs(intent(latest), now) <= 0) latest.copy(state = PlaybackRequestPolicy.expire(intent(latest)).state.name.lowercase()) else latest
            if (expired != latest) require(dao.advance(scope.owner, latest, expired))
            publish(scope, readState(scope, expired, error = response.code))
        }
        return response
    }

    private suspend fun accept(scope: LearningCallScope, request: WireReview2ReviewNextRequest2, sent: LocalPlaybackRequest,
        value: WireReview2ReviewNextResponse2, guard: PlaybackActivationGuard): LearningReply<ReviewNext2State> {
        if (!api.isCurrent(scope)) return failedSession()
        validateResponse(scope, request, sent, value)
        // Re-read local cancellation inside the same DB transaction as the receipt/outcome.
        // A network reply cannot race a stop into resurrecting ACTIVE or changing its deadline.
        val previous = database.withTransaction {
            val local = requireOwned(scope, UUID.fromString(sent.operationId))
            val before = response(local)
            if (before != null) {
                val first = codecs.parse("review2", codecs.encode("review2", "ReviewNextResponse2", before, WireReview2ReviewNextResponse2.serializer())).jsonObject
                val incoming = codecs.parse("review2", codecs.encode("review2", "ReviewNextResponse2", value, WireReview2ReviewNextResponse2.serializer())).jsonObject
                if (!codecs.validateReviewNextTransition(first, incoming) || value.outcomeProjection.version == before.outcomeProjection.version &&
                    Instant.parse(value.outcomeProjection.observedAt) < Instant.parse(before.outcomeProjection.observedAt)) return@withTransaction local
            }
            val admission = codecs.encode("review2", "NextAdmissionReceipt2", value.admissionReceipt, WireReview2NextAdmissionReceipt2.serializer())
            val outcome = codecs.encode("review2", "NextOutcome2", value.outcomeProjection, WireReview2NextOutcome2.serializer())
            var updated = PlaybackRequestPolicy.admit(intent(local), value.admissionReceipt.domain())
            updated = PlaybackRequestPolicy.observe(updated, value.outcomeProjection.version, PlaybackRequestOutcome.valueOf(value.outcomeProjection.state.uppercase()))
            if (PlaybackRequestPolicy.remainingMs(updated, freshGuard(guard)) <= 0) updated = PlaybackRequestPolicy.expire(updated)
            val changed = local.copy(state = updated.state.name.lowercase(), admissionJson = admission, outcomeJson = outcome, outcomeVersion = updated.outcomeVersion)
            val operation = requireNotNull(reviewDao.operation(local.operationId))
            reviewDao.acknowledge(scope.owner, operation, admission)
            require(dao.advance(scope.owner, local, changed))
            changed
        }
        val acceptedResponse = response(previous) ?: value
        var business = false
        if (acceptedResponse.outcomeProjection.state in setOf("selected", "resumed") && api.isCurrent(scope)) {
            business = sharedState.accept(scope, request, acceptedResponse)
            if (business && api.isCurrent(scope)) businessVersions[previous.operationId] = previous.outcomeVersion
        }
        if (!api.isCurrent(scope)) return failedSession()
        val projected = readState(scope, previous, business = business)
        publish(scope, projected)
        return LearningReply.Stored(scope, projected)
    }

    private fun validateRequest(scope: LearningCallScope, request: WireReview2ReviewNextRequest2, binding: PlaybackRequestBinding) {
        require(request.operationId == binding.operationId.toString() && request.schemaVersion == "6" && request.reviewContractVersion == 2L &&
            request.profileContext.profileId == scope.owner.profileId.toString() && request.profileContext.privacyEpoch == scope.owner.privacyEpoch &&
            request.intentKind == binding.kind.name.lowercase() && request.requestChannel == binding.channel.name.lowercase() &&
            request.requestedArticleId == binding.requestedArticleId?.toString() && request.requestedSourceSnapshotId == binding.requestedSourceSnapshotId?.toString())
        if (request.originView == "issues") require(binding.kind == PlaybackRequestKind.EXPLICIT_ARTICLE ||
            binding.kind == PlaybackRequestKind.EXPLICIT_START && request.selectionKind == "explicit_article" && binding.requestedArticleId != null && binding.requestedSourceSnapshotId != null)
    }
    private fun validateResponse(scope: LearningCallScope, request: WireReview2ReviewNextRequest2, row: LocalPlaybackRequest, value: WireReview2ReviewNextResponse2) {
        codecs.encode("review2", "ReviewNextResponse2", value, WireReview2ReviewNextResponse2.serializer())
        require(value.operationId == request.operationId && value.streamId == request.streamId && value.profileContext == request.profileContext &&
            value.admissionReceipt.operationId == value.operationId && value.admissionReceipt.requestHash == row.requestHash && value.admissionReceipt.intentKind == request.intentKind)
        val outcome = value.outcomeProjection
        require(Instant.parse(outcome.observedAt) >= Instant.parse(value.admissionReceipt.acceptedAt))
        if (outcome.state in setOf("selected", "resumed")) {
            val selected = requireNotNull(outcome.selection); val checkpoint = requireNotNull(outcome.checkpoint); val context = requireNotNull(outcome.context)
            require(checkpoint.recordId == checkpoint.payload.checkpointId && checkpoint.payload.selectionId == selected.selectionId && checkpoint.payload.contentRef == selected.contentRef && checkpoint.payload.streamId == selected.streamId &&
                context.selectionId == selected.selectionId && context.profileId == scope.owner.profileId.toString() && context.learningEpoch == scope.owner.privacyEpoch &&
                context.autoProgressRef?.autoProgressId == request.streamId && context.autoProgressRef?.version == outcome.streamVersion)
            if (outcome.state == "selected") require(selected.streamId == request.streamId && checkpoint.recordId == request.checkpointId && checkpoint.version == 1L)
            // Request previous_selection_id is the logical auto-progress head CAS. New selection
            // previous_selection_id remains its native same-stream FK after cross-stream resume.
            // Resumed legacy-native selections likewise retain original stream/reference identities.
            request.requestedArticleId?.let { require(article(selected.contentRef) == it) }
            request.requestedSourceSnapshotId?.let { require(snapshot(selected.contentRef) == it) }
            val mode = when (val ref = selected.contentRef) {
                is WireReview2Source6LegacyContentRef6 -> ref.legacyRef.listeningMode
                is WireReview2Source6PreparedContentRef6 -> ref.listeningMode
            }
            if (outcome.state == "selected") require(selected.settingsSnapshot.listeningMode == mode && mode == intent(row).binding.listeningMode.name.lowercase())
            require(outcome.sourceStatus.any { it.kind == (if (mode == "original_full") "main_original" else "main_briefing") && it.status == "ready" })
        }
    }
    private suspend fun requireOwned(scope: LearningCallScope, id: UUID): LocalPlaybackRequest {
        val row = requireNotNull(dao.intent(id.toString()))
        require(!row.isolated && row.profileId == scope.owner.profileId.toString() && row.privacyEpoch == scope.owner.privacyEpoch && row.ownerRevision == scope.owner.revision)
        return row
    }
    private fun intent(row: LocalPlaybackRequest): PlaybackRequestIntent {
        val binding = decodeBinding(row.bindingJson)
        val admission = row.admissionJson?.let { codecs.decode("review2", "NextAdmissionReceipt2", it, WireReview2NextAdmissionReceipt2.serializer()).domain() }
        val outcome = row.outcomeJson?.let { codecs.decode("review2", "NextOutcome2", it, WireReview2NextOutcome2.serializer()) }
        return PlaybackRequestIntent(binding, admission, PlaybackRequestState.valueOf(row.state.uppercase()), row.polls, row.outcomeVersion,
            outcome?.state?.let { PlaybackRequestOutcome.valueOf(it.uppercase()) }, row.activationClaimId?.let(UUID::fromString))
    }
    private fun response(row: LocalPlaybackRequest): WireReview2ReviewNextResponse2? {
        val admission = row.admissionJson ?: return null; val outcome = row.outcomeJson ?: return null
        val request = Json.parseToJsonElement(row.bindingJson).jsonObject
        return WireReview2ReviewNextResponse2("6", 2, row.operationId, WireReview2Source6CommonProfileContext(row.profileId, row.privacyEpoch),
            request.getValue("stream_id").jsonPrimitive.content,
            codecs.decode("review2", "NextAdmissionReceipt2", admission, WireReview2NextAdmissionReceipt2.serializer()),
            codecs.decode("review2", "NextOutcome2", outcome, WireReview2NextOutcome2.serializer()))
    }
    private fun readState(scope: LearningCallScope, row: LocalPlaybackRequest, loading: Boolean = false, error: String? = null,
        business: Boolean = businessVersions[row.operationId] == row.outcomeVersion): ReviewNext2State {
        val intent = intent(row)
        return ReviewNext2State(scope.owner, intent, response(row), loading, error, business,
            intent.state != PlaybackRequestState.ACTIVE || !business || intent.outcome !in setOf(PlaybackRequestOutcome.SELECTED, PlaybackRequestOutcome.RESUMED))
    }
    private suspend fun publish(scope: LearningCallScope, value: ReviewNext2State) {
        if (api.isCurrent(scope) && value.intent?.binding?.operationId == visibleOperation) mutable.value = value
    }
    private fun freshGuard(value: PlaybackActivationGuard) = value.copy(elapsedMs = elapsedMs(), utcNow = if (value.utcNow == null) null else utcNow())
    private fun WireReview2NextAdmissionReceipt2.domain() = PlaybackAdmission(UUID.fromString(operationId), requestHash, Instant.parse(acceptedAt),
        PlaybackRequestKind.valueOf(intentKind.uppercase()), selectionIntentId?.let(UUID::fromString), activationDeadlineAt?.let(Instant::parse))
    private fun article(ref: WireReview2Source6ContentRef6) = when (ref) { is WireReview2Source6LegacyContentRef6 -> ref.legacyRef.articleId; is WireReview2Source6PreparedContentRef6 -> ref.articleId }
    private fun snapshot(ref: WireReview2Source6ContentRef6) = when (ref) { is WireReview2Source6LegacyContentRef6 -> ref.legacyRef.sourceSnapshotId; is WireReview2Source6PreparedContentRef6 -> ref.sourceSnapshotId }
    private suspend fun safely(scope: LearningCallScope, work: suspend () -> LearningReply<ReviewNext2State>): LearningReply<ReviewNext2State> = try { work() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { failure(scope, "INVALID_REVIEW2_CONTRACT", false) }
        catch (_: LearningProtocolException) { failure(scope, "INVALID_REVIEW2_CONTRACT", false) }
        catch (_: Exception) { if (!api.isCurrent(scope)) failedSession() else failure(scope, "REVIEW2_UNAVAILABLE", true) }
    private suspend fun failure(scope: LearningCallScope, code: String, retryable: Boolean): LearningReply.Failed {
        val id = visibleOperation
        if (id != null && api.isCurrent(scope)) {
            val row = dao.intent(id.toString())
            if (row != null && !row.isolated && row.profileId == scope.owner.profileId.toString() && row.privacyEpoch == scope.owner.privacyEpoch && row.ownerRevision == scope.owner.revision)
                try { publish(scope, readState(scope, row, error = code)) } catch (_: Exception) { }
        }
        return LearningReply.Failed(code, retryable)
    }
    private fun failedSession() = LearningReply.Failed("SESSION_CHANGED", false)

    private fun decodeBinding(source: String): PlaybackRequestBinding = decodePlaybackBinding(source)
}

/** Local metadata codec, deliberately distinct from every closed public request. */
internal fun encodePlaybackBinding(value: PlaybackRequestBinding, streamId: String): String = buildJsonObject {
    put("profile_id", value.owner.profileId.toString()); put("privacy_epoch", value.owner.privacyEpoch); put("owner_revision", value.owner.revision)
    put("presentation_token", value.owner.presentationToken.toString()); put("operation_id", value.operationId.toString()); put("context_token", value.contextToken.toString())
    put("process_token", value.processToken.toString()); put("kind", value.kind.name); put("channel", value.channel.name)
    put("article_id", value.requestedArticleId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
    put("source_snapshot_id", value.requestedSourceSnapshotId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
    put("mode", value.listeningMode.name); put("requested_elapsed_ms", value.requestedAtElapsedMs); put("deadline_elapsed_ms", value.localDeadlineElapsedMs)
    put("stream_id", streamId)
    value.pendingOriginalParentOperationId?.let{put("pending_original_parent_operation_id",it.toString())}
    value.pendingChoiceKind?.let{put("pending_choice_kind",it.name)}
    put("return_token", value.returnToken?.let { buildJsonObject {
        put("view", it.view.name); put("filter", it.categoryFilter?.name?.let(::JsonPrimitive) ?: JsonNull)
        put("snapshot_id", it.snapshotId?.toString()?.let(::JsonPrimitive) ?: JsonNull); put("snapshot_revision", it.snapshotRevision?.let(::JsonPrimitive) ?: JsonNull)
        put("anchor_article", it.anchorArticle?.articleId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("anchor_source", it.anchorArticle?.sourceSnapshotId?.toString()?.let(::JsonPrimitive) ?: JsonNull)
        put("navigation_generation", it.navigationGeneration); put("token", it.token.toString())
    } } ?: JsonNull)
}.toString()

internal fun decodePlaybackBinding(source: String): PlaybackRequestBinding {
    val json = Json.parseToJsonElement(source).jsonObject
    fun text(key: String) = json.getValue(key).jsonPrimitive.content
    fun nullable(key: String) = json.getValue(key).takeUnless { it === JsonNull }?.jsonPrimitive?.content
    val owner = OwnerContext(UUID.fromString(text("profile_id")), json.getValue("privacy_epoch").jsonPrimitive.long,
        json.getValue("owner_revision").jsonPrimitive.long, UUID.fromString(text("presentation_token")))
    val result = json.getValue("return_token").takeUnless { it === JsonNull }?.jsonObject?.let { value ->
        fun optional(key: String) = value.getValue(key).takeUnless { it === JsonNull }?.jsonPrimitive?.content
        val article = optional("anchor_article")
        CatalogViewReturnToken(owner, CatalogView.valueOf(value.getValue("view").jsonPrimitive.content), optional("filter")?.let(CatalogCategoryId::valueOf),
            optional("snapshot_id")?.let(UUID::fromString), optional("snapshot_revision")?.toLong(),
            article?.let { CatalogArticleKey(UUID.fromString(it), UUID.fromString(requireNotNull(optional("anchor_source")))) },
            value.getValue("navigation_generation").jsonPrimitive.long, UUID.fromString(value.getValue("token").jsonPrimitive.content))
    }
    return PlaybackRequestBinding(owner, UUID.fromString(text("operation_id")), UUID.fromString(text("context_token")), UUID.fromString(text("process_token")),
        PlaybackRequestKind.valueOf(text("kind")), PlaybackRequestChannel.valueOf(text("channel")), nullable("article_id")?.let(UUID::fromString),
        nullable("source_snapshot_id")?.let(UUID::fromString), ListeningMode.valueOf(text("mode")), json.getValue("requested_elapsed_ms").jsonPrimitive.long,
        json.getValue("deadline_elapsed_ms").jsonPrimitive.long, result,
        json["pending_original_parent_operation_id"]?.jsonPrimitive?.content?.let(UUID::fromString),
        json["pending_choice_kind"]?.jsonPrimitive?.content?.let(PendingChoiceKind::valueOf))
}

/** Same existing snapshot/cache writer, with no new stream/selection/resume mutation. */
class Source6ReviewNextStatePort(private val listening: Source6ListeningRepository, private val codecs: IncrementalContractJson) : ReviewNext2StatePort {
    override suspend fun accept(scope: LearningCallScope, request: WireReview2ReviewNextRequest2, response: WireReview2ReviewNextResponse2): Boolean {
        val selected = response.outcomeProjection.selection ?: return false
        val checkpoint = response.outcomeProjection.checkpoint ?: return false
        val originalSelection = codecs.decodeSource6("PersonalSelectionRecord", codecs.encode("review2", "Source6PersonalSelectionRecord", selected,
            WireReview2Source6PersonalSelectionRecord.serializer()), WireSource6PersonalSelectionRecord.serializer())
        val originalCheckpoint = codecs.decodeSource6("PersonalReadCheckpoint", codecs.encode("review2", "Source6PersonalReadCheckpoint", checkpoint,
            WireReview2Source6PersonalReadCheckpoint.serializer()), WireSource6PersonalReadCheckpoint.serializer())
        return acceptSource6(scope,request.expectedResumeVersion,originalSelection,originalCheckpoint)
    }
    suspend fun acceptSource6(scope:LearningCallScope,expectedResumeVersion:Long,originalSelection:WireSource6PersonalSelectionRecord,
        originalCheckpoint:WireSource6PersonalReadCheckpoint):Boolean {
        val read = listening.snapshot(scope) as? LearningReply.Stored ?: return false
        val profile = read.value.profile
        if (profile.activeResume?.kind != "listening" || profile.activeResume?.recordId != originalCheckpoint.recordId || profile.resumeVersion < expectedResumeVersion) return false
        val selection = read.value.records.filterIsInstance<WireSource6PersonalReadSelection>().singleOrNull { it.recordId == originalSelection.selectionId } ?: return false
        val point = read.value.records.filterIsInstance<WireSource6PersonalReadCheckpoint>().singleOrNull { it.recordId == originalCheckpoint.recordId } ?: return false
        return selection.payload == originalSelection && point.version >= originalCheckpoint.version && point.payload.contentRef == originalCheckpoint.payload.contentRef &&
            point.payload.selectionId == originalCheckpoint.payload.selectionId && point.payload.cursor == originalCheckpoint.payload.cursor
    }
}

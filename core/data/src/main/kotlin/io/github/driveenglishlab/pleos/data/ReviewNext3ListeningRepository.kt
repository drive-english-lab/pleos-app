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
fun interface ReviewNext3StatePort {
    suspend fun accept(scope: LearningCallScope, request: WireReview3ReviewNextRequest3, response: WireReview3ReviewNextResponse3): Boolean
}
data class ReviewNext3State(val owner: OwnerContext? = null, val intent: PlaybackRequestIntent? = null,
    val response: WireReview3ReviewNextResponse3? = null, val loading: Boolean = false, val errorCode: String? = null,
    val businessStateCurrent: Boolean = false, val requiresExplicitResume: Boolean = true,
    val retainedParentAnchor:PendingOriginalAnchor?=null,val supportedChoices:Set<PendingChoiceKind> = emptySet(),
    val pendingChoiceRetention:PendingChoiceRetention?=null)
data class ReviewNext3Activation(val intent: PlaybackRequestIntent, val response: WireReview3ReviewNextResponse3)
data class RestorableNextOperation(val operationId:UUID,val contractVersion:Int)
enum class PendingChoiceReconciliation { NONE, SAME_CHILD_REQUIRED, HELD_PARENT_REFRESH }
data class PendingChoiceRetention(val anchor:PendingOriginalAnchor,val childOperationId:UUID?,
    val reconciliation:PendingChoiceReconciliation,val parentHoldConfirmed:Boolean,val parentProjectionObservedAfterChild:Boolean=false)
/** Optional existing-registry adapter. A local flag or a public catalog read is not evidence. */
fun interface PendingOriginalCapabilityPort { suspend fun supported(scope:LearningCallScope):Boolean }
fun interface PendingChoiceCapabilityPort { suspend fun supported(scope:LearningCallScope,kind:PendingChoiceKind):Boolean }
data class PendingOriginalAnchor(val owner:OwnerContext,val parentOperationId:UUID,val parentContractVersion:Int,
    val selectionIntentId:UUID,val requestHash:String,val outcomeVersion:Long,val targetStreamId:UUID,
    val parentKind:PlaybackRequestKind,val selectionKind:String,val originView:String?,
    val requestedArticleId:UUID?,val requestedSourceSnapshotId:UUID?,val pendingTarget:WireReview3PendingTarget3?,val parentListeningMode:ListeningMode) {
    val childKind get()=when(parentKind) {
        PlaybackRequestKind.EXPLICIT_START->PlaybackRequestKind.EXPLICIT_START
        PlaybackRequestKind.EXPLICIT_ARTICLE->PlaybackRequestKind.EXPLICIT_ARTICLE
        else->PlaybackRequestKind.EXPLICIT_NEXT
    }
    fun childKind(kind:PendingChoiceKind)=when(kind) {
        PendingChoiceKind.ORIGINAL->childKind
        PendingChoiceKind.READY_ALTERNATIVE->PlaybackRequestKind.READY_ALTERNATIVE
        PendingChoiceKind.CONTINUE->if(parentKind==PlaybackRequestKind.AUTO_NEXT)PlaybackRequestKind.EXPLICIT_NEXT else parentKind
    }
}

/** Durable original input/admission + versioned outcome. No output/navigation API is owned here. */
class ReviewNext3ListeningRepository(private val api: IncrementalLearningApi, private val codecs: IncrementalContractJson,
    private val database: LearningDatabase, private val dao: ServiceIntentDao, private val reviewDao: ReviewDao,
    private val sharedState: ReviewNext3StatePort, private val capability:PendingOriginalCapabilityPort?=null,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val utcNow: () -> Instant = Instant::now, val processToken: UUID = UUID.randomUUID(),
    private val choiceCapability:PendingChoiceCapabilityPort?=null) {
    private val lane = Mutex()
    private val observations=api.nextObservations()
    @Volatile private var visibleOperation: UUID? = null
    private val mutable = MutableStateFlow(ReviewNext3State())
    val state = mutable.asStateFlow()
    private val businessVersions = ConcurrentHashMap<String, Long>()
    private data class NegotiatedScope(val owner:OwnerContext,val subject:UUID,val session:UUID,val installation:UUID)
    private val negotiated=ConcurrentHashMap<NegotiatedScope,Set<String>>()
    private fun LearningCallScope.negotiationKey()=NegotiatedScope(owner,session.subjectId,session.sessionId,session.installationId)
    suspend fun supportsPendingOriginal(scope:LearningCallScope):Boolean=supportsPendingChoice(scope,PendingChoiceKind.ORIGINAL)
    suspend fun supportsPendingChoice(scope:LearningCallScope,kind:PendingChoiceKind):Boolean=api.isCurrent(scope) &&
        (kind.capability in negotiated[scope.negotiationKey()].orEmpty() ||
            kind==PendingChoiceKind.ORIGINAL && capability?.supported(scope)==true || choiceCapability?.supported(scope,kind)==true)

    fun newBinding(owner: OwnerContext, operationId: UUID, contextToken: UUID, kind: PlaybackRequestKind,
        channel: PlaybackRequestChannel, requestedArticleId: UUID?, requestedSourceSnapshotId: UUID?, listeningMode: ListeningMode,
        returnToken: CatalogViewReturnToken? = null,pendingOriginalParentOperationId:UUID?=null,pendingChoiceKind:PendingChoiceKind?=null): PlaybackRequestBinding {
        val began = elapsedMs()
        return PlaybackRequestBinding(owner, operationId, contextToken, processToken, kind, channel, requestedArticleId, requestedSourceSnapshotId,
            listeningMode, began, Math.addExact(began, channel.budgetMs), returnToken,pendingOriginalParentOperationId,pendingChoiceKind)
    }
    fun clear() { observations.invalidate(); visibleOperation = null; businessVersions.clear();negotiated.clear(); mutable.value = ReviewNext3State() }

    /** Called on explicit input, before saving A or awaiting a network. No server cancel is sent. */
    suspend fun beginOriginalInput(scope:LearningCallScope,parentOperationId:UUID,childOperationId:UUID):PendingOriginalAnchor? =
        beginPendingInput(scope,parentOperationId,childOperationId,PendingChoiceKind.ORIGINAL)
    suspend fun beginPendingInput(scope:LearningCallScope,parentOperationId:UUID,childOperationId:UUID,kind:PendingChoiceKind):PendingOriginalAnchor? {
        if(!supportsPendingChoice(scope,kind) || parentOperationId==childOperationId)return null
        val anchor=database.withTransaction {
            if(dao.pendingChoiceStopped(scope.owner,parentOperationId.toString()))return@withTransaction null
            dao.pendingOriginalInput(scope.owner,parentOperationId.toString(),childOperationId.toString())?.let {
                if(dao.pendingChoiceInputs(scope.owner).firstOrNull{entry->entry.targetId==parentOperationId.toString()}?.operationId!=it.operationId)return@withTransaction null
                val saved=Json.parseToJsonElement(it.payloadJson).jsonObject
                require((saved["choice_kind"]?.jsonPrimitive?.content?.let(PendingChoiceKind::valueOf)?:PendingChoiceKind.ORIGINAL)==kind)
                return@withTransaction decodeStoredAnchor(scope,saved)
            }
            val parent=requireOwned(scope,parentOperationId)
            val anchor=anchorForParent(scope,parent)?:return@withTransaction null
            if(kind==PendingChoiceKind.ORIGINAL && anchor.parentListeningMode==ListeningMode.ORIGINAL_FULL)return@withTransaction null
            if(!dao.recordPendingOriginalInput(scope.owner,parent,childOperationId.toString(),encodeAnchor(anchor,childOperationId,kind)))return@withTransaction null
            businessVersions.remove(parent.operationId)
            anchor
        }
        if(anchor!=null && api.isCurrent(scope)) {
            visibleOperation=anchor.parentOperationId
            val row=requireOwned(scope,anchor.parentOperationId)
            publish(scope,readAnyState(scope,row,anchor.parentContractVersion))
        }
        return anchor
    }

    suspend fun submit(scope: LearningCallScope, request: WireReview3ReviewNextRequest3, binding: PlaybackRequestBinding,
        guard: PlaybackActivationGuard, maxPolls: Int = 5): LearningReply<ReviewNext3State> = lane.withLock {
        if (!api.isCurrent(scope) || binding.owner != scope.owner) return@withLock failedSession()
        val choice=request.selectionOverride?.kind?.let(PendingChoiceKind::fromWire)
        if(request.selectionOverride!=null && choice==null)return@withLock LearningReply.Failed("PENDING_CHOICE_UNSUPPORTED",false)
        return@withLock safely(scope) {
            validateRequest(scope, request, binding)
            request.selectionOverride?.let { selectionOverride ->
                val original=dao.pendingOriginalInput(scope.owner,selectionOverride.pendingOperationId,request.operationId)
                    ?:return@safely LearningReply.Failed("PENDING_ORIGINAL_INPUT_REQUIRED",false)
                if(dao.pendingChoiceStopped(scope.owner,selectionOverride.pendingOperationId) ||
                    dao.pendingChoiceInputs(scope.owner).firstOrNull{it.targetId==selectionOverride.pendingOperationId}?.operationId!=original.operationId)
                    return@safely LearningReply.Failed("PENDING_INPUT_SUPERSEDED",false)
                // Revocation is durable and independent of this process's capability memo.
                // Only the latest still-valid input reaches the actual support gate.
                if(!supportsPendingChoice(scope,requireNotNull(choice)))return@safely LearningReply.Failed("PENDING_CHOICE_UNSUPPORTED",false)
                val marker=Json.parseToJsonElement(original.payloadJson).jsonObject
                require((marker["choice_kind"]?.jsonPrimitive?.content?.let(PendingChoiceKind::valueOf)?:PendingChoiceKind.ORIGINAL)==PendingChoiceKind.fromWire(selectionOverride.kind))
                validateOverride(request,binding,decodeStoredAnchor(scope,marker))
            }
            val payload = codecs.encode("review3", "ReviewNextRequest3", request, WireReview3ReviewNextRequest3.serializer())
            val canonical = incrementalCanonical(codecs.parse("review3", payload))
            val hash = LearningJson.hash(canonical)
            val local = LocalPlaybackRequest(request.operationId, scope.owner.profileId.toString(), scope.owner.privacyEpoch, scope.owner.revision,
                binding.contextToken.toString(), binding.processToken.toString(), encodePlaybackBinding(binding, request.streamId), hash, "active", 0, null, null, 0, null, false)
            val registered = database.withTransaction {
                reviewDao.queue(scope.owner, LocalReviewOperation(request.operationId, local.profileId, local.privacyEpoch, local.ownerRevision,
                    "review3_next", request.streamId, canonical, hash))
                dao.register(scope.owner, local)
            }
            visibleOperation = binding.operationId
            exchange(scope, request, registered, guard, maxPolls)
        }
    }

    /** Observation is exactly the original POST bytes, never a new operation/job/deadline. */
    suspend fun observe(scope: LearningCallScope, operationId: UUID, guard: PlaybackActivationGuard,
        maxPolls: Int = 5): LearningReply<ReviewNext3State> = lane.withLock {
        if (!api.isCurrent(scope)) return@withLock failedSession()
        safely(scope) {
            val row = requireOwned(scope, operationId)
            val operation = requireNotNull(reviewDao.operation(operationId.toString()))
            require(operation.kind == "review3_next" && operation.payloadHash == row.requestHash && operation.status != "isolated")
            val request = codecs.decode("review3", "ReviewNextRequest3", operation.payloadJson, WireReview3ReviewNextRequest3.serializer())
            exchange(scope, request, row, guard, maxPolls)
        }
    }
    suspend fun restore(scope: LearningCallScope, operationId: UUID): LearningReply<ReviewNext3State> = lane.withLock {
        if (!api.isCurrent(scope)) return@withLock failedSession()
        if(reviewDao.operation(operationId.toString())?.kind!="review3_next")return@withLock LearningReply.Failed("REVIEW3_OPERATION_REQUIRED",false)
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
    suspend fun latestPendingOperation(scope:LearningCallScope):RestorableNextOperation? {
        if(!api.isCurrent(scope))return null
        val pending=dao.restorableRequests(scope.owner.profileId.toString(),scope.owner.privacyEpoch,scope.owner.revision).firstNotNullOfOrNull { row ->
            val operation=reviewDao.operation(row.operationId)
            val version=when(operation?.kind){"review3_next"->3;"review2_next"->2;else->null}
            val outcome=row.outcomeJson?.let{serviceIntentOutcome(Json.parseToJsonElement(it).jsonObject).getValue("state").jsonPrimitive.content}
            val retainedFailure=version==3 && outcome=="no_candidate" && pendingChoiceRetention(scope,UUID.fromString(row.operationId))?.parentHoldConfirmed==true
            if(version!=null && operation?.status!="isolated" && (outcome==null || outcome in setOf("pending","expired_waiting_explicit") || retainedFailure))
                RestorableNextOperation(UUID.fromString(row.operationId),version) else null
        }
        if(pending!=null)return pending
        // A local explicit choice can be fenced before A saves, without ever sending its child.
        return dao.pendingChoiceInputs(scope.owner).firstNotNullOfOrNull { marker ->
            val child=Json.parseToJsonElement(marker.payloadJson).jsonObject.getValue("child_operation_id").jsonPrimitive.content
            if(dao.intent(child)!=null)return@firstNotNullOfOrNull null
            val id=UUID.fromString(requireNotNull(marker.targetId))
            val retained=pendingChoiceRetention(scope,id)?:return@firstNotNullOfOrNull null
            RestorableNextOperation(id,retained.anchor.parentContractVersion)
        }
    }
    suspend fun restoreLatestPending(scope:LearningCallScope):LearningReply<ReviewNext3State> {
        val latest=latestPendingOperation(scope)?:return LearningReply.Failed("NO_PENDING_SELECTION",false)
        if(latest.contractVersion!=3)return LearningReply.Failed("REVIEW2_PENDING_RESTORE_REQUIRED",false)
        return restore(scope,latest.operationId)
    }
    /** Manual receipt reconciliation uses a dedicated read of the original identity, with no new grant. */
    suspend fun observeStateOnly(scope:LearningCallScope,operationId:UUID,maxPolls:Int=20):LearningReply<ReviewNext3State> = lane.withLock {
        if(!api.isCurrent(scope))return@withLock failedSession()
        safely(scope) {
            require(maxPolls in 1..20)
            val old=requireOwned(scope,operationId)
            val operation=requireNotNull(reviewDao.operation(old.operationId))
            require(operation.kind=="review3_next" && operation.status!="isolated" && operation.payloadHash==old.requestHash && LearningJson.hash(operation.payloadJson)==old.requestHash)
            if(old.polls>=maxPolls)return@safely LearningReply.Failed("OBSERVATION_LIMIT_REACHED",false)
            val stopped=old.copy(state=PlaybackRequestPolicy.restored(intent(old)).state.name.lowercase(),polls=old.polls+1)
            require(dao.advance(scope.owner,old,stopped))
            visibleOperation=operationId
            val request=codecs.decode("review3","ReviewNextRequest3",operation.payloadJson,WireReview3ReviewNextRequest3.serializer())
            val guard=PlaybackActivationGuard(scope.owner,null,processToken,elapsedMs(),utcNow(),false)
            when(val reply=observations.read(scope,NextObservationRequest(operationId,3,operation.payloadHash,decodeBinding(old.bindingJson).channel))) {
                is NextObservationReply.Found-> {
                    val value=(reply.value as? NextObservationValue.Review3)?.value
                        ?:return@safely LearningReply.Failed("NEXT_OBSERVATION_UNCERTAIN",true)
                    accept(scope,request,stopped,value,guard,reply.choiceCapabilities)
                }
                else->{
                    val failure=observationFailure(scope,stopped,reply)
                    publish(scope,readState(scope,requireOwned(scope,operationId),error=failure.code));failure
                }
            }
        }
    }
    /** Cancellation has its own DB transaction, never waits behind a pending HTTP read. */
    suspend fun cancel(scope: LearningCallScope, operationId: UUID): Boolean {
        if (!api.isCurrent(scope)) return false
        val version=when(reviewDao.operation(operationId.toString())?.kind){"review3_next"->3;"review2_next"->2;else->return false}
        val next = database.withTransaction {
            val old = requireOwned(scope, operationId)
            dao.stopPendingChoice(scope.owner,old)
            val cancelled = PlaybackRequestPolicy.cancel(intentAny(old,version))
            val changed = old.copy(state = cancelled.state.name.lowercase())
            if (changed != old && !dao.advance(scope.owner, old, changed)) return@withTransaction null
            changed
        } ?: return false
        val visible=visibleOperation?.let{dao.intent(it.toString())}
        if(visible!=null) {
            val visibleVersion=if(reviewDao.operation(visible.operationId)?.kind=="review3_next")3 else 2
            publish(scope,readAnyState(scope,visible,visibleVersion,error="EXPLICIT_RESUME_REQUIRED"))
        } else publish(scope,readAnyState(scope,next,version,error="EXPLICIT_RESUME_REQUIRED"))
        return true
    }

    /** Call with fresh owner/context/process/time/permission, then perform product output outside. */
    suspend fun claimActivation(scope: LearningCallScope, operationId: UUID, guard: PlaybackActivationGuard,
        claimId: UUID = UUID.randomUUID()): ReviewNext3Activation? = lane.withLock {
        if (!api.isCurrent(scope)) return@withLock null
        if(reviewDao.operation(operationId.toString())?.kind!="review3_next")return@withLock null
        val old = requireOwned(scope, operationId)
        val response = response(old) ?: return@withLock null
        if (businessVersions[old.operationId] != old.outcomeVersion) return@withLock null
        val original = intent(old)
        val decision = PlaybackRequestPolicy.claim(original, freshGuard(guard), claimId)
        val changed = when (decision) { is PlaybackActivationDecision.Claimed -> decision.intent; is PlaybackActivationDecision.StateOnly -> decision.intent }
        val next = old.copy(state = changed.state.name.lowercase(), activationClaimId = changed.activationClaimId?.toString())
        if (next != old && !dao.advance(scope.owner, old, next)) return@withLock null
        publish(scope, readState(scope, next))
        if (decision is PlaybackActivationDecision.Claimed) ReviewNext3Activation(changed, response) else null
    }

    private suspend fun exchange(scope: LearningCallScope, request: WireReview3ReviewNextRequest3, original: LocalPlaybackRequest,
        suppliedGuard: PlaybackActivationGuard, maxPolls: Int): LearningReply<ReviewNext3State> {
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
        val response: LearningReply<ReviewNext3State> = withTimeoutOrNull(remaining) {
            val operation = requireNotNull(reviewDao.operation(row.operationId))
            var capabilities:Set<String> = emptySet()
            val reply = api.call("review3", "/v2/listening/next", "post", scope, codecs.parse("review3", operation.payloadJson), WireReview3ReviewNextResponse3.serializer(),
                responseCapabilities={capabilities=it})
            when (reply) {
                is LearningReply.Stored -> accept(scope, request, row, reply.value, suppliedGuard,capabilities)
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

    private suspend fun accept(scope: LearningCallScope, request: WireReview3ReviewNextRequest3, sent: LocalPlaybackRequest,
        value: WireReview3ReviewNextResponse3, guard: PlaybackActivationGuard,capabilities:Set<String>): LearningReply<ReviewNext3State> {
        if (!api.isCurrent(scope)) return failedSession()
        validateResponse(scope, request, sent, value)
        negotiated[scope.negotiationKey()]=capabilities.intersect(PendingChoiceKind.entries.map{it.capability}.toSet())
        // Re-read local cancellation inside the same DB transaction as the receipt/outcome.
        // A network reply cannot race a stop into resurrecting ACTIVE or changing its deadline.
        val previous = database.withTransaction {
            val local = requireOwned(scope, UUID.fromString(sent.operationId))
            val before = response(local)
            if (before != null) {
                val first = codecs.parse("review3", codecs.encode("review3", "ReviewNextResponse3", before, WireReview3ReviewNextResponse3.serializer())).jsonObject
                val incoming = codecs.parse("review3", codecs.encode("review3", "ReviewNextResponse3", value, WireReview3ReviewNextResponse3.serializer())).jsonObject
                if (!codecs.validateReviewNextTransition(first, incoming) || value.outcomeProjection.version == before.outcomeProjection.version &&
                    Instant.parse(value.outcomeProjection.observedAt) < Instant.parse(before.outcomeProjection.observedAt)) return@withTransaction local
            }
            val admission = codecs.encode("review3", "NextAdmissionReceipt3", value.admissionReceipt, WireReview3NextAdmissionReceipt3.serializer())
            val outcome = buildJsonObject {
                put("local_review_contract_version",3)
                put("outcome_projection",codecs.parse("review3",codecs.encode("review3", "NextOutcome3", value.outcomeProjection, WireReview3NextOutcome3.serializer())))
                put("pending_target",value.pendingTarget?.let{codecs.parse("review3",codecs.encode("review3","PendingTarget3",it,WireReview3PendingTarget3.serializer()))}?:JsonNull)
            }.toString()
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

    private fun validateRequest(scope: LearningCallScope, request: WireReview3ReviewNextRequest3, binding: PlaybackRequestBinding) {
        require(request.operationId == binding.operationId.toString() && request.schemaVersion == "6" && request.reviewContractVersion == 3L &&
            request.profileContext.profileId == scope.owner.profileId.toString() && request.profileContext.privacyEpoch == scope.owner.privacyEpoch &&
            request.intentKind == binding.kind.name.lowercase() && request.requestChannel == binding.channel.name.lowercase() &&
            request.requestedArticleId == binding.requestedArticleId?.toString() && request.requestedSourceSnapshotId == binding.requestedSourceSnapshotId?.toString())
        require((request.selectionOverride==null)==(binding.pendingOriginalParentOperationId==null))
        if (request.originView == "issues" && request.selectionOverride==null) require(binding.kind == PlaybackRequestKind.EXPLICIT_ARTICLE ||
            binding.kind == PlaybackRequestKind.EXPLICIT_START && request.selectionKind == "explicit_article" && binding.requestedArticleId != null && binding.requestedSourceSnapshotId != null)
    }
    private fun validateOverride(request:WireReview3ReviewNextRequest3,binding:PlaybackRequestBinding,anchor:PendingOriginalAnchor) {
        val selectionOverride=requireNotNull(request.selectionOverride)
        val kind=requireNotNull(PendingChoiceKind.fromWire(selectionOverride.kind))
        require((binding.pendingChoiceKind?:PendingChoiceKind.ORIGINAL)==kind)
        require(selectionOverride.pendingOperationId==anchor.parentOperationId.toString() &&
            selectionOverride.pendingSelectionIntentId==anchor.selectionIntentId.toString() && selectionOverride.pendingRequestHash==anchor.requestHash &&
            selectionOverride.expectedPendingOutcomeVersion==anchor.outcomeVersion && binding.pendingOriginalParentOperationId==anchor.parentOperationId &&
            request.streamId==anchor.targetStreamId.toString() && request.intentKind==anchor.childKind(kind).name.lowercase() &&
            request.originView==anchor.originView && request.trigger=="explicit" && request.operationId!=anchor.parentOperationId.toString())
        require(binding.listeningMode==if(kind==PendingChoiceKind.ORIGINAL)ListeningMode.ORIGINAL_FULL else anchor.parentListeningMode)
        if(kind==PendingChoiceKind.READY_ALTERNATIVE)require(request.selectionKind=="automatic_unheard" && request.requestedArticleId==null && request.requestedSourceSnapshotId==null)
        else require(request.selectionKind==anchor.selectionKind)
        val expectedArticle=anchor.pendingTarget?.articleId?:anchor.requestedArticleId?.toString()
        val expectedSource=anchor.pendingTarget?.sourceSnapshotId?:anchor.requestedSourceSnapshotId?.toString()
        if(request.requestedArticleId!=null && expectedArticle!=null)require(request.requestedArticleId==expectedArticle)
        if(request.requestedSourceSnapshotId!=null && expectedSource!=null)require(request.requestedSourceSnapshotId==expectedSource)
    }
    private suspend fun validateResponse(scope: LearningCallScope, request: WireReview3ReviewNextRequest3, row: LocalPlaybackRequest, value: WireReview3ReviewNextResponse3) {
        codecs.encode("review3", "ReviewNextResponse3", value, WireReview3ReviewNextResponse3.serializer())
        codecs.validateReviewNext3Choice(codecs.parse("review3",codecs.encode("review3","ReviewNextRequest3",request,WireReview3ReviewNextRequest3.serializer())).jsonObject,
            codecs.parse("review3",codecs.encode("review3","ReviewNextResponse3",value,WireReview3ReviewNextResponse3.serializer())).jsonObject)
        require(value.operationId == request.operationId && value.streamId == request.streamId && value.profileContext == request.profileContext &&
            value.admissionReceipt.operationId == value.operationId && value.admissionReceipt.requestHash == row.requestHash && value.admissionReceipt.intentKind == request.intentKind)
        val outcome = value.outcomeProjection
        require(Instant.parse(outcome.observedAt) >= Instant.parse(value.admissionReceipt.acceptedAt))
        value.pendingTarget?.let { target ->
            request.requestedArticleId?.let{require(target.articleId==it)}
            request.requestedSourceSnapshotId?.let{require(target.sourceSnapshotId==it)}
            if(target.originalAvailability=="ready")require(target.originalComponent?.let{it.kind=="main_original" && it.status=="ready" &&
                it.componentId!=null && it.payloadHash!=null}==true)
            if(target.originalAvailability=="unknown")require(target.originalComponent==null)
        }
        val choice=request.selectionOverride?.kind?.let(PendingChoiceKind::fromWire)
        if(choice==PendingChoiceKind.CONTINUE && value.pendingTarget!=null) {
            val marker=requireNotNull(dao.pendingOriginalInput(scope.owner,requireNotNull(request.selectionOverride).pendingOperationId,request.operationId))
            val anchor=decodeStoredAnchor(scope,Json.parseToJsonElement(marker.payloadJson).jsonObject)
            anchor.pendingTarget?.let{require(value.pendingTarget.articleId==it.articleId && value.pendingTarget.sourceSnapshotId==it.sourceSnapshotId && value.pendingTarget.sourceHash==it.sourceHash)}
        }
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
                is WireReview3Source6LegacyContentRef6 -> ref.legacyRef.listeningMode
                is WireReview3Source6PreparedContentRef6 -> ref.listeningMode
            }
            if (outcome.state == "selected" || choice!=null) {
                require(mode == intent(row).binding.listeningMode.name.lowercase())
                if(request.selectionOverride==null)require(selected.settingsSnapshot.listeningMode==mode)
                else if(choice==PendingChoiceKind.ORIGINAL) {
                    require(mode=="original_full" && checkpoint.payload.activeRunId==null)
                    val cursor=checkpoint.payload.cursor as? WireReview3Source6SegmentCursor6
                    require(cursor!=null && cursor.phase=="main" && cursor.segmentIndex==0L)
                }
                if(request.selectionOverride!=null) {
                    val marker=requireNotNull(dao.pendingOriginalInput(scope.owner,request.selectionOverride.pendingOperationId,request.operationId))
                    val anchor=decodeStoredAnchor(scope,Json.parseToJsonElement(marker.payloadJson).jsonObject)
                    val target=anchor.pendingTarget
                    target?.let {
                        if(choice==PendingChoiceKind.READY_ALTERNATIVE)require(article(selected.contentRef)!=it.articleId)
                        else {
                            require(article(selected.contentRef)==it.articleId && snapshot(selected.contentRef)==it.sourceSnapshotId)
                            (selected.contentRef as? WireReview3Source6PreparedContentRef6)?.let{ref->require(ref.sourceHash==target.sourceHash)}
                        }
                    }
                    if(choice==PendingChoiceKind.READY_ALTERNATIVE && target==null)anchor.requestedArticleId?.let{require(article(selected.contentRef)!=it.toString())}
                }
            }
            require(outcome.sourceStatus.any { it.kind == (if (mode == "original_full") "main_original" else "main_briefing") && it.status == "ready" })
            (selected.contentRef as? WireReview3Source6PreparedContentRef6)?.let{ref->require(outcome.sourceStatus.any{it.componentId==ref.mainComponentId && it.status=="ready" &&
                it.kind==(if(mode=="original_full")"main_original" else "main_briefing")})}
        }
    }
    private suspend fun requireOwned(scope: LearningCallScope, id: UUID): LocalPlaybackRequest {
        val row = requireNotNull(dao.intent(id.toString()))
        require(!row.isolated && row.profileId == scope.owner.profileId.toString() && row.privacyEpoch == scope.owner.privacyEpoch && row.ownerRevision == scope.owner.revision)
        return row
    }
    private fun intent(row: LocalPlaybackRequest): PlaybackRequestIntent {
        val binding = decodeBinding(row.bindingJson)
        val admission = row.admissionJson?.let { codecs.decode("review3", "NextAdmissionReceipt3", it, WireReview3NextAdmissionReceipt3.serializer()).domain() }
        val outcome = row.outcomeJson?.let { codecs.decode("review3", "NextOutcome3", serviceIntentOutcome(Json.parseToJsonElement(it).jsonObject).toString(), WireReview3NextOutcome3.serializer()) }
        return PlaybackRequestIntent(binding, admission, PlaybackRequestState.valueOf(row.state.uppercase()), row.polls, row.outcomeVersion,
            outcome?.state?.let { PlaybackRequestOutcome.valueOf(it.uppercase()) }, row.activationClaimId?.let(UUID::fromString))
    }
    private fun intentAny(row:LocalPlaybackRequest,version:Int):PlaybackRequestIntent {
        if(version==3)return intent(row)
        val admission=row.admissionJson?.let{codecs.decode("review2","NextAdmissionReceipt2",it,WireReview2NextAdmissionReceipt2.serializer())}?.let{
            PlaybackAdmission(UUID.fromString(it.operationId),it.requestHash,Instant.parse(it.acceptedAt),PlaybackRequestKind.valueOf(it.intentKind.uppercase()),
                it.selectionIntentId?.let(UUID::fromString),it.activationDeadlineAt?.let(Instant::parse))}
        val outcome=row.outcomeJson?.let{codecs.decode("review2","NextOutcome2",it,WireReview2NextOutcome2.serializer())}
        return PlaybackRequestIntent(decodeBinding(row.bindingJson),admission,PlaybackRequestState.valueOf(row.state.uppercase()),row.polls,row.outcomeVersion,
            outcome?.state?.let{PlaybackRequestOutcome.valueOf(it.uppercase())},row.activationClaimId?.let(UUID::fromString))
    }
    private fun response(row: LocalPlaybackRequest): WireReview3ReviewNextResponse3? {
        val admission = row.admissionJson ?: return null; val outcome = row.outcomeJson ?: return null
        val request = Json.parseToJsonElement(row.bindingJson).jsonObject
        val projection=Json.parseToJsonElement(outcome).jsonObject
        return WireReview3ReviewNextResponse3("6", 3, row.operationId, WireReview3Source6CommonProfileContext(row.profileId, row.privacyEpoch),
            request.getValue("stream_id").jsonPrimitive.content,
            codecs.decode("review3", "NextAdmissionReceipt3", admission, WireReview3NextAdmissionReceipt3.serializer()),
            codecs.decode("review3", "NextOutcome3", serviceIntentOutcome(projection).toString(), WireReview3NextOutcome3.serializer()),
            projection.getValue("pending_target").takeUnless{it==JsonNull}?.let{codecs.decode("review3","PendingTarget3",it.toString(),WireReview3PendingTarget3.serializer())})
    }
    private suspend fun readState(scope: LearningCallScope, row: LocalPlaybackRequest, loading: Boolean = false, error: String? = null,
        business: Boolean = businessVersions[row.operationId] == row.outcomeVersion): ReviewNext3State {
        val intent = intent(row)
        val retained=pendingChoiceRetention(scope,UUID.fromString(row.operationId))
        val supported=PendingChoiceKind.entries.filter{it.capability in negotiated[scope.negotiationKey()].orEmpty()}.toSet()
        return ReviewNext3State(scope.owner, intent, response(row), loading, error, business,
            intent.state != PlaybackRequestState.ACTIVE || !business || intent.outcome !in setOf(PlaybackRequestOutcome.SELECTED, PlaybackRequestOutcome.RESUMED),
            retained?.anchor,supported,retained)
    }
    private suspend fun readAnyState(scope:LearningCallScope,row:LocalPlaybackRequest,version:Int,error:String?=null):ReviewNext3State {
        if(version==3)return readState(scope,row,error=error)
        val retained=pendingChoiceRetention(scope,UUID.fromString(row.operationId))
        return ReviewNext3State(owner=scope.owner,intent=intentAny(row,2),errorCode=error,requiresExplicitResume=true,
            retainedParentAnchor=retained?.anchor,supportedChoices=PendingChoiceKind.entries.filter{it.capability in negotiated[scope.negotiationKey()].orEmpty()}.toSet(),pendingChoiceRetention=retained)
    }
    private suspend fun publish(scope: LearningCallScope, value: ReviewNext3State) {
        if (api.isCurrent(scope) && value.intent?.binding?.operationId == visibleOperation) mutable.value = value
    }
    private fun freshGuard(value: PlaybackActivationGuard) = value.copy(elapsedMs = elapsedMs(), utcNow = if (value.utcNow == null) null else utcNow())
    private fun WireReview3NextAdmissionReceipt3.domain() = PlaybackAdmission(UUID.fromString(operationId), requestHash, Instant.parse(acceptedAt),
        PlaybackRequestKind.valueOf(intentKind.uppercase()), selectionIntentId?.let(UUID::fromString), activationDeadlineAt?.let(Instant::parse))
    private fun article(ref: WireReview3Source6ContentRef6) = when (ref) { is WireReview3Source6LegacyContentRef6 -> ref.legacyRef.articleId; is WireReview3Source6PreparedContentRef6 -> ref.articleId }
    private fun snapshot(ref: WireReview3Source6ContentRef6) = when (ref) { is WireReview3Source6LegacyContentRef6 -> ref.legacyRef.sourceSnapshotId; is WireReview3Source6PreparedContentRef6 -> ref.sourceSnapshotId }
    private suspend fun safely(scope: LearningCallScope, work: suspend () -> LearningReply<ReviewNext3State>): LearningReply<ReviewNext3State> = try { work() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { failure(scope, "INVALID_REVIEW3_CONTRACT", false) }
        catch (_: LearningProtocolException) { failure(scope, "INVALID_REVIEW3_CONTRACT", false) }
        catch (_: Exception) { if (!api.isCurrent(scope)) failedSession() else failure(scope, "REVIEW3_UNAVAILABLE", true) }
    private suspend fun failure(scope: LearningCallScope, code: String, retryable: Boolean): LearningReply.Failed {
        observations.invalidate();negotiated.remove(scope.negotiationKey())
        val id = visibleOperation
        if (id != null && api.isCurrent(scope)) {
            val row = dao.intent(id.toString())
            if (row != null && !row.isolated && row.profileId == scope.owner.profileId.toString() && row.privacyEpoch == scope.owner.privacyEpoch && row.ownerRevision == scope.owner.revision)
                try { publish(scope, readState(scope, row, error = code)) } catch (_: Exception) { }
        }
        return LearningReply.Failed(code, retryable)
    }
    private fun failedSession() = LearningReply.Failed("SESSION_CHANGED", false)

    private suspend fun anchorForParent(scope:LearningCallScope,parent:LocalPlaybackRequest):PendingOriginalAnchor? {
        if(parent.state=="applied" || parent.outcomeJson==null || dao.pendingChoiceStopped(scope.owner,parent.operationId))return null
        if(parent.state=="cancelled" && dao.pendingChoiceInputs(scope.owner).none{it.targetId==parent.operationId})return null
        val operation=reviewDao.operation(parent.operationId)?:return null
        if(operation.status=="isolated" || operation.kind !in setOf("review2_next","review3_next") ||
            operation.payloadHash!=parent.requestHash || LearningJson.hash(operation.payloadJson)!=parent.requestHash)return null
        val version=if(operation.kind=="review3_next")3 else 2
        if(version==3)codecs.decode("review3","ReviewNextRequest3",operation.payloadJson,WireReview3ReviewNextRequest3.serializer())
        else codecs.decode("review2","ReviewNextRequest2",operation.payloadJson,WireReview2ReviewNextRequest2.serializer())
        val request=Json.parseToJsonElement(operation.payloadJson).jsonObject
        val outcome=serviceIntentOutcome(Json.parseToJsonElement(parent.outcomeJson).jsonObject)
        if(outcome.getValue("state").jsonPrimitive.content !in setOf("pending","expired_waiting_explicit"))return null
        val admission=parent.admissionJson?.let{Json.parseToJsonElement(it).jsonObject}?:return null
        val intent=admission.getValue("selection_intent_id").takeUnless{it==JsonNull}?.jsonPrimitive?.content?:return null
        require(admission.getValue("request_hash").jsonPrimitive.content==parent.requestHash)
        fun optional(key:String)=request[key]?.takeUnless{it==JsonNull}?.jsonPrimitive?.content
        return PendingOriginalAnchor(scope.owner,UUID.fromString(parent.operationId),version,UUID.fromString(intent),parent.requestHash,parent.outcomeVersion,
            UUID.fromString(request.getValue("stream_id").jsonPrimitive.content),PlaybackRequestKind.valueOf(request.getValue("intent_kind").jsonPrimitive.content.uppercase()),
            request.getValue("selection_kind").jsonPrimitive.content,optional("origin_view"),optional("requested_article_id")?.let(UUID::fromString),
            optional("requested_source_snapshot_id")?.let(UUID::fromString),if(version==3)response(parent)?.pendingTarget else null,decodeBinding(parent.bindingJson).listeningMode)
    }
    private suspend fun decodeStoredAnchor(scope:LearningCallScope,value:JsonObject):PendingOriginalAnchor {
        val parent=requireOwned(scope,UUID.fromString(value.getValue("parent_operation_id").jsonPrimitive.content))
        return decodeAnchor(scope.owner,value,decodeBinding(parent.bindingJson).listeningMode)
    }
    suspend fun retainedPendingAnchor(scope:LearningCallScope,operationId:UUID):PendingOriginalAnchor?=pendingChoiceRetention(scope,operationId)?.anchor
    /** Refresh only confirmed holds automatically; ambiguous children are reconciled first. */
    suspend fun refreshRetainedPendingAnchor(scope:LearningCallScope,operationId:UUID):LearningReply<PendingOriginalAnchor> {
        if(!api.isCurrent(scope))return failedSession()
        val retained=pendingChoiceRetention(scope,operationId)?:return LearningReply.Failed("PENDING_CONTEXT_CHANGED",false)
        if(retained.reconciliation==PendingChoiceReconciliation.SAME_CHILD_REQUIRED)return LearningReply.Failed("PENDING_CHILD_RECEIPT_REQUIRED",true)
        if(!retained.parentHoldConfirmed)return LearningReply.Stored(scope,retained.anchor)
        return observePendingParentStateOnly(scope,retained.anchor.parentOperationId)
    }
    /** Explicit, decrease-only lookup of the original version/op/hash; never submits a child. */
    suspend fun observePendingParentStateOnly(scope:LearningCallScope,parentOperationId:UUID):LearningReply<PendingOriginalAnchor> = lane.withLock {
        if(!api.isCurrent(scope))return@withLock failedSession()
        try {
            val retention=pendingChoiceRetention(scope,parentOperationId)
            if(retention?.reconciliation==PendingChoiceReconciliation.SAME_CHILD_REQUIRED)return@withLock LearningReply.Failed("PENDING_CHILD_RECEIPT_REQUIRED",true)
            val parent=requireOwned(scope,parentOperationId)
            if(parent.state=="applied" || dao.pendingChoiceStopped(scope.owner,parent.operationId))return@withLock LearningReply.Failed("PENDING_CONTEXT_CHANGED",false)
            val original=requireNotNull(reviewDao.operation(parent.operationId))
            val version=when(original.kind){"review3_next"->3;"review2_next"->2;else->return@withLock LearningReply.Failed("PENDING_CONTEXT_CHANGED",false)}
            require(original.status!="isolated" && original.payloadHash==parent.requestHash && LearningJson.hash(original.payloadJson)==parent.requestHash)
            if(parent.polls>=20)return@withLock LearningReply.Failed("OBSERVATION_LIMIT_REACHED",false)
            val polling=parent.copy(state=if(parent.state=="active")"expired" else parent.state,polls=parent.polls+1)
            require(dao.advance(scope.owner,parent,polling))
            val bundle=if(version==3)"review3" else "review2"
            when(val reply=observations.read(scope,NextObservationRequest(parentOperationId,version,original.payloadHash,decodeBinding(parent.bindingJson).channel))) {
                is NextObservationReply.Found->{
                    val value=reply.body;val request=codecs.parse(bundle,original.payloadJson).jsonObject
                    require((version==3 && reply.value is NextObservationValue.Review3) || (version==2 && reply.value is NextObservationValue.Review2))
                    codecs.validateDefinition(bundle,if(version==3)"ReviewNextResponse3" else "ReviewNextResponse2",value)
                    if(version==3)codecs.validateReviewNext3Choice(request,value)
                    val admission=value.getValue("admission_receipt").jsonObject
                    require(value.getValue("operation_id")==request.getValue("operation_id") && value.getValue("profile_context")==request.getValue("profile_context") &&
                        value.getValue("stream_id")==request.getValue("stream_id") && admission.getValue("request_hash")==JsonPrimitive(parent.requestHash) &&
                        admission.getValue("operation_id")==request.getValue("operation_id") && admission.getValue("intent_kind")==request.getValue("intent_kind"))
                    parent.admissionJson?.let{require(admission==Json.parseToJsonElement(it))}
                    val outcome=value.getValue("outcome_projection").jsonObject
                    require(Instant.parse(outcome.getValue("observed_at").jsonPrimitive.content)>=Instant.parse(admission.getValue("accepted_at").jsonPrimitive.content))
                    if(parent.admissionJson!=null && parent.outcomeJson!=null) {
                        val previous=if(version==3)codecs.parse("review3",codecs.encode("review3","ReviewNextResponse3",requireNotNull(response(parent)),WireReview3ReviewNextResponse3.serializer())).jsonObject
                            else buildJsonObject {
                                put("schema_version","6");put("review_contract_version",2);put("operation_id",parent.operationId)
                                put("profile_context",request.getValue("profile_context"));put("stream_id",request.getValue("stream_id"))
                                put("admission_receipt",Json.parseToJsonElement(parent.admissionJson));put("outcome_projection",Json.parseToJsonElement(parent.outcomeJson))
                            }
                        require(codecs.validateReviewNextTransition(previous,value))
                    }
                    val projection=if(version==3)buildJsonObject {
                        put("local_review_contract_version",3);put("outcome_projection",outcome);put("pending_target",value.getValue("pending_target"))
                    }.toString() else outcome.toString()
                    val admissionBytes=parent.admissionJson?:if(version==3)codecs.encode("review3","NextAdmissionReceipt3",
                        codecs.decode("review3","NextAdmissionReceipt3",admission.toString(),WireReview3NextAdmissionReceipt3.serializer()),WireReview3NextAdmissionReceipt3.serializer())
                    else codecs.encode("review2","NextAdmissionReceipt2",codecs.decode("review2","NextAdmissionReceipt2",admission.toString(),WireReview2NextAdmissionReceipt2.serializer()),WireReview2NextAdmissionReceipt2.serializer())
                    database.withTransaction {
                        val latest=requireOwned(scope,parentOperationId)
                        val next=latest.copy(admissionJson=latest.admissionJson?:admissionBytes,outcomeJson=projection,outcomeVersion=outcome.getValue("version").jsonPrimitive.long)
                        reviewDao.acknowledge(scope.owner,original,next.admissionJson!!)
                        require(dao.advance(scope.owner,latest,next))
                    }
                    negotiated[scope.negotiationKey()]=reply.choiceCapabilities
                    val latest=requireOwned(scope,parentOperationId)
                    val fresh=anchorForParent(scope,latest)
                    val displayed=visibleOperation?.let{dao.intent(it.toString())}?:latest
                    if(visibleOperation==null)visibleOperation=parentOperationId
                    val displayedVersion=if(reviewDao.operation(displayed.operationId)?.kind=="review3_next")3 else 2
                    publish(scope,readAnyState(scope,displayed,displayedVersion))
                    if(fresh==null)LearningReply.Failed("PENDING_CONTEXT_CHANGED",false) else LearningReply.Stored(scope,fresh)
                }
                else->{
                    val failure=observationFailure(scope,polling,reply)
                    val displayed=visibleOperation?.let{dao.intent(it.toString())}?:requireOwned(scope,parentOperationId)
                    val displayedVersion=if(reviewDao.operation(displayed.operationId)?.kind=="review3_next")3 else 2
                    publish(scope,readAnyState(scope,displayed,displayedVersion,error=failure.code))
                    failure
                }
            }
        }catch(error:CancellationException){throw error}
         catch(_:Exception){
             observations.invalidate();negotiated.remove(scope.negotiationKey())
             if(api.isCurrent(scope) && mutable.value.owner==scope.owner)mutable.value=mutable.value.copy(supportedChoices=emptySet())
             LearningReply.Failed("PENDING_CONTEXT_CHANGED",false)
         }
    }
    /** Only a fresh dedicated read can confirm non-admission; an existing receipt always wins. */
    private suspend fun observationFailure(scope:LearningCallScope,row:LocalPlaybackRequest,reply:NextObservationReply):LearningReply.Failed {
        negotiated.remove(scope.negotiationKey())
        if(!api.isCurrent(scope)){observations.invalidate();return failedSession()}
        return when(reply) {
            is NextObservationReply.Unavailable->reply.failure
            is NextObservationReply.StoredError->reply.failure
            is NextObservationReply.NotAdmitted->database.withTransaction {
                val latest=requireOwned(scope,UUID.fromString(row.operationId))
                val operation=requireNotNull(reviewDao.operation(row.operationId))
                if(latest.admissionJson!=null || latest.outcomeJson!=null || operation.status=="acknowledged" || operation.receiptJson!=null) {
                    observations.invalidate();LearningReply.Failed("NEXT_OBSERVATION_RECEIPT_CONFLICT",false)
                } else {
                    dao.recordPendingChoiceNotAdmitted(scope.owner,latest)
                    LearningReply.Failed("review_operation_not_admitted",false)
                }
            }
            is NextObservationReply.Found->error("Found observations must be validated before acceptance")
        }
    }
    suspend fun pendingChoiceRetention(scope:LearningCallScope,operationId:UUID):PendingChoiceRetention? {
        if(!api.isCurrent(scope))return null
        val visible=requireOwned(scope,operationId)
        val parentId=decodeBinding(visible.bindingJson).pendingOriginalParentOperationId?.toString()?:visible.operationId
        if(dao.pendingChoiceStopped(scope.owner,parentId))return null
        val marker=if(parentId!=visible.operationId)dao.pendingOriginalInput(scope.owner,parentId,visible.operationId)
            else dao.pendingChoiceInputs(scope.owner).firstOrNull{it.targetId==parentId}
        val parent=requireOwned(scope,UUID.fromString(parentId))
        val currentAnchor=anchorForParent(scope,parent)?:return null
        if(marker==null)return PendingChoiceRetention(currentAnchor,null,PendingChoiceReconciliation.NONE,false)
        val saved=Json.parseToJsonElement(marker.payloadJson).jsonObject
        val original=decodeStoredAnchor(scope,saved)
        require(original.requestHash==currentAnchor.requestHash && original.selectionIntentId==currentAnchor.selectionIntentId)
        val childId=UUID.fromString(saved.getValue("child_operation_id").jsonPrimitive.content)
        val child=dao.intent(childId.toString())
        if(child==null)return PendingChoiceRetention(currentAnchor,childId,PendingChoiceReconciliation.NONE,false)
        require(!child.isolated && child.profileId==scope.owner.profileId.toString() && child.privacyEpoch==scope.owner.privacyEpoch && child.ownerRevision==scope.owner.revision)
        if(child.state in setOf("applied","cancelled"))return null
        val operation=reviewDao.operation(child.operationId)?:return null
        if(operation.status=="isolated")return null
        if(child.admissionJson==null || child.outcomeJson==null || operation.status!="acknowledged")
            return PendingChoiceRetention(currentAnchor,childId,if(child.polls==0 || dao.pendingChoiceNotAdmitted(scope.owner,child))PendingChoiceReconciliation.NONE else PendingChoiceReconciliation.SAME_CHILD_REQUIRED,false)
        require(Json.parseToJsonElement(requireNotNull(operation.receiptJson))==Json.parseToJsonElement(child.admissionJson))
        val result=requireNotNull(response(child));codecs.encode("review3","ReviewNextResponse3",result,WireReview3ReviewNextResponse3.serializer())
        if(result.outcomeProjection.state in setOf("selected","resumed"))return null
        val input=codecs.decode("review3","ReviewNextRequest3",operation.payloadJson,WireReview3ReviewNextRequest3.serializer())
        val held=(result.outcomeProjection.state=="no_candidate" && result.outcomeProjection.noCandidateReason in setOf("original_unavailable","ready_alternative_unavailable")) ||
            (input.selectionOverride?.kind==PendingChoiceKind.CONTINUE.wire && result.outcomeProjection.state in setOf("pending","expired_waiting_explicit") && result.admissionReceipt.selectionIntentId!=null)
        val parentObserved=input.selectionOverride?.expectedPendingOutcomeVersion?.let{parent.outcomeVersion>it}==true
        return PendingChoiceRetention(currentAnchor,childId,if(held)PendingChoiceReconciliation.HELD_PARENT_REFRESH else PendingChoiceReconciliation.SAME_CHILD_REQUIRED,held,parentObserved)
    }

    private fun encodeAnchor(value:PendingOriginalAnchor,child:UUID,kind:PendingChoiceKind)=buildJsonObject {
        put("parent_operation_id",value.parentOperationId.toString());put("child_operation_id",child.toString());put("parent_contract_version",value.parentContractVersion)
        put("selection_intent_id",value.selectionIntentId.toString());put("request_hash",value.requestHash);put("outcome_version",value.outcomeVersion)
        put("target_stream_id",value.targetStreamId.toString());put("parent_kind",value.parentKind.name);put("selection_kind",value.selectionKind)
        put("parent_listening_mode",value.parentListeningMode.name);put("choice_kind",kind.name)
        put("origin_view",value.originView?.let(::JsonPrimitive)?:JsonNull)
        put("requested_article_id",value.requestedArticleId?.toString()?.let(::JsonPrimitive)?:JsonNull)
        put("requested_source_snapshot_id",value.requestedSourceSnapshotId?.toString()?.let(::JsonPrimitive)?:JsonNull)
        put("pending_target",value.pendingTarget?.let{codecs.parse("review3",codecs.encode("review3","PendingTarget3",it,WireReview3PendingTarget3.serializer()))}?:JsonNull)
    }.toString()
    private fun decodeAnchor(owner:OwnerContext,value:JsonObject,parentMode:ListeningMode):PendingOriginalAnchor {
        fun text(key:String)=value.getValue(key).jsonPrimitive.content
        fun optional(key:String)=value.getValue(key).takeUnless{it==JsonNull}?.jsonPrimitive?.content
        val version=value.getValue("parent_contract_version").jsonPrimitive.int
        require(version in setOf(2,3) && text("request_hash").matches(Regex("[a-f0-9]{64}")) && value.getValue("outcome_version").jsonPrimitive.long>=1)
        value["parent_listening_mode"]?.let{require(ListeningMode.valueOf(it.jsonPrimitive.content)==parentMode)}
        return PendingOriginalAnchor(owner,UUID.fromString(text("parent_operation_id")),version,UUID.fromString(text("selection_intent_id")),text("request_hash"),
            value.getValue("outcome_version").jsonPrimitive.long,UUID.fromString(text("target_stream_id")),PlaybackRequestKind.valueOf(text("parent_kind")),
            text("selection_kind"),optional("origin_view"),optional("requested_article_id")?.let(UUID::fromString),optional("requested_source_snapshot_id")?.let(UUID::fromString),
            value.getValue("pending_target").takeUnless{it==JsonNull}?.let{codecs.decode("review3","PendingTarget3",it.toString(),WireReview3PendingTarget3.serializer())},parentMode)
    }

    private fun decodeBinding(source: String): PlaybackRequestBinding = decodePlaybackBinding(source)
}

/** Strict source6 facts join the same snapshot/cache admission used by review2, without wire2 conversion. */
class Source6ReviewNext3StatePort(private val shared:Source6ReviewNextStatePort,private val codecs:IncrementalContractJson):ReviewNext3StatePort {
    constructor(listening:Source6ListeningRepository,codecs:IncrementalContractJson):this(Source6ReviewNextStatePort(listening,codecs),codecs)
    override suspend fun accept(scope:LearningCallScope,request:WireReview3ReviewNextRequest3,response:WireReview3ReviewNextResponse3):Boolean {
        val selection=response.outcomeProjection.selection?:return false
        val checkpoint=response.outcomeProjection.checkpoint?:return false
        val originalSelection=codecs.decodeSource6("PersonalSelectionRecord",codecs.encode("review3","Source6PersonalSelectionRecord",selection,
            WireReview3Source6PersonalSelectionRecord.serializer()),WireSource6PersonalSelectionRecord.serializer())
        val originalCheckpoint=codecs.decodeSource6("PersonalReadCheckpoint",codecs.encode("review3","Source6PersonalReadCheckpoint",checkpoint,
            WireReview3Source6PersonalReadCheckpoint.serializer()),WireSource6PersonalReadCheckpoint.serializer())
        return shared.acceptSource6(scope,request.expectedResumeVersion,originalSelection,originalCheckpoint)
    }
}

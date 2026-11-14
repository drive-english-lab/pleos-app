package io.github.driveenglishlab.pleos.data

import androidx.room.withTransaction
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.util.UUID

/** Preserve schema5 acceptance; attach its exact immutable input to the shared review frontier. */
class LegacyAssistanceAdmissionAdapter(private val database:LearningDatabase,private val api:IncrementalLearningApi,
    private val review:ReviewRepository,private val codecs:IncrementalContractJson,private val sessions:LearningSessionSource) {
    suspend fun persistAccepted(answer:AcceptedAnswer,persist:suspend()->Unit)=database.withTransaction {
        require(answer.lease.task is PracticeContentRef)
        persist()
        val owner=answer.lease.owner
        val row=database.learning().record(owner.profileId.toString(),owner.privacyEpoch,answer.lease.attemptId.toString())?:error("Original accepted answer missing.")
        require(row.kind=="attempt" && row.payloadSchemaVersion==5)
        val input=learningJson.decodeFromString(WirePersonalAttemptInput.serializer(),row.payloadJson)
        require(input.attemptId==answer.lease.attemptId.toString() && input.lessonId==answer.lease.lessonId.toString() && input.answerText==answer.text)
        val canonical=incrementalCanonical(codecs.parse("review",row.payloadJson))
        val mirror=LocalReviewOperation(input.attemptId,owner.profileId.toString(),owner.privacyEpoch,owner.revision,
            "legacy_attempt_admission",input.lessonId,canonical,LearningJson.hash(canonical))
        // The installation is the existing verified observation stream identity.
        val current=scopeFor(owner)?:error("Original session unavailable.")
        val stream=current.session.installationId.toString()
        val fenceId=stableRecordId("legacy-assistance-fence:${input.attemptId}").toString()
        val oldFence=database.review().operation(fenceId)
        val fence=oldFence?.let{codecs.decodeReview("AssistanceFenceRequest",it.payloadJson,WireReviewAssistanceFenceRequest.serializer())}
            ?:WireReviewAssistanceFenceRequest(1,mirror.profileId,mirror.privacyEpoch,fenceId,input.attemptId,stream,
                database.review().frontier(mirror.profileId,mirror.privacyEpoch,stream),mirror.payloadHash)
        require(fence.originalRequestHash==mirror.payloadHash && fence.deviceStreamId==stream)
        val json=incrementalCanonical(codecs.parse("review",codecs.encode("review","AssistanceFenceRequest",fence,WireReviewAssistanceFenceRequest.serializer())))
        database.review().queueAttemptAndFence(owner,mirror,LocalReviewOperation(fence.operationId,mirror.profileId,mirror.privacyEpoch,owner.revision,
            "fence",input.attemptId,json,LearningJson.hash(json)),stream,fence.throughSequence)
    }
    private suspend fun scopeFor(owner:OwnerContext)=sessions.current()?.takeIf{it.owner==owner && api.isCurrent(it)}
    suspend fun admit(scope:LearningCallScope,attemptId:UUID):LearningReply<Unit> {
        if(!api.isCurrent(scope))return LearningReply.Failed("SESSION_CHANGED",false)
        val mirror=database.review().operation(attemptId.toString())?:return LearningReply.Failed("ASSISTANCE_FENCE_MISSING",false)
        require(mirror.kind=="legacy_attempt_admission" && mirror.profileId==scope.owner.profileId.toString() && mirror.privacyEpoch==scope.owner.privacyEpoch && mirror.ownerRevision==scope.owner.revision)
        val original=database.learning().record(mirror.profileId,mirror.privacyEpoch,attemptId.toString())?:return LearningReply.Failed("NOT_ACCEPTED",false)
        require(incrementalCanonical(codecs.parse("review",original.payloadJson))==mirror.payloadJson)
        val row=database.review().operation(stableRecordId("legacy-assistance-fence:$attemptId").toString())?:return LearningReply.Failed("ASSISTANCE_FENCE_MISSING",false)
        val fence=codecs.decodeReview("AssistanceFenceRequest",row.payloadJson,WireReviewAssistanceFenceRequest.serializer())
        require(fence.attemptId==attemptId.toString() && fence.originalRequestHash==mirror.payloadHash && fence.profileId==mirror.profileId && fence.learningEpoch==mirror.privacyEpoch)
        when(val flushed=review.flushPendingObservations(scope,fence.deviceStreamId,fence.throughSequence)){
            is LearningReply.Failed->return flushed
            is LearningReply.Stored->Unit
        }
        return when(val result=review.fence(scope,fence)){
            is LearningReply.Failed->result
            is LearningReply.Stored->if(result.value.state=="ready")LearningReply.Stored(scope,Unit) else LearningReply.Failed("ASSISTANCE_FENCE_NOT_READY",true)
        }
    }
}

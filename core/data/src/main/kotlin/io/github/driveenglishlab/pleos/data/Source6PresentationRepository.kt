package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

/** Reuses the common presentation/output witness models, with source6 durable transport only. */
class Source6PresentationRepository(private val api: IncrementalLearningApi, private val dao: ReviewDao,
    private val codecs: IncrementalContractJson) {
    suspend fun prepare(scope: LearningCallScope, ref: PracticeRef, lesson: UUID?, artifacts: List<SupportArtifact>,
        channel: InteractionChannel, operationId: UUID = UUID.randomUUID(), presentationId: UUID = UUID.randomUUID()): LearningReply<PracticePresentationTicket> {
        val request = WireSource6PersonalPracticePresentationPrepare("6", operationId.toString(), scope.owner.source6Profile(), presentationId.toString(),
            WireSource6CommonClientContext(scope.owner.profileId.toString(), scope.owner.privacyEpoch, scope.owner.revision, scope.owner.presentationToken.toString()),
            LearningJson.wire(channel), ref.source6Wire(), lesson?.toString(), artifacts.map { it.supportRef })
        require(artifacts.isNotEmpty() && artifacts.map{it.supportRef}.distinct().size==artifacts.size)
        artifacts.forEach{require(LearningJson.hash(it.text)==it.textHash)}
        val row = queue(scope, request.operationId, "source6_presentation_prepare", presentationId.toString(),
            codecs.encode("source6", "PersonalPracticePresentationPrepare", request, WireSource6PersonalPracticePresentationPrepare.serializer()))
        return when (val reply = api.call("source6", "/v2/practice-presentations", "post", scope,
            api.payload("source6", "PersonalPracticePresentationPrepare", request, WireSource6PersonalPracticePresentationPrepare.serializer()), WireSource6PersonalPracticePresentationTicket.serializer())) {
            is LearningReply.Failed -> reply
            is LearningReply.Stored -> {
                val value = reply.value
                require(value.operationId == request.operationId && value.presentation.presentationId == request.presentationId &&
                    value.presentation.contentRef == request.contentRef && value.presentation.lessonId == request.lessonId && value.clientContext == request.clientContext)
                val ticket = PracticePresentationTicket(operationId, ProfileContext(scope.owner.profileId, scope.owner.privacyEpoch), scope.owner,
                    record(value.presentation, ref), exposure(value.exposure))
                require(ticket.presentation.artifacts.size==artifacts.size && ticket.presentation.artifacts.toSet() == artifacts.toSet())
                val json = codecs.encode("source6", "PersonalPracticePresentationTicket", value, WireSource6PersonalPracticePresentationTicket.serializer())
                dao.storeSource6(scope.owner, LocalSource6Record(scope.owner.profileId.toString(), scope.owner.privacyEpoch, "presentation_ticket", presentationId.toString(), 1, json, LearningJson.hash(json)))
                dao.acknowledge(scope.owner, row, json)
                LearningReply.Stored(scope, ticket)
            }
        }
    }
    suspend fun record(scope: LearningCallScope, ticket: PracticePresentationTicket, value: PracticePresentationObservation) {
        require(api.isCurrent(scope) && ticket.clientContext == scope.owner && value.clientContext == scope.owner && value.profileContext==ticket.profileContext)
        val request = observation(value)
        queue(scope, request.operationId, "source6_presentation_observation", ticket.presentation.presentationId.toString(),
            codecs.encode("source6", "PersonalPracticePresentationObservation", request, WireSource6PersonalPracticePresentationObservation.serializer()))
    }
    suspend fun send(scope: LearningCallScope, id: UUID, value: PracticePresentationObservation, expectedRef:PracticeRef?=null): LearningReply<PracticePresentationReceipt> {
        val row = dao.operation(value.operationId.toString()) ?: return LearningReply.Failed("OBSERVATION_NOT_SAVED", false)
        require(row.kind == "source6_presentation_observation" && row.targetId == id.toString())
        val request = codecs.decodeSource6("PersonalPracticePresentationObservation", row.payloadJson, WireSource6PersonalPracticePresentationObservation.serializer())
        require(request == observation(value))
        val saved = dao.source6Record(scope.owner.profileId.toString(), scope.owner.privacyEpoch, "presentation_ticket", id.toString())
            ?: return LearningReply.Failed("TICKET_NOT_FOUND", false)
        val ticket = codecs.decodeSource6("PersonalPracticePresentationTicket", saved.payloadJson, WireSource6PersonalPracticePresentationTicket.serializer())
        require(ticket.presentation.presentationId==id.toString() && ticket.clientContext==request.clientContext && ticket.profileContext==request.profileContext)
        expectedRef?.let{require(ticket.presentation.contentRef==it.source6Wire())}
        return when (val reply = api.call("source6", "/v2/practice-presentations/{presentation_id}/observations", "post", scope,
            api.payload("source6", "PersonalPracticePresentationObservation", request, WireSource6PersonalPracticePresentationObservation.serializer()),
            WireSource6PersonalPracticePresentationReceipt.serializer(), mapOf("presentation_id" to id.toString()))) {
            is LearningReply.Failed -> reply
            is LearningReply.Stored -> {
                require(reply.value.presentation.presentationId==id.toString() && reply.value.presentation.contentRef == ticket.presentation.contentRef &&
                    reply.value.presentation.lessonId==ticket.presentation.lessonId && reply.value.presentation.lastObservationId == value.observationId.toString())
                val json = codecs.encode("source6", "PersonalPracticePresentationReceipt", reply.value, WireSource6PersonalPracticePresentationReceipt.serializer())
                dao.acknowledge(scope.owner, row, json)
                val ref = expectedRef ?: ticket.presentation.contentRef.domainPracticeRef()
                LearningReply.Stored(scope, PracticePresentationReceipt(value.operationId, ProfileContext(scope.owner.profileId, scope.owner.privacyEpoch), record(reply.value.presentation, ref), exposure(reply.value.exposure)))
            }
        }
    }
    /** Retry original witnessed deliveries before submitting an answer; never creates a witness. */
    suspend fun flushPending(scope:LearningCallScope,ref:PracticeRef,lesson:UUID):LearningReply<Unit> = flush(scope,ref,lesson)
    suspend fun flushAll(scope:LearningCallScope):LearningReply<Unit> = flush(scope,null,null)
    private suspend fun flush(scope:LearningCallScope,ref:PracticeRef?,lesson:UUID?):LearningReply<Unit> {
        if(!api.isCurrent(scope))return LearningReply.Failed("SESSION_CHANGED",false)
        for(row in dao.pending(scope.owner.profileId.toString(),scope.owner.privacyEpoch,scope.owner.revision).filter{it.kind=="source6_presentation_observation"}){
            val id=row.targetId?.let(UUID::fromString)?:return LearningReply.Failed("INVALID_PRESENTATION_JOURNAL",false)
            val saved=dao.source6Record(scope.owner.profileId.toString(),scope.owner.privacyEpoch,"presentation_ticket",id.toString())
                ?:return LearningReply.Failed("TICKET_NOT_FOUND",false)
            val ticket=codecs.decodeSource6("PersonalPracticePresentationTicket",saved.payloadJson,WireSource6PersonalPracticePresentationTicket.serializer())
            if(lesson!=null && ticket.presentation.lessonId!=lesson.toString() || ref!=null && ticket.presentation.contentRef!=ref.source6Wire())continue
            val request=codecs.decodeSource6("PersonalPracticePresentationObservation",row.payloadJson,WireSource6PersonalPracticePresentationObservation.serializer())
            val original=OwnerContext(UUID.fromString(request.clientContext.profileId),request.clientContext.privacyEpoch,request.clientContext.contextRevision,UUID.fromString(request.clientContext.presentationToken))
            val observation=PracticePresentationObservation(UUID.fromString(request.operationId),ProfileContext(UUID.fromString(request.profileContext.profileId),request.profileContext.privacyEpoch),
                UUID.fromString(request.observationId),original,UUID.fromString(request.outputInstanceId),ObservationOutcome.valueOf(request.outcome.uppercase(Locale.ROOT)),
                ObservationMode.valueOf(request.mode.uppercase(Locale.ROOT)),request.adapterEvidenceRef)
            when(val sent=send(scope,id,observation,ref)){
                is LearningReply.Failed->return sent
                is LearningReply.Stored->Unit
            }
        }
        return LearningReply.Stored(scope,Unit)
    }
    private suspend fun queue(scope: LearningCallScope, id: String, kind: String, target: String, json: String) = dao.queue(scope.owner,
        LocalReviewOperation(id, scope.owner.profileId.toString(), scope.owner.privacyEpoch, scope.owner.revision, kind, target, json, LearningJson.hash(json)))
    private fun observation(value: PracticePresentationObservation) = WireSource6PersonalPracticePresentationObservation("6", value.operationId.toString(),
        WireSource6CommonProfileContext(value.profileContext.profileId.toString(), value.profileContext.privacyEpoch), value.observationId.toString(),
        WireSource6CommonClientContext(value.clientContext.profileId.toString(), value.clientContext.privacyEpoch, value.clientContext.revision, value.clientContext.presentationToken.toString()),
        value.outputInstanceId.toString(), LearningJson.wire(value.outcome), LearningJson.wire(value.mode), value.adapterEvidenceRef)
    private fun record(value: WireSource6PersonalPracticePresentationRecord, ref: PracticeRef) = PracticePresentationRecord(UUID.fromString(value.presentationId), ref,
        value.lessonId?.let(UUID::fromString), InteractionChannel.valueOf(value.interactionChannel.uppercase(Locale.ROOT)), value.artifacts.map { item ->
            SupportArtifact(item.supportRef, item.kind?.let { HelpKind.valueOf(it.name) }, SupportRole.valueOf(item.role.uppercase(Locale.ROOT)), SupportLanguage.valueOf(item.language.uppercase(Locale.ROOT)),
                item.text, item.textHash, SupportOrigin.valueOf(item.origin.uppercase(Locale.ROOT)), item.feedbackAttemptId?.let(UUID::fromString))
        }, value.issuedSequence, OffsetDateTime.parse(value.preparedAt).toInstant(), PresentationStatus.valueOf(value.status.uppercase(Locale.ROOT)), value.lastObservationId?.let(UUID::fromString))
    private fun exposure(value: WireSource6CommonQuestionExampleExposure) = QuestionExampleExposure(BasicQuestionRef(UUID.fromString(value.questionRef.articleId), UUID.fromString(value.questionRef.basicQuestionVersionId)),
        ExposureStatus.valueOf(value.status.uppercase(Locale.ROOT)), ExposureBasis.valueOf(value.basis.uppercase(Locale.ROOT)), value.exposureVersion, value.coverageVersion, value.confirmedPresentationId?.let(UUID::fromString))
}

fun WireSource6PreparedContentRef6.domainRef() = PreparedContentRef(UUID.fromString(articleId), UUID.fromString(sourceSnapshotId), sourceHash,
    UUID.fromString(materialGenerationId), UUID.fromString(mainComponentId), UUID.fromString(mainScriptId), mainTextHash, ListeningMode.valueOf(listeningMode.uppercase(Locale.ROOT)))
fun WireSource6PreparedPracticeRef6.domainRef() = PreparedPracticeRef(PreparedContentRef(UUID.fromString(articleId), UUID.fromString(sourceSnapshotId), sourceHash,
    UUID.fromString(materialGenerationId), UUID.fromString(mainComponentId), UUID.fromString(mainScriptId), mainTextHash, ListeningMode.valueOf(listeningMode.uppercase(Locale.ROOT))),
    UUID.fromString(practiceComponentId), UUID.fromString(basicQuestionVersionId), taskRevision)

/** Exact tagged source adapter; a legacy segment run retains its original bundle reference. */
fun SegmentContentRef.source6Wire(): WireSource6ContentRef6 = when (this) {
    is PreparedContentRef -> this.source6Wire()
    is ContentRef -> WireSource6LegacyContentRef6("legacy_bundle", WireSource6LegacyContentRef5(articleId.toString(), bundleId.toString(),
        sourceSnapshotId.toString(), listeningMode.name.lowercase(Locale.ROOT), listeningScriptId.toString()))
}
fun WireSource6ContentRef6.domainSegmentRef(): SegmentContentRef = when (this) {
    is WireSource6PreparedContentRef6 -> domainRef()
    is WireSource6LegacyContentRef6 -> ContentRef(UUID.fromString(legacyRef.articleId), UUID.fromString(legacyRef.bundleId), UUID.fromString(legacyRef.sourceSnapshotId),
        UUID.fromString(legacyRef.listeningScriptId), ListeningMode.valueOf(legacyRef.listeningMode.uppercase(Locale.ROOT)))
}

/** Exact source6 practice union. Legacy values retain their actual task/bundle identities. */
fun WireSource6PracticeContentRef6.domainPracticeRef():PracticeRef=when(this){
    is WireSource6PreparedPracticeRef6->domainRef()
    is WireSource6LegacyPracticeRef6->PracticeContentRef(ContentRef(UUID.fromString(legacyRef.articleId),UUID.fromString(legacyRef.bundleId),
        UUID.fromString(legacyRef.sourceSnapshotId),UUID.fromString(legacyRef.listeningScriptId),ListeningMode.valueOf(legacyRef.listeningMode.uppercase(Locale.ROOT))),
        UUID.fromString(legacyRef.taskId),UUID.fromString(legacyRef.basicQuestionVersionId))
}

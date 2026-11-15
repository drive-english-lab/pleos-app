package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.time.OffsetDateTime
import java.util.UUID
import java.util.Locale
import kotlinx.serialization.json.*

/** A ticket is server registered and locally durable before any potentially exposing output. */
class PresentationRepository(private val api: LearningApi, private val database: LearningDatabase,
    private val current: suspend (OwnerContext) -> Boolean) {
    suspend fun prepare(scope: LearningCallScope, ref: PracticeContentRef, lessonId: UUID?, artifacts: List<SupportArtifact>,
        channel: InteractionChannel, operationId: UUID = UUID.randomUUID(), presentationId: UUID = UUID.randomUUID()): LearningReply<PracticePresentationTicket> {
        require(artifacts.size in 1..4 && artifacts.map { it.supportRef }.distinct().size == artifacts.size)
        if (!current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
        val request = PracticePresentationPrepare(operationId, ProfileContext(scope.owner.profileId, scope.owner.privacyEpoch),
            presentationId, scope.owner, channel, ref, lessonId, artifacts.map { it.supportRef }.toSet())
        return when (val reply = api.preparePracticePresentation(scope, request.wire())) {
            is LearningReply.Failed -> reply
            is LearningReply.Stored -> {
                val ticket = reply.value.domain()
                require(ticket.operationId == operationId && ticket.clientContext == scope.owner &&
                    ticket.presentation.presentationId == presentationId && ticket.presentation.contentRef == ref &&
                    ticket.presentation.lessonId == lessonId && ticket.presentation.interactionChannel == channel &&
                    ticket.presentation.artifacts.toSet() == artifacts.toSet())
                if (!current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
                database.learning().persistTicket(scope.owner, ticket)
                LearningReply.Stored(scope, ticket)
            }
        }
    }

    suspend fun record(scope: LearningCallScope, ticket: PracticePresentationTicket, observation: PracticePresentationObservation) {
        require(ticket.clientContext == observation.clientContext && observation.clientContext == scope.owner)
        database.learning().persistObservation(scope.owner, ticket.presentation.presentationId, observation)
    }

    suspend fun flush(scope: LearningCallScope): LearningReply<Unit> {
        while (true) {
            val rows = database.learning().pending(scope.owner.profileId.toString(), scope.owner.privacyEpoch)
                .filter { it.transport == "presentation_observation" }
            if (rows.isEmpty()) return LearningReply.Stored(scope, Unit)
            for (row in rows) {
                if (row.ownerRevision != scope.owner.revision || LearningJson.hash(row.payloadJson) != row.payloadHash)
                    return LearningReply.Failed("OWNER_CHANGED", false)
                val observation = learningJson.decodeFromString(WirePersonalPracticePresentationObservation.serializer(), row.payloadJson).domain()
                val ticket = database.learning().ticket(row.profileId, row.privacyEpoch, row.recordId)
                    ?: return LearningReply.Failed("TICKET_NOT_FOUND", false)
                require(ticket.contextJson == LearningJson.context(observation.clientContext).toString())
                when (val result = send(scope, UUID.fromString(row.recordId), observation)) {
                    is LearningReply.Failed -> return result
                    is LearningReply.Stored -> Unit
                }
            }
        }
    }

    suspend fun send(scope: LearningCallScope, presentationId: UUID, observation: PracticePresentationObservation): LearningReply<PracticePresentationReceipt> {
        if (!current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
        val saved = database.learning().observation(observation.observationId.toString())
            ?: return LearningReply.Failed("OBSERVATION_NOT_SAVED", false)
        require(saved.presentationId == presentationId.toString() && saved.payloadJson == LearningJson.observation(observation).toString())
        return when (val reply = api.observePracticePresentation(scope, observation.wire(), presentationId)) {
            is LearningReply.Failed -> reply
            is LearningReply.Stored -> {
                val receipt = reply.value.domain()
                require(receipt.operationId == observation.operationId && receipt.presentation.presentationId == presentationId &&
                    receipt.presentation.lastObservationId == observation.observationId)
                database.learning().acknowledgePresentation(scope.owner, observation.observationId, receipt)
                LearningReply.Stored(scope, receipt)
            }
        }
    }
}

internal fun WireCommonQuestionExampleExposure.domain() = QuestionExampleExposure(
    BasicQuestionRef(UUID.fromString(questionRef.articleId), UUID.fromString(questionRef.basicQuestionVersionId)),
    ExposureStatus.valueOf(status.uppercase(Locale.ROOT)), ExposureBasis.valueOf(basis.uppercase(Locale.ROOT)),
    exposureVersion, coverageVersion, confirmedPresentationId?.let(UUID::fromString))
internal fun WirePersonalPracticePresentationRecord.domain() = PracticePresentationRecord(UUID.fromString(presentationId), contentRef.domainPractice(),
    lessonId?.let(UUID::fromString), InteractionChannel.valueOf(interactionChannel.uppercase(Locale.ROOT)), artifacts.map { it.domain() },
    issuedSequence, OffsetDateTime.parse(preparedAt).toInstant(), PresentationStatus.valueOf(status.uppercase(Locale.ROOT)), lastObservationId?.let(UUID::fromString))
internal fun WirePersonalPracticePresentationTicket.domain() = PracticePresentationTicket(UUID.fromString(operationId),
    ProfileContext(UUID.fromString(profileContext.profileId), profileContext.privacyEpoch),
    OwnerContext(UUID.fromString(clientContext.profileId), clientContext.privacyEpoch, clientContext.contextRevision, UUID.fromString(clientContext.presentationToken)),
    presentation.domain(), exposure.domain())
internal fun WirePersonalPracticePresentationReceipt.domain() = PracticePresentationReceipt(UUID.fromString(operationId),
    ProfileContext(UUID.fromString(profileContext.profileId), profileContext.privacyEpoch), presentation.domain(), exposure.domain())
internal fun WireCommonPracticeContentRef.domainPractice() = PracticeContentRef(ContentRef(UUID.fromString(articleId), UUID.fromString(bundleId),
    UUID.fromString(sourceSnapshotId), UUID.fromString(listeningScriptId), ListeningMode.valueOf(listeningMode.uppercase(Locale.ROOT))),
    UUID.fromString(taskId), UUID.fromString(basicQuestionVersionId))

internal fun WirePersonalPracticePresentationObservation.domain() = PracticePresentationObservation(UUID.fromString(operationId),
    ProfileContext(UUID.fromString(profileContext.profileId), profileContext.privacyEpoch), UUID.fromString(observationId),
    OwnerContext(UUID.fromString(clientContext.profileId), clientContext.privacyEpoch, clientContext.contextRevision, UUID.fromString(clientContext.presentationToken)),
    UUID.fromString(outputInstanceId), ObservationOutcome.valueOf(outcome.uppercase(Locale.ROOT)), ObservationMode.valueOf(mode.uppercase(Locale.ROOT)), adapterEvidenceRef)

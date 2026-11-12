package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.Locale
import java.util.UUID

internal fun WireCommonSupportArtifact.domain(): SupportArtifact {
    require(LearningJson.hash(text) == textHash)
    return SupportArtifact(supportRef, kind?.let { HelpKind.valueOf(it.name) }, SupportRole.valueOf(role.uppercase(Locale.ROOT)),
        SupportLanguage.valueOf(language.uppercase(Locale.ROOT)), text, textHash, SupportOrigin.valueOf(origin.uppercase(Locale.ROOT)),
        feedbackAttemptId?.let(UUID::fromString))
}
internal fun WireCommonPracticeSupportManifest.domain() = PracticeSupportManifest(UUID.fromString(taskId),
    BasicQuestionRef(UUID.fromString(questionRef.articleId), UUID.fromString(questionRef.basicQuestionVersionId)), supports.map { it.domain() })
internal fun Capture.wire(): WireCommonCapture = learningJson.decodeFromString(WireCommonCapture.serializer(), LearningJson.capture(this).toString())
internal fun DialogueProgressEvent.wire(): WirePersonalDialogueProgressEvent = learningJson.decodeFromString(WirePersonalDialogueProgressEvent.serializer(), LearningJson.dialogueEvent(this).toString())
internal fun PracticePresentationObservation.wire(): WirePersonalPracticePresentationObservation = learningJson.decodeFromString(WirePersonalPracticePresentationObservation.serializer(), LearningJson.observation(this).toString())
internal fun PracticePresentationPrepare.wire() = WirePersonalPracticePresentationPrepare(schemaVersion = "5", operationId = operationId.toString(),
    profileContext = WireCommonProfileContext(profileId = profileContext.profileId.toString(), privacyEpoch = profileContext.privacyEpoch),
    presentationId = presentationId.toString(), clientContext = WireCommonClientContext(profileId = clientContext.profileId.toString(),
        privacyEpoch = clientContext.privacyEpoch, contextRevision = clientContext.revision, presentationToken = clientContext.presentationToken.toString()),
    interactionChannel = LearningJson.wire(interactionChannel), contentRef = learningJson.decodeFromString(WireCommonPracticeContentRef.serializer(), LearningJson.practice(contentRef).toString()),
    lessonId = lessonId?.toString(), supportRefs = supportRefs.sorted())
internal fun FormatChangeRequest.wire() = WirePersonalFormatChangeRequest(schemaVersion = "5", operationId = operationId.toString(),
    profileContext = WireCommonProfileContext(profileId = profileContext.profileId.toString(), privacyEpoch = profileContext.privacyEpoch),
    streamId = streamId.toString(), expectedStreamVersion = expectedStreamVersion,
    expectedHeadSelectionId = expectedHeadSelectionId.toString(), sourceSelectionId = sourceSelectionId.toString(),
    sourceCheckpointId = sourceCheckpointId.toString(), expectedCheckpointVersion = expectedCheckpointVersion,
    checkpointId = checkpointId.toString(), targetListeningMode = LearningJson.wire(targetListeningMode))

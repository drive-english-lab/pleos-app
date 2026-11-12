// Generated typed methods; responses are stored data, never output commands.
package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.util.UUID
import kotlinx.serialization.json.*

suspend fun LearningApi.bootstrapProfile(scope: LearningCallScope, request: WirePersonalBootstrapRequest): LearningReply<WirePersonalProfileView> =
    call("personal-data.openapi.json", "/v1/profile/bootstrap", "post", scope, learningJson.encodeToJsonElement(WirePersonalBootstrapRequest.serializer(), request),
        WirePersonalProfileView.serializer(), mapOf(), mapOf())

suspend fun LearningApi.readProfile(scope: LearningCallScope): LearningReply<WirePersonalProfileView> =
    call("personal-data.openapi.json", "/v1/profile", "get", scope, null,
        WirePersonalProfileView.serializer(), mapOf(), mapOf())

suspend fun LearningApi.updatePreferences(scope: LearningCallScope, request: WirePersonalSettingsPatch): LearningReply<WirePersonalProfileView> =
    call("personal-data.openapi.json", "/v1/profile/preferences", "patch", scope, learningJson.encodeToJsonElement(WirePersonalSettingsPatch.serializer(), request),
        WirePersonalProfileView.serializer(), mapOf(), mapOf())

suspend fun LearningApi.applySyncBatch(scope: LearningCallScope, request: WirePersonalSyncRequest): LearningReply<WirePersonalSyncReceipt> =
    call("personal-data.openapi.json", "/v1/profile/sync", "post", scope, learningJson.encodeToJsonElement(WirePersonalSyncRequest.serializer(), request),
        WirePersonalSyncReceipt.serializer(), mapOf(), mapOf())

suspend fun LearningApi.readSyncPage(scope: LearningCallScope, clientPrivacyEpoch: Long, cursor: String? = null, limit: Long? = null): LearningReply<WirePersonalSyncPage> =
    call("personal-data.openapi.json", "/v1/profile/sync", "get", scope, null,
        WirePersonalSyncPage.serializer(), mapOf(), mapOf("client_privacy_epoch" to clientPrivacyEpoch.toString(), "cursor" to cursor, "limit" to limit?.toString()))

suspend fun LearningApi.prepareMerge(scope: LearningCallScope, request: WirePersonalMergePrepare): LearningReply<WirePersonalMergeIntent> =
    call("personal-data.openapi.json", "/v1/profile/merges/prepare", "post", scope, learningJson.encodeToJsonElement(WirePersonalMergePrepare.serializer(), request),
        WirePersonalMergeIntent.serializer(), mapOf(), mapOf())

suspend fun LearningApi.completeMerge(scope: LearningCallScope, request: WirePersonalMergeComplete): LearningReply<WirePersonalMergeReceipt> =
    call("personal-data.openapi.json", "/v1/profile/merges/complete", "post", scope, learningJson.encodeToJsonElement(WirePersonalMergeComplete.serializer(), request),
        WirePersonalMergeReceipt.serializer(), mapOf(), mapOf())

suspend fun LearningApi.deleteLearningHistory(scope: LearningCallScope, request: WirePersonalDeletionRequest): LearningReply<WirePersonalDeletionReceipt> =
    call("personal-data.openapi.json", "/v1/profile/history-deletions", "post", scope, learningJson.encodeToJsonElement(WirePersonalDeletionRequest.serializer(), request),
        WirePersonalDeletionReceipt.serializer(), mapOf(), mapOf())

suspend fun LearningApi.logoutCurrentSession(scope: LearningCallScope, request: WirePersonalLogoutRequest): LearningReply<WirePersonalLogoutReceipt> =
    call("personal-data.openapi.json", "/v1/profile/sessions/logout", "post", scope, learningJson.encodeToJsonElement(WirePersonalLogoutRequest.serializer(), request),
        WirePersonalLogoutReceipt.serializer(), mapOf(), mapOf())

suspend fun LearningApi.issueAudioAccess(scope: LearningCallScope, request: WirePersonalAudioAccessRequest): LearningReply<WirePersonalAudioAccessResponse> =
    call("personal-data.openapi.json", "/v1/content/audio-access", "post", scope, learningJson.encodeToJsonElement(WirePersonalAudioAccessRequest.serializer(), request),
        WirePersonalAudioAccessResponse.serializer(), mapOf(), mapOf())

suspend fun LearningApi.selectActiveResume(scope: LearningCallScope, request: WirePersonalResumeSelection): LearningReply<WirePersonalProfileView> =
    call("personal-data.openapi.json", "/v1/profile/resume", "patch", scope, learningJson.encodeToJsonElement(WirePersonalResumeSelection.serializer(), request),
        WirePersonalProfileView.serializer(), mapOf(), mapOf())

suspend fun LearningApi.selectNextArticle(scope: LearningCallScope, request: WirePersonalNextArticleRequest): LearningReply<WirePersonalNextArticleResponse> =
    call("personal-data.openapi.json", "/v1/listening/next", "post", scope, learningJson.encodeToJsonElement(WirePersonalNextArticleRequest.serializer(), request),
        WirePersonalNextArticleResponse.serializer(), mapOf(), mapOf())

suspend fun LearningApi.startPlaybackRun(scope: LearningCallScope, request: WirePersonalPlaybackRunStart): LearningReply<WirePersonalPlaybackRunReceipt> =
    call("personal-data.openapi.json", "/v1/playback-runs", "post", scope, learningJson.encodeToJsonElement(WirePersonalPlaybackRunStart.serializer(), request),
        WirePersonalPlaybackRunReceipt.serializer(), mapOf(), mapOf())

suspend fun LearningApi.declineLessonRetry(scope: LearningCallScope, request: WirePersonalLessonFinishRequest, lessonId: UUID): LearningReply<WirePersonalLessonCommandReceipt> =
    call("personal-data.openapi.json", "/v1/lessons/{lesson_id}/finish", "post", scope, learningJson.encodeToJsonElement(WirePersonalLessonFinishRequest.serializer(), request),
        WirePersonalLessonCommandReceipt.serializer(), mapOf("lesson_id" to lessonId.toString()), mapOf())

suspend fun LearningApi.acknowledgeFeedbackPresentation(scope: LearningCallScope, request: WirePersonalLessonPresentationRequest, lessonId: UUID): LearningReply<WirePersonalLessonCommandReceipt> =
    call("personal-data.openapi.json", "/v1/lessons/{lesson_id}/feedback-presentations", "post", scope, learningJson.encodeToJsonElement(WirePersonalLessonPresentationRequest.serializer(), request),
        WirePersonalLessonCommandReceipt.serializer(), mapOf("lesson_id" to lessonId.toString()), mapOf())

suspend fun LearningApi.changeCurrentArticleFormat(scope: LearningCallScope, request: WirePersonalFormatChangeRequest): LearningReply<WirePersonalFormatChangeResponse> =
    call("personal-data.openapi.json", "/v1/listening/format-changes", "post", scope, learningJson.encodeToJsonElement(WirePersonalFormatChangeRequest.serializer(), request),
        WirePersonalFormatChangeResponse.serializer(), mapOf(), mapOf())

suspend fun LearningApi.preparePracticePresentation(scope: LearningCallScope, request: WirePersonalPracticePresentationPrepare): LearningReply<WirePersonalPracticePresentationTicket> =
    call("personal-data.openapi.json", "/v1/practice-presentations", "post", scope, learningJson.encodeToJsonElement(WirePersonalPracticePresentationPrepare.serializer(), request),
        WirePersonalPracticePresentationTicket.serializer(), mapOf(), mapOf())

suspend fun LearningApi.observePracticePresentation(scope: LearningCallScope, request: WirePersonalPracticePresentationObservation, presentationId: UUID): LearningReply<WirePersonalPracticePresentationReceipt> =
    call("personal-data.openapi.json", "/v1/practice-presentations/{presentation_id}/observations", "post", scope, learningJson.encodeToJsonElement(WirePersonalPracticePresentationObservation.serializer(), request),
        WirePersonalPracticePresentationReceipt.serializer(), mapOf("presentation_id" to presentationId.toString()), mapOf())

suspend fun LearningApi.answerArticleQuestion(scope: LearningCallScope, request: WireInferenceQARequest): LearningReply<WireInferenceQAResponse> =
    call("inference.openapi.json", "/v1/article-questions", "post", scope, learningJson.encodeToJsonElement(WireInferenceQARequest.serializer(), request),
        WireInferenceQAResponse.serializer(), mapOf(), mapOf())

suspend fun LearningApi.evaluateSpeakingAttempt(scope: LearningCallScope, request: WireInferenceFeedbackRequest): LearningReply<WireInferenceFeedbackResponse> =
    call("inference.openapi.json", "/v1/attempt-feedback", "post", scope, learningJson.encodeToJsonElement(WireInferenceFeedbackRequest.serializer(), request),
        WireInferenceFeedbackResponse.serializer(), mapOf(), mapOf())

suspend fun LearningApi.readAttemptResult(scope: LearningCallScope, attemptId: UUID): LearningReply<WireInferenceAttemptResult> =
    call("inference.openapi.json", "/v1/attempts/{attempt_id}", "get", scope, null,
        WireInferenceAttemptResult.serializer(), mapOf("attempt_id" to attemptId.toString()), mapOf())

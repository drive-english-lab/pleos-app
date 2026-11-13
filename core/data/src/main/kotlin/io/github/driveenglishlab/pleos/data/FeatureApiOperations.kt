// Generated typed methods; responses are stored data, never output commands.
package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.util.UUID
import kotlinx.serialization.json.*

suspend fun LearningProgressApi.readDailyListeningPreference(scope: LearningCallScope): LearningReply<WireProgressDailyPreferenceView> =
    client.call("learning-progress.openapi.json", "/v1/profile/daily-listening-preference", "get", scope, null,
        WireProgressDailyPreferenceView.serializer(), mapOf(), mapOf())

suspend fun LearningProgressApi.setDailyListeningPreference(scope: LearningCallScope, request: WireProgressDailyPreferenceRequest): LearningReply<WireProgressDailyPreferenceReceipt> =
    client.call("learning-progress.openapi.json", "/v1/profile/daily-listening-preference/choices", "post", scope, featureJson.encodeToJsonElement(WireProgressDailyPreferenceRequest.serializer(), request),
        WireProgressDailyPreferenceReceipt.serializer(), mapOf(), mapOf())

suspend fun LearningProgressApi.listSpeakingRecords(scope: LearningCallScope, clientPrivacyEpoch: Long, visibility: String? = null, cursor: String? = null, limit: Long? = null): LearningReply<WireProgressSpeakingRecordPage> =
    client.call("learning-progress.openapi.json", "/v1/profile/speaking-records", "get", scope, null,
        WireProgressSpeakingRecordPage.serializer(), mapOf(), mapOf("client_privacy_epoch" to clientPrivacyEpoch.toString(), "visibility" to visibility, "cursor" to cursor, "limit" to limit?.toString()))

suspend fun LearningProgressApi.readSpeakingRecord(scope: LearningCallScope, lessonId: UUID, clientPrivacyEpoch: Long): LearningReply<WireProgressSpeakingRecordView> =
    client.call("learning-progress.openapi.json", "/v1/profile/speaking-records/{lesson_id}", "get", scope, null,
        WireProgressSpeakingRecordView.serializer(), mapOf("lesson_id" to lessonId.toString()), mapOf("client_privacy_epoch" to clientPrivacyEpoch.toString()))

suspend fun LearningProgressApi.setSpeakingRecordVisibility(scope: LearningCallScope, request: WireProgressVisibilityRequest, lessonId: UUID): LearningReply<WireProgressVisibilityReceipt> =
    client.call("learning-progress.openapi.json", "/v1/profile/speaking-records/{lesson_id}/visibility", "patch", scope, featureJson.encodeToJsonElement(WireProgressVisibilityRequest.serializer(), request),
        WireProgressVisibilityReceipt.serializer(), mapOf("lesson_id" to lessonId.toString()), mapOf())

suspend fun LearningProgressApi.recordAttemptPracticeTime(scope: LearningCallScope, request: WireProgressPracticeTimeRequest, attemptId: UUID): LearningReply<WireProgressPracticeTimeReceipt> =
    client.call("learning-progress.openapi.json", "/v1/attempts/{attempt_id}/practice-time", "post", scope, featureJson.encodeToJsonElement(WireProgressPracticeTimeRequest.serializer(), request),
        WireProgressPracticeTimeReceipt.serializer(), mapOf("attempt_id" to attemptId.toString()), mapOf())

suspend fun AiLearningApi.readLearningPreference(scope: LearningCallScope): LearningReply<WireAiLearningLearningPreferenceView> =
    client.call("ai-learning.openapi.json", "/v1/profile/learning-preference", "get", scope, null,
        WireAiLearningLearningPreferenceView.serializer(), mapOf(), mapOf())

suspend fun AiLearningApi.setLearningPreference(scope: LearningCallScope, request: WireAiLearningLearningPreferenceRequest): LearningReply<WireAiLearningLearningPreferenceReceipt> =
    client.call("ai-learning.openapi.json", "/v1/profile/learning-preference/choices", "post", scope, featureJson.encodeToJsonElement(WireAiLearningLearningPreferenceRequest.serializer(), request),
        WireAiLearningLearningPreferenceReceipt.serializer(), mapOf(), mapOf())

suspend fun AiLearningApi.listLearningMaterialCandidates(scope: LearningCallScope, articleId: String, sourceSnapshotId: String, listeningMode: String, targetBand: String): LearningReply<WireAiLearningLearningMaterialCandidatePage> =
    client.call("ai-learning.openapi.json", "/v1/learning/material-candidates", "get", scope, null,
        WireAiLearningLearningMaterialCandidatePage.serializer(), mapOf(), mapOf("article_id" to articleId, "source_snapshot_id" to sourceSnapshotId, "listening_mode" to listeningMode, "target_band" to targetBand))

suspend fun AiLearningApi.readSelectionLearningMaterial(scope: LearningCallScope, clientPrivacyEpoch: Long, selectionId: UUID): LearningReply<WireAiLearningSelectionLearningMaterialView> =
    client.call("ai-learning.openapi.json", "/v1/selections/{selection_id}/learning-material", "get", scope, null,
        WireAiLearningSelectionLearningMaterialView.serializer(), mapOf("selection_id" to selectionId.toString()), mapOf("client_privacy_epoch" to clientPrivacyEpoch.toString()))

suspend fun AiLearningApi.readLearningRecommendation(scope: LearningCallScope, clientPrivacyEpoch: Long): LearningReply<WireAiLearningLearningRecommendationView> =
    client.call("ai-learning.openapi.json", "/v1/profile/learning-recommendation", "get", scope, null,
        WireAiLearningLearningRecommendationView.serializer(), mapOf(), mapOf("client_privacy_epoch" to clientPrivacyEpoch.toString()))

suspend fun AiLearningApi.actOnLearningRecommendation(scope: LearningCallScope, request: WireAiLearningLearningRecommendationActionRequest): LearningReply<WireAiLearningLearningRecommendationActionReceipt> =
    client.call("ai-learning.openapi.json", "/v1/profile/learning-recommendation/actions", "post", scope, featureJson.encodeToJsonElement(WireAiLearningLearningRecommendationActionRequest.serializer(), request),
        WireAiLearningLearningRecommendationActionReceipt.serializer(), mapOf(), mapOf())

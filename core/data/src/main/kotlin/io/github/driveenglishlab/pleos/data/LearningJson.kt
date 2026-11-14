package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Explicit required/null encoding. Read-only projections have their own encoders. */
internal object LearningJson {
    fun wire(value: Enum<*>) = value.name.lowercase(Locale.ROOT)
    fun nullable(value: Any?) = value ?: JSONObject.NULL
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun content(value: ContentRef) = JSONObject().put("article_id", value.articleId.toString())
        .put("bundle_id", value.bundleId.toString()).put("source_snapshot_id", value.sourceSnapshotId.toString())
        .put("listening_mode", wire(value.listeningMode)).put("listening_script_id", value.listeningScriptId.toString())
    fun practice(value: PracticeRef): JSONObject {
        require(value is PracticeContentRef) { "Prepared source requires source6 serialization" }
        return content(value.content).put("task_id", value.taskId.toString())
            .put("basic_question_version_id", value.basicQuestionVersionId.toString())
    }
    fun question(value: BasicQuestionRef) = JSONObject().put("article_id", value.articleId.toString())
        .put("basic_question_version_id", value.basicQuestionVersionId.toString())
    fun profile(value: ProfileContext) = JSONObject().put("profile_id", value.profileId.toString()).put("privacy_epoch", value.privacyEpoch)
    fun context(value: OwnerContext) = profile(ProfileContext(value.profileId, value.privacyEpoch))
        .put("context_revision", value.revision).put("presentation_token", value.presentationToken.toString())
    fun capture(value: Capture) = JSONObject().put("path", wire(value.path)).put("form_fidelity", wire(value.formFidelity))
        .put("uncertain_spans", JSONArray(value.uncertainSpans)).put("spoken_language", wire(value.spokenLanguage))
        .put("english_evidence", wire(value.englishEvidence)).put("language_evidence_ref", nullable(value.languageEvidenceRef))
    fun help(json: JSONObject, value: HelpSnapshot): JSONObject = json
        .put("help_received", JSONArray(value.kinds.map(::wire).sorted())).put("support_refs", JSONArray(value.refs.sorted()))
    fun progress(value: DialogueProgress) = JSONObject().put("hint_method_announced", value.hintMethodAnnounced)
    fun lesson(value: LessonInput) = help(JSONObject().put("lesson_id", value.lessonId.toString())
        .put("content_ref", practice(value.contentRef)).put("phase", wire(value.phase))
        .put("return_checkpoint_id", nullable(value.returnCheckpointId?.toString()))
        .put("dialogue_progress", progress(value.dialogueProgress)), value.helpReceived)
    fun attempt(value: AttemptInput) = help(JSONObject().put("attempt_id", value.attemptId.toString())
        .put("lesson_id", value.lessonId.toString()).put("content_ref", practice(value.contentRef))
        .put("turn", wire(value.turn)).put("previous_attempt_id", nullable(value.previousAttemptId?.toString()))
        .put("answer_text", value.answerText).put("capture", capture(value.capture)), value.helpReceived)
    fun support(value: SupportArtifact) = JSONObject().put("support_ref", value.supportRef)
        .put("kind", nullable(value.kind?.let(::wire))).put("role", wire(value.role)).put("language", wire(value.language))
        .put("text", value.text).put("text_hash", value.textHash).put("origin", wire(value.origin))
        .put("feedback_attempt_id", nullable(value.feedbackAttemptId?.toString()))
    fun exposure(value: QuestionExampleExposure) = JSONObject().put("question_ref", question(value.questionRef))
        .put("status", wire(value.status)).put("basis", wire(value.basis)).put("exposure_version", value.exposureVersion)
        .put("coverage_version", value.coverageVersion).put("confirmed_presentation_id", nullable(value.confirmedPresentationId?.toString()))
    fun presentation(value: PracticePresentationRecord) = JSONObject().put("presentation_id", value.presentationId.toString())
        .put("content_ref", practice(value.contentRef)).put("lesson_id", nullable(value.lessonId?.toString()))
        .put("interaction_channel", wire(value.interactionChannel)).put("artifacts", JSONArray(value.artifacts.map(::support)))
        .put("issued_sequence", value.issuedSequence).put("prepared_at", value.preparedAt.toString())
        .put("status", wire(value.status)).put("last_observation_id", nullable(value.lastObservationId?.toString()))
    fun ticket(value: PracticePresentationTicket) = JSONObject().put("schema_version", "5")
        .put("operation_id", value.operationId.toString()).put("profile_context", profile(value.profileContext))
        .put("client_context", context(value.clientContext)).put("presentation", presentation(value.presentation))
        .put("exposure", exposure(value.exposure))
    fun observation(value: PracticePresentationObservation) = JSONObject().put("schema_version", "5")
        .put("operation_id", value.operationId.toString()).put("profile_context", profile(value.profileContext))
        .put("observation_id", value.observationId.toString()).put("client_context", context(value.clientContext))
        .put("output_instance_id", value.outputInstanceId.toString()).put("outcome", wire(value.outcome))
        .put("mode", wire(value.mode)).put("adapter_evidence_ref", value.adapterEvidenceRef)
    fun dialogueEvent(value: DialogueProgressEvent) = JSONObject().put("event_id", value.eventId.toString())
        .put("kind", "dialogue_progress").put("expected_record_version", JSONObject.NULL)
        .put("payload", JSONObject().put("lesson_id", value.observation.lessonId.toString())
            .put("hint_method_announced", value.observation.hintMethodAnnounced)
            .put("output_instance_id", value.observation.outputInstanceId.toString())
            .put("adapter_evidence_ref", value.observation.adapterEvidenceRef))
}

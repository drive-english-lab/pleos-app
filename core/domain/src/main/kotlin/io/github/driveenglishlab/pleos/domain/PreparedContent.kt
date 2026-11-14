package io.github.driveenglishlab.pleos.domain

import java.util.UUID

/** Actual prepared references. No bundle, audio asset, duration or legacy task is synthesized. */
data class PreparedContentRef(override val articleId: UUID, override val sourceSnapshotId: UUID, val sourceHash: String,
    val materialGenerationId: UUID, val mainComponentId: UUID, val mainScriptId: UUID, val mainTextHash: String,
    override val listeningMode: ListeningMode) : SegmentContentRef {
    init { require(sourceHash.matches(Regex("[a-f0-9]{64}")) && mainTextHash.matches(Regex("[a-f0-9]{64}"))) }
}

data class PreparedPracticeRef(val content: PreparedContentRef, val practiceComponentId: UUID,
    override val basicQuestionVersionId: UUID, val taskRevision: String, override val supportTaskIdentity: UUID? = null) : PracticeRef {
    override val articleId get() = content.articleId
    override val taskIdentity get() = practiceComponentId
    init { require(taskRevision.isNotBlank() && taskRevision.length <= 128) }
}

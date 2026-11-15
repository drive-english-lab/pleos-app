package io.github.driveenglishlab.pleos.domain

import java.net.URI
import java.util.UUID

/** Fixed sample material. Excerpts are explicit samples, never complete news/listening records. */
data class DemoExcerpt(val text: String, val textHash: String, val language: SegmentLanguage, val segments: SegmentManifest) {
    init {
        require(text.isNotBlank() && textHash == segments.scriptHash && language == segments.language)
        segments.validate(text)
    }
    override fun toString() = "DemoExcerpt(content=redacted, language=$language)"
}
data class DemoSource(val publisher: String, val originalUrl: String?, val publishedAtRaw: String?, val publishedAtUtc: String?, val synthetic: Boolean) {
    init {
        require(publisher.isNotBlank())
        if (!synthetic) require(originalUrl != null && URI(originalUrl).scheme == "https" && !URI(originalUrl).host.isNullOrBlank() && !publishedAtRaw.isNullOrBlank() && !publishedAtUtc.isNullOrBlank())
    }
}
/** Approval is supplied by the reviewed local fixture loader, never inferred from valid JSON/hash. */
data class DemoApproval(val reviewedManifestHash: String, val sourceReviewReference: String,
    val rightsReviewReference: String, val validUntilEpochMs: Long?, val withdrawn: Boolean = false) {
    init {
        require(reviewedManifestHash.matches(Regex("[a-f0-9]{64}")))
        requireReference(sourceReviewReference); requireReference(rightsReviewReference)
        validUntilEpochMs?.let { require(it >= 0) }
    }
    fun valid(hash: String, nowEpochMs: Long): Boolean = nowEpochMs >= 0 && !withdrawn && hash == reviewedManifestHash &&
        (validUntilEpochMs == null || nowEpochMs < validUntilEpochMs)
}

enum class DemoFollowupRole { ORIGINAL_EN_SUMMARY, ORIGINAL_KO_SUMMARY, ORIGINAL_KO_TRANSLATION, BRIEFING_KO_SUMMARY, BRIEFING_KO_TRANSLATION }

data class SettingsDemoManifest(val demoId: UUID, val version: Long, val hash: String, val source: DemoSource,
    val originalFull: DemoExcerpt, val briefing: DemoExcerpt, val followups: Map<DemoFollowupRole, DemoExcerpt>,
    val questions: Map<LearningBand, DemoExcerpt>, val approval: DemoApproval?) {
    init {
        require(version >= 1 && hash.matches(Regex("[a-f0-9]{64}")))
        require(originalFull.language == SegmentLanguage.EN && briefing.language == SegmentLanguage.EN)
        require(followups.keys == DemoFollowupRole.entries.toSet())
        require(followups.getValue(DemoFollowupRole.ORIGINAL_EN_SUMMARY).language == SegmentLanguage.EN)
        require(followups.filterKeys { it != DemoFollowupRole.ORIGINAL_EN_SUMMARY }.values.all { it.language == SegmentLanguage.KO })
        require(questions.keys == LearningBand.entries.toSet() && questions.values.all { it.language == SegmentLanguage.EN })
        // The loader verifies the exact fixture manifest hash. Each supplied excerpt additionally
        // proves its complete Unicode text/segmentation identity; band never rewrites originalFull.
    }
    fun approved(nowEpochMs: Long) = approval?.valid(hash, nowEpochMs) == true
}

data class SettingsPreviewChoice(val listeningMode: ListeningMode, val followup: Followup, val band: LearningBand)
enum class PreviewPhase { MAIN, FOLLOWUP, QUESTION }
data class PreviewClip(val phase: PreviewPhase, val excerpt: DemoExcerpt)
data class SettingsPreviewPlan(val demoId: UUID, val demoHash: String, val choice: SettingsPreviewChoice, val clips: List<PreviewClip>) {
    init { require(clips.firstOrNull()?.phase == PreviewPhase.MAIN && clips.lastOrNull()?.phase == PreviewPhase.QUESTION && clips.size in 2..3) }
}
/** Ephemeral. No restore constructor, repository command, actual selection, answer or lesson. */
data class PreviewRun(val runId: UUID, val owner: OwnerContext, val draftRevision: Long, val bindingToken: UUID,
    val demoId: UUID, val demoHash: String, val phase: PreviewPhase, val exclusiveAuxLease: UUID?,
    val original: ReviewReturnSnapshot?) {
    init {
        require(draftRevision >= 1 && demoHash.matches(Regex("[a-f0-9]{64}")))
        original?.let { require(it.profile == ProfileContext(owner.profileId, owner.privacyEpoch)) }
    }
}

object SettingsPreviewPolicy {
    /** Two formats × four followups × three bands; briefing+English-summary is one excerpt. */
    fun plan(manifest: SettingsDemoManifest, choice: SettingsPreviewChoice, nowEpochMs: Long): SettingsPreviewPlan? {
        if (!manifest.approved(nowEpochMs)) return null
        val clips = mutableListOf(PreviewClip(PreviewPhase.MAIN,
            if (choice.listeningMode == ListeningMode.ORIGINAL_FULL) manifest.originalFull else manifest.briefing))
        val role = when (choice.listeningMode) {
            ListeningMode.ORIGINAL_FULL -> when (choice.followup) {
                Followup.NONE -> null
                Followup.EN_SUMMARY -> DemoFollowupRole.ORIGINAL_EN_SUMMARY
                Followup.KO_SUMMARY -> DemoFollowupRole.ORIGINAL_KO_SUMMARY
                Followup.KO_TRANSLATION -> DemoFollowupRole.ORIGINAL_KO_TRANSLATION
            }
            ListeningMode.BRIEFING -> when (choice.followup) {
                Followup.NONE, Followup.EN_SUMMARY -> null
                Followup.KO_SUMMARY -> DemoFollowupRole.BRIEFING_KO_SUMMARY
                Followup.KO_TRANSLATION -> DemoFollowupRole.BRIEFING_KO_TRANSLATION
            }
        }
        if (role != null) clips += PreviewClip(PreviewPhase.FOLLOWUP, manifest.followups.getValue(role))
        clips += PreviewClip(PreviewPhase.QUESTION, manifest.questions.getValue(choice.band))
        return SettingsPreviewPlan(manifest.demoId, manifest.hash, choice, clips)
    }
    fun stillCurrent(run: PreviewRun, owner: OwnerContext?, draftRevision: Long, bindingToken: UUID, manifest: SettingsDemoManifest,
        nowEpochMs: Long): Boolean = owner == run.owner && run.draftRevision == draftRevision && run.bindingToken == bindingToken &&
        run.demoId == manifest.demoId && run.demoHash == manifest.hash && manifest.approved(nowEpochMs)
}

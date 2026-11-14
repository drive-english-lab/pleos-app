package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Read permission is separate from this frozen data. These values never start output. */
data class FrozenSelectionMaterial(val owner: OwnerContext, val selectionId: UUID, val content: ContentRef,
    val selectedBand: LearningBand, val preferenceVersionAtSelection: Long, val materialId: UUID,
    val practiceRef: PracticeContentRef?, val view: WireAiLearningSelectionLearningMaterialView,
    val differsFromLastAcknowledgedChoice: Boolean = false) {
    val withdrawn get() = view.material.materialStatus == "withdrawn"
    val canListen get() = !withdrawn && (content.listeningMode == ListeningMode.ORIGINAL_FULL ||
        view.material.briefingLevel?.let { it.status == "verified" && it.band == selectedBand.wireBand() } == true)
    val canPractice get() = canListen && view.material.materialStatus == "ready" && practiceRef != null &&
        view.material.questionLevel?.let { it.status == "verified" && it.band == selectedBand.wireBand() } == true
}

data class LearningMaterialState(val owner: OwnerContext? = null, val candidates: WireAiLearningLearningMaterialCandidatePage? = null,
    val frozen: FrozenSelectionMaterial? = null, val loading: Boolean = false, val failed: Boolean = false,
    val unavailableCode: String? = null)

class LearningMaterialRepository(private val api: AiLearningApi, private val database: LearningDatabase,
    private val current: suspend (OwnerContext) -> Boolean) {
    private val lane = Mutex()
    private val mutable = MutableStateFlow(LearningMaterialState())
    val state = mutable.asStateFlow()

    suspend fun clear() { mutable.value = LearningMaterialState() }

    /** Same original source only. Empty/missing band remains unavailable; no sibling is chosen. */
    suspend fun candidates(scope: LearningCallScope, content: ContentRef, band: LearningBand): LearningReply<WireAiLearningLearningMaterialCandidatePage> = lane.withLock {
        if (!current(scope.owner)) return@withLock LearningReply.Failed("SESSION_CHANGED", false)
        mutable.value = LearningMaterialState(owner = scope.owner, frozen = mutable.value.frozen?.takeIf { it.owner == scope.owner }, loading = true)
        when (val reply = api.listLearningMaterialCandidates(scope, content.articleId.toString(), content.sourceSnapshotId.toString(),
            content.listeningMode.name.lowercase(Locale.ROOT), band.wireBand())) {
            is LearningReply.Failed -> {
                if (current(scope.owner)) mutable.value = mutable.value.copy(loading = false, failed = true, unavailableCode = reply.code)
                reply
            }
            is LearningReply.Stored -> {
                if (!current(scope.owner)) return@withLock LearningReply.Failed("SESSION_CHANGED", false)
                require(reply.value.articleId == content.articleId.toString() && reply.value.sourceSnapshotId == content.sourceSnapshotId.toString() &&
                    reply.value.listeningMode == content.listeningMode.name.lowercase(Locale.ROOT) && reply.value.targetBand == band.wireBand())
                require(reply.value.items.map { it.materialId }.distinct().size == reply.value.items.size)
                reply.value.items.forEach { value ->
                    validateMaterial(value, content.articleId, content.sourceSnapshotId, content.listeningMode, band)
                }
                if (reply.value.status != "ready") require(reply.value.items.none { it.materialStatus == "ready" })
                mutable.value = mutable.value.copy(candidates = reply.value, loading = false, failed = false,
                    unavailableCode = if (reply.value.status == "ready") null else reply.value.status.uppercase(Locale.ROOT))
                reply
            }
        }
    }

    /** Must run after the actual v5 selection ACK and before opening any new S4 output. */
    suspend fun readSelection(scope: LearningCallScope, selectionId: UUID, content: ContentRef,
        lastAcknowledged: NextSelectionPreference? = null): LearningReply<FrozenSelectionMaterial> = lane.withLock {
        readSelectionLocked(scope, selectionId, content, lastAcknowledged)
    }

    private suspend fun readSelectionLocked(scope: LearningCallScope, selectionId: UUID, content: ContentRef,
        lastAcknowledged: NextSelectionPreference?, inheritance: FrozenSelectionMaterial? = null): LearningReply<FrozenSelectionMaterial> {
        if (!current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
        mutable.value = LearningMaterialState(owner = scope.owner, frozen = mutable.value.frozen?.takeIf { it.owner == scope.owner }, loading = true)
        return when (val reply = api.readSelectionLearningMaterial(scope, scope.owner.privacyEpoch, selectionId)) {
            is LearningReply.Failed -> {
                if (current(scope.owner)) mutable.value = mutable.value.copy(loading = false, failed = true, unavailableCode = reply.code)
                reply
            }
            is LearningReply.Stored -> {
                if (!current(scope.owner)) return LearningReply.Failed("SESSION_CHANGED", false)
                val value = reply.value
                require(value.profileContext == scope.owner.profileWire() && value.selectionId == selectionId.toString() &&
                    value.material.contentRef == content.commonContentWire() && value.selectedBand == value.material.band && value.preferenceVersionAtSelection >= 0)
                val band = value.selectedBand.band()
                validateMaterial(value.material, content.articleId, content.sourceSnapshotId, content.listeningMode, band)
                val practice = value.material.practiceContentRef?.practiceDomain()
                val frozen = FrozenSelectionMaterial(scope.owner, selectionId, content, band, value.preferenceVersionAtSelection,
                    UUID.fromString(value.material.materialId), practice, value,
                    lastAcknowledged?.let { it.preferenceVersion != value.preferenceVersionAtSelection || it.band != band } == true)
                if (inheritance != null && (frozen.selectedBand != inheritance.selectedBand || frozen.preferenceVersionAtSelection != inheritance.preferenceVersionAtSelection ||
                    inheritance.practiceRef != null && frozen.practiceRef != null && inheritance.practiceRef.basicQuestionVersionId != frozen.practiceRef.basicQuestionVersionId)) {
                    mutable.value = LearningMaterialState(owner = scope.owner, frozen = inheritance, failed = true, unavailableCode = "INVALID_FORMAT_INHERITANCE")
                    return LearningReply.Failed("INVALID_FORMAT_INHERITANCE", false)
                }
                database.aiLearning().saveSelectionMaterialProjection(scope.owner, LocalAiLearningCache(scope.owner.profileId.toString(), scope.owner.privacyEpoch,
                    "selection_material", selectionId.toString(), value.selectionMaterialRevision,
                    featureJson.encodeToString(WireAiLearningSelectionLearningMaterialView.serializer(), value)))
                mutable.value = LearningMaterialState(owner = scope.owner, frozen = frozen)
                LearningReply.Stored(scope, frozen)
            }
        }
    }

    /** Current format change deliberately does not read/flush next-selection preference. */
    suspend fun readFormatChange(scope: LearningCallScope, source: FrozenSelectionMaterial,
        targetSelectionId: UUID, targetContent: ContentRef): LearningReply<FrozenSelectionMaterial> = lane.withLock {
        if (source.owner != scope.owner || source.content.articleId != targetContent.articleId || source.content.bundleId != targetContent.bundleId ||
            source.content.sourceSnapshotId != targetContent.sourceSnapshotId || source.content.listeningMode == targetContent.listeningMode)
            return@withLock LearningReply.Failed("FORMAT_SOURCE_CHANGED", false)
        readSelectionLocked(scope, targetSelectionId, targetContent, null, source)
    }

    private fun validateMaterial(value: WireAiLearningLearningMaterialCandidate, article: UUID, source: UUID, mode: ListeningMode, band: LearningBand) {
        require(value.band == band.wireBand() && value.contentRef.articleId == article.toString() &&
            value.contentRef.sourceSnapshotId == source.toString() && value.contentRef.listeningMode == mode.name.lowercase(Locale.ROOT))
        value.practiceContentRef?.let { practice -> require(practice.contentWire() == value.contentRef) }
        require(value.materialStatus in setOf("ready", "practice_unavailable", "withdrawn"))
        if (value.materialStatus == "ready") require(value.practiceContentRef != null && value.questionLevel?.let { it.status == "verified" && it.band == value.band } == true)
        if (mode == ListeningMode.BRIEFING && value.materialStatus != "withdrawn") require(value.briefingLevel?.let { it.status == "verified" && it.band == value.band } == true)
        // original_full is the actual unmodified source; source_level unknown/different is
        // visible metadata, not permission to rewrite or block it as another band.
    }
}

private fun ContentRef.commonContentWire() = WireCommonContentRef(articleId = articleId.toString(), bundleId = bundleId.toString(), sourceSnapshotId = sourceSnapshotId.toString(), listeningMode = listeningMode.name.lowercase(Locale.ROOT), listeningScriptId = listeningScriptId.toString())
private fun WireCommonPracticeContentRef.contentWire() = WireCommonContentRef(articleId = articleId, bundleId = bundleId, sourceSnapshotId = sourceSnapshotId, listeningMode = listeningMode, listeningScriptId = listeningScriptId)
private fun WireCommonPracticeContentRef.practiceDomain() = PracticeContentRef(ContentRef(UUID.fromString(articleId), UUID.fromString(bundleId),
    UUID.fromString(sourceSnapshotId), UUID.fromString(listeningScriptId), ListeningMode.valueOf(listeningMode.uppercase(Locale.ROOT))), UUID.fromString(taskId), UUID.fromString(basicQuestionVersionId))

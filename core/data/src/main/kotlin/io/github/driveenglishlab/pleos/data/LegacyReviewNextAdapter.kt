package io.github.driveenglishlab.pleos.data

import android.content.Context
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/** Supplies an actual schema5 record, not a reconstructed guess from a schema6 time cursor. */
fun interface OriginalLegacyCheckpointPort {
    suspend fun read(scope: LearningCallScope, checkpointId: UUID): WirePersonalReadCheckpoint?
}
sealed interface LegacyReviewAdmission {
    data object NotLegacyTime : LegacyReviewAdmission
    data class Unavailable(val reason: String) : LegacyReviewAdmission
    class Ready(val scope: LearningCallScope, val frame: ListeningFrame, val source6Checkpoint: WireSource6PersonalReadCheckpoint,
        val script: PublishedScript?, val title: String) : LegacyReviewAdmission {
        val canPlay get() = frame.checkpoint?.payload?.phase in setOf("main", "followup") && script != null
        override fun toString() = "LegacyReviewAdmission.Ready(content=redacted)"
    }
}

/** Narrow compatibility adapter. It neither chooses a new resume nor starts playback. Every
 * schema5-only field comes from an original schema5 checkpoint and verified current audio.
 */
class LegacyReviewNextAdapter(context: Context, private val catalog: CatalogRepository,
    private val listening: ListeningRepository, private val isCurrent: suspend (OwnerContext) -> Boolean,
    private val originalReads: OriginalLegacyCheckpointPort? = null) {
    private val contracts = LearningContractJson(context)
    private val codecs = IncrementalContractJson(context)

    suspend fun prepare(scope:LearningCallScope,snapshot:Source6ProfileSnapshot,response:WireReview3ReviewNextResponse3,
        originalCheckpoint:WirePersonalReadCheckpoint?=null):LegacyReviewAdmission {
        if(response.outcomeProjection.state !in setOf("selected","resumed"))return LegacyReviewAdmission.Unavailable("SELECTION_NOT_ADMITTED")
        val selected=response.outcomeProjection.selection?:return LegacyReviewAdmission.Unavailable("SELECTION_NOT_ADMITTED")
        val checkpoint=response.outcomeProjection.checkpoint?:return LegacyReviewAdmission.Unavailable("CHECKPOINT_NOT_ADMITTED")
        val source=codecs.decodeSource6("PersonalSelectionRecord",codecs.encode("review3","Source6PersonalSelectionRecord",selected,
            WireReview3Source6PersonalSelectionRecord.serializer()),WireSource6PersonalSelectionRecord.serializer())
        val point=codecs.decodeSource6("PersonalReadCheckpoint",codecs.encode("review3","Source6PersonalReadCheckpoint",checkpoint,
            WireReview3Source6PersonalReadCheckpoint.serializer()),WireSource6PersonalReadCheckpoint.serializer())
        return prepare(scope,snapshot,source,point,originalCheckpoint)
    }

    suspend fun prepare(scope: LearningCallScope, snapshot: Source6ProfileSnapshot, response: WireReview2ReviewNextResponse2,
        originalCheckpoint: WirePersonalReadCheckpoint? = null): LegacyReviewAdmission {
        val selected = response.outcomeProjection.selection ?: return LegacyReviewAdmission.Unavailable("SELECTION_NOT_ADMITTED")
        val checkpoint = response.outcomeProjection.checkpoint ?: return LegacyReviewAdmission.Unavailable("CHECKPOINT_NOT_ADMITTED")
        if (response.outcomeProjection.state !in setOf("selected", "resumed")) return LegacyReviewAdmission.Unavailable("SELECTION_NOT_ADMITTED")
        val source = codecs.decodeSource6("PersonalSelectionRecord", codecs.encode("review2", "Source6PersonalSelectionRecord", selected,
            WireReview2Source6PersonalSelectionRecord.serializer()), WireSource6PersonalSelectionRecord.serializer())
        val point = codecs.decodeSource6("PersonalReadCheckpoint", codecs.encode("review2", "Source6PersonalReadCheckpoint", checkpoint,
            WireReview2Source6PersonalReadCheckpoint.serializer()), WireSource6PersonalReadCheckpoint.serializer())
        return prepare(scope, snapshot, source, point, originalCheckpoint)
    }

    suspend fun prepare(scope: LearningCallScope, snapshot: Source6ProfileSnapshot, selection: WireSource6PersonalSelectionRecord,
        checkpoint: WireSource6PersonalReadCheckpoint, originalCheckpoint: WirePersonalReadCheckpoint? = null): LegacyReviewAdmission {
        val ref = selection.contentRef as? WireSource6LegacyContentRef6 ?: return LegacyReviewAdmission.NotLegacyTime
        val cursor = checkpoint.payload.cursor as? WireSource6LegacyTimeCursor6 ?: return LegacyReviewAdmission.NotLegacyTime
        if (!isCurrent(scope.owner)) return LegacyReviewAdmission.Unavailable("SESSION_CHANGED")
        return try {
            codecs.encode("source6", "PersonalSelectionRecord", selection, WireSource6PersonalSelectionRecord.serializer())
            codecs.encode("source6", "PersonalReadCheckpoint", checkpoint, WireSource6PersonalReadCheckpoint.serializer())
            val profile = snapshot.profile
            require(profile.profileContext.profileId == scope.owner.profileId.toString() && profile.profileContext.privacyEpoch == scope.owner.privacyEpoch)
            require(profile.activeResume?.kind == "listening" && profile.activeResume?.recordId == checkpoint.recordId)
            require(checkpoint.payload.selectionId == selection.selectionId && checkpoint.payload.contentRef == selection.contentRef &&
                checkpoint.payload.streamId == selection.streamId && checkpoint.recordId == checkpoint.payload.checkpointId)
            val actualPoint = snapshot.records.filterIsInstance<WireSource6PersonalReadCheckpoint>().singleOrNull { it.recordId == checkpoint.recordId }
                ?: return LegacyReviewAdmission.Unavailable("ACTUAL_SOURCE6_CHECKPOINT_REQUIRED")
            require(actualPoint == checkpoint)
            val actualSelection = snapshot.records.filterIsInstance<WireSource6PersonalReadSelection>().singleOrNull { it.recordId == selection.selectionId }
                ?: return LegacyReviewAdmission.Unavailable("ACTUAL_SOURCE6_SELECTION_REQUIRED")
            require(actualSelection.payload == selection)
            val nativeStream = snapshot.records.filterIsInstance<WireSource6PersonalReadStream>().singleOrNull { it.recordId == selection.streamId }
                ?: return LegacyReviewAdmission.Unavailable("ACTUAL_NATIVE_STREAM_REQUIRED")
            require(nativeStream.payload.streamId == nativeStream.recordId && nativeStream.version >= 1)
            val nativeHeadId = nativeStream.payload.currentSelectionId
                ?: return LegacyReviewAdmission.Unavailable("ACTUAL_NATIVE_HEAD_REQUIRED")
            val nativeHead = snapshot.records.filterIsInstance<WireSource6PersonalReadSelection>().singleOrNull { it.recordId == nativeHeadId }
                ?: return LegacyReviewAdmission.Unavailable("ACTUAL_NATIVE_HEAD_REQUIRED")
            if (nativeHead.payload.contentRef !is WireSource6LegacyContentRef6)
                return LegacyReviewAdmission.Unavailable("NATIVE_HEAD_REQUIRES_SOURCE6")
            val original = originalCheckpoint ?: originalReads?.read(scope, UUID.fromString(cursor.originalCheckpointId))
                ?: return LegacyReviewAdmission.Unavailable("ORIGINAL_SCHEMA5_CHECKPOINT_REQUIRED")
            strict("ReadCheckpoint", learningJson.encodeToJsonElement(WirePersonalReadCheckpoint.serializer(), original))
            // Do not rebind an old checkpoint's output intent/assets to a newly invented identity.
            if (original.recordId != cursor.originalCheckpointId || original.recordId != checkpoint.recordId || original.version != checkpoint.version)
                return LegacyReviewAdmission.Unavailable("ORIGINAL_CHECKPOINT_VERSION_MISMATCH")
            val point = original.payload
            val sourceRef = legacyRef(ref)
            require(point.checkpointId == checkpoint.recordId && point.selectionId == selection.selectionId && point.streamId == selection.streamId && point.contentRef == sourceRef &&
                point.phase == cursor.phase && point.positionMs == cursor.originalPositionMs && point.durationMs == cursor.originalDurationMs &&
                point.activeRunId == checkpoint.payload.activeRunId && point.mainCoverageStatus == checkpoint.payload.mainCoverageStatus)
            val nativeSelection = legacySelection(selection)
            val head = legacySelection(nativeHead.payload)
            val frame = ListeningFrame(scope.owner, UUID.fromString(selection.streamId), nativeStream.version, profile.settings.version,
                profile.resumeVersion, nativeSelection, original, head)
            val script = if (point.phase == "article_offer") {
                // Current bundle/rights still need a real read; there is no current output asset.
                if (catalog.readMain(sourceRef.toDomainContentRef()) !is CatalogRead.Stored)
                    return LegacyReviewAdmission.Unavailable("LEGACY_BUNDLE_UNAVAILABLE")
                null
            } else {
                val read = catalog.readCheckpointScript(frame)
                (read as? CatalogRead.Stored)?.value ?: return LegacyReviewAdmission.Unavailable((read as CatalogRead.Unavailable).code)
            }
            val titleRead = catalog.readTitle(sourceRef.toDomainContentRef())
            val title = (titleRead as? CatalogRead.Stored)?.value ?: return LegacyReviewAdmission.Unavailable((titleRead as CatalogRead.Unavailable).code)
            if (!isCurrent(scope.owner)) LegacyReviewAdmission.Unavailable("SESSION_CHANGED")
            else LegacyReviewAdmission.Ready(scope, frame, checkpoint, script, title)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { LegacyReviewAdmission.Unavailable("LEGACY_FACTS_OR_ASSETS_MISMATCH") }
    }

    /** Invoke only after the current original request has been claimed by the product. */
    suspend fun attachReady(value: LegacyReviewAdmission.Ready): Boolean {
        if (!isCurrent(value.scope.owner) || value.frame.owner != value.scope.owner) return false
        val ref = value.frame.selection?.contentRef?.toDomainContentRef() ?: return false
        val current = if (value.canPlay) catalog.readCheckpointScript(value.frame) else catalog.readMain(ref)
        if (current !is CatalogRead.Stored || value.script != null && current.value != value.script || !isCurrent(value.scope.owner)) return false
        listening.attach(value.frame)
        return isCurrent(value.scope.owner)
    }

    private fun legacyRef(ref: WireSource6LegacyContentRef6): WireCommonContentRef {
        val value = codecs.parse("source6", codecs.encode("source6", "ContentRef6", ref, WireSource6ContentRef6.serializer())).jsonObject.getValue("legacy_ref")
        contracts.validate("common.schema.json", contracts.schema("common.schema.json", "#/\$defs/ContentRef"), value)
        return learningJson.decodeFromJsonElement(WireCommonContentRef.serializer(), value)
    }
    private fun legacySelection(value: WireSource6PersonalSelectionRecord): WirePersonalSelectionRecord {
        val ref = value.contentRef as? WireSource6LegacyContentRef6 ?: error("Prepared identity is not a schema5 selection.")
        val encoded = codecs.parse("source6", codecs.encode("source6", "PersonalSelectionRecord", value, WireSource6PersonalSelectionRecord.serializer())).jsonObject
        val original = JsonObject(encoded + ("content_ref" to learningJson.encodeToJsonElement(WireCommonContentRef.serializer(), legacyRef(ref))))
        strict("SelectionRecord", original)
        return learningJson.decodeFromJsonElement(WirePersonalSelectionRecord.serializer(), original)
    }
    private fun strict(name: String, value: JsonElement) = contracts.validate("personal-data.openapi.json",
        contracts.schema("personal-data.openapi.json", "#/components/schemas/$name"), value)
}

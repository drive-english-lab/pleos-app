package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.*

/** An acknowledged read, never a request to resume or start output. */
data class Source6ProfileSnapshot(val profile: WireSource6PersonalProfileView,
    val records: List<WireSource6PersonalReadRecord>, val cursor: String? = null)

/** Same selection/sync engine with real tagged refs and immutable original command bytes. */
class Source6ListeningRepository(private val api: IncrementalLearningApi, private val dao: ReviewDao,
    private val codecs: IncrementalContractJson) {
    private val lane = Mutex()
    private val profiles = mutableMapOf<String, WireSource6PersonalProfileView>()
    private val streamVersions = mutableMapOf<String, Long>()

    suspend fun snapshot(scope: LearningCallScope): LearningReply<Source6ProfileSnapshot> = lane.withLock {
        guarded(scope) { readSnapshot(scope) }
    }

    private suspend fun readSnapshot(scope: LearningCallScope): LearningReply<Source6ProfileSnapshot> =
        withTimeoutOrNull(8_000) {
            if (!api.isCurrent(scope)) return@withTimeoutOrNull failedSession()
            val records = linkedMapOf<String, Pair<WireSource6PersonalReadRecord, LocalSource6Record>>()
            val seenCursors = mutableSetOf<String>()
            var cursor: String? = null
            var profile: WireSource6PersonalProfileView? = null
            while (true) {
                when (val page = api.call("source6", "/v2/profile/sync", "get", scope, null,
                    WireSource6PersonalSyncPage.serializer(), queryArguments = mapOf(
                        "client_privacy_epoch" to scope.owner.privacyEpoch.toString(), "cursor" to cursor, "limit" to "50"))) {
                    is LearningReply.Failed -> return@withTimeoutOrNull page
                    is LearningReply.Stored -> {
                        if (page.scope != scope || !api.isCurrent(scope)) return@withTimeoutOrNull failedSession()
                        val value = page.value
                        if (value.resetRequired) return@withTimeoutOrNull LearningReply.Failed("RESTORE_REQUIRED", false)
                        requireOwner(scope, value.profileContext)
                        requireOwner(scope, value.profile.profileContext)
                        if (profile != null && profile != value.profile)
                            return@withTimeoutOrNull LearningReply.Failed("SNAPSHOT_CHANGED", true)
                        profile = value.profile
                        if (!profileNotOlder(scope, value.profile)) return@withTimeoutOrNull LearningReply.Failed("STALE_RESPONSE", true)
                        for (record in value.records) {
                            val wire = codecs.encode("source6", "PersonalReadRecord", record, WireSource6PersonalReadRecord.serializer())
                            val raw = codecs.parse("source6", wire).jsonObject
                            // ReviewDao stores source6 records in canonical form. Compare
                            // the same bytes/hash before and after its CAS transaction;
                            // serializer property order is not a changed server record.
                            val encoded = incrementalCanonical(raw)
                            val id = raw.getValue("record_id").jsonPrimitive.content
                            val kind = raw.getValue("kind").jsonPrimitive.content
                            val version = raw.getValue("version").jsonPrimitive.long
                            val stored = LocalSource6Record(scope.owner.profileId.toString(), scope.owner.privacyEpoch,
                                kind, id, version, encoded, LearningJson.hash(encoded))
                            val key = "$kind:$id"
                            val previous = records[key]?.second
                            if (previous != null && previous.version == version) require(previous == stored) { "Immutable record changed." }
                            if (previous == null || version > previous.version) records[key] = record to stored
                        }
                        val next = value.cursor
                        // Sync cursors are high-water marks, including on the final empty
                        // page. Waiting for null would repeatedly fetch the same page.
                        val finished = value.records.size < 50 || next == null
                        if (!finished && (next == cursor || !seenCursors.add(requireNotNull(next))))
                            return@withTimeoutOrNull LearningReply.Failed("SYNC_CURSOR_CYCLE", false)
                        if (finished) {
                            if (!api.isCurrent(scope)) return@withTimeoutOrNull failedSession()
                            // Validate the whole read before cache writes. Each DAO write
                            // independently rechecks the owner/revision in its transaction.
                            for ((_, candidate) in records.values) {
                                val old = dao.source6Record(candidate.profileId, candidate.privacyEpoch, candidate.kind, candidate.recordId)
                                if (old != null && old.version > candidate.version)
                                    return@withTimeoutOrNull LearningReply.Failed("STALE_RESPONSE", true)
                                if (old != null && old.version == candidate.version) require(old == candidate) { "Immutable cached record changed." }
                            }
                            for ((_, candidate) in records.values) {
                                if (!api.isCurrent(scope)) return@withTimeoutOrNull failedSession()
                                dao.storeSource6(scope.owner, candidate)
                                val current = dao.source6Record(candidate.profileId, candidate.privacyEpoch, candidate.kind, candidate.recordId)
                                if (current != candidate) return@withTimeoutOrNull LearningReply.Failed("STALE_RESPONSE", true)
                            }
                            if (!api.isCurrent(scope)) return@withTimeoutOrNull failedSession()
                            rememberProfile(scope, value.profile)
                            val result = records.values.map { it.first }
                            result.filterIsInstance<WireSource6PersonalReadStream>().forEach {
                                streamVersions[streamKey(scope, it.recordId)] = maxOf(streamVersions[streamKey(scope, it.recordId)] ?: 0, it.version)
                            }
                            return@withTimeoutOrNull LearningReply.Stored(scope, Source6ProfileSnapshot(value.profile, result, next))
                        }
                        cursor = next
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE")
            LearningReply.Failed("SYNC_NOT_READY", true)
        } ?: LearningReply.Failed("SYNC_NOT_READY", true)

    /** Register one real stream through existing sync; no local-only stream is admitted. */
    suspend fun createStream(scope: LearningCallScope, operationId: UUID, streamId: UUID,
        startedAt: Instant): LearningReply<Source6ProfileSnapshot> = lane.withLock {
        guarded(scope) {
            if (!api.isCurrent(scope)) return@guarded failedSession()
            val request = WireSource6PersonalSyncRequest(operationId.toString(), context(scope), listOf(
                WireSource6PersonalStreamEvent(operationId.toString(), "stream", JsonNull,
                    WireSource6PersonalStreamInput(streamId.toString(), startedAt.truncatedTo(ChronoUnit.MICROS).toString()))), "6")
            val encoded = codecs.encode("source6", "PersonalSyncRequest", request, WireSource6PersonalSyncRequest.serializer())
            val row = freeze(scope, request.operationId, "source6_stream", streamId.toString(), encoded)
            val original = codecs.decodeSource6("PersonalSyncRequest", row.payloadJson, WireSource6PersonalSyncRequest.serializer())
            val reply = if (row.status == "acknowledged") cached(scope, row, "PersonalSyncReceipt", WireSource6PersonalSyncReceipt.serializer())
                else api.call("source6", "/v2/profile/sync", "post", scope, codecs.parse("source6", row.payloadJson), WireSource6PersonalSyncReceipt.serializer())
            when (reply) {
                is LearningReply.Failed -> reply
                is LearningReply.Stored -> {
                    if (reply.scope != scope || !api.isCurrent(scope)) return@guarded failedSession()
                    requireOwner(scope, reply.value.profileContext)
                    val input = original.events.single() as WireSource6PersonalStreamEvent
                    val receipt = reply.value.receipts.single()
                    require(receipt.eventId == input.eventId && receipt.recordId == input.payload.streamId && receipt.version >= 1)
                    dao.acknowledge(scope.owner, row, codecs.encode("source6", "PersonalSyncReceipt", reply.value, WireSource6PersonalSyncReceipt.serializer()))
                    when (val snapshot = readSnapshot(scope)) {
                        is LearningReply.Failed -> snapshot
                        is LearningReply.Stored -> {
                            val stream = snapshot.value.records.filterIsInstance<WireSource6PersonalReadStream>().singleOrNull { it.recordId == input.payload.streamId }
                            require(stream != null && stream.version >= receipt.version && stream.payload.streamId == input.payload.streamId && Instant.parse(stream.payload.startedAt) == Instant.parse(input.payload.startedAt))
                            snapshot
                        }
                    }
                }
            }
        }
    }

    /** A pending response keeps the exact operation/CAS journal open for explicit retry. */
    suspend fun select(scope: LearningCallScope, request: WireSource6PersonalNextArticleRequest): LearningReply<WireSource6PersonalNextArticleResponse> = lane.withLock {
        guarded(scope) {
            if (!api.isCurrent(scope)) return@guarded failedSession()
            requireOwner(scope, request.profileContext)
            val encoded = codecs.encode("source6", "PersonalNextArticleRequest", request, WireSource6PersonalNextArticleRequest.serializer())
            val row = freeze(scope, request.operationId, "source6_selection", request.streamId, encoded)
            val original = codecs.decodeSource6("PersonalNextArticleRequest", row.payloadJson, WireSource6PersonalNextArticleRequest.serializer())
            val reply = if (row.status == "acknowledged") cached(scope, row, "PersonalNextArticleResponse", WireSource6PersonalNextArticleResponse.serializer())
                else api.call("source6", "/v2/listening/next", "post", scope, codecs.parse("source6", row.payloadJson), WireSource6PersonalNextArticleResponse.serializer())
            when (reply) {
                is LearningReply.Failed -> reply
                is LearningReply.Stored -> {
                    if (reply.scope != scope || !api.isCurrent(scope)) return@guarded failedSession()
                    val value = reply.value
                    requireOwner(scope, value.profileContext)
                    require(value.operationId == original.operationId && value.streamId == original.streamId)
                    require(original.expectedStreamVersion < Long.MAX_VALUE)
                    require(value.streamVersion == original.expectedStreamVersion + if (value.status == "selected") 1 else 0)
                    if (value.status == "selected") {
                        val selection = requireNotNull(value.selection); val point = requireNotNull(value.checkpoint)
                        require(selection.streamId == original.streamId && selection.previousSelectionId == original.previousSelectionId &&
                            selection.settingsSnapshot.version == original.expectedSettingsVersion && selection.selectionReason == original.selectionKind &&
                            point.version == 1L && point.recordId == original.checkpointId && point.payload.checkpointId == original.checkpointId &&
                            point.payload.streamId == original.streamId && point.payload.selectionId == selection.selectionId && point.payload.contentRef == selection.contentRef)
                        requireInitial(point.payload)
                        if (original.requestedArticleId != null) require(articleId(selection.contentRef) == original.requestedArticleId)
                        cacheCheckpoint(scope, point)
                        val review = requireNotNull(value.context)
                        require(review.profileId == scope.owner.profileId.toString() && review.learningEpoch == scope.owner.privacyEpoch && review.selectionId == selection.selectionId)
                    }
                    if (value.status != "pending") dao.acknowledge(scope.owner, row,
                        codecs.encode("source6", "PersonalNextArticleResponse", value, WireSource6PersonalNextArticleResponse.serializer()))
                    if ((streamVersions[streamKey(scope, value.streamId)] ?: 0) > value.streamVersion)
                        return@guarded LearningReply.Failed("STALE_RESPONSE", true)
                    streamVersions[streamKey(scope, value.streamId)] = value.streamVersion
                    if (!api.isCurrent(scope)) failedSession() else reply
                }
            }
        }
    }

    suspend fun changeFormat(scope: LearningCallScope, request: WireSource6PersonalFormatChangeRequest): LearningReply<WireSource6PersonalFormatChangeResponse> = lane.withLock {
        guarded(scope) {
            if (!api.isCurrent(scope)) return@guarded failedSession()
            requireOwner(scope, request.profileContext)
            val encoded = codecs.encode("source6", "PersonalFormatChangeRequest", request, WireSource6PersonalFormatChangeRequest.serializer())
            val row = freeze(scope, request.operationId, "source6_format", request.sourceSelectionId, encoded)
            val original = codecs.decodeSource6("PersonalFormatChangeRequest", row.payloadJson, WireSource6PersonalFormatChangeRequest.serializer())
            val reply = if (row.status == "acknowledged") cached(scope, row, "PersonalFormatChangeResponse", WireSource6PersonalFormatChangeResponse.serializer())
                else api.call("source6", "/v2/listening/format-changes", "post", scope, codecs.parse("source6", row.payloadJson), WireSource6PersonalFormatChangeResponse.serializer())
            when (reply) {
                is LearningReply.Failed -> reply
                is LearningReply.Stored -> {
                    if (reply.scope != scope || !api.isCurrent(scope)) return@guarded failedSession()
                    val value = reply.value; requireOwner(scope, value.profileContext)
                    val selection = value.selection; val point = value.checkpoint
                    require(original.expectedStreamVersion < Long.MAX_VALUE && value.operationId == original.operationId &&
                        value.streamId == original.streamId && value.streamVersion == original.expectedStreamVersion + 1 &&
                        selection.streamId == original.streamId && selection.previousSelectionId == original.expectedHeadSelectionId &&
                        selection.formatChangeSource?.selectionId == original.sourceSelectionId && selection.formatChangeSource?.checkpointId == original.sourceCheckpointId &&
                        point.version == 1L && point.recordId == original.checkpointId && point.payload.checkpointId == original.checkpointId &&
                        point.payload.streamId == original.streamId && point.payload.selectionId == selection.selectionId && point.payload.contentRef == selection.contentRef)
                    val mode = when (val ref = selection.contentRef) {
                        is WireSource6PreparedContentRef6 -> ref.listeningMode
                        is WireSource6LegacyContentRef6 -> ref.legacyRef.listeningMode
                    }
                    require(mode == original.targetListeningMode)
                    requireInitial(point.payload)
                    cacheCheckpoint(scope, point)
                    dao.acknowledge(scope.owner, row, codecs.encode("source6", "PersonalFormatChangeResponse", value, WireSource6PersonalFormatChangeResponse.serializer()))
                    if ((streamVersions[streamKey(scope, value.streamId)] ?: 0) > value.streamVersion)
                        return@guarded LearningReply.Failed("STALE_RESPONSE", true)
                    streamVersions[streamKey(scope, value.streamId)] = value.streamVersion
                    if (!api.isCurrent(scope)) failedSession() else reply
                }
            }
        }
    }

    /** Freeze resume CAS once, including after a lost ACK. Composition stays outside
     * lane: snapshot()/resume() each take the same mutex themselves.
     */
    suspend fun resumeListening(scope: LearningCallScope, operationId: UUID,
        checkpointId: UUID): LearningReply<WireSource6PersonalProfileView> = guarded(scope) {
        if (!api.isCurrent(scope)) return@guarded failedSession()
        val id = operationId.toString(); val target = checkpointId.toString()
        val previous = dao.operation(id)
        val frozen: Pair<WireSource6PersonalResumeSelection, Boolean> = if (previous != null) {
            originalListeningResume(scope, previous, id, target) to true
        } else {
            val fresh = when (val read = snapshot(scope)) {
                is LearningReply.Failed -> return@guarded read
                is LearningReply.Stored -> {
                    if (read.scope != scope || !api.isCurrent(scope)) return@guarded failedSession()
                    read.value.profile
                }
            }
            // Another same-operation caller may have frozen while the GET was
            // in flight. Read again under lane before introducing any CAS bytes.
            lane.withLock {
                require(api.isCurrent(scope))
                val raced = dao.operation(id)
                if (raced != null) originalListeningResume(scope, raced, id, target) to true
                else {
                    requireOwner(scope, fresh.profileContext)
                    val request = WireSource6PersonalResumeSelection("6", id, context(scope), fresh.resumeVersion,
                        WireSource6PersonalResumeRef("listening", target))
                    val encoded = codecs.encode("source6", "PersonalResumeSelection", request, WireSource6PersonalResumeSelection.serializer())
                    val row = freeze(scope, id, "source6_resume", target, encoded)
                    originalListeningResume(scope, row, id, target) to false
                }
            }
        }
        if (!api.isCurrent(scope)) return@guarded failedSession()
        val reply = resume(scope, frozen.first)
        if (reply is LearningReply.Failed && reply.code != "STALE_RESPONSE") return@guarded reply
        if (reply is LearningReply.Stored && !frozen.second) return@guarded reply
        if (!api.isCurrent(scope)) return@guarded failedSession()
        // Only an actual, strictly matched ACK can justify replacing an old
        // receipt projection with a fresh GET. A failed CAS/pending row is not proof.
        val row = dao.operation(id) ?: return@guarded LearningReply.Failed("STALE_RESPONSE", true)
        val original = originalListeningResume(scope, row, id, target)
        if (row.status != "acknowledged" || row.receiptJson == null)
            return@guarded LearningReply.Failed("STALE_RESPONSE", true)
        val ack = codecs.decodeSource6("PersonalProfileView", row.receiptJson, WireSource6PersonalProfileView.serializer())
        requireOwner(scope, ack.profileContext)
        require(original.expectedResumeVersion < Long.MAX_VALUE && ack.activeResume == original.activeResume &&
            ack.resumeVersion == original.expectedResumeVersion + 1)
        when (val read = snapshot(scope)) {
            is LearningReply.Failed -> read
            is LearningReply.Stored -> {
                if (read.scope != scope || !api.isCurrent(scope)) return@guarded failedSession()
                val actual = read.value.profile
                if (actual.activeResume != original.activeResume || actual.resumeVersion < ack.resumeVersion ||
                    actual.version < ack.version || actual.settings.version < ack.settings.version ||
                    actual.historyCoverage.version < ack.historyCoverage.version)
                    LearningReply.Failed("STALE_RESPONSE", false)
                else LearningReply.Stored(scope, actual)
            }
        }
    }

    private fun originalListeningResume(scope: LearningCallScope, row: LocalReviewOperation,
        operationId: String, checkpointId: String): WireSource6PersonalResumeSelection {
        check(row.operationId == operationId && row.profileId == scope.owner.profileId.toString() &&
            row.privacyEpoch == scope.owner.privacyEpoch && row.ownerRevision == scope.owner.revision &&
            row.kind == "source6_resume" && row.targetId == checkpointId && row.status in setOf("pending", "acknowledged") &&
            row.payloadHash == LearningJson.hash(row.payloadJson)) { "Immutable listening resume changed." }
        val original = codecs.decodeSource6("PersonalResumeSelection", row.payloadJson, WireSource6PersonalResumeSelection.serializer())
        requireOwner(scope, original.profileContext)
        check(original.operationId == operationId && original.activeResume == WireSource6PersonalResumeRef("listening", checkpointId) &&
            codecs.encode("source6", "PersonalResumeSelection", original, WireSource6PersonalResumeSelection.serializer()) == row.payloadJson) {
            "Immutable listening resume target changed."
        }
        return original
    }

    suspend fun resume(scope: LearningCallScope, request: WireSource6PersonalResumeSelection): LearningReply<WireSource6PersonalProfileView> = lane.withLock {
        guarded(scope) {
            if (!api.isCurrent(scope)) return@guarded failedSession()
            requireOwner(scope, request.profileContext)
            val encoded = codecs.encode("source6", "PersonalResumeSelection", request, WireSource6PersonalResumeSelection.serializer())
            val row = freeze(scope, request.operationId, "source6_resume", request.activeResume?.recordId, encoded)
            val original = codecs.decodeSource6("PersonalResumeSelection", row.payloadJson, WireSource6PersonalResumeSelection.serializer())
            if (row.status != "acknowledged" && original.activeResume != null) {
                val ref = original.activeResume
                val kind = if (ref.kind == "listening") "checkpoint" else "lesson"
                if (dao.source6Record(scope.owner.profileId.toString(), scope.owner.privacyEpoch, kind, ref.recordId) == null)
                    return@guarded LearningReply.Failed("REFERENCE_NOT_ACKNOWLEDGED", false)
            }
            val reply = if (row.status == "acknowledged") cached(scope, row, "PersonalProfileView", WireSource6PersonalProfileView.serializer())
                else api.call("source6", "/v2/profile/resume", "patch", scope, codecs.parse("source6", row.payloadJson), WireSource6PersonalProfileView.serializer())
            when (reply) {
                is LearningReply.Failed -> reply
                is LearningReply.Stored -> {
                    if (reply.scope != scope || !api.isCurrent(scope)) return@guarded failedSession()
                    requireOwner(scope, reply.value.profileContext)
                    require(original.expectedResumeVersion < Long.MAX_VALUE && reply.value.activeResume == original.activeResume &&
                        reply.value.resumeVersion == original.expectedResumeVersion + 1)
                    dao.acknowledge(scope.owner, row, codecs.encode("source6", "PersonalProfileView", reply.value, WireSource6PersonalProfileView.serializer()))
                    if (!profileNotOlder(scope, reply.value)) return@guarded LearningReply.Failed("STALE_RESPONSE", true)
                    rememberProfile(scope, reply.value)
                    if (!api.isCurrent(scope)) failedSession() else reply
                }
            }
        }
    }

    private fun articleId(ref: WireSource6ContentRef6) = when (ref) {
        is WireSource6PreparedContentRef6 -> ref.articleId
        is WireSource6LegacyContentRef6 -> ref.legacyRef.articleId
    }
    private fun requireInitial(point: WireSource6PersonalCheckpointRecord) {
        require(point.activeRunId == null && point.mainCoverageStatus == "unknown")
        when (val cursor = point.cursor) {
            is WireSource6LegacyTimeCursor6 -> require(cursor.phase == "main" && cursor.originalCheckpointId == point.checkpointId && cursor.originalPositionMs == 0L)
            is WireSource6SegmentCursor6 -> {
                require(cursor.phase == "main" && cursor.segmentIndex == 0L && cursor.segmentId != null && cursor.resumeFrom == "start")
                when (val ref = point.contentRef) {
                    is WireSource6PreparedContentRef6 -> require(cursor.scriptId == ref.mainScriptId && cursor.scriptHash == ref.mainTextHash)
                    is WireSource6LegacyContentRef6 -> require(cursor.scriptId == ref.legacyRef.listeningScriptId)
                }
            }
        }
    }
    private suspend fun cacheCheckpoint(scope: LearningCallScope, value: WireSource6PersonalReadCheckpoint) {
        require(api.isCurrent(scope))
        val encoded = codecs.encode("source6", "PersonalReadCheckpoint", value, WireSource6PersonalReadCheckpoint.serializer())
        dao.storeSource6(scope.owner, LocalSource6Record(scope.owner.profileId.toString(), scope.owner.privacyEpoch,
            "checkpoint", value.recordId, value.version, encoded, LearningJson.hash(encoded)))
    }

    private suspend fun freeze(scope: LearningCallScope, id: String, kind: String, target: String?, encoded: String): LocalReviewOperation {
        require(api.isCurrent(scope))
        val row = dao.queue(scope.owner, LocalReviewOperation(id, scope.owner.profileId.toString(), scope.owner.privacyEpoch,
            scope.owner.revision, kind, target, encoded, LearningJson.hash(encoded)))
        require(row.status in setOf("pending", "acknowledged") && row.payloadJson == encoded && row.payloadHash == LearningJson.hash(encoded))
        require(api.isCurrent(scope))
        return row
    }
    private fun <T> cached(scope: LearningCallScope, row: LocalReviewOperation, name: String, serializer: DeserializationStrategy<T>): LearningReply<T> =
        LearningReply.Stored(scope, codecs.decodeSource6(name, requireNotNull(row.receiptJson), serializer))
    private fun context(scope: LearningCallScope) = WireSource6CommonProfileContext(scope.owner.profileId.toString(), scope.owner.privacyEpoch)
    private fun requireOwner(scope: LearningCallScope, value: WireSource6CommonProfileContext) = require(value == context(scope))
    private fun ownerKey(scope: LearningCallScope) = "${scope.owner.profileId}:${scope.owner.privacyEpoch}:${scope.owner.revision}"
    private fun streamKey(scope: LearningCallScope, stream: String) = "${ownerKey(scope)}:$stream"
    private fun profileNotOlder(scope: LearningCallScope, value: WireSource6PersonalProfileView): Boolean {
        val old = profiles[ownerKey(scope)] ?: return true
        if (value.version < old.version || value.settings.version < old.settings.version ||
            value.resumeVersion < old.resumeVersion || value.historyCoverage.version < old.historyCoverage.version) return false
        if (value.version == old.version && value.settings.version == old.settings.version &&
            value.resumeVersion == old.resumeVersion && value.historyCoverage.version == old.historyCoverage.version) require(value == old) { "Profile changed without version." }
        return true
    }
    private fun rememberProfile(scope: LearningCallScope, value: WireSource6PersonalProfileView) { profiles[ownerKey(scope)] = value }
    private fun failedSession() = LearningReply.Failed("SESSION_CHANGED", false)
    private suspend fun <T> guarded(scope: LearningCallScope, body: suspend () -> LearningReply<T>): LearningReply<T> = try { body() }
        catch (error: CancellationException) { throw error }
        catch (_: IllegalArgumentException) { if (!api.isCurrent(scope)) failedSession() else LearningReply.Failed("INVALID_CONTRACT", false) }
        catch (_: IllegalStateException) { if (!api.isCurrent(scope)) failedSession() else LearningReply.Failed("IMMUTABLE_OPERATION_CONFLICT", false) }
        catch (_: Exception) { if (!api.isCurrent(scope)) failedSession() else LearningReply.Failed("STORAGE_UNAVAILABLE", true) }
}

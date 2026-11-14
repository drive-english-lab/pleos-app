package io.github.driveenglishlab.pleos.data

import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.data.wire.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Read status says nothing about mastery, playback or presentation completion. */
enum class Source6SpeakingRecordsStatus { UNBOUND, CACHED, LOADING, READY, EMPTY, FAILED }
data class Source6SpeakingRecordsState(
    val owner: OwnerContext? = null,
    val items: List<WireSource6ProgressSpeakingRecordSummary> = emptyList(),
    val selectedId: String? = null,
    val detail: WireSource6ProgressSpeakingRecordView? = null,
    val originalFeedback: WireSource6InferenceAttemptResult? = null,
    val status: Source6SpeakingRecordsStatus = Source6SpeakingRecordsStatus.UNBOUND,
    val snapshotRevision: Long? = null,
    val nextCursor: String? = null,
    val visibilityFilter: String = "visible",
    val locallyHidden: Set<String> = emptySet(),
    val unhidePending: Set<String> = emptySet(),
    val conflicts: Set<String> = emptySet(),
    val errorCode: String? = null,
) {
    val loading get() = status == Source6SpeakingRecordsStatus.LOADING
    val failed get() = status == Source6SpeakingRecordsStatus.FAILED
    val visibleItems get() = items.filter { visibilityFilter == "hidden" || it.lessonId !in locallyHidden && it.lessonId !in unhidePending }
}

/** Protocol adapter: source6 reads, the existing source-free visibility command engine.
 * No answer classification, inference, help observation or lesson/feedback ACK occurs here.
 */
class Source6SpeakingRecordsRepository(
    private val api: IncrementalLearningApi,
    private val codecs: IncrementalContractJson,
    private val database: LearningDatabase,
    private val visibilityCommands: SpeakingRecordsRepository,
) {
    private val lane = Mutex()
    private val revisions = mutableMapOf<String, Long>()
    private val mutable = MutableStateFlow(Source6SpeakingRecordsState())
    val state = mutable.asStateFlow()
    private val dao get() = database.progress()

    suspend fun bind(scope: LearningCallScope): LearningReply<Unit> = lane.withLock {
        guarded(scope) {
            if (!api.isCurrent(scope)) return@guarded sessionChanged()
            val cached = dao.cache(scope.owner.profileId.toString(), scope.owner.privacyEpoch, "source6_speaking_page", "visible")
            val page = cached?.let { codecs.decodeSource6("ProgressSpeakingRecordPage", it.payloadJson, WireSource6ProgressSpeakingRecordPage.serializer()) }
            if (page != null) { requireOwner(scope, page.profileContext); validatePage(page, "visible"); require(page.snapshotRevision >= (revisions[ownerKey(scope)] ?: 0)); revisions[ownerKey(scope)] = page.snapshotRevision }
            if (!api.isCurrent(scope)) return@guarded sessionChanged()
            mutable.value = Source6SpeakingRecordsState(owner = scope.owner, items = page?.items ?: emptyList(),
                snapshotRevision = page?.snapshotRevision, nextCursor = page?.nextCursor,
                status = if (page == null) Source6SpeakingRecordsStatus.READY else Source6SpeakingRecordsStatus.CACHED)
            projectPending(scope)
            LearningReply.Stored(scope, Unit)
        }
    }
    suspend fun clear() = lane.withLock { mutable.value = Source6SpeakingRecordsState(); revisions.clear() }

    suspend fun refresh(scope: LearningCallScope, more: Boolean = false, visibility: String = "visible"): LearningReply<Unit> = lane.withLock {
        guarded(scope) {
            if (!owns(scope)) return@guarded sessionChanged()
            require(visibility in setOf("visible", "hidden"))
            val before = mutable.value
            if (more && before.visibilityFilter != visibility) return@guarded LearningReply.Failed("SNAPSHOT_CHANGED", true)
            val cursor = if (more) before.nextCursor ?: return@guarded LearningReply.Stored(scope, Unit) else null
            mutable.value = before.copy(status = Source6SpeakingRecordsStatus.LOADING, errorCode = null)
            when (val result = api.call("source6", "/v2/profile/speaking-records", "get", scope, null,
                WireSource6ProgressSpeakingRecordPage.serializer(), queryArguments = mapOf(
                    "client_privacy_epoch" to scope.owner.privacyEpoch.toString(), "visibility" to visibility, "cursor" to cursor, "limit" to "20"))) {
                is LearningReply.Failed -> {
                    if (owns(scope)) {
                        val reset = more && result.code in setOf("SNAPSHOT_CHANGED", "RECORD_CONFLICT")
                        mutable.value = mutable.value.copy(status = Source6SpeakingRecordsStatus.FAILED, errorCode = result.code,
                            items = if (reset) emptyList() else before.items, snapshotRevision = if (reset) null else before.snapshotRevision,
                            nextCursor = if (reset) null else before.nextCursor)
                    }
                    result
                }
                is LearningReply.Stored -> {
                    if (result.scope != scope || !owns(scope)) return@guarded sessionChanged()
                    val page = result.value; requireOwner(scope, page.profileContext); validatePage(page, visibility)
                    if (page.snapshotRevision < (revisions[ownerKey(scope)] ?: 0) || more && page.snapshotRevision != before.snapshotRevision)
                        return@guarded snapshotChanged(scope)
                    if (more && page.nextCursor != null && page.nextCursor == cursor) return@guarded snapshotChanged(scope)
                    val existing = if (more) before.items.associateBy { it.lessonId } else emptyMap()
                    for (item in page.items) existing[item.lessonId]?.let { require(it == item) { "Changed record in the same revision." } }
                    val combined = if (more) (before.items + page.items).distinctBy { it.lessonId } else page.items
                    val encoded = codecs.encode("source6", "ProgressSpeakingRecordPage", page, WireSource6ProgressSpeakingRecordPage.serializer())
                    dao.saveCache(scope.owner, LocalProgressCache(scope.owner.profileId.toString(), scope.owner.privacyEpoch, "source6_speaking_page",
                        if (cursor == null) visibility else "$visibility/${LearningJson.hash(cursor)}", page.snapshotRevision, encoded))
                    if (!owns(scope)) return@guarded sessionChanged()
                    revisions[ownerKey(scope)] = page.snapshotRevision
                    // Only the canonical transport page is cached. Scroll aggregation is UI state.
                    mutable.value = mutable.value.copy(items = combined, visibilityFilter = visibility, snapshotRevision = page.snapshotRevision,
                        nextCursor = page.nextCursor, status = if (combined.isEmpty()) Source6SpeakingRecordsStatus.EMPTY else Source6SpeakingRecordsStatus.READY,
                        detail = before.detail?.takeIf { it.snapshotRevision == page.snapshotRevision },
                        selectedId = before.selectedId.takeIf { before.detail?.snapshotRevision == page.snapshotRevision },
                        originalFeedback = before.originalFeedback.takeIf { before.detail?.snapshotRevision == page.snapshotRevision }, errorCode = null)
                    projectPending(scope)
                    LearningReply.Stored(scope, Unit)
                }
            }
        }
    }

    suspend fun detail(scope: LearningCallScope, id: UUID): LearningReply<Unit> = lane.withLock {
        guarded(scope) {
            if (!owns(scope)) return@guarded sessionChanged()
            mutable.value = mutable.value.copy(selectedId = id.toString(), detail = null, originalFeedback = null,
                status = Source6SpeakingRecordsStatus.LOADING, errorCode = null)
            when (val result = api.call("source6", "/v2/profile/speaking-records/{lesson_id}", "get", scope, null,
                WireSource6ProgressSpeakingRecordView.serializer(), pathArguments = mapOf("lesson_id" to id.toString()),
                queryArguments = mapOf("client_privacy_epoch" to scope.owner.privacyEpoch.toString()))) {
                is LearningReply.Failed -> { if (owns(scope)) mutable.value = mutable.value.copy(status = Source6SpeakingRecordsStatus.FAILED, errorCode = result.code); result }
                is LearningReply.Stored -> {
                    if (result.scope != scope || !owns(scope)) return@guarded sessionChanged()
                    val value = result.value; requireOwner(scope, value.profileContext); require(value.record.lessonId == id.toString())
                    validateQuote(value.record)
                    if (value.snapshotRevision < (revisions[ownerKey(scope)] ?: 0)) return@guarded snapshotChanged(scope)
                    dao.saveCache(scope.owner, LocalProgressCache(scope.owner.profileId.toString(), scope.owner.privacyEpoch, "source6_speaking_detail", id.toString(),
                        value.snapshotRevision, codecs.encode("source6", "ProgressSpeakingRecordView", value, WireSource6ProgressSpeakingRecordView.serializer())))
                    if (!owns(scope)) return@guarded sessionChanged()
                    revisions[ownerKey(scope)] = value.snapshotRevision
                    mutable.value = mutable.value.copy(detail = value, selectedId = id.toString(), originalFeedback = null,
                        status = Source6SpeakingRecordsStatus.READY, errorCode = null)
                    LearningReply.Stored(scope, Unit)
                }
            }
        }
    }
    suspend fun closeDetail() = lane.withLock { mutable.value = mutable.value.copy(selectedId = null, detail = null, originalFeedback = null) }

    /** The original stored attempt result only. Opening this never acknowledges its feedback. */
    suspend fun originalFeedback(scope: LearningCallScope, attemptId: UUID? = null): LearningReply<Unit> = lane.withLock {
        guarded(scope) {
            if (!owns(scope)) return@guarded sessionChanged()
            val record = mutable.value.detail?.record ?: return@guarded LearningReply.Failed("RECORD_NOT_LOADED", false)
            mutable.value = mutable.value.copy(originalFeedback = null)
            if (record.sourceAccess.originalFeedback != "available") return@guarded LearningReply.Failed("CONTENT_UNAVAILABLE", false)
            val id = attemptId?.toString() ?: record.representative.attemptId
            val allowed = listOfNotNull(record.representative.attemptId, record.firstAttempt?.feedbackAttemptId, record.retryAttempt?.feedbackAttemptId)
            require(id in allowed)
            when (val result = api.call("source6", "/v2/attempts/{attempt_id}", "get", scope, null,
                WireSource6InferenceAttemptResult.serializer(), pathArguments = mapOf("attempt_id" to id))) {
                is LearningReply.Failed -> result
                is LearningReply.Stored -> {
                    if (result.scope != scope || !owns(scope)) return@guarded sessionChanged()
                    requireOwner(scope, result.value.profileContext)
                    require(result.value.attemptId == id && result.value.contentRef == record.contentRef)
                    result.value.feedback?.let { require(it.attemptId == id && it.contentRef == record.contentRef) }
                    mutable.value = mutable.value.copy(originalFeedback = result.value, errorCode = null)
                    LearningReply.Stored(scope, Unit)
                }
            }
        }
    }

    /** Existing schema5 visibility command has no source ref, so it serves both source kinds. */
    suspend fun visibility(scope: LearningCallScope, id: UUID, hidden: Boolean, operationId: UUID = UUID.randomUUID()): LearningReply<Unit> = lane.withLock {
        guarded(scope) {
            if (!owns(scope)) return@guarded sessionChanged()
            val kind = "visibility:$id"
            val previous = dao.unsettled(scope.owner.profileId.toString(), scope.owner.privacyEpoch)
                .lastOrNull { it.kind == kind && !it.isolated && it.ownerRevision == scope.owner.revision }
            if (previous != null) {
                val original = featureJson.decodeFromString(WireProgressVisibilityRequest.serializer(), previous.payloadJson)
                if (previous.state == "conflict" || original.hidden != hidden) return@guarded LearningReply.Failed("VISIBILITY_PENDING", false)
            } else {
                val expected = mutable.value.detail?.record?.takeIf { it.lessonId == id.toString() }?.visibility
                    ?: mutable.value.items.singleOrNull { it.lessonId == id.toString() }?.visibility
                    ?: return@guarded LearningReply.Failed("RECORD_NOT_LOADED", false)
                val request = WireProgressVisibilityRequest("5", "1", operationId.toString(), scope.owner.profileWire(), expected.version, hidden)
                val encoded = featureJson.encodeToString(WireProgressVisibilityRequest.serializer(), request)
                dao.queue(scope.owner, LocalProgressJournal(request.operationId, scope.owner.profileId.toString(), scope.owner.privacyEpoch,
                    scope.owner.revision, kind, encoded, LearningJson.hash(encoded), "pending", false))
            }
            projectPending(scope) // Hide immediately; restoring waits for the server ACK.
            flushLocked(scope)
        }
    }
    suspend fun flush(scope: LearningCallScope): LearningReply<Unit> = lane.withLock { guarded(scope) { flushLocked(scope) } }
    private suspend fun flushLocked(scope: LearningCallScope): LearningReply<Unit> {
        if (!owns(scope)) return sessionChanged()
        val before = dao.unsettled(scope.owner.profileId.toString(), scope.owner.privacyEpoch)
            .filter { it.kind.startsWith("visibility:") && !it.isolated && it.ownerRevision == scope.owner.revision }
        val result = visibilityCommands.flush(scope) // Reuse its real API/journal/CAS/retry policy.
        if (!owns(scope)) return sessionChanged()
        val settled = before.filter { dao.operation(it.operationId)?.state == "acknowledged" }.map { it.kind.removePrefix("visibility:") }.toSet()
        if (settled.isNotEmpty()) mutable.value = mutable.value.copy(items = mutable.value.items.filterNot { it.lessonId in settled }, nextCursor = null,
            detail = mutable.value.detail?.takeUnless { it.record.lessonId in settled },
            selectedId = mutable.value.selectedId?.takeUnless { it in settled }, originalFeedback = mutable.value.originalFeedback.takeIf { mutable.value.selectedId !in settled })
        projectPending(scope)
        if (result is LearningReply.Failed) mutable.value = mutable.value.copy(errorCode = result.code)
        return result
    }

    private suspend fun projectPending(scope: LearningCallScope) {
        val rows = dao.unsettled(scope.owner.profileId.toString(), scope.owner.privacyEpoch).filter {
            it.kind.startsWith("visibility:") && !it.isolated && it.ownerRevision == scope.owner.revision }
        val latest = rows.associateBy { it.kind.removePrefix("visibility:") }
        val hidden = latest.filterValues { featureJson.decodeFromString(WireProgressVisibilityRequest.serializer(), it.payloadJson).hidden }.keys
        if (owns(scope)) mutable.value = mutable.value.copy(locallyHidden = hidden, unhidePending = latest.keys - hidden,
            conflicts = latest.filterValues { it.state == "conflict" }.keys)
    }
    private fun validatePage(page: WireSource6ProgressSpeakingRecordPage, visibility: String) {
        require(page.visibilityFilter == visibility && page.items.map { it.lessonId }.toSet().size == page.items.size)
        require(page.items.all { it.visibility.hidden == (visibility == "hidden") })
    }
    private fun validateQuote(record: WireSource6ProgressSpeakingRecordDetail) {
        record.representativeQuote?.let { quote ->
            require(quote.attemptId == record.representative.attemptId && record.representative.formStatus == "confirmed")
            val count = quote.text.codePointCount(0, quote.text.length).toLong()
            for (excerpt in quote.excerpts) {
                require(excerpt.startCodePoint >= 0 && excerpt.startCodePoint < excerpt.endCodePoint && excerpt.endCodePoint <= count)
                val start = quote.text.offsetByCodePoints(0, excerpt.startCodePoint.toInt())
                val end = quote.text.offsetByCodePoints(0, excerpt.endCodePoint.toInt())
                require(quote.text.substring(start, end) == excerpt.text)
            }
        }
    }
    private fun ownerKey(scope: LearningCallScope) = "${scope.owner.profileId}:${scope.owner.privacyEpoch}:${scope.owner.revision}"
    private fun requireOwner(scope: LearningCallScope, context: WireSource6CommonProfileContext) =
        require(context.profileId == scope.owner.profileId.toString() && context.privacyEpoch == scope.owner.privacyEpoch)
    private suspend fun owns(scope: LearningCallScope) = api.isCurrent(scope) && mutable.value.owner == scope.owner
    private fun sessionChanged() = LearningReply.Failed("SESSION_CHANGED", false)
    private suspend fun snapshotChanged(scope: LearningCallScope): LearningReply<Unit> {
        if (owns(scope)) mutable.value = mutable.value.copy(items = emptyList(), nextCursor = null, snapshotRevision = null,
            status = Source6SpeakingRecordsStatus.FAILED, errorCode = "SNAPSHOT_CHANGED")
        return LearningReply.Failed("SNAPSHOT_CHANGED", true)
    }
    private suspend fun <T> guarded(scope: LearningCallScope, body: suspend () -> LearningReply<T>): LearningReply<T> = try { body() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            if (owns(scope)) mutable.value = mutable.value.copy(status = Source6SpeakingRecordsStatus.FAILED, errorCode = "INVALID_RECORD", originalFeedback = null)
            LearningReply.Failed(if (api.isCurrent(scope)) "INVALID_RECORD" else "SESSION_CHANGED", false)
        }
}

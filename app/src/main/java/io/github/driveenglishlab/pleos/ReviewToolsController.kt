package io.github.driveenglishlab.pleos

import io.github.driveenglishlab.pleos.data.*
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.platform.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** An actual current approved source read, including the precise script/phase being inspected. */
data class ReviewToolsSource(val owner: OwnerContext, val selectionId: UUID, val content: WireSource6ContentRef6,
    val phase: SegmentPhase, val script: String, val segments: SegmentManifest, val rightsRevision: String,
    val rightsCurrent: Boolean, val contextToken: UUID) {
    init { require(phase != SegmentPhase.ARTICLE_OFFER && rightsRevision.isNotBlank()); segments.validate(script) }
    override fun toString() = "ReviewToolsSource(selection=$selectionId, content=redacted)"
}
data class ReviewToolsOpenSnapshot(val original: ReviewReturnSnapshot?, val resumeVersion: Long,
    val savedAndStopped: Boolean) { init { require(resumeVersion >= 0) } }
data class ReviewToolsPreviewDraft(val revision: Long, val bindingToken: UUID, val choice: SettingsPreviewChoice) {
    init { require(revision >= 1) }
}
data class RegisteredSentenceMeaning(val ref: String, val hash: String, val text: String, val version: Long,
    val manifestId: UUID, val sentenceId: UUID, val language: SegmentLanguage, val registrationEvidenceRef: String) {
    init {
        require(version >= 1 && language == SegmentLanguage.KO && ref.isNotBlank() && text.isNotBlank() && hash == segmentSha256(text))
        require(registrationEvidenceRef.codePointCount(0, registrationEvidenceRef.length) in 1..160)
    }
    override fun toString() = "RegisteredSentenceMeaning(content=redacted)"
}
data class SentenceMeaningWitness(val toolToken: UUID, val meaning: RegisteredSentenceMeaning,
    val startCodePoint: Int, val endCodePoint: Int, val evidenceRef: UUID)

/** Real source/return/observer adapters only. Default missing capabilities stay unavailable. */
interface ReviewToolsPorts {
    suspend fun source(scope: LearningCallScope): ReviewToolsSource? = null
    suspend fun sameSource(scope: LearningCallScope, source: ReviewToolsSource): Boolean = false
    /** For current news, must save the exact original and confirm physical stop before returning. */
    suspend fun beforeOpen(scope: LearningCallScope, source: ReviewToolsSource?): ReviewToolsOpenSnapshot? = null
    /** Local immutable cancellation+pending original command; called before manifest/network work. */
    suspend fun persistOfferCancellation(scope: LearningCallScope, operationId: UUID, context: ReviewContext,
        original: ReviewReturnSnapshot): Boolean = false
    suspend fun draft(scope: LearningCallScope): ReviewToolsPreviewDraft? = null
    suspend fun approvedDemo(scope: LearningCallScope): SettingsDemoManifest? = null
    val meaningObserverAvailable: Boolean get() = false
    suspend fun registeredMeaning(scope: LearningCallScope, source: ReviewToolsSource, manifest: WireReviewSentenceManifest,
        entry: WireReviewSentenceEntry): RegisteredSentenceMeaning? = null
    /** Registers only actual visible ranges with the existing review observation/fence journal. */
    suspend fun observedMeaning(scope: LearningCallScope, source: ReviewToolsSource, manifest: WireReviewSentenceManifest,
        entry: WireReviewSentenceEntry, witness: SentenceMeaningWitness): Boolean = false
}
enum class ReviewToolsStatus { CLOSED, LOADING, SENTENCE_READY, SPEECH_UNAVAILABLE, MEANING_READY, MEANING_UNAVAILABLE, PREVIEW, UNAVAILABLE }
data class ReviewToolsState(val owner: OwnerContext? = null, val token: UUID? = null, val status: ReviewToolsStatus = ReviewToolsStatus.CLOSED,
    val manifest: WireReviewSentenceManifest? = null, val sentence: WireReviewSentenceEntry? = null,
    val meaning: RegisteredSentenceMeaning? = null, val meaningObserved: Boolean = false,
    val commandPending: Boolean = false, val reason: String? = null)

/** Presentation binder only. SessionCoordinator owns policy; AuxiliaryPlayback owns one shared
 * Pleos/lease runtime. This controller never creates a selection, lesson, answer or model result.
 */
class ReviewToolsController(private val coordinator: SessionCoordinator, private val profiles: LearningSessionSource,
    private val api: IncrementalLearningApi, private val codecs: IncrementalContractJson,
    private val reviews: ReviewRepository, private val auxiliary: AuxiliaryPlayback,
    private val ports: ReviewToolsPorts = object : ReviewToolsPorts {}) {
    private data class SentenceSession(val token: UUID, val scope: LearningCallScope, val source: ReviewToolsSource,
        val original: ReviewReturnSnapshot, val context: ReviewContext, val resumeVersion: Long,
        val manifest: WireReviewSentenceManifest? = null, val entry: WireReviewSentenceEntry? = null,
        val enter: WireReviewReviewCommandRequest? = null, val acknowledged: Boolean = false)
    private data class PendingClose(val scope: LearningCallScope, val selectionId: UUID, val request: WireReviewReviewCommandRequest)
    private val lane = Mutex()
    @Volatile private var pendingClose: PendingClose? = null
    @Volatile private var sentenceSession: SentenceSession? = null
    @Volatile private var desired: UUID? = null
    @Volatile private var lookup: UUID? = null
    private val mutable = MutableStateFlow(ReviewToolsState())
    val state = mutable.asStateFlow()
    val playbackState = auxiliary.state

    /** Read-only discovery. Opening a card/list alone creates no observation or mutation. */
    suspend fun loadSentences(): Boolean {
        val scope = profiles.current() ?: return false
        val source = ports.source(scope) ?: return unavailable(scope.owner, null, "CURRENT_SCRIPT_UNAVAILABLE")
        if (!canOpen(scope) || source.owner != scope.owner || !source.rightsCurrent) return unavailable(scope.owner, null, "REVIEW_RESTRICTED")
        val token = UUID.randomUUID()
        lane.withLock { lookup = token; if (sentenceSession == null) mutable.value = ReviewToolsState(scope.owner, token, ReviewToolsStatus.LOADING) }
        val manifest = readManifest(scope, source) ?: run {
            lane.withLock { if (lookup == token && sentenceSession == null) mutable.value = ReviewToolsState(scope.owner, token, ReviewToolsStatus.UNAVAILABLE, reason = "SENTENCE_MANIFEST_UNAVAILABLE") }
            return false
        }
        if (lookup != token || !current(scope) || !ports.sameSource(scope, source)) return false
        lane.withLock { if (lookup == token && sentenceSession == null) mutable.value = ReviewToolsState(scope.owner, token, ReviewToolsStatus.SENTENCE_READY, manifest) }
        return true
    }

    /** Explicit sentence selection cancels/fences the old offer before loading can fail. */
    suspend fun openSentence(sentenceId: UUID): Boolean {
        val scope = profiles.current() ?: return false
        if (!canOpen(scope)) return false
        val token = UUID.randomUUID()
        lane.withLock { desired = token; sentenceSession = null; mutable.value = ReviewToolsState(scope.owner, token, ReviewToolsStatus.LOADING) }
        coordinator.suspendForReviewTools(scope.owner)
        auxiliary.close()
        val source = ports.source(scope) ?: return unavailable(scope.owner, token, "CURRENT_SCRIPT_UNAVAILABLE")
        if (source.owner != scope.owner || !source.rightsCurrent || !same(token, scope, source)) return unavailable(scope.owner, token, "SOURCE_CHANGED")
        val opened = ports.beforeOpen(scope, source) ?: return unavailable(scope.owner, token, "ORIGINAL_SAVE_OR_STOP_UNCONFIRMED")
        val original = opened.original ?: return unavailable(scope.owner, token, "ORIGINAL_CHECKPOINT_UNAVAILABLE")
        if (!opened.savedAndStopped || original.selectionId != source.selectionId || original.profile != ProfileContext(scope.owner.profileId, scope.owner.privacyEpoch))
            return unavailable(scope.owner, token, "ORIGINAL_SAVE_OR_STOP_UNCONFIRMED")
        var context = coordinator.snapshot().reviewPolicy.context?.takeIf { it.selectionId == source.selectionId }
        if (context == null) {
            val response = reviews.read(scope, source.selectionId.toString()) as? LearningReply.Stored
                ?: return unavailable(scope.owner, token, "REVIEW_CONTEXT_UNAVAILABLE")
            context = response.value.context.domainReview(coordinator.snapshot().listening)
            if (!coordinator.bindReviewContext(scope.owner, context)) return false
        }
        val cancelled = original.phase != SegmentPhase.ARTICLE_OFFER || context.offerId == null ||
            ports.persistOfferCancellation(scope, UUID.nameUUIDFromBytes("local-review-cancel:$token".toByteArray()), context, original)
        if (!cancelled || !same(token, scope, source)) return unavailable(scope.owner, token, "LOCAL_OFFER_CANCELLATION_PENDING")
        if (coordinator.enterReviewAuxiliary(scope.owner, ReviewAuxiliaryKind.SENTENCE, original, cancelled) == null) return false
        val pending = SentenceSession(token, scope, source, original, context, opened.resumeVersion)
        lane.withLock { if (desired == token) sentenceSession = pending }
        val manifest = readManifest(scope, source) ?: return unavailable(scope.owner, token, "SENTENCE_MANIFEST_UNAVAILABLE")
        val entry = manifest.entries.singleOrNull { it.sentenceId == sentenceId.toString() }
            ?: return unavailable(scope.owner, token, "EXACT_SENTENCE_UNAVAILABLE")
        validateSentence(source, manifest, entry)
        val request = WireReviewReviewCommandRequest(1, scope.owner.profileId.toString(), scope.owner.privacyEpoch, token.toString(),
            context.version, opened.resumeVersion, context.offerId?.toString(), "enter_sentence", WireReviewEnterSentenceArgs(manifest.manifestId, entry.sentenceId))
        val current = pending.copy(manifest = manifest, entry = entry, enter = request)
        lane.withLock { if (desired == token) {
            sentenceSession = current
            mutable.value = ReviewToolsState(scope.owner, token, ReviewToolsStatus.SENTENCE_READY, manifest, entry, commandPending = true)
        } }
        return enterSentence(current)
    }

    private suspend fun enterSentence(session: SentenceSession): Boolean {
        if (!same(session.token, session.scope, session.source)) return false
        val request = session.enter ?: return false
        val reply = reviews.command(session.scope, session.source.selectionId.toString(), request)
        if (!same(session.token, session.scope, session.source)) return false
        if (reply !is LearningReply.Stored) return unavailable(session.scope.owner, session.token, "ENTER_SENTENCE_PENDING", true)
        val context = reply.value.context.domainReview(coordinator.snapshot().listening)
        if (!coordinator.bindReviewContext(session.scope.owner, context)) return false
        val original = context.returnSnapshot ?: session.original
        require(original.selectionId == session.source.selectionId && original.profile == session.original.profile)
        val accepted = session.copy(context = context, original = original, resumeVersion = reply.value.resumeVersion, acknowledged = true)
        lane.withLock { if (desired == session.token) {
            sentenceSession = accepted
            mutable.value = mutable.value.copy(commandPending = false, status = ReviewToolsStatus.SENTENCE_READY, reason = null)
        } }
        return true
    }
    /** Original request/op/CAS only; no automatic rebase or new enter on a lost response. */
    suspend fun retrySentence(): Boolean = lane.withLock { sentenceSession }?.let { enterSentence(it) } ?: false

    suspend fun speakSentence(): Boolean {
        val session = lane.withLock { sentenceSession } ?: return false
        val entry = session.entry ?: return false
        val manifest = session.manifest ?: return false
        if (!session.acknowledged || entry.speechAvailability != "available" || !same(session.token, session.scope, session.source))
            return unavailable(session.scope.owner, session.token, "SENTENCE_SPEECH_UNAVAILABLE")
        validateSentence(session.source, manifest, entry)
        val request = SentenceSpeechRequest(session.scope.owner, session.token, session.source.selectionId, sourceKey(session.source.content), session.source.script,
            session.source.segments, session.source.phase, UUID.fromString(entry.sentenceId), checkedInt(entry.textStart), checkedInt(entry.textEnd),
            entry.segmentIds.map(UUID::fromString), session.source.rightsRevision, session.original)
        val spoken = auxiliary.sentence(request)
        if (!same(session.token, session.scope, session.source)) { auxiliary.close(); return false }
        if (!spoken) lane.withLock { if (desired == session.token) mutable.value = mutable.value.copy(status = ReviewToolsStatus.SPEECH_UNAVAILABLE,
            reason = auxiliary.state.value.reason ?: "APPROVED_SENTENCE_BINDING_UNAVAILABLE") }
        return spoken
    }

    suspend fun showMeaning(): Boolean {
        val session = lane.withLock { sentenceSession } ?: return false
        val entry = session.entry ?: return false
        val manifest = session.manifest ?: return false
        if (!session.acknowledged || entry.meaningAvailability != "available" || entry.meaningRef == null || entry.meaningHash == null ||
            !ports.meaningObserverAvailable || !same(session.token, session.scope, session.source)) return meaningUnavailable(session.token, "REGISTERED_MEANING_UNAVAILABLE")
        val meaning = ports.registeredMeaning(session.scope, session.source, manifest, entry)
            ?: return meaningUnavailable(session.token, "REGISTERED_MEANING_UNAVAILABLE")
        require(meaning.ref == entry.meaningRef && meaning.hash == entry.meaningHash && meaning.manifestId.toString() == manifest.manifestId && meaning.sentenceId.toString() == entry.sentenceId)
        if (!same(session.token, session.scope, session.source)) return false
        auxiliary.pause()
        lane.withLock { if (desired == session.token) mutable.value = mutable.value.copy(status = ReviewToolsStatus.MEANING_READY, meaning = meaning, meaningObserved = false, reason = null) }
        return true
    }
    /** The actual visible component calls this with its registered observation evidence. */
    suspend fun meaningVisible(token: UUID, startCodePoint: Int, endCodePoint: Int, evidenceRef: UUID): Boolean {
        val session = lane.withLock { sentenceSession?.takeIf { it.token == token } } ?: return false
        val meaning = mutable.value.meaning ?: return false
        val count = meaning.text.codePointCount(0, meaning.text.length)
        if (startCodePoint !in 0..count || endCodePoint !in startCodePoint..count || endCodePoint == startCodePoint ||
            !same(token, session.scope, session.source) || !coordinator.snapshot().access.window.visible) return false
        val actual = ports.observedMeaning(session.scope, session.source, requireNotNull(session.manifest), requireNotNull(session.entry),
            SentenceMeaningWitness(token, meaning, startCodePoint, endCodePoint, evidenceRef))
        if (actual && same(token, session.scope, session.source)) lane.withLock { if (desired == token) mutable.value = mutable.value.copy(meaningObserved = true) }
        return actual
    }

    /** No demo calls a selection/lesson/answer/preparation/learning sync API. Missing asset is normal unavailable. */
    suspend fun preview(): Boolean {
        val scope = profiles.current() ?: return false
        if (!canOpen(scope)) return false
        val draft = ports.draft(scope) ?: return unavailable(scope.owner, null, "PREVIEW_DRAFT_UNAVAILABLE")
        val demo = ports.approvedDemo(scope) ?: return unavailable(scope.owner, null, "REVIEWED_DEMO_UNAVAILABLE")
        if (!demo.approved(System.currentTimeMillis())) return unavailable(scope.owner, null, "REVIEWED_DEMO_UNAVAILABLE")
        lane.withLock { desired = draft.bindingToken; sentenceSession = null; mutable.value = ReviewToolsState(scope.owner, draft.bindingToken, ReviewToolsStatus.LOADING) }
        coordinator.suspendForReviewTools(scope.owner)
        auxiliary.close()
        val source = ports.source(scope)
        val opened = ports.beforeOpen(scope, source) ?: return unavailable(scope.owner, draft.bindingToken, "ORIGINAL_SAVE_OR_STOP_UNCONFIRMED")
        if (!opened.savedAndStopped || !current(scope) || desired != draft.bindingToken) return false
        if (opened.original != null) {
            val policy = coordinator.snapshot().reviewPolicy.context ?: return unavailable(scope.owner, draft.bindingToken, "REVIEW_CONTEXT_UNAVAILABLE")
            val cancelled = opened.original.phase != SegmentPhase.ARTICLE_OFFER || policy.offerId == null ||
                ports.persistOfferCancellation(scope, UUID.nameUUIDFromBytes("local-preview-cancel:${draft.bindingToken}".toByteArray()), policy, opened.original)
            if (!cancelled || coordinator.enterReviewAuxiliary(scope.owner, ReviewAuxiliaryKind.PREVIEW, opened.original, cancelled) == null) return false
        }
        val latest = ports.draft(scope)
        if (latest != draft || !current(scope) || desired != draft.bindingToken) return false
        val started = auxiliary.preview(scope.owner, draft.revision, draft.bindingToken, demo, draft.choice, opened.original)
        if (!current(scope) || desired != draft.bindingToken) { auxiliary.close(); return false }
        lane.withLock { if (desired == draft.bindingToken) mutable.value = ReviewToolsState(scope.owner, draft.bindingToken,
            if (started) ReviewToolsStatus.PREVIEW else ReviewToolsStatus.UNAVAILABLE, reason = if (started) null else auxiliary.state.value.reason) }
        return started
    }

    /** Local presentation closes immediately. Original immutable close request remains in ReviewJournal on failure. */
    suspend fun close(): Boolean {
        val session = lane.withLock {
            val old = sentenceSession; desired = null; lookup = null; sentenceSession = null; mutable.value = ReviewToolsState(); old
        }
        val auxiliaryOwner = auxiliary.state.value.binding?.owner
        auxiliary.close()
        val owner = session?.scope?.owner ?: auxiliaryOwner
        owner?.let { coordinator.closeReviewAuxiliary(it) }
        if (session == null || !current(session.scope)) return session == null && pendingClose == null
        val request = WireReviewReviewCommandRequest(1, session.scope.owner.profileId.toString(), session.scope.owner.privacyEpoch,
            UUID.nameUUIDFromBytes("close-review:${session.token}".toByteArray()).toString(), session.context.version,
            session.resumeVersion, session.context.offerId?.toString(), "close_auxiliary", WireReviewEmptyArgs())
        val close = PendingClose(session.scope, session.source.selectionId, request)
        pendingClose = close
        return retryClose()
    }
    /** Same original close op/CAS only. A conflict requires an explicit refreshed command elsewhere. */
    suspend fun retryClose(): Boolean {
        val close = pendingClose ?: return true
        if (!current(close.scope)) return false
        val acknowledged = reviews.command(close.scope, close.selectionId.toString(), close.request) is LearningReply.Stored
        lane.withLock {
            if (pendingClose == close && current(close.scope)) {
                if (acknowledged) pendingClose = null
                if (mutable.value.status == ReviewToolsStatus.CLOSED) mutable.value = mutable.value.copy(commandPending = !acknowledged,
                    reason = if (acknowledged) null else "CLOSE_AUXILIARY_PENDING")
            }
        }
        return acknowledged
    }

    /** Bind this to AuxiliaryPlayback.authorized; it fences each segment after draft/source changes. */
    suspend fun auxiliaryCurrent(binding: AuxiliaryBinding): Boolean {
        val scope = profiles.current()?.takeIf { it.owner == binding.owner } ?: return false
        if (desired != binding.token || !canOpen(scope)) return false
        return when (binding) {
            is AuxiliaryBinding.Sentence -> {
                val session = sentenceSession ?: return false
                session.acknowledged && session.token == binding.token && session.scope == scope &&
                    session.source.selectionId == binding.request.selectionId && session.source.segments == binding.request.manifest &&
                    session.entry?.sentenceId == binding.request.sentenceId.toString() && ports.sameSource(scope, session.source)
            }
            is AuxiliaryBinding.Preview -> {
                val draft = ports.draft(scope) ?: return false
                val demo = ports.approvedDemo(scope) ?: return false
                SettingsPreviewPolicy.stillCurrent(binding.run, scope.owner, draft.revision, draft.bindingToken, demo, System.currentTimeMillis()) &&
                    draft.choice == binding.plan.choice && demo.hash == binding.manifest.hash
            }
        }
    }
    /** Call on owner/vehicle/window/draft/source changes; restoration never starts a tool. */
    suspend fun recheck() {
        val binding = auxiliary.state.value.binding
        if (binding != null && !auxiliaryCurrent(binding)) close()
        else {
            val owner = mutable.value.owner
            if (owner != null && (profiles.current()?.owner != owner || !coordinator.snapshot().access.detailsOpen)) close()
            else auxiliary.recheck()
        }
    }

    private suspend fun readManifest(scope: LearningCallScope, source: ReviewToolsSource): WireReviewSentenceManifest? =
        try { readManifestChecked(scope, source) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }

    private suspend fun readManifestChecked(scope: LearningCallScope, source: ReviewToolsSource): WireReviewSentenceManifest? {
        val ref = source.content
        val kind = when (ref) { is WireSource6LegacyContentRef6 -> "legacy_bundle"; is WireSource6PreparedContentRef6 -> "prepared_components" }
        val reply = api.call("review", "/v1/content/sentence-manifests/{script_id}", "get", scope, null, WireReviewSentenceManifestResponse.serializer(),
            mapOf("script_id" to source.segments.scriptId.toString()), mapOf("source_kind" to kind,
                "bundle_id" to if (ref is WireSource6LegacyContentRef6) ref.legacyRef.bundleId else "null",
                "material_generation_id" to if (ref is WireSource6PreparedContentRef6) ref.materialGenerationId else "null",
                "segments_version" to source.segments.segmentsVersion.toString(), "script_text_hash" to source.segments.scriptHash))
        val result = (reply as? LearningReply.Stored)?.value ?: return null
        if (result.availability != "available") return null
        val manifest = result.manifest ?: return null
        require(manifest.sourceKind == kind && manifest.scriptId == source.segments.scriptId.toString() && manifest.textHash == source.segments.scriptHash &&
            manifest.segmentsManifestId == source.segments.manifestId.toString() && manifest.segmentsVersion == source.segments.segmentsVersion.toLong() && manifest.segmentsHash == source.segments.manifestHash &&
            manifest.rightsRevision == source.rightsRevision)
        when (ref) {
            is WireSource6LegacyContentRef6 -> require(manifest.bundleId == ref.legacyRef.bundleId && manifest.materialGenerationId == null)
            is WireSource6PreparedContentRef6 -> require(manifest.materialGenerationId == ref.materialGenerationId && manifest.bundleId == null)
        }
        val exact = codecs.parse("review", codecs.encode("review", "SentenceManifest", manifest, WireReviewSentenceManifest.serializer())).jsonObject
        require(manifest.hash == segmentSha256(canonical(JsonObject(exact - "hash"))))
        require(manifest.entries.map { it.sentenceId }.toSet() == source.segments.segments.map { it.sentenceId.toString() }.toSet())
        manifest.entries.forEach { validateSentence(source, manifest, it) }
        return manifest
    }
    private fun validateSentence(source: ReviewToolsSource, manifest: WireReviewSentenceManifest, entry: WireReviewSentenceEntry) {
        require(manifest.entries.count { it.sentenceId == entry.sentenceId } == 1 && entry.language == source.segments.language.name.lowercase())
        source.segments.sentenceSegments(UUID.fromString(entry.sentenceId), checkedInt(entry.textStart), checkedInt(entry.textEnd), entry.segmentIds.map(UUID::fromString))
        require((entry.meaningRef == null) == (entry.meaningHash == null))
    }
    private fun sourceKey(ref: WireSource6ContentRef6): SegmentSourceKey {
        val value = codecs.parse("source6", codecs.encode("source6", "ContentRef6", ref, WireSource6ContentRef6.serializer()))
        return SegmentSourceKey(if (ref is WireSource6LegacyContentRef6) "legacy_bundle" else "prepared_components", segmentSha256(canonical(value)))
    }
    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(",", "{", "}") { JsonPrimitive(it.key).toString() + ":" + canonical(it.value) }
        is JsonArray -> value.joinToString(",", "[", "]") { canonical(it) }
        else -> value.toString()
    }
    private fun checkedInt(value: Long): Int { require(value in 0L..Int.MAX_VALUE.toLong()); return value.toInt() }
    private suspend fun canOpen(scope: LearningCallScope): Boolean = current(scope) && coordinator.snapshot().access.detailsOpen
    private suspend fun current(scope: LearningCallScope): Boolean = profiles.current() == scope && api.isCurrent(scope) && coordinator.snapshot().owner == scope.owner
    private suspend fun same(token: UUID, scope: LearningCallScope, source: ReviewToolsSource): Boolean =
        desired == token && current(scope) && coordinator.snapshot().access.detailsOpen && ports.sameSource(scope, source)
    private suspend fun unavailable(owner: OwnerContext, token: UUID?, reason: String, pending: Boolean = false): Boolean = lane.withLock {
        if (token == null || desired == token) mutable.value = if (coordinator.state.value.owner == owner)
            mutable.value.copy(owner = owner, token = token, status = ReviewToolsStatus.UNAVAILABLE, reason = reason, commandPending = pending)
            else ReviewToolsState(status = ReviewToolsStatus.UNAVAILABLE, reason = "OWNER_CHANGED")
        false
    }
    private suspend fun meaningUnavailable(token: UUID, reason: String): Boolean = lane.withLock {
        if (desired == token) mutable.value = mutable.value.copy(status = ReviewToolsStatus.MEANING_UNAVAILABLE, meaning = null, meaningObserved = false, reason = reason)
        false
    }
}

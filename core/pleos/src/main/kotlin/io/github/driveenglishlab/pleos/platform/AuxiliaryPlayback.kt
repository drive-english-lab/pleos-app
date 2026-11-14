package io.github.driveenglishlab.pleos.platform

import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Save the exact original work and confirm its physical output stopped. Null is onboarding;
 * it still requires confirming no competing output. This port never resumes original playback.
 */
fun interface AuxiliaryOriginalPort { suspend fun saveAndStop(owner: OwnerContext, original: ReviewReturnSnapshot?): Boolean }

data class SentenceSpeechRequest(val owner: OwnerContext, val bindingToken: UUID, val selectionId: UUID,
    val source: SegmentSourceKey, val script: String, val manifest: SegmentManifest, val phase: SegmentPhase, val sentenceId: UUID,
    val startCodePoint: Int, val endCodePoint: Int, val segmentIds: List<UUID>, val rightsEvidenceRef: String,
    val original: ReviewReturnSnapshot) {
    init {
        require(phase != SegmentPhase.ARTICLE_OFFER && source.kind != "settings_demo" && original.profile == ProfileContext(owner.profileId, owner.privacyEpoch) && original.selectionId == selectionId)
        require(rightsEvidenceRef.codePointCount(0, rightsEvidenceRef.length) in 1..160)
        manifest.validate(script); manifest.sentenceSegments(sentenceId, startCodePoint, endCodePoint, segmentIds)
    }
    override fun toString() = "SentenceSpeechRequest(content=redacted, token=$bindingToken)"
}

sealed interface AuxiliaryBinding {
    val owner: OwnerContext
    val token: UUID
    data class Sentence(val request: SentenceSpeechRequest) : AuxiliaryBinding {
        override val owner get() = request.owner
        override val token get() = request.bindingToken
    }
    data class Preview(val run: PreviewRun, val manifest: SettingsDemoManifest, val plan: SettingsPreviewPlan,
        val clipIndex: Int) : AuxiliaryBinding {
        override val owner get() = run.owner
        override val token get() = run.bindingToken
    }
}
enum class AuxiliaryPlaybackStatus { IDLE, PREPARING, PLAYING, PAUSED, COMPLETED, UNAVAILABLE, CLOSED }
data class AuxiliaryPlaybackState(val binding: AuxiliaryBinding? = null, val status: AuxiliaryPlaybackStatus = AuxiliaryPlaybackStatus.IDLE,
    val reason: String? = null, val currentSegment: Int? = null, val totalSegments: Int? = null)

/** Uses the injected shared process lease and the existing segment runtime/Pleos gateway only.
 * A missing sentence journal means no approved review binding: it is unavailable, never mapped
 * onto a main source6 run. Preview uses an explicitly local in-memory journal with zero sync.
 */
class AuxiliaryPlayback(private val speech: SegmentSpeechPort, private val leases: SegmentOutputLeasePort,
    private val scope: CoroutineScope, private val originals: AuxiliaryOriginalPort,
    private val access: () -> AccessState, private val currentOwner: () -> OwnerContext?,
    private val authorized: suspend (AuxiliaryBinding) -> Boolean,
    private val sentenceJournal: SegmentJournalPort? = null, private val nowEpochMs: () -> Long = System::currentTimeMillis) {
    private data class Active(val binding: AuxiliaryBinding, val program: SegmentProgram)
    private val lane = Mutex()
    private val starts = Mutex()
    private val stops = Mutex()
    @Volatile private var desired: UUID? = null
    @Volatile private var active: Active? = null
    @Volatile private var retiring: Active? = null
    @Volatile private var closed = false
    private val mutable = MutableStateFlow(AuxiliaryPlaybackState())
    val state = mutable.asStateFlow()
    private fun bound(snapshot: SegmentPlaybackSnapshot): Active? = listOfNotNull(active, retiring).firstOrNull {
        it.program.owner == snapshot.program.owner && it.program.selectionId == snapshot.program.selectionId && it.program.source == snapshot.program.source &&
            it.program.manifest == snapshot.program.manifest && it.program.purpose == snapshot.program.purpose && it.program.phase == snapshot.program.phase
    }
    private val journal = object : SegmentJournalPort {
        override suspend fun save(snapshot: SegmentPlaybackSnapshot) {
            val original = bound(snapshot) ?: return
            if (original.binding is AuxiliaryBinding.Sentence) sentenceJournal?.save(snapshot)
            // Preview facts are transient only. No Room/outbox/selection/lesson/attempt API.
        }
        override suspend fun prepareOutput(snapshot: SegmentPlaybackSnapshot): SegmentRun? {
            val original = active?.takeIf { bound(snapshot) === it } ?: return null
            if (!permitted(original)) return null
            return when (val binding = original.binding) {
                is AuxiliaryBinding.Sentence -> sentenceJournal?.prepareOutput(snapshot)
                is AuxiliaryBinding.Preview -> {
                    val request = snapshot.activeRequest ?: return null
                    require(request.purpose == SegmentPurpose.SETTINGS_PREVIEW && request.source.kind == "settings_demo")
                    // A local request binding is not an SDK completion. Actual completion still
                    // comes exclusively through the reviewed Pleos gateway and exact runtime.
                    val next = binding.copy(run = binding.run.copy(exclusiveAuxLease = request.outputLeaseId))
                    lane.withLock {
                        if (active === original && desired == binding.token) {
                            active = original.copy(binding = next)
                            mutable.value = mutable.value.copy(binding = next)
                        }
                    }
                    request
                }
            }
        }
        override suspend fun observed(event: SegmentSpeechEvent, snapshot: SegmentPlaybackSnapshot, recordEnglishCompletion: Boolean) {
            require(!recordEnglishCompletion) { "Auxiliary output cannot complete the main English article." }
            val original = bound(snapshot) ?: return
            if (original.binding is AuxiliaryBinding.Sentence) requireNotNull(sentenceJournal).observed(event, snapshot, false)
        }
    }
    private val runtime = SegmentPlaybackCoordinator(speech, journal, leases, scope) { program ->
        active?.takeIf { it.program == program }?.let { permitted(it) } == true
    }
    val segmentState = runtime.state
    private val runtimeEvents = scope.launch { runtime.events.collect { event ->
        if (event is SegmentRuntimeEvent.PhaseCompleted) phaseCompleted(event)
        else if (event is SegmentRuntimeEvent.Interrupted) lane.withLock {
            val current = active
            if (current != null && event.run?.selectionId == current.program.selectionId && event.run?.purpose == current.program.purpose)
                mutable.value = mutable.value.copy(status = AuxiliaryPlaybackStatus.UNAVAILABLE, reason = event.reason)
        }
    } }
    private val runtimeState = scope.launch { runtime.state.collect { value ->
        val snapshot = value.snapshot ?: return@collect
        lane.withLock {
            val current = active ?: return@withLock
            if (currentOwner() != current.binding.owner) { mutable.value = AuxiliaryPlaybackState(status = AuxiliaryPlaybackStatus.UNAVAILABLE, reason = "OWNER_CHANGED"); return@withLock }
            if (current.program != snapshot.program || desired != current.binding.token) return@withLock
            val status = when (value.status) {
                SegmentRuntimeStatus.PREPARING -> AuxiliaryPlaybackStatus.PREPARING
                SegmentRuntimeStatus.SPEAKING -> AuxiliaryPlaybackStatus.PLAYING
                SegmentRuntimeStatus.UNAVAILABLE -> AuxiliaryPlaybackStatus.UNAVAILABLE
                SegmentRuntimeStatus.PAUSED, SegmentRuntimeStatus.READY -> AuxiliaryPlaybackStatus.PAUSED
                SegmentRuntimeStatus.PHASE_COMPLETED -> mutable.value.status
                SegmentRuntimeStatus.IDLE -> return@withLock
            }
            mutable.value = mutable.value.copy(status = status, reason = value.reason,
                currentSegment = snapshot.cursor.segmentIndex?.let { index -> current.program.segmentIndices.indexOf(index) + 1 }, totalSegments = current.program.segmentIndices.size)
        }
    } }

    suspend fun sentence(request: SentenceSpeechRequest): Boolean {
        val binding = AuxiliaryBinding.Sentence(request)
        val indices = request.manifest.sentenceSegments(request.sentenceId, request.startCodePoint, request.endCodePoint, request.segmentIds).map { it.order }
        val program = SegmentProgram(request.owner, request.selectionId, request.source, request.manifest, request.script,
            request.phase, SegmentPurpose.SENTENCE_HELP, indices)
        return begin(binding, program, request.original, sentenceJournal != null)
    }

    suspend fun preview(owner: OwnerContext, draftRevision: Long, bindingToken: UUID, manifest: SettingsDemoManifest,
        choice: SettingsPreviewChoice, original: ReviewReturnSnapshot?): Boolean {
        val plan = SettingsPreviewPolicy.plan(manifest, choice, nowEpochMs())
        if (plan == null) {
            close()
            mutable.value = AuxiliaryPlaybackState(status = AuxiliaryPlaybackStatus.UNAVAILABLE, reason = "DEMO_NOT_APPROVED_OR_WITHDRAWN")
            return false
        }
        val run = PreviewRun(UUID.randomUUID(), owner, draftRevision, bindingToken, manifest.demoId, manifest.hash, PreviewPhase.MAIN, null, original)
        val binding = AuxiliaryBinding.Preview(run, manifest, plan, 0)
        return begin(binding, previewProgram(binding), original, true)
    }

    private suspend fun begin(binding: AuxiliaryBinding, program: SegmentProgram, original: ReviewReturnSnapshot?, supported: Boolean): Boolean {
        lane.withLock { if (closed) return false; desired = binding.token }
        val prepared = starts.withLock {
            if (!isDesired(binding.token)) return@withLock false
            stopRuntime(binding.token)
            if (!isDesired(binding.token)) return@withLock false
            // Even unavailable sentence output preserves the user's request to pause/cancel the
            // original offer. Caller saves its local cancellation before invoking this port.
            if (!originals.saveAndStop(binding.owner, original) || !isDesired(binding.token)) {
                unavailable(binding, "ORIGINAL_STOP_OR_CHECKPOINT_UNCONFIRMED"); return@withLock false
            }
            val current = Active(binding, program)
            if (!permitted(current)) { unavailable(binding, "AUXILIARY_PERMISSION_OR_RIGHTS_UNAVAILABLE"); return@withLock false }
            lane.withLock {
                if (desired != binding.token) return@withLock
                active = current
                mutable.value = AuxiliaryPlaybackState(binding, AuxiliaryPlaybackStatus.PREPARING)
            }
            if (!supported) { unavailable(binding, "APPROVED_SENTENCE_BINDING_UNAVAILABLE"); return@withLock false }
            if (!isDesired(binding.token)) return@withLock false
            runtime.attach(program)
        }
        if (!prepared || !isDesired(binding.token)) return false
        val started = runtime.play()
        if (!started) unavailable(binding, runtime.state.value.reason ?: "AUXILIARY_OUTPUT_UNAVAILABLE")
        return started
    }

    /** Explicit resume only, still at the same interrupted segment start. */
    suspend fun play(): Boolean {
        val current = active ?: return false
        if (!permitted(current)) { close(); return false }
        return runtime.play()
    }
    suspend fun pause() {
        val token = active?.binding?.token ?: return
        runtime.hold(PauseReason.USER)
        lane.withLock { if (desired == token) mutable.value = mutable.value.copy(status = AuxiliaryPlaybackStatus.PAUSED) }
    }

    /** Invalidates the local binding before the runtime's generation/lease/SDK stop. No restore. */
    suspend fun close() {
        lane.withLock { desired = null; mutable.value = AuxiliaryPlaybackState(status = AuxiliaryPlaybackStatus.CLOSED) }
        stopRuntime(null)
    }
    suspend fun recheck() {
        val current = active ?: return
        if (!permitted(current)) {
            close()
            mutable.value = mutable.value.copy(status = AuxiliaryPlaybackStatus.UNAVAILABLE, reason = "AUXILIARY_BINDING_REVOKED")
        }
    }
    private suspend fun stopRuntime(expected: UUID?) = stops.withLock {
        val shouldStop = lane.withLock {
            if (expected != null && desired != expected) return@withLock false
            retiring = active; active = null
            true
        }
        if (!shouldStop) return@withLock
        try { runtime.detach() } finally { retiring = null }
    }
    private suspend fun isDesired(token: UUID) = lane.withLock { !closed && desired == token }
    private suspend fun unavailable(binding: AuxiliaryBinding, reason: String) = lane.withLock {
        if (desired == binding.token) mutable.value = if (currentOwner() == binding.owner)
            AuxiliaryPlaybackState(binding, AuxiliaryPlaybackStatus.UNAVAILABLE, reason)
            else AuxiliaryPlaybackState(status = AuxiliaryPlaybackStatus.UNAVAILABLE, reason = "OWNER_CHANGED")
    }
    private suspend fun permitted(value: Active): Boolean {
        val access = access()
        if (closed || desired != value.binding.token || currentOwner() != value.binding.owner || !access.detailsOpen || !access.window.focused || !access.audioAllowsReply) return false
        val preview = value.binding as? AuxiliaryBinding.Preview
        if (preview != null && !preview.manifest.approved(nowEpochMs())) return false
        return authorized(value.binding)
    }
    private fun previewProgram(value: AuxiliaryBinding.Preview): SegmentProgram {
        val excerpt = value.plan.clips[value.clipIndex].excerpt
        return SegmentProgram(value.owner, value.run.runId, SegmentSourceKey("settings_demo", value.run.demoHash), excerpt.segments, excerpt.text,
            if (value.plan.clips[value.clipIndex].phase == PreviewPhase.FOLLOWUP) SegmentPhase.FOLLOWUP else SegmentPhase.MAIN, SegmentPurpose.SETTINGS_PREVIEW)
    }
    private suspend fun phaseCompleted(event: SegmentRuntimeEvent.PhaseCompleted) {
        require(!event.englishMainCompletionRecorded)
        val next = starts.withLock {
            val current = active ?: return@withLock null
            if (!isDesired(current.binding.token) || event.run.owner != current.binding.owner || event.run.selectionId != current.program.selectionId ||
                event.run.scriptId != current.program.manifest.scriptId || event.run.manifestHash != current.program.manifest.manifestHash || event.run.purpose != current.program.purpose) return@withLock null
            val preview = current.binding as? AuxiliaryBinding.Preview
            if (preview == null || preview.clipIndex == preview.plan.clips.lastIndex) {
                lane.withLock { if (active === current) mutable.value = mutable.value.copy(status = AuxiliaryPlaybackStatus.COMPLETED) }
                return@withLock null
            }
            val clip = preview.clipIndex + 1
            val binding = preview.copy(run = preview.run.copy(phase = preview.plan.clips[clip].phase, exclusiveAuxLease = null), clipIndex = clip)
            val candidate = Active(binding, previewProgram(binding))
            if (!permitted(candidate)) { unavailable(binding, "AUXILIARY_BINDING_REVOKED"); return@withLock null }
            lane.withLock {
                if (desired != binding.token) return@withLock
                active = candidate; mutable.value = AuxiliaryPlaybackState(binding, AuxiliaryPlaybackStatus.PREPARING)
            }
            if (!isDesired(binding.token)) return@withLock null
            if (!runtime.attach(candidate.program)) return@withLock null
            candidate
        }
        if (next != null && isDesired(next.binding.token) && !runtime.play()) unavailable(next.binding, runtime.state.value.reason ?: "AUXILIARY_OUTPUT_UNAVAILABLE")
    }
    suspend fun release() {
        lane.withLock { closed = true; desired = null }
        stopRuntime(null)
        runtime.close(); runtimeEvents.cancel(); runtimeState.cancel()
    }
}

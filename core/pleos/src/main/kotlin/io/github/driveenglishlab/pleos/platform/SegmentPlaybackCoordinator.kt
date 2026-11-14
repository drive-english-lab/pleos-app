package io.github.driveenglishlab.pleos.platform

import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Thin media runtime only: product navigation, followup choice, ownership and output permissions
 * remain in the existing SessionCoordinator/Host. A phase end is an event, never a next article.
 */
enum class SegmentRuntimeStatus { IDLE, READY, PREPARING, SPEAKING, PAUSED, PHASE_COMPLETED, UNAVAILABLE }
data class SegmentRuntimeState(val snapshot: SegmentPlaybackSnapshot? = null, val status: SegmentRuntimeStatus = SegmentRuntimeStatus.IDLE,
    val reason: String? = null)
sealed interface SegmentRuntimeEvent {
    data class PhaseCompleted(val run: SegmentRun, val englishMainCompletionRecorded: Boolean) : SegmentRuntimeEvent
    data class Interrupted(val run: SegmentRun?, val reason: String) : SegmentRuntimeEvent
}

class SegmentPlaybackCoordinator(private val speech: SegmentSpeechPort, private val journal: SegmentJournalPort,
    private val leases: SegmentOutputLeasePort, private val scope: CoroutineScope,
    private val mayOutput: suspend (SegmentProgram) -> Boolean) {
    private val lane = Mutex()
    private val mutable = MutableStateFlow(SegmentRuntimeState())
    val state = mutable.asStateFlow()
    private val notifications = Channel<SegmentRuntimeEvent>(Channel.UNLIMITED)
    val events = notifications.receiveAsFlow()
    private val collection = scope.launch { speech.events.collect { event ->
        try { observe(event) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { journalFailure(event.run) }
    } }

    /** Restores data only. Legacy milliseconds have no automatic segment counterpart. */
    suspend fun attach(program: SegmentProgram, saved: PlaybackCursor6? = null,
        retained: SegmentPlaybackSnapshot? = null, explicitlyFromBeginning: Boolean = false): Boolean {
        val oldOwner = mutable.value.snapshot?.program?.owner
        if (oldOwner != null && oldOwner != program.owner) detach()
        else hold(PauseReason.SYSTEM, preservePlayIntent = true)
        return lane.withLock {
            if (saved is PlaybackCursor6.LegacyTime && !explicitlyFromBeginning) {
                mutable.value = SegmentRuntimeState(status = SegmentRuntimeStatus.UNAVAILABLE, reason = "LEGACY_RESTART_REQUIRES_EXPLICIT_CHOICE")
                return@withLock false
            }
            val first = program.segmentIndices.first()
            val cursor = if (!explicitlyFromBeginning && saved is PlaybackCursor6.Segment) {
                if (!saved.matches(program.manifest) || saved.phase != program.phase || saved.segmentIndex !in program.segmentIndices) {
                    mutable.value = SegmentRuntimeState(status = SegmentRuntimeStatus.UNAVAILABLE, reason = "PINNED_MANIFEST_CHANGED")
                    return@withLock false
                }
                saved
            } else cursor(program, first, false, PauseReason.SYSTEM)
            val preserved = retained?.takeIf { old ->
                old.program.source == program.source && old.program.selectionId == program.selectionId &&
                    old.program.owner.profileId == program.owner.profileId && old.program.owner.privacyEpoch == program.owner.privacyEpoch &&
                    old.program.manifest == program.manifest && old.program.phase == program.phase && old.program.purpose == program.purpose &&
                    old.cursor == cursor && !explicitlyFromBeginning
            }
            val runId = preserved?.runId ?: UUID.randomUUID()
            val ledger = preserved?.ledger ?: SequentialCompletion.start(program, runId, requireNotNull(cursor.segmentIndex))
            val snapshot = SegmentPlaybackSnapshot(program, cursor, runId, (preserved?.generation ?: 0) + 1, ledger)
            journal.save(snapshot)
            mutable.value = SegmentRuntimeState(snapshot, SegmentRuntimeStatus.READY)
            true
        }
    }

    /** Only an explicit product play intent invokes this method; attach/focus gain never do. */
    suspend fun play(): Boolean {
        val request = lane.withLock {
            val original = mutable.value.snapshot ?: return@withLock null
            if (!mayOutput(original.program)) return@withLock null
            if (original.activeRequest != null) {
                if (mutable.value.status != SegmentRuntimeStatus.UNAVAILABLE || mutable.value.reason != "SEGMENT_START_PENDING") return@withLock null
                val pending = requireNotNull(original.activeRequest)
                if (!leases.owns(pending.outputLeaseId, pending.owner, pending.purpose)) return@withLock null
                mutable.value = mutable.value.copy(status = SegmentRuntimeStatus.PREPARING, reason = null)
                return@withLock Triple(pending, segmentText(original.program.script, original.program.manifest.segments[pending.segmentIndex]), original.program.manifest.language)
            }
            val index = original.cursor.segmentIndex ?: return@withLock null
            val segment = original.program.manifest.segments[index]
            val lease = leases.acquire(original.program.owner, original.program.purpose) ?: run {
                mutable.value = mutable.value.copy(status = SegmentRuntimeStatus.UNAVAILABLE, reason = "OUTPUT_LEASE_UNAVAILABLE")
                return@withLock null
            }
            val run = SegmentRun(original.runId, original.generation, UUID.randomUUID(), original.program.owner, original.program.selectionId,
                original.program.source, original.program.phase, original.program.purpose, original.program.manifest.scriptId, original.program.manifest.scriptHash,
                original.program.manifest.manifestId, original.program.manifest.segmentsVersion, original.program.manifest.manifestHash,
                segment.segmentId, index, segment.textHash, lease)
            val next = original.copy(cursor = original.cursor.copy(playIntent = true, pauseReason = PauseReason.NONE), activeRequest = run, bindingAcknowledged = false)
            try {
                journal.save(next)
                mutable.value = SegmentRuntimeState(next, SegmentRuntimeStatus.PREPARING)
            } catch (error: Exception) { leases.release(lease); throw error }
            Triple(run, segmentText(original.program.script, segment), original.program.manifest.language)
        } ?: return false
        val run = request.first
        // Recheck after leaving the runtime lock. The gateway also checks the exact lease
        // again just before actual SDK speak, including asynchronous initialization/registration.
        val snapshot = lane.withLock { mutable.value.snapshot?.takeIf { it.activeRequest == run } } ?: return false
        val authoritative = try { journal.prepareOutput(snapshot) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { interrupt(run, "SEGMENT_BINDING_UNAVAILABLE"); return false }
        if (authoritative == null) {
            lane.withLock { if (mutable.value.snapshot?.activeRequest == run) mutable.value = mutable.value.copy(status = SegmentRuntimeStatus.UNAVAILABLE, reason = "SEGMENT_START_PENDING") }
            return false
        }
        val acceptedBinding = try { lane.withLock {
            val current = mutable.value.snapshot?.takeIf { it.activeRequest == run } ?: return@withLock false
            requireSameMaterial(run, authoritative)
            // Only an initial, as-yet-unobserved run may adopt a server-issued run ID.
            if (authoritative.runId != current.runId && current.ledger.completed.isNotEmpty()) return@withLock false
            if (!leases.adopt(run.outputLeaseId, authoritative.outputLeaseId, run.owner, run.purpose)) return@withLock false
            val next = current.copy(runId = authoritative.runId, generation = authoritative.generation,
                ledger = current.ledger.copy(runId = authoritative.runId), activeRequest = authoritative, bindingAcknowledged = true)
            journal.save(next)
            mutable.value = mutable.value.copy(snapshot = next)
            true
        } } catch (cancelled: CancellationException) { leases.release(authoritative.outputLeaseId); throw cancelled }
        catch (_: Exception) { leases.release(authoritative.outputLeaseId); interrupt(run, "SEGMENT_BINDING_UNAVAILABLE"); return false }
        if (!acceptedBinding || !leases.owns(authoritative.outputLeaseId, authoritative.owner, authoritative.purpose)) return false
        return when (val accepted = speech.request(authoritative, request.second, request.third)) {
            SegmentSpeechAdmission.Preparing -> true
            is SegmentSpeechAdmission.Unavailable -> { interrupt(authoritative, accepted.reason); false }
        }
    }

    /** Generation and lease authority are invalidated before journaling or SDK stop. */
    suspend fun hold(reason: PauseReason, preservePlayIntent: Boolean = false) {
        var stop: SegmentRun? = null
        try {
            lane.withLock {
                val original = mutable.value.snapshot ?: return@withLock
                stop = original.activeRequest
                val next = original.copy(generation = original.generation + 1, activeRequest = null, bindingAcknowledged = false,
                    cursor = original.cursor.copy(playIntent = preservePlayIntent && original.cursor.playIntent, pauseReason = reason))
                mutable.value = SegmentRuntimeState(next, SegmentRuntimeStatus.PAUSED)
                stop?.let { leases.release(it.outputLeaseId) }
                journal.save(next)
            }
        } finally { stop?.let { speech.stop(it) } }
    }

    /** Native previous/next segment, never ±time or next article. Navigation breaks this run's
     * sequential completion eligibility; actual earlier completion records remain intact.
     */
    suspend fun move(delta: Int, playAfterMove: Boolean = false): Boolean {
        require(delta == -1 || delta == 1)
        hold(PauseReason.USER, preservePlayIntent = playAfterMove)
        val moved = lane.withLock {
            val original = mutable.value.snapshot ?: return@withLock false
            val current = original.cursor.segmentIndex ?: return@withLock false
            val target = current + delta
            if (target !in original.program.segmentIndices) return@withLock false
            val next = original.copy(cursor = cursor(original.program, target, playAfterMove, PauseReason.USER), ledger = original.ledger.manuallyMoved())
            journal.save(next)
            mutable.value = SegmentRuntimeState(next, SegmentRuntimeStatus.PAUSED)
            true
        }
        if (moved && playAfterMove) play()
        return moved
    }

    private suspend fun observe(event: SegmentSpeechEvent) {
        var next = false
        var release: UUID? = null
        var stop: SegmentRun? = null
        lane.withLock {
            val original = mutable.value.snapshot ?: return@withLock
            if (!original.bindingAcknowledged || original.activeRequest != event.run || original.generation != event.run.generation) return@withLock
            // Revocation must still accept an exact interrupt event so the old SDK instance stops.
            // A completed/started callback under revoked permission cannot advance the ledger.
            if (event.kind in setOf(SegmentSpeechEventKind.STARTED, SegmentSpeechEventKind.COMPLETED) &&
                !leases.owns(event.run.outputLeaseId, event.run.owner, event.run.purpose)) error("Output permission revoked.")
            when (event.kind) {
                SegmentSpeechEventKind.STARTED -> {
                    journal.observed(event, original, false)
                    mutable.value = mutable.value.copy(status = SegmentRuntimeStatus.SPEAKING, reason = null)
                }
                SegmentSpeechEventKind.COMPLETED -> {
                    val observed = original.ledger.completed(event)
                    val newEnglishCompletion = observed.complete && !observed.englishMainCompletionRecorded
                    val ledger = observed.copy(englishMainCompletionRecorded = observed.englishMainCompletionRecorded || newEnglishCompletion)
                    val current = requireNotNull(original.cursor.segmentIndex)
                    val index = original.program.segmentIndices.indexOf(current)
                    val nextIndex = original.program.segmentIndices.getOrNull(index + 1)
                    val saved = original.copy(ledger = ledger, activeRequest = null, bindingAcknowledged = false,
                        cursor = if (nextIndex != null) cursor(original.program, nextIndex, original.cursor.playIntent, PauseReason.NONE) else original.cursor)
                    // Exact observed evidence, cursor and completion flag are one local commit.
                    journal.observed(event, saved, newEnglishCompletion)
                    mutable.value = SegmentRuntimeState(saved, if (nextIndex == null) SegmentRuntimeStatus.PHASE_COMPLETED else SegmentRuntimeStatus.PAUSED)
                    release = event.run.outputLeaseId
                    if (nextIndex == null) notifications.trySend(SegmentRuntimeEvent.PhaseCompleted(event.run, newEnglishCompletion))
                    else next = original.cursor.playIntent
                }
                SegmentSpeechEventKind.STOPPED, SegmentSpeechEventKind.ERROR, SegmentSpeechEventKind.UNKNOWN -> {
                    val saved = original.copy(generation = original.generation + 1, activeRequest = null, bindingAcknowledged = false,
                        cursor = original.cursor.copy(pauseReason = PauseReason.SYSTEM))
                    journal.observed(event, saved, false)
                    mutable.value = SegmentRuntimeState(saved, if (event.kind == SegmentSpeechEventKind.STOPPED) SegmentRuntimeStatus.PAUSED else SegmentRuntimeStatus.UNAVAILABLE,
                        reason = event.kind.name)
                    release = event.run.outputLeaseId
                    stop = event.run
                    notifications.trySend(SegmentRuntimeEvent.Interrupted(event.run, event.kind.name))
                }
            }
        }
        release?.let { leases.release(it) }
        stop?.let { speech.stop(it) }
        // play() rechecks current owner/intent/actual access and acquires a new exact lease.
        if (next) play()
    }

    private suspend fun journalFailure(run: SegmentRun) {
        var stop = false
        lane.withLock {
            val original = mutable.value.snapshot
            if (original?.activeRequest == run) {
                mutable.value = SegmentRuntimeState(original.copy(generation = original.generation + 1, activeRequest = null, bindingAcknowledged = false,
                    cursor = original.cursor.copy(pauseReason = PauseReason.SYSTEM)), SegmentRuntimeStatus.UNAVAILABLE, "SEGMENT_JOURNAL_UNAVAILABLE")
                try { journal.save(requireNotNull(mutable.value.snapshot)) } catch (_: Exception) { }
                stop = true
            }
        }
        if (stop) {
            leases.release(run.outputLeaseId)
            speech.stop(run)
            notifications.trySend(SegmentRuntimeEvent.Interrupted(run, "SEGMENT_JOURNAL_UNAVAILABLE"))
        }
    }

    private suspend fun interrupt(run: SegmentRun, reason: String) {
        if (lane.withLock { mutable.value.snapshot?.activeRequest == run }) {
            hold(PauseReason.VOICE_FAILURE, preservePlayIntent = true)
            lane.withLock { if (mutable.value.snapshot?.runId == run.runId) mutable.value = mutable.value.copy(status = SegmentRuntimeStatus.UNAVAILABLE, reason = reason) }
            notifications.trySend(SegmentRuntimeEvent.Interrupted(run, reason))
        }
    }
    /** Ownership/privacy transitions clear the visible projection immediately, even if the old
     * owner is already fenced and can no longer write its journal. Old callbacks stay unmatched.
     */
    suspend fun detach() {
        var stop: SegmentRun? = null
        try {
            lane.withLock {
                val original = mutable.value.snapshot
                stop = original?.activeRequest
                mutable.value = SegmentRuntimeState()
                stop?.let { leases.release(it.outputLeaseId) }
                if (original != null) {
                    try { journal.save(original.copy(generation = original.generation + 1, activeRequest = null,
                        bindingAcknowledged = false, cursor = original.cursor.copy(playIntent = false, pauseReason = PauseReason.PRIVACY))) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* The shared owner transaction may already have fenced this epoch. */ }
                }
            }
        } finally { stop?.let { speech.stop(it) } }
    }
    suspend fun close() { try { detach() } finally { collection.cancel(); notifications.close() } }

    private fun requireSameMaterial(proposed: SegmentRun, actual: SegmentRun) {
        require(proposed.owner == actual.owner && proposed.selectionId == actual.selectionId && proposed.source == actual.source &&
            proposed.phase == actual.phase && proposed.purpose == actual.purpose && proposed.scriptId == actual.scriptId && proposed.scriptHash == actual.scriptHash &&
            proposed.manifestId == actual.manifestId && proposed.manifestVersion == actual.manifestVersion && proposed.manifestHash == actual.manifestHash &&
            proposed.segmentId == actual.segmentId && proposed.segmentIndex == actual.segmentIndex && proposed.segmentTextHash == actual.segmentTextHash)
    }

    private fun cursor(program: SegmentProgram, index: Int, playIntent: Boolean, reason: PauseReason) = PlaybackCursor6.Segment(program.manifest.scriptId,
        program.manifest.scriptHash, program.manifest.manifestId, program.manifest.segmentsVersion, program.manifest.manifestHash,
        program.phase, program.manifest.segments[index].segmentId, index, playIntent, reason)
}

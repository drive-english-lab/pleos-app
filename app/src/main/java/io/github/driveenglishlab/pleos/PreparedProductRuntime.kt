package io.github.driveenglishlab.pleos

import android.content.Context
import io.github.driveenglishlab.pleos.data.*
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.platform.*
import java.util.UUID
import kotlinx.coroutines.*

/** Process binder for actual prepared content, one native lease, and the shared session authority. */
internal class PreparedProductRuntime(context: Context, private val scope: CoroutineScope,
    private val profiles: ProfileRepository, database: LearningDatabase, api: IncrementalLearningApi,
    private val catalog: PreparedCatalogRepository, private val coordinator: SessionCoordinator,
    voices: ProductVoiceConfiguration, visible: () -> Boolean,
    private val phaseEnded: suspend (SegmentRun) -> Unit) {
    private val codecs = IncrementalContractJson(context)
    val checkpoints = Source6SegmentCheckpointPort(api, profiles, database.review(), codecs)
    private val repository = SegmentPlaybackRepository(context, database.segments(), checkpoints) { profiles.current()?.owner == it }
    var auxiliaryPermission: suspend (OwnerContext, SegmentPurpose) -> Boolean = { _, _ -> false }
    var auxiliaryRunPermission: suspend (SegmentRun) -> Boolean = { false }
    val leases = SegmentOutputLease { owner, purpose ->
        val view = coordinator.snapshot()
        owner == view.owner && view.access.ownerValid && view.access.power == PowerAvailability.ALLOWED &&
            view.voiceInput == VoiceInputActivity.IDLE && view.inputPhase == InputPhase.IDLE && view.pendingAnswer == null &&
            view.practiceModal?.visible != true && (if (purpose in setOf(SegmentPurpose.MAIN, SegmentPurpose.FOLLOWUP)) true else auxiliaryPermission(owner, purpose))
    }
    val speech: PleosTtsGateway = PleosTtsGateway(context, scope, voices.pleosTts, voices.pleosRegistration,
        permitted = { run -> if (run.purpose in setOf(SegmentPurpose.MAIN, SegmentPurpose.FOLLOWUP))
            runtime.state.value.snapshot?.program?.let { it.owner == run.owner && allowed(it) } == true
            else auxiliaryRunPermission(run) }, appVisible = visible)
    val runtime: SegmentPlaybackCoordinator = SegmentPlaybackCoordinator(speech, repository.journal, leases, scope, ::allowed)
    val state get() = runtime.state
    private suspend fun allowed(program: SegmentProgram): Boolean {
        if (!coordinator.segmentPermission(program)) return false
        val call = profiles.current()?.takeIf { it.owner == program.owner } ?: return false
        val ref = coordinator.state.value.preparedListening?.content ?: return false
        val value = if (program.phase == SegmentPhase.MAIN) catalog.main(call, ref.source6Wire())
            else catalog.main(call, ref.source6Wire()) // Recheck current source/rights independently of followup readiness.
        return value is CatalogRead.Stored && profiles.current()?.owner == program.owner && coordinator.segmentPermission(program)
    }
    fun start() {
        scope.launch { runtime.state.collect { state -> state.snapshot?.let { snapshot ->
            val previous = coordinator.state.value.preparedListening?.runId
            coordinator.observeSegment(snapshot, state.status == SegmentRuntimeStatus.SPEAKING, previous)
        } } }
        scope.launch { runtime.events.collect { event -> if (event is SegmentRuntimeEvent.PhaseCompleted) phaseEnded(event.run) } }
        scope.launch { coordinator.state.collect { view ->
            val current = runtime.state.value.snapshot ?: return@collect
            if (view.owner != current.program.owner) runtime.detach()
            else if (!coordinator.segmentPermission(current.program) && current.activeRequest != null) runtime.hold(PauseReason.SYSTEM, true)
        } }
    }
    suspend fun attach(owner: OwnerContext, work: PreparedListeningWorkRef, script: SourcePublicScript, restored: Boolean = false): Boolean {
        val manifest = script.manifest ?: return false
        val source = codecs.encode("source6", "ContentRef6", work.content.source6Wire(), WireSource6ContentRef6.serializer())
        val encoded = codecs.encode("source6", "SegmentManifest", manifest, WireSource6SegmentManifest.serializer())
        val phase = work.cursor.phase
        if (phase == SegmentPhase.ARTICLE_OFFER) return false
        val program = repository.program(owner, work.selectionId, source, encoded, script.text, phase,
            if (phase == SegmentPhase.MAIN) SegmentPurpose.MAIN else SegmentPurpose.FOLLOWUP)
        val retained = repository.restore(program)
        return runtime.attach(program, work.cursor, retained = retained, explicitlyFromBeginning = false)
    }
    suspend fun hold(reason: PauseReason = PauseReason.USER): Boolean {
        val current = runtime.state.value.snapshot ?: return coordinator.state.value.preparedListening?.let {
            profiles.current()?.owner == coordinator.state.value.owner && speech.quiescent
        } == true
        runtime.hold(reason)
        return runtime.state.value.snapshot?.program == current.program && speech.quiescent
    }
    suspend fun detach() = runtime.detach()
}

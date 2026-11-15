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
    private data class OutputAuthority(val owner: OwnerContext, val selectionId: UUID, val intentGeneration: Long)
    @Volatile private var outputAuthority: OutputAuthority? = null
    val leases = SegmentOutputLease { owner, purpose ->
        val view = coordinator.snapshot()
        owner == view.owner && view.access.mediaReady &&
            view.voiceInput == VoiceInputActivity.IDLE && view.inputPhase == InputPhase.IDLE && view.pendingAnswer == null &&
            view.practiceModal?.visible != true && (if (purpose in setOf(SegmentPurpose.MAIN, SegmentPurpose.FOLLOWUP))
                outputAuthority?.let { it.owner == owner && it.selectionId == view.preparedListening?.selectionId &&
                    it.intentGeneration == view.intentGeneration && view.intent == PlaybackIntent.PLAY } == true
                else auxiliaryPermission(owner, purpose))
    }
    val speech: PleosTtsGateway = PleosTtsGateway(context, scope, voices.pleosTts, voices.pleosRegistration,
        permitted = { run -> if (run.purpose in setOf(SegmentPurpose.MAIN, SegmentPurpose.FOLLOWUP))
            runtime.state.value.snapshot?.program?.let { it.owner == run.owner && allowed(it) } == true
            else auxiliaryRunPermission(run) }, appVisible = visible, outputPermitted = ::outputAllowed)
    val runtime: SegmentPlaybackCoordinator = SegmentPlaybackCoordinator(speech, repository.journal, leases, scope, ::allowed)
    val state get() = runtime.state
    private suspend fun allowed(program: SegmentProgram): Boolean = verifyOutput(program, null)
    private suspend fun outputAllowed(run: SegmentRun): Boolean {
        if (run.purpose in setOf(SegmentPurpose.MAIN, SegmentPurpose.FOLLOWUP)) {
            val snapshot = runtime.state.value.snapshot ?: return false
            if (snapshot.activeRequest != run || snapshot.program.owner != run.owner) return false
            return verifyOutput(snapshot.program, run)
        }
        if (!speech.ownsOutputFocus(run) || !auxiliaryRunPermission(run)) return false
        val view = coordinator.snapshot()
        return view.owner == run.owner && FeatureAccessEvaluator.evaluate(FeatureScope.MEDIA,
            view.access.copy(mediaFocusAllowed = speech.ownsOutputFocus(run))).allowed
    }
    private suspend fun verifyOutput(program: SegmentProgram, run: SegmentRun?): Boolean {
        val authority = outputAuthority?.takeIf { it.owner == program.owner && it.selectionId == program.selectionId } ?: return false
        val verifyContent: suspend () -> Boolean = verify@ {
            val call = profiles.current()?.takeIf { it.owner == program.owner } ?: return@verify false
            val ref = coordinator.state.value.preparedListening?.content ?: return@verify false
            // Recheck source/rights independently of followup readiness.
            val value = catalog.main(call, ref.source6Wire())
            value is CatalogRead.Stored && outputAuthority === authority && profiles.current()?.owner == program.owner
        }
        return if (run == null) coordinator.segmentOutputAdmissionPermission(program, authority.intentGeneration, verifyContent)
        else coordinator.segmentFocusedOutputPermission(program, authority.intentGeneration,
            { speech.ownsOutputFocus(run) }, verifyContent)
    }
    /** The generation comes from the successful atomic product play command, never a later read. */
    suspend fun play(owner: OwnerContext, selectionId: UUID, intentGeneration: Long): Boolean {
        val view = coordinator.state.value
        if (view.owner != owner || view.preparedListening?.selectionId != selectionId ||
            view.intentGeneration != intentGeneration || view.intent != PlaybackIntent.PLAY) return false
        outputAuthority = OutputAuthority(owner, selectionId, intentGeneration)
        return runtime.play()
    }
    fun start() {
        scope.launch { runtime.state.collect { state -> state.snapshot?.let { snapshot ->
            val previous = coordinator.state.value.preparedListening?.runId
            coordinator.observeSegment(snapshot, state.status == SegmentRuntimeStatus.SPEAKING, previous)
        } } }
        scope.launch { runtime.events.collect { event -> if (event is SegmentRuntimeEvent.PhaseCompleted) phaseEnded(event.run) } }
        scope.launch { coordinator.state.collect { view ->
            val current = runtime.state.value.snapshot ?: return@collect
            if (view.owner != current.program.owner) detach()
            else if (!coordinator.segmentAdmissionPermission(current.program) && current.activeRequest != null) hold(PauseReason.SYSTEM, true)
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
    suspend fun hold(reason: PauseReason = PauseReason.USER, preservePlayIntent: Boolean = false): Boolean {
        outputAuthority = null
        val current = runtime.state.value.snapshot ?: return coordinator.state.value.preparedListening?.let {
            profiles.current()?.owner == coordinator.state.value.owner && speech.quiescent
        } == true
        runtime.hold(reason, preservePlayIntent)
        return runtime.state.value.snapshot?.program == current.program && speech.quiescent
    }
    suspend fun detach() { outputAuthority = null; runtime.detach() }
}

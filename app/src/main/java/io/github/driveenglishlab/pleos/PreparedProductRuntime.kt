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
    private val phaseEnded: suspend (SegmentRun,SegmentContinuationAuthority?) -> Unit) {
    private val codecs = IncrementalContractJson(context)
    private val segmentDao=database.segments()
    val checkpoints = Source6SegmentCheckpointPort(api, profiles, database.review(), codecs)
    private val repository = SegmentPlaybackRepository(context, database.segments(), checkpoints) { profiles.current()?.owner == it }
    var auxiliaryPermission: suspend (OwnerContext, SegmentPurpose) -> Boolean = { _, _ -> false }
    var auxiliaryRunPermission: suspend (SegmentRun) -> Boolean = { false }
    private data class OutputAuthority(val owner: OwnerContext, val selectionId: UUID, val intentGeneration: Long)
    @Volatile private var outputAuthority: OutputAuthority? = null
    private data class SourceAuthority(val program:SegmentProgram,val output:OutputAuthority)
    @Volatile private var sourceAuthority:SourceAuthority?=null
    var auxiliaryProgram:(SegmentRun)->SegmentProgram?={null}
    var auxiliaryLocalCurrent:(SegmentRun)->Boolean={false}
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
            else auxiliaryRunPermission(run) }, appVisible = visible, outputPermitted = ::outputAllowed,
        runtimeIdentity=voices.ttsRuntimeIdentity,currentFacts=::currentFacts,programForRun={run->
            runtime.state.value.snapshot?.takeIf{it.activeRequest==run && it.bindingAcknowledged}?.program?:auxiliaryProgram(run)
        },onScopeLost={run,_->
            val authority=outputAuthority?.takeIf{it.owner==run.owner && it.selectionId==run.selectionId}
            outputAuthority=null;sourceAuthority=null
            if(authority!=null)coordinator.systemHoldSegment(run,authority.intentGeneration)
        })
    val runtime: SegmentPlaybackCoordinator = SegmentPlaybackCoordinator(speech, repository.journal, leases, scope, ::allowed).apply {
        configureCompletion(speech::completionDisposition,speech::recoverCleanup){program->
            val authority=outputAuthority;val view=coordinator.state.value
            if(authority!=null && authority.owner==program.owner && authority.selectionId==program.selectionId && authority.intentGeneration==view.intentGeneration &&
                view.owner==program.owner && view.intent==PlaybackIntent.PLAY && !view.requiresExplicitMediaResume)
                SegmentContinuationAuthority(authority.owner,authority.selectionId,authority.intentGeneration) else null
        }
    }
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
            val valid=value is CatalogRead.Stored && outputAuthority === authority && profiles.current()?.owner == program.owner
            if(valid)sourceAuthority=SourceAuthority(program,authority) else sourceAuthority=null
            valid
        }
        return if (run == null) coordinator.segmentOutputAdmissionPermission(program, authority.intentGeneration, verifyContent)
        else coordinator.segmentFocusedOutputPermission(program, authority.intentGeneration,
            { speech.ownsOutputFocus(run) }, verifyContent)
    }
    private suspend fun currentFacts(run:SegmentRun):TtsCurrentScopeFact1? {
        val view=coordinator.state.value
        if(view.owner!=run.owner)return null
        val main=run.purpose in setOf(SegmentPurpose.MAIN,SegmentPurpose.FOLLOWUP)
        val authority=outputAuthority
        val original=runtime.state.value.snapshot?.takeIf{it.activeRequest==run && it.bindingAcknowledged}
        val currentSource=if(main)authority!=null && original!=null && sourceAuthority?.let{it.output===authority && it.program==original.program}==true &&
            view.preparedListening?.accepts(original.program)==true && authority.intentGeneration==view.intentGeneration && view.intent==PlaybackIntent.PLAY && !view.requiresExplicitMediaResume
            else auxiliaryLocalCurrent(run)
        return TtsCurrentScopeFact1(view.access,view.owner,leases.retains(run.outputLeaseId,run.owner,run.purpose),currentSource,
            TtsPermissionState.UNKNOWN,0,speech.scopeGeneration,speech.ownsOutputFocus(run),view.intentGeneration)
    }
    suspend fun acknowledgePhase(snapshot:SegmentPlaybackSnapshot,run:SegmentRun,checkpoint:WireSource6PersonalReadCheckpoint):Boolean {
        val view=coordinator.state.value;val work=view.preparedListening?:return false
        if(view.owner!=snapshot.program.owner || work.selectionId!=run.selectionId || checkpoint.recordId!=work.checkpointId.toString() ||
            checkpoint.payload.selectionId!=work.selectionId.toString() || checkpoint.payload.contentRef!=work.content.source6Wire() ||
            checkpoint.payload.cursor!=work.cursor.toWireSegmentCursor() || checkpoint.version!=work.checkpointVersion || work.cursor.phase==run.phase)return false
        val continued=snapshot.continuation?.takeIf{it.completedRun==run && it.phaseEndPending}?:return false
        return repository.journal.phaseAcknowledged(snapshot,continued.completedEventId)
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
            snapshot.continuation?.let{continued->
                if(continued.state!=OutputContinuationState.READY) {
                    speech.retainCleanupHold(continued)
                    val authority=outputAuthority
                    if(authority!=null){outputAuthority=null;sourceAuthority=null;coordinator.systemHoldSegment(continued.completedRun,authority.intentGeneration)}
                }
            }
            val previous = coordinator.state.value.preparedListening?.runId
            coordinator.observeSegment(snapshot, state.status == SegmentRuntimeStatus.SPEAKING, previous)
        } } }
        scope.launch { runtime.events.collect { event -> if (event is SegmentRuntimeEvent.PhaseCompleted) phaseEnded(event.run,event.authority) } }
        scope.launch { coordinator.state.collect { view ->
            val current = runtime.state.value.snapshot ?: return@collect
            if (view.owner != current.program.owner) detach()
            else if (!coordinator.segmentAdmissionPermission(current.program) && current.activeRequest != null) hold(PauseReason.SYSTEM, true)
            speech.recheckCurrent()
        } }
    }
    /** No output is admitted here. The supplied checkpoint is the exact current read/ACK. */
    suspend fun reconcileRestoredPhase(owner:OwnerContext,work:PreparedListeningWorkRef,checkpoint:WireSource6PersonalReadCheckpoint,followupPolicy:String) {
        val call=profiles.current()?.takeIf{it.owner==owner}?:return
        if(coordinator.state.value.preparedListening!=work || checkpoint.recordId!=work.checkpointId.toString() ||
            checkpoint.version!=work.checkpointVersion || checkpoint.payload.contentRef!=work.content.source6Wire() || checkpoint.payload.cursor!=work.cursor.toWireSegmentCursor())return
        val phases=when(work.cursor.phase){SegmentPhase.MAIN->emptyList();SegmentPhase.FOLLOWUP->listOf(SegmentPhase.MAIN);SegmentPhase.ARTICLE_OFFER->listOf(SegmentPhase.MAIN,SegmentPhase.FOLLOWUP)}
        for(phase in phases) {
            val purpose=if(phase==SegmentPhase.MAIN)SegmentPurpose.MAIN else SegmentPurpose.FOLLOWUP
            val stored=segmentDao.state(owner.profileId.toString(),owner.privacyEpoch,work.selectionId.toString(),phase.name.lowercase(),purpose.name.lowercase(),"segment")?:continue
            val wrapper=stored.ledgerJson?.let{kotlinx.serialization.json.Json.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject}?:continue
            val continuation=wrapper["continuation"] as? kotlinx.serialization.json.JsonObject?:continue
            if(continuation["phase_end_pending"]!=kotlinx.serialization.json.JsonPrimitive(true))continue
            val script=if(phase==SegmentPhase.MAIN)catalog.main(call,work.content.source6Wire()) else catalog.followup(call,work.content.source6Wire(),followupPolicy)
            if(script !is CatalogRead.Stored || profiles.current()?.owner!=owner || coordinator.state.value.preparedListening!=work)continue
            val manifest=script.value.manifest?:continue
            val program=repository.program(owner,work.selectionId,codecs.encode("source6","ContentRef6",work.content.source6Wire(),WireSource6ContentRef6.serializer()),
                codecs.encode("source6","SegmentManifest",manifest,WireSource6SegmentManifest.serializer()),script.value.text,phase,purpose)
            val retained=repository.restore(program)?:continue
            retained.continuation?.let(speech::retainCleanupHold)
            repository.acknowledgeRestoredPhase(program,checkpoint)
        }
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
        retained?.continuation?.let(speech::retainCleanupHold)
        return runtime.attach(program, work.cursor, retained = retained, explicitlyFromBeginning = false)
    }
    suspend fun hold(reason: PauseReason = PauseReason.USER, preservePlayIntent: Boolean = false): Boolean {
        outputAuthority = null;sourceAuthority=null
        val current = runtime.state.value.snapshot ?: return coordinator.state.value.preparedListening?.let {
            profiles.current()?.owner == coordinator.state.value.owner && speech.quiescent
        } == true
        runtime.hold(reason, preservePlayIntent)
        return runtime.state.value.snapshot?.program == current.program && speech.quiescent
    }
    suspend fun detach() { outputAuthority = null;sourceAuthority=null; runtime.detach() }
}

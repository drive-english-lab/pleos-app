package io.github.driveenglishlab.pleos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.driveenglishlab.pleos.data.*
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.platform.*
import io.github.driveenglishlab.pleos.ui.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Read projections and adapter effects; the shared coordinator remains the only UI/input authority. */
internal class ProductLearningController(private val context: Context, private val scope: CoroutineScope,
    private val coordinator: SessionCoordinator, private val profiles: ProfileRepository, private val api: LearningApi,
    private val database: LearningDatabase, private val catalog: CatalogRepository,
    private val freeze: suspend (OwnerContext) -> Boolean,
    private val restoreQuestion: suspend (OwnerContext, ConversationReturn, Boolean) -> Unit,
    private val accessChanged: suspend () -> Unit,
    private val voices: ProductVoiceConfiguration = ProductVoiceConfiguration()) {
    private val mutable = MutableStateFlow(LearningPresentation())
    val state = mutable.asStateFlow()
    private val resumes = ResumeRepository(api, database)
    private val practiceTimes = AttemptPracticeTimeRepository(LearningProgressApi(context, BuildConfig.LEARNING_API_ORIGIN, profiles, BuildConfig.DEBUG), database)
    private val practice = PracticeRepository(context, api, database, isCurrent = { owner, lesson, ref ->
        coordinator.state.value.let { it.owner == owner && it.lessonId == lesson && it.task == ref }
    }, mayProjectResult = { owner, lesson, attempt ->
        val current = coordinator.snapshot()
        current.owner == owner && current.lessonId == lesson && current.captureLease == null &&
            current.inputPhase !in setOf(InputPhase.CAPTURING, InputPhase.FINALIZING) &&
            (current.pendingAnswer == null || current.pendingAnswer?.lease?.attemptId == attempt)
    }, presentationVerifier = FeedbackPresentationVerifier { witness ->
        val view = coordinator.snapshot()
        witness.mode == "explicit_user_action" && witness.owner == view.owner && witness.lessonId == view.lessonId &&
            mutable.value.practice?.progress?.pendingFeedbackAttemptId == witness.attemptId && (view.access.detailsOpen && view.access.window.focused || mutable.value.channel == InteractionChannel.GLEO && voices.gleo?.schemaApproval is Capability.Verified && replyAllowed)
    })
    private val incremental = api.incremental(context)
    private val codecs = IncrementalContractJson(context)
    private val preparedListening = Source6ListeningRepository(incremental, database.review(), codecs)
    private val preparedPresentations = Source6PresentationRepository(incremental, database.review(), codecs)
    private val reviewJournal = ReviewJournal(database.review(), codecs)
    private val reviews = ReviewRepository(incremental, database.review(), reviewJournal, codecs)
    private val legacyAssistance=LegacyAssistanceAdmissionAdapter(database,incremental,reviews,codecs,profiles)
    private val preparedPractice = Source6PracticeRepository(incremental, database.review(), codecs, reviews,
        isCurrent = { owner, lesson, ref -> coordinator.state.value.let { it.owner == owner && it.lessonId == lesson && it.task == ref } },
        presentationVerifier = FeedbackPresentationVerifier { witness ->
            val view = coordinator.snapshot()
            witness.mode == "explicit_user_action" && witness.owner == view.owner && witness.lessonId == view.lessonId &&
                mutable.value.practice?.progress?.pendingFeedbackAttemptId == witness.attemptId &&
                (view.access.detailsOpen && view.access.window.focused || mutable.value.channel == InteractionChannel.GLEO &&
                    voices.gleo?.schemaApproval is Capability.Verified && replyAllowed)
        }, mayProjectResult = { owner, lesson, attempt ->
            coordinator.snapshot().let { it.owner == owner && it.lessonId == lesson && it.captureLease == null &&
                it.inputPhase !in setOf(InputPhase.CAPTURING, InputPhase.FINALIZING) &&
                (it.pendingAnswer == null || it.pendingAnswer?.lease?.attemptId == attempt) }
        })
    private val preparedQuestions = Source6QuestionRepository(incremental, isCurrent = { owner, origin ->
        coordinator.state.value.owner == owner && mutable.value.questionOrigin == origin && questionContext?.matches(coordinator.state.value) == true
    })
    private suspend fun persistAnswer(answer: AcceptedAnswer) {
        val current = mutable.value.practice ?: error("Current lesson missing")
        if (source6(answer.lease.owner,answer.lease.lessonId,answer.lease.task)) {
            val call = profiles.current()?.takeIf { it.owner == answer.lease.owner } ?: error("Owner changed")
            val stream = call.session.installationId
            preparedPractice.persistAccepted(answer, current, stream, reviewJournal.frontier(call, stream.toString()))
        } else legacyAssistance.persistAccepted(answer){practice.persistAccepted(answer)}
    }
    private val questions = QuestionRepository(api, isCurrent = { owner, origin ->
        coordinator.state.value.owner == owner && mutable.value.questionOrigin == origin &&
            coordinator.state.value.screen in setOf(SessionScreen.ARTICLE_QUESTION, SessionScreen.RESTRICTED) && questionContext?.matches(coordinator.state.value) == true
    })
    private val presentations = PresentationRepository(api, database) { profiles.current()?.owner == it }
    private var task: PublishedPracticeTask? = null
    private var preparedManifest: PracticeSupportManifest? = null
    private var preparedResumeSelected = false
    private var preparedReadOnly=false
    private data class Source6Binding(val owner:OwnerContext,val lesson:UUID,val ref:PracticeRef)
    private var source6Binding:Source6Binding?=null
    private fun source6(owner:OwnerContext?,lesson:UUID?,ref:PracticeRef?):Boolean=source6Binding?.let{it.owner==owner && it.lesson==lesson && it.ref==ref}==true
    private fun source6(session:PracticeSession?):Boolean=session!=null && source6(session.owner,session.lesson.lessonId,session.lesson.contentRef)
    private fun currentSource6():Boolean=coordinator.state.value.let{source6(it.owner,it.lessonId,it.task)}
    private fun PracticeRef.segmentContent():SegmentContentRef=when(this){is PreparedPracticeRef->content;is PracticeContentRef->content;else->error("Unknown source reference")}
    private val preparedRequestClock = PracticeRequestClock()
    private var preparedRequestActive = false
    var preparedPracticeMaterial: (suspend (PreparedContentRef) -> Triple<PreparedPracticeRef, String, PracticeSupportManifest>?)? = null
    private var questionReturn: ConversationReturn? = null
    private data class QuestionContext(val owner: OwnerContext, val work: ListeningWorkRef?, val task: PracticeRef?, val lesson: UUID?, val navigation: Long, val intent: Long, val prepared: PreparedListeningWorkRef? = null,val modal:PracticeModalToken?=null)
    private data class PreparedQuestionResult(val value:WireSource6InferenceQAResponse,val intent:String)
    private data class QuestionOutput(val id: UUID, val context: QuestionContext, var ended: Boolean = false,val prepared:PreparedQuestionResult?=null)
    data class PreparedQuestionInterpretation(val intent:String,val targetSpan:WireSource6TargetSpan?=null) {
        init{require(intent in setOf("content","language","mixed","unclear"))}
    }
    var interpretPreparedQuestion:(suspend(SegmentContentRef,String)->PreparedQuestionInterpretation)?=null
    /** Returns true only after an actual observer has durably joined this delivery to the review journal. */
    var observePreparedQuestionDelivery:(suspend(LearningCallScope,WireSource6InferenceQAResponse,String,UUID,String)->Boolean)?=null
    private var preparedQuestionResult:PreparedQuestionResult?=null
    private var preparedQuestionIntent="unclear"
    private var questionContext: QuestionContext? = null
    private var questionOutput: QuestionOutput? = null
    private fun QuestionContext.matches(view: SessionView): Boolean = owner == view.owner && task == view.task && lesson == view.lessonId &&
        navigation == view.navigationGeneration && intent == view.intentGeneration && modal==view.practiceModal?.token && (work == null && view.listening == null || work?.sameWork(view.listening) == true) &&
        (prepared == null && view.preparedListening == null || prepared?.sameWork(view.preparedListening) == true)
    private var support: PresentationUiBinding? = null
    private data class SupportContext(val navigation:Long,val intent:Long,val modal:PracticeModalToken?)
    private var supportContext:SupportContext? = null
    private val completedOutputs = mutableSetOf<UUID>()
    private val speech = DirectSpeechGateway(context, voices.direct) { event -> scope.launch { speechEvent(event) } }
    private val presenter = SupportPresenter(current = { binding ->
        val view = coordinator.snapshot()
        support == binding && supportContext?.let { it.navigation==view.navigationGeneration && it.intent==view.intentGeneration && it.modal==view.practiceModal?.token &&
            (it.modal==null || view.practiceModal?.visible==true) }==true && view.owner == binding.owner && view.task == binding.task && view.lessonId == binding.lessonId &&
            view.access.ownerValid && view.access.power == PowerAvailability.ALLOWED &&
            (if (binding.ticket.presentation.interactionChannel == InteractionChannel.PARKED) view.access.detailsOpen && view.access.window.focused
             else voices.gleo?.capabilities?.exactOutputContent is Capability.Verified && voices.gleo?.capabilities?.outputCompletion is Capability.Verified)
    }, record = { binding, observation ->
        profiles.current()?.takeIf { it.owner == binding.owner }?.let { if (source6(binding.owner,binding.lessonId,binding.task)) preparedPresentations.record(it, binding.ticket, observation) else presentations.record(it, binding.ticket, observation) }
    })
    val speechState get() = speech.state.value
    val microphoneGranted get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private data class GleoInput(val owner: OwnerContext, val lessonId: UUID?, val task: PracticeRef?, val id: UUID, val navigation: Long)
    private var gleoInput: GleoInput? = null
    private var gleoReplyAllowed = false
    val replyAllowed get() = speech.state.value.focusGranted || gleoReplyAllowed
    val gleoRuntime: GleoProductRuntime? = voices.gleo?.let { GleoGateway(it, object : GleoProductHandler {
        override suspend fun playback(command: String, title: String?, mode: String?): String {
            if (command == "resume" && questionReturn != null) return if (returnFromQuestion()) "원래 작업으로 돌아갈게요" else "저장된 원래 작업을 다시 확인해 주세요"
            return playbackHandler?.invoke(command, title, mode) ?: "뉴스 재생 연결을 준비하고 있어요"
        }
        override suspend fun question(text: String, title: String?): String = ask(text, title)
        override suspend fun practice(command: String, text: String?, title: String?, capture: GleoCaptureBinding?): String = when (command) {
            "start" -> { startPractice(InteractionChannel.GLEO); mutable.value.practice?.questionEn ?: "말하기 연습을 준비하지 못했어요" }
            "answer" -> {
                val current = coordinator.snapshot()
                val started = gleoInput
                val sameInput = started != null && capture != null && started.id == capture.inputId && started.owner == current.owner &&
                    started.task == current.task && started.lessonId == current.lessonId && started.navigation == current.navigationGeneration
                val lease = if (sameInput && current.lessonId != null && current.owner != null && mutable.value.practice?.progress?.nextTurn != null &&
                    (!currentSource6() || preparedResumeSelected && !preparedReadOnly) && support==null && flushQuestionDeliveries())
                    coordinator.startGleoAnswer(requireNotNull(current.owner), requireNotNull(current.lessonId)) else null
                if (lease == null || capture == null || text == null) "실제 답변 입력을 확인하지 못했어요. 같은 질문을 유지할게요"
                else {
                    gleoInput = null
                    val accepted = coordinator.acceptGleoFinal(lease, text, capture.capture, ::persistAnswer)
                    if (accepted == null) "답변을 접수하지 못했어요. 같은 질문을 유지할게요"
                    else { evaluate(accepted.lease.attemptId, InteractionChannel.GLEO); mutable.value.practice?.feedback?.feedbackKo ?: "답변을 저장했어요. 결과를 다시 확인할 수 있어요" }
                }
            }
            "retry" -> { readyForAnswer(); mutable.value.practice?.questionEn ?: "저장한 질문을 확인해 주세요" }
            "finish" -> if (finishPractice(true)) "이번 연습을 마쳤어요" else "연습 저장을 확인하지 못했어요. 같은 질문을 유지할게요"
            "hint", "explain_question", "explain_facts", "sentence_starter", "partial_correction", "complete_example" -> {
                val request = when(command) { "explain_question" -> HelpRequest.QUESTION_MEANING; "explain_facts" -> HelpRequest.FACT; "complete_example" -> HelpRequest.EXAMPLE; else -> HelpRequest.HINT }
                val exact = when(command) {
                    "sentence_starter" -> (if(currentSource6())preparedManifest else task?.manifest)?.find(SupportRole.SENTENCE_STARTER, SupportLanguage.EN)
                    "partial_correction" -> mutable.value.practice?.feedback?.attemptId?.let { id ->
                        (if(currentSource6())preparedPractice.preparedFeedbackSupports(requireNotNull(coordinator.state.value.owner),id)
                         else practice.preparedFeedbackSupports(requireNotNull(coordinator.state.value.owner), id)).singleOrNull { it.role == SupportRole.PARTIAL_CORRECTION } }
                    else -> null
                }
                if (command in setOf("sentence_starter", "partial_correction") && exact == null) "그 도움을 지금 준비하지 못했어요"
                else { requestHelp(request, InteractionChannel.GLEO, exact); support?.artifact?.text ?: "도움 출력을 준비하지 못했어요. 질문은 유지돼요" }
            }
            "resume" -> { readyForAnswer(); mutable.value.practice?.questionEn ?: "저장한 연습을 먼저 확인해 주세요" }
            else -> "요청을 확인하지 못했어요. 같은 질문을 유지할게요"
        }
    }) }
    var materialPracticeAllowed: (PracticeContentRef) -> Boolean = { true }
    var listeningOnly: () -> Boolean = { false }
    var playbackHandler: (suspend (String, String?, String?) -> String)? = null
    var beforePracticeEntry:(suspend(OwnerContext,UUID,PracticeRef)->Boolean)?=null
    var afterPracticeClose:(suspend(OwnerContext)->Boolean)?=null
    fun reconciler(listening:Source6ListeningRepository):IncrementalSyncReconciler=IncrementalSyncReconciler(incremental,reviews,preparedPractice,listening)
    /** Only original witnessed output requests are replayed; no new witness, output or model work. */
    suspend fun flushActualOutputs(call:LearningCallScope):LearningReply<Unit> {
        when(val legacy=presentations.flush(call)){
            is LearningReply.Failed->return legacy
            is LearningReply.Stored->Unit
        }
        return preparedPresentations.flushAll(call)
    }

    fun start() {
        speech.initialize()
        scope.launch { speech.state.collect { accessChanged() } }
        scope.launch { voices.gleo?.let { adapter ->
            coordinator.configureGleo(adapter.capabilities)
            adapter.events.collect { event -> when (event) {
                is GleoRuntimeEvent.InputStarted -> {
                    val current = coordinator.snapshot()
                    if (current.owner == event.owner && current.lessonId == event.lessonId) {
                        gleoInput = GleoInput(event.owner, event.lessonId, current.task, event.inputId, current.navigationGeneration)
                        coordinator.updateVoiceInput(VoiceInputActivity.CAPTURING, event.owner, android.os.SystemClock.elapsedRealtime())
                    }
                }
                is GleoRuntimeEvent.InputChanged -> { coordinator.updateVoiceInput(event.activity, event.owner, event.atMs); completeQuestionReturn() }
                is GleoRuntimeEvent.OutputEnded -> outputEnded(event.outputId, event.owner, event.atMs, event.evidenceRef)
                is GleoRuntimeEvent.ReplyAvailability -> if (event.owner == coordinator.state.value.owner) { gleoReplyAllowed = event.allowed; accessChanged() }
                is GleoRuntimeEvent.Interrupted -> if (event.owner == coordinator.state.value.owner) {
                    val interrupted=questionOutput
                    interrupted?.prepared?.takeIf{it.value.status=="answered" && !interrupted.ended}?.let{result->
                        profiles.current()?.takeIf{it.owner==event.owner}?.let{call->reviewJournal.recordQuestionDelivery(call,result.value,result.intent,interrupted.id,"output-interrupted-before-verified-completion")}
                    }
                    gleoReplyAllowed = false; gleoInput = null; questionOutput = null; coordinator.cancelCapture(event.owner); accessChanged()
                }
            } }
        } }
        scope.launch {
            while (isActive) {
                delay(200)
                when (coordinator.tick(android.os.SystemClock.elapsedRealtime())) {
                    WaitAction.ANNOUNCE_HINT_METHOD -> announce("막히면 힌트 줘, 또는 질문 뜻을 알려줘라고 말해 주세요", "ko", WaitKind.HINT_METHOD)
                    WaitAction.SAVE_AND_PAUSE -> finishPractice(false)
                    WaitAction.NEXT_ARTICLE -> playbackHandler?.invoke("next", null, null)
                    else -> Unit
                }
            }
        }
        scope.launch { coordinator.state.collect { view ->
            if (mutable.value.owner != view.owner) { speech.releaseFocus(); task = null; preparedManifest=null;preparedResumeSelected=false;preparedRequestActive=false;preparedRequestClock.clear()
                preparedReadOnly=false;source6Binding=null;preparedQuestionResult=null;support = null;supportContext=null;completedOutputs.clear(); questionReturn = null; questionContext = null; questionOutput = null; gleoInput = null; mutable.value = LearningPresentation(owner = view.owner) }
            if (speech.state.value.listening && !view.access.canCapture) speech.cancel()
            if (support != null && (support?.owner != view.owner || supportContext?.modal!=view.practiceModal?.token || view.practiceModal?.visible==false ||
                support?.ticket?.presentation?.interactionChannel==InteractionChannel.PARKED && (!view.access.detailsOpen || !view.access.window.focused))) closeSupport()
        } }
    }

    suspend fun handle(intent: UiIntent): Boolean {
        if (!coordinator.state.value.access.detailsOpen) return false
        when (intent) {
            is UiIntent.StartPractice -> startPractice(InteractionChannel.PARKED)
            is UiIntent.StartAnswer -> startAnswer()
            is UiIntent.FinishAnswer -> coordinator.snapshot().captureLease?.let { if (coordinator.finishAnswer(it.captureId)) speech.finish(it) }
            is UiIntent.CancelAnswer -> coordinator.snapshot().owner?.let { speech.cancel(); coordinator.cancelCapture(it) }
            is UiIntent.RetryFeedback -> mutable.value.lastAttemptId?.let { evaluate(it, mutable.value.channel, intent.readOnly) }
            is UiIntent.RetryAnswer -> readyForAnswer()
            is UiIntent.FinishPractice -> finishPractice(intent.declineRetry)
            is UiIntent.RequestHelp -> requestHelp(intent.request, InteractionChannel.PARKED)
            is UiIntent.CloseSupport -> closeSupport()
            is UiIntent.SupportWitness -> observeSupport(intent.witness)
            is UiIntent.OpenArticleQuestion -> openQuestionGuide()
            is UiIntent.ReturnFromQuestion -> returnFromQuestion()
            is UiIntent.RetryQuestion -> retryQuestion()
            else -> return false
        }
        return true
    }

    suspend fun startPractice(channel: InteractionChannel, fixed: PracticeContentRef? = null) {
        val view = coordinator.snapshot(); val call = profiles.current() ?: return
        val work = view.listening
        val prepared = view.preparedListening
        if (prepared != null && fixed == null) {
            val content=prepared.content
            if(content is ContentRef){
                val material=(catalog.readPractice(content) as? CatalogRead.Stored)?.value?:run{notice("이 기사의 고정 질문 자료를 아직 확인하지 못했어요");return}
                if(coordinator.state.value.preparedListening?.sameWork(prepared)==true)startPreparedPractice(channel,material.ref,material.questionEn,material.manifest)
                return
            }
            val material = preparedPracticeMaterial?.invoke(content as PreparedContentRef)
                ?: run { notice("이 기사의 고정 질문 자료를 아직 확인하지 못했어요. 듣던 진행은 유지돼요"); return }
            if(profiles.current()?.owner!=call.owner || coordinator.state.value.preparedListening?.sameWork(prepared)!=true || material.first.content!=prepared.content)return
            startPreparedPractice(channel, material.first, material.second, material.third)
            return
        }
        if (work == null && fixed == null) return
        if (call.owner != view.owner || view.task != null || view.pendingAnswer != null || view.access.power != PowerAvailability.ALLOWED ||
            channel == InteractionChannel.PARKED && !view.access.detailsOpen) return
        val op = coordinator.beginOperation(SessionOperationKind.PRACTICE, call.owner) ?: return
        try {
            if ((work != null || prepared!=null) && !freeze(call.owner)) { notice("듣던 위치를 저장하지 못했어요. 다시 시도해 주세요"); return }
            val published = when (val reply = catalog.readPractice(fixed?.content ?: requireNotNull(work).content)) {
                is CatalogRead.Stored -> reply.value
                is CatalogRead.Unavailable -> { notice("이 기사 말하기 연습을 아직 준비하지 못했어요"); return }
            }
            if (!materialPracticeAllowed(published.ref)) { notice("이 기사의 현재 단계 질문은 아직 준비되지 않았어요. 듣기는 이어갈 수 있어요"); return }
            if (fixed != null && published.ref != fixed) { notice("원래 질문 자료를 지금 확인할 수 없어요"); return }
            val checkpoint=work?.checkpointId?.takeIf { published.ref.content == work.content }?:prepared?.checkpointId?.takeIf{prepared.content==published.ref.content}
            val lesson = LessonInput(UUID.randomUUID(), published.ref, LessonInputPhase.QUESTION_WAIT, HelpSnapshot(),checkpoint, DialogueProgress.newLesson())
            if (!coordinator.bindPractice(op, published.ref, lesson.lessonId, lesson.dialogueProgress)) return
            task = published
            preparedManifest=null;preparedResumeSelected=false;preparedReadOnly=false;source6Binding=null
            practice.open(call, lesson, published.questionEn)
            val title = (catalog.readTitle(published.ref.content) as? CatalogRead.Stored)?.value
            mutable.update { it.copy(owner = call.owner, channel = channel, articleTitle = title, notice = null) }
            updatePractice(practice.synchronize(call))
            when (val snapshot = resumes.snapshot(call)) {
                is LearningReply.Stored -> resumes.select(call, snapshot.value, "practice", lesson.lessonId)
                is LearningReply.Failed -> notice("연습은 저장했어요. 이어하기 선택을 다시 확인해 주세요")
            }
            if (mutable.value.practice?.progress != null && (channel!=InteractionChannel.PARKED || beforePracticeEntry?.invoke(call.owner,lesson.lessonId,published.ref)==true))
                announce(published.questionEn, "en", WaitKind.FIRST_ANSWER)
        } finally { coordinator.finishOperation(op) }
    }

    /** A caller supplies only a server-pinned practice reference plus its exact public material. */
    suspend fun startPreparedPractice(channel: InteractionChannel, ref: PracticeRef, question: String, manifest: PracticeSupportManifest,
        restored: WireSource6PersonalLessonRecord? = null,readOnlyAttempt:UUID?=null,readOnly:Boolean=false) {
        val call = profiles.current() ?: return
        val view = coordinator.snapshot()
        if (view.owner != call.owner || view.pendingAnswer != null || restored==null && view.task != null || view.access.power != PowerAvailability.ALLOWED ||
            channel == InteractionChannel.PARKED && !view.access.detailsOpen) return
        if(restored==null && view.preparedListening?.content!=ref.segmentContent())return
        if(restored!=null && restored.contentRef!=ref.source6Wire())return
        if(manifest.taskId!=ref.supportTaskIdentity || manifest.questionRef!=ref.questionRef)return
        if(restored!=null){speech.cancel();closeSupport()}
        val displayOnly=readOnly || readOnlyAttempt!=null || restored?.state?.phase=="completed" || restored?.state?.pendingFeedbackAttemptId!=null || restored?.state?.pendingAttemptId!=null
        val operation = coordinator.beginOperation(SessionOperationKind.PRACTICE, call.owner) ?: return
        try {
            if (view.preparedListening != null && !freeze(call.owner)) { notice("듣던 구간 저장을 먼저 확인해 주세요"); return }
            val help=restored?.let{HelpSnapshot(it.helpReceived.map{kind->HelpKind.valueOf(kind.name)}.toSet(),it.supportRefs.toSet())}?:HelpSnapshot()
            val dialogue=restored?.let{DialogueProgress(it.dialogueProgress.hintMethodAnnounced)}?:DialogueProgress.newLesson()
            val lesson = LessonInput(restored?.lessonId?.let(UUID::fromString) ?: UUID.randomUUID(), ref, LessonInputPhase.QUESTION_WAIT,
                help,restored?.returnCheckpointId?.let(UUID::fromString)?:view.preparedListening?.checkpointId,dialogue)
            if (!coordinator.bindPractice(operation, ref, lesson.lessonId, lesson.dialogueProgress,help)) return
            source6Binding=Source6Binding(call.owner,lesson.lessonId,ref)
            task = null; preparedManifest = manifest; preparedResumeSelected = false;preparedReadOnly=displayOnly
            mutable.update { it.copy(owner = call.owner, practice = PracticeSession(call.owner, lesson, question, null), channel = channel, notice = null) }
            val opened=preparedPractice.open(call, lesson, question, restored)
            updatePractice(opened)
            if(opened !is PracticeResult.Ready)return
            if(!displayOnly && !selectPreparedResume(call,opened.session))return
            if(channel==InteractionChannel.PARKED && beforePracticeEntry?.invoke(call.owner,lesson.lessonId,ref)!=true){preparedResumeSelected=false;return}
            if(readOnlyAttempt!=null){
                preparedPractice.restore(call,opened.session,readOnlyAttempt)
                val result=preparedPractice.savedResult(call.owner,readOnlyAttempt)
                if(result!=null && result.contentRef==ref.source6Wire() && result.lessonState.lessonId==lesson.lessonId.toString()){
                    val feedback=result.feedback?.let{PracticeFeedback(UUID.fromString(it.attemptId),Judgement.valueOf(it.judgement.uppercase()),it.focus,it.feedbackKo,null,
                        EnglishAttemptStatus.valueOf(it.englishAttemptStatus.name),result.lessonState.practiceProgress())}
                    mutable.update{it.copy(practice=it.practice?.copy(feedback=feedback),lastAttemptId=readOnlyAttempt)}
                    if(feedback!=null)coordinator.showPractice(call.owner,lesson.lessonId,SessionScreen.FEEDBACK)
                }
            }else if(!displayOnly && mutable.value.practice?.progress?.nextTurn!=null)announce(question, "en", WaitKind.FIRST_ANSWER)
        } finally { coordinator.finishOperation(operation) }
    }
    /** History feedback reads preserve the active pointer and never acknowledge a displayed result. */
    suspend fun restorePreparedPractice(record:WireSource6PersonalLessonRecord,readOnlyAttempt:UUID?=null){
        val call=profiles.current()?:return
        if(coordinator.state.value.owner!=call.owner || !coordinator.state.value.access.detailsOpen)return
        val frozen=record.contentRef.domainPracticeRef()
        val material:Triple<PracticeRef,String,PracticeSupportManifest> = when(frozen){
            is PreparedPracticeRef->preparedPracticeMaterial?.invoke(frozen.content)?:run{notice("이 학습의 고정 자료를 지금 확인할 수 없어요. 원래 기록은 유지돼요");return}
            is PracticeContentRef->{
                val published=(catalog.readPractice(frozen.content) as? CatalogRead.Stored)?.value?.takeIf{it.ref==frozen}
                    ?:run{notice("이 학습의 고정 자료를 지금 확인할 수 없어요. 원래 기록은 유지돼요");return}
                Triple(published.ref,published.questionEn,published.manifest)
            }
            else->return
        }
        if(material.first.source6Wire()!=record.contentRef || profiles.current()?.owner!=call.owner)return
        val attempt=readOnlyAttempt?:record.state.pendingFeedbackAttemptId?.let(UUID::fromString)?:record.state.pendingAttemptId?.let(UUID::fromString)
            ?:if(record.state.phase=="completed")(record.state.validRetryAttemptId?:record.state.validFirstAttemptId)?.let(UUID::fromString) else null
        startPreparedPractice(InteractionChannel.PARKED,material.first,material.second,material.third,record,attempt,
            readOnly=readOnlyAttempt!=null || record.state.phase=="completed" || record.state.pendingFeedbackAttemptId!=null || record.state.pendingAttemptId!=null)
    }
    private suspend fun selectPreparedResume(call:LearningCallScope,current:PracticeSession):Boolean {
        if(current.owner!=call.owner || !source6(current) || coordinator.state.value.lessonId!=current.lesson.lessonId ||
            coordinator.state.value.task!=current.lesson.contentRef)return false
        val snapshot=(preparedListening.snapshot(call) as? LearningReply.Stored)?.value
            ?:run{notice("질문은 저장했어요. 이어하기 선택을 확인한 뒤 시작해 주세요");return false}
        val profile=snapshot.profile
        if(snapshot.records.filterIsInstance<WireSource6PersonalReadLesson>().none{it.recordId==current.lesson.lessonId.toString() && it.payload.contentRef==current.lesson.contentRef.source6Wire()})return false
        if(profile.activeResume?.kind=="practice" && profile.activeResume?.recordId==current.lesson.lessonId.toString()){
            if(profiles.current()?.owner!=call.owner || coordinator.state.value.lessonId!=current.lesson.lessonId || coordinator.state.value.task!=current.lesson.contentRef)return false
            preparedResumeSelected=true;return true
        }
        val request=WireSource6PersonalResumeSelection("6",UUID.nameUUIDFromBytes("practice-resume:${current.lesson.lessonId}:${profile.resumeVersion}".toByteArray()).toString(),
            call.owner.source6Profile(),profile.resumeVersion,WireSource6PersonalResumeRef("practice",current.lesson.lessonId.toString()))
        if(preparedListening.resume(call,request) !is LearningReply.Stored){notice("연습은 저장했어요. 이어하기 선택을 다시 확인해 주세요");return false}
        if(profiles.current()?.owner!=call.owner || coordinator.state.value.lessonId!=current.lesson.lessonId || coordinator.state.value.task!=current.lesson.contentRef)return false
        preparedResumeSelected=true;return true
    }

    suspend fun restorePractice(record: io.github.driveenglishlab.pleos.data.wire.WirePersonalLessonRecord, snapshot: ProfileReadSnapshot, selectPointer: Boolean = true, reviewedAttempt: UUID? = null) {
        val call = profiles.current() ?: return
        val view = coordinator.snapshot()
        if (!view.access.detailsOpen || view.owner != call.owner || view.pendingAnswer != null) return
        val ref = record.contentRef
        val fixed = PracticeContentRef(ContentRef(UUID.fromString(ref.articleId), UUID.fromString(ref.bundleId), UUID.fromString(ref.sourceSnapshotId),
            UUID.fromString(ref.listeningScriptId), ListeningMode.valueOf(ref.listeningMode.uppercase(java.util.Locale.ROOT))), UUID.fromString(ref.taskId), UUID.fromString(ref.basicQuestionVersionId))
        val published = (catalog.readPractice(fixed.content) as? CatalogRead.Stored)?.value?.takeIf { it.ref == fixed }
            ?: run { notice("이 학습의 고정 자료를 지금 이용할 수 없어요. 원래 기록은 유지돼요"); return }
        val operation = coordinator.beginOperation(SessionOperationKind.PRACTICE, call.owner) ?: return
        try {
            if (view.listening != null && !freeze(call.owner)) { notice("듣던 위치 저장을 먼저 확인해 주세요"); return }
            if (selectPointer && resumes.select(call, snapshot, "practice", UUID.fromString(record.lessonId)) !is LearningReply.Stored) { notice("이어서 할 학습 선택을 저장하지 못했어요"); return }
            val help = HelpSnapshot(record.helpReceived.map { HelpKind.valueOf(it.name) }.toSet(), record.supportRefs.toSet())
            val dialogue = DialogueProgress(record.dialogueProgress.hintMethodAnnounced)
            val lesson = LessonInput(UUID.fromString(record.lessonId), fixed, LessonInputPhase.SUSPENDED, help,
                record.returnCheckpointId?.let(UUID::fromString), dialogue)
            if (!coordinator.bindPractice(operation, fixed, lesson.lessonId, dialogue, help)) return
            task = published
            preparedManifest=null;preparedResumeSelected=false;source6Binding=null
            practice.open(call, lesson, published.questionEn, record)
            mutable.update { it.copy(owner = call.owner, channel = InteractionChannel.PARKED, notice = null, lastAttemptId = record.state.pendingFeedbackAttemptId?.let(UUID::fromString) ?: record.state.pendingAttemptId?.let(UUID::fromString)) }
            val title = (catalog.readTitle(fixed.content) as? CatalogRead.Stored)?.value
            mutable.update { it.copy(articleTitle = title) }
            val pending = reviewedAttempt?.toString() ?: record.state.pendingFeedbackAttemptId ?: record.state.pendingAttemptId
            if(beforePracticeEntry?.invoke(call.owner,lesson.lessonId,fixed)!=true)return
            if (pending != null) {
                updatePractice(practice.restore(call, UUID.fromString(pending)))
                if (mutable.value.practice?.feedback != null) coordinator.showPractice(call.owner, lesson.lessonId, SessionScreen.FEEDBACK)
            } else {
                val existing = mutable.value.practice
        updatePractice(if (existing!=null && source6(existing)) preparedPractice.resumeLesson(call, existing) else practice.resumeLesson(call))
                announce(published.questionEn, "en", WaitKind.FIRST_ANSWER)
            }
        } finally { coordinator.finishOperation(operation) }
    }

    suspend fun stopped() {
        speech.cancel()
        profiles.current()?.let { call ->
            val current = mutable.value.practice
            if (current!=null && source6(current)) {if(!preparedReadOnly)preparedPractice.suspendLesson(call,current)} else practice.suspendLesson(call)
        }
    }

    suspend fun announceArticleOffer(announcement:ReviewAnnouncement?=null) {
        val view = coordinator.snapshot(); val work = view.listening;val prepared=view.preparedListening; val owner = view.owner ?: return
        if ((prepared?.stage?:work?.stage) != ListeningStage.ARTICLE_OFFER || view.task != null) return
        val route = if (voices.gleo != null) OutputRoute.GLEO else OutputRoute.PARKED_TTS
        if (route == OutputRoute.PARKED_TTS) { if (!speech.acquireFocus(owner)) return; accessChanged() }
        val id = announcement?.requestId?:UUID.randomUUID()
        val token=announcement?:if(view.offerRuntime!=null)coordinator.prepareOfferAnnouncement(owner,id)?:return else null
        val text = if (listeningOnly()) "오늘은 듣기만 할게요. 말씀하지 않으면 다음 뉴스를 이어서 재생할게요" else "말하기 연습을 할까요, 뉴스를 더 들을까요? 말씀하지 않으면 다음 뉴스를 재생할게요"
        if (!coordinator.expectOutput(OutputExpectation(id, WaitKind.ARTICLE_OFFER, owner, null, null, route, work,prepared,token))) return
        if (route == OutputRoute.GLEO) voices.gleo?.bindReplyOutput(owner, id, text)
        else speech.speak(owner, id, text, "ko", coordinator.state.value.access)
    }

    fun cancelEffects() { speech.releaseFocus() }

    private suspend fun updatePractice(result: PracticeResult? = null) {
        val current = mutable.value.practice
        val value = if (currentSource6()) when (result) {
            is PracticeResult.Ready -> result.session
            is PracticeResult.Saved -> current?.copy(feedback = result.feedback,
                progress = result.feedback?.progress ?: preparedPractice.savedResult(requireNotNull(coordinator.state.value.owner), result.attemptId)?.lessonState?.practiceProgress() ?: current.progress,
                requestState=if(result.waiting)PracticeRequestState.WAIT_EXPIRED else if(result.feedback!=null)PracticeRequestState.STORED else PracticeRequestState.DEVICE_ONLY)
            is PracticeResult.Failed -> current?.copy(requestState=PracticeRequestState.FAILED)
            else -> if(preparedRequestActive)current?.copy(requestState=preparedRequestClock.state(android.os.SystemClock.elapsedRealtime())) else current
        } else practice.snapshot(android.os.SystemClock.elapsedRealtime())
        value ?: return
        if (value.owner != coordinator.state.value.owner || value.lesson.lessonId != coordinator.state.value.lessonId) return
        mutable.update { it.copy(practice = value, lastAttemptId = when(result) { is PracticeResult.Saved -> result.attemptId; is PracticeResult.Failed -> result.acceptedAttemptId ?: it.lastAttemptId; else -> it.lastAttemptId },
            notice = if (result is PracticeResult.Failed) "답변과 진행은 저장돼요. 연결을 확인한 뒤 결과를 다시 요청해 주세요" else it.notice) }
    }
    private suspend fun startAnswer() {
        val view = coordinator.snapshot(); val value = mutable.value.practice ?: return
        if(!flushQuestionDeliveries()){notice("실제로 들은 도움의 기록을 아직 확인하지 못했어요. 같은 질문은 유지돼요");return}
        if(view.practiceModal?.visible!=true)return
        if (source6(value) && (!preparedResumeSelected || preparedReadOnly)) return
        if (value.progress?.nextTurn == null || value.progress?.pendingFeedbackAttemptId != null || support != null) return
        if (!speech.state.value.approved) { notice("이 차량의 직접 음성 입력 경로를 확인하고 있어요. 현재 질문은 유지돼요"); return }
        if (!microphoneGranted) { mutable.update { it.copy(microphonePermissionNeeded = true) }; return }
        if (!speech.acquireFocus(requireNotNull(view.owner))) { notice("지금은 마이크를 사용할 수 없어요. 잠시 후 다시 눌러 주세요"); return }
        accessChanged()
        if (view.owner != coordinator.state.value.owner) return
        val lease = coordinator.startAnswer() ?: return
        if (!speech.start(lease, coordinator.state.value.access)) { coordinator.cancelCapture(lease.owner); notice("답변 입력을 시작하지 못했어요. 다시 눌러 주세요") }
    }
    /** A permission result changes availability only; it never opens a capture. */
    suspend fun permissionResult() { mutable.update { it.copy(microphonePermissionNeeded = false) }; accessChanged() }
    private suspend fun speechEvent(event: DirectSpeechEvent) {
        when (event) {
            is DirectSpeechEvent.Final -> {
                coordinator.finishAnswer(event.lease.captureId)
                if(!flushQuestionDeliveries()){coordinator.cancelCapture(event.lease.owner);notice("도움 제공 기록을 확인한 뒤 같은 질문에 다시 답해 주세요");return}
                val answer = coordinator.acceptFinal(event.lease.captureId, event.text, event.capture, ::persistAnswer) ?: return
                scope.launch { evaluate(answer.lease.attemptId, InteractionChannel.PARKED) }
            }
            is DirectSpeechEvent.Ended -> outputEnded(event.outputId, event.owner, event.endedAtMs, event.evidenceRef)
            is DirectSpeechEvent.Failed -> {
                val view = coordinator.snapshot()
                if (!speech.acceptsEvent(event.owner, event.focusGeneration) || event.owner != null && event.owner != view.owner) return
                if (event.captureId != null && event.captureId != view.captureLease?.captureId) return
                if (event.captureId != null) view.owner?.let { coordinator.cancelCapture(it) }
                if (event.captureId != null || view.inputPhase == InputPhase.IDLE) notice("음성 처리를 완료하지 못했어요. 같은 질문에 다시 답할 수 있어요")
            }
            is DirectSpeechEvent.Interrupted -> {
                val view = coordinator.snapshot()
                if (speech.acceptsEvent(event.owner, event.focusGeneration) && event.owner == view.owner &&
                    event.captureId != null && event.captureId == view.captureLease?.captureId) coordinator.cancelCapture(event.owner)
            }
        }
    }
    private suspend fun evaluate(id: UUID, channel: InteractionChannel, readOnly: Boolean = false) {
        val call = profiles.current() ?: return; val view = coordinator.snapshot(); val lesson = view.lessonId ?: return
        val op = coordinator.beginOperation(SessionOperationKind.FEEDBACK, call.owner) ?: return
        val isPrepared=source6(mutable.value.practice)
        if(isPrepared){preparedRequestClock.start(android.os.SystemClock.elapsedRealtime());preparedRequestActive=true}
        mutable.update { it.copy(lastAttemptId = id, channel = channel) }
        val ticker = scope.launch { while (isActive) { delay(250); updatePractice() } }
        try {
            val current = mutable.value.practice
            val result = if (current!=null && source6(current)) {
                if (readOnly) preparedPractice.restore(call, current, id) else withTimeoutOrNull(if(channel==InteractionChannel.GLEO)6_000L else 10_000L) {
                    when(val flushed=preparedPresentations.flushPending(call,current.lesson.contentRef,current.lesson.lessonId)){
                        is LearningReply.Failed->PracticeResult.Failed(flushed.code,id)
                        is LearningReply.Stored->preparedPractice.submit(call,current,id,channel,waitExpired={preparedRequestClock.state(android.os.SystemClock.elapsedRealtime())==PracticeRequestState.WAIT_EXPIRED})
                    }
                }?:PracticeResult.Failed("DEADLINE_EXCEEDED",id)
            } else if (readOnly) practice.restore(call, id) else withTimeoutOrNull(if(channel==InteractionChannel.GLEO)6_000L else 10_000L){
                when(val admitted=legacyAssistance.admit(call,id)){
                    is LearningReply.Failed->PracticeResult.Failed(admitted.code,id)
                    is LearningReply.Stored->practice.submit(call,id,channel)
                }
            }?:PracticeResult.Failed("DEADLINE_EXCEEDED",id)
            updatePractice(result)
            scope.launch { practiceTimes.flush(call) }
            if (result is PracticeResult.Saved) coordinator.practiceResult(call.owner, lesson, id, result.feedback != null && !result.waiting, op.navigationGeneration)
        } finally { ticker.cancel();if(isPrepared){preparedRequestActive=false;preparedRequestClock.clear()}; coordinator.finishOperation(op) }
    }
    private suspend fun acknowledge(): Boolean {
        if(preparedReadOnly)return false
        val call = profiles.current() ?: return false; val p = mutable.value.practice ?: return false
        val pending = p.progress?.pendingFeedbackAttemptId ?: return true
        if (p.feedback?.attemptId != pending) return false
        val witness = FeedbackPresentationWitness(call.owner, p.lesson.lessonId, pending, "explicit_user_action", UUID.randomUUID(), "current-feedback-explicit-continue")
        val reply = if (source6(p)) preparedPractice.acknowledgeFeedback(call, p, witness) else practice.acknowledgeFeedback(call, witness)
        updatePractice(if (source6(p)) preparedPractice.synchronize(call, p) else null)
        if (reply is LearningReply.Failed) notice("피드백 확인을 저장하지 못했어요. 같은 결과를 유지할게요")
        return reply is LearningReply.Stored
    }
    private suspend fun readyForAnswer() {
        if(preparedReadOnly)return
        if(!flushQuestionDeliveries()){notice("실제로 들은 도움의 기록을 확인하고 있어요. 같은 질문은 유지돼요");return}
        if (!acknowledge()) return
        val call = profiles.current() ?: return
        val existing = mutable.value.practice
        if(existing!=null && source6(existing) && !preparedResumeSelected && !selectPreparedResume(call,existing))return
        updatePractice(if (existing!=null && source6(existing)) preparedPractice.resumeLesson(call, existing) else practice.resumeLesson(call))
        val p = mutable.value.practice ?: return
        if(mutable.value.channel==InteractionChannel.PARKED && coordinator.state.value.practiceModal?.visible!=true && beforePracticeEntry?.invoke(call.owner,p.lesson.lessonId,p.lesson.contentRef)!=true)return
        if (p.progress?.nextTurn != null) {
            coordinator.showPractice(p.owner, p.lesson.lessonId, SessionScreen.QUESTION)
            announce(p.questionEn, "en", WaitKind.FIRST_ANSWER)
        }
    }
    private suspend fun finishPractice(decline: Boolean): Boolean {
        val call = profiles.current() ?: return false
        speech.cancel(); closeSupport()
        val p = mutable.value.practice ?: return false
        if(preparedReadOnly){
            if(coordinator.state.value.practiceModal!=null && afterPracticeClose?.invoke(call.owner)!=true)return false
            coordinator.leavePractice(call.owner);preparedReadOnly=false;preparedResumeSelected=false;preparedManifest=null;source6Binding=null
            mutable.update{it.copy(practice=null,support=null)};coordinator.navigate(call.owner,SessionScreen.PLAYER);return true
        }
        val reply = if (decline && p.progress?.phase == "retry_offer") {
            if (source6(p)) preparedPractice.declineRetry(call, p) else practice.declineRetry(call)
        } else {
            if (p.progress?.phase == "completed" && !acknowledge()) return false
            if (source6(p)) preparedPractice.suspendLesson(call, p) else practice.suspendLesson(call)
        }
        if (reply is LearningReply.Failed) { notice("진행 저장을 확인하지 못했어요. 같은 연습을 유지할게요"); return false }
        if(coordinator.state.value.practiceModal!=null && afterPracticeClose?.invoke(call.owner)!=true)return false
        coordinator.leavePractice(call.owner); speech.releaseFocus()
        mutable.update { it.copy(practice = null, support = null) }
        preparedManifest=null;preparedResumeSelected=false;supportContext=null;source6Binding=null
        coordinator.navigate(call.owner, SessionScreen.PLAYER)
        return true
    }
    private suspend fun requestHelp(request: HelpRequest, channel: InteractionChannel, exact: SupportArtifact? = null) {
        val call = profiles.current() ?: return; val view = coordinator.snapshot()
        val ref = view.task ?: return
        if(channel==InteractionChannel.PARKED && view.practiceModal?.visible!=true)return
        val manifest = if (source6(view.owner,view.lessonId,ref)) preparedManifest else task?.manifest
        manifest ?: return
        if (support != null || view.pendingAnswer != null || view.inputPhase != InputPhase.IDLE) return
        val artifact = exact ?: (PracticePolicy.provide(request, manifest, ref, view.helpReceived) as? SupportPreparation.Ready)?.prepared?.artifact
            ?: run { notice("요청한 도움을 아직 준비하지 못했어요"); return }
        val registered = if (source6(view.owner,view.lessonId,ref)) preparedPresentations.prepare(call, ref, view.lessonId, listOf(artifact), channel)
            else presentations.prepare(call, ref as PracticeContentRef, view.lessonId, listOf(artifact), channel)
        val live=coordinator.snapshot()
        if(live.owner!=view.owner || live.task!=ref || live.lessonId!=view.lessonId || live.navigationGeneration!=view.navigationGeneration || live.intentGeneration!=view.intentGeneration ||
            live.practiceModal?.token!=view.practiceModal?.token || live.practiceModal?.visible==false)return
        if (registered !is LearningReply.Stored || !coordinator.preparedSupport(registered.value)) { notice("도움 출력을 준비하지 못했어요. 질문은 유지돼요"); return }
        val binding = PresentationUiBinding(call.owner, ref, view.lessonId, registered.value, UUID.randomUUID(), artifact)
        support = binding
        supportContext=SupportContext(view.navigationGeneration,view.intentGeneration,view.practiceModal?.token)
        if (channel == InteractionChannel.PARKED && view.access.detailsOpen) mutable.update { it.copy(support = binding) }
        else if (voices.gleo?.bindReplyOutput(call.owner, binding.outputId, artifact.text) != true)
            notice("음성 도움 출력 경로를 확인하고 있어요. 같은 질문은 유지돼요")
    }
    private suspend fun announce(text: String, language: String, kind: WaitKind) {
        val view = coordinator.snapshot(); val owner = view.owner ?: return; val ref = view.task ?: return
        val id = UUID.randomUUID()
        val route = if (mutable.value.channel == InteractionChannel.GLEO) OutputRoute.GLEO else OutputRoute.PARKED_TTS
        if (route == OutputRoute.PARKED_TTS) { if (!speech.acquireFocus(owner)) return; accessChanged() }
        if (!coordinator.expectOutput(OutputExpectation(id, kind, owner, ref, view.lessonId, route,modalToken=view.practiceModal?.token))) return
        if (route == OutputRoute.PARKED_TTS) speech.speak(owner, id, text, language, coordinator.state.value.access)
        else voices.gleo?.bindReplyOutput(owner, id, text)
    }
    private suspend fun outputEnded(id: UUID, owner: OwnerContext, at: Long, evidence: String) {
        val qa = questionOutput
        if (qa?.id == id && qa.context.owner == owner) {
            qa.ended = true
            qa.prepared?.takeIf{it.value.status=="answered"}?.let{result->profiles.current()?.takeIf{it.owner==owner}?.let{call->
                reviewJournal.recordQuestionDelivery(call,result.value,result.intent,id,evidence);flushQuestionDeliveries()
            }}
            completeQuestionReturn()
        }
        coordinator.outputCompleted(id, owner, at, evidence) { original, fact ->
            if (currentSource6()) preparedPractice.persistHintCompletion(original, fact)
            else practice.persistHintCompletion(original, fact)
        }
        val binding = support?.takeIf { it.outputId == id && it.owner == owner } ?: return
        observeSupport(ArtifactWitness(id, binding.artifact.textHash, ObservationMode.VOICE_OUTPUT_COMPLETED, evidence))
    }
    private suspend fun observeSupport(witness: ArtifactWitness) {
        val binding = support ?: return; val call = profiles.current()?.takeIf { it.owner == binding.owner } ?: return
        val observation = presenter.witnessed(binding, witness) ?: return
        completedOutputs += binding.outputId
        coordinator.observedSupport(binding.ticket.presentation.presentationId, observation)
        when (if (source6(binding.owner,binding.lessonId,binding.task)) preparedPresentations.send(call, binding.ticket.presentation.presentationId, observation,binding.task)
            else presentations.send(call, binding.ticket.presentation.presentationId, observation)) {
            is LearningReply.Stored -> updatePractice(if (source6(binding.owner,binding.lessonId,binding.task)) mutable.value.practice?.let { preparedPractice.synchronize(call, it) } else practice.synchronize(call))
            is LearningReply.Failed -> notice("도움 제공 기록을 기기에 저장했어요. 연결되면 다시 확인할게요")
        }
    }
    private suspend fun closeSupport() {
        val binding = support ?: return
        if (binding.outputId !in completedOutputs) {
            val observation = presenter.interrupted(binding)
            profiles.current()?.takeIf { it.owner == binding.owner }?.let { call ->
                if (source6(binding.owner,binding.lessonId,binding.task)) { preparedPresentations.record(call, binding.ticket, observation); preparedPresentations.send(call, binding.ticket.presentation.presentationId, observation,binding.task) }
                else presentations.send(call, binding.ticket.presentation.presentationId, observation)
            }
        }
        support = null;supportContext=null; mutable.update { it.copy(support = null) }
    }
    private suspend fun origin(): QuestionOrigin? {
        val view = coordinator.snapshot()
        if (view.task != null && view.lessonId != null) return QuestionOrigin.Practice(requireNotNull(view.lessonId), requireNotNull(view.task))
        view.preparedListening?.let { work ->
            return if (work.stage == ListeningStage.ARTICLE_OFFER) QuestionOrigin.PreparedArticleOffer(work.content)
                else QuestionOrigin.PreparedListening(PreparedReturnPoint(work, view.intent))
        }
        val work = view.listening ?: return null
        if (work.stage == ListeningStage.ARTICLE_OFFER) return QuestionOrigin.ArticleOffer(work.content)
        return QuestionOrigin.Listening(ReturnPoint(PlaybackPosition(requireNotNull(work.assetId), work.stage,
            (view.mediaPositionMs ?: 0).coerceIn(0, requireNotNull(work.durationMs)), requireNotNull(work.durationMs)), view.intent))
    }
    private suspend fun openQuestionGuide() {
        val call = profiles.current() ?: return
        val op = coordinator.beginOperation(SessionOperationKind.QUESTION, call.owner) ?: return
        try {
            if (!freeze(call.owner)) { notice("듣던 위치 저장을 다시 확인해 주세요"); return }
            val original = origin() ?: return
            val frozen = coordinator.snapshot()
            questionContext = QuestionContext(call.owner, frozen.listening, frozen.task, frozen.lessonId, frozen.navigationGeneration, frozen.intentGeneration, frozen.preparedListening,frozen.practiceModal?.token)
            questionOutput = null
            preparedQuestionResult=null
            if (!coordinator.openArticleQuestion(op)) return
            questionReturn = ConversationPolicy.returnTo(original)
            mutable.update { it.copy(questionOrigin = original, questionAnswer = null, questionStatus = LoadStatus.NOT_REQUESTED,
                questionNotice = if (gleoRuntime == null) "이 차량의 Gleo 기사 질문 연결을 준비하고 있어요. 듣던 기사와 연습은 유지돼요" else "Gleo에게 이 기사에 대해 물어보세요") }
        } finally { coordinator.finishOperation(op) }
    }
    private suspend fun ask(text: String, title: String?): String {
        val call = profiles.current() ?: return "학습 연결을 먼저 확인해 주세요"
        val before = coordinator.snapshot()
        val prepared = before.task?.takeIf{currentSource6()}?.segmentContent() ?: before.preparedListening?.content
        if (prepared != null) return askPrepared(call, prepared, text, title)
        val source = (before.task as? PracticeContentRef)?.content ?: before.listening?.content ?: return "질문할 기사를 먼저 들어 주세요"
        preparedQuestionResult=null
        var asked = source
        if (!title.isNullOrBlank()) {
            val rows = (catalog.catalogPage(0, 50) as? CatalogRead.Stored)?.value ?: return "기사 제목을 확인하지 못했어요. 듣던 기사는 유지돼요"
            val identities = rows.map { ArticleIdentity(it.articleId, it.title, it.title, it.publisher, it.publishedAtRaw) }
            when(val match = questions.resolveTitle(title, identities, mutable.value.titleConfirmationUsed)) {
                is TitleResolution.Selected -> { asked = (catalog.readArticleRef(rows.single { it.articleId == match.article.articleId }, source.listeningMode) as? CatalogRead.Stored)?.value ?: return "그 기사를 지금 이용할 수 없어요" }
                is TitleResolution.AskOnce -> { mutable.update { it.copy(titleConfirmationUsed = true) }; return "${match.candidates.take(2).joinToString(" 또는 ") { it.title }} 중 어떤 기사인가요?" }
                TitleResolution.KeepCurrentWork -> return "기사 제목을 확인하지 못했어요. 듣던 기사는 유지돼요"
            }
        }
        val op = coordinator.beginOperation(SessionOperationKind.QUESTION, call.owner) ?: return "이전 질문을 처리하고 있어요"
        try {
            if (before.listening != null && !freeze(call.owner)) return "듣던 위치 저장을 확인하지 못했어요"
            val original = origin() ?: return "원래 작업을 확인하지 못했어요"
            val frozen = coordinator.snapshot()
            questionContext = QuestionContext(call.owner, frozen.listening, frozen.task, frozen.lessonId, frozen.navigationGeneration, frozen.intentGeneration, frozen.preparedListening,frozen.practiceModal?.token)
            questionOutput = null
            if (!coordinator.openArticleQuestion(op)) return "원래 작업을 확인하지 못했어요"
            mutable.update { it.copy(channel = InteractionChannel.GLEO, questionOrigin = original, questionStatus = LoadStatus.LOADING, questionAnswer = null, questionNotice = null, titleConfirmationUsed = false) }
            val response = finishQuestion(questions.ask(call, asked, original, text, InteractionChannel.GLEO))
            bindQuestionReply(response)
            return response
        } finally { coordinator.finishOperation(op) }
    }
    var resolvePreparedTitle: (suspend (String, PreparedContentRef) -> PreparedContentRef?)? = null
    private suspend fun askPrepared(call: LearningCallScope, source: SegmentContentRef, text: String, title: String?): String {
        val asked:SegmentContentRef=if(title.isNullOrBlank())source else when(source){
            is PreparedContentRef->resolvePreparedTitle?.invoke(title,source)?:return "기사 제목을 확인하지 못했어요. 듣던 진행은 유지돼요"
            is ContentRef->{
                val rows=(catalog.catalogPage(0,50) as? CatalogRead.Stored)?.value?:return "기사 제목을 확인하지 못했어요. 듣던 진행은 유지돼요"
                when(val match=questions.resolveTitle(title,rows.map{ArticleIdentity(it.articleId,it.title,it.title,it.publisher,it.publishedAtRaw)},mutable.value.titleConfirmationUsed)){
                    is TitleResolution.Selected->(catalog.readArticleRef(rows.single{it.articleId==match.article.articleId},source.listeningMode) as? CatalogRead.Stored)?.value?:return "그 기사를 지금 이용할 수 없어요"
                    is TitleResolution.AskOnce->{mutable.update{it.copy(titleConfirmationUsed=true)};return "${match.candidates.take(2).joinToString(" 또는 "){it.title}} 중 어떤 기사인가요?"}
                    TitleResolution.KeepCurrentWork->return "기사 제목을 확인하지 못했어요. 듣던 진행은 유지돼요"
                }
            }
        }
        val operation = coordinator.beginOperation(SessionOperationKind.QUESTION, call.owner) ?: return "이전 질문을 처리하고 있어요"
        try {
            val interpretation=interpretPreparedQuestion?.invoke(asked,text)?:PreparedQuestionInterpretation("unclear")
            interpretation.targetSpan?.let{span->
                val valid=when(asked){
                    is PreparedContentRef->span.scriptId==asked.mainScriptId.toString() && span.textHash==asked.mainTextHash
                    is ContentRef->(catalog.readMain(asked) as? CatalogRead.Stored)?.value?.let{it.scriptId.toString()==span.scriptId && it.textHash==span.textHash}==true
                }
                if(!valid)return "질문할 문장 범위를 먼저 확인해 주세요. 듣던 진행은 유지돼요"
            }
            if (coordinator.state.value.preparedListening != null && !freeze(call.owner)) return "듣던 구간 저장을 확인하지 못했어요"
            val original = origin() ?: return "원래 작업을 확인하지 못했어요"
            val frozen = coordinator.snapshot()
            questionContext = QuestionContext(call.owner, frozen.listening, frozen.task, frozen.lessonId, frozen.navigationGeneration, frozen.intentGeneration, frozen.preparedListening,frozen.practiceModal?.token)
            questionOutput = null
            if (!coordinator.openArticleQuestion(operation)) return "원래 작업을 확인하지 못했어요"
            mutable.update { it.copy(channel = InteractionChannel.GLEO, questionOrigin = original, questionStatus = LoadStatus.LOADING, questionAnswer = null, questionNotice = null) }
            preparedQuestionIntent=interpretation.intent;preparedQuestionResult=null
            val response = finishPreparedQuestion(preparedQuestions.ask(call, asked, original, text, InteractionChannel.GLEO,questionIntent=interpretation.intent,targetSpan=interpretation.targetSpan))
            bindQuestionReply(response)
            return response
        } finally { coordinator.finishOperation(operation) }
    }
    private fun finishPreparedQuestion(result: Source6ArticleQuestionResult): String = when (result) {
        is Source6ArticleQuestionResult.Answer -> {
            preparedQuestionResult=PreparedQuestionResult(result.value,preparedQuestionIntent)
            questionReturn = result.returnPlan
            mutable.update { it.copy(questionStatus = LoadStatus.READY, questionAnswer = result.value.answerText, questionNotice = null) }
            result.value.answerText
        }
        is Source6ArticleQuestionResult.Failed -> {
            preparedQuestionResult=null
            questionReturn = result.returnPlan
            val message = "질문을 처리하지 못했어요. 듣던 기사와 연습은 유지돼요"
            mutable.update { it.copy(questionStatus = LoadStatus.FAILED, questionNotice = message) }; message
        }
        else -> "현재 대화가 바뀌었어요. 저장한 진행은 유지돼요"
    }
    private fun finishQuestion(result: ArticleQuestionResult): String = when(result) {
        is ArticleQuestionResult.Answer -> {
            questionReturn = result.returnPlan
            mutable.update { it.copy(questionStatus = LoadStatus.READY, questionAnswer = result.value.answerText, questionNotice = null) }
            result.value.answerText
        }
        is ArticleQuestionResult.Failed -> {
            questionReturn = result.returnPlan
            val message = "질문을 처리하지 못했어요. 듣던 기사와 연습은 유지돼요"
            mutable.update { it.copy(questionStatus = LoadStatus.FAILED, questionNotice = message) }; message
        }
        else -> "현재 대화가 바뀌었어요. 저장한 진행은 유지돼요"
    }
    private suspend fun retryQuestion() {
        val call = profiles.current() ?: return
        mutable.update { it.copy(questionStatus = LoadStatus.LOADING) }
        if (coordinator.state.value.preparedListening != null || currentSource6())
            finishPreparedQuestion(preparedQuestions.retry(call)) else finishQuestion(questions.retry(call))
    }
    private suspend fun bindQuestionReply(text: String) {
        val adapter = voices.gleo ?: return
        val context = questionContext ?: return
        if (adapter.capabilities.outputCompletion !is Capability.Verified || adapter.capabilities.exactOutputContent !is Capability.Verified ||
            !context.matches(coordinator.snapshot())) return
        val output = QuestionOutput(UUID.randomUUID(), context,prepared=preparedQuestionResult)
        questionOutput = output
        if (!adapter.bindReplyOutput(context.owner, output.id, text) && questionOutput == output) questionOutput = null
    }
    private suspend fun flushQuestionDeliveries():Boolean {
        val call=profiles.current()?:return false
        for(pending in reviewJournal.pendingQuestionDeliveries(call)) {
            if(observePreparedQuestionDelivery?.invoke(call,pending.result,pending.intent,pending.outputId,pending.evidence)!=true)return false
            reviewJournal.questionDeliveryJoined(call,pending)
        }
        return true
    }
    private suspend fun completeQuestionReturn() {
        val output = questionOutput?.takeIf { it.ended } ?: return
        val view = coordinator.snapshot()
        if (!output.context.matches(view) || !view.access.ownerValid || view.access.power != PowerAvailability.ALLOWED || !view.access.audioAllowsReply) {
            questionOutput = null; return
        }
        if (view.voiceInput != VoiceInputActivity.IDLE || view.inputPhase in setOf(InputPhase.CAPTURING, InputPhase.FINALIZING)) return
        returnFromQuestion()
    }
    private suspend fun returnFromQuestion(): Boolean {
        val call = profiles.current() ?: return false; val plan = questionReturn ?: return false
        if (questionContext?.matches(coordinator.snapshot()) != true) return false
        questions.interrupt(); preparedQuestions.interrupt(); questionOutput = null
        // This is either an explicit user return or the exact verified reply's real end.
        restoreQuestion(call.owner, plan, true)
        mutable.update { it.copy(questionOrigin = null, questionAnswer = null, questionStatus = LoadStatus.NOT_REQUESTED, questionNotice = null) }
        questionReturn = null; questionContext = null
        if (plan is ConversationReturn.SameQuestion) mutable.value.practice?.let { announce(it.questionEn, "en", WaitKind.FIRST_ANSWER) }
        if (plan is ConversationReturn.ReannounceOffer || plan is ConversationReturn.ReannouncePreparedOffer) announceArticleOffer()
        return true
    }
    private fun notice(text: String) { mutable.update { it.copy(notice = text) } }
}

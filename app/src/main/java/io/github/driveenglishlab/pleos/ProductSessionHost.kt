package io.github.driveenglishlab.pleos

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import com.google.common.util.concurrent.ListenableFuture
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.driveenglishlab.pleos.data.*
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import io.github.driveenglishlab.pleos.platform.*
import io.github.driveenglishlab.pleos.ui.*
import java.util.UUID
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Module
@InstallIn(SingletonComponent::class)
object ProductRuntimeModule {
    @Provides @Singleton fun voiceConfiguration() = ProductVoiceConfiguration()
    @Provides @Singleton fun settingsDemoLoader(@ApplicationContext context: Context) = ReviewedSettingsDemoLoader(context)
    @Provides @Singleton fun coordinator() = SessionCoordinator()
    @Provides @Singleton fun vehicle(@ApplicationContext context: Context): VehicleStateSource =
        PublicVehicleStateSource(context, CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
}

private data class ProductPresentation(val owner: OwnerContext? = null, val profile: WirePersonalProfileView? = null,
    val catalog: List<CatalogArticle> = emptyList(), val catalogStatus: LoadStatus = LoadStatus.NOT_REQUESTED,
    val more: Boolean = false, val offset: Int = 0, val article: CatalogArticle? = null,
    val reader: ReaderPresentation? = null, val readerStatus: LoadStatus = LoadStatus.NOT_REQUESTED,
    val draft: SettingsDraft? = null, val themeDraft: DeviceAppearance = DeviceAppearance.SYSTEM,
    val notice: String? = null, val recovery: String? = null, val noCandidate: String? = null,
    val settingsPending: Boolean = false, val selectionPending: Boolean = false,
    val resumeFrame: ListeningFrame? = null, val formatPendingMode: ListeningMode? = null,
    val history: ProfileReadSnapshot? = null, val historyStatus: LoadStatus = LoadStatus.NOT_REQUESTED, val deletionPending: Boolean = false,
    val daily: DailyListeningState = DailyListeningState(), val listenOnly: Boolean = false, val bandDraft: LearningBand? = null,
    val preparedCatalog: List<WirePreparationDiscovery> = emptyList(), val categoryConfig: WirePreparationCategoryPublicProjection? = null,
    val preparedCatalogCursor: String? = null, val preparedCatalogAvailable: Boolean = false,
    val preparedSelection: WireSource6PersonalSelectionRecord? = null, val preparedScript: SourcePublicScript? = null,
    val preparedSource: WirePreparationDiscovery? = null, val followupWaiting: Boolean = false, val source6History: Source6ProfileSnapshot? = null,
    val accountEntry: AccountEntryState = AccountEntryState(), val identityRecovery: IdentityRecoveryProjection? = null, val autoProgress: WireReview2AutoProgressRef? = null, val autoProgressHead: String? = null,
    val draftStorageRevision: Long? = null, val draftRevision: Long = 1, val draftToken: UUID = UUID.randomUUID())

private data class PendingReview2Selection(val owner: OwnerContext, val binding: PlaybackRequestBinding,
    val request: WireReview2ReviewNextRequest2, val navigationGeneration: Long, val viaVoice: Boolean)

private data class PendingReview3Selection(val owner: OwnerContext, val binding: PlaybackRequestBinding,
    val request: WireReview3ReviewNextRequest3, val navigationGeneration: Long, val viaVoice: Boolean)

private data class PendingFormatChange(val owner: OwnerContext, val id: UUID, val mode: ListeningMode)
private data class PendingNewsSelection(val owner: OwnerContext, val id: UUID, val article: UUID?, val fresh: Boolean, val prepared: Boolean = false, val preference: NextSelectionPreference? = null)

/** Process composition only. Business navigation/identity stays in SessionCoordinator; repositories
 * retain canonical records. Every network call happens outside its reducer lock.
 */
@UnstableApi
@Singleton
class ProductSessionHost @Inject constructor(@param:ApplicationContext private val context: Context,
    database: LearningDatabase, val coordinator: SessionCoordinator, val appearances: DeviceAppearanceStore,
    private val vehicle: VehicleStateSource, voices: ProductVoiceConfiguration,
    private val settingsDemoLoader: ReviewedSettingsDemoLoader) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val originalTtsReview = voices.pleosTts
    private val originalTtsRegistration = voices.pleosRegistration
    private val profiles = ProfileRepository(context, database, BuildConfig.LEARNING_API_ORIGIN, BuildConfig.DEBUG)
    private val api = LearningApi(context, BuildConfig.LEARNING_API_ORIGIN, profiles, BuildConfig.DEBUG)
    private val catalog = CatalogRepository(context)
    private val incrementalApi = api.incremental(context)
    private val incrementalCodecs = IncrementalContractJson(context)
    private val preparedCatalog = catalog.prepared(context, incrementalApi, database.review())
    private val source6Listening = Source6ListeningRepository(incrementalApi, database.review(), incrementalCodecs)
    private val reviewNext2 = ReviewNext2ListeningRepository(incrementalApi, incrementalCodecs, database, database.serviceIntents(), database.review(), Source6ReviewNextStatePort(source6Listening, incrementalCodecs))
    private var pendingReview2Selection: PendingReview2Selection? = null
    private val reviewNext3 = ReviewNext3ListeningRepository(incrementalApi, incrementalCodecs, database, database.serviceIntents(), database.review(), Source6ReviewNext3StatePort(source6Listening, incrementalCodecs))
    private var pendingReview3Selection: PendingReview3Selection? = null
    private val catalogViews = CatalogViewsRepository(incrementalApi, incrementalCodecs, database.serviceIntents())
    private val autoHeadRecovery = AutoProgressHeadRecoveryRepository(incrementalApi, incrementalCodecs, database.serviceIntents(), database.review(), database.learning())
    private val assessmentReviews = AssessmentReviewRepository(incrementalApi, incrementalCodecs, database)
    private val reviewDao = database.review()
    private val ownerDrafts = OwnerSettingsDraftStore(context, database.review())
    private val draftLane = Mutex()
    private val ownerDraftJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = false; explicitNulls = true; encodeDefaults = true }
    private val reviewRepository = ReviewRepository(incrementalApi, database.review(), ReviewJournal(database.review(), incrementalCodecs), incrementalCodecs)
    private val freshPractice = FreshPracticeRepository(incrementalApi, incrementalCodecs, database, source6Listening, reviewRepository)
    private var pendingFreshPractice: Pair<UUID, UUID>? = null
    private val resumes = ResumeRepository(api, database)
    private val aiApi = AiLearningApi(context, BuildConfig.LEARNING_API_ORIGIN, profiles, BuildConfig.DEBUG)
    private val preferences = LearningPreferenceRepository(aiApi, database) { profiles.current()?.owner == it }
    private val materials = LearningMaterialRepository(aiApi, database) { profiles.current()?.owner == it }
    private val recommendations = LearningRecommendationRepository(aiApi, database, preferences) { profiles.current()?.owner == it }
    private val speaking = SpeakingRecordsRepository(LearningProgressApi(context, BuildConfig.LEARNING_API_ORIGIN, profiles, BuildConfig.DEBUG), database) { profiles.current()?.owner == it }
    private val speaking6 = Source6SpeakingRecordsRepository(incrementalApi, incrementalCodecs, database, speaking)
    private val daily = DailyListeningPreferenceRepository(LearningProgressApi(context, BuildConfig.LEARNING_API_ORIGIN, profiles, BuildConfig.DEBUG), database)
    private val syncWorker = ProfileSyncWorker(FeatureLifecycle(database))
    private val learningDatabase = database
    private val accounts = AccountRepository(context, database, profiles, BuildConfig.LEARNING_API_ORIGIN, BuildConfig.DEBUG)
    private val mediaJournal = MediaPlaybackJournal(context, database)
    private val listening = ListeningRepository(context, api, database,
        isCurrent = { profiles.current()?.owner == it }, transition = ListeningTransitionPort { owner -> freeze(owner) })
    private val legacyReviewNext = LegacyReviewNextAdapter(context, catalog, listening, { profiles.current()?.owner == it },
        OriginalLegacyCheckpointPort { call, id ->
            listening.snapshot()?.takeIf { it.owner == call.owner }?.checkpoint?.takeIf { it.recordId == id.toString() }
                ?: presentation.value.history?.records?.filterIsInstance<WirePersonalReadCheckpoint>()?.singleOrNull { it.recordId == id.toString() }
                ?: (resumes.snapshot(call) as? LearningReply.Stored)?.value?.records?.filterIsInstance<WirePersonalReadCheckpoint>()?.singleOrNull { it.recordId == id.toString() }
        })
    private val audio = AudioAccessRepository(catalog, api, profiles)
    private val playback = NewsPlaybackRepository(profiles, listening, catalog, audio, mediaJournal,
        outputAllowed = coordinator::mediaPermission, onEnded = ::advanceAfterEnd)
    private val presentation = MutableStateFlow(ProductPresentation(themeDraft = appearances.state.value))
    private var controller: MediaBrowser? = null
    private var connectingMedia = false
    private var started = false
    private var window = WindowState()
    private var projectedWindow = WindowState()
    private var windowToken: UUID? = null
    private var productVisible = false
    private var vehicleSnapshot = VehicleSnapshot()
    private var pendingSelection: PendingNewsSelection? = null
    private var pendingFormat: PendingFormatChange? = null
    private var pendingPhase: Pair<NewsPlaybackKey, UUID>? = null

    private val preparedRuntime = PreparedProductRuntime(context, scope, profiles, database, incrementalApi, preparedCatalog,
        coordinator, voices, visible = { productVisible && window.visible }, phaseEnded = { preparedPhaseEnded(it) })
    private var pendingPreparedFormat: WireSource6PersonalFormatChangeRequest? = null
    private var pendingPreparedPhase: SegmentRun? = null
    private var pendingPreparedSnapshot: SegmentPlaybackSnapshot? = null
    val segmentMediaPort: SegmentMediaControlPort = PreparedMediaPort(coordinator, scope, preparedRuntime) { presentation.value.preparedSource?.title ?: "현재 기사" }
    private var pendingPreparedSelection: WireSource6PersonalNextArticleRequest? = null
    private val learning = ProductLearningController(context, scope, coordinator, profiles, api, database, catalog,
        freeze = { owner -> freezeAndSave(owner) }, restoreQuestion = ::restoreQuestion, accessChanged = { updateAccess() }, voices = voices)
    private val auxiliary: AuxiliaryPlayback = AuxiliaryPlayback(preparedRuntime.speech, preparedRuntime.leases, scope,
        originals = AuxiliaryOriginalPort { owner, original ->
            if (profiles.current()?.owner != owner) false else if (original == null)
                coordinator.state.value.listening == null && coordinator.state.value.preparedListening == null && preparedRuntime.speech.quiescent
            else freezeAndSave(owner)
        }, access = { coordinator.state.value.access }, currentOwner = { coordinator.state.value.owner },
        authorized = { reviewTools.auxiliaryCurrent(it) })
    private val reviewTools: ReviewToolsController = ReviewToolsController(coordinator, profiles, incrementalApi, incrementalCodecs, reviewRepository, auxiliary,
        object : ReviewToolsPorts {
            override suspend fun source(scope: LearningCallScope): ReviewToolsSource? {
                val work = coordinator.state.value.preparedListening ?: return null
                val script = presentation.value.preparedScript ?: return null
                val manifest = script.manifest ?: return null
                val source = presentation.value.preparedSource ?: return null
                if (scope.owner != coordinator.state.value.owner || preparedCatalog.main(scope, work.content.source6Wire()) !is CatalogRead.Stored) return null
                val parsed = SegmentManifest(UUID.fromString(manifest.manifestId), UUID.fromString(manifest.scriptId), manifest.scriptHash, manifest.segmentsVersion.toInt(), manifest.manifestHash,
                    SegmentLanguage.valueOf(manifest.language.uppercase()), manifest.segments.map { SpeechSegment(UUID.fromString(it.segmentId), it.order.toInt(), UUID.fromString(it.sentenceId), it.startCp.toInt(), it.endCp.toInt(), it.textHash) })
                return ReviewToolsSource(scope.owner, work.selectionId, work.content.source6Wire(), if (work.stage == ListeningStage.FOLLOWUP) SegmentPhase.FOLLOWUP else SegmentPhase.MAIN,
                    script.text, parsed, source.rightsRevision, true, UUID.nameUUIDFromBytes("${scope.owner.presentationToken}:${work.selectionId}:${script.textHash}".toByteArray()))
            }
            override suspend fun sameSource(scope: LearningCallScope, source: ReviewToolsSource): Boolean {
                val current = source(scope) ?: return false
                return current.owner == source.owner && current.selectionId == source.selectionId && current.content == source.content && current.segments == source.segments && current.contextToken == source.contextToken
            }
            override suspend fun beforeOpen(scope: LearningCallScope, source: ReviewToolsSource?): ReviewToolsOpenSnapshot? {
                val view = coordinator.snapshot()
                if (view.task != null || view.captureLease != null) return null
                if (source == null && view.preparedListening == null && view.listening == null) return ReviewToolsOpenSnapshot(null, presentation.value.profile?.resumeVersion ?: 0, preparedRuntime.speech.quiescent)
                val work = view.preparedListening ?: return null
                if (!freezeAndSave(scope.owner)) return null
                val snapshot = (source6Listening.snapshot(scope) as? LearningReply.Stored)?.value ?: return null
                val checkpoint = snapshot.records.filterIsInstance<WireSource6PersonalReadCheckpoint>().singleOrNull { it.recordId == work.checkpointId.toString() } ?: return null
                val cursor = checkpoint.payload.cursor as? WireSource6SegmentCursor6 ?: return null
                val original = ReviewReturnSnapshot(ProfileContext(scope.owner.profileId, scope.owner.privacyEpoch), work.selectionId, work.checkpointId, checkpoint.version, null,
                    SegmentPhase.valueOf(cursor.phase.uppercase()), cursor.toSegmentCursor(), view.intent == PlaybackIntent.PLAY, PauseReason.valueOf(cursor.pauseReason.uppercase()), snapshot.profile.resumeVersion)
                return ReviewToolsOpenSnapshot(original, snapshot.profile.resumeVersion, true)
            }
            override suspend fun persistOfferCancellation(scope: LearningCallScope, operationId: UUID, context: ReviewContext, original: ReviewReturnSnapshot): Boolean {
                val json = kotlinx.serialization.json.buildJsonObject {
                    put("selection_id", kotlinx.serialization.json.JsonPrimitive(context.selectionId.toString())); put("offer_id", kotlinx.serialization.json.JsonPrimitive(context.offerId.toString()))
                    put("expected_context_version", kotlinx.serialization.json.JsonPrimitive(context.version)); put("checkpoint_id", kotlinx.serialization.json.JsonPrimitive(original.checkpointId.toString()))
                }.toString()
                reviewDao.queue(scope.owner, LocalReviewOperation(operationId.toString(), scope.owner.profileId.toString(), scope.owner.privacyEpoch, scope.owner.revision,
                    "local_offer_cancel", context.selectionId.toString(), json, segmentSha256(json)))
                return true
            }
            override suspend fun draft(scope: LearningCallScope): ReviewToolsPreviewDraft? {
                val data = presentation.value; val draft = data.draft ?: return null
                return ReviewToolsPreviewDraft(data.draftRevision, data.draftToken, SettingsPreviewChoice(ListeningMode.valueOf(draft.listeningMode.uppercase()),
                    Followup.valueOf((draft.followup ?: "none").uppercase()), data.bandDraft ?: preferences.state.value.band ?: LearningBand.BASIC))
            }
            override suspend fun approvedDemo(scope: LearningCallScope): SettingsDemoManifest? {
                if (profiles.current()?.owner != scope.owner) return null
                val demo = settingsDemoLoader.load() ?: return null
                return demo.takeIf { profiles.current()?.owner == scope.owner }
            }
        })
    val gleoRuntime get() = learning.gleoRuntime
    suspend fun microphonePermissionResult() = learning.permissionResult()

    private data class Features(val study: LearningPresentation, val records: SpeakingRecordsState,
        val preference: LearningPreferenceState, val material: LearningMaterialState, val recommendation: LearningRecommendationState)
    private val features = combine(learning.state, speaking.state, preferences.state, materials.state, recommendations.state) { study, records, preference, material, recommendation ->
        Features(study, records, preference, material, recommendation)
    }
    private val allFeatures = combine(features, preparedRuntime.state, speaking6.state, reviewTools.state, assessmentReviews.state) { feature, segment, records, tools, assessments -> ProductFeatures(feature, segment, records, tools, assessments) }
    private data class ProductFeatures(val first: Features, val second: SegmentRuntimeState, val third: Source6SpeakingRecordsState, val tools: ReviewToolsState, val assessments: AssessmentReviewState)
    private val serviceStates = combine(catalogViews.state, reviewNext2.state, reviewNext3.state) { catalog, playback, pending -> Triple(catalog, playback, pending) }
    val state: StateFlow<ProductScreenState> = combine(coordinator.state, presentation, appearances.state, allFeatures, serviceStates) { session, data, theme, joined, services ->
        val discovered = services.first
        val feature = joined.first
        val study = feature.study; val records = feature.records
        val visible = if (data.owner == session.owner) data else ProductPresentation(themeDraft = theme)
        ProductScreenState(session = session, profileLabel = when (visible.profile?.profileKind) {
            "anonymous" -> "이 차량의 학습"; "linked" -> "내 학습"; else -> "기록 연결 확인 중"
        }, catalog = visible.catalog, catalogStatus = visible.catalogStatus, moreCatalog = visible.more,
            selectedArticle = visible.article, reader = if (session.access.detailsOpen) visible.reader else null,
            readerStatus = visible.readerStatus, draft = visible.draft, appearance = theme, appearanceDraft = visible.themeDraft,
            busy = visible.accountEntry.busy || session.activeOperations.any { it in setOf(SessionOperationKind.PROFILE, SessionOperationKind.SELECTION, SessionOperationKind.SETTINGS) },
            notice = study.takeIf { it.owner == session.owner }?.notice ?: visible.notice, recovery = visible.recovery ?: restriction(session), noCandidateReason = visible.noCandidate,
            settingsPending = visible.settingsPending, selectionPending = visible.selectionPending, formatPendingMode = visible.formatPendingMode, learning = if (study.owner == session.owner) study else LearningPresentation(), history = visible.history, historyStatus = visible.historyStatus, deletionPending = visible.deletionPending, dailyListening = visible.daily, listenOnlyActive = visible.listenOnly, speakingRecords = if (records.owner == session.owner) records else SpeakingRecordsState(),
            learningPreference = if (feature.preference.owner == session.owner) feature.preference else LearningPreferenceState(),
            learningMaterial = if (feature.material.owner == session.owner) feature.material else LearningMaterialState(),
            learningRecommendation = if (feature.recommendation.owner == session.owner) feature.recommendation else LearningRecommendationState(), bandDraft = visible.bandDraft, preparedCatalog = visible.preparedCatalog, categoryConfig = visible.categoryConfig,
            preparedSource = visible.preparedSource, preparedScript = if (session.access.detailsOpen) visible.preparedScript else null, segmentPlayback = joined.second, followupWaiting = visible.followupWaiting, source6History = visible.source6History, source6SpeakingRecords = if (joined.third.owner == session.owner) joined.third else Source6SpeakingRecordsState(), reviewTools = if (joined.tools.owner == session.owner) joined.tools else ReviewToolsState(), assessments = if (joined.assessments.owner == session.owner) joined.assessments else AssessmentReviewState(),
            catalogViews = if (discovered.owner == session.owner) discovered else CatalogViewState(), identityRecovery = visible.identityRecovery, playbackRequest = if (services.second.owner == session.owner) services.second else ReviewNext2State(),
            playbackRequest3 = if (services.third.owner == session.owner) services.third else ReviewNext3State(),
            originalOutputConfigured = originalOutputConfigured(), accountEntry = visible.accountEntry.copy(
                linked = visible.profile?.profileKind == "linked", hasSession = session.ownerBinding == OwnerBindingStatus.VERIFIED,
                canLaunch = accountAccess(session) && !visible.accountEntry.canRetryTransition))
    }.stateIn(scope, SharingStarted.Eagerly, ProductScreenState())

    val mediaPort: NewsPlaybackPort = object : NewsPlaybackPort {
        override suspend fun current(): NewsPlaybackResource? {
            val previous = coordinator.state.value.listening?.runId
            val result = playback.current() ?: return null
            return if (coordinator.bindMediaRun(result.key, previous)) result else null
        }
        override suspend fun renew(key: NewsPlaybackKey, positionMs: Long) = playback.renew(key, positionMs)
        override suspend fun streamAccess(key: NewsPlaybackKey) = playback.streamAccess(key)
        override suspend fun mayPlay(key: NewsPlaybackKey) = playback.mayPlay(key)
        override suspend fun persist(position: NewsPlaybackPosition, interval: PlayedInterval?) {
            playback.persist(position, interval)
            coordinator.observeMedia(position.key, position.positionMs, position.playIntent, position.playing, position.pauseReason)
        }
        override suspend fun ended(key: NewsPlaybackKey) = playback.ended(key)
        override suspend fun followupReady(endedKey: NewsPlaybackKey): Boolean {
            val view = coordinator.snapshot(); val work = view.listening ?: return false
            return view.owner == endedKey.owner && work.selectionId == endedKey.selectionId &&
                work.checkpointId == endedKey.checkpointId && work.content == endedKey.content &&
                work.stage == ListeningStage.FOLLOWUP && view.intent == PlaybackIntent.PLAY
        }
    }

    fun start() {
        if (started) return
        started = true
        learning.start()
        preparedRuntime.start()
        preparedRuntime.auxiliaryPermission = { owner, _ -> auxiliary.state.value.binding?.let { it.owner == owner && reviewTools.auxiliaryCurrent(it) } == true }
        preparedRuntime.auxiliaryRunPermission = { run -> auxiliary.segmentState.value.snapshot?.let { it.activeRequest == run } == true &&
            auxiliary.state.value.binding?.let { reviewTools.auxiliaryCurrent(it) } == true }
        scope.launch { coordinator.state.collect { reviewTools.recheck() } }
        scope.launch { coordinator.state.map { it.access.detailsOpen }.distinctUntilChanged().collect { allowed ->
            val current = presentation.value
            val dirty = current.draft?.dirty == true || current.bandDraft?.let { it != preferences.state.value.band } == true || current.themeDraft != appearances.state.value
            if (!allowed && dirty) draftLane.withLock { persistSettingsDraft(OwnerDraftState.PRESERVED, OwnerDraftLeaveState.FORCED_CLOSED) }
        } }
        scope.launch { coordinator.state.collect {
            pendingReview2Selection?.let { pending ->
                if (!nextGuard(pending).outputAllowed || pending.navigationGeneration != it.navigationGeneration || pending.owner != it.owner) revokePendingActivation()
            }
            pendingReview3Selection?.let { pending ->
                if (!nextGuard(pending).outputAllowed || pending.navigationGeneration != it.navigationGeneration || pending.owner != it.owner) revokePendingActivation()
            }
        } }
        learning.preparedPracticeMaterial = { ref ->
            val call = profiles.current()
            if (call == null) null else (preparedCatalog.readPreparedPractice(call, ref) as? CatalogRead.Stored)?.value?.let { Triple(it.ref, it.questionEn, it.manifest) }
        }
        learning.resolvePreparedTitle = { title, current ->
            val candidates = presentation.value.preparedCatalog.filter { it.title.trim() == title.trim() }
            if (candidates.singleOrNull()?.articleId == current.articleId.toString()) current else null
        }
        learning.beforePracticeEntry = ::enterPracticeModal
        learning.afterPracticeClose = ::closePracticeModal
        learning.playbackHandler = ::gleoPlayback
        learning.pendingChoiceContext = ::pendingChoiceContextOperation
        learning.learningPreferenceHandler = ::gleoLearningPreference
        learning.learningPreferenceRecommendationBasis = { owner ->
            profiles.current()?.takeIf { it.owner == owner }?.let { call ->
                if (preferences.refresh(call) is LearningReply.Stored && recommendations.refresh(call) is LearningReply.Stored)
                    currentVoiceRecommendation(owner) else null
            }
        }
        learning.currentAssessment = { owner, lesson, attempt ->
            val call = profiles.current()?.takeIf { it.owner == owner && coordinator.state.value.lessonId == lesson }
            if (call == null) null else (assessmentReviews.read(call, lesson, attempt) as? LearningReply.Stored)?.value?.feedbackKo
        }
        learning.assessmentOriginalAllowed = { owner, attempt ->
            val lesson = learning.state.value.takeIf { it.owner == owner }?.practice?.lesson?.lessonId
            val call = profiles.current()?.takeIf { it.owner == owner }
            val result = if (call != null && lesson != null) (assessmentReviews.read(call, lesson, attempt) as? LearningReply.Stored)?.value else null
            result?.displayReady == true && result.effective?.get("original_feedback_hash") == result.effective?.get("effective_feedback_hash")
        }
        learning.assessmentHandler = { command ->
            val study = learning.state.value
            val lesson = study.practice?.lesson?.lessonId
            val attempt = study.lastAttemptId
            if (study.owner != coordinator.state.value.owner || lesson == null || attempt == null) "확인할 답변 기록을 먼저 열어 주세요"
            else {
                if (command == "review_assessment") requestAssessment(lesson, attempt, "gleo", false) else readAssessment(lesson, attempt)
                val result = assessmentReviews.state.value.entries[attempt]?.takeIf { it.owner == study.owner && it.lessonId == lesson }
                when {
                    result?.displayReady == true -> result.feedbackKo ?: "판정 기록을 확인해 주세요"
                    result?.pending == true -> "판정을 다시 확인하고 있어요. 원래 답변은 보존돼요"
                    result?.evaluationStatus == "withheld" -> "판정을 보류했어요. 원래 답변과 기록은 보존돼요"
                    else -> "판정 연결을 다시 확인해 주세요. 원래 답변은 보존돼요"
                }
            }
        }
        learning.listeningOnly = { presentation.value.listenOnly }
        learning.materialPracticeAllowed = { content ->
            val frozen = materials.state.value.frozen
            frozen == null || frozen.content != content.content || frozen.canPractice && frozen.practiceRef == content
        }
        scope.launch {
            vehicle.start()
            launch { vehicle.state.collect { vehicleSnapshot = it; updateAccess() } }
            launch {
                while (isActive) {
                    delay(1_000)
                    val value = presentation.value
                    val effective = value.daily.listenOnly(daily.trustedNow())
                    if (value.listenOnly != effective) {
                        presentation.update { it.copy(listenOnly = effective) }
                        if ((coordinator.state.value.preparedListening?.stage ?: coordinator.state.value.listening?.stage) == ListeningStage.ARTICLE_OFFER) learning.announceArticleOffer()
                    }
                }
            }
            launch { coordinator.state.map { Triple(it.owner, it.screen, it.access.detailsOpen) }.distinctUntilChanged().collect { (_, screen, allowed) ->
                if (allowed && screen in setOf(SessionScreen.HISTORY, SessionScreen.SAVED)) loadHistory()
            } }
            launch { coordinator.state.map { Triple(it.owner, it.screen, it.access.detailsOpen) }.distinctUntilChanged().collectLatest { (owner, screen, allowed) ->
                if (allowed && screen in setOf(SessionScreen.HISTORY, SessionScreen.SAVED)) {
                    while (isActive) {
                        profiles.current()?.takeIf { it.owner == owner }?.let { if (presentation.value.preparedCatalogAvailable) speaking6.refresh(it) else speaking.refresh(it) }
                        delay(30_000)
                    }
                }
            } }
            connectMedia()
            connectProfile()
            launch { while (isActive) { delay(30_000); reconcileStoredWork() } }
        }
    }

    suspend fun attachWindow(token: UUID) {
        windowToken = token
        window = WindowState()
        projectedWindow = WindowState(generation = projectedWindow.generation + 1)
        updateAccess()
    }

    fun surfaceVisible(token: UUID, visible: Boolean) { scope.launch {
        if (windowToken == token) { productVisible = visible; updateAccess() }
    } }

    suspend fun updateWindow(token: UUID, value: WindowState) {
        if (windowToken != token) return
        if (value.generation < window.generation) return
        window = value
        updateAccess()
    }

    private suspend fun updateAccess() {
        if (accountJob?.isActive == true && !accountAccess(coordinator.state.value.copy(access = coordinator.state.value.access.copy(
                safety = vehicleSnapshot.safety, power = vehicleSnapshot.effectivePower, window = window.copy(visible = window.visible && productVisible))), requireFocus = false)) cancelAccountEntry()
        val ownerValid = coordinator.state.value.ownerBinding == OwnerBindingStatus.VERIFIED &&
            coordinator.state.value.owner?.let { profiles.current()?.owner == it } == true
        val previous = coordinator.state.value.access
        val visible = window.visible && productVisible
        val focused = window.focused && visible
        if (visible != projectedWindow.visible || focused != projectedWindow.focused)
            projectedWindow = WindowState(visible, focused, projectedWindow.generation + 1)
        coordinator.updateAccess(previous.copy(safety = vehicleSnapshot.safety, window = projectedWindow,
            power = vehicleSnapshot.effectivePower, ownerValid = ownerValid,
            microphoneGranted = learning.microphoneGranted, directProviderVerified = learning.speechState.approved,
            inputArbitrationVerified = learning.speechState.approved, audioAllowsReply = learning.replyAllowed))
    }

    private fun connectMedia() {
        if (connectingMedia || controller != null) return
        connectingMedia = true
        val future = MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, NewsMediaService::class.java)))
            .setListener(object : MediaBrowser.Listener {
                override fun onDisconnected(value: MediaController) {
                    if (controller === value) { controller = null; scope.launch { coordinator.setMediaConnected(false) } }
                }
            }).buildAsync()
        future.addListener({
            connectingMedia = false
            try {
                controller = future.get()
                scope.launch { coordinator.setMediaConnected(true) }
            } catch (_: Exception) { scope.launch { coordinator.setMediaConnected(false) } }
        }, context.mainExecutor)
    }

    private suspend fun <T> await(future: ListenableFuture<T>): T = suspendCancellableCoroutine { continuation ->
        future.addListener({
            if (continuation.isActive) try { continuation.resume(future.get()) }
            catch (error: Exception) { continuation.resumeWithException(error) }
        }, context.mainExecutor)
        continuation.invokeOnCancellation { future.cancel(false) }
    }

    private suspend fun freeze(owner: OwnerContext): Boolean {
        if (profiles.current()?.owner != owner) return false
        if (coordinator.state.value.preparedListening != null) return preparedRuntime.hold(PauseReason.USER, preservePlayIntent = true)
        return withContext(Dispatchers.Main.immediate) {
            val media = controller ?: return@withContext false
            withTimeoutOrNull(3_000) {
                await(media.sendCustomCommand(SessionCommand(NewsMediaService.FREEZE, Bundle.EMPTY), Bundle.EMPTY)).resultCode == SessionResult.RESULT_SUCCESS
            } == true
        }
    }

    private var accountActivity = java.lang.ref.WeakReference<android.app.Activity>(null)
    private var accountJob: Job? = null
    private var accountGeneration = 0L
    private val accountCredentials = io.github.driveenglishlab.pleos.accountcredential.GoogleAccountCredentialAdapter(context)
    fun bindAccountActivity(activity: android.app.Activity) { accountActivity = java.lang.ref.WeakReference(activity) }
    fun unbindAccountActivity(activity: android.app.Activity) {
        if (accountActivity.get() === activity) { cancelAccountEntry(); accountActivity.clear() }
    }
    private fun accountAccess(view: SessionView, requireFocus: Boolean = true) = accountEntryAllowed(view, requireFocus)
    private fun cancelAccountEntry() {
        accountGeneration++
        accountJob?.cancel(); accountJob = null
        presentation.update { it.copy(accountEntry = AccountEntryState(AccountEntryPhase.CANCELLED, message = "계정 연결을 멈췄어요. 기존 기록은 유지돼요", canRetryTransition = it.accountEntry.canRetryTransition)) }
    }
    private fun accountMessage(code: String) = when (code) {
        "GOOGLE_PROVIDER_DISABLED" -> "로그인 제공자가 아직 활성화되지 않았어요"
        "GOOGLE_WEB_CLIENT_ID_MISSING", "GOOGLE_WEB_CLIENT_ID_INVALID", "AUTH_PROJECT_NOT_CONFIGURED" -> "차량의 계정 연결 설정을 먼저 확인해 주세요"
        "AUTH_SETTINGS_UNAVAILABLE" -> "로그인 제공자 상태를 확인하지 못했어요. 연결을 다시 확인해 주세요"
        "GOOGLE_CREDENTIAL_CLIENT_UNAVAILABLE", "GOOGLE_CREDENTIAL_CLIENT_UNSUPPORTED", "GOOGLE_CREDENTIAL_PROVIDER_UNAVAILABLE" -> "이 차량에서 Google 계정 선택을 사용할 수 없어요"
        else -> "계정 연결을 확인하지 못했어요. 기존 기록을 유지한 채 다시 시도할 수 있어요"
    }
    private fun startAccountEntry(action: AccountEntryAction) {
        if (action == AccountEntryAction.CANCEL) { cancelAccountEntry(); return }
        if (accountJob?.isActive == true || !accountAccess(coordinator.state.value)) return
        val original = coordinator.state.value
        val originalOwner = original.owner
        val generation = ++accountGeneration
        fun current() = generation == accountGeneration && coordinator.state.value.owner == originalOwner &&
            coordinator.state.value.navigationGeneration == original.navigationGeneration && accountAccess(coordinator.state.value)
        accountJob = scope.launch {
            fun phase(value: AccountEntryPhase, message: String? = null) {
                if (generation == accountGeneration) presentation.update { it.copy(accountEntry = it.accountEntry.copy(phase = value, action = action, message = message)) }
            }
            var preparedTarget: UUID? = null
            try {
                if (action == AccountEntryAction.RETRY_TRANSITION) {
                    phase(AccountEntryPhase.RECOVERING)
                    if (originalOwner != null) {
                        if ((original.listening != null || original.preparedListening != null) && !freezeAndSave(originalOwner)) {
                            phase(AccountEntryPhase.FAILED, "듣던 위치를 저장한 뒤 다시 확인해 주세요"); return@launch
                        }
                        if (!current()) return@launch
                        coordinator.invalidateMediaActivation(originalOwner)
                        coordinator.suspendForReviewTools(originalOwner)
                    }
                    learning.cancelEffects(); controller?.pause()
                    val result = accounts.recoverLinkedTarget(::current) ?: accounts.recoverMerge()
                    if (generation != accountGeneration) return@launch
                    if (result == null) phase(AccountEntryPhase.FAILED, "저장된 계정 전환이 없어요")
                    else if (result is LearningReply.Failed) phase(AccountEntryPhase.FAILED, accountMessage(result.code))
                    else { phase(AccountEntryPhase.COMPLETE, "저장된 계정 전환을 확인했어요"); connectProfile() }
                    return@launch
                }
                if (accounts.pendingLinkedTarget() != null) {
                    phase(AccountEntryPhase.FAILED, "저장된 계정 전환을 먼저 다시 확인해 주세요"); return@launch
                }
                phase(AccountEntryPhase.CHECKING_PROVIDER)
                val ready = accountCredentials.readiness()
                if (!current()) return@launch
                if (ready is io.github.driveenglishlab.pleos.accountcredential.GoogleCredentialReadiness.Unavailable) {
                    phase(AccountEntryPhase.UNAVAILABLE, accountMessage(ready.code)); return@launch
                }
                val call = profiles.current()
                if (action in setOf(AccountEntryAction.NEW_LINK, AccountEntryAction.EXISTING_MERGE) &&
                    (call == null || presentation.value.profile?.profileKind != "anonymous")) {
                    phase(AccountEntryPhase.FAILED, "현재 미로그인 학습 연결을 먼저 확인해 주세요"); return@launch
                }
                if (action == AccountEntryAction.RESTORE_LINKED && call != null) { phase(AccountEntryPhase.FAILED, "현재 계정에서 연결 또는 전환을 선택해 주세요"); return@launch }
                if (action == AccountEntryAction.SWITCH_LINKED && (call == null || presentation.value.profile?.profileKind != "linked")) return@launch
                if (originalOwner != null) {
                    if ((original.listening != null || original.preparedListening != null) && !freezeAndSave(originalOwner)) {
                        phase(AccountEntryPhase.FAILED, "듣던 위치를 저장한 뒤 다시 연결해 주세요"); return@launch
                    }
                    if (!current()) return@launch
                    cancelPendingSelection()
                    coordinator.invalidateMediaActivation(originalOwner)
                    coordinator.suspendForReviewTools(originalOwner)
                }
                learning.cancelEffects(); controller?.pause()
                phase(AccountEntryPhase.PREPARING)
                val target = withContext(NonCancellable) {
                    val prepared = when (action) {
                        AccountEntryAction.RESTORE_LINKED -> accounts.prepareLinkedTarget(AccountTargetPurpose.RESTORE_LINKED)
                        AccountEntryAction.SWITCH_LINKED -> accounts.prepareLinkedTarget(AccountTargetPurpose.SWITCH_LINKED)
                        else -> null
                    }
                    preparedTarget = (prepared as? AccountTargetPreparation.Ready)?.attempt?.operationId
                    prepared
                }
                if (target is AccountTargetPreparation.Failed) { phase(AccountEntryPhase.FAILED, accountMessage(target.code)); return@launch }
                if (action == AccountEntryAction.EXISTING_MERGE) {
                    val prepared = accounts.prepareExistingAccount()
                    if (prepared is LearningReply.Failed) { phase(AccountEntryPhase.FAILED, accountMessage(prepared.code)); return@launch }
                }
                if (!current()) return@launch
                val activity = accountActivity.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: run {
                    phase(AccountEntryPhase.UNAVAILABLE, "보이는 차량 화면에서 다시 연결해 주세요"); return@launch
                }
                phase(AccountEntryPhase.AUTHENTICATING)
                when (val credential = accountCredentials.request(activity)) {
                    is io.github.driveenglishlab.pleos.accountcredential.GoogleCredentialResult.Credential -> {
                        if (!current()) return@launch
                        phase(AccountEntryPhase.VERIFYING)
                        cancelPendingSelection(); learning.cancelEffects(); controller?.pause()
                        val result = when (action) {
                            AccountEntryAction.NEW_LINK -> accounts.linkNewGoogle(credential.idToken, credential.nonce, ::current)
                            AccountEntryAction.EXISTING_MERGE -> accounts.signInExistingGoogle(credential.idToken, credential.nonce, ::current)
                            else -> accounts.signInLinkedTargetGoogle(requireNotNull((target as? AccountTargetPreparation.Ready)?.attempt).operationId, credential.idToken, credential.nonce, ::current)
                        }
                        if (generation != accountGeneration) return@launch
                        if (result is LearningReply.Failed) phase(AccountEntryPhase.FAILED, accountMessage(result.code))
                        else { phase(AccountEntryPhase.COMPLETE, "서버에서 계정 기록을 확인했어요. 학습은 직접 이어서 시작해 주세요"); connectProfile() }
                    }
                    is io.github.driveenglishlab.pleos.accountcredential.GoogleCredentialResult.Cancelled -> phase(AccountEntryPhase.CANCELLED, "계정 선택을 취소했어요. 기존 기록은 유지돼요")
                    is io.github.driveenglishlab.pleos.accountcredential.GoogleCredentialResult.Unavailable -> phase(AccountEntryPhase.UNAVAILABLE, accountMessage(credential.code))
                    is io.github.driveenglishlab.pleos.accountcredential.GoogleCredentialResult.Failed -> phase(AccountEntryPhase.FAILED, accountMessage(credential.code))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { phase(AccountEntryPhase.FAILED, accountMessage("ACCOUNT_FAILED")) }
            finally {
                withContext(NonCancellable) {
                    preparedTarget?.let { id -> accounts.cancelLinkedTarget(id) }
                    val pending = accounts.pendingLinkedTarget() != null
                    if (generation == accountGeneration || presentation.value.accountEntry.phase == AccountEntryPhase.CANCELLED)
                        presentation.update { it.copy(accountEntry = it.accountEntry.copy(canRetryTransition = pending)) }
                }
                if (generation == accountGeneration && presentation.value.accountEntry.busy)
                    phase(AccountEntryPhase.CANCELLED, "계정 연결을 멈췄어요. 보이는 주차 화면에서 다시 시도해 주세요")
            }
        }
    }

    suspend fun handle(intent: UiIntent) {
        val view = coordinator.snapshot()
        if (intent.origin.owner != view.owner) return
        val sameWork = (intent.origin.work == null && view.listening == null || intent.origin.work?.sameWork(view.listening) == true) &&
            (intent.origin.preparedWork == null && view.preparedListening == null || intent.origin.preparedWork?.sameWork(view.preparedListening) == true)
        val currentPresentation = intent.origin.navigationGeneration == view.navigationGeneration && sameWork && intent.origin.task == view.task && intent.origin.lessonId == view.lessonId
        if (!currentPresentation && intent !is UiIntent.Stop && !(intent is UiIntent.PlayPause && !intent.wantPlay)) return
        if (accountJob?.isActive == true && intent !is UiIntent.AccountEntry && intent !is UiIntent.Stop &&
            intent !is UiIntent.Navigate && !(intent is UiIntent.PlayPause && !intent.wantPlay)) return
        try {
            if (intent is UiIntent.AccountEntry) { startAccountEntry(intent.action); return }
            if (intent is UiIntent.Navigate || intent is UiIntent.Logout || intent is UiIntent.DeleteHistory || intent is UiIntent.Stop) {
                if (accountJob?.isActive == true) cancelAccountEntry()
            }
            if (intent is UiIntent.Stop || intent is UiIntent.PlayPause && !intent.wantPlay ||
                intent is UiIntent.Navigate && intent.screen != view.screen || intent is UiIntent.OpenArticleQuestion ||
                intent is UiIntent.StartPractice || intent is UiIntent.Logout || intent is UiIntent.DeleteHistory) cancelPendingSelection()
            if (learning.handle(intent)) return
            when (intent) {
                is UiIntent.ReadAssessment -> if (view.access.detailsOpen) readAssessment(intent.lessonId, intent.attemptId)
                is UiIntent.RequestAssessmentReview -> if (view.access.detailsOpen) requestAssessment(intent.lessonId, intent.attemptId, intent.entry, false)
                is UiIntent.RetryAssessmentReview -> if (view.access.detailsOpen) requestAssessment(intent.lessonId, intent.attemptId, intent.entry, true)
                is UiIntent.CatalogViewChanged -> if (view.access.detailsOpen) readCatalogView(intent.view, catalogViews.state.value.categoryFilter)
                is UiIntent.CatalogFilterChanged -> if (view.access.detailsOpen) readCatalogView(catalogViews.state.value.view, intent.category)
                is UiIntent.MoreCatalogView -> if (view.access.detailsOpen) profiles.current()?.let { catalogViews.more(it) }
                is UiIntent.SelectCatalogArticle -> if (view.access.detailsOpen) {
                    val visibleCatalog = catalogViews.state.value
                    val item = visibleCatalog.items.firstOrNull { it.key == intent.key }
                    if (item != null && visibleCatalog.owner == view.owner && visibleCatalog.view == intent.originView &&
                        visibleCatalog.availability == CatalogAvailability.READY) {
                        selectPreparedArticle(intent.key.articleId, false, false, false, intent.key.sourceSnapshotId, intent.originView)
                    }
                }
                is UiIntent.Band -> if (view.access.detailsOpen) changeSettingsDraft { it.copy(bandDraft = intent.value, draftRevision = it.draftRevision + 1) }
                is UiIntent.ReadRecommendation -> if (view.access.detailsOpen) profiles.current()?.let { recommendations.refresh(it) }
                is UiIntent.Recommendation -> if (view.access.detailsOpen) profiles.current()?.let {
                    val result = recommendations.act(it, intent.action, intent.explicitReview)
                    if (result is LearningReply.Stored && intent.action == RecommendationAction.APPLY) {
                        if (presentation.value.draftStorageRevision == null) presentation.value.profile?.let { profile ->
                            draftLane.withLock { restoreSettingsDraft(profile) }
                        }
                        changeSettingsDraft { data -> data.copy(bandDraft = preferences.state.value.band,
                            notice = "다음 기사에 적용할 단계를 바꿨어요. 지금 듣던 기사와 다른 설정 초안은 유지돼요") }
                    }
                }
                is UiIntent.ListeningOnly -> if (view.access.detailsOpen) chooseListeningOnly(intent.enabled)
                is UiIntent.ShowOriginalFeedback -> if (view.access.detailsOpen) {
                    if (presentation.value.preparedCatalogAvailable) {
                        val call = profiles.current() ?: return
                        speaking6.originalFeedback(call, intent.attemptId)
                        return
                    }
                    val detail = speaking.state.value.detail?.record?.takeIf { it.lessonId == intent.lessonId.toString() && it.sourceAccess.originalFeedback == "available" } ?: return
                    val anchors = listOfNotNull(detail.firstAttempt?.feedbackAttemptId, detail.retryAttempt?.feedbackAttemptId)
                    if (intent.attemptId.toString() !in anchors) return
                    val snapshot = presentation.value.history ?: return
                    val lesson = snapshot.records.filterIsInstance<WirePersonalReadLesson>().singleOrNull { it.recordId == intent.lessonId.toString() } ?: return
                    learning.restorePractice(lesson.payload, snapshot, selectPointer = false, reviewedAttempt = intent.attemptId)
                }
                is UiIntent.ShowSpeakingRecord -> if (view.access.detailsOpen) profiles.current()?.let { if (presentation.value.preparedCatalogAvailable) speaking6.detail(it, intent.lessonId) else speaking.detail(it, intent.lessonId) }
                is UiIntent.CloseSpeakingRecord -> { speaking.closeDetail(); speaking6.closeDetail() }
                is UiIntent.MoreSpeakingRecords -> if (view.access.detailsOpen) profiles.current()?.let { if (presentation.value.preparedCatalogAvailable) speaking6.refresh(it, more = true) else speaking.refresh(it, more = true) }
                is UiIntent.HideSpeakingRecord -> if (view.access.detailsOpen) profiles.current()?.let {
                    if (presentation.value.preparedCatalogAvailable) { speaking6.visibility(it, intent.lessonId, intent.hidden); speaking6.refresh(it) }
                    else { speaking.visibility(it, intent.lessonId, intent.hidden); speaking.refresh(it) }
                }
                is UiIntent.RepracticeRecord -> if (view.access.detailsOpen) {
                    if (presentation.value.preparedCatalogAvailable) { startFreshPractice(intent.lessonId); return }
                    val record = speaking.state.value.detail?.record?.takeIf { it.lessonId == intent.lessonId.toString() && it.sourceAccess.newPractice == "available" } ?: return
                    val ref = record.contentRef
                    val fixed = PracticeContentRef(ContentRef(UUID.fromString(ref.articleId), UUID.fromString(ref.bundleId), UUID.fromString(ref.sourceSnapshotId),
                        UUID.fromString(ref.listeningScriptId), ListeningMode.valueOf(ref.listeningMode.uppercase(java.util.Locale.ROOT))), UUID.fromString(ref.taskId), UUID.fromString(ref.basicQuestionVersionId))
                    learning.startPractice(InteractionChannel.PARKED, fixed)
                }
                is UiIntent.ReplayHistory -> replayHistory(intent.selectionId, intent.checkpointId, intent.alternateMode)
                is UiIntent.ReadHistory -> if (view.access.detailsOpen) { loadHistory(); profiles.current()?.let { if (presentation.value.preparedCatalogAvailable) speaking6.refresh(it) else speaking.refresh(it) } }
                is UiIntent.ResumePractice -> if (view.access.detailsOpen) {
                    if (presentation.value.preparedCatalogAvailable) {
                        val record = presentation.value.source6History?.records?.filterIsInstance<WireSource6PersonalReadLesson>()?.singleOrNull { it.recordId == intent.lessonId.toString() } ?: return
                        if (attachPracticeArticle(record.payload)) learning.restorePreparedPractice(record.payload)
                        return
                    }
                    val snapshot = presentation.value.history ?: return
                    val record = snapshot.records.filterIsInstance<WirePersonalReadLesson>().singleOrNull { it.recordId == intent.lessonId.toString() } ?: return
                    learning.restorePractice(record.payload, snapshot)
                }
                is UiIntent.DeleteHistory -> if (view.access.detailsOpen) {
                    val owner = view.owner ?: return
                    learning.cancelEffects(); preparedRuntime.detach(); controller?.stop(); coordinator.fenceOwner(owner)
                    presentation.update { it.copy(history = null, reader = null, article = null, deletionPending = true) }
                    val result = accounts.requestHistoryDeletion()
                    presentation.update { it.copy(notice = if (result is LearningReply.Stored) "요청한 학습 기록 삭제를 확인했어요. 다른 기기는 연결되면 반영돼요" else "이 차량에서 기록을 가렸어요. 서버 삭제 확인을 기다리고 있어요", deletionPending = result !is LearningReply.Stored) }
                    if (result is LearningReply.Stored) connectProfile()
                }
                is UiIntent.RetryDeletion -> {
                    val result = accounts.retryDeletion()
                    if (result is LearningReply.Stored) connectProfile()
                    else presentation.update { it.copy(recovery = "서버 삭제 확인을 기다리고 있어요. 연결을 확인한 뒤 다시 시도해 주세요", deletionPending = true) }
                }
                is UiIntent.Logout -> if (view.access.detailsOpen) {
                    val owner = view.owner ?: return
                    learning.cancelEffects(); preparedRuntime.detach(); controller?.stop(); coordinator.fenceOwner(owner)
                    accounts.transitions.logout()
                    presentation.update { it.copy(history = null, reader = null, article = null) }
                    connectProfile()
                }
                is UiIntent.LoadSentences -> reviewTools.loadSentences()
                is UiIntent.OpenSentence -> reviewTools.openSentence(intent.sentenceId)
                is UiIntent.SpeakSentence -> reviewTools.speakSentence()
                is UiIntent.ShowSentenceMeaning -> reviewTools.showMeaning()
                is UiIntent.CloseReviewTool -> reviewTools.close()
                is UiIntent.PreviewSettings -> reviewTools.preview()
                is UiIntent.RetryFollowup -> pendingPreparedPhase?.let { preparedPhaseEnded(it) }
                is UiIntent.SkipFollowup -> pendingPreparedPhase?.let { preparedPhaseEnded(it, true) }
                is UiIntent.Refresh -> { if (controller == null) connectMedia(); connectProfile(allowCreation = false) }
                is UiIntent.Navigate -> {
                    if (coordinator.state.value.practiceModal?.visible == true) {
                        learning.handle(UiIntent.FinishPractice(intent.origin))
                        if (coordinator.state.value.task != null) return
                    }
                    reviewTools.close()
                    if (intent.screen in setOf(SessionScreen.SETTINGS, SessionScreen.ONBOARDING)) {
                        val profile = presentation.value.profile ?: return
                        view.owner?.let { coordinator.settingsOpened(it, if (view.screen in setOf(SessionScreen.HISTORY, SessionScreen.SAVED)) ReviewEntryOrigin.HISTORY else if (view.practiceModal?.visible == true) ReviewEntryOrigin.PRACTICE else ReviewEntryOrigin.NEWS) }
                        draftLane.withLock { restoreSettingsDraft(profile) }
                    }
                    coordinator.navigate(view.owner, intent.screen)
                    if (intent.screen in setOf(SessionScreen.PLAYER, SessionScreen.ARTICLE_INFO) && view.access.detailsOpen) loadContent()
                }
                is UiIntent.Topic -> {
                    val configured = presentation.value.categoryConfig?.categories?.singleOrNull { it.id == intent.topic }
                    if (configured?.active == true) editDraft { it.toggleTopic(intent.topic) }
                }
                is UiIntent.Mode -> editDraft { it.mode(intent.mode) }
                is UiIntent.FollowupChoice -> editDraft { it.followup(intent.policy) }
                is UiIntent.Appearance -> if (view.access.detailsOpen) changeSettingsDraft { it.copy(themeDraft = intent.value, draftRevision = it.draftRevision + 1) }
                is UiIntent.ApplySettings -> applySettings()
                is UiIntent.CancelSettings -> {
                    view.owner?.let { owner -> draftLane.withLock { ownerDrafts.discard(owner) } }
                    presentation.update { it.copy(draft = it.profile?.settings?.let(::SettingsDraft), themeDraft = appearances.state.value, bandDraft = preferences.state.value.band, draftStorageRevision = null, notice = null) }
                    coordinator.navigate(view.owner, intent.destination ?: if (view.listening == null && view.preparedListening == null) SessionScreen.NEWS else SessionScreen.PLAYER)
                    view.owner?.let { finishSettingsOffer(it) }
                }
                is UiIntent.SelectArticle -> selectArticle(intent.articleId, intent.freshStream, retry = false)
                is UiIntent.RetrySelection -> selectArticle(null, false, retry = true)
                is UiIntent.CancelSelection -> cancelPendingSelection()
                is UiIntent.ReadyAlternative -> if (view.access.detailsOpen) choosePendingChoice(PendingChoiceKind.READY_ALTERNATIVE, false)
                is UiIntent.PendingOriginal -> if (view.access.detailsOpen && state.value.originalAlternativeAvailable) choosePendingOriginal(false)
                is UiIntent.Resume -> {
                    if (view.preparedListening != null) { coordinator.navigate(view.owner, SessionScreen.PLAYER); play(requireNotNull(view.owner), true); return }
                    if (presentation.value.formatPendingMode != null) { presentation.update { it.copy(notice = "형식 변경 결과를 먼저 확인해 주세요") }; return }
                    val current = profiles.current() ?: return
                    if (coordinator.state.value.owner?.let { it != current.owner } == true) controller?.pause()
                    if (!coordinator.invalidateMediaActivation(current.owner)) return
                    val ticket = coordinator.beginOperation(SessionOperationKind.RESTORE, current.owner) ?: return
                    try {
                        val frame = listening.snapshot()?.takeIf { it.checkpoint != null } ?: presentation.value.resumeFrame ?: return
                        val work = frame.workRef() ?: return
                        listening.attach(frame.copy(owner = current.owner))
                        if (!coordinator.admitListening(RepositoryCompletion(ticket, work), localPosition(frame), true)) return
                        loadContent()
                        play(current.owner, true, operationTicket = ticket, expectedSelection = work.selectionId)
                    } finally { coordinator.finishOperation(ticket) }
                }
                is UiIntent.PlayPause -> view.owner?.let { play(it, intent.wantPlay) }
                is UiIntent.Stop -> {
                    coordinator.stop(); learning.cancelEffects(); preparedRuntime.detach(); controller?.stop()
                    scope.launch { learning.stopped() }
                    if (view.listening?.stage == ListeningStage.ARTICLE_OFFER) coordinator.navigate(view.owner, SessionScreen.NEWS)
                }
                is UiIntent.Seek -> {
                    if (view.preparedListening != null) { preparedRuntime.runtime.move(if (intent.offsetMs < 0) -1 else 1, view.intent == PlaybackIntent.PLAY); return }
                    val duration = view.listening?.durationMs ?: return
                    if (view.access.power == PowerAvailability.ALLOWED && view.mediaConnected)
                        controller?.seekTo(((view.mediaPositionMs ?: 0) + intent.offsetMs).coerceIn(0, duration))
                }
                is UiIntent.ReaderView -> if (view.access.detailsOpen) loadContent(intent.language)
                is UiIntent.ChangeFormat -> changeFormat(intent.mode)
                is UiIntent.MoreCatalog -> if (view.access.detailsOpen) loadCatalog(more = true)
                else -> Unit // Learning intents were dispatched above with the same origin guard.
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { presentation.update { it.copy(notice = "저장한 진행은 유지돼요. 잠시 후 다시 확인해 주세요") } }
    }

    private suspend fun readAssessment(lessonId: UUID, attemptId: UUID) {
        val call = profiles.current() ?: return
        assessmentReviews.read(call, lessonId, attemptId)
    }

    private suspend fun requestAssessment(lessonId: UUID, attemptId: UUID, entry: String, retry: Boolean) {
        val call = profiles.current() ?: return
        val active = learning.state.value.practice?.takeIf { it.lesson.lessonId == lessonId }?.lesson?.contentRef
        val recorded = speaking6.state.value.detail?.record?.takeIf { it.lessonId == lessonId.toString() }?.contentRef?.domainPracticeRef()
        val legacy = presentation.value.history?.records?.filterIsInstance<WirePersonalReadLesson>()
            ?.singleOrNull { it.recordId == lessonId.toString() }?.payload?.contentRef?.let { ref ->
                PracticeContentRef(ContentRef(UUID.fromString(ref.articleId), UUID.fromString(ref.bundleId), UUID.fromString(ref.sourceSnapshotId),
                    UUID.fromString(ref.listeningScriptId), ListeningMode.valueOf(ref.listeningMode.uppercase())), UUID.fromString(ref.taskId), UUID.fromString(ref.basicQuestionVersionId))
            }
        val source = active ?: recorded ?: legacy ?: run {
            presentation.update { it.copy(notice = "원래 연습 기록을 확인한 뒤 판정을 다시 확인할 수 있어요") }; return
        }
        // Results update this original record only. No capture, feedback ACK, resume or
        // playback call is made, even if the user leaves while the request is running.
        if (!coordinator.holdAssessmentReview(call.owner)) return
        assessmentReviews.request(call, lessonId, attemptId, source, entry, retry)
    }

    private suspend fun freezeAndSave(owner: OwnerContext): Boolean {
        if (coordinator.state.value.preparedListening != null) return preparedRuntime.hold(PauseReason.USER, preservePlayIntent = true)
        if (!freeze(owner)) return false
        val call = profiles.current()?.takeIf { it.owner == owner } ?: return false
        val frame = listening.snapshot() ?: return false
        val current = point(frame) ?: return false
        return listening.saveCurrent(call, current) is ListeningResult.Ready
    }

    private suspend fun finishSettingsOffer(owner: OwnerContext) {
        val result = coordinator.settingsUserLeft(owner, UUID.randomUUID()) ?: return
        result.effects.filterIsInstance<ReviewOfferEffect.AnnounceFullOffer>().singleOrNull()?.let { learning.announceArticleOffer(it.announcement) }
    }

    /** Restores only the exact article underpinning this lesson, without choosing a new resume. */
    private suspend fun attachPracticeArticle(lesson: WireSource6PersonalLessonRecord): Boolean {
        val call = profiles.current() ?: return false
        val reference: WireSource6ContentRef6 = when (val ref = lesson.contentRef) {
            is WireSource6PreparedPracticeRef6 -> WireSource6PreparedContentRef6("prepared_components", ref.articleId, ref.sourceSnapshotId, ref.sourceHash,
                ref.materialGenerationId, ref.mainComponentId, ref.mainScriptId, ref.mainTextHash, ref.listeningMode)
            is WireSource6LegacyPracticeRef6 -> WireSource6LegacyContentRef6("legacy_bundle", WireSource6LegacyContentRef5(ref.legacyRef.articleId, ref.legacyRef.bundleId,
                ref.legacyRef.sourceSnapshotId, ref.legacyRef.listeningMode, ref.legacyRef.listeningScriptId))
        }
        val before = coordinator.snapshot()
        if (before.preparedListening?.content?.source6Wire() == reference) return true
        val snapshot = (source6Listening.snapshot(call) as? LearningReply.Stored)?.value ?: return false
        val checkpoint = snapshot.records.filterIsInstance<WireSource6PersonalReadCheckpoint>().singleOrNull {
            it.recordId == lesson.returnCheckpointId && it.payload.contentRef == reference
        } ?: run { presentation.update { it.copy(notice = "이 연습의 원래 기사 듣기 위치를 먼저 확인해 주세요. 답변과 결과는 기록에 보존돼요") }; return false }
        val cursor = checkpoint.payload.cursor as? WireSource6SegmentCursor6 ?: run {
            presentation.update { it.copy(notice = "예전 재생 위치의 구간 대응은 미확인이에요. 기록에서 당시 기사를 다시 듣기로 선택할 수 있어요") }; return false
        }
        if (before.preparedListening != null && !preparedRuntime.hold(PauseReason.USER) || before.listening != null && !freezeAndSave(call.owner)) return false
        val selection = snapshot.records.filterIsInstance<WireSource6PersonalReadSelection>().singleOrNull { it.recordId == checkpoint.payload.selectionId }?.payload ?: return false
        val context = readCurrentReviewContext(call, selection.selectionId)
        val work = PreparedListeningWorkRef(UUID.fromString(selection.streamId), UUID.fromString(selection.selectionId), UUID.fromString(checkpoint.recordId),
            reference.domainSegmentRef(), cursor.toSegmentCursor(), checkpoint.version, checkpoint.payload.activeRunId?.let(UUID::fromString))
        val ticket = coordinator.beginOperation(SessionOperationKind.RESTORE, call.owner) ?: return false
        try {
            if (!coordinator.admitPreparedListening(RepositoryCompletion(ticket, work), true, context, restored = true)) return false
            presentation.update { it.copy(preparedSelection = selection, preparedScript = null, preparedSource = it.preparedCatalog.singleOrNull { row -> row.articleId == work.content.articleId.toString() }, notice = null) }
            return loadPreparedContent()
        } finally { coordinator.finishOperation(ticket) }
    }

    private suspend fun startFreshPractice(sourceLessonId: UUID) {
        val call = profiles.current() ?: return
        val detail = speaking6.state.value.detail?.record
        if (detail != null && detail.lessonId == sourceLessonId.toString() && detail.sourceAccess.newPractice != "available") return
        val snapshot = (source6Listening.snapshot(call) as? LearningReply.Stored)?.value ?: return
        val original = snapshot.records.filterIsInstance<WireSource6PersonalReadLesson>().singleOrNull { it.recordId == sourceLessonId.toString() } ?: return
        val previousIntent = pendingFreshPractice?.takeIf { it.first == sourceLessonId }
        val pending = previousIntent ?: (sourceLessonId to UUID.randomUUID()).also { pendingFreshPractice = it }
        learning.cancelEffects()
        coordinator.cancelCapture(call.owner)
        when (val result = if (previousIntent != null) freshPractice.retry(call, pending.second) else freshPractice.prepareAndSelect(call, original.payload, snapshot.profile.resumeVersion, pending.second)) {
            is FreshPracticeResult.Selected -> {
                pendingFreshPractice = null
                if (!result.discardUnsubmittedUi && coordinator.state.value.task != null) { presentation.update { it.copy(notice = "새 회차는 이미 저장돼요. 지금 입력 중인 연습은 유지할게요") }; return }
                coordinator.state.value.practiceModal?.let { coordinator.practiceModalEvent(call.owner, PracticeModalEvent.Close(PracticeModalCloseReason.SAVE_EXIT)) }
                if (coordinator.state.value.task != null) coordinator.leavePractice(call.owner)
                if (attachPracticeArticle(result.lesson.payload)) learning.restorePreparedPractice(result.lesson.payload)
            }
            is FreshPracticeResult.Pending -> presentation.update { it.copy(notice = "새 회차 저장을 확인하고 있어요. 원래 연습과 답변은 유지돼요") }
            is FreshPracticeResult.Conflict -> { pendingFreshPractice = null; presentation.update { it.copy(notice = "이어할 학습이 다른 곳에서 바뀌었어요. 원래 기록과 새 회차는 보존돼요") } }
        }
    }

    private suspend fun enterPracticeModal(owner: OwnerContext, lessonId: UUID, ref: PracticeRef): Boolean {
        val call = profiles.current()?.takeIf { it.owner == owner } ?: return false
        val view = coordinator.snapshot()
        if (!view.access.detailsOpen || view.lessonId != lessonId || view.task != ref) return false
        val selection = view.preparedListening?.selectionId ?: view.listening?.selectionId ?: return false
        val read = reviewRepository.read(call, selection.toString()) as? LearningReply.Stored ?: return false
        val context = read.value.context
        val profile = (source6Listening.snapshot(call) as? LearningReply.Stored)?.value?.profile ?: return false
        val entryId = UUID.randomUUID()
        val request = WireReviewReviewCommandRequest(1, owner.profileId.toString(), owner.privacyEpoch, UUID.randomUUID().toString(),
            context.version, profile.resumeVersion, context.offerId, "enter_practice", WireReviewEnterPracticeArgs(entryId.toString(), lessonId.toString()))
        val result = reviewRepository.command(call, selection.toString(), request) as? LearningReply.Stored ?: return false
        val domain = result.value.context.domainReview(view.listening)
        if (!coordinator.bindReviewContext(owner, domain)) return false
        val original = domain.returnSnapshot ?: return false
        val p = learning.state.value.practice ?: return false
        val modal = PracticeModalLesson(if (p.feedback != null) PracticeModalPhase.FEEDBACK else PracticeModalPhase.QUESTION,
            p.progress?.version ?: 0, p.feedback?.attemptId, p.progress?.validRetryAttemptId != null, p.progress?.phase == "completed")
        return coordinator.openPracticeModal(PracticeModalToken(owner, selection, lessonId, entryId, view.navigationGeneration + 1), original,
            if (view.reviewPolicy.context?.origin == ReviewOrigin.HISTORY_REPLAY) ReviewEntryOrigin.HISTORY else ReviewEntryOrigin.NEWS,
            modal, PracticeModalAdmission(true, true, domain.endPolicy != ReviewEndPolicy.REGULAR_AUTO || domain.offerId == null, true, true)) != null
    }

    private suspend fun closePracticeModal(owner: OwnerContext): Boolean {
        val call = profiles.current()?.takeIf { it.owner == owner } ?: return false
        val view = coordinator.snapshot(); val modal = view.practiceModal?.takeIf { it.visible } ?: return true
        val context = reviewRepository.cached(call, modal.token.selectionId.toString()) ?: return false
        val profile = (source6Listening.snapshot(call) as? LearningReply.Stored)?.value?.profile ?: return false
        val request = WireReviewReviewCommandRequest(1, owner.profileId.toString(), owner.privacyEpoch,
            UUID.nameUUIDFromBytes("close-modal:${modal.token.entryId}:${context.version}".toByteArray()).toString(), context.version, profile.resumeVersion,
            context.offerId, "close_practice", WireReviewClosePracticeArgs(modal.token.entryId.toString(), "save_exit"))
        val response = reviewRepository.command(call, modal.token.selectionId.toString(), request)
        // Local close always stops presentation; an unsent command remains durably pending.
        coordinator.practiceModalEvent(owner, PracticeModalEvent.Close(PracticeModalCloseReason.SAVE_EXIT))
        return response is LearningReply.Stored
    }

    private suspend fun persistSettingsDraft(draftState: OwnerDraftState = OwnerDraftState.EDITING, leaveState: OwnerDraftLeaveState = OwnerDraftLeaveState.NONE) {
        val value = presentation.value
        val owner = value.owner ?: return
        val draft = value.draft ?: return
        if (profiles.current()?.owner != owner) return
        val band = value.bandDraft ?: preferences.state.value.band
        val fields = draft.touched.mapNotNull { when (it) {
            "topics" -> OwnerDraftField.TOPICS; "listening_mode" -> OwnerDraftField.LISTENING_MODE
            "followup_policy" -> OwnerDraftField.FOLLOWUP_POLICY; else -> null
        } }.toSet() + (if (band != preferences.state.value.band) setOf(OwnerDraftField.BAND) else emptySet()) +
            (if (value.themeDraft != appearances.state.value) setOf(OwnerDraftField.APPEARANCE) else emptySet())
        val snapshot = OwnerSettingsDraftSnapshot(
            originalSettingsJson = ownerDraftJson.encodeToString(WireCommonSettings.serializer(), draft.original),
            topics = draft.topics, listeningMode = draft.listeningMode, followupPolicy = draft.followup,
            originalBand = preferences.state.value.band, band = band,
            originalAppearance = appearances.state.value.name.lowercase(), appearance = value.themeDraft.name.lowercase(),
            dirtyFields = fields, localRevision = value.draftRevision, state = draftState, leaveState = leaveState)
        try {
            val saved = ownerDrafts.save(owner, value.draftStorageRevision, snapshot)
            if (presentation.value.owner == owner) presentation.update { it.copy(draftStorageRevision = saved.storageRevision) }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            if (presentation.value.owner == owner) presentation.update { it.copy(notice = "변경한 설정의 기기 저장을 확인하지 못했어요. 초안은 현재 화면에 유지돼요") }
        }
    }

    private suspend fun restoreSettingsDraft(profile: WirePersonalProfileView) {
        val owner = profiles.current()?.owner ?: return
        try {
            val saved = ownerDrafts.load(owner)
            if (profiles.current()?.owner != owner || presentation.value.owner != owner) return
            val current = presentation.value
            if (saved == null) {
                presentation.update { it.copy(draft = SettingsDraft(profile.settings), themeDraft = appearances.state.value,
                    bandDraft = preferences.state.value.band, draftStorageRevision = null, draftRevision = current.draftRevision + 1, draftToken = UUID.randomUUID(), notice = null) }
            } else {
                val data = saved.snapshot
                val original = ownerDraftJson.decodeFromString(WireCommonSettings.serializer(), data.originalSettingsJson)
                val touched = data.dirtyFields.mapNotNull { when (it) {
                    OwnerDraftField.TOPICS -> "topics"; OwnerDraftField.LISTENING_MODE -> "listening_mode"
                    OwnerDraftField.FOLLOWUP_POLICY -> "followup_policy"; else -> null
                } }.toSet()
                presentation.update { it.copy(draft = SettingsDraft(original, data.topics, data.listeningMode, data.followupPolicy, touched),
                    themeDraft = DeviceAppearance.valueOf(data.appearance.uppercase()), bandDraft = data.band,
                    draftStorageRevision = saved.storageRevision, draftRevision = data.localRevision, draftToken = UUID.randomUUID(),
                    notice = if (data.dirtyFields.isEmpty()) null else "이 기기에 남겨 둔 설정 초안을 불러왔어요. 적용한 설정은 아직 바뀌지 않았어요") }
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            presentation.update { it.copy(notice = "저장된 설정 초안을 확인하지 못했어요. 다른 값으로 덮지 않고 연결을 다시 확인해 주세요") }
        }
    }

    private suspend fun changeSettingsDraft(edit: (ProductPresentation) -> ProductPresentation) = draftLane.withLock {
        presentation.update(edit)
        persistSettingsDraft()
    }

    private suspend fun restoreQuestion(owner: OwnerContext, plan: ConversationReturn, explicit: Boolean) {
        if (profiles.current()?.owner != owner) return
        when (plan) {
            is ConversationReturn.SameQuestion -> coordinator.showPractice(owner, plan.lessonId, SessionScreen.QUESTION)
            is ConversationReturn.ReannounceOffer -> coordinator.navigate(owner, SessionScreen.PLAYER)
            is ConversationReturn.ReannouncePreparedOffer -> coordinator.navigate(owner, SessionScreen.PLAYER)
            is ConversationReturn.PreparedListening -> {
                val work = coordinator.state.value.preparedListening ?: return
                if (!work.sameWork(plan.point.work)) return
                coordinator.navigate(owner, SessionScreen.PLAYER)
                if (explicit && coordinator.state.value.intent == plan.point.playIntent && plan.point.playIntent == PlaybackIntent.PLAY) play(owner, true)
            }
            is ConversationReturn.Listening -> {
                val view = coordinator.snapshot(); val work = view.listening ?: return
                if (work.assetId != plan.point.playback.assetId || work.stage != plan.point.playback.stage) return
                coordinator.navigate(owner, SessionScreen.PLAYER)
                controller?.seekTo(plan.point.playback.positionMs)
                if (explicit && view.intent == plan.point.intent && plan.point.intent == PlaybackIntent.PLAY) play(owner, true)
            }
        }
    }

    private fun currentVoiceRecommendation(owner: OwnerContext): GleoRecommendationBasis? {
        val view = recommendations.state.value
        val preference = preferences.state.value
        val record = view.view ?: return null
        val recommendation = record.recommendation ?: return null
        if (view.owner != owner || preference.owner != owner || !view.mayAct(true) || preference.loading || preference.failed ||
            preference.pending || preference.conflict || recommendation.evaluatedAt == null) return null
        return GleoRecommendationBasis(owner.profileId, owner.privacyEpoch, UUID.fromString(recommendation.recommendationId),
            recommendation.version, record.recommendationRevision, recommendation.evidenceRevision, recommendation.basisSignature,
            java.time.Instant.parse(recommendation.evaluatedAt), preference.serverVersion, preference.serverChoiceId)
    }

    private suspend fun adoptImmediateBand() {
        if (presentation.value.draftStorageRevision == null) presentation.value.profile?.let { profile ->
            draftLane.withLock { restoreSettingsDraft(profile) }
        }
        changeSettingsDraft { it.copy(bandDraft = preferences.state.value.band, draftRevision = it.draftRevision + 1) }
    }

    private suspend fun gleoLearningPreference(request: GleoLearningPreferenceRequest): String? {
        val before = coordinator.snapshot()
        if (!request.lease.mayAct(before)) return null
        val call = profiles.current()?.takeIf { it.owner == request.lease.owner } ?: return null
        val band = request.command.band
        if (band != null) {
            if (preferences.choose(call, band) !is LearningReply.Stored) return "단계 선택을 저장하지 못했어요. 현재 기사는 유지돼요"
            val result = preferences.flush(call)
            if (!request.lease.mayAct(coordinator.snapshot()) || profiles.current()?.owner != call.owner) return null
            adoptImmediateBand()
            return if (result is LearningReply.Stored && !preferences.state.value.conflict && !preferences.state.value.pending)
                "${bandName(band)}으로 선택했어요. 다음 기사부터 적용하고 지금 연습과 다른 설정 초안은 유지할게요"
            else "단계 선택은 이 기기에 남겼어요. 서버 저장을 다시 확인한 뒤 다음 기사에 적용할게요"
        }
        val basis = request.recommendationBasis
        val current = currentVoiceRecommendation(call.owner)
        if (basis != current) return "추천 근거가 바뀌었어요. 현재 추천을 다시 확인해 주세요"
        val action = request.command.action
        if (action == null) {
            val record = recommendations.state.value.view?.recommendation
            if (recommendations.state.value.failed || preferences.state.value.failed)
                return "참고 추천의 연결을 확인하지 못했어요. 저장한 기록과 선택한 단계는 유지돼요"
            if (recommendations.state.value.loading || recommendations.state.value.status == RecommendationStatus.PENDING)
                return "참고 추천을 확인하고 있어요. 준비된 뒤 직접 다시 확인해 주세요"
            if (basis == null || record?.recommendedBand == null || record.evaluatedAt == null)
                return "현재 기록으로 확인된 참고 추천이 없어요. 직접 선택한 단계를 유지할게요"
            val selected = LearningBand.valueOf(requireNotNull(record.recommendedBand).uppercase())
            val evaluated = java.time.format.DateTimeFormatter.ofPattern("M월 d일 HH시 mm분")
                .withZone(java.time.ZoneId.of("Asia/Seoul")).format(basis.evaluatedAt)
            return "영어 연습 참고로 ${bandName(selected)}을 제안해요. ${record.reasonKo} 이 평가의 시각은 ${evaluated}이에요"
        }
        if (basis == null || !basis.ownedBy(call.owner)) return null
        val result = recommendations.act(call, action, explicitReview = true)
        if (!request.lease.mayAct(coordinator.snapshot()) || profiles.current()?.owner != call.owner) return null
        if (result !is LearningReply.Stored) return "추천 처리 결과를 다시 확인해 주세요. 지금 기사와 연습은 유지돼요"
        if (action == RecommendationAction.APPLY) adoptImmediateBand()
        return when (action) {
            RecommendationAction.APPLY -> "추천 단계를 적용했어요. 다음 기사부터 사용하고 다른 설정 초안은 유지할게요"
            RecommendationAction.KEEP -> "선택한 단계를 유지할게요"
            RecommendationAction.DISMISS -> "이 참고 추천을 닫았어요. 선택한 단계는 유지돼요"
        }
    }

    private fun bandName(band: LearningBand) = when (band) {
        LearningBand.BASIC -> "초급"; LearningBand.INTERMEDIATE -> "중급"; LearningBand.ADVANCED -> "고급"
    }

    private suspend fun gleoPlayback(command: String, title: String?, mode: String?): String {
        val view = coordinator.snapshot(); val owner = view.owner ?: return "학습 연결을 먼저 확인해 주세요"
        return when(command) {
            "pause" -> { cancelPendingSelection(); play(owner, false); "뉴스를 멈췄어요" }
            "stop" -> { cancelPendingSelection(); coordinator.requestMediaIntent(owner, PlaybackIntent.STOPPED); if (view.preparedListening != null) preparedRuntime.hold(PauseReason.USER) else controller?.stop(); "뉴스를 멈췄어요" }
            "resume" -> { if (view.task == null) { play(owner, true); "뉴스를 이어 들을게요" } else "먼저 진행 중인 연습을 저장해 주세요" }
            "listen_only_today", "default_interaction" -> {
                chooseListeningOnly(command == "listen_only_today")
                if (presentation.value.daily.conflict) "이 차량의 선택은 유지돼요. 다른 기기와의 동기화를 확인해 주세요"
                else if (command == "listen_only_today") "듣기만 할게요. 원하면 말하기를 직접 시작할 수 있어요" else "말하기 제안을 다시 받을 수 있어요"
            }
            "pending_original" -> {
                choosePendingOriginal(true)
                presentation.value.notice ?: "선택한 기사의 원문 상태를 확인했어요"
            }
            "pending_alternative" -> {
                choosePendingChoice(PendingChoiceKind.READY_ALTERNATIVE, true)
                presentation.value.notice ?: "다른 준비 기사의 상태를 확인했어요"
            }
            "pending_wait" -> {
                selectPreparedArticle(null, false, true, true)
                presentation.value.notice ?: "같은 기사의 준비 상태를 확인했어요"
            }
            "change_format" -> {
                val target = mode?.let { value -> ListeningMode.entries.singleOrNull { it.name.lowercase() == value } }
                    ?: return "원문과 브리핑 중 원하는 형식을 확인해 주세요"
                if (!title.isNullOrBlank()) {
                    val currentTitle = presentation.value.preparedSource?.title ?: presentation.value.article?.title
                    if (currentTitle?.trim() != title.trim()) return "현재 듣는 기사 제목을 확인해 주세요. 준비 중인 기사 원문은 별도로 선택할 수 있어요"
                }
                val completed = withTimeoutOrNull(6_000L) { changeFormat(target, viaVoice = true); true } == true
                if (completed) presentation.value.notice ?: "같은 기사의 형식을 확인했어요" else "형식 선택 상태를 다시 확인해 주세요. 자동으로 다시 재생하지 않아요"
            }
            "start", "next", "auto_next" -> {
                if (view.task != null) "진행 중인 연습을 먼저 저장해 주세요"
                else {
                    val sourceCandidates = if (title.isNullOrBlank()) emptyList() else (
                        presentation.value.preparedCatalog.filter { it.title.trim() == title.trim() }.map { CatalogArticleKey(UUID.fromString(it.articleId), UUID.fromString(it.sourceSnapshotId)) } +
                            catalogViews.state.value.items.filter { it.title.trim() == title.trim() }.map { it.key }).distinct()
                    if (!title.isNullOrBlank() && sourceCandidates.isNotEmpty()) {
                        if (sourceCandidates.size != 1) return "같은 제목의 원문이 여러 개예요. 원문을 확인한 뒤 다시 선택해 주세요"
                        val target = sourceCandidates.single()
                        selectPreparedArticle(target.articleId, command == "start", false, true, target.sourceSnapshotId, automatic = command == "auto_next")
                        return if (presentation.value.selectionPending) "자료 준비 상태를 다시 확인할 수 있어요" else "선택한 기사를 준비하고 있어요"
                    }
                    val article = if (title.isNullOrBlank()) null else {
                        val candidates = presentation.value.catalog.filter { it.title.trim() == title.trim() }
                        if (candidates.size != 1) return "기사 제목을 확인하지 못했어요. 듣던 진행은 유지돼요"
                        candidates.single().articleId
                    }
                    selectArticle(article, fresh = command == "start", retry = false, viaVoice = true, automatic = command == "auto_next")
                    if (presentation.value.selectionPending) "기사 선택 결과를 다시 확인해 주세요" else if (presentation.value.noCandidate != null) "새로 들을 뉴스를 지금 준비하지 못했어요" else "선택한 기사를 준비하고 있어요"
                }
            }
            else -> "현재 음성 재생 요청을 지원하는지 확인하고 있어요. 듣던 진행은 유지돼요"
        }
    }

    private suspend fun editDraft(edit: (SettingsDraft) -> SettingsDraft) {
        if (!coordinator.state.value.access.detailsOpen) return
        changeSettingsDraft { value -> value.draft?.let(edit)?.takeIf { it.topics.size <= 20 }?.let { value.copy(draft = it, notice = null, draftRevision = value.draftRevision + 1) } ?: value }
    }

    private suspend fun connectProfile(allowCreation: Boolean = true) {
        val ticket = coordinator.beginOperation(SessionOperationKind.PROFILE, coordinator.state.value.owner) ?: return
        try {
            if (BuildConfig.LEARNING_API_ORIGIN.isBlank()) {
                presentation.update { it.copy(recovery = "학습 연결을 준비하고 있어요. 연결 점검에서 설정을 확인해 주세요") }; return
            }
            accounts.transitions.recoverAndRetry()
            if (accounts.hasPendingDeletion()) {
                val deleted = accounts.retryDeletion()
                if (deleted is LearningReply.Failed) { presentation.update { it.copy(deletionPending = true, recovery = "학습 기록 삭제를 서버에 확인하고 있어요") }; return }
            }
            val targetRecovery = accounts.recoverLinkedTarget()
            if (targetRecovery is LearningReply.Failed) {
                presentation.update { it.copy(accountEntry = AccountEntryState(AccountEntryPhase.FAILED, message = "저장된 계정 전환을 다시 확인해 주세요", canRetryTransition = true)) }; return
            }
            val merge = accounts.recoverMerge()
            if (merge is LearningReply.Failed) { presentation.update { it.copy(recovery = "기존 기록을 보존한 채 계정 연결을 다시 확인하고 있어요") }; return }
            when (val reply = profiles.connect(allowCreation)) {
                is LearningReply.Failed -> presentation.update { it.copy(recovery = failure(reply.code)) }
                is LearningReply.Stored -> {
                    val current = profiles.current() ?: return
                    if (coordinator.state.value.owner?.let { it != current.owner } == true) controller?.pause()
                    if (!coordinator.bindVerifiedOwner(ticket, current.owner, reply.value.settings.onboardingComplete)) return
                    val previous = presentation.value
                    if (previous.owner != current.owner) { pendingPreparedFormat = null; pendingReview3Selection = null; reviewNext3.clear(); pendingReview2Selection = null; reviewNext2.clear(); pendingPreparedSelection = null; pendingPreparedPhase = null; pendingPreparedSnapshot = null; preparedRuntime.detach() }
                    val next = if (previous.owner == current.owner) previous.copy(profile = reply.value,
                        draft = previous.draft?.takeIf { it.dirty } ?: SettingsDraft(reply.value.settings), recovery = null)
                    else ProductPresentation(owner = current.owner, profile = reply.value, draft = SettingsDraft(reply.value.settings), themeDraft = appearances.state.value)
                    presentation.value = next.copy(settingsPending = profiles.hasPendingSettings())
                    updateAccess()
                    speaking.bind(current); speaking6.bind(current); preferences.bind(current); recommendations.bind(current); assessmentReviews.bind(current)
                    if (catalogViews.state.value.owner != current.owner) catalogViews.bind(current)
                    val recoveryProjection = profiles.recoveryProjection()
                    presentation.update { it.copy(identityRecovery = recoveryProjection) }
                    preferences.refresh(current)
                    val cached = listening.restoreCached(current)
                    if (cached is ListeningResult.Ready) {
                        listening.updateSettingsVersion(current.owner, reply.value.settings.version)
                        val ref = cached.frame.workRef()
                        if (ref != null) {
                            val restore = coordinator.beginOperation(SessionOperationKind.RESTORE, current.owner)
                            if (restore != null) coordinator.admitListening(RepositoryCompletion(restore, ref), localPosition(cached.frame), false)
                            presentation.update { it.copy(resumeFrame = cached.frame) }
                        }
                    }
                    when (val preference = daily.refresh(current)) {
                        is LearningReply.Stored -> presentation.update { it.copy(daily = preference.value, listenOnly = preference.value.listenOnly(daily.trustedNow())) }
                        is LearningReply.Failed -> {
                            val local = daily.state(current)
                            presentation.update { it.copy(daily = local, listenOnly = local.listenOnly(daily.trustedNow())) }
                        }
                    }
                    loadCatalog(false)
                    if (presentation.value.preparedCatalogAvailable) restorePreparedProfile(current)
                    restorePendingListeningRequest(current)
                }
            }
        } finally {
            coordinator.finishOperation(ticket)
            updateRecoveryProjection()
        }
    }

    private suspend fun updateRecoveryProjection() {
        try {
            val value = profiles.recoveryProjection()
            val owner = coordinator.state.value.owner
            if (owner == null || value.owner == owner) presentation.update { it.copy(identityRecovery = value) }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* Existing storage/recovery notice remains; no new identity is created. */ }
    }

    private suspend fun readCurrentReviewContext(call: LearningCallScope, selectionId: String, formatOperationId: UUID? = null): ReviewContext? {
        formatOperationId?.let { autoHeadRecovery.recordConfirmedFormatHead(call, it) }
        val fresh = incrementalApi.call("review2", "/v1/review/listening-contexts/{selection_id}", "get", call, null,
            WireReview2ListeningContextResponse2.serializer(), mapOf("selection_id" to selectionId), mapOf("review_contract_version" to "2"))
        if (fresh is LearningReply.Stored) {
            val context = fresh.value.context
            if (profiles.current()?.owner != call.owner) return null
            val confirmedHead = context.autoProgressRef?.let { progress ->
                (autoHeadRecovery.recover(call, UUID.fromString(progress.autoProgressId), progress.version) as? AutoProgressHeadRecovery.Confirmed)?.head?.selectionId?.toString()
            }
            presentation.update { if (it.owner == call.owner) it.copy(autoProgress = context.autoProgressRef,
                autoProgressHead = confirmedHead ?: it.autoProgressHead.takeIf { _ -> it.autoProgress == context.autoProgressRef }) else it }
            val value = incrementalCodecs.parse("review2", incrementalCodecs.encode("review2", "ListeningContext2", context, WireReview2ListeningContext2.serializer())).jsonObject
            return incrementalCodecs.decodeReview("ListeningContext", kotlinx.serialization.json.JsonObject(value - "auto_progress_ref").toString(), WireReviewListeningContext.serializer()).domainReview()
        }
        return (reviewRepository.read(call, selectionId) as? LearningReply.Stored)?.value?.context?.domainReview()
    }

    private suspend fun restorePreparedProfile(call: LearningCallScope) {
        val snapshot = (source6Listening.snapshot(call) as? LearningReply.Stored)?.value ?: return
        val resume = snapshot.profile.activeResume ?: return
        if (resume.kind != "listening") return
        val checkpoint = snapshot.records.filterIsInstance<WireSource6PersonalReadCheckpoint>().singleOrNull { it.recordId == resume.recordId } ?: return
        val ref = checkpoint.payload.contentRef
        val cursor = checkpoint.payload.cursor as? WireSource6SegmentCursor6 ?: return
        val selection = snapshot.records.filterIsInstance<WireSource6PersonalReadSelection>().singleOrNull { it.recordId == checkpoint.payload.selectionId }?.payload ?: return
        val work = PreparedListeningWorkRef(UUID.fromString(selection.streamId), UUID.fromString(selection.selectionId), UUID.fromString(checkpoint.recordId),
            ref.domainSegmentRef(), cursor.toSegmentCursor(), checkpoint.version, checkpoint.payload.activeRunId?.let(UUID::fromString))
        val context = readCurrentReviewContext(call, selection.selectionId)
        val ticket = coordinator.beginOperation(SessionOperationKind.RESTORE, call.owner) ?: return
        try {
            if (!coordinator.admitPreparedListening(RepositoryCompletion(ticket, work), false, context, restored = true)) return
            presentation.update { it.copy(preparedSelection = selection, preparedSource = it.preparedCatalog.singleOrNull { row -> row.articleId == ref.domainSegmentRef().articleId.toString() && (ref !is WireSource6PreparedContentRef6 || row.sourceHash == ref.sourceHash) }) }
            loadPreparedContent() // Material binding only. Restore never requests output or opens a modal.
        } finally { coordinator.finishOperation(ticket) }
    }

    /** Restores only the original version/request and passive state, never its live output grant. */
    private suspend fun restorePendingListeningRequest(call: LearningCallScope) {
        if (pendingReview3Selection != null || pendingReview2Selection != null) return
        val latest = reviewNext3.latestPendingOperation(call) ?: return
        val row = reviewDao.operation(latest.operationId.toString()) ?: return
        val view = coordinator.snapshot()
        if (profiles.current()?.owner != call.owner || view.owner != call.owner) return
        if (latest.contractVersion == 3) {
            val restored = (reviewNext3.restore(call, latest.operationId) as? LearningReply.Stored)?.value ?: return
            val binding = restored.intent?.binding ?: return
            val request = incrementalCodecs.decode("review3", "ReviewNextRequest3", row.payloadJson, WireReview3ReviewNextRequest3.serializer())
            pendingReview3Selection = PendingReview3Selection(call.owner, binding, request, view.navigationGeneration, false)
        } else {
            val restored = (reviewNext2.restore(call, latest.operationId) as? LearningReply.Stored)?.value ?: return
            val binding = restored.intent?.binding ?: return
            val request = incrementalCodecs.decode("review2", "ReviewNextRequest2", row.payloadJson, WireReview2ReviewNextRequest2.serializer())
            pendingReview2Selection = PendingReview2Selection(call.owner, binding, request, view.navigationGeneration, false)
        }
        presentation.update { it.copy(selectionPending = true, notice = "저장된 기사 선택이 있어요. 상태를 직접 확인하거나 새 뉴스를 선택해 주세요") }
    }

    /** Reconnect sends existing immutable journals only; no selection, model or output effects. */
    private suspend fun reconcileStoredWork() {
        if (!presentation.value.preparedCatalogAvailable) return
        val previous = profiles.current()?.owner ?: return
        val result = syncWorker.reconcileIncremental(context, learningDatabase, profiles, api, accounts, learning.reconciler(source6Listening),
            flushActualOutputs = learning::flushActualOutputs,
            flushFeatures = { call ->
                val steps = listOf<suspend () -> LearningReply<Unit>>(
                    { daily.flush(call).let { if (it is LearningReply.Failed) it else LearningReply.Stored(call, Unit) } },
                    { preferences.flush(call) }, { recommendations.flush(call) }, { speaking6.flush(call) })
                var failed: LearningReply.Failed? = null
                for (step in steps) { val reply = step(); if (reply is LearningReply.Failed) { failed = reply; break } }
                failed ?: LearningReply.Stored(call, Unit)
            })
        val current = profiles.current()?.owner
        if (current != null && current != previous) {
            coordinator.fenceOwner(previous); learning.cancelEffects(); auxiliary.close(); preparedRuntime.detach(); controller?.stop()
            connectProfile()
        } else if (result is LearningReply.Stored && coordinator.state.value.owner == previous && coordinator.state.value.screen in setOf(SessionScreen.HISTORY, SessionScreen.SAVED))
            presentation.update { it.copy(source6History = result.value) }
    }

    private suspend fun chooseListeningOnly(enabled: Boolean) {
        val call = profiles.current() ?: return
        val selected = daily.choose(call, enabled) // Durable before changing product behaviour.
        presentation.update { it.copy(daily = selected, listenOnly = selected.listenOnly(daily.trustedNow()), notice =
            if (enabled && selected.timeUnresolved) "기기에 듣기만 선택을 저장했어요. 오늘의 종료 시각은 확인 중이며 직접 해제할 수 있어요" else if (enabled) "오늘은 듣기만 할게요. 원하면 언제든 말하기를 시작할 수 있어요" else "말하기 제안을 다시 받을 수 있어요") }
        if ((coordinator.state.value.preparedListening?.stage ?: coordinator.state.value.listening?.stage) == ListeningStage.ARTICLE_OFFER) learning.announceArticleOffer()
        val result = daily.flush(call)
        val state = daily.state(call)
        if (profiles.current()?.owner == call.owner) presentation.update { it.copy(daily = state, listenOnly = state.listenOnly(daily.trustedNow()),
            notice = if (result is LearningReply.Failed) "이 차량의 선택을 유지하고 있어요. 다른 기기와의 동기화를 다시 확인해야 해요" else it.notice) }
    }

    private suspend fun loadHistory() {
        val call = profiles.current() ?: return
        val op = coordinator.beginOperation(SessionOperationKind.HISTORY, call.owner) ?: return
        presentation.update { it.copy(historyStatus = LoadStatus.LOADING) }
        try {
            if (presentation.value.preparedCatalogAvailable) {
                val result = source6Listening.snapshot(call)
                if (!coordinator.finishOperation(op)) return
                when (result) {
                    is LearningReply.Stored -> presentation.update { it.copy(source6History = result.value, historyStatus = LoadStatus.READY) }
                    is LearningReply.Failed -> presentation.update { it.copy(historyStatus = LoadStatus.FAILED, notice = "기록을 불러오지 못했어요. 저장된 학습은 유지돼요") }
                }
                return
            }
            val result = resumes.snapshot(call)
            if (!coordinator.finishOperation(op)) return
            when (result) {
                is LearningReply.Stored -> presentation.update { it.copy(history = result.value, historyStatus = LoadStatus.READY) }
                is LearningReply.Failed -> presentation.update { it.copy(historyStatus = LoadStatus.FAILED, notice = "기록을 불러오지 못했어요. 저장된 학습은 유지돼요") }
            }
        } finally { coordinator.finishOperation(op) }
    }

    private suspend fun readCatalogView(view: CatalogView, category: CatalogCategoryId?) {
        val call = profiles.current() ?: return
        catalogViews.bind(call, view, category)
        catalogViews.refresh(call)
    }

    private suspend fun loadCatalog(more: Boolean) {
        val owner = coordinator.state.value.owner ?: return
        val ticket = coordinator.beginOperation(SessionOperationKind.CATALOG, owner) ?: return
        val offset = if (more) presentation.value.offset else 0
        presentation.update { it.copy(catalogStatus = LoadStatus.LOADING) }
        val call = profiles.current()?.takeIf { it.owner == owner }
        if (!more && call != null) scope.launch {
            if (catalogViews.state.value.owner != call.owner) catalogViews.bind(call)
            catalogViews.refresh(call)
        }
        val prepared = call?.let { preparedCatalog.catalog(it, presentation.value.profile?.settings?.topics.orEmpty().filter { topic -> topic in setOf("technology", "science", "business", "world", "environment", "culture") }, 20, if (more) presentation.value.preparedCatalogCursor else null) }
        if (prepared is LearningReply.Stored) {
            if (!coordinator.finishOperation(ticket)) return
            presentation.update { it.copy(preparedCatalog = ((if (more) it.preparedCatalog else emptyList()) + prepared.value.items).distinctBy { item -> item.articleId to item.sourceHash },
                categoryConfig = prepared.value.categoryConfig, preparedCatalogCursor = prepared.value.nextCursor, preparedCatalogAvailable = true,
                catalogStatus = LoadStatus.READY, more = prepared.value.nextCursor != null) }
            return
        }
        val result = catalog.catalogPage(offset, 20)
        if (!coordinator.finishOperation(ticket)) return
        when (result) {
            is CatalogRead.Unavailable -> presentation.update { it.copy(catalogStatus = LoadStatus.FAILED, notice = failure(result.code)) }
            is CatalogRead.Stored -> presentation.update {
                it.copy(catalog = ((if (more) it.catalog else emptyList()) + result.value).distinctBy { it.articleId to it.bundleId },
                    catalogStatus = LoadStatus.READY, offset = offset + result.value.size, more = result.value.size == 20)
            }
        }
    }

    private suspend fun applySettings() {
        val view = coordinator.snapshot(); val owner = view.owner ?: return
        if (!view.access.detailsOpen) return
        val current = presentation.value; val draft = current.draft ?: return
        if (!draft.valid && !current.settingsPending) return
        val ticket = coordinator.beginOperation(SessionOperationKind.SETTINGS, owner) ?: return
        try {
            val result = when {
                current.settingsPending -> profiles.reconcileSettings()
                draft.dirty || !draft.original.onboardingComplete -> profiles.updateSettings(draft.request(owner, ticket.id))
                else -> null
            }
            if (!coordinator.finishOperation(ticket)) return
            when (result) {
                is LearningReply.Failed -> presentation.update { it.copy(settingsPending = true, notice = failure(result.code)) }
                else -> {
                    if (result is LearningReply.Stored) {
                        if (result.scope.owner != owner) { connectProfile(); return }
                        presentation.update { it.copy(profile = result.value, draft = SettingsDraft(result.value.settings), settingsPending = false) }
                        if (listening.snapshot() != null) listening.updateSettingsVersion(owner, result.value.settings.version)
                    }
                    val band = current.bandDraft
                    if (band != null && band != preferences.state.value.band) preferences.choose(requireNotNull(profiles.current()), band)
                    val bandSync = preferences.flush(requireNotNull(profiles.current()))
                    if (bandSync is LearningReply.Failed) {
                        presentation.update { it.copy(notice = "듣기 설정은 확인했어요. 다음 기사의 단계 동기화는 다시 확인해 주세요") }; return
                    }
                    if (!appearances.save(current.themeDraft)) { presentation.update { it.copy(notice = "화면 설정을 저장하지 못했어요. 다시 적용해 주세요") }; return }
                    draftLane.withLock { ownerDrafts.discard(owner) }
                    presentation.update { it.copy(draftStorageRevision = null, notice = if (current.settingsPending) "현재 설정을 확인했어요. 변경할 항목을 다시 골라 주세요" else "설정을 적용했어요") }
                    if (!current.settingsPending && coordinator.state.value.navigationGeneration == ticket.navigationGeneration)
                        {
                            coordinator.navigate(owner, if (coordinator.state.value.listening == null && coordinator.state.value.preparedListening == null) SessionScreen.NEWS else SessionScreen.PLAYER)
                            finishSettingsOffer(owner)
                        }
                }
            }
        } finally { coordinator.finishOperation(ticket) }
    }

    private suspend fun localPosition(frame: ListeningFrame): Long {
        val point = frame.checkpoint?.payload ?: return 0
        val local = mediaJournal.position(frame.owner, point.checkpointId)
        return local?.takeIf { it.contentRef == point.contentRef && it.phase == point.phase && it.currentAssetId == point.currentAssetId }?.positionMs ?: point.positionMs
    }

    private suspend fun point(frame: ListeningFrame): WirePersonalCheckpointInput? {
        val value = frame.checkpoint?.payload ?: return null
        return WirePersonalCheckpointInput(value.checkpointId, value.contentRef, value.phase, value.currentAssetId, localPosition(frame),
            value.durationMs, coordinator.state.value.intent == PlaybackIntent.PLAY, value.pauseReason, value.streamId, value.selectionId)
    }

    private suspend fun selectArticle(article: UUID?, fresh: Boolean, retry: Boolean, viaVoice: Boolean = false, automatic: Boolean = false) {
        if (retry && (pendingReview3Selection != null || pendingReview2Selection != null || reviewNext3.state.value.retainedParentAnchor != null)) {
            retryPendingListening(viaVoice); return
        }
        val view = coordinator.snapshot(); val call = profiles.current() ?: return
        if (presentation.value.preparedCatalogAvailable || coordinator.state.value.preparedListening != null || catalogViews.state.value.snapshot != null) {
            selectPreparedArticle(article, fresh, retry, viaVoice, automatic = automatic); return
        }
        if ((!view.access.detailsOpen && !(viaVoice && gleoRuntime != null && view.access.ownerValid && view.access.power == PowerAvailability.ALLOWED && view.access.audioAllowsReply)) || presentation.value.profile?.settings?.onboardingComplete != true) return
        if (pendingFormat?.owner == call.owner) { presentation.update { it.copy(notice = "형식 변경 결과를 먼저 확인해 주세요") }; return }
        val saved = pendingSelection?.takeIf { it.owner == call.owner }
        if (!retry && saved != null) { presentation.update { it.copy(notice = "이전 기사 선택을 먼저 확인해 주세요", selectionPending = true) }; return }
        val request = if (retry) saved ?: return else PendingNewsSelection(call.owner, UUID.randomUUID(), article, fresh)
        val ticket = coordinator.beginOperation(SessionOperationKind.SELECTION, call.owner, request.id) ?: return
        try {
            pendingSelection = request
            if (!request.prepared) {
                val choice = preferences.prepareNextSelection(call)
                if (choice is LearningReply.Failed) {
                    presentation.update { it.copy(selectionPending = true, notice = "다음 기사에 적용할 단계를 먼저 확인해 주세요. 듣던 진행은 유지돼요") }; return
                }
                pendingSelection = request.copy(preference = (choice as LearningReply.Stored).value)
                val old = listening.snapshot()
                if (request.fresh && old != null) {
                    presentation.update { it.copy(resumeFrame = old) }
                    val closed = listening.closeStream(call, point(old))
                    if (closed !is ListeningResult.Closed) { presentation.update { it.copy(selectionPending = true, notice = "듣던 위치를 먼저 저장해야 해요") }; return }
                }
                if (listening.snapshot() == null) {
                    val profile = requireNotNull(presentation.value.profile)
                    val opened = listening.createStream(call, profile.settings.version, profile.resumeVersion)
                    if (opened !is ListeningResult.Ready) { presentation.update { it.copy(selectionPending = true, notice = "뉴스 듣기를 준비하지 못했어요. 다시 확인해 주세요") }; return }
                }
                pendingSelection = requireNotNull(pendingSelection).copy(prepared = true)
            }
            var result = if (retry) listening.retryNext(call, request.id)
                else listening.next(call, point(requireNotNull(listening.snapshot())), if (request.article == null) "automatic_unheard" else "explicit_article", request.article, request.id)
            if (result is ListeningResult.Failed && result.code == "NO_SAVED_REQUEST")
                result = listening.next(call, point(requireNotNull(listening.snapshot())), if (request.article == null) "automatic_unheard" else "explicit_article", request.article, request.id)
            when (result) {
                is ListeningResult.Ready -> {
                    val admittedWork = result.frame.workRef() ?: return
                    val material = materials.readSelection(call, admittedWork.selectionId, admittedWork.content, pendingSelection?.preference)
                    if (material !is LearningReply.Stored || !material.value.canListen) {
                        presentation.update { it.copy(selectionPending = true, notice = "선택한 기사의 자료를 확인하고 있어요. 준비되면 같은 선택을 다시 확인할 수 있어요") }; return
                    }
                    val resume = listening.selectResume(call)
                    val frame = if (resume is ListeningResult.Ready) resume.frame else result.frame
                    val ref = frame.workRef() ?: return
                    if (!coordinator.admitListening(RepositoryCompletion(ticket, ref), frame.checkpoint!!.payload.positionMs, true)) return
                    pendingSelection = null
                    presentation.update { it.copy(selectionPending = false, noCandidate = null, reader = null, article = null,
                        resumeFrame = frame, notice = if (resume is ListeningResult.Ready) null else "이어서 듣기 저장을 다시 확인해 주세요") }
                    loadContent()
                    if (coordinator.state.value.navigationGeneration == ticket.navigationGeneration && coordinator.state.value.intentGeneration == ticket.intentGeneration)
                        play(call.owner, true, selectCurrent = true, operationTicket = ticket, expectedSelection = ref.selectionId)
                }
                is ListeningResult.NoCandidate -> {
                    pendingSelection = null
                    presentation.update { it.copy(selectionPending = false, noCandidate = result.reason, notice = null) }
                    coordinator.navigate(call.owner, SessionScreen.NEWS)
                }
                is ListeningResult.Failed -> presentation.update { it.copy(selectionPending = true, notice = failure(result.code)) }
                else -> presentation.update { it.copy(selectionPending = true, notice = "기사 선택 상태를 다시 확인해 주세요") }
            }
        } finally { coordinator.finishOperation(ticket) }
    }

    private fun nextGuard(pending: PendingReview2Selection): PlaybackActivationGuard {
        val view = coordinator.state.value
        val samePresentation = pending.navigationGeneration == view.navigationGeneration && pendingReview2Selection === pending
        val format = presentation.value.profile?.settings?.listeningMode?.let { ListeningMode.valueOf(it.uppercase()) }
        return PlaybackActivationGuard(view.owner, if (samePresentation) pending.binding.contextToken else null,
            reviewNext2.processToken, System.nanoTime() / 1_000_000, java.time.Instant.now(),
            view.access.ownerValid && view.access.power == PowerAvailability.ALLOWED && view.task == null &&
                (view.access.detailsOpen || pending.viaVoice && view.access.audioAllowsReply && gleoRuntime != null),
            sameArticleAndFormat = samePresentation && format == pending.binding.listeningMode)
    }

    private fun nextGuard(pending: PendingReview3Selection): PlaybackActivationGuard {
        val view = coordinator.state.value
        val samePresentation = pending.navigationGeneration == view.navigationGeneration && pendingReview3Selection === pending
        val format = presentation.value.profile?.settings?.listeningMode?.let { ListeningMode.valueOf(it.uppercase()) }
        return PlaybackActivationGuard(view.owner, if (samePresentation) pending.binding.contextToken else null,
            reviewNext3.processToken, System.nanoTime() / 1_000_000, java.time.Instant.now(),
            view.access.ownerValid && view.access.power == PowerAvailability.ALLOWED && view.task == null &&
                (view.access.detailsOpen || pending.viaVoice && view.access.audioAllowsReply && gleoRuntime != null),
            sameArticleAndFormat = samePresentation && (pending.request.selectionOverride != null || format == pending.binding.listeningMode))
    }

    private suspend fun revokePendingActivation() {
        val call = profiles.current() ?: return
        // A system restriction revokes live delivery, while retaining the pending anchor for
        // a later explicit choice. Explicit Stop/cancel/navigation uses cancelPendingSelection.
        pendingReview3Selection?.takeIf { it.owner == call.owner }?.let { pending ->
            if (reviewNext3.state.value.intent?.state == PlaybackRequestState.ACTIVE) reviewNext3.restore(call, pending.binding.operationId)
        }
        pendingReview2Selection?.takeIf { it.owner == call.owner }?.let { pending ->
            if (reviewNext2.state.value.intent?.state == PlaybackRequestState.ACTIVE) reviewNext2.restore(call, pending.binding.operationId)
        }
    }

    private suspend fun cancelPendingSelection() {
        val old = pendingReview2Selection
        val current = pendingReview3Selection
        val retained = reviewNext3.state.value.retainedParentAnchor
        val pendingOperation = pendingActionOperation()
        pendingReview2Selection = null
        pendingReview3Selection = null
        presentation.update { it.copy(selectionPending = false) }
        val call = profiles.current() ?: return
        if (old != null || current != null || retained != null) coordinator.invalidateMediaActivation(call.owner)
        val operations = setOfNotNull(current?.binding?.operationId, old?.binding?.operationId, pendingOperation, retained?.parentOperationId)
        for (operation in operations) when (reviewDao.operation(operation.toString())?.kind) {
            "review3_next" -> reviewNext3.cancel(call, operation)
            "review2_next" -> { reviewNext3.cancel(call, operation); reviewNext2.clear() }
        }
    }

    private suspend fun selectPreparedArticle(article: UUID?, fresh: Boolean, retry: Boolean, viaVoice: Boolean,
        requestedSnapshotId: UUID? = null, originView: CatalogView? = null, automatic: Boolean = false, readyAlternative: Boolean = false) {
        if (retry) { retryPendingListening(viaVoice); return }
        val began = System.nanoTime() / 1_000_000
        val call = profiles.current() ?: return
        val before = coordinator.snapshot()
        if (before.task != null || !before.access.ownerValid || before.access.power != PowerAvailability.ALLOWED ||
            !before.access.detailsOpen && !(viaVoice && before.access.audioAllowsReply && gleoRuntime != null)) return
        if (presentation.value.profile?.settings?.onboardingComplete != true) return
        val existing = pendingReview3Selection
        if (!automatic && !coordinator.invalidateMediaActivation(call.owner)) return
        if (existing != null || pendingReview2Selection != null || reviewNext3.state.value.retainedParentAnchor != null) cancelPendingSelection()
        val operationId = UUID.randomUUID()
        val ticket = coordinator.beginOperation(SessionOperationKind.SELECTION, call.owner, operationId) ?: return
        try {
            if (preferences.prepareNextSelection(call) !is LearningReply.Stored) {
                presentation.update { it.copy(notice = "다음 기사에 적용할 단계를 먼저 확인해 주세요") }; return
            }
            if (before.preparedListening != null && !preparedRuntime.hold(PauseReason.USER, preservePlayIntent = true)) return
            if (before.listening != null && !freezeAndSave(call.owner)) return
            var snapshot = (source6Listening.snapshot(call) as? LearningReply.Stored)?.value ?: return
            val oldStream = presentation.value.autoProgress?.autoProgressId ?: before.reviewPolicy.context?.selectionId?.let { selectionId ->
                snapshot.records.filterIsInstance<WireSource6PersonalReadSelection>().singleOrNull { it.recordId == selectionId.toString() }?.payload?.streamId
            } ?: (before.preparedListening?.streamId ?: before.listening?.streamId)?.toString()
            if (!fresh && presentation.value.autoProgress != null && presentation.value.autoProgressHead == null) {
                presentation.update { it.copy(selectionPending = false, notice = "이 자동 진행의 마지막 선택을 확인하지 못했어요. 저장된 작업을 이어 듣거나 ‘새 뉴스 듣기’를 직접 선택해 주세요") }; return
            }
            var stream = if (fresh) null else snapshot.records.filterIsInstance<WireSource6PersonalReadStream>().singleOrNull { it.recordId == oldStream }
            val newStream = stream == null
            if (newStream) {
                if (automatic || readyAlternative) { presentation.update { it.copy(notice = "새 뉴스 듣기를 직접 시작해 주세요") }; return }
                val id = UUID.nameUUIDFromBytes("prepared-stream:$operationId".toByteArray())
                snapshot = (source6Listening.createStream(call, operationId, id, java.time.Instant.now()) as? LearningReply.Stored)?.value ?: return
                stream = snapshot.records.filterIsInstance<WireSource6PersonalReadStream>().singleOrNull { it.recordId == id.toString() } ?: return
            }
            val targetSnapshot = requestedSnapshotId ?: article?.let { wanted ->
                presentation.value.preparedCatalog.singleOrNull { it.articleId == wanted.toString() }?.sourceSnapshotId?.let(UUID::fromString)
            }
            if (article != null && targetSnapshot == null) {
                presentation.update { it.copy(notice = "이 기사의 원문 판을 확인해 주세요. 듣던 진행은 유지돼요") }; return
            }
            val kind = when { newStream -> PlaybackRequestKind.EXPLICIT_START; article != null -> PlaybackRequestKind.EXPLICIT_ARTICLE
                readyAlternative -> PlaybackRequestKind.READY_ALTERNATIVE; automatic -> PlaybackRequestKind.AUTO_NEXT; else -> PlaybackRequestKind.EXPLICIT_NEXT }
            val channel = if (viaVoice) PlaybackRequestChannel.GLEO else PlaybackRequestChannel.APP
            val mode = ListeningMode.valueOf(snapshot.profile.settings.listeningMode.uppercase())
            val binding = reviewNext3.newBinding(call.owner, operationId, UUID.randomUUID(), kind, channel, article, targetSnapshot, mode,
                catalogViews.returnToken(before.navigationGeneration, article?.let { id -> targetSnapshot?.let { CatalogArticleKey(id, it) } }))
                .copy(requestedAtElapsedMs = began, localDeadlineElapsedMs = began + channel.budgetMs)
            val selectedStream = requireNotNull(stream)
            val request = WireReview3ReviewNextRequest3("6", operationId.toString(),
                WireReview3Source6CommonProfileContext(call.owner.profileId.toString(), call.owner.privacyEpoch), selectedStream.recordId,
                selectedStream.version, if (newStream) null else presentation.value.autoProgressHead ?: selectedStream.payload.currentSelectionId, snapshot.profile.settings.version,
                UUID.nameUUIDFromBytes("prepared-checkpoint:$operationId".toByteArray()).toString(),
                if (article == null) "automatic_unheard" else "explicit_article", article?.toString(), if (automatic) "auto_offer" else "explicit",
                snapshot.profile.resumeVersion, 3, kind.name.lowercase(), originView?.name?.lowercase(), targetSnapshot?.toString(), channel.name.lowercase(), null)
            if (coordinator.state.value.intentGeneration != ticket.intentGeneration || coordinator.state.value.navigationGeneration != ticket.navigationGeneration) return
            val pending = PendingReview3Selection(call.owner, binding, request, before.navigationGeneration, viaVoice)
            pendingReview3Selection = pending
            presentation.update { it.copy(selectionPending = true, notice = null) }
            reviewNext3.submit(call, request, binding, nextGuard(pending))
            finishReview3Selection(call, pending, ticket)
        } finally { coordinator.finishOperation(ticket) }
    }

    private fun originalOutputConfigured(): Boolean = originalTtsReview?.languageEvidence?.containsKey(SegmentLanguage.EN) == true &&
        context.checkSelfPermission("pleos.car.permission.TTS_SERVICE") == android.content.pm.PackageManager.PERMISSION_GRANTED &&
        ((productVisible && window.visible) || originalTtsReview?.backgroundEvidence != null) &&
        runCatching { Class.forName("ai.pleos.playground.tts.TextToSpeech") }.isSuccess

    private fun pendingChoiceContextOperation(): UUID? = pendingReview3Selection?.binding?.operationId
        ?: pendingReview2Selection?.binding?.operationId
        ?: reviewNext3.state.value.takeIf { it.retainedParentAnchor != null && SessionOperationKind.SELECTION !in coordinator.state.value.activeOperations }?.let {
            it.pendingChoiceRetention?.childOperationId ?: it.intent?.binding?.operationId ?: it.retainedParentAnchor?.parentOperationId
        }

    private fun pendingActionOperation(): UUID? = pendingReview3Selection?.binding?.operationId
        ?: pendingReview2Selection?.binding?.operationId
        ?: reviewNext3.state.value.takeIf { it.retainedParentAnchor != null || presentation.value.selectionPending &&
            it.response?.outcomeProjection?.state in setOf("selected", "resumed") }?.let { it.intent?.binding?.operationId ?: it.retainedParentAnchor?.parentOperationId }

    private suspend fun retryPendingListening(viaVoice: Boolean) {
        val call = profiles.current() ?: return
        val pending = pendingReview3Selection?.takeIf { it.owner == call.owner }
        val live = reviewNext3.state.value.intent
        if (pending != null && live?.state == PlaybackRequestState.ACTIVE && pending.binding.processToken == reviewNext3.processToken &&
            System.nanoTime() / 1_000_000 < pending.binding.localDeadlineElapsedMs && nextGuard(pending).outputAllowed) {
            val ticket = coordinator.beginOperation(SessionOperationKind.SELECTION, call.owner, pending.binding.operationId) ?: return
            try {
                reviewNext3.observe(call, pending.binding.operationId, nextGuard(pending))
                finishReview3Selection(call, pending, ticket)
            } finally { coordinator.finishOperation(ticket) }
            return
        }
        val old = pendingReview2Selection?.takeIf { it.owner == call.owner }
        if (old != null && reviewNext2.state.value.intent?.state == PlaybackRequestState.ACTIVE && old.binding.processToken == reviewNext2.processToken &&
            System.nanoTime() / 1_000_000 < old.binding.localDeadlineElapsedMs && nextGuard(old).outputAllowed) {
            selectPreparedArticle2(null, false, true, viaVoice)
            return
        }
        choosePendingChoice(PendingChoiceKind.CONTINUE, viaVoice,
            statusOnly = !reviewNext3.supportsPendingChoice(call, PendingChoiceKind.CONTINUE))
    }

    /** Reads an already committed selection into the current view; never requests output/navigation. */
    private suspend fun restoreConfirmedListeningState(call: LearningCallScope, operationId: UUID): Boolean {
        val response3 = reviewNext3.state.value.takeIf { it.owner == call.owner }?.response
            ?.takeIf { it.operationId == operationId.toString() && it.outcomeProjection.state in setOf("selected", "resumed") }
        val response2 = reviewNext2.state.value.takeIf { it.owner == call.owner }?.response
            ?.takeIf { it.operationId == operationId.toString() && it.outcomeProjection.state in setOf("selected", "resumed") }
        val selected = response3?.outcomeProjection?.selection?.selectionId ?: response2?.outcomeProjection?.selection?.selectionId ?: return false
        val point = response3?.outcomeProjection?.checkpoint?.recordId ?: response2?.outcomeProjection?.checkpoint?.recordId ?: return false
        val snapshot = (source6Listening.snapshot(call) as? LearningReply.Stored)?.value ?: return false
        if (snapshot.profile.activeResume?.kind != "listening" || snapshot.profile.activeResume?.recordId != point) return false
        val legacy = if (response3 != null) legacyReviewNext.prepare(call, snapshot, response3) else legacyReviewNext.prepare(call, snapshot, requireNotNull(response2))
        if (legacy is LegacyReviewAdmission.Ready) {
            if (!legacyReviewNext.attachReady(legacy)) return false
            val work = legacy.frame.workRef() ?: return false
            val ticket = coordinator.beginOperation(SessionOperationKind.RESTORE, call.owner) ?: return false
            try {
                if (!coordinator.admitListening(RepositoryCompletion(ticket, work), requireNotNull(legacy.frame.checkpoint).payload.positionMs, false)) return false
                readCurrentReviewContext(call, selected)?.let { coordinator.bindReviewContext(call.owner, it, true) }
                presentation.update { it.copy(resumeFrame = legacy.frame, preparedSelection = null, preparedSource = null, preparedScript = null) }
            } finally { coordinator.finishOperation(ticket) }
        } else restorePreparedProfile(call)
        return (coordinator.state.value.preparedListening?.selectionId ?: coordinator.state.value.listening?.selectionId)?.toString() == selected
    }

    private suspend fun choosePendingOriginal(viaVoice: Boolean) = choosePendingChoice(PendingChoiceKind.ORIGINAL, viaVoice)

    /** All three explicit choices retain the fixed B parent and its actual target progress. */
    private suspend fun choosePendingChoice(kind: PendingChoiceKind, viaVoice: Boolean, statusOnly: Boolean = false) {
        val began = System.nanoTime() / 1_000_000
        val call = profiles.current() ?: return
        val before = coordinator.snapshot()
        if (!before.access.ownerValid || before.access.power != PowerAvailability.ALLOWED || before.task != null ||
            !before.access.detailsOpen && !(viaVoice && before.access.audioAllowsReply && gleoRuntime != null)) return
        val actionOperation = pendingActionOperation() ?: return
        val old = pendingReview2Selection
        val current = pendingReview3Selection
        pendingReview3Selection = null
        pendingReview2Selection = null
        if (!coordinator.invalidateMediaActivation(call.owner)) return
        val operation = UUID.randomUUID()
        val ticket = coordinator.beginOperation(SessionOperationKind.SELECTION, call.owner, operation) ?: return
        presentation.update { it.copy(selectionPending = true, notice = "선택한 기사의 상태를 확인하고 있어요") }
        try {
            // Revocation is local and immediate; never send server cancel before resolving the B anchor.
            if (current != null) reviewNext3.restore(call, current.binding.operationId)
            if (old != null) reviewNext2.restore(call, old.binding.operationId)
            var retention = reviewNext3.pendingChoiceRetention(call, actionOperation)
            if (retention == null && reviewDao.operation(actionOperation.toString())?.kind == "review3_next" &&
                reviewNext3.state.value.response == null) {
                val observed = reviewNext3.observeStateOnly(call, actionOperation)
                if (observed is LearningReply.Failed && observed.code == "review_operation_not_admitted") {
                    reviewNext3.cancel(call, actionOperation)
                    presentation.update { it.copy(selectionPending = false, notice = "선택이 접수되지 않았어요. 새 뉴스 듣기로 다시 시작할 수 있어요") }
                    return
                }
                retention = reviewNext3.pendingChoiceRetention(call, actionOperation)
            }
            val cachedSelected = reviewNext3.state.value.response?.let { it.operationId == actionOperation.toString() && it.outcomeProjection.state in setOf("selected", "resumed") } == true
            if (retention?.reconciliation == PendingChoiceReconciliation.SAME_CHILD_REQUIRED || cachedSelected) {
                val child = retention?.childOperationId ?: actionOperation
                reviewNext3.observeStateOnly(call, child)
                if (restoreConfirmedListeningState(call, child)) {
                    presentation.update { it.copy(selectionPending = false, notice = "저장된 선택을 확인했어요. 재생을 직접 눌러 주세요") }
                    return
                }
                retention = reviewNext3.pendingChoiceRetention(call, actionOperation)
                if (retention?.reconciliation == PendingChoiceReconciliation.SAME_CHILD_REQUIRED) {
                    presentation.update { it.copy(notice = "원래 선택의 접수 상태를 다시 확인해 주세요. 새 선택이나 재생은 시작하지 않았어요") }
                    return
                }
            }
            var anchor = retention?.anchor ?: reviewNext3.retainedPendingAnchor(call, actionOperation)
            if (anchor == null) {
                if (!restoreConfirmedListeningState(call, actionOperation)) presentation.update { it.copy(notice = "최근 저장된 작업을 먼저 확인해 주세요. 듣던 기록은 유지돼요") }
                return
            }
            if (retention?.reconciliation == PendingChoiceReconciliation.HELD_PARENT_REFRESH ||
                statusOnly || !reviewNext3.supportsPendingChoice(call, kind)) {
                val observed = reviewNext3.observePendingParentStateOnly(call, anchor.parentOperationId)
                if (observed !is LearningReply.Stored) {
                    if (restoreConfirmedListeningState(call, actionOperation)) presentation.update { it.copy(selectionPending = false, notice = "저장된 선택을 확인했어요. 재생을 직접 눌러 주세요") }
                    else presentation.update { it.copy(notice = (observed as? LearningReply.Failed)?.code?.let(::failure) ?: "같은 기사의 상태를 다시 확인해 주세요") }
                    return
                }
                anchor = observed.value
            }
            if (statusOnly) {
                presentation.update { it.copy(notice = "상태를 확인했어요. 계속 기다리려면 ‘다시 기다리기’를 선택해 주세요") }
                return
            }
            if (!reviewNext3.supportsPendingChoice(call, kind)) {
                presentation.update { it.copy(notice = "이 선택의 연결 지원을 확인하지 못했어요. 듣던 기록은 유지돼요") }
                return
            }
            if (coordinator.state.value.intentGeneration != ticket.intentGeneration || coordinator.state.value.navigationGeneration != ticket.navigationGeneration) return
            anchor = reviewNext3.beginPendingInput(call, anchor.parentOperationId, operation, kind) ?: return
            val target = anchor.pendingTarget
            val channel = if (viaVoice) PlaybackRequestChannel.GLEO else PlaybackRequestChannel.APP
            val targetArticle = if (kind == PendingChoiceKind.READY_ALTERNATIVE) null else target?.articleId?.let(UUID::fromString) ?: anchor.requestedArticleId
            val targetSnapshot = if (kind == PendingChoiceKind.READY_ALTERNATIVE) null else target?.sourceSnapshotId?.let(UUID::fromString) ?: anchor.requestedSourceSnapshotId
            val binding = reviewNext3.newBinding(call.owner, operation, UUID.randomUUID(), anchor.childKind(kind), channel, targetArticle, targetSnapshot,
                if (kind == PendingChoiceKind.ORIGINAL) ListeningMode.ORIGINAL_FULL else anchor.parentListeningMode,
                pendingOriginalParentOperationId = anchor.parentOperationId, pendingChoiceKind = kind)
                .copy(requestedAtElapsedMs = began, localDeadlineElapsedMs = began + channel.budgetMs)
            if (kind == PendingChoiceKind.ORIGINAL && (!originalOutputConfigured() ||
                    originalTtsReview?.mode != PleosTtsMode.ON_DEVICE && originalTtsRegistration() == null)) {
                presentation.update { it.copy(notice = "차량의 원문 낭독 연결을 먼저 확인해 주세요. 듣던 기록은 유지돼요") }
                return
            }
            if (before.preparedListening != null && !preparedRuntime.hold(PauseReason.USER, preservePlayIntent = true) ||
                before.listening != null && !freezeAndSave(call.owner)) {
                presentation.update { it.copy(notice = "듣던 위치를 저장하지 못했어요. 같은 기록을 확인한 뒤 다시 선택해 주세요") }
                return
            }
            if (kind == PendingChoiceKind.ORIGINAL && target != null) {
                val remaining = binding.localDeadlineElapsedMs - System.nanoTime() / 1_000_000
                val original = if (remaining > 0) withTimeoutOrNull(remaining) { preparedCatalog.inspectPendingOriginal(call, target) } else null
                if (original !is CatalogRead.Stored || profiles.current()?.owner != call.owner) {
                    presentation.update { it.copy(notice = "선택한 기사의 원문과 낭독 구간을 확인하지 못했어요. 듣던 기록은 유지돼요") }
                    return
                }
            }
            val snapshot = (source6Listening.snapshot(call) as? LearningReply.Stored)?.value ?: return
            val stream = snapshot.records.filterIsInstance<WireSource6PersonalReadStream>().singleOrNull { it.recordId == anchor.targetStreamId.toString() } ?: return
            val progress = target?.progressHead
            val currentProgress = if (progress != null) progress.currentAutoProgressId else presentation.value.autoProgress?.autoProgressId
            val currentHead = if (progress != null) progress.currentSelectionId else presentation.value.autoProgressHead
            if (currentProgress != null && currentHead == null) {
                presentation.update { it.copy(notice = "현재 진행의 마지막 선택을 확인하지 못했어요. 저장된 작업을 다시 확인해 주세요") }
                return
            }
            if (System.nanoTime() / 1_000_000 >= binding.localDeadlineElapsedMs || profiles.current()?.owner != call.owner ||
                coordinator.state.value.intentGeneration != ticket.intentGeneration || coordinator.state.value.navigationGeneration != ticket.navigationGeneration) {
                presentation.update { it.copy(notice = "대기 시간이 끝났어요. 원할 때 다시 선택해 주세요") }
                return
            }
            val request = WireReview3ReviewNextRequest3("6", operation.toString(), WireReview3Source6CommonProfileContext(call.owner.profileId.toString(), call.owner.privacyEpoch),
                stream.recordId, stream.version, currentHead, snapshot.profile.settings.version, UUID.nameUUIDFromBytes("prepared-checkpoint:$operation".toByteArray()).toString(),
                if (kind == PendingChoiceKind.READY_ALTERNATIVE) "automatic_unheard" else anchor.selectionKind, targetArticle?.toString(), "explicit", snapshot.profile.resumeVersion, 3,
                anchor.childKind(kind).name.lowercase(), anchor.originView, targetSnapshot?.toString(), channel.name.lowercase(),
                WireReview3SelectionOverride3(kind.wire, anchor.parentOperationId.toString(), anchor.selectionIntentId.toString(), anchor.requestHash, anchor.outcomeVersion, currentProgress))
            val pending = PendingReview3Selection(call.owner, binding, request, before.navigationGeneration, viaVoice)
            pendingReview3Selection = pending
            reviewNext3.submit(call, request, binding, nextGuard(pending))
            finishReview3Selection(call, pending, ticket)
        } finally { coordinator.finishOperation(ticket) }
    }

    private suspend fun selectPreparedArticle2(article: UUID?, fresh: Boolean, retry: Boolean, viaVoice: Boolean,
        requestedSnapshotId: UUID? = null, originView: CatalogView? = null, automatic: Boolean = false, readyAlternative: Boolean = false) {
        val began = System.nanoTime() / 1_000_000
        val call = profiles.current() ?: return
        val before = coordinator.snapshot()
        if (before.task != null || !before.access.ownerValid || before.access.power != PowerAvailability.ALLOWED ||
            !before.access.detailsOpen && !(viaVoice && before.access.audioAllowsReply && gleoRuntime != null)) return
        if (presentation.value.profile?.settings?.onboardingComplete != true) return
        val existing = pendingReview2Selection
        if (retry && existing != null && existing.owner == call.owner) {
            val live = reviewNext2.state.value.intent
            if (live?.state == PlaybackRequestState.ACTIVE && nextGuard(existing).elapsedMs < existing.binding.localDeadlineElapsedMs) {
                val ticket = coordinator.beginOperation(SessionOperationKind.SELECTION, call.owner, existing.binding.operationId) ?: return
                try {
                    reviewNext2.observe(call, existing.binding.operationId, nextGuard(existing))
                    finishReview2Selection(call, existing, ticket)
                } finally { coordinator.finishOperation(ticket) }
                return
            }
            // This button is a new explicit input after the old window expired. Preserve
            // its original target, but never extend/rewrite its immutable request.
            cancelPendingSelection()
            selectPreparedArticle2(existing.request.requestedArticleId?.let(UUID::fromString),
                existing.binding.kind == PlaybackRequestKind.EXPLICIT_START, false, viaVoice,
                existing.request.requestedSourceSnapshotId?.let(UUID::fromString),
                existing.request.originView?.let { CatalogView.valueOf(it.uppercase()) })
            return
        }
        if (!automatic && !coordinator.invalidateMediaActivation(call.owner)) return
        if (existing != null) cancelPendingSelection()
        val operationId = UUID.randomUUID()
        val ticket = coordinator.beginOperation(SessionOperationKind.SELECTION, call.owner, operationId) ?: return
        try {
            if (preferences.prepareNextSelection(call) !is LearningReply.Stored) {
                presentation.update { it.copy(notice = "다음 기사에 적용할 단계를 먼저 확인해 주세요") }; return
            }
            if (before.preparedListening != null && !preparedRuntime.hold(PauseReason.USER, preservePlayIntent = true)) return
            if (before.listening != null && !freezeAndSave(call.owner)) return
            var snapshot = (source6Listening.snapshot(call) as? LearningReply.Stored)?.value ?: return
            val oldStream = presentation.value.autoProgress?.autoProgressId ?: before.reviewPolicy.context?.selectionId?.let { selectionId ->
                snapshot.records.filterIsInstance<WireSource6PersonalReadSelection>().singleOrNull { it.recordId == selectionId.toString() }?.payload?.streamId
            } ?: (before.preparedListening?.streamId ?: before.listening?.streamId)?.toString()
            if (!fresh && presentation.value.autoProgress != null && presentation.value.autoProgressHead == null) {
                presentation.update { it.copy(selectionPending = false, notice = "이 자동 진행의 마지막 선택을 확인하지 못했어요. 저장된 작업을 이어 듣거나 ‘새 뉴스 듣기’를 직접 선택해 주세요") }; return
            }
            var stream = if (fresh) null else snapshot.records.filterIsInstance<WireSource6PersonalReadStream>().singleOrNull { it.recordId == oldStream }
            val newStream = stream == null
            if (newStream) {
                if (automatic || readyAlternative) { presentation.update { it.copy(notice = "새 뉴스 듣기를 직접 시작해 주세요") }; return }
                val id = UUID.nameUUIDFromBytes("prepared-stream:$operationId".toByteArray())
                snapshot = (source6Listening.createStream(call, operationId, id, java.time.Instant.now()) as? LearningReply.Stored)?.value ?: return
                stream = snapshot.records.filterIsInstance<WireSource6PersonalReadStream>().singleOrNull { it.recordId == id.toString() } ?: return
            }
            val targetSnapshot = requestedSnapshotId ?: article?.let { wanted ->
                presentation.value.preparedCatalog.singleOrNull { it.articleId == wanted.toString() }?.sourceSnapshotId?.let(UUID::fromString)
            }
            if (article != null && targetSnapshot == null) {
                presentation.update { it.copy(notice = "이 기사의 원문 판을 확인해 주세요. 듣던 진행은 유지돼요") }; return
            }
            val kind = when { newStream -> PlaybackRequestKind.EXPLICIT_START; article != null -> PlaybackRequestKind.EXPLICIT_ARTICLE
                readyAlternative -> PlaybackRequestKind.READY_ALTERNATIVE; automatic -> PlaybackRequestKind.AUTO_NEXT; else -> PlaybackRequestKind.EXPLICIT_NEXT }
            val channel = if (viaVoice) PlaybackRequestChannel.GLEO else PlaybackRequestChannel.APP
            val mode = ListeningMode.valueOf(snapshot.profile.settings.listeningMode.uppercase())
            val binding = reviewNext2.newBinding(call.owner, operationId, UUID.randomUUID(), kind, channel, article, targetSnapshot, mode,
                catalogViews.returnToken(before.navigationGeneration, article?.let { id -> targetSnapshot?.let { CatalogArticleKey(id, it) } }))
                .copy(requestedAtElapsedMs = began, localDeadlineElapsedMs = began + channel.budgetMs)
            val selectedStream = requireNotNull(stream)
            val request = WireReview2ReviewNextRequest2("6", operationId.toString(),
                WireReview2Source6CommonProfileContext(call.owner.profileId.toString(), call.owner.privacyEpoch), selectedStream.recordId,
                selectedStream.version, if (newStream) null else presentation.value.autoProgressHead ?: selectedStream.payload.currentSelectionId, snapshot.profile.settings.version,
                UUID.nameUUIDFromBytes("prepared-checkpoint:$operationId".toByteArray()).toString(),
                if (article == null) "automatic_unheard" else "explicit_article", article?.toString(), if (automatic) "auto_offer" else "explicit",
                snapshot.profile.resumeVersion, 2, kind.name.lowercase(), originView?.name?.lowercase(), targetSnapshot?.toString(), channel.name.lowercase())
            if (coordinator.state.value.intentGeneration != ticket.intentGeneration || coordinator.state.value.navigationGeneration != ticket.navigationGeneration) return
            val pending = PendingReview2Selection(call.owner, binding, request, before.navigationGeneration, viaVoice)
            pendingReview2Selection = pending
            presentation.update { it.copy(selectionPending = true, notice = null) }
            reviewNext2.submit(call, request, binding, nextGuard(pending))
            finishReview2Selection(call, pending, ticket)
        } finally { coordinator.finishOperation(ticket) }
    }

    /** A legacy time cursor is admitted only with its actual original time/asset/intent facts. */
    private suspend fun finishLegacyReview2(call: LearningCallScope, pending: PendingReview2Selection,
        ticket: SessionOperationTicket, response: WireReview2ReviewNextResponse2): Boolean {
        if (response.outcomeProjection.checkpoint?.payload?.cursor !is WireReview2Source6LegacyTimeCursor6) return false
        val snapshot = (source6Listening.snapshot(call) as? LearningReply.Stored)?.value ?: run {
            presentation.update { it.copy(selectionPending = false, notice = "원래 재생 기록을 확인해 주세요. 저장된 위치는 유지돼요") }; return true
        }
        when (val prepared = legacyReviewNext.prepare(call, snapshot, response)) {
            is LegacyReviewAdmission.Unavailable -> {
                if (pendingReview2Selection === pending && profiles.current()?.owner == call.owner)
                    presentation.update { it.copy(selectionPending = false, notice = "예전 음원의 원래 위치·재생 정보를 확인하지 못했어요. 기존 기록에서 이어 듣거나 다시 듣기를 직접 선택해 주세요") }
            }
            is LegacyReviewAdmission.Ready -> {
                val claim = reviewNext2.claimActivation(call, pending.binding.operationId, nextGuard(pending)) ?: return true
                if (!legacyReviewNext.attachReady(prepared)) return true
                val ref = prepared.frame.workRef() ?: return true
                if (!coordinator.admitListening(RepositoryCompletion(ticket, ref), requireNotNull(prepared.frame.checkpoint).payload.positionMs, true)) return true
                val context = response.outcomeProjection.context?.let(::review2DomainContext)
                if (context != null) coordinator.bindReviewContext(call.owner, context, restored = response.outcomeProjection.state == "resumed")
                pendingReview2Selection = null
                presentation.update { it.copy(autoProgress = response.outcomeProjection.context?.autoProgressRef,
                    autoProgressHead = response.outcomeProjection.selection?.selectionId, selectionPending = false, noCandidate = null,
                    preparedSelection = null, preparedSource = null, preparedScript = null, reader = null, article = null, resumeFrame = prepared.frame) }
                loadContent()
                if (response.outcomeProjection.state == "selected" && prepared.canPlay &&
                    System.nanoTime() / 1_000_000 < pending.binding.localDeadlineElapsedMs &&
                    claim.intent.admission?.activationDeadlineAt?.let { java.time.Instant.now() < it } == true &&
                    coordinator.state.value.listening?.sameWork(ref) == true && profiles.current()?.owner == call.owner) play(call.owner, true, selectCurrent = true, operationTicket = ticket, expectedSelection = ref.selectionId)
            }
            LegacyReviewAdmission.NotLegacyTime -> return false
        }
        return true
    }

    private fun review2DomainContext(context: WireReview2ListeningContext2): ReviewContext {
        val value = incrementalCodecs.parse("review2", incrementalCodecs.encode("review2", "ListeningContext2", context, WireReview2ListeningContext2.serializer())).jsonObject
        return incrementalCodecs.decodeReview("ListeningContext", kotlinx.serialization.json.JsonObject(value - "auto_progress_ref").toString(), WireReviewListeningContext.serializer()).domainReview()
    }

    private suspend fun finishReview2Selection(call: LearningCallScope, pending: PendingReview2Selection, ticket: SessionOperationTicket) {
        if (pendingReview2Selection !== pending || coordinator.state.value.navigationGeneration != pending.navigationGeneration ||
            coordinator.state.value.intentGeneration != ticket.intentGeneration || profiles.current()?.owner != call.owner) return
        val result = reviewNext2.state.value.takeIf { it.owner == call.owner && it.intent?.binding?.operationId == pending.binding.operationId } ?: return
        when (result.response?.outcomeProjection?.state) {
            "no_candidate" -> {
                if (pendingReview2Selection === pending) pendingReview2Selection = null
                presentation.update { it.copy(selectionPending = false, noCandidate = result.response?.outcomeProjection?.noCandidateReason) }
            }
            "requires_explicit_replay" -> presentation.update { it.copy(selectionPending = false,
                notice = "이미 끝까지 들은 기사예요. 기록에서 다시 듣기를 직접 선택해 주세요") }
            "selected", "resumed" -> {
                val actualResponse = result.response ?: return
                if (finishLegacyReview2(call, pending, ticket, actualResponse)) return
                val activation = reviewNext2.claimActivation(call, pending.binding.operationId, nextGuard(pending))
                if (activation == null) {
                    presentation.update { it.copy(selectionPending = false, notice = "준비 상태를 저장했어요. 재생하려면 직접 다시 선택해 주세요") }; return
                }
                val outcome = activation.response.outcomeProjection
                val selection = incrementalCodecs.decodeSource6("PersonalSelectionRecord", incrementalCodecs.encode("review2", "Source6PersonalSelectionRecord",
                    requireNotNull(outcome.selection), WireReview2Source6PersonalSelectionRecord.serializer()), WireSource6PersonalSelectionRecord.serializer())
                val checkpoint = incrementalCodecs.decodeSource6("PersonalReadCheckpoint", incrementalCodecs.encode("review2", "Source6PersonalReadCheckpoint",
                    requireNotNull(outcome.checkpoint), WireReview2Source6PersonalReadCheckpoint.serializer()), WireSource6PersonalReadCheckpoint.serializer())
                val cursor = checkpoint.payload.cursor as? WireSource6SegmentCursor6 ?: return
                val work = PreparedListeningWorkRef(UUID.fromString(selection.streamId), UUID.fromString(selection.selectionId), UUID.fromString(checkpoint.recordId),
                    selection.contentRef.domainSegmentRef(), cursor.toSegmentCursor(), checkpoint.version, checkpoint.payload.activeRunId?.let(UUID::fromString))
                val context = review2DomainContext(requireNotNull(outcome.context))
                val resumed = outcome.state == "resumed"
                if (!coordinator.admitPreparedListening(RepositoryCompletion(ticket, work), true, context, restored = resumed)) return
                pendingReview2Selection = null
                presentation.update { it.copy(autoProgress = outcome.context?.autoProgressRef, autoProgressHead = outcome.selection?.selectionId, preparedSelection = selection, preparedSource = it.preparedCatalog.singleOrNull { row -> row.articleId == work.content.articleId.toString() && row.sourceSnapshotId == work.content.sourceSnapshotId.toString() },
                    preparedScript = null, reader = null, article = null, selectionPending = false, noCandidate = null) }
                val loaded = loadPreparedContent()
                val withinOriginalWindow = System.nanoTime() / 1_000_000 < pending.binding.localDeadlineElapsedMs &&
                    activation.intent.admission?.activationDeadlineAt?.let { java.time.Instant.now() < it } == true
                // Resuming the same article preserves its saved play/pause/manual intent.
                if (loaded && withinOriginalWindow && !resumed && coordinator.state.value.preparedListening?.sameWork(work) == true && profiles.current()?.owner == call.owner) play(call.owner, true, operationTicket = ticket, expectedSelection = work.selectionId)
                else if (!withinOriginalWindow) presentation.update { it.copy(notice = "기사는 준비됐어요. 재생을 직접 눌러 주세요") }
            }
            "pending" -> presentation.update { it.copy(selectionPending = true, notice = "자료를 준비하고 있어요. 같은 요청을 다시 확인하거나 다른 준비된 뉴스를 선택할 수 있어요") }
            "expired_waiting_explicit" -> presentation.update { it.copy(selectionPending = true, notice = "대기 시간이 끝났어요. 준비 상태는 보존돼요. 원할 때 다시 요청해 주세요") }
            else -> presentation.update { it.copy(selectionPending = true, notice = result.errorCode?.let(::failure) ?: "기사 선택 상태를 다시 확인해 주세요") }
        }
    }

    /** A legacy time cursor is admitted only with its actual original time/asset/intent facts. */
    private suspend fun finishLegacyReview3(call: LearningCallScope, pending: PendingReview3Selection,
        ticket: SessionOperationTicket, response: WireReview3ReviewNextResponse3): Boolean {
        if (response.outcomeProjection.checkpoint?.payload?.cursor !is WireReview3Source6LegacyTimeCursor6) return false
        val snapshot = (source6Listening.snapshot(call) as? LearningReply.Stored)?.value ?: run {
            presentation.update { it.copy(selectionPending = false, notice = "원래 재생 기록을 확인해 주세요. 저장된 위치는 유지돼요") }; return true
        }
        when (val prepared = legacyReviewNext.prepare(call, snapshot, response)) {
            is LegacyReviewAdmission.Unavailable -> {
                if (pendingReview3Selection === pending && profiles.current()?.owner == call.owner)
                    presentation.update { it.copy(selectionPending = false, notice = "예전 음원의 원래 위치·재생 정보를 확인하지 못했어요. 기존 기록에서 이어 듣거나 다시 듣기를 직접 선택해 주세요") }
            }
            is LegacyReviewAdmission.Ready -> {
                val claim = reviewNext3.claimActivation(call, pending.binding.operationId, nextGuard(pending)) ?: return true
                if (!legacyReviewNext.attachReady(prepared)) return true
                val ref = prepared.frame.workRef() ?: return true
                if (!coordinator.admitListening(RepositoryCompletion(ticket, ref), requireNotNull(prepared.frame.checkpoint).payload.positionMs, true)) return true
                val context = response.outcomeProjection.context?.let(::review3DomainContext)
                if (context != null) coordinator.bindReviewContext(call.owner, context, restored = response.outcomeProjection.state == "resumed")
                pendingReview3Selection = null
                presentation.update { it.copy(autoProgress = response.outcomeProjection.context?.autoProgressRef?.let { WireReview2AutoProgressRef(it.autoProgressId, it.version) },
                    autoProgressHead = response.outcomeProjection.selection?.selectionId, selectionPending = false, noCandidate = null,
                    preparedSelection = null, preparedSource = null, preparedScript = null, reader = null, article = null, resumeFrame = prepared.frame) }
                loadContent()
                if (response.outcomeProjection.state == "selected" && prepared.canPlay &&
                    System.nanoTime() / 1_000_000 < pending.binding.localDeadlineElapsedMs &&
                    claim.intent.admission?.activationDeadlineAt?.let { java.time.Instant.now() < it } == true &&
                    coordinator.state.value.listening?.sameWork(ref) == true && profiles.current()?.owner == call.owner) play(call.owner, true, selectCurrent = true, operationTicket = ticket, expectedSelection = ref.selectionId)
            }
            LegacyReviewAdmission.NotLegacyTime -> return false
        }
        return true
    }

    private fun review3DomainContext(context: WireReview3ListeningContext2): ReviewContext {
        val value = incrementalCodecs.parse("review3", incrementalCodecs.encode("review3", "ListeningContext2", context, WireReview3ListeningContext2.serializer())).jsonObject
        return incrementalCodecs.decodeReview("ListeningContext", kotlinx.serialization.json.JsonObject(value - "auto_progress_ref").toString(), WireReviewListeningContext.serializer()).domainReview()
    }

    private suspend fun finishReview3Selection(call: LearningCallScope, pending: PendingReview3Selection, ticket: SessionOperationTicket) {
        if (pendingReview3Selection !== pending || coordinator.state.value.navigationGeneration != pending.navigationGeneration ||
            coordinator.state.value.intentGeneration != ticket.intentGeneration || profiles.current()?.owner != call.owner) return
        val result = reviewNext3.state.value.takeIf { it.owner == call.owner && it.intent?.binding?.operationId == pending.binding.operationId } ?: return
        when (result.response?.outcomeProjection?.state) {
            "no_candidate" -> {
                if (pendingReview3Selection === pending) pendingReview3Selection = null
                presentation.update { it.copy(selectionPending = result.retainedParentAnchor != null,
                    noCandidate = result.response?.outcomeProjection?.noCandidateReason) }
            }
            "requires_explicit_replay" -> {
                pendingReview3Selection = null
                presentation.update { it.copy(selectionPending = false, notice = "이미 끝까지 들은 기사예요. 기록에서 다시 듣기를 직접 선택해 주세요") }
            }
            "selected", "resumed" -> {
                val actualResponse = result.response ?: return
                if (finishLegacyReview3(call, pending, ticket, actualResponse)) return
                val activation = reviewNext3.claimActivation(call, pending.binding.operationId, nextGuard(pending))
                if (activation == null) {
                    presentation.update { it.copy(selectionPending = false, notice = "준비 상태를 저장했어요. 재생하려면 직접 다시 선택해 주세요") }; return
                }
                val outcome = activation.response.outcomeProjection
                val selection = incrementalCodecs.decodeSource6("PersonalSelectionRecord", incrementalCodecs.encode("review3", "Source6PersonalSelectionRecord",
                    requireNotNull(outcome.selection), WireReview3Source6PersonalSelectionRecord.serializer()), WireSource6PersonalSelectionRecord.serializer())
                val checkpoint = incrementalCodecs.decodeSource6("PersonalReadCheckpoint", incrementalCodecs.encode("review3", "Source6PersonalReadCheckpoint",
                    requireNotNull(outcome.checkpoint), WireReview3Source6PersonalReadCheckpoint.serializer()), WireSource6PersonalReadCheckpoint.serializer())
                val cursor = checkpoint.payload.cursor as? WireSource6SegmentCursor6 ?: return
                val work = PreparedListeningWorkRef(UUID.fromString(selection.streamId), UUID.fromString(selection.selectionId), UUID.fromString(checkpoint.recordId),
                    selection.contentRef.domainSegmentRef(), cursor.toSegmentCursor(), checkpoint.version, checkpoint.payload.activeRunId?.let(UUID::fromString))
                val context = review3DomainContext(requireNotNull(outcome.context))
                val resumed = outcome.state == "resumed"
                if (!coordinator.admitPreparedListening(RepositoryCompletion(ticket, work), true, context, restored = resumed)) return
                pendingReview3Selection = null
                presentation.update { it.copy(autoProgress = outcome.context?.autoProgressRef?.let { WireReview2AutoProgressRef(it.autoProgressId, it.version) }, autoProgressHead = outcome.selection?.selectionId, preparedSelection = selection, preparedSource = it.preparedCatalog.singleOrNull { row -> row.articleId == work.content.articleId.toString() && row.sourceSnapshotId == work.content.sourceSnapshotId.toString() },
                    preparedScript = null, reader = null, article = null, selectionPending = false, noCandidate = null) }
                val loaded = loadPreparedContent()
                val withinOriginalWindow = System.nanoTime() / 1_000_000 < pending.binding.localDeadlineElapsedMs &&
                    activation.intent.admission?.activationDeadlineAt?.let { java.time.Instant.now() < it } == true
                // Resuming the same article preserves its saved play/pause/manual intent.
                if (loaded && withinOriginalWindow && !resumed && coordinator.state.value.preparedListening?.sameWork(work) == true && profiles.current()?.owner == call.owner) play(call.owner, true, operationTicket = ticket, expectedSelection = work.selectionId)
                else if (!withinOriginalWindow) presentation.update { it.copy(notice = "기사는 준비됐어요. 재생을 직접 눌러 주세요") }
            }
            "pending" -> presentation.update { it.copy(selectionPending = true, notice = "자료를 준비하고 있어요. 같은 요청을 다시 확인하거나 다른 준비된 뉴스를 선택할 수 있어요") }
            "expired_waiting_explicit" -> presentation.update { it.copy(selectionPending = true,
                notice = if (result.response?.outcomeProjection?.sourceStatus?.any { source -> source.status in setOf("failed", "unavailable") } == true)
                    "자료 준비를 마치지 못했어요. 확인된 원문이나 다른 준비 뉴스를 선택할 수 있어요"
                else "대기 시간이 끝났어요. 같은 기사에서 다시 기다리거나 다른 선택을 할 수 있어요") }
            else -> presentation.update { it.copy(selectionPending = true, notice = result.errorCode?.let(::failure) ?: "기사 선택 상태를 다시 확인해 주세요") }
        }
    }

    private suspend fun loadPreparedContent(): Boolean {
        val view = coordinator.snapshot(); val call = profiles.current()?.takeIf { it.owner == view.owner } ?: return false
        val work = view.preparedListening ?: return false
        val selection = presentation.value.preparedSelection ?: return false
        val script = if (work.stage == ListeningStage.FOLLOWUP) preparedCatalog.followup(call, work.content.source6Wire(), selection.settingsSnapshot.followupPolicy ?: "none")
            else preparedCatalog.main(call, work.content.source6Wire())
        if (script !is CatalogRead.Stored) { presentation.update { it.copy(readerStatus = LoadStatus.FAILED, notice = "이 구간의 자료를 아직 이용할 수 없어요. 저장된 진행은 유지돼요") }; return false }
        if (coordinator.state.value.preparedListening?.sameWork(work) != true || profiles.current()?.owner != call.owner) return false
        presentation.update { it.copy(preparedScript = script.value, readerStatus = LoadStatus.READY) }
        if (work.stage == ListeningStage.ARTICLE_OFFER) return true
        return preparedRuntime.attach(call.owner, work, script.value)
    }

    private suspend fun preparedPhaseEnded(run: SegmentRun, skipFollowup: Boolean = false) {
        val view = coordinator.snapshot(); val work = view.preparedListening ?: return
        val call = profiles.current()?.takeIf { it.owner == run.owner && it.owner == view.owner } ?: return
        val phaseView = view
        val current = preparedRuntime.state.value.snapshot ?: pendingPreparedSnapshot ?: return
        if (current.activeRequest != null || current.runId != run.runId || !work.accepts(current.program)) return
        pendingPreparedSnapshot = current
        val selection = presentation.value.preparedSelection ?: return
        val followup = selection.settingsSnapshot.followupPolicy ?: "none"
        val needsFollowup = !skipFollowup && run.phase == SegmentPhase.MAIN && followup != "none" && !(work.content.listeningMode == ListeningMode.BRIEFING && followup == "en_summary")
        val nextScript = if (needsFollowup) (preparedCatalog.followup(call, work.content.source6Wire(), followup) as? CatalogRead.Stored)?.value else null
        if (needsFollowup && nextScript?.manifest == null) {
            pendingPreparedPhase = run
            presentation.update { it.copy(followupWaiting = true, notice = "후속 듣기를 준비하고 있어요. 준비된 뒤 다시 확인하거나 후속 듣기를 건너뛸 수 있어요") }; return
        }
        val manifest = nextScript?.manifest
        var nextCursor = if (needsFollowup && manifest != null) PlaybackCursor6.Segment(UUID.fromString(manifest.scriptId), manifest.scriptHash,
            UUID.fromString(manifest.manifestId), manifest.segmentsVersion.toInt(), manifest.manifestHash, SegmentPhase.FOLLOWUP,
            UUID.fromString(manifest.segments.first().segmentId), 0, phaseView.intent == PlaybackIntent.PLAY, PauseReason.NONE)
            else current.cursor.copy(phase = SegmentPhase.ARTICLE_OFFER, segmentId = null, segmentIndex = null, playIntent = false, pauseReason = PauseReason.NONE)
        pendingPreparedPhase = run
        preparedRuntime.detach()
        var receipt = preparedRuntime.checkpoints.cursor(SegmentCursorWrite(UUID.nameUUIDFromBytes("phase:${run.runId}:${nextCursor.phase}".toByteArray()), call.owner,
            work.selectionId, work.content.source6Wire(), nextCursor.toWireSegmentCursor(), if (run.phase == SegmentPhase.MAIN) SegmentPurpose.MAIN else SegmentPurpose.FOLLOWUP)) ?: return
        val afterWrite = coordinator.snapshot()
        if (afterWrite.owner != call.owner || afterWrite.preparedListening?.selectionId != work.selectionId) return
        if (nextCursor.playIntent && (afterWrite.intentGeneration != phaseView.intentGeneration || afterWrite.intent != PlaybackIntent.PLAY)) {
            nextCursor = nextCursor.copy(playIntent = false, pauseReason = PauseReason.USER)
            receipt = preparedRuntime.checkpoints.cursor(SegmentCursorWrite(UUID.nameUUIDFromBytes("phase-pause:${run.runId}:${afterWrite.intentGeneration}".toByteArray()), call.owner,
                work.selectionId, work.content.source6Wire(), nextCursor.toWireSegmentCursor(), if (run.phase == SegmentPhase.MAIN) SegmentPurpose.MAIN else SegmentPurpose.FOLLOWUP)) ?: return
        }
        pendingPreparedPhase = null; pendingPreparedSnapshot = null
        presentation.update { it.copy(followupWaiting = false, notice = null) }
        val updated = work.copy(cursor = nextCursor, checkpointVersion = receipt.version, runId = receipt.payload.activeRunId?.let(UUID::fromString))
        val ticket = coordinator.beginOperation(SessionOperationKind.RESTORE, call.owner) ?: return
        if (!coordinator.admitPreparedListening(RepositoryCompletion(ticket, updated), false)) { coordinator.finishOperation(ticket); return }
        val context = reviewRepository.read(call, work.selectionId.toString())
        if (context is LearningReply.Stored) coordinator.bindReviewContext(call.owner, context.value.context.domainReview(), false)
        if (needsFollowup && nextScript != null) {
            presentation.update { it.copy(preparedScript = nextScript) }
            if (preparedRuntime.attach(call.owner, updated, nextScript) && nextCursor.playIntent) play(call.owner, true,
                operationTicket = ticket.copy(intentGeneration = phaseView.intentGeneration), expectedSelection = updated.selectionId, guardNavigation = false)
        } else { preparedRuntime.detach(); learning.announceArticleOffer() }
    }

    private suspend fun play(owner: OwnerContext, wanted: Boolean, selectCurrent: Boolean = false,
        operationTicket: SessionOperationTicket? = null, expectedSelection: UUID? = null, guardNavigation: Boolean = true) {
        val before = coordinator.snapshot()
        val generation = operationTicket?.intentGeneration ?: before.intentGeneration
        val selection = expectedSelection ?: before.preparedListening?.selectionId ?: before.listening?.selectionId
        if (!coordinator.requestMediaIntent(owner, if (wanted) PlaybackIntent.PLAY else PlaybackIntent.USER_PAUSED,
                operationTicket?.navigationGeneration?.takeIf { guardNavigation }, generation, selection)) return
        if (coordinator.state.value.preparedListening != null) {
            if (wanted && selection != null) preparedRuntime.play(owner, selection, generation + 1) else preparedRuntime.hold(PauseReason.USER)
            return
        }
        val media = controller ?: return
        if (selectCurrent) media.setMediaItem(MediaItem.Builder().setMediaId(NewsMediaService.CURRENT).build())
        if (wanted) media.play() else media.pause()
    }

    private suspend fun changeFormat(mode: ListeningMode, viaVoice: Boolean = false) {
        val view = coordinator.snapshot()
        if (!view.access.detailsOpen && !(viaVoice && view.access.ownerValid && view.access.power == PowerAvailability.ALLOWED &&
                view.access.audioAllowsReply && gleoRuntime != null)) return
        if (view.preparedListening != null) { changePreparedFormat(mode); return }
        val call = profiles.current() ?: return; val frame = listening.snapshot() ?: return
        val source = frame.selection ?: return; val current = point(frame) ?: return
        val sourceMaterial = materials.state.value.frozen?.takeIf { it.selectionId.toString() == source.selectionId && it.owner == call.owner }
        if (pendingSelection?.owner == call.owner) { presentation.update { it.copy(notice = "기사 선택 결과를 먼저 확인해 주세요") }; return }
        val request = pendingFormat?.takeIf { it.owner == call.owner } ?: PendingFormatChange(call.owner, UUID.randomUUID(), mode)
        if (!coordinator.invalidateMediaActivation(call.owner)) return
        val ticket = coordinator.beginOperation(SessionOperationKind.SELECTION, call.owner, request.id) ?: return
        pendingFormat = request
        presentation.update { it.copy(formatPendingMode = request.mode) }
        try {
            when (val result = listening.changeFormat(call, source, current, requireNotNull(frame.checkpoint).version, request.mode, ticket.id)) {
                is ListeningResult.Ready -> {
                    val ref = result.frame.workRef() ?: return
                    if (sourceMaterial != null) {
                        val material = materials.readFormatChange(call, sourceMaterial, ref.selectionId, ref.content)
                        if (material !is LearningReply.Stored || !material.value.canListen) { presentation.update { it.copy(notice = "같은 기사의 단계 자료를 다시 확인해 주세요") }; return }
                    }
                    val resume = listening.selectResume(call, UUID.nameUUIDFromBytes("format-resume:${request.id}".toByteArray()))
                    val frame = (resume as? ListeningResult.Ready)?.frame ?: result.frame
                    val review = readCurrentReviewContext(call, ref.selectionId.toString(), request.id)
                    if (coordinator.admitListening(RepositoryCompletion(ticket, ref), 0, true)) {
                        if (review != null) coordinator.bindReviewContext(call.owner, review, false)
                        pendingFormat = null
                        presentation.update { it.copy(reader = null, article = null, resumeFrame = frame,
                            notice = if (resume is ListeningResult.Ready) null else "형식은 바뀌었어요. 이어서 듣기 저장을 다시 확인해 주세요", formatPendingMode = null) }
                        loadContent()
                        if (resume is ListeningResult.Ready && view.intent == PlaybackIntent.PLAY && coordinator.state.value.intentGeneration == ticket.intentGeneration)
                            play(call.owner, true, selectCurrent = true, operationTicket = ticket, expectedSelection = ref.selectionId)
                    }
                }
                is ListeningResult.Failed -> presentation.update { it.copy(notice = failure(result.code)) }
                else -> Unit
            }
        } finally { coordinator.finishOperation(ticket) }
    }

    private suspend fun changePreparedFormat(mode: ListeningMode) {
        val call = profiles.current() ?: return; val view = coordinator.snapshot(); val work = view.preparedListening ?: return
        if (view.task != null || pendingPreparedSelection != null) return
        if (!coordinator.invalidateMediaActivation(call.owner)) return
        val ticket = coordinator.beginOperation(SessionOperationKind.SELECTION, call.owner, pendingPreparedFormat?.operationId?.let(UUID::fromString) ?: UUID.randomUUID()) ?: return
        try {
            if (pendingPreparedFormat == null) {
                if (!preparedRuntime.hold(PauseReason.USER, preservePlayIntent = true)) return
                val snapshot = (source6Listening.snapshot(call) as? LearningReply.Stored)?.value ?: return
                val stream = snapshot.records.filterIsInstance<WireSource6PersonalReadStream>().singleOrNull { it.recordId == work.streamId.toString() } ?: return
                val checkpoint = snapshot.records.filterIsInstance<WireSource6PersonalReadCheckpoint>().singleOrNull { it.recordId == work.checkpointId.toString() } ?: return
                pendingPreparedFormat = WireSource6PersonalFormatChangeRequest("6", ticket.id.toString(), call.owner.source6Profile(), work.streamId.toString(), stream.version,
                    requireNotNull(stream.payload.currentSelectionId), work.selectionId.toString(), checkpoint.recordId, checkpoint.version, UUID.randomUUID().toString(), mode.name.lowercase())
            }
            val request = requireNotNull(pendingPreparedFormat)
            presentation.update { it.copy(formatPendingMode = mode) }
            when (val response = source6Listening.changeFormat(call, request)) {
                is LearningReply.Failed -> presentation.update { it.copy(notice = failure(response.code)) }
                is LearningReply.Stored -> {
                    val selection = response.value.selection; val checkpoint = response.value.checkpoint
                    val ref = selection.contentRef
                    val cursor = checkpoint.payload.cursor as? WireSource6SegmentCursor6 ?: return
                    val changed = PreparedListeningWorkRef(work.streamId, UUID.fromString(selection.selectionId), UUID.fromString(checkpoint.recordId), ref.domainSegmentRef(), cursor.toSegmentCursor(), checkpoint.version)
                    if (source6Listening.resumeListening(call, UUID.nameUUIDFromBytes("format-resume:${request.operationId}".toByteArray()),
                            UUID.fromString(checkpoint.recordId)) !is LearningReply.Stored) return
                    val review = readCurrentReviewContext(call, selection.selectionId, UUID.fromString(request.operationId))
                    if (!coordinator.admitPreparedListening(RepositoryCompletion(ticket, changed), true, review)) return
                    pendingPreparedFormat = null
                    presentation.update { it.copy(preparedSelection = selection, preparedScript = null, formatPendingMode = null, notice = null) }
                    if (loadPreparedContent() && view.intent == PlaybackIntent.PLAY) play(call.owner, true, operationTicket = ticket, expectedSelection = changed.selectionId)
                }
            }
        } finally { coordinator.finishOperation(ticket) }
    }

    private suspend fun replayHistory(selectionId: UUID, checkpointId: UUID, mode: String?) {
        val call = profiles.current() ?: return; val view = coordinator.snapshot()
        if (!view.access.detailsOpen || view.task != null) return
        if (!coordinator.invalidateMediaActivation(call.owner)) return
        val ticket = coordinator.beginOperation(SessionOperationKind.RESTORE, call.owner) ?: return
        try {
            val snapshot = (source6Listening.snapshot(call) as? LearningReply.Stored)?.value ?: return
            if (view.preparedListening != null && !preparedRuntime.hold(PauseReason.USER, preservePlayIntent = true)) return
            if (view.listening != null && !freezeAndSave(call.owner)) return
            val request = WireReviewHistoryReplayRequest(1, call.owner.profileId.toString(), call.owner.privacyEpoch, ticket.id.toString(),
                selectionId.toString(), checkpointId.toString(), snapshot.profile.resumeVersion, mode)
            val result = reviewRepository.replay(call, request) as? LearningReply.Stored ?: run {
                presentation.update { it.copy(notice = "당시 자료를 지금 이용할 수 없어요. 기록은 유지돼요") }; return
            }
            val after = (source6Listening.snapshot(call) as? LearningReply.Stored)?.value ?: return
            val checkpoint = after.records.filterIsInstance<WireSource6PersonalReadCheckpoint>().singleOrNull { it.recordId == result.value.checkpointId } ?: return
            val selection = after.records.filterIsInstance<WireSource6PersonalReadSelection>().singleOrNull { it.recordId == result.value.selectionId }?.payload ?: return
            val ref = checkpoint.payload.contentRef
            val cursor = checkpoint.payload.cursor as? WireSource6SegmentCursor6 ?: return
            val work = PreparedListeningWorkRef(UUID.fromString(selection.streamId), UUID.fromString(selection.selectionId), UUID.fromString(checkpoint.recordId), ref.domainSegmentRef(), cursor.toSegmentCursor(), checkpoint.version)
            if (!coordinator.admitPreparedListening(RepositoryCompletion(ticket, work), true, result.value.context.domainReview())) return
            presentation.update { it.copy(preparedSelection = selection, preparedScript = null,
                preparedSource = it.preparedCatalog.singleOrNull { row -> row.articleId == ref.domainSegmentRef().articleId.toString() && (ref !is WireSource6PreparedContentRef6 || row.sourceHash == ref.sourceHash) }, notice = null) }
            if (loadPreparedContent()) play(call.owner, true, operationTicket = ticket, expectedSelection = work.selectionId)
        } finally { coordinator.finishOperation(ticket) }
    }

    private suspend fun loadContent(language: ReaderLanguage? = null) {
        val view = coordinator.snapshot(); val owner = view.owner ?: return
        if (!view.access.detailsOpen) return
        if (view.preparedListening != null) { loadPreparedContent(); return }
        val frame = listening.snapshot() ?: return; val work = frame.workRef() ?: return
        val ticket = coordinator.beginOperation(SessionOperationKind.READER, owner) ?: return
        presentation.update { it.copy(readerStatus = LoadStatus.LOADING) }
        try {
            val script = if (work.stage == ListeningStage.ARTICLE_OFFER) catalog.readMain(work.content) else catalog.readCheckpointScript(frame)
            val sourceUnavailable = script is CatalogRead.Unavailable && script.code == "CONTENT_UNAVAILABLE"
            val actual = (script as? CatalogRead.Stored)?.value
            val displayed = when {
                actual == null -> script
                language == null || (language == ReaderLanguage.KOREAN) == (actual.role.language == "ko") -> script
                language == ReaderLanguage.KOREAN && work.stage == ListeningStage.MAIN -> catalog.readFollowup(work.content,
                    if (work.content.listeningMode == ListeningMode.ORIGINAL_FULL) CatalogScriptRole.ORIGINAL_KO_TRANSLATION else CatalogScriptRole.BRIEFING_KO_TRANSLATION)
                else -> CatalogRead.Unavailable("MATCHING_SCRIPT_UNAVAILABLE", false)
            }
            val metadata = (catalog.catalogPage(0, 1, work.content.articleId) as? CatalogRead.Stored)?.value
                ?.singleOrNull { it.bundleId == work.content.bundleId }
            if (!coordinator.finishOperation(ticket, true)) return
            if (sourceUnavailable) {
                controller?.pause()
                presentation.update { it.copy(reader = null, article = null, readerStatus = LoadStatus.FAILED,
                    notice = "이 기사를 지금 이용할 수 없어요. 저장된 학습 상태를 다시 확인해 주세요") }
                return
            }
            when (displayed) {
                is CatalogRead.Stored -> presentation.update { it.copy(article = metadata ?: it.article,
                    reader = ReaderPresentation(displayed.value, if (displayed.value.role.language == "ko") ReaderLanguage.KOREAN else ReaderLanguage.ENGLISH), readerStatus = LoadStatus.READY) }
                is CatalogRead.Unavailable -> presentation.update { it.copy(article = metadata ?: it.article,
                    readerStatus = if (it.reader == null) LoadStatus.FAILED else LoadStatus.READY,
                    notice = if (language != null) "이 단계에 대응하는 대본을 아직 확인하지 못했어요" else failure(displayed.code)) }
            }
        } finally { coordinator.finishOperation(ticket, true) }
    }

    private suspend fun advanceAfterEnd(key: NewsPlaybackKey) {
        val view = coordinator.snapshot(); val call = profiles.current() ?: return
        if (call.owner != key.owner || view.listening?.accepts(key) != true) return
        val ticket = coordinator.beginOperation(SessionOperationKind.SELECTION, call.owner) ?: return
        try {
            val frame = listening.snapshot() ?: return
            val followup = if (key.phase == "main") {
                val policy = frame.selection?.settingsSnapshot?.followupPolicy
                val chosen = when (policy) { "en_summary" -> Followup.EN_SUMMARY; "ko_summary" -> Followup.KO_SUMMARY;
                    "ko_translation" -> Followup.KO_TRANSLATION; "none" -> Followup.NONE; else -> return }
                val role = ListeningPolicy.followupRole(key.content.listeningMode, chosen)
                if (role == null) null else when (val read = catalog.readFollowup(key.content, CatalogScriptRole.entries.single { it.wire == role })) {
                    is CatalogRead.Stored -> read.value
                    is CatalogRead.Unavailable -> { presentation.update { it.copy(notice = "후속 듣기를 준비하지 못했어요. 듣던 기사는 유지돼요") }; return }
                }
            } else null
            val operation = pendingPhase?.takeIf { it.first == key }?.second ?: UUID.randomUUID().also { pendingPhase = key to it }
            when (val advanced = listening.advanceAudioPhase(call, followup, view.intent == PlaybackIntent.PLAY, operation)) {
                is ListeningResult.Ready -> {
                    val ref = advanced.frame.workRef() ?: return
                    if (!coordinator.admitListening(RepositoryCompletion(ticket, ref), 0, true)) return
                    pendingPhase = null
                    presentation.update { it.copy(reader = null, readerStatus = LoadStatus.NOT_REQUESTED, resumeFrame = advanced.frame,
                        notice = if (followup == null) "다음으로 할 일을 골라 주세요" else null) }
                    if (coordinator.state.value.access.detailsOpen) loadContent()
                    if (followup == null) learning.announceArticleOffer()
                }
                is ListeningResult.Failed -> presentation.update { it.copy(notice = failure(advanced.code)) }
                else -> Unit
            }
        } finally { coordinator.finishOperation(ticket) }
    }

    private fun restriction(view: SessionView): String? = when {
        view.ownerBinding != OwnerBindingStatus.VERIFIED -> "이 차량의 저장된 학습을 확인하고 있어요"
        view.access.power != PowerAvailability.ALLOWED -> "차량 상태 확인이 필요해요. 확인되면 대본과 학습 화면을 열 수 있어요"
        !view.access.safety.uxKnown -> "차량의 화면 사용 조건을 확인하지 못했어요"
        view.access.safety.position != VehiclePosition.PARKED -> "주차 후 대본과 학습 화면을 열 수 있어요"
        !view.access.window.visible -> "화면이 다시 보이면 이어서 이용할 수 있어요"
        else -> null
    }

    private fun failure(code: String): String = when (code) {
        "VERSION_MISMATCH", "RECORD_CONFLICT", "CHECKPOINT_CHANGED" -> "다른 곳에서 진행이 바뀌었어요. 현재 상태를 다시 확인해 주세요"
        "CONTENT_UNAVAILABLE" -> "이 기사를 지금 준비할 수 없어요. 다른 기사를 골라 주세요"
        "SESSION_CHANGED", "SESSION_UNAVAILABLE", "SESSION_RECOVERY_REQUIRED", "SESSION_REVOKED" -> "저장된 학습 연결을 다시 확인해 주세요"
        "SETTINGS_PENDING" -> "이전에 적용한 설정을 먼저 확인해 주세요"
        else -> "연결을 확인하지 못했어요. 저장한 진행은 유지되니 잠시 후 다시 확인해 주세요"
    }

    suspend fun canOpenSource(origin: UiOrigin, uri: java.net.URI): Boolean {
        val view = coordinator.snapshot()
        return origin.owner == view.owner && origin.navigationGeneration == view.navigationGeneration &&
            (origin.work?.sameWork(view.listening) == true && presentation.value.article?.originalUrl == uri ||
                origin.preparedWork?.sameWork(view.preparedListening) == true && presentation.value.preparedSource?.source?.originalUrl == uri.toString() ||
                presentation.value.preparedCatalog.any { it.source.originalUrl == uri.toString() } ||
                catalogViews.state.value.let { it.owner == view.owner && it.items.any { item -> item.originalUrl == uri.toString() } }) &&
            view.access.detailsOpen && view.access.window.focused && uri.scheme in setOf("https", "http")
    }
    fun sourceUnavailable() { presentation.update { it.copy(notice = "원문을 열 앱을 찾지 못했어요. 원문 주소를 확인해 주세요") } }
}

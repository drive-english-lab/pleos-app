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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Module
@InstallIn(SingletonComponent::class)
object ProductRuntimeModule {
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
    val resumeFrame: ListeningFrame? = null, val formatPendingMode: ListeningMode? = null)

private data class PendingFormatChange(val owner: OwnerContext, val id: UUID, val mode: ListeningMode)
private data class PendingNewsSelection(val owner: OwnerContext, val id: UUID, val article: UUID?, val fresh: Boolean, val prepared: Boolean = false)

/** Process composition only. Business navigation/identity stays in SessionCoordinator; repositories
 * retain canonical records. Every network call happens outside its reducer lock.
 */
@UnstableApi
@Singleton
class ProductSessionHost @Inject constructor(@param:ApplicationContext private val context: Context,
    database: LearningDatabase, val coordinator: SessionCoordinator, val appearances: DeviceAppearanceStore,
    private val vehicle: VehicleStateSource) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val profiles = ProfileRepository(context, database, BuildConfig.LEARNING_API_ORIGIN, BuildConfig.DEBUG)
    private val api = LearningApi(context, BuildConfig.LEARNING_API_ORIGIN, profiles, BuildConfig.DEBUG)
    private val catalog = CatalogRepository(context)
    private val mediaJournal = MediaPlaybackJournal(context, database)
    private val listening = ListeningRepository(context, api, database,
        isCurrent = { profiles.current()?.owner == it }, transition = ListeningTransitionPort { owner -> freeze(owner) })
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

    val state: StateFlow<ProductScreenState> = combine(coordinator.state, presentation, appearances.state) { session, data, theme ->
        val visible = if (data.owner == session.owner) data else ProductPresentation(themeDraft = theme)
        ProductScreenState(session = session, profileLabel = when (visible.profile?.profileKind) {
            "anonymous" -> "이 차량의 학습"; "linked" -> "내 학습"; else -> "기록 연결 확인 중"
        }, catalog = visible.catalog, catalogStatus = visible.catalogStatus, moreCatalog = visible.more,
            selectedArticle = visible.article, reader = if (session.access.detailsOpen) visible.reader else null,
            readerStatus = visible.readerStatus, draft = visible.draft, appearance = theme, appearanceDraft = visible.themeDraft,
            busy = session.activeOperations.any { it in setOf(SessionOperationKind.PROFILE, SessionOperationKind.SELECTION, SessionOperationKind.SETTINGS) },
            notice = visible.notice, recovery = visible.recovery ?: restriction(session), noCandidateReason = visible.noCandidate,
            settingsPending = visible.settingsPending, selectionPending = visible.selectionPending, formatPendingMode = visible.formatPendingMode)
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
        scope.launch {
            vehicle.start()
            launch { vehicle.state.collect { vehicleSnapshot = it; updateAccess() } }
            connectMedia()
            connectProfile()
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
        val ownerValid = coordinator.state.value.ownerBinding == OwnerBindingStatus.VERIFIED &&
            coordinator.state.value.owner?.let { profiles.current()?.owner == it } == true
        val previous = coordinator.state.value.access
        val visible = window.visible && productVisible
        val focused = window.focused && visible
        if (visible != projectedWindow.visible || focused != projectedWindow.focused)
            projectedWindow = WindowState(visible, focused, projectedWindow.generation + 1)
        coordinator.updateAccess(previous.copy(safety = vehicleSnapshot.safety, window = projectedWindow,
            power = vehicleSnapshot.effectivePower, ownerValid = ownerValid))
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
        return withContext(Dispatchers.Main.immediate) {
            val media = controller ?: return@withContext false
            withTimeoutOrNull(3_000) {
                await(media.sendCustomCommand(SessionCommand(NewsMediaService.FREEZE, Bundle.EMPTY), Bundle.EMPTY)).resultCode == SessionResult.RESULT_SUCCESS
            } == true
        }
    }

    suspend fun handle(intent: UiIntent) {
        val view = coordinator.snapshot()
        if (intent.origin.owner != view.owner) return
        val sameWork = intent.origin.work == null && view.listening == null || intent.origin.work?.sameWork(view.listening) == true
        val currentPresentation = intent.origin.navigationGeneration == view.navigationGeneration && sameWork
        if (!currentPresentation && intent !is UiIntent.Stop && !(intent is UiIntent.PlayPause && !intent.wantPlay)) return
        try {
            when (intent) {
                is UiIntent.Refresh -> { if (controller == null) connectMedia(); connectProfile() }
                is UiIntent.Navigate -> {
                    if (intent.screen in setOf(SessionScreen.SETTINGS, SessionScreen.ONBOARDING)) {
                        val profile = presentation.value.profile ?: return
                        presentation.update { it.copy(draft = SettingsDraft(profile.settings), themeDraft = appearances.state.value, notice = null) }
                    }
                    coordinator.navigate(view.owner, intent.screen)
                    if (intent.screen in setOf(SessionScreen.PLAYER, SessionScreen.ARTICLE_INFO) && view.access.detailsOpen) loadContent()
                }
                is UiIntent.Topic -> editDraft { it.toggleTopic(intent.topic) }
                is UiIntent.Mode -> editDraft { it.mode(intent.mode) }
                is UiIntent.FollowupChoice -> editDraft { it.followup(intent.policy) }
                is UiIntent.Appearance -> if (view.access.detailsOpen) presentation.update { it.copy(themeDraft = intent.value) }
                is UiIntent.ApplySettings -> applySettings()
                is UiIntent.CancelSettings -> {
                    presentation.update { it.copy(draft = it.profile?.settings?.let(::SettingsDraft), themeDraft = appearances.state.value, notice = null) }
                    coordinator.navigate(view.owner, intent.destination ?: if (view.listening == null) SessionScreen.NEWS else SessionScreen.PLAYER)
                }
                is UiIntent.SelectArticle -> selectArticle(intent.articleId, intent.freshStream, retry = false)
                is UiIntent.RetrySelection -> selectArticle(null, false, retry = true)
                is UiIntent.Resume -> {
                    if (presentation.value.formatPendingMode != null) { presentation.update { it.copy(notice = "형식 변경 결과를 먼저 확인해 주세요") }; return }
                    val current = profiles.current() ?: return
                    if (coordinator.state.value.owner?.let { it != current.owner } == true) controller?.pause()
                    val frame = listening.snapshot()?.takeIf { it.checkpoint != null } ?: presentation.value.resumeFrame ?: return
                    listening.attach(frame.copy(owner = current.owner))
                    val ticket = coordinator.beginOperation(SessionOperationKind.RESTORE, current.owner) ?: return
                    coordinator.admitListening(RepositoryCompletion(ticket, requireNotNull(frame.workRef())), localPosition(frame), true)
                    loadContent()
                    play(current.owner, true)
                }
                is UiIntent.PlayPause -> view.owner?.let { play(it, intent.wantPlay) }
                is UiIntent.Stop -> {
                    view.owner?.let { coordinator.requestMediaIntent(it, PlaybackIntent.STOPPED) }
                    controller?.stop()
                    if (view.listening?.stage == ListeningStage.ARTICLE_OFFER) coordinator.navigate(view.owner, SessionScreen.NEWS)
                }
                is UiIntent.Seek -> {
                    val duration = view.listening?.durationMs ?: return
                    if (view.access.power == PowerAvailability.ALLOWED && view.mediaConnected)
                        controller?.seekTo(((view.mediaPositionMs ?: 0) + intent.offsetMs).coerceIn(0, duration))
                }
                is UiIntent.ReaderView -> if (view.access.detailsOpen) loadContent(intent.language)
                is UiIntent.ChangeFormat -> changeFormat(intent.mode)
                is UiIntent.MoreCatalog -> if (view.access.detailsOpen) loadCatalog(more = true)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { presentation.update { it.copy(notice = "저장한 진행은 유지돼요. 잠시 후 다시 확인해 주세요") } }
    }

    private fun editDraft(edit: (SettingsDraft) -> SettingsDraft) {
        if (!coordinator.state.value.access.detailsOpen) return
        presentation.update { value -> value.draft?.let(edit)?.takeIf { it.topics.size <= 20 }?.let { value.copy(draft = it, notice = null) } ?: value }
    }

    private suspend fun connectProfile() {
        val ticket = coordinator.beginOperation(SessionOperationKind.PROFILE, coordinator.state.value.owner) ?: return
        try {
            if (BuildConfig.LEARNING_API_ORIGIN.isBlank()) {
                presentation.update { it.copy(recovery = "학습 연결을 준비하고 있어요. 연결 점검에서 설정을 확인해 주세요") }; return
            }
            when (val reply = profiles.connect()) {
                is LearningReply.Failed -> presentation.update { it.copy(recovery = failure(reply.code)) }
                is LearningReply.Stored -> {
                    val current = profiles.current() ?: return
                    if (coordinator.state.value.owner?.let { it != current.owner } == true) controller?.pause()
                    if (!coordinator.bindVerifiedOwner(ticket, current.owner, reply.value.settings.onboardingComplete)) return
                    val previous = presentation.value
                    val next = if (previous.owner == current.owner) previous.copy(profile = reply.value,
                        draft = previous.draft?.takeIf { it.dirty } ?: SettingsDraft(reply.value.settings), recovery = null)
                    else ProductPresentation(owner = current.owner, profile = reply.value, draft = SettingsDraft(reply.value.settings), themeDraft = appearances.state.value)
                    presentation.value = next.copy(settingsPending = profiles.hasPendingSettings())
                    updateAccess()
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
                    loadCatalog(false)
                }
            }
        } finally { coordinator.finishOperation(ticket) }
    }

    private suspend fun loadCatalog(more: Boolean) {
        val owner = coordinator.state.value.owner ?: return
        val ticket = coordinator.beginOperation(SessionOperationKind.CATALOG, owner) ?: return
        val offset = if (more) presentation.value.offset else 0
        presentation.update { it.copy(catalogStatus = LoadStatus.LOADING) }
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
                    if (!appearances.save(current.themeDraft)) { presentation.update { it.copy(notice = "화면 설정을 저장하지 못했어요. 다시 적용해 주세요") }; return }
                    presentation.update { it.copy(notice = if (current.settingsPending) "현재 설정을 확인했어요. 변경할 항목을 다시 골라 주세요" else "설정을 적용했어요") }
                    if (!current.settingsPending && coordinator.state.value.navigationGeneration == ticket.navigationGeneration)
                        coordinator.navigate(owner, if (coordinator.state.value.listening == null) SessionScreen.NEWS else SessionScreen.PLAYER)
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

    private suspend fun selectArticle(article: UUID?, fresh: Boolean, retry: Boolean) {
        val view = coordinator.snapshot(); val call = profiles.current() ?: return
        if (!view.access.detailsOpen || presentation.value.profile?.settings?.onboardingComplete != true) return
        if (pendingFormat?.owner == call.owner) { presentation.update { it.copy(notice = "형식 변경 결과를 먼저 확인해 주세요") }; return }
        val saved = pendingSelection?.takeIf { it.owner == call.owner }
        if (!retry && saved != null) { presentation.update { it.copy(notice = "이전 기사 선택을 먼저 확인해 주세요", selectionPending = true) }; return }
        val request = if (retry) saved ?: return else PendingNewsSelection(call.owner, UUID.randomUUID(), article, fresh)
        val ticket = coordinator.beginOperation(SessionOperationKind.SELECTION, call.owner, request.id) ?: return
        try {
            pendingSelection = request
            if (!request.prepared) {
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
                pendingSelection = request.copy(prepared = true)
            }
            var result = if (retry) listening.retryNext(call, request.id)
                else listening.next(call, point(requireNotNull(listening.snapshot())), if (request.article == null) "automatic_unheard" else "explicit_article", request.article, request.id)
            if (result is ListeningResult.Failed && result.code == "NO_SAVED_REQUEST")
                result = listening.next(call, point(requireNotNull(listening.snapshot())), if (request.article == null) "automatic_unheard" else "explicit_article", request.article, request.id)
            when (result) {
                is ListeningResult.Ready -> {
                    val resume = listening.selectResume(call)
                    val frame = if (resume is ListeningResult.Ready) resume.frame else result.frame
                    val ref = frame.workRef() ?: return
                    if (!coordinator.admitListening(RepositoryCompletion(ticket, ref), frame.checkpoint!!.payload.positionMs, true)) return
                    pendingSelection = null
                    presentation.update { it.copy(selectionPending = false, noCandidate = null, reader = null, article = null,
                        resumeFrame = frame, notice = if (resume is ListeningResult.Ready) null else "이어서 듣기 저장을 다시 확인해 주세요") }
                    loadContent()
                    if (coordinator.state.value.navigationGeneration == ticket.navigationGeneration && coordinator.state.value.intentGeneration == ticket.intentGeneration)
                        play(call.owner, true, selectCurrent = true)
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

    private suspend fun play(owner: OwnerContext, wanted: Boolean, selectCurrent: Boolean = false) {
        if (!coordinator.requestMediaIntent(owner, if (wanted) PlaybackIntent.PLAY else PlaybackIntent.USER_PAUSED)) return
        val media = controller ?: return
        if (selectCurrent) media.setMediaItem(MediaItem.Builder().setMediaId(NewsMediaService.CURRENT).build())
        if (wanted) media.play() else media.pause()
    }

    private suspend fun changeFormat(mode: ListeningMode) {
        val view = coordinator.snapshot(); if (!view.access.detailsOpen) return
        val call = profiles.current() ?: return; val frame = listening.snapshot() ?: return
        val source = frame.selection ?: return; val current = point(frame) ?: return
        if (pendingSelection?.owner == call.owner) { presentation.update { it.copy(notice = "기사 선택 결과를 먼저 확인해 주세요") }; return }
        val request = pendingFormat?.takeIf { it.owner == call.owner } ?: PendingFormatChange(call.owner, UUID.randomUUID(), mode)
        val ticket = coordinator.beginOperation(SessionOperationKind.SELECTION, call.owner, request.id) ?: return
        pendingFormat = request
        presentation.update { it.copy(formatPendingMode = request.mode) }
        try {
            when (val result = listening.changeFormat(call, source, current, requireNotNull(frame.checkpoint).version, request.mode, ticket.id)) {
                is ListeningResult.Ready -> {
                    val ref = result.frame.workRef() ?: return
                    if (coordinator.admitListening(RepositoryCompletion(ticket, ref), 0, true)) {
                        pendingFormat = null
                        presentation.update { it.copy(reader = null, article = null, resumeFrame = result.frame, notice = null, formatPendingMode = null) }
                        loadContent()
                        if (view.intent == PlaybackIntent.PLAY && coordinator.state.value.intentGeneration == ticket.intentGeneration)
                            play(call.owner, true, selectCurrent = true)
                    }
                }
                is ListeningResult.Failed -> presentation.update { it.copy(notice = failure(result.code)) }
                else -> Unit
            }
        } finally { coordinator.finishOperation(ticket) }
    }

    private suspend fun loadContent(language: ReaderLanguage? = null) {
        val view = coordinator.snapshot(); val owner = view.owner ?: return
        if (!view.access.detailsOpen) return
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
            origin.work?.sameWork(view.listening) == true && view.access.detailsOpen && view.access.window.focused &&
            uri.scheme in setOf("https", "http") && presentation.value.article?.originalUrl == uri
    }
    fun sourceUnavailable() { presentation.update { it.copy(notice = "원문을 열 앱을 찾지 못했어요. 원문 주소를 확인해 주세요") } }
}

package io.github.driveenglishlab.pleos.ui

import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID

enum class LoadStatus { NOT_REQUESTED, LOADING, READY, FAILED }
enum class ReaderLanguage { ENGLISH, KOREAN }
data class ReaderPresentation(val script: PublishedScript, val language: ReaderLanguage)
data class UiOrigin(val owner: OwnerContext?, val work: ListeningWorkRef?, val navigationGeneration: Long)
data class ProductScreenState(val session: SessionView = SessionView(), val profileLabel: String = "기록 연결 확인 중",
    val catalog: List<CatalogArticle> = emptyList(), val catalogStatus: LoadStatus = LoadStatus.NOT_REQUESTED,
    val moreCatalog: Boolean = false, val selectedArticle: CatalogArticle? = null,
    val reader: ReaderPresentation? = null, val readerStatus: LoadStatus = LoadStatus.NOT_REQUESTED,
    val draft: SettingsDraft? = null, val appearance: DeviceAppearance = DeviceAppearance.SYSTEM,
    val appearanceDraft: DeviceAppearance = appearance, val busy: Boolean = false,
    val notice: String? = null, val recovery: String? = null, val noCandidateReason: String? = null,
    val settingsPending: Boolean = false, val selectionPending: Boolean = false,
    val formatPendingMode: ListeningMode? = null) {
    val origin get() = UiOrigin(session.owner, session.listening, session.navigationGeneration)
    val canRead get() = session.access.detailsOpen
    val mediaAllowed get() = session.ownerBinding == OwnerBindingStatus.VERIFIED && session.mediaConnected &&
        session.access.ownerValid && session.access.power == PowerAvailability.ALLOWED && session.listening != null
}

sealed interface UiIntent {
    val origin: UiOrigin
    data class Refresh(override val origin: UiOrigin) : UiIntent
    data class Navigate(override val origin: UiOrigin, val screen: SessionScreen) : UiIntent
    data class SelectArticle(override val origin: UiOrigin, val articleId: UUID?, val freshStream: Boolean = false) : UiIntent
    data class RetrySelection(override val origin: UiOrigin) : UiIntent
    data class Resume(override val origin: UiOrigin) : UiIntent
    data class PlayPause(override val origin: UiOrigin, val wantPlay: Boolean) : UiIntent
    data class Stop(override val origin: UiOrigin) : UiIntent
    data class Seek(override val origin: UiOrigin, val offsetMs: Long) : UiIntent
    data class ReaderView(override val origin: UiOrigin, val language: ReaderLanguage) : UiIntent
    data class Topic(override val origin: UiOrigin, val topic: String) : UiIntent
    data class Mode(override val origin: UiOrigin, val mode: String) : UiIntent
    data class FollowupChoice(override val origin: UiOrigin, val policy: String) : UiIntent
    data class Appearance(override val origin: UiOrigin, val value: DeviceAppearance) : UiIntent
    data class ApplySettings(override val origin: UiOrigin) : UiIntent
    data class CancelSettings(override val origin: UiOrigin, val destination: SessionScreen? = null) : UiIntent
    data class ChangeFormat(override val origin: UiOrigin, val mode: ListeningMode) : UiIntent
    data class MoreCatalog(override val origin: UiOrigin) : UiIntent
}

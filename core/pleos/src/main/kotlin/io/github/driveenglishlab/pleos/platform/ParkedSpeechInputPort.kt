package io.github.driveenglishlab.pleos.platform

import io.github.driveenglishlab.pleos.domain.AccessState
import io.github.driveenglishlab.pleos.domain.CaptureLease
import io.github.driveenglishlab.pleos.domain.OwnerContext
import kotlinx.coroutines.flow.StateFlow

/** Reviewed wording, not an SDK availability/locale claim. Never populated from a transcript. */
data class ParkedSpeechProcessingNotice(val text: String, val revision: String, val reviewRef: String) {
    init { require(listOf(text, revision, reviewRef).all(String::isNotBlank)) }
}

/** Installation/environment review supplied by trusted configuration; production defaults to null. */
data class ParkedSpeechRuntimeApproval(
    val environmentReviewRef: String,
    val retentionReviewRef: String,
    val arbitrationReviewRef: String,
    val processingNotice: ParkedSpeechProcessingNotice,
    val sdkVersion: String = "3.3.1",
    val originalEnglishReviewRef: String? = null,
    val literalFormReviewRef: String? = null,
) {
    init {
        require(sdkVersion == "3.3.1")
        require(listOf(environmentReviewRef, retentionReviewRef, arbitrationReviewRef).all(String::isNotBlank))
        require(listOfNotNull(originalEnglishReviewRef, literalFormReviewRef).all(String::isNotBlank))
    }
}

data class ParkedSpeechInputReadiness(
    val approved: Boolean = false,
    val focusGranted: Boolean = false,
    val listening: Boolean = false,
    val initializing: Boolean = false,
    val finalReady: Boolean = false,
    val cleanupUnknown: Boolean = false,
    val processingNotice: ParkedSpeechProcessingNotice? = null,
)

/** Calls and event consumption belong to the consumer's serialized main/event lane.
 * SDK I/O is asynchronous. A successful start only reserves a run, never accepts an answer.
 * Normal SDK cleanup preserves both generations through E1; cancel/release invalidates them.
 * Consumers must recheck their immutable lease again when consuming DirectSpeechEvent.Final. */
interface ParkedSpeechInputPort : AutoCloseable {
    val state: StateFlow<ParkedSpeechInputReadiness>
    val currentFocusGeneration: Long
    val currentCaptureGeneration: Long
    fun initialize()
    fun acquireFocus(owner: OwnerContext): Boolean
    fun acceptsEvent(owner: OwnerContext?, generation: Long): Boolean
    fun start(value: CaptureLease, access: AccessState): Boolean
    fun finish(value: CaptureLease)
    fun cancel()
    fun releaseFocus()
}

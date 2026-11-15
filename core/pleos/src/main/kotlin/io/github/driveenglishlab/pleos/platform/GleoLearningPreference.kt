package io.github.driveenglishlab.pleos.platform

import io.github.driveenglishlab.pleos.domain.*
import java.time.Instant
import java.util.UUID

/** Exact values in the existing PRACTICE function; free utterances never become these actions. */
enum class GleoLearningPreferenceCommand(val wire:String,val band:LearningBand?=null,val action:RecommendationAction?=null) {
    SET_BAND_BASIC("set_band_basic",LearningBand.BASIC),
    SET_BAND_INTERMEDIATE("set_band_intermediate",LearningBand.INTERMEDIATE),
    SET_BAND_ADVANCED("set_band_advanced",LearningBand.ADVANCED),
    READ_RECOMMENDATION("read_recommendation"),
    APPLY_RECOMMENDATION("apply_recommendation",action=RecommendationAction.APPLY),
    KEEP_RECOMMENDATION("keep_recommendation",action=RecommendationAction.KEEP),
    DISMISS_RECOMMENDATION("dismiss_recommendation",action=RecommendationAction.DISMISS);
    companion object {
        fun fromWire(value:String)=entries.firstOrNull{it.wire==value}
        val wires:Set<String> get()=entries.map{it.wire}.toSet()
    }
}

/** Original server evaluation and preference CAS, never a locally refreshed evaluation time. */
data class GleoRecommendationBasis(
    val profileId:UUID,val privacyEpoch:Long,
    val recommendationId:UUID,val recommendationVersion:Long,val recommendationRevision:Long,
    val evidenceRevision:Long,val basisSignature:String,val evaluatedAt:Instant,
    val preferenceVersion:Long,val preferenceChoiceId:UUID?,
) {
    init {
        require(privacyEpoch>=0 && recommendationVersion>=1 && recommendationRevision>=0 && evidenceRevision>=0 && preferenceVersion>=0)
        require(basisSignature.isNotBlank())
    }
    fun ownedBy(owner:OwnerContext)=profileId==owner.profileId && privacyEpoch==owner.privacyEpoch
}

/** Captured from the real InputStarted event and checked against its actual capture binding. */
data class GleoLearningPreferenceLease(
    val owner:OwnerContext,val inputId:UUID,val task:PracticeRef?,val lessonId:UUID?,
    val navigationGeneration:Long,val intentGeneration:Long,
    val listening:ListeningWorkRef?,val preparedListening:PreparedListeningWorkRef?,
    val modalToken:PracticeModalToken?,
    val gleoPermissionGeneration:Long=0,
) {
    fun matches(view:SessionView):Boolean=owner==view.owner && gleoPermissionGeneration==view.access.gleoPermissionGeneration && task==view.task && lessonId==view.lessonId &&
        navigationGeneration==view.navigationGeneration && intentGeneration==view.intentGeneration && modalToken==view.practiceModal?.token &&
        (listening==null && view.listening==null || listening?.sameWork(view.listening)==true) &&
        (preparedListening==null && view.preparedListening==null || preparedListening?.sameWork(view.preparedListening)==true)
    fun mayAct(view:SessionView):Boolean=matches(view) && view.access.gleoAllowed
}
data class GleoLearningPreferenceRequest(val command:GleoLearningPreferenceCommand,val lease:GleoLearningPreferenceLease,
    val recommendationBasis:GleoRecommendationBasis?=null)

/**
 * A project adapter must confirm actual delivery of this exact recommendation and the explicit
 * current user action. A returned callback string or a cached ready view is not such evidence.
 */
fun interface GleoRecommendationReviewPort {
    suspend fun permits(request:GleoLearningPreferenceRequest):Boolean
}

object GleoLearningPreferencePolicy {
    fun admits(extraCommandsVerified:Boolean,schemaVerified:Boolean,parametersVerified:Boolean,
        inputActivityVerified:Boolean,microphoneArbitrationVerified:Boolean,captureInputId:UUID?,
        lease:GleoLearningPreferenceLease?,current:SessionView):Boolean =
        extraCommandsVerified && schemaVerified && parametersVerified && inputActivityVerified && microphoneArbitrationVerified &&
            lease!=null && captureInputId==lease.inputId && lease.mayAct(current)

    fun requiresReviewedBasis(command:GleoLearningPreferenceCommand)=command.action!=null

    suspend fun admitsReviewedAction(request:GleoLearningPreferenceRequest,review:GleoRecommendationReviewPort?,
        currentBasis:suspend()->GleoRecommendationBasis?):Boolean {
        if(!requiresReviewedBasis(request.command))return false
        val expected=request.recommendationBasis?:return false
        if(!expected.ownedBy(request.lease.owner) || review?.permits(request)!=true)return false
        return currentBasis()==expected
    }
}

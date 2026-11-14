package io.github.driveenglishlab.pleos.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.driveenglishlab.pleos.domain.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GleoLearningPreferenceTest {
    private val owner=OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID())
    private val input=UUID.randomUUID()
    private val lease=GleoLearningPreferenceLease(owner,input,null,null,4,8,null,null,null)
    private val view=SessionView(owner=owner,navigationGeneration=4,intentGeneration=8,
        access=AccessState(ownerValid=true,power=PowerAvailability.ALLOWED,audioAllowsReply=true))
    private fun admits(extra:Boolean=true,schema:Boolean=true,parameters:Boolean=true,inputVerified:Boolean=true,
        arbitration:Boolean=true,capture:UUID?=input,current:SessionView=view)=GleoLearningPreferencePolicy.admits(
        extra,schema,parameters,inputVerified,arbitration,capture,lease,current)
    private fun basis()=GleoRecommendationBasis(owner.profileId,owner.privacyEpoch,UUID.randomUUID(),1,7,7,"test-only-reviewed-basis",
        Instant.parse("2026-11-15T00:00:00Z"),3,UUID.randomUUID())

    @Test fun oldSchemaApprovalDoesNotGrantNewEnumsAndActualCaptureAndOwnerFenceAreRequired() {
        assertTrue(admits())
        assertFalse(admits(extra=false));assertFalse(admits(schema=false));assertFalse(admits(parameters=false))
        assertFalse(admits(inputVerified=false));assertFalse(admits(arbitration=false))
        assertFalse(admits(capture=null));assertFalse(admits(capture=UUID.randomUUID()))
        assertFalse(admits(current=view.copy(owner=owner.copy(privacyEpoch=1))))
        assertFalse(admits(current=view.copy(owner=owner.copy(presentationToken=UUID.randomUUID()))))
        assertFalse(admits(current=view.copy(navigationGeneration=5)))
        assertFalse(admits(current=view.copy(intentGeneration=9)))
        assertFalse(admits(current=view.copy(lessonId=UUID.randomUUID())))
        assertFalse(admits(current=view.copy(access=view.access.copy(power=PowerAvailability.UNKNOWN))))
        assertFalse(admits(current=view.copy(access=view.access.copy(audioAllowsReply=false))))
    }

    @Test fun cachedReadyOrCallbackTextWithoutReviewedPortCannotAuthorizeAnyRecommendationAction()=runBlocking {
        val basis=basis();var checks=0
        for(command in listOf(GleoLearningPreferenceCommand.APPLY_RECOMMENDATION,GleoLearningPreferenceCommand.KEEP_RECOMMENDATION,
            GleoLearningPreferenceCommand.DISMISS_RECOMMENDATION)) {
            val request=GleoLearningPreferenceRequest(command,lease,basis)
            assertFalse(GleoLearningPreferencePolicy.admitsReviewedAction(request,null){checks++;basis})
            assertFalse(GleoLearningPreferencePolicy.admitsReviewedAction(request,GleoRecommendationReviewPort{false}){checks++;basis})
            assertFalse(GleoLearningPreferencePolicy.admitsReviewedAction(request.copy(recommendationBasis=null),GleoRecommendationReviewPort{true}){checks++;basis})
        }
        assertEquals(0,checks)
        val read=GleoLearningPreferenceRequest(GleoLearningPreferenceCommand.READ_RECOMMENDATION,lease,basis)
        assertFalse(GleoLearningPreferencePolicy.admitsReviewedAction(read,GleoRecommendationReviewPort{true}){basis})
        assertEquals(LearningBand.ADVANCED,GleoLearningPreferenceCommand.SET_BAND_ADVANCED.band)
        assertNull(GleoLearningPreferenceCommand.SET_BAND_ADVANCED.action)
    }

    @Test fun actualReviewIsBoundToOriginalEvaluationAndCasNotWhateverRecommendationArrivesLater()=runBlocking {
        val basis=basis();val request=GleoLearningPreferenceRequest(GleoLearningPreferenceCommand.APPLY_RECOMMENDATION,lease,basis)
        val review=GleoRecommendationReviewPort{it==request}
        assertTrue(GleoLearningPreferencePolicy.admitsReviewedAction(request,review){basis})
        for(stale in listOf(basis.copy(recommendationVersion=2),basis.copy(evidenceRevision=8),basis.copy(basisSignature="another-basis"),
            basis.copy(evaluatedAt=basis.evaluatedAt.plusSeconds(1)),basis.copy(preferenceVersion=4),basis.copy(preferenceChoiceId=UUID.randomUUID()))) {
            assertFalse(GleoLearningPreferencePolicy.admitsReviewedAction(request,review){stale})
        }
        assertFalse(GleoLearningPreferencePolicy.admitsReviewedAction(request.copy(recommendationBasis=basis.copy(privacyEpoch=1)),review){basis})
    }
}

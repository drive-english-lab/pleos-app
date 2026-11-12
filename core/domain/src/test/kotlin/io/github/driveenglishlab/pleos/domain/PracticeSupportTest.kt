package io.github.driveenglishlab.pleos.domain

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PracticeSupportTest {
    private val task = PracticeContentRef(ContentRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ListeningMode.ORIGINAL_FULL), UUID.randomUUID(), UUID.randomUUID())
    private val owner = OwnerContext(UUID.randomUUID(),0,1,UUID.randomUUID())
    private fun artifact(ref: String, role: SupportRole, language: SupportLanguage, text: String): SupportArtifact {
        val kind = when (role) {
            SupportRole.QUESTION_MEANING -> null
            SupportRole.CORE_EXPLANATION, SupportRole.SIMPLE_EXPLANATION -> HelpKind.FACT_EXPLANATION
            SupportRole.SENTENCE_STARTER -> HelpKind.SENTENCE_STARTER
            SupportRole.COMPLETE_EXAMPLE -> HelpKind.COMPLETE_EXAMPLE
            else -> error("Not a public practice role.")
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return SupportArtifact(ref,kind,role,language,text,hash,SupportOrigin.PUBLISHED_TASK,null)
    }
    private fun manifest(extra: List<SupportArtifact> = emptyList()) = PracticeSupportManifest(task.taskId,task.questionRef,listOf(
        artifact("meaning-ko",SupportRole.QUESTION_MEANING,SupportLanguage.KO,"무엇이 바뀌었나요?"),
        artifact("core-ko",SupportRole.CORE_EXPLANATION,SupportLanguage.KO,"공공 도서관이 문을 열었어요."),
        artifact("simple-en",SupportRole.SIMPLE_EXPLANATION,SupportLanguage.EN,"A library opened. Residents can read there."),
        artifact("starter-en",SupportRole.SENTENCE_STARTER,SupportLanguage.EN,"The city opened"),
        artifact("example-en",SupportRole.COMPLETE_EXAMPLE,SupportLanguage.EN,"The city opened a public library.")) + extra)

    private fun ready(request: HelpRequest, received: HelpSnapshot = HelpSnapshot()) =
        (PracticePolicy.provide(request,manifest(),task,received) as SupportPreparation.Ready).prepared
    private fun observed(prepared: PreparedSupport, received: HelpSnapshot, completed: Boolean): HelpSnapshot {
        val profile = ProfileContext(owner.profileId,owner.privacyEpoch)
        val ticket = PracticePresentationTicket(UUID.randomUUID(),profile,owner,
            PracticePresentationRecord(UUID.randomUUID(),task,UUID.randomUUID(),InteractionChannel.PARKED,listOf(prepared.artifact),1,Instant.EPOCH,PresentationStatus.PREPARED,null),
            QuestionExampleExposure(task.questionRef,ExposureStatus.UNKNOWN,ExposureBasis.HISTORY_GAP,0,1,null))
        val observation = PracticePresentationObservation(UUID.randomUUID(),profile,UUID.randomUUID(),owner,UUID.randomUUID(),
            if (completed) ObservationOutcome.COMPLETED else ObservationOutcome.PARTIAL_OR_UNKNOWN,
            if (completed) ObservationMode.PARKED_VISIBLE else ObservationMode.INDETERMINATE,"test-only-component-observation")
        return PracticePolicy.delivered(prepared,ticket,observation,received)
    }

    @Test fun repeatedPreparationAndPartialOutputDoNotAdvanceFactToStarter() {
        val received = HelpSnapshot(); val fact = ready(HelpRequest.HINT,received)
        assertEquals("core-ko",fact.artifact.supportRef)
        val partial = observed(fact,received,false)
        assertEquals(received,partial); assertEquals("core-ko",ready(HelpRequest.HINT,partial).artifact.supportRef)
        val delivered = observed(fact,received,true)
        assertEquals("starter-en",ready(HelpRequest.HINT,delivered).artifact.supportRef)
        assertFalse(HelpKind.COMPLETE_EXAMPLE in delivered.kinds)
    }
    @Test fun questionMeaningIsActualHelpWithNullKindAndKeepsFactStepAvailable() {
        val meaning = ready(HelpRequest.QUESTION_MEANING)
        val received = observed(meaning,HelpSnapshot(),true)
        assertEquals(setOf("meaning-ko"),received.refs); assertTrue(received.kinds.isEmpty())
        assertEquals("core-ko",ready(HelpRequest.HINT,received).artifact.supportRef)
    }
    @Test fun defaultsAndExplicitApprovedVariantUseExactRoleAndLanguage() {
        val englishMeaning = artifact("meaning-en",SupportRole.QUESTION_MEANING,SupportLanguage.EN,"Say what changed.")
        val variant = PracticePolicy.provide(HelpRequest.QUESTION_MEANING,manifest(listOf(englishMeaning)),task,HelpSnapshot(),SupportLanguage.EN) as SupportPreparation.Ready
        assertEquals(englishMeaning,variant.prepared.artifact)
        assertEquals(SupportLanguage.KO,ready(HelpRequest.FACT).artifact.language)
        assertTrue(PracticePolicy.provide(HelpRequest.EXAMPLE,manifest(),task,HelpSnapshot(),SupportLanguage.KO) is SupportPreparation.Unavailable)
    }
    @Test fun partialListeningOffersTwoRelevantEnglishSentencesWithoutCopyingPriorExposure() {
        val prepared = PracticePolicy.partialListeningSupport(manifest(),task,HelpSnapshot()) as SupportPreparation.Ready
        assertEquals(SupportRole.SIMPLE_EXPLANATION,prepared.prepared.artifact.role)
        assertEquals(SupportLanguage.EN,prepared.prepared.artifact.language)
        assertEquals(HelpSnapshot(),observed(prepared.prepared,HelpSnapshot(),false))
    }
    @Test fun modifiedTextAndWrongCanonicalQuestionAreRejected() {
        val pack = manifest(); val modified = pack.copy(supports = pack.supports.map { if (it.role == SupportRole.CORE_EXPLANATION) it.copy(text = "Changed after review.") else it })
        assertThrows(IllegalArgumentException::class.java) { PracticePolicy.provide(HelpRequest.FACT,modified,task,HelpSnapshot()) }
        assertThrows(IllegalArgumentException::class.java) { PracticePolicy.provide(HelpRequest.FACT,pack,task.copy(basicQuestionVersionId = UUID.randomUUID()),HelpSnapshot()) }
    }
}

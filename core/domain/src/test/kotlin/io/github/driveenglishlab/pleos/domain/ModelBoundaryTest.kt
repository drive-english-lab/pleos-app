package io.github.driveenglishlab.pleos.domain

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class ModelBoundaryTest {
    private fun english(form: CaptureFidelity) = Capture(CapturePath.GLEO, form, emptyList(),
        SpokenLanguage.EN, EnglishEvidence.VERIFIED_PATH, "verified-route:capture")

    @Test fun originalLanguageDoesNotRequireLiteralForm() {
        assertEquals(CaptureFidelity.UNKNOWN, english(CaptureFidelity.UNKNOWN).formFidelity)
        assertThrows(IllegalArgumentException::class.java) { english(CaptureFidelity.UNKNOWN).copy(spokenLanguage = SpokenLanguage.KO) }
        assertThrows(IllegalArgumentException::class.java) { english(CaptureFidelity.UNKNOWN).copy(languageEvidenceRef = null) }
        assertEquals(EnglishEvidence.UNVERIFIED, Capture.unverified(CapturePath.PARKED_STT).englishEvidence)
    }

    @Test fun uncertainSpansUseCodePointsAndCannotCarryUnknownEvidenceAsVerified() {
        assertEquals(1, english(CaptureFidelity.UNKNOWN).copy(uncertainSpans = listOf("😀".repeat(240))).uncertainSpans.size)
        assertThrows(IllegalArgumentException::class.java) { english(CaptureFidelity.UNKNOWN).copy(uncertainSpans = listOf("😀".repeat(241))) }
        assertThrows(IllegalArgumentException::class.java) { english(CaptureFidelity.UNKNOWN).copy(spokenLanguage = SpokenLanguage.UNKNOWN) }
    }

    private fun artifact(role: SupportRole, lang: SupportLanguage, kind: HelpKind?) = SupportArtifact(
        "approved:${role.name}:${lang.name}", kind, role, lang, "Reviewed support.", "a".repeat(64), SupportOrigin.PUBLISHED_TASK, null)

    @Test fun questionMeaningRetainsItsOwnRoleAndDoesNotBecomeFactHint() {
        val meaning = artifact(SupportRole.QUESTION_MEANING, SupportLanguage.KO, null)
        assertNull(meaning.kind)
        assertThrows(IllegalArgumentException::class.java) { meaning.copy(kind = HelpKind.FACT_EXPLANATION) }
        assertThrows(IllegalArgumentException::class.java) { artifact(SupportRole.COMPLETE_EXAMPLE, SupportLanguage.KO, HelpKind.COMPLETE_EXAMPLE) }
    }

    @Test fun manifestUsesExplicitRoleLanguageAndRequiresAllFiveDefaults() {
        val supports = listOf(artifact(SupportRole.QUESTION_MEANING, SupportLanguage.KO, null),
            artifact(SupportRole.CORE_EXPLANATION, SupportLanguage.KO, HelpKind.FACT_EXPLANATION),
            artifact(SupportRole.SIMPLE_EXPLANATION, SupportLanguage.EN, HelpKind.FACT_EXPLANATION),
            artifact(SupportRole.SENTENCE_STARTER, SupportLanguage.EN, HelpKind.SENTENCE_STARTER),
            artifact(SupportRole.COMPLETE_EXAMPLE, SupportLanguage.EN, HelpKind.COMPLETE_EXAMPLE))
        val manifest = PracticeSupportManifest(UUID.randomUUID(), BasicQuestionRef(UUID.randomUUID(), UUID.randomUUID()), supports)
        assertNull(manifest.find(SupportRole.QUESTION_MEANING, SupportLanguage.EN))
        assertEquals(supports.first(), manifest.find(SupportRole.QUESTION_MEANING, SupportLanguage.KO))
        assertThrows(IllegalArgumentException::class.java) { manifest.copy(supports = supports.drop(1)) }
    }

    @Test fun businessFlagIsMonotonicAndLiveStateIsNotPartOfIt() {
        assertTrue(DialogueProgress(true).merge(DialogueProgress(false)).hintMethodAnnounced)
        assertFalse(DialogueProgress.newLesson().hintMethodAnnounced)
        assertThrows(IllegalArgumentException::class.java) { DialogueProgressObservation(UUID.randomUUID(), false, UUID.randomUUID(), "actual-completion") }
    }

    @Test fun missingHistoryAndGenericCallbacksNeverBecomeProofOfNoExposure() {
        assertThrows(IllegalArgumentException::class.java) { HistoryCoverage(HistoryOrigin.HISTORY_RESET, 1, false) }
        val question = BasicQuestionRef(UUID.randomUUID(), UUID.randomUUID())
        assertThrows(IllegalArgumentException::class.java) {
            QuestionExampleExposure(question, ExposureStatus.UNEXPOSED_VERIFIED, ExposureBasis.HISTORY_GAP, 0, 1, null)
        }
        val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
        assertThrows(IllegalArgumentException::class.java) {
            PracticePresentationObservation(UUID.randomUUID(), ProfileContext(owner.profileId, 0), UUID.randomUUID(), owner,
                UUID.randomUUID(), ObservationOutcome.COMPLETED, ObservationMode.INDETERMINATE, "callback-sent")
        }
    }
}

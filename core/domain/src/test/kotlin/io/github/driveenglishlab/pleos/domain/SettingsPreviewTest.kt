package io.github.driveenglishlab.pleos.domain

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class SettingsPreviewTest {
    private fun excerpt(text: String, language: SegmentLanguage): DemoExcerpt {
        val segment = SpeechSegment(UUID.randomUUID(), 0, UUID.randomUUID(), 0, text.codePointCount(0, text.length), segmentSha256(text))
        val unsigned = SegmentManifest(UUID.randomUUID(), UUID.randomUUID(), segmentSha256(text), 1, "0".repeat(64), language, listOf(segment))
        val signed = unsigned.copy(manifestHash = segmentSha256(unsigned.canonicalPayload()))
        return DemoExcerpt(text, signed.scriptHash, language, signed)
    }
    private fun demo(approved: Boolean): SettingsDemoManifest {
        val hash = "a".repeat(64)
        return SettingsDemoManifest(UUID.randomUUID(), 1, hash, DemoSource("Synthetic test fixture", null, null, null, true),
            excerpt("Original sample.", SegmentLanguage.EN), excerpt("Short sample.", SegmentLanguage.EN),
            mapOf(DemoFollowupRole.ORIGINAL_EN_SUMMARY to excerpt("Summary.", SegmentLanguage.EN), DemoFollowupRole.ORIGINAL_KO_SUMMARY to excerpt("원문 요약.", SegmentLanguage.KO),
                DemoFollowupRole.ORIGINAL_KO_TRANSLATION to excerpt("원문 번역.", SegmentLanguage.KO), DemoFollowupRole.BRIEFING_KO_SUMMARY to excerpt("브리핑 요약.", SegmentLanguage.KO),
                DemoFollowupRole.BRIEFING_KO_TRANSLATION to excerpt("브리핑 번역.", SegmentLanguage.KO)),
            LearningBand.entries.associateWith { excerpt("A question for ${it.name}.", SegmentLanguage.EN) },
            if (approved) DemoApproval(hash, "test-only-source-review", "test-only-rights-review", null) else null)
    }
    @Test fun allTwentyFourCombinationsPreserveOriginalAndOmitDuplicateEnglishFollowup() {
        val fixture = demo(true)
        var combinations = 0
        for (mode in ListeningMode.entries) for (followup in Followup.entries) for (band in LearningBand.entries) {
            val plan = requireNotNull(SettingsPreviewPolicy.plan(fixture, SettingsPreviewChoice(mode, followup, band), 1))
            combinations++
            assertEquals(PreviewPhase.QUESTION, plan.clips.last().phase)
            assertSame(fixture.questions.getValue(band), plan.clips.last().excerpt)
            if (mode == ListeningMode.ORIGINAL_FULL) assertSame(fixture.originalFull, plan.clips.first().excerpt)
            val omit = followup == Followup.NONE || mode == ListeningMode.BRIEFING && followup == Followup.EN_SUMMARY
            assertEquals(if (omit) 2 else 3, plan.clips.size)
        }
        assertEquals(24, combinations)
        assertNull(SettingsPreviewPolicy.plan(demo(false), SettingsPreviewChoice(ListeningMode.ORIGINAL_FULL, Followup.NONE, LearningBand.BASIC), 1))
        val withdrawn = fixture.copy(approval = requireNotNull(fixture.approval).copy(withdrawn = true))
        assertNull(SettingsPreviewPolicy.plan(withdrawn, SettingsPreviewChoice(ListeningMode.ORIGINAL_FULL, Followup.NONE, LearningBand.BASIC), 1))
    }
}

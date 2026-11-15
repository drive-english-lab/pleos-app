package io.github.driveenglishlab.pleos.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Entirely synthetic test inputs and test-only trust callback. Not a reviewed product demo. */
@RunWith(AndroidJUnit4::class)
class ReviewedSettingsDemoLoaderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val parser by lazy { LearningContractJson(context) }
    private val now = 1_000L
    private fun excerpt(text: String, language: SegmentLanguage): JsonObject {
        val hash = segmentSha256(text)
        val segment = SpeechSegment(UUID.randomUUID(), 0, UUID.randomUUID(), 0, text.codePointCount(0, text.length), hash)
        val provisional = SegmentManifest(UUID.randomUUID(), UUID.randomUUID(), hash, 1, "0".repeat(64), language, listOf(segment))
        val manifest = JsonObject(learningJson.parseToJsonElement(provisional.canonicalPayload()).jsonObject +
            ("manifest_hash" to JsonPrimitive(segmentSha256(provisional.canonicalPayload()))))
        return buildJsonObject {
            put("text", text); put("text_hash", hash); put("language", language.name.lowercase()); put("segments", manifest)
        }
    }
    private fun fixture(): JsonObject = buildJsonObject {
        put("schema_version", 1); put("demo_id", UUID.randomUUID().toString()); put("version", 1)
        put("source", buildJsonObject {
            put("publisher", "Synthetic test only"); put("original_url", JsonNull)
            put("published_at_raw", JsonNull); put("published_at_utc", JsonNull); put("synthetic", true)
        })
        put("original_full", excerpt("Synthetic original 🌱.", SegmentLanguage.EN))
        put("briefing", excerpt("Synthetic briefing.", SegmentLanguage.EN))
        put("followups", buildJsonObject {
            DemoFollowupRole.entries.forEach { role ->
                val en = role == DemoFollowupRole.ORIGINAL_EN_SUMMARY
                put(role.name.lowercase(), excerpt(if (en) "Synthetic summary." else "합성 테스트 요약입니다.", if (en) SegmentLanguage.EN else SegmentLanguage.KO))
            }
        })
        put("questions", buildJsonObject {
            LearningBand.entries.forEach { put(it.name.lowercase(), excerpt("Synthetic ${it.name} question?", SegmentLanguage.EN)) }
        })
    }
    private fun approval(bytes: ByteArray) = DemoApproval(segmentSha256(bytes.toString(Charsets.UTF_8)),
        "synthetic-test-source-review", "synthetic-test-rights-review", now + 100)
    private fun loader(bytes: ByteArray?, trust: () -> DemoApproval?, clock: () -> Long = { now }) =
        ReviewedSettingsDemoLoader({ bytes }, parser::parse, trust, clock)
    private fun encoded(value: JsonObject) = value.toString().toByteArray(Charsets.UTF_8)

    @Test fun all24ChoicesUseExactReviewedOriginalAndSkipDuplicateEnglishBriefing() = runBlocking {
        val bytes = encoded(fixture()); val trusted = approval(bytes)
        val result = requireNotNull(loader(bytes, { trusted }).load())
        for (mode in ListeningMode.entries) for (followup in Followup.entries) for (band in LearningBand.entries) {
            val plan = requireNotNull(SettingsPreviewPolicy.plan(result, SettingsPreviewChoice(mode, followup, band), now))
            assertEquals(if (mode == ListeningMode.ORIGINAL_FULL) result.originalFull else result.briefing, plan.clips.first().excerpt)
            assertEquals(result.questions.getValue(band), plan.clips.last().excerpt)
            val skipped = followup == Followup.NONE || mode == ListeningMode.BRIEFING && followup == Followup.EN_SUMMARY
            assertEquals(if (skipped) 2 else 3, plan.clips.size)
        }
    }
    @Test fun missingAssetsOrExternalApprovalNeverCreateDemo() = runBlocking {
        val bytes = encoded(fixture())
        assertNull(loader(bytes, { null }).load())
        assertNull(loader(null, { approval(bytes) }).load())
        assertNull(ReviewedSettingsDemoLoader(context).load())
    }
    @Test fun changedBytesExpiredOrWithdrawnReviewNeverPass() = runBlocking {
        val bytes = encoded(fixture()); val trusted = approval(bytes)
        assertNull(loader(bytes + byteArrayOf(32), { trusted }).load())
        assertNull(loader(bytes, { trusted.copy(withdrawn = true) }).load())
        assertNull(loader(bytes, { trusted }, { now + 100 }).load())
    }
    @Test fun reviewIsCheckedAgainAfterRead() = runBlocking {
        val bytes = encoded(fixture()); val trusted = approval(bytes); var reads = 0
        val loader = ReviewedSettingsDemoLoader({ reads++; bytes }, parser::parse,
            { if (reads == 0) trusted else trusted.copy(withdrawn = true) }, { now })
        assertNull(loader.load())
    }
    @Test fun inlineSelfApprovalUnknownKeysAndDuplicateKeysAreRejectedEvenWithTestTrust() = runBlocking {
        val root = fixture()
        val unknown = encoded(JsonObject(root + ("approval" to JsonPrimitive(true))))
        assertNull(loader(unknown, { approval(unknown) }).load())
        val duplicate = (root.toString().dropLast(1) + ",\"schema_version\":1}").toByteArray()
        assertNull(loader(duplicate, { approval(duplicate) }).load())
    }
    @Test fun segmentOmissionAndWrongLanguageAreRejectedEvenWhenFileIdentityMatches() = runBlocking {
        val root = fixture(); val original = root.getValue("original_full").jsonObject
        for ((key, value) in listOf("text" to JsonPrimitive("Unreviewed changed text."), "language" to JsonPrimitive("ko"))) {
            val bytes = encoded(JsonObject(root + ("original_full" to JsonObject(original + (key to value)))))
            assertNull(loader(bytes, { approval(bytes) }).load())
        }
        val questions = root.getValue("questions").jsonObject
        val bytes = encoded(JsonObject(root + ("questions" to JsonObject(questions - "advanced"))))
        assertNull(loader(bytes, { approval(bytes) }).load())
    }
    @Test fun actualSourceMetadataCannotBeReplacedBySyntheticFlagFalseAlone() = runBlocking {
        val root = fixture(); val source = root.getValue("source").jsonObject
        val bytes = encoded(JsonObject(root + ("source" to JsonObject(source + ("synthetic" to JsonPrimitive(false))))))
        assertNull(loader(bytes, { approval(bytes) }).load())
    }
}

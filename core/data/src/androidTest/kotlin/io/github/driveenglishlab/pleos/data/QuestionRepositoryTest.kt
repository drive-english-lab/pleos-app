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

@RunWith(AndroidJUnit4::class)
class QuestionRepositoryTest {
    private val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private val scope = LearningCallScope(owner, LearningSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "test-user-session"))
    private fun content() = ContentRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ListeningMode.ORIGINAL_FULL)
    private class ReplyTransport(val beforeReply: suspend () -> Unit = {}, val fail: Boolean = false) : LearningHttpTransport {
        var count = 0
        override suspend fun exchange(method: String, path: String, query: Map<String,String>, headers: Map<String,String>, body: ByteArray?): LearningHttpResponse {
            count++; val request = learningJson.parseToJsonElement(body!!.toString(Charsets.UTF_8)).jsonObject
            beforeReply()
            val result = buildJsonObject {
                put("schema_version","5"); put("operation_id",request["operation_id"]!!)
                if (fail) putJsonObject("error") { put("code","UPSTREAM_UNAVAILABLE"); put("retryable",true); put("message_ko","진행을 유지하고 다시 시도할 수 있어요.") }
                else {
                    put("content_ref",request["content_ref"]!!); put("client_context",request["client_context"]!!)
                    put("status","answered"); put("answer_text","The city opened a public library."); put("language","en")
                    put("evidence_refs",JsonArray(listOf(JsonPrimitive("fact:1")))); put("outside_listening_script",false)
                    putJsonObject("generation") { put("mode","fixture"); put("model_revision","test-only"); put("prompt_version","1"); put("validator_version","1") }
                }
            }
            return LearningHttpResponse(if (fail) 503 else 200,mapOf("Content-Type" to "application/json"),result.toString().toByteArray())
        }
    }
    private fun api(transport: LearningHttpTransport) = LearningApi(LearningSessionSource { scope },
        LearningContractJson(InstrumentationRegistry.getInstrumentation().targetContext),transport)

    @Test fun anotherArticleQuestionReturnsToTheOriginalPracticeWithoutCreatingAttempt() = runBlocking {
        val original = PracticeContentRef(content(),UUID.randomUUID(),UUID.randomUUID()); val lesson = UUID.randomUUID()
        val origin = QuestionOrigin.Practice(lesson,original); val transport = ReplyTransport()
        val repository = QuestionRepository(api(transport), isCurrent = { context, frame -> context == owner && frame == origin })
        val result = repository.ask(scope,content(),origin,"Why did the city open it?",InteractionChannel.GLEO)
        assertTrue(result is ArticleQuestionResult.Answer)
        assertEquals(ConversationReturn.SameQuestion(lesson,original),(result as ArticleQuestionResult.Answer).returnPlan)
        assertEquals(1,transport.count)
    }

    @Test fun failedQaKeepsSameAssetStagePauseAndFiveSecondReturnWithoutAutomaticRetry() = runBlocking {
        val point = ReturnPoint(PlaybackPosition(UUID.randomUUID(),ListeningStage.FOLLOWUP,12_000,30_000),PlaybackIntent.USER_PAUSED)
        val origin = QuestionOrigin.Listening(point); val transport = ReplyTransport(fail = true)
        val result = QuestionRepository(api(transport), isCurrent = { _, _ -> true }).ask(scope,content(),origin,"What changed?",InteractionChannel.PARKED)
        assertTrue(result is ArticleQuestionResult.Failed)
        val plan = (result as ArticleQuestionResult.Failed).returnPlan as ConversationReturn.Listening
        assertEquals(point.playback.assetId,plan.point.playback.assetId); assertEquals(7_000L,plan.point.playback.positionMs)
        assertEquals(PlaybackIntent.USER_PAUSED,plan.point.intent); assertEquals(1,transport.count)
    }

    @Test fun interruptionDuringRequestSuppressesLateAnswerAndReturnPlan() = runBlocking {
        lateinit var repository: QuestionRepository
        val transport = ReplyTransport(beforeReply = { repository.interrupt() })
        repository = QuestionRepository(api(transport), isCurrent = { _, _ -> true })
        assertEquals(ArticleQuestionResult.Superseded,repository.ask(scope,content(),QuestionOrigin.ArticleOffer(content()),"What happened?",InteractionChannel.PARKED))
    }
}

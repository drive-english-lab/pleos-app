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
class CatalogRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val ref = ContentRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ListeningMode.ORIGINAL_FULL)
    private fun response(vararg rows: JsonObject) = LearningHttpResponse(200, mapOf("Content-Type" to "application/json"), JsonArray(rows.toList()).toString().toByteArray())
    private fun bundle() = buildJsonObject {
        put("bundle_id", ref.bundleId.toString()); put("article_id", ref.articleId.toString()); put("source_snapshot_id", ref.sourceSnapshotId.toString())
        put("schema_version", "5"); put("status", "published"); put("published_at", "2026-11-13T00:00:00Z"); put("access_until", "2026-11-14T00:00:00Z")
    }
    private fun script(hash: String = LearningJson.hash("실제 UTF-8 script.")) = buildJsonObject {
        put("listening_script_id", ref.listeningScriptId.toString()); put("bundle_id", ref.bundleId.toString()); put("role", "original_full")
        put("base_script_id", JsonNull); put("language", "en"); put("text", "실제 UTF-8 script."); put("text_hash", hash)
        put("audio_asset_id", UUID.randomUUID().toString()); put("duration_ms", 1000)
    }
    private fun repo(handler: suspend (String, Map<String, String>) -> LearningHttpResponse) = CatalogRepository(
        LearningContractJson(context), object : CatalogHttpTransport {
            override suspend fun get(table: String, query: Map<String, String>) = handler(table, query)
        }, "https://synthetic.supabase.co")

    @Test fun mainScriptKeepsThePinnedBundleSnapshotAndVerifiesUtf8Hash() = runBlocking {
        val calls = mutableListOf<String>()
        val result = repo { table, query ->
            calls += table
            assertEquals("eq.${ref.bundleId}", query["bundle_id"])
            if (table == "news_bundles") response(bundle()) else response(script())
        }.readMain(ref)
        assertTrue(result is CatalogRead.Stored)
        assertEquals("실제 UTF-8 script.", (result as CatalogRead.Stored).value.text)
        assertEquals(listOf("news_bundles", "news_scripts"), calls)
    }

    @Test fun wrongSnapshotOrChangedScriptNeverBecomesDisplayableContent() = runBlocking {
        val foreign = JsonObject(bundle() + ("source_snapshot_id" to JsonPrimitive(UUID.randomUUID().toString())))
        val wrongSource = repo { table, _ -> if (table == "news_bundles") response(foreign) else throw AssertionError("Pinned source mismatch.") }.readMain(ref)
        assertEquals(CatalogRead.Unavailable("INVALID_CONTENT", false), wrongSource)
        val wrongHash = repo { table, _ -> if (table == "news_bundles") response(bundle()) else response(script("0".repeat(64))) }.readMain(ref)
        assertEquals(CatalogRead.Unavailable("INVALID_CONTENT", false), wrongHash)
    }

    @Test fun noCatalogAndWithdrawnPinnedSourceHaveDistinctResults() = runBlocking {
        val repository = repo { _, _ -> response() }
        assertTrue(repository.catalogPage() is CatalogRead.Stored)
        assertEquals(emptyList<CatalogArticle>(), (repository.catalogPage() as CatalogRead.Stored).value)
        assertEquals(CatalogRead.Unavailable("CONTENT_UNAVAILABLE", false), repository.readMain(ref))
    }

    @Test fun originalUnknownTimestampIsPreservedSeparatelyFromBundlePublication() = runBlocking {
        val item = buildJsonObject {
            put("article_id", ref.articleId.toString()); put("bundle_id", ref.bundleId.toString()); put("title", "Synthetic")
            put("publisher", "Fixture"); put("original_url", "https://example.invalid/source"); put("published_at_raw", "timezone unknown")
            put("published_at_utc", JsonNull); put("timezone_status", "unknown"); put("topic_ids", JsonArray(listOf(JsonPrimitive("science"))))
            put("original_duration_ms", 1000); put("briefing_duration_ms", 500); put("published_at", "2026-11-13T00:00:00Z")
            put("access_until", "2026-11-14T00:00:00Z"); put("status", "published")
        }
        val result = repo { table, query -> assertEquals("news_catalog", table); assertEquals("published_at.desc,article_id.asc", query["order"]); response(item) }.catalogPage()
        val article = (result as CatalogRead.Stored).value.single()
        assertNull(article.originalPublishedAt); assertEquals("timezone unknown", article.publishedAtRaw)
        assertEquals("2026-11-13T00:00:00Z", article.publishedAt.toString())
        assertEquals(CatalogRead.Unavailable("INVALID_CONTENT", false), repo { _, _ -> response(item.changed("published_at" to JsonPrimitive("not-a-date"))) }.catalogPage())
        assertEquals(CatalogRead.Unavailable("INVALID_CONTENT", false), repo { _, _ -> response(item.changed("original_url" to JsonPrimitive("https://example.invalid/white space"))) }.catalogPage())
    }

    @Test fun duplicateJsonFieldsAndUnsupportedFollowupAreRejected() = runBlocking {
        val bad = LearningHttpResponse(200, mapOf("Content-Type" to "application/json"), "[{\"article_id\":\"a\",\"article_id\":\"b\"}]".toByteArray())
        assertEquals(CatalogRead.Unavailable("INVALID_CONTENT", false), repo { _, _ -> bad }.catalogPage())
        val impossible = repo { _, _ -> throw AssertionError("Do not fetch a different main's support.") }
        assertEquals(CatalogRead.Unavailable("INVALID_CONTENT", false), impossible.readFollowup(ref.copy(listeningMode = ListeningMode.BRIEFING), CatalogScriptRole.ORIGINAL_EN_SUMMARY))
    }

    @Test fun signedAudioMustMatchThePinnedAssetDurationAndConfiguredStorageOrigin() = runBlocking {
        val script = PublishedScript(ref.listeningScriptId, ref.bundleId, CatalogScriptRole.ORIGINAL_FULL, null,
            "Synthetic.", LearningJson.hash("Synthetic."), UUID.randomUUID(), 1000)
        val scope = LearningCallScope(OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID()),
            LearningSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "synthetic-user-token"))
        val operation = UUID.randomUUID()
        val base = buildJsonObject {
            put("schema_version", "5"); put("operation_id", operation.toString()); put("bundle_id", script.bundleId.toString()); put("audio_asset_id", script.audioAssetId.toString())
            put("asset_hash", "0".repeat(64)); put("content_type", "audio/wav"); put("duration_ms", 1000)
            put("signed_url", "https://synthetic.supabase.co/storage/v1/object/sign/news-audio/fixture.wav?token=synthetic-only")
            put("expires_at", "2026-11-14T00:00:00Z")
        }
        suspend fun call(value: JsonObject): LearningReply<*> {
            val api = LearningApi(LearningSessionSource { scope }, LearningContractJson(context), object : LearningHttpTransport {
                override suspend fun exchange(method: String, path: String, query: Map<String, String>, headers: Map<String, String>, body: ByteArray?) = jsonResponse(value)
            })
            return repo { _, _ -> throw AssertionError("Audio access uses the protected API.") }.audioAccess(api, scope, script, operation)
        }
        assertTrue(call(base) is LearningReply.Stored)
        assertEquals(LearningReply.Failed("INVALID_CONTRACT", false), call(base.changed("duration_ms" to JsonPrimitive(999))))
        assertEquals(LearningReply.Failed("INVALID_CONTRACT", false), call(base.changed("signed_url" to JsonPrimitive("https://other.invalid/storage/v1/object/sign/news-audio/fixture.wav?token=synthetic"))))
    }
}

package io.github.driveenglishlab.pleos.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.domain.OwnerContext
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FeatureMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun open(name: String) = Room.databaseBuilder(context, LearningDatabase::class.java, name)
        .addMigrations(LEARNING_MIGRATION_1_2, LEARNING_MIGRATION_2_3, LEARNING_MIGRATION_3_4).build()
    private fun scope(owner: LocalOwner) = OwnerContext(UUID.fromString(owner.profileId), owner.privacyEpoch, owner.revision, UUID.randomUUID())

    @Test fun versionTwoPreservesPendingAnswerAndPauseAndExposureWithoutBackfillingFeatureEvidence() = runBlocking {
        val name = "feature-migrate-${UUID.randomUUID()}.db"
        val profile = UUID.randomUUID().toString(); val event = UUID.randomUUID().toString(); val lesson = UUID.randomUUID().toString()
        val payload = "{\"phase\":\"suspended\",\"intent\":\"user_paused\",\"exposure\":\"unknown\",\"pending_feedback_attempt_id\":\"original\"}"
        val hash = LearningJson.hash(payload)
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val schema = InstrumentationRegistry.getInstrumentation().context.assets
                .open("io.github.driveenglishlab.pleos.data.LearningDatabase/2.json").bufferedReader()
                .use { JSONObject(it.readText()).getJSONObject("database") }
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO local_owner VALUES(1, ?, 0, 3, 'active')", arrayOf(profile))
            old.execSQL("INSERT INTO learning_record VALUES(?, 0, ?, 'lesson', ?, 4, 5)", arrayOf(profile, lesson, payload))
            old.execSQL("INSERT INTO learning_outbox VALUES(?, ?, 0, 3, ?, ?, ?, 0, 5, 'profile_sync')", arrayOf(event, profile, lesson, payload, hash))
            old.execSQL("INSERT INTO history_coverage VALUES(?, 0, 'legacy_or_incomplete', 1, 1)", arrayOf(profile))
            old.version = 2
        }
        val db = open(name)
        try {
            val owner = db.learning().currentOwner()!!
            assertEquals(4, db.openHelper.readableDatabase.version)
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM segment_state").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM review_context").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            assertEquals(3L, owner.revision)
            assertEquals(payload, db.learning().record(profile, 0, lesson)!!.payloadJson)
            assertEquals(hash, db.learning().pending(profile, 0).single().payloadHash)
            assertTrue(db.learning().historyCoverage(profile, 0)!!.historyGap)
            assertNull(db.progress().preference(profile)); assertNull(db.aiLearning().preference(profile))
            assertNull(db.progress().cache(profile, 0, "page", "visible"))
            assertNull(db.aiLearning().cache(profile, 0, "recommendation", "current"))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun learningDeletionKeepsSettingsButRemovesEpochDataAndOutdatedOwnersCannotRestoreIt() = runBlocking {
        val name = "feature-lifetime-${UUID.randomUUID()}.db"; var db = open(name)
        try {
            val owner = db.learning().provisionalOwner(); val scope = scope(owner); val profile = owner.profileId
            val payload = "{\"original_practice_time\":null}"
            db.progress().savePreference(scope, LocalProgressPreference(profile, 1, "{\"decision\":\"listen_only\"}"))
            db.aiLearning().savePreference(scope, LocalAiLearningPreference(profile, 1, "{\"band\":\"basic\"}"))
            val setting = LocalProgressPreferenceJournal(UUID.randomUUID().toString(), profile, scope.revision, 1,
                "{\"boot\":\"original-boot\",\"monotonic_ms\":123,\"zone\":null}", payload, LearningJson.hash(payload), "pending", false)
            assertTrue(db.progress().queuePreference(scope, setting))
            assertFalse(db.progress().queuePreference(scope, setting))
            var rejected = false
            try { db.progress().queuePreference(scope, setting.copy(payloadJson = "{}", payloadHash = LearningJson.hash("{}"))) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            db.progress().saveCache(scope, LocalProgressCache(profile, 0, "detail", "lesson", 2, payload))
            db.aiLearning().saveCache(scope, LocalAiLearningCache(profile, 0, "recommendation", "current", 2, payload))
            val action = LocalAiLearningJournal(UUID.randomUUID().toString(), profile, 0, scope.revision, "recommendation_action", payload,
                LearningJson.hash(payload), "pending", false)
            db.aiLearning().queue(scope, action)
            db.withTransaction {
                FeatureLifecycle(db).clearConfirmedEpoch(UUID.fromString(profile), 0)
                db.learning().putOwner(owner.copy(privacyEpoch = 1, revision = owner.revision + 1))
            }
            db.close(); db = open(name)
            assertNotNull(db.progress().preference(profile)); assertNotNull(db.aiLearning().preference(profile))
            assertEquals(setting, db.progress().preferenceOperation(setting.operationId))
            assertNull(db.progress().cache(profile, 0, "detail", "lesson")); assertNull(db.aiLearning().operation(action.operationId))
            rejected = false
            try { db.aiLearning().queue(scope, action) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        } finally { db.close(); context.deleteDatabase(name) }
    }
    @Test fun deletionGateRejectsLateSyncReceiptBeforeItCanRepopulateVisibleCaches() = runBlocking {
        val name = "feature-gate-${UUID.randomUUID()}.db"; val db = open(name)
        try {
            val owner = db.learning().provisionalOwner(); val scope = scope(owner); val profile = owner.profileId
            db.progress().savePreference(scope, LocalProgressPreference(profile, 1, "{\"decision\":\"listen_only\"}"))
            db.progress().saveCache(scope, LocalProgressCache(profile, 0, "page", "visible", 1, "{}"))
            val lifecycle = FeatureLifecycle(db)
            val call = io.github.driveenglishlab.pleos.domain.LearningCallScope(scope,
                io.github.driveenglishlab.pleos.domain.LearningSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "synthetic-gate-test-token"))
            val result = ProfileSyncWorker(lifecycle).runOnce(call) {
                lifecycle.blockForHistoryDeletion(scope, UUID.randomUUID())
                io.github.driveenglishlab.pleos.domain.LearningReply.Stored(call, "late-response")
            }
            assertEquals(io.github.driveenglishlab.pleos.domain.LearningReply.Failed("SESSION_CHANGED", false), result)
            assertEquals("delete_pending", db.learning().currentOwner()!!.status)
            assertFalse(lifecycle.isCurrent(scope))
            assertNotNull(db.progress().preference(profile))
            assertNull(db.progress().cache(profile, 0, "page", "visible"))
            var called = false
            ProfileSyncWorker(lifecycle).runOnce(call) { called = true; io.github.driveenglishlab.pleos.domain.LearningReply.Stored(call, "never") }
            assertFalse(called)
        } finally { db.close(); context.deleteDatabase(name) }
    }

}

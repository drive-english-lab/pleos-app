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

/** Uses exported real Room schemas; opening Room performs its complete schema validation. */
@RunWith(AndroidJUnit4::class)
class ReviewSegmentMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAssets get() = InstrumentationRegistry.getInstrumentation().context.assets
    private fun schema(version: Int) = testAssets.open("io.github.driveenglishlab.pleos.data.LearningDatabase/$version.json")
        .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
    private fun open(name: String) = Room.databaseBuilder(context, LearningDatabase::class.java, name)
        .addMigrations(LEARNING_MIGRATION_1_2, LEARNING_MIGRATION_2_3, LEARNING_MIGRATION_3_4, LEARNING_MIGRATION_4_5).build()
    private fun ownerScope(owner: LocalOwner) = OwnerContext(UUID.fromString(owner.profileId), owner.privacyEpoch, owner.revision, UUID.randomUUID())
    private val addedTables = listOf("review_context", "review_operation", "review_observation", "fresh_practice_intent",
        "preparation_waiter", "settings_draft", "source6_record", "segment_state", "segment_journal", "segment_completion", "playback_request_intent", "catalog_view_page")
    private val epochTables = addedTables.filterNot { it == "settings_draft" }

    private fun createVersion(name: String, version: Int, seed: (SQLiteDatabase) -> Unit) {
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            val document = schema(version)
            val entities = document.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = document.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            seed(db)
            db.version = version
        }
    }
    private fun count(db: LearningDatabase, table: String, profile: String? = null, epoch: Long? = null): Int {
        require(table in addedTables || table in setOf("learning_record", "learning_outbox", "daily_preference_cache", "learning_preference_cache"))
        val where = if (profile == null) "" else if (epoch == null) " WHERE profileId = ?" else " WHERE profileId = ? AND privacyEpoch = ?"
        val args = if (profile == null) emptyArray<Any>() else if (epoch == null) arrayOf<Any>(profile) else arrayOf<Any>(profile, epoch)
        return db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table$where", args).use { it.moveToFirst(); it.getInt(0) }
    }

    @Test fun realVersionsOneThroughFourReachRoomFiveWithoutConvertingOldTimeOrOutboxBytes() = runBlocking {
        for (version in 1..4) {
            val name = "review-room-$version-${UUID.randomUUID()}.db"
            val profile = UUID.randomUUID().toString(); val checkpoint = UUID.randomUUID().toString(); val event = UUID.randomUUID().toString()
            val legacyTime = "{\n  \"position_ms\": 4321, \"duration_ms\": 98765, \"phase\": \"followup\", \"play_intent\": false, \"pause_reason\": \"user\", \"main_coverage_status\": \"partial\"\n}"
            val hash = LearningJson.hash(legacyTime)
            createVersion(name, version) { old ->
                old.execSQL("INSERT INTO local_owner VALUES(1, ?, 4, 9, 'active')", arrayOf<Any>(profile))
                if (version == 1) {
                    old.execSQL("INSERT INTO learning_record VALUES(?, 4, ?, 'checkpoint', ?, 6)", arrayOf<Any>(profile, checkpoint, legacyTime))
                    old.execSQL("INSERT INTO learning_outbox VALUES(?, ?, 4, 9, ?, ?, ?, 0)", arrayOf<Any>(event, profile, checkpoint, legacyTime, hash))
                } else {
                    old.execSQL("INSERT INTO learning_record VALUES(?, 4, ?, 'checkpoint', ?, 6, 5)", arrayOf<Any>(profile, checkpoint, legacyTime))
                    old.execSQL("INSERT INTO learning_outbox VALUES(?, ?, 4, 9, ?, ?, ?, 0, 5, 'profile_sync')", arrayOf<Any>(event, profile, checkpoint, legacyTime, hash))
                    old.execSQL("INSERT INTO history_coverage VALUES(?, 4, 'legacy_or_incomplete', 2, 1)", arrayOf<Any>(profile))
                }
                old.execSQL("INSERT INTO owner_journal VALUES(?, ?, 9, 'logout', 'pending', 'original-reference')", arrayOf<Any>(UUID.randomUUID().toString(), profile))
                if (version >= 3) {
                    old.execSQL("INSERT INTO learning_preference_cache VALUES(?, 3, ?)", arrayOf<Any>(profile, "{\"band\":\"intermediate\"}"))
                    old.execSQL("INSERT INTO daily_preference_cache VALUES(?, 2, ?)", arrayOf<Any>(profile, "{\"decision\":\"listen_only\"}"))
                }
            }
            val db = open(name)
            try {
                val actualOwner = requireNotNull(db.learning().currentOwner()) // Triggers Room migration + full schema validation.
                assertEquals(LocalOwner(profileId = profile, privacyEpoch = 4, revision = 9, status = "active"), actualOwner)
                assertEquals(5, db.openHelper.readableDatabase.version)
                val record = requireNotNull(db.learning().record(profile, 4, checkpoint))
                assertEquals(legacyTime, record.payloadJson); assertEquals(6L, record.version)
                assertEquals(if (version == 1) 3 else 5, record.payloadSchemaVersion)
                val operation = requireNotNull(db.learning().outboxEvent(event))
                assertEquals(legacyTime, operation.payloadJson); assertEquals(hash, operation.payloadHash)
                assertEquals(9L, operation.ownerRevision); assertFalse(operation.isolated)
                assertEquals(if (version == 1) "legacy" else "profile_sync", operation.transport)
                assertTrue(requireNotNull(db.learning().historyCoverage(profile, 4)).historyGap)
                for (table in addedTables) assertEquals("No guessed new evidence in $table after v$version", 0, count(db, table))
                if (version >= 3) {
                    assertEquals("{\"band\":\"intermediate\"}", db.aiLearning().preference(profile)?.payloadJson)
                    assertEquals("{\"decision\":\"listen_only\"}", db.progress().preference(profile)?.payloadJson)
                }
                db.openHelper.readableDatabase.query("SELECT kind,status,isolatedSessionReference FROM owner_journal").use {
                    assertTrue(it.moveToFirst()); assertEquals("logout", it.getString(0)); assertEquals("pending", it.getString(1)); assertEquals("original-reference", it.getString(2))
                }
                // Generated schema5 is packaged in androidTest assets and matches the opened identity.
                val expected = schema(5)
                assertEquals(5, expected.getInt("version"))
                assertEquals(30, expected.getJSONArray("entities").length())
                db.openHelper.readableDatabase.query("SELECT identity_hash FROM room_master_table WHERE id=42").use {
                    assertTrue(it.moveToFirst()); assertEquals(expected.getString("identityHash"), it.getString(0))
                }
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }

    private data class EpochFixture(val profile: String, val epoch: Long, val selection: String, val run: String,
        val operation: LocalReviewOperation, val segment: LocalSegmentOperation, val original: String)
    /** Seed preserved records, not live output grants; none of these fixtures starts synthesis. */
    private fun seedEpoch(db: LearningDatabase, profile: String, epoch: Long, revision: Long): EpochFixture {
        val sql = db.openHelper.writableDatabase
        val selection = UUID.randomUUID().toString(); val run = UUID.randomUUID().toString(); val operationId = UUID.randomUUID().toString()
        val text = "{ \"original_epoch\": $epoch, \"pause_reason\": \"user\", \"position_ms\": 4321 }"
        val hash = LearningJson.hash(text)
        val review = LocalReviewOperation(operationId, profile, epoch, revision, "command", selection, text, hash)
        val segment = LocalSegmentOperation(UUID.randomUUID().toString(), profile, epoch, revision, selection, run, "observation", text, hash, "pending", false, null)
        sql.execSQL("INSERT INTO review_context VALUES(?, ?, ?, 3, ?, ?)", arrayOf<Any>(profile, epoch, selection, text, hash))
        sql.execSQL("INSERT INTO review_operation VALUES(?, ?, ?, ?, 'command', ?, ?, ?, 'pending', NULL)", arrayOf<Any>(operationId, profile, epoch, revision, selection, text, hash))
        sql.execSQL("INSERT INTO review_observation VALUES(?, ?, ?, 1, ?, ?, ?)", arrayOf<Any>(profile, epoch, UUID.randomUUID().toString(), UUID.randomUUID().toString(), operationId, hash))
        sql.execSQL("INSERT INTO fresh_practice_intent VALUES(?, ?, ?, ?, ?, ?, 2, ?, ?, 'prepared')", arrayOf<Any>(UUID.randomUUID().toString(), profile, epoch, revision, UUID.randomUUID().toString(), UUID.randomUUID().toString(), text, hash))
        sql.execSQL("INSERT INTO preparation_waiter VALUES(?, ?, ?, ?, ?, 'sentence', ?, ?, 0, NULL, 0)", arrayOf<Any>(UUID.randomUUID().toString(), profile, epoch, revision, UUID.randomUUID().toString(), text, hash))
        sql.execSQL("INSERT INTO source6_record VALUES(?, ?, 'checkpoint', ?, 3, ?, ?)", arrayOf<Any>(profile, epoch, selection, text, hash))
        sql.execSQL("INSERT INTO segment_state VALUES(?, ?, ?, 'followup', 'main', 'legacy_time', ?, ?, NULL, ?, ?, 1, ?, NULL, 0, 1)", arrayOf<Any>(profile, epoch, selection, revision, text, text, run, text))
        sql.execSQL("INSERT INTO segment_journal VALUES(?, ?, ?, ?, ?, ?, 'observation', ?, ?, 'pending', 0, NULL)", arrayOf<Any>(segment.operationId, profile, epoch, revision, selection, run, text, hash))
        sql.execSQL("INSERT INTO segment_completion VALUES(?, ?, ?, ?, ?, ?, ?, ?)", arrayOf<Any>(profile, epoch, run, selection, UUID.randomUUID().toString(), UUID.randomUUID().toString(), text, "original-server-receipt"))
        sql.execSQL("INSERT INTO playback_request_intent VALUES(?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 0, NULL, NULL, 0, NULL, 0)",
            arrayOf<Any>(UUID.randomUUID().toString(), profile, epoch, revision, UUID.randomUUID().toString(), UUID.randomUUID().toString(), text, hash))
        sql.execSQL("INSERT INTO catalog_view_page VALUES(?, ?, ?, 'interests', '', ?, '', 1, ?, ?)",
            arrayOf<Any>(profile, epoch, revision, UUID.randomUUID().toString(), text, hash))
        return EpochFixture(profile, epoch, selection, run, review, segment, text)
    }
    private fun assertEpochPresent(db: LearningDatabase, value: EpochFixture) {
        for (table in epochTables) assertEquals("Keep unrelated epoch row in $table", 1, count(db, table, value.profile, value.epoch))
    }

    @Test fun confirmedSingleEpochDeletionPreservesOtherEpochProfileAndOrdinarySettings() = runBlocking {
        val name = "review-epoch-${UUID.randomUUID()}.db"; val db = open(name)
        try {
            val profile = UUID.randomUUID().toString()
            val owner = LocalOwner(profileId = profile, privacyEpoch = 3, revision = 9, status = "active")
            db.learning().putOwner(owner)
            val erased = seedEpoch(db, profile, 2, 8)
            val kept = seedEpoch(db, profile, 3, 9)
            val other = seedEpoch(db, UUID.randomUUID().toString(), 2, 1)
            val scope = ownerScope(owner)
            db.aiLearning().savePreference(scope, LocalAiLearningPreference(profile, 3, "{\"band\":\"advanced\"}"))
            db.progress().savePreference(scope, LocalProgressPreference(profile, 4, "{\"decision\":\"listen_only\"}"))
            val draft = LocalSettingsDraft(profile, 3, 9, 1, 7, "original settings", "unapplied draft", null, null)
            db.review().openDraft(scope, draft)
            val olderDraft = LocalSettingsDraft(profile, 2, 8, 2, 6, "older original settings", "older unapplied draft", null, null)
            db.openHelper.writableDatabase.execSQL("INSERT INTO settings_draft VALUES(?, 2, 8, 2, 6, ?, ?, NULL, NULL)",
                arrayOf<Any>(profile, olderDraft.originalJson, olderDraft.draftJson))
            db.withTransaction { FeatureLifecycle(db).clearConfirmedEpoch(UUID.fromString(profile), 2) }
            for (table in epochTables) assertEquals("Delete only confirmed epoch in $table", 0, count(db, table, profile, 2))
            assertEpochPresent(db, kept); assertEpochPresent(db, other)
            assertEquals(kept.operation, db.review().operation(kept.operation.operationId))
            assertEquals(kept.segment, db.segments().operation(kept.segment.operationId))
            assertEquals(kept.original, db.segments().state(profile, 3, kept.selection, "followup", "main", "legacy_time")?.cursorJson)
            assertEquals(draft, db.review().draft(profile, 3))
            assertEquals(olderDraft, db.review().draft(profile, 2))
            assertEquals("{\"band\":\"advanced\"}", db.aiLearning().preference(profile)?.payloadJson)
            assertEquals("{\"decision\":\"listen_only\"}", db.progress().preference(profile)?.payloadJson)
            val stale = scope.copy(privacyEpoch = 2, revision = 8)
            var rejected = false
            try { db.review().queue(stale, erased.operation) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            rejected = false
            try { db.segments().enqueue(stale, erased.segment) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(0, count(db, "review_operation", profile, 2)); assertEquals(0, count(db, "segment_journal", profile, 2))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun logoutIsolatesImmutablePendingWorkAndSameProfileReturnCannotReplayIt() = runBlocking {
        val name = "review-logout-${UUID.randomUUID()}.db"; var db = open(name)
        try {
            val profile = UUID.randomUUID().toString()
            val owner = LocalOwner(profileId = profile, privacyEpoch = 3, revision = 9, status = "active")
            db.learning().putOwner(owner)
            val original = seedEpoch(db, profile, 3, 9)
            val scope = ownerScope(owner)
            val draft = LocalSettingsDraft(profile, 3, 9, 1, 4, "saved settings", "dirty draft", UUID.randomUUID().toString(), null)
            db.review().openDraft(scope, draft)
            val guest = db.withTransaction {
                FeatureLifecycle(db).isolateForOwnerTransition(UUID.fromString(profile))
                db.learning().detachForLogout(owner.revision, UUID.randomUUID().toString(), "test-isolated-session-reference")
            }
            db.close(); db = open(name)
            assertEquals(guest, db.learning().currentOwner())
            assertNotEquals(profile, guest.profileId)
            assertTrue(db.review().pending(profile, 3, 9).isEmpty()); assertTrue(db.segments().pending(profile, 3).isEmpty())
            assertEquals(original.operation.copy(status = "isolated"), db.review().operation(original.operation.operationId))
            assertEquals(original.segment.copy(isolated = true), db.segments().operation(original.segment.operationId))
            assertEquals("dirty draft", db.review().draft(profile, 3)?.draftJson)
            assertNull(db.review().draft(profile, 3)?.previewToken)
            assertNull(db.review().draft(guest.profileId, 0))
            assertEquals(0, count(db, "review_context", profile, 3)); assertEquals(0, count(db, "segment_state", profile, 3))
            val witnessed = requireNotNull(db.segments().completion(profile, 3, original.run))
            assertEquals(original.original, witnessed.proofJson)
            assertEquals("original-server-receipt", witnessed.serverRecordJson)
            // Simulate a later verified binding to the same identity: isolated payloads must not
            // become fresh commands merely by replacing their ownerRevision with the new value.
            val returned = owner.copy(revision = guest.revision + 1)
            db.learning().putOwner(returned)
            val rebound = ownerScope(returned)
            var rejected = false
            try { db.review().queue(rebound, original.operation.copy(ownerRevision = rebound.revision)) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            rejected = false
            try { db.segments().enqueue(rebound, original.segment.copy(ownerRevision = rebound.revision)) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertTrue(db.review().pending(profile, 3, rebound.revision).isEmpty()); assertTrue(db.segments().pending(profile, 3).isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }
}

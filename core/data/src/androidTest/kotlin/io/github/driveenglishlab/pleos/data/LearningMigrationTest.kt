package io.github.driveenglishlab.pleos.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.domain.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearningMigrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun open(name: String) = Room.databaseBuilder(context, LearningDatabase::class.java, name)
        .addMigrations(LEARNING_MIGRATION_1_2).build()
    private fun scope(owner: LocalOwner) = OwnerContext(UUID.fromString(owner.profileId), owner.privacyEpoch, owner.revision, UUID.randomUUID())
    private fun practice() = PracticeContentRef(ContentRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        ListeningMode.ORIGINAL_FULL), UUID.randomUUID(), UUID.randomUUID())

    @Test fun versionOnePayloadsHashesOwnersAndUnknownHistorySurviveRealMigration() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val profile = UUID.randomUUID().toString(); val lesson = UUID.randomUUID(); val event = UUID.randomUUID().toString()
        val payload = "{\"phase\":\"suspended\",\"help_received\":[]}"
        val hash = "1".repeat(64)
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { legacy ->
            val asset = "io.github.driveenglishlab.pleos.data.LearningDatabase/1.json"
            val schema = InstrumentationRegistry.getInstrumentation().context.assets.open(asset).bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                legacy.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) legacy.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val queries = schema.getJSONArray("setupQueries")
            for (i in 0 until queries.length()) legacy.execSQL(queries.getString(i))
            legacy.execSQL("INSERT INTO local_owner VALUES(1, ?, 0, 7, 'provisional')", arrayOf(profile))
            legacy.execSQL("INSERT INTO learning_record VALUES(?, 0, ?, 'lesson', ?, 1)", arrayOf(profile, lesson.toString(), payload))
            legacy.execSQL("INSERT INTO learning_outbox VALUES(?, ?, 0, 7, ?, ?, ?, 0)", arrayOf(event, profile, lesson.toString(), payload, hash))
            legacy.execSQL("INSERT INTO owner_journal VALUES(?, ?, 7, 'logout', 'pending', 'isolated-session-reference')", arrayOf(UUID.randomUUID().toString(), profile))
            legacy.version = 1
        }
        val database = open(name)
        try {
            val owner = database.learning().currentOwner()!!
            assertEquals(profile, owner.profileId); assertEquals(7L, owner.revision)
            val record = database.learning().record(profile, 0, lesson.toString())!!
            assertEquals(payload, record.payloadJson); assertEquals(3, record.payloadSchemaVersion)
            assertTrue(database.learning().pending(profile, 0).isEmpty())
            val held = database.learning().legacyPending(profile, 0).single()
            assertEquals(payload, held.payloadJson); assertEquals(hash, held.payloadHash); assertEquals("legacy", held.transport)
            assertEquals("legacy_or_incomplete", database.learning().historyCoverage(profile, 0)!!.origin)
            assertTrue(database.learning().historyCoverage(profile, 0)!!.historyGap)
            assertNull(LearningStore(database).restoreDialogue(scope(owner), lesson))
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM owner_journal").use { it.moveToFirst(); assertEquals(1, it.getInt(0)) }
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun lateHintFactIsDurableAndDoesNotRewindAdvancedLessonPhase() = runBlocking {
        val name = "dialogue-${UUID.randomUUID()}.db"; var database = open(name)
        try {
            val owner = database.learning().provisionalOwner(); val scope = scope(owner); val lesson = UUID.randomUUID()
            val input = LessonInput(lesson, practice(), LessonInputPhase.QUESTION_WAIT, HelpSnapshot(), null, DialogueProgress.newLesson())
            database.learning().createLesson(scope, input)
            val advanced = "{\"phase\":\"completed\",\"pending_feedback_attempt_id\":\"unchanged\"}"
            database.openHelper.writableDatabase.execSQL("UPDATE learning_record SET payloadJson = ?, version = 2 WHERE recordId = ?", arrayOf(advanced, lesson.toString()))
            val fact = DialogueProgressEvent(UUID.randomUUID(), DialogueProgressObservation(lesson, true, UUID.randomUUID(), "actual-method-output-end"))
            assertTrue(database.learning().persistHintCompletion(scope, fact))
            assertFalse(database.learning().persistHintCompletion(scope, fact))
            assertTrue(LearningStore(database).restoreDialogue(scope, lesson)!!.hintMethodAnnounced)
            assertEquals(advanced, database.learning().record(owner.profileId, 0, lesson.toString())!!.payloadJson)
            assertEquals(1, database.learning().pending(owner.profileId, 0).size)
            database.close(); database = open(name)
            assertTrue(LearningStore(database).restoreDialogue(scope, lesson)!!.hintMethodAnnounced)
            database.learning().putOwner(owner.copy(privacyEpoch = 1, revision = 8))
            var rejected = false
            try { database.learning().persistHintCompletion(scope, fact) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            rejected = false
            try { database.learning().persistHintCompletion(scope.copy(privacyEpoch = 1, revision = 8), fact) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected); assertTrue(database.learning().pending(owner.profileId, 1).isEmpty())
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun registeredTicketAndImmutableObservationSurviveRestartWithoutGrantingNewOutput() = runBlocking {
        val name = "presentation-${UUID.randomUUID()}.db"; var database = open(name)
        try {
            val owner = database.learning().provisionalOwner(); val scope = scope(owner); val content = practice()
            val profile = ProfileContext(scope.profileId, 0); val presentation = UUID.randomUUID()
            val support = SupportArtifact("approved:example", HelpKind.COMPLETE_EXAMPLE, SupportRole.COMPLETE_EXAMPLE, SupportLanguage.EN,
                "The city opened a library.", "a".repeat(64), SupportOrigin.PUBLISHED_TASK, null)
            val exposure = QuestionExampleExposure(content.questionRef, ExposureStatus.UNKNOWN, ExposureBasis.UNRESOLVED_PRESENTATION, 1, 1, null)
            val ticket = PracticePresentationTicket(UUID.randomUUID(), profile, scope,
                PracticePresentationRecord(presentation, content, null, InteractionChannel.PARKED, listOf(support), 1,
                    Instant.parse("2026-11-12T00:00:00Z"), PresentationStatus.PREPARED, null), exposure)
            assertTrue(database.learning().persistTicket(scope, ticket))
            val observation = PracticePresentationObservation(UUID.randomUUID(), profile, UUID.randomUUID(), scope, UUID.randomUUID(),
                ObservationOutcome.PARTIAL_OR_UNKNOWN, ObservationMode.INDETERMINATE, "actual-partial-component")
            assertTrue(database.learning().persistObservation(scope, presentation, observation))
            assertFalse(database.learning().persistObservation(scope, presentation, observation))
            database.close(); database = open(name)
            assertNotNull(database.learning().ticket(owner.profileId, 0, presentation.toString()))
            assertNotNull(database.learning().observation(observation.observationId.toString()))
            assertEquals("presentation_observation", database.learning().pending(owner.profileId, 0).single().transport)
            assertEquals("partial_or_unknown", JSONObject(database.learning().observation(observation.observationId.toString())!!.payloadJson).getString("outcome"))
            var rejected = false
            try { database.learning().persistObservation(scope, presentation, observation.copy(adapterEvidenceRef = "changed")) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
        } finally { database.close(); context.deleteDatabase(name) }
    }
}

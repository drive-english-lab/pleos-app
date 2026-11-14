package io.github.driveenglishlab.pleos.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaPlaybackJournalTest {
    private lateinit var db: LearningDatabase
    private lateinit var journal: MediaPlaybackJournal
    private lateinit var point: WirePersonalCheckpointInput
    private val owner = OwnerContext(UUID.randomUUID(), 0, 1, UUID.randomUUID())
    private val run = UUID.randomUUID()
    @Before fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java).build()
        db.learning().putOwner(LocalOwner(profileId = owner.profileId.toString(), privacyEpoch = 0, revision = 1, status = "active"))
        journal = MediaPlaybackJournal(context, db)
        val value = contractFixture("NextArticleResponse") { it["status"] == JsonPrimitive("selected") }
            .getValue("checkpoint").jsonObject.getValue("payload").jsonObject
        point = learningJson.decodeFromJsonElement(WirePersonalCheckpointInput.serializer(),
            JsonObject(value.filterKeys { it !in setOf("active_run_id", "main_coverage_status") }))
    }
    @After fun close() { db.close() }

    @Test fun durableObservationsHaveOneSequenceAndDoNotInventServerCheckpointCas() = runBlocking {
        journal.persist(owner, 1, point.copy(positionMs = 500), run, PlayedInterval(0, 500))
        journal.persist(owner, 1, point.copy(positionMs = 1_000), run, PlayedInterval(500, 1_000))
        val rows = db.learning().pendingSync(owner.profileId.toString(), 0)
        assertEquals(2, rows.size)
        val values = rows.map { learningJson.parseToJsonElement(it.payloadJson).jsonObject }
        assertTrue(values.all { it["kind"] == JsonPrimitive("playback_observation") })
        assertEquals(listOf(1L, 2L), values.map { it.getValue("payload").jsonObject.getValue("sequence_no").jsonPrimitive.long })
        assertTrue(values.all { it.getValue("payload").jsonObject["status"] == JsonPrimitive("observed") })
        assertEquals(1_000L, journal.position(owner, point.checkpointId)!!.positionMs)
        assertNull(db.learning().record(owner.profileId.toString(), 0, stableRecordId("server-record:${point.checkpointId}").toString()))
    }

    @Test fun failureRollsBackObservationSequenceAndPositionTogether() = runBlocking {
        try {
            journal.persist(owner, 1, point.copy(positionMs = requireNotNull(point.durationMs) + 1), run, PlayedInterval(0, 500))
            fail("Out-of-range checkpoint must fail.")
        } catch (_: IllegalArgumentException) { }
        assertTrue(db.learning().pendingSync(owner.profileId.toString(), 0).isEmpty())
        assertNull(journal.position(owner, point.checkpointId))
        journal.persist(owner, 1, point.copy(positionMs = 500), run, PlayedInterval(0, 500))
        val row = db.learning().pendingSync(owner.profileId.toString(), 0).single()
        assertEquals(1L, learningJson.parseToJsonElement(row.payloadJson).jsonObject.getValue("payload").jsonObject.getValue("sequence_no").jsonPrimitive.long)
    }

    @Test fun changedOwnerCannotWriteOldLearningOrCopyItToNewProfile() = runBlocking {
        db.learning().putOwner(LocalOwner(profileId = UUID.randomUUID().toString(), privacyEpoch = 0, revision = 2, status = "active"))
        try { journal.persist(owner, 1, point, run, null); fail("Old media owner must be rejected.") }
        catch (_: IllegalArgumentException) { }
        assertNull(journal.position(owner, point.checkpointId))
        assertTrue(db.learning().pendingSync(owner.profileId.toString(), 0).isEmpty())
    }
}

package io.github.driveenglishlab.pleos.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.domain.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearningDatabaseTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun db(name: String) = Room.databaseBuilder(context, LearningDatabase::class.java, name).addMigrations(LEARNING_MIGRATION_1_2, LEARNING_MIGRATION_2_3).build()
    private fun answer(owner: LocalOwner): AcceptedAnswer {
        val content = ContentRef(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), ListeningMode.ORIGINAL_FULL)
        val lease = CaptureLease(UUID.randomUUID(), UUID.randomUUID(),
            OwnerContext(UUID.fromString(owner.profileId), owner.privacyEpoch, owner.revision, UUID.randomUUID()),
            PracticeContentRef(content, UUID.randomUUID(), UUID.randomUUID()), UUID.randomUUID(), 1, 1)
        return AcceptedAnswer(lease, "The city opened a public library.", Capture.unverified(CapturePath.PARKED_STT), HelpSnapshot(refs = setOf("meaning-actually-delivered")))
    }

    @Test fun provisionalProfileAndPendingAttemptSurviveDatabaseReopen() = runBlocking {
        val name = "learning-test-${UUID.randomUUID()}.db"
        var database = db(name)
        try {
            val owner = database.learning().provisionalOwner()
            val accepted = answer(owner)
            assertTrue(LearningStore(database).acceptAnswer(accepted, "first", null))
            database.close(); database = db(name)
            assertEquals(owner, database.learning().provisionalOwner())
            val record = database.learning().record(owner.profileId, 0, accepted.lease.attemptId.toString())
            assertNotNull(record); assertTrue(record!!.payloadJson.contains("public library"))
            assertTrue(record.payloadJson.contains("meaning-actually-delivered"))
            assertFalse(LearningStore(database).acceptAnswer(accepted, "first", null))
            assertEquals(1, database.learning().pending(owner.profileId, 0).size)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun immutableAttemptAndOwnerEpochAreEnforcedBeforeOutboxCommit() = runBlocking {
        val name = "learning-test-${UUID.randomUUID()}.db"; val database = db(name)
        try {
            val owner = database.learning().provisionalOwner(); val store = LearningStore(database); val accepted = answer(owner)
            store.acceptAnswer(accepted, "first", null)
            var rejected = false
            try { store.acceptAnswer(accepted.copy(text = "Changed payload"), "first", null) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            database.learning().putOwner(owner.copy(privacyEpoch = 1, revision = 2))
            rejected = false
            try { store.acceptAnswer(answer(owner), "first", null) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(1, database.learning().pending(owner.profileId, 0).size)
            assertTrue(database.learning().pending(owner.profileId, 1).isEmpty())
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun logoutJournalIsolationAndNewOwnerAreOneDurableTransition() = runBlocking {
        val name = "learning-test-${UUID.randomUUID()}.db"; var database = db(name)
        try {
            val old = database.learning().provisionalOwner(); val accepted = answer(old)
            LearningStore(database).acceptAnswer(accepted, "first", null)
            val newOwner = database.learning().detachForLogout(old.revision, UUID.randomUUID().toString(), "isolated-test-reference")
            assertNotEquals(old.profileId, newOwner.profileId)
            assertEquals(old.revision + 1, newOwner.revision)
            assertTrue(database.learning().pending(old.profileId, 0).isEmpty())
            database.close(); database = db(name)
            assertEquals(newOwner, database.learning().currentOwner())
            var rejected = false
            try { LearningStore(database).acceptAnswer(accepted, "first", null) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            // Isolation preserves the old record; it does not expose or transfer ownership.
            assertNotNull(database.learning().record(old.profileId, 0, accepted.lease.attemptId.toString()))
        } finally { database.close(); context.deleteDatabase(name) }
    }
}

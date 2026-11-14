package io.github.driveenglishlab.pleos.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.domain.OwnerContext
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceIntentDaoTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun scope(owner: LocalOwner) = OwnerContext(UUID.fromString(owner.profileId), owner.privacyEpoch, owner.revision, UUID.randomUUID())
    private fun original(owner: OwnerContext) = LocalPlaybackRequest(UUID.randomUUID().toString(), owner.profileId.toString(), owner.privacyEpoch,
        owner.revision, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "{\"original_local_window\":10000}", LearningJson.hash("{}"), "active", 0, null, null, 0, null, false)
    private fun outcome(version: Int, observed: String = "2026-11-15T01:00:00Z") =
        "{\"version\":$version,\"observed_at\":\"$observed\",\"state\":\"pending\",\"stream_version\":1,\"selection\":null,\"checkpoint\":null,\"context\":null,\"source_status\":[],\"no_candidate_reason\":null}"

    @Test fun originalAdmissionAndBindingCannotBeRebasedAndOutcomeVersionCannotMoveBackwards() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java).build()
        try {
            val owner = scope(db.learning().provisionalOwner()); val dao = db.serviceIntents(); val original = original(owner)
            assertEquals(original, dao.register(owner, original))
            val admitted = original.copy(polls = 1, admissionJson = "{\"original_admission_deadline\":10000}", outcomeJson = outcome(1), outcomeVersion = 1)
            assertTrue(dao.advance(owner, original, admitted))
            assertEquals(admitted, dao.register(owner, original)) // Redelivery never resets polls/deadline/outcome.
            var rejected = false
            try { dao.register(owner, original.copy(bindingJson = "{\"original_local_window\":20000}")) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            rejected = false
            try { dao.advance(owner, admitted, admitted.copy(admissionJson = "{\"original_admission_deadline\":20000}")) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            val observedLater = admitted.copy(outcomeJson = outcome(1, "2026-11-15T01:00:01Z"))
            assertTrue(dao.advance(owner, admitted, observedLater))
            rejected = false
            try { dao.advance(owner, observedLater, observedLater.copy(outcomeJson = outcome(1))) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(observedLater, dao.intent(original.operationId))
        } finally { db.close() }
    }

    @Test fun durableCancelWinsLateOutcomeAndCannotBeChangedIntoActiveOrClaimedAgain() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java).build()
        try {
            val owner = scope(db.learning().provisionalOwner()); val dao = db.serviceIntents(); val original = original(owner)
            dao.register(owner, original)
            val cancelled = original.copy(state = "cancelled")
            assertTrue(dao.advance(owner, original, cancelled))
            val late = cancelled.copy(admissionJson = "original receipt", outcomeVersion = 2, outcomeJson = outcome(2))
            assertTrue(dao.advance(owner, cancelled, late))
            var rejected = false
            try { dao.advance(owner, late, late.copy(state = "active")) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            rejected = false
            try { dao.advance(owner, late, late.copy(state = "applied", activationClaimId = UUID.randomUUID().toString())) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            assertEquals(late, dao.intent(original.operationId))
            // A response based on the pre-cancel row loses CAS rather than replacing cancellation.
            assertFalse(dao.advance(owner, original, original.copy(polls = 1)))
        } finally { db.close() }
    }

    @Test fun isolatedIntentNeverReentersTheNewOwnerAndConfirmedDeleteRemovesOnlyItsEpoch() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java).build()
        try {
            val local = db.learning().provisionalOwner(); val owner = scope(local); val dao = db.serviceIntents(); val old = original(owner)
            dao.register(owner, old)
            val payload = "{\"actual_snapshot\":true}"
            dao.saveCatalog(owner, LocalCatalogViewPage(old.profileId, old.privacyEpoch, old.ownerRevision, "interests", "", UUID.randomUUID().toString(), "", 1, payload, LearningJson.hash(payload)))
            db.withTransaction {
                FeatureLifecycle(db).isolateForOwnerTransition(owner.profileId)
                db.learning().putOwner(local.copy(privacyEpoch = 1, revision = local.revision + 1))
            }
            assertTrue(requireNotNull(dao.intent(old.operationId)).isolated)
            assertNull(dao.latestCatalog(old.profileId, old.privacyEpoch, "interests", ""))
            val current = owner.copy(privacyEpoch = 1, revision = owner.revision + 1)
            val kept = original(current)
            dao.register(current, kept)
            var rejected = false
            try { dao.register(current, old.copy(privacyEpoch = 1, ownerRevision = current.revision)) } catch (_: IllegalArgumentException) { rejected = true }
            assertTrue(rejected)
            db.withTransaction { FeatureLifecycle(db).clearConfirmedEpoch(owner.profileId, 0) }
            assertNull(dao.intent(old.operationId)); assertEquals(kept, dao.intent(kept.operationId))
        } finally { db.close() }
    }
}

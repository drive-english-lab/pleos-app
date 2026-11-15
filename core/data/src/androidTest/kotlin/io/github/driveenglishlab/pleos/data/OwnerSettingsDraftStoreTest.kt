package io.github.driveenglishlab.pleos.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.domain.LearningBand
import io.github.driveenglishlab.pleos.domain.OwnerContext
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OwnerSettingsDraftStoreTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    // Whitespace and key order are deliberately noncanonical: the baseline must remain exact.
    private val original="""{ "topics":["science"], "listening_mode":"original_full", "followup_policy":"none", "explicit_fields":["topics","listening_mode","followup_policy"], "onboarding_complete":true, "version":7 }"""
    private fun owner(value:LocalOwner)=OwnerContext(UUID.fromString(value.profileId),value.privacyEpoch,value.revision,UUID.randomUUID())
    private fun snapshot()=OwnerSettingsDraftSnapshot(original,setOf("technology","economy"),"briefing","ko_summary",
        LearningBand.BASIC,LearningBand.INTERMEDIATE,"system","dark",OwnerDraftField.entries.toSet(),12,
        OwnerDraftState.PRESERVED,OwnerDraftLeaveState.FORCED_CLOSED)

    @Test fun forcedCloseSnapshotSurvivesDatabaseReopenWithoutRestoringExecutionAuthority()=runBlocking {
        val name="owner-draft-${UUID.randomUUID()}.db"
        var db=Room.databaseBuilder(context,LearningDatabase::class.java,name).build()
        try {
            val local=db.learning().provisionalOwner();val initial=owner(local)
            val saved=OwnerSettingsDraftStore(context,db.review()).save(initial,null,snapshot())
            db.close()
            db=Room.databaseBuilder(context,LearningDatabase::class.java,name).build()
            val current=owner(db.learning().provisionalOwner())
            val restored=requireNotNull(OwnerSettingsDraftStore(context,db.review()).load(current))
            assertEquals(saved.snapshot,restored.snapshot)
            assertEquals(saved.storageRevision,restored.storageRevision)
            assertEquals(current,restored.owner)
            val row=requireNotNull(db.review().draft(local.profileId,local.privacyEpoch))
            assertEquals(original,row.originalJson);assertEquals(7L,row.baseSettingsVersion)
            assertNull(row.previewToken);assertNull(row.savedOperationId)
        } finally { db.close();context.deleteDatabase(name) }
    }

    @Test fun confirmedLearningDeletionPreservesOrdinaryDraftAndRebindsItToCurrentEpoch()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,LearningDatabase::class.java).build()
        try {
            val local=db.learning().provisionalOwner();val old=owner(local);val store=OwnerSettingsDraftStore(context,db.review())
            val saved=store.save(old,null,snapshot())
            // A genuine preview token from the previous implementation is never restored.
            db.review().changeDraft(old,saved.storageRevision,requireNotNull(db.review().draft(local.profileId,old.privacyEpoch)).draftJson,"old-preview",null)
            db.withTransaction {
                FeatureLifecycle(db).isolateForOwnerTransition(old.profileId)
                FeatureLifecycle(db).clearConfirmedEpoch(old.profileId,old.privacyEpoch)
                db.learning().putOwner(local.copy(privacyEpoch=old.privacyEpoch+1,revision=old.revision+1))
            }
            val current=old.copy(privacyEpoch=old.privacyEpoch+1,revision=old.revision+1,presentationToken=UUID.randomUUID())
            val restored=requireNotNull(store.load(current))
            assertEquals(saved.snapshot,restored.snapshot)
            assertTrue(restored.storageRevision>saved.storageRevision)
            val row=requireNotNull(db.review().draft(local.profileId,current.privacyEpoch))
            assertEquals(current.revision,row.ownerRevision);assertEquals(original,row.originalJson)
            assertNull(row.previewToken);assertNull(row.savedOperationId)
            assertEquals(restored.storageRevision,requireNotNull(store.load(current)).storageRevision)
            assertTrue(store.discard(current))
            assertNull(db.review().draft(local.profileId,old.privacyEpoch));assertNull(store.load(current))
        } finally { db.close() }
    }

    @Test fun differentProfileNeverInheritsDraftAndStaleOwnersAndRevisionsCannotOverwriteIt()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,LearningDatabase::class.java).build()
        try {
            val local=db.learning().provisionalOwner();val first=owner(local);val store=OwnerSettingsDraftStore(context,db.review())
            val saved=store.save(first,null,snapshot())
            val nextLocal=local.copy(profileId=UUID.randomUUID().toString(),privacyEpoch=0,revision=local.revision+1)
            db.withTransaction { FeatureLifecycle(db).isolateForOwnerTransition(first.profileId);db.learning().putOwner(nextLocal) }
            val second=owner(nextLocal)
            assertNull(store.load(second));assertFalse(store.discard(second))
            var blocked=false
            try { store.save(first,saved.storageRevision,snapshot()) } catch(_:IllegalArgumentException) { blocked=true }
            assertTrue(blocked)
            val different=snapshot().copy(topics=emptySet(),appearance="light",localRevision=20)
            val own=store.save(second,null,different)
            val changed=store.save(second,own.storageRevision,different.copy(localRevision=21))
            blocked=false
            try { store.save(second,own.storageRevision,different) } catch(e:OwnerSettingsDraftException) { blocked=e.code=="DRAFT_CONFLICT" }
            assertTrue(blocked);assertEquals(changed,store.load(second))
            val returned=local.copy(revision=nextLocal.revision+1)
            db.learning().putOwner(returned)
            assertEquals(saved.snapshot,requireNotNull(store.load(owner(returned))).snapshot)
        } finally { db.close() }
    }

    @Test fun unsupportedDraftKindsRemainUntouchedAndOwnedMalformedEnvelopeFailsClosed()=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,LearningDatabase::class.java).build()
        try {
            val current=owner(db.learning().provisionalOwner());val dao=db.review();val store=OwnerSettingsDraftStore(context,dao)
            val legacy=LocalSettingsDraft(current.profileId.toString(),current.privacyEpoch,current.revision,1,7,original,original,null,null)
            dao.openDraft(current,legacy)
            assertNull(store.load(current));assertFalse(store.discard(current))
            var rejected=false
            try { store.save(current,null,snapshot()) } catch(e:OwnerSettingsDraftException) { rejected=e.code=="OTHER_DRAFT_KIND" }
            assertTrue(rejected);assertEquals(legacy,dao.draft(legacy.profileId,legacy.privacyEpoch))
            dao.changeDraft(current,1,"""{"kind":"owner_settings_draft_v1","version":1,"unexpected":true}""",null,null)
            rejected=false
            try { store.load(current) } catch(e:OwnerSettingsDraftException) { rejected=e.code=="INVALID_DRAFT" }
            assertTrue(rejected)
            dao.closeDraft(current,2)
            rejected=false
            try { store.save(current,null,snapshot().copy(localRevision=0)) } catch(e:OwnerSettingsDraftException) { rejected=e.code=="INVALID_DRAFT" }
            assertTrue(rejected)
            rejected=false
            try { store.save(current,null,snapshot().copy(dirtyFields=emptySet())) } catch(e:OwnerSettingsDraftException) { rejected=e.code=="INVALID_DIRTY_FIELDS" }
            assertTrue(rejected);assertNull(store.load(current))
        } finally { db.close() }
    }
}

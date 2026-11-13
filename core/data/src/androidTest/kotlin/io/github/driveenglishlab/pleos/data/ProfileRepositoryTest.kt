package io.github.driveenglishlab.pleos.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.*
import io.github.jan.supabase.auth.user.UserSession
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileRepositoryTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java).build()
    private val dao get() = db.learning()
    private class Auth : ProfileAuth {
        var session: LearningSession? = null; var creations = 0; var unavailable = false
        val operation = UUID.randomUUID()
        override suspend fun ensureSession(allowCreate: Boolean, beforeCreate: suspend () -> Unit): LearningSession {
            check(!unavailable)
            if (session == null) { check(allowCreate); beforeCreate(); creations++; session = LearningSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "synthetic-session-token") }
            return session!!
        }
        override suspend fun currentSession() = session
        override suspend fun bootstrapOperation(profileId: UUID) = operation
    }
    private fun profile(owner: LocalOwner, epoch: Long = owner.privacyEpoch, version: Long = 1) = buildJsonObject {
        put("schema_version", "5"); putJsonObject("profile_context") { put("profile_id", owner.profileId); put("privacy_epoch", epoch) }
        putJsonObject("settings") { put("topics", JsonArray(emptyList())); put("listening_mode", "original_full"); put("followup_policy", JsonNull)
            put("explicit_fields", JsonArray(emptyList())); put("onboarding_complete", false); put("version", version) }
        put("active_resume", JsonNull); put("version", version); put("resume_version", 1); put("profile_kind", "anonymous")
        putJsonObject("history_coverage") { put("origin", "known_new_profile"); put("version", 1); put("history_gap", false) }
    }
    private fun repo(auth: Auth, exchange: suspend (String, ByteArray?) -> LearningHttpResponse) = ProfileRepository(db, auth) { sessions ->
        LearningApi(sessions, LearningContractJson(context), object : LearningHttpTransport {
            override suspend fun exchange(method: String, path: String, query: Map<String, String>, headers: Map<String, String>, body: ByteArray?) = exchange(path, body)
        })
    }
    @After fun close() { db.close() }

    @Test fun bootstrapAndRestartKeepTheProvisionalOwnerAndUnsentBytesExactlyOnce() = runBlocking {
        val local = dao.provisionalOwner(); val auth = Auth(); val bodies = mutableListOf<String>()
        val outbox = OutboxRecord(UUID.randomUUID().toString(), local.profileId, 0, 1, UUID.randomUUID().toString(), "immutable-local-payload", "synthetic-hash")
        dao.insertOutbox(outbox)
        fun repository() = repo(auth) { path, bytes -> if (path.endsWith("bootstrap")) bodies += bytes!!.toString(Charsets.UTF_8); jsonResponse(profile(local)) }
        val result = repository().connect(); assertTrue(result is LearningReply.Stored)
        assertEquals(local.copy(status = "active"), dao.currentOwner()); assertEquals(outbox, dao.outboxEvent(outbox.eventId))
        assertTrue(repository().connect() is LearningReply.Stored); assertEquals(1, auth.creations)
        assertEquals(2, bodies.size); assertEquals(bodies[0], bodies[1])
        assertEquals("known_new_profile", dao.historyCoverage(local.profileId, 0)!!.origin)
    }

    @Test fun restorationFailureKeepsExistingOwnerAndDoesNotSignUpOrSendRequests() = runBlocking {
        val local = dao.provisionalOwner().copy(status = "active"); dao.putOwner(local)
        val auth = Auth().apply { unavailable = true }
        val repository = repo(auth) { _, _ -> throw AssertionError("No request is authorized.") }
        assertEquals(LearningReply.Failed("SESSION_RECOVERY_REQUIRED", true), repository.connect())
        assertEquals(local, dao.currentOwner()); assertEquals(0, auth.creations)
    }

    @Test fun lostCredentialFileAfterCreationCannotCreateASecondGuestEvenForProvisionalOwner() = runBlocking {
        val local = dao.provisionalOwner()
        dao.insertJournal(OwnerJournal(UUID.randomUUID().toString(), local.profileId, local.revision, "auth_creation", "started", null))
        val auth = Auth(); val repository = repo(auth) { _, _ -> throw AssertionError("No new guest allowed.") }
        assertEquals(LearningReply.Failed("SESSION_RECOVERY_REQUIRED", true), repository.connect())
        assertEquals(0, auth.creations); assertEquals(local, dao.currentOwner())
    }

    @Test fun bootstrapFailureThenRetryUsesSameProfileOperationAndSession() = runBlocking {
        val local = dao.provisionalOwner(); val auth = Auth(); val bodies = mutableListOf<String>(); var offline = true
        val repository = repo(auth) { path, bytes ->
            if (path.endsWith("bootstrap")) { bodies += bytes!!.toString(Charsets.UTF_8); if (offline) throw java.io.IOException() }
            jsonResponse(profile(local))
        }
        assertTrue(repository.connect() is LearningReply.Failed); assertEquals(local, dao.currentOwner())
        offline = false; assertTrue(repository.connect() is LearningReply.Stored)
        assertEquals(1, auth.creations); assertEquals(bodies[0], bodies[1])
    }

    @Test fun anotherServerProfileRequiresExplicitTransitionAndCannotRelabelLocalRecords() = runBlocking {
        val local = dao.provisionalOwner(); val auth = Auth()
        val repository = repo(auth) { _, _ -> jsonResponse(profile(local.copy(profileId = UUID.randomUUID().toString()))) }
        assertEquals(LearningReply.Failed("PROFILE_MERGE_REQUIRED", false), repository.connect()); assertEquals(local, dao.currentOwner())
    }

    @Test fun settingsRetryRetainsOriginalCommandAndRejectsReplacementWhileUnacknowledged() = runBlocking {
        val local = dao.provisionalOwner(); val auth = Auth(); var offline = true; val sent = mutableListOf<String>()
        val repository = repo(auth) { path, bytes ->
            if (path.endsWith("preferences")) { sent += bytes!!.toString(Charsets.UTF_8); if (offline) throw java.io.IOException(); jsonResponse(profile(local, version = 2)) }
            else jsonResponse(profile(local))
        }
        assertTrue(repository.connect() is LearningReply.Stored)
        val request = WirePersonalSettingsPatch(UUID.randomUUID().toString(), WireCommonProfileContext(local.profileId, 0), 1,
            WirePersonalSettingsPatchSet(followupPolicy = "none"), true, "5")
        assertTrue(repository.updateSettings(request) is LearningReply.Failed)
        val pending = dao.pendingProfileSettings(local.profileId)!!
        assertEquals(LearningReply.Failed("SETTINGS_PENDING", false), repository.updateSettings(request.copy(operationId = UUID.randomUUID().toString())))
        offline = false; assertTrue(repository.retrySettings() is LearningReply.Stored)
        assertEquals(2, sent.size); assertEquals(sent[0], sent[1]); assertEquals(pending.payloadJson, sent[1])
        assertNull(dao.pendingProfileSettings(local.profileId))
    }

    @Test fun serverEpochAdvanceDiscardsOldLearningAndPreservesIndependentPreferences() = runBlocking {
        val local = dao.provisionalOwner(); val auth = Auth(); var epoch = 0L
        val repository = repo(auth) { _, _ -> jsonResponse(profile(local, epoch, epoch + 1)) }
        assertTrue(repository.connect() is LearningReply.Stored)
        dao.insertRecord(LearningRecord(local.profileId, 0, UUID.randomUUID().toString(), "attempt", "old-private-answer", 1))
        val event = OutboxRecord(UUID.randomUUID().toString(), local.profileId, 0, 1, UUID.randomUUID().toString(), "old-private-answer", "synthetic")
        dao.insertOutbox(event)
        db.openHelper.writableDatabase.execSQL("INSERT INTO daily_preference_cache VALUES (?, ?, ?)", arrayOf<Any>(local.profileId, 1, "independent-settings"))
        epoch = 1
        val refreshed = repository.refresh(); assertTrue(refreshed is LearningReply.Stored)
        assertEquals(1L, dao.currentOwner()!!.privacyEpoch); assertEquals(2L, dao.currentOwner()!!.revision)
        assertNull(dao.outboxEvent(event.eventId)); assertTrue(dao.pending(local.profileId, 0).isEmpty())
        db.openHelper.readableDatabase.query("SELECT payloadJson FROM daily_preference_cache").use { assertTrue(it.moveToFirst()); assertEquals("independent-settings", it.getString(0)) }
    }

    @Test fun keystoreCiphertextRestoresCredentialsAndCorruptionNeverBecomesANewGuest() = runBlocking {
        val name = "test-auth-" + UUID.randomUUID(); val file = File(context.noBackupFilesDir, "$name.enc")
        try {
            val store = AuthSessionStore(context, name); val identity = store.identity(); store.beginCreation()
            val session = UserSession("synthetic-access-secret", "synthetic-refresh-secret", expiresIn = 3600, tokenType = "bearer")
            store.saveSession(session)
            val bytes = file.readBytes(); assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("synthetic-refresh-secret"))
            val restored = AuthSessionStore(context, name); assertEquals(identity.installationId, restored.identity().installationId)
            assertEquals(session, restored.loadSession()); assertTrue(restored.identity().creationStarted)
            file.writeBytes(bytes.apply { this[lastIndex] = (last().toInt() xor 1).toByte() })
            var failed = false; try { AuthSessionStore(context, name).identity() } catch (_: Exception) { failed = true }
            assertTrue(failed); assertTrue(file.exists())
        } finally {
            file.delete(); File(file.path + ".bak").delete()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("${context.packageName}.$name") }
        }
    }
}

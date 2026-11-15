package io.github.driveenglishlab.pleos.data

import android.os.Bundle
import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.driveenglishlab.pleos.data.wire.*
import io.github.driveenglishlab.pleos.domain.LearningReply
import java.io.File
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real SDK -> JWT/API -> DB. Host runner removes the owned anonymous Auth user. */
@RunWith(AndroidJUnit4::class)
class ProfileRuntimeTest {
    @Test fun anonymousSdkRestoresTheSameProfileAndUpdatesRealSettings() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("liveProfileRuntime") == "true")
        check(BuildConfig.SUPABASE_URL == "https://odfhxyelkwdaekoxhzwt.supabase.co")
        val instrumentation = InstrumentationRegistry.getInstrumentation(); val base = instrumentation.targetContext
        val directory = File(base.cacheDir, "live-r6-profile-${UUID.randomUUID()}").apply { mkdirs() }
        val packageId = "${base.packageName}.r6fixture.${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = directory
            override fun getPackageName() = packageId
        }
        val store = AuthSessionStore(context)
        val db = Room.inMemoryDatabaseBuilder(context, LearningDatabase::class.java).build()
        var auth = SupabaseProfileAuth(store)
        fun repository() = ProfileRepository(db, auth) { sessions -> LearningApi(context, "http://127.0.0.1:8008", sessions, allowLocalHttp = true) }
        try {
            assertTrue("Real public catalog read failed.", CatalogRepository(context).catalogPage() is io.github.driveenglishlab.pleos.domain.CatalogRead.Stored)
            val local = db.learning().provisionalOwner()
            val currentScope = CurrentAccountScope(context, db)
            assertNull("Untouched anonymous bootstrap must remain current.", currentScope.recoveryHold())
            val first = repository().connect(); assertTrue("Real SDK/bootstrap failed.", first is LearningReply.Stored)
            first as LearningReply.Stored
            assertEquals(local.profileId, first.value.profileContext.profileId)
            assertEquals("active", db.learning().currentOwner()!!.status)
            val subject = auth.currentSession()!!.subjectId
            assertNull(currentScope.recoveryHold())
            auth.close(); auth = SupabaseProfileAuth(AuthSessionStore(context))
            val restored = repository(); val second = restored.connect()
            assertTrue("Protected SDK session restore failed.", second is LearningReply.Stored)
            assertEquals(subject, auth.currentSession()!!.subjectId)
            assertEquals(first.value.profileContext, (second as LearningReply.Stored).value.profileContext)
            assertNull("R6 must preserve the actual same-install protected session.", currentScope.recoveryHold())
            assertTrue(currentScope.permitsProtectedOperation(requireNotNull(restored.current()).owner))
            val request = WirePersonalSettingsPatch(UUID.randomUUID().toString(), second.value.profileContext,
                second.value.settings.version, WirePersonalSettingsPatchSet(followupPolicy = "none"), true, "5")
            val saved = restored.updateSettings(request); assertTrue("Real settings update failed.", saved is LearningReply.Stored)
            assertTrue((saved as LearningReply.Stored).value.settings.onboardingComplete)
            assertEquals("none", saved.value.settings.followupPolicy)
            assertTrue(restored.refresh() is LearningReply.Stored)
            assertNull(currentScope.pendingAccountAction())
            assertTrue(currentScope.permitsProtectedOperation(requireNotNull(restored.current()).owner))
        } finally {
            // Only a UUID is reported. Tokens/headers/session payloads never enter instrumentation output.
            store.identity().session?.user?.id?.let {
                instrumentation.sendStatus(2, Bundle().apply { putString("live_profile_test_user", UUID.fromString(it).toString()) })
            }
            auth.close(); db.close()
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null); aliases().toList().filter { it.startsWith("$packageId.") }.forEach(::deleteEntry)
            }
        }
    }
}
